package com.bookreader.core.engine

import com.bookreader.core.layout.ApproximateBreaker
import com.bookreader.core.layout.LineBreaker
import com.bookreader.core.layout.PageComposer
import com.bookreader.core.model.BookPosition
import com.bookreader.core.model.Chapter
import com.bookreader.core.model.Page
import com.bookreader.core.model.PageStyle
import com.bookreader.core.storage.Book
import com.bookreader.core.text.ParagraphCursor

/**
 * 阅读控制器：UI 只跟它打交道。
 *
 * 不变量：位置一律用 [BookPosition.absoluteOffset] 表达；页码是当前 [PageStyle] 下的派生值，
 * 换字体/换屏幕后重新分页并按 offset 找回，绝不把页码当持久状态。
 */
class ReaderEngine(
    val book: Book,
    private val breakerFactory: (PageStyle) -> LineBreaker,
    initialStyle: PageStyle,
    private val maxCachedPages: Int = 24,
    private val maxCachedChapters: Int = 3,
) : AutoCloseable {

    var style: PageStyle = initialStyle
        private set

    private var composer: PageComposer = newComposer(initialStyle)
    private var pagesComposedTotal = 0L

    private val paginations = object : LinkedHashMap<Int, ChapterPagination>(8, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Int, ChapterPagination>?): Boolean =
            size > maxCachedChapters
    }

    private var chapterIndex = 0
    private var pageIndex = 0

    /**
     * 用户正在看的那一行（字符偏移）。页码只是当前样式下的派生值，这条才是权威：
     * "上下"那一档每 250ms 把屏幕首行提交进来，按页头收的话每提交一次退半页（量过：2486→2260→1298→687）。
     */
    var cursor: Long = 0L
        private set

    val chapterCount: Int get() = book.chapters.size
    val totalChars: Long get() = book.totalChars
    val chapter: Chapter get() = book.chapters[chapterIndex]
    val currentChapterIndex: Int get() = chapterIndex
    val currentPageIndex: Int get() = pageIndex

    @Synchronized
    fun openChapter(index: Int, offset: Long = -1L): Page? {
        require(index in 0 until chapterCount) { "章节号越界: $index" }
        chapterIndex = index
        val p = pagination(index)
        pageIndex = if (offset >= 0L) p.indexOfOffset(offset) else 0
        cursor = if (offset >= 0L) offset else currentPage()?.startOffset ?: book.chapters[index].startOffset
        // 引擎只交整页，绝不把裁短的页写回缓存：写回过一次，后面重建滚动窗口就拿到一张少了开头几行的页
        // （量过：改一次字号，按页那一档整屏只剩 7 行、底下空 1774px；上下那一档正文里出现 349px/1475px 的空洞）。
        // "从某一行开头"是滚动窗口自己的事，它按 cursor 裁首屏第一页，见 [trimmedFrom]。
        return currentPage()
    }

    @Synchronized
    fun currentPage(): Page? = pagination(chapterIndex).pageAt(pageIndex)

    /**
     * 切到按页那一档时，屏幕上第一行是页首而不是游标那一行，所以游标要跟到页首 ——
     * 否则切走再切回"上下"，起锚的那一行和刚才看到的不是同一行（量过：整屏往上跳最多 38 行）。
     */
    @Synchronized
    fun snapToPageTop(): Page? = currentPage()?.also { cursor = it.startOffset }

    /** 滚动模式要按章内页号随机取页，但不动阅读游标。 */
    @Synchronized
    fun pageAtInChapter(index: Int): Page? =
        if (index < 0 || index >= pagesInChapter()) null else pagination(chapterIndex).pageAt(index)

    /**
     * 某一章从 [from] 页起的至多 [count] 页，给"上下滚动"的窗口用。只读：不改 chapterIndex /
     * pageIndex，所以滚掉缓冲不会把用户正读着的位置改掉。
     *
     * 按段取而不是整章取：滚动文档的总高度有硬上限（Compose 的 Constraints 装不下无限高的布局），
     * 一次排整章在 20 万字的章上会排出上百页。取段只排要看的这几页。
     * 返回不足 [count] 页就说明这一章到末尾了 —— 窗口靠这个信号决定要不要接下一章。
     */
    @Synchronized
    fun chapterPages(index: Int, from: Int, count: Int): List<Page> {
        if (index < 0 || index >= chapterCount || from < 0 || count <= 0) return emptyList()
        val p = pagination(index)
        val out = ArrayList<Page>(count)
        var i = from
        while (out.size < count) {
            val page = p.pageAt(i) ?: break
            out += page
            i++
        }
        return out
    }

    /** 这一章一共几页。要把章尾接上前一章，只能先把整章排完，所以这是整章代价，窗口只在跨章界时调一次。 */
    @Synchronized
    fun pageCount(index: Int): Int =
        if (index < 0 || index >= chapterCount) 0 else pagination(index).totalPages()

    @Synchronized
    fun nextPage(): Page? {
        val p = pagination(chapterIndex)
        val candidate = pageIndex + 1
        if (p.pageAt(candidate) != null) {
            pageIndex = candidate
        } else if (chapterIndex + 1 < chapterCount) {
            chapterIndex++
            pageIndex = 0
        } else {
            return null
        }
        return currentPage()?.also { cursor = it.startOffset }
    }

    @Synchronized
    fun prevPage(): Page? {
        if (pageIndex > 0) {
            pageIndex--
        } else if (chapterIndex > 0) {
            chapterIndex--
            val p = pagination(chapterIndex)
            pageIndex = (p.totalPages() - 1).coerceAtLeast(0)
        } else {
            return null
        }
        return currentPage()?.also { cursor = it.startOffset }
    }

    @Synchronized
    fun jumpToChapter(index: Int): Page? = openChapter(index)

    /** 搜索/书签跳到这里：二分定位章节，再按偏移找回所在页。 */
    @Synchronized
    fun jumpToOffset(offset: Long): Page? =
        openChapter(book.chapters.indexOf(offset.coerceIn(0L, (totalChars - 1).coerceAtLeast(0L))), offset)

    /**
     * 换样式：整章重新分页，再按 [cursor] 找回所在页。交出去的还是整页 ——
     * "屏幕上第一行还是刚才那一行"由滚动的窗口自己裁首屏，引擎一裁就把整页裁短了。
     */
    @Synchronized
    fun applyStyle(newStyle: PageStyle): Page? {
        val keep = cursor
        style = newStyle
        composer = newComposer(newStyle)
        paginations.clear()
        pageIndex = pagination(chapterIndex).indexOfOffset(keep)
        return currentPage()
    }

    /** 某个偏移所在段落的起止：长按选段要整段选中，不能只选到屏幕上那一行。 */
    @Synchronized
    fun paragraphRange(offset: Long): LongRange {
        val clamped = offset.coerceIn(0L, (totalChars - 1).coerceAtLeast(0L))
        val c = ParagraphCursor(book.cache)
        val start = c.startAtOrBefore(clamped)
        val p = c.paragraphAt(start)
        // `p.end` 就是段末那个换行的位置，所以最后一个真字符在 `p.end - 1`。
        // 原来写的是 `until (p.end - 1)`（= 到 p.end-2），把句号也削掉了：
        // 表现是听书/词典取到的那段少结尾一个字，而且折行的末行盖不上底色
        return start until p.end.coerceAtLeast(start + 1)
    }

    /** 这一段有没有正文（原文里段与段之间保留的空行会被 `paragraphRange` 认成"一行空段"）。 */
    private fun LongRange.hasText(): Boolean =
        book.cache.slice(first, (last - first + 1).toInt()).isNotBlank()

    /**
     * 这一段之后的那一段，跳过保留的空行；到书末返回 null。听书浮窗的「下一段」用。
     * 起点是 `after.last + 2` 而不是 +1：+1 落在那个换行上，`startAtOrBefore` 会把它算回**当前**这一段，
     * 于是永远找不到下一段（每一轮都原地踏步，只能靠单调保护退出）。
     */
    @Synchronized
    fun nextParagraph(after: LongRange): LongRange? {
        var at = after.last + 2
        while (at < totalChars) {
            val r = paragraphRange(at)
            if (r.first > after.last && r.hasText()) return r
            if (r.last + 1 <= at) break
            at = r.last + 1
        }
        return null
    }

    /** 这一段之前的那一段，同样跳过空行；到书首返回 null。 */
    @Synchronized
    fun prevParagraph(before: LongRange): LongRange? {
        var at = before.first - 2
        while (at >= 0) {
            val r = paragraphRange(at)
            if (r.last < before.first && r.hasText()) return r
            if (r.first - 1 >= at) break
            at = r.first - 1
        }
        return null
    }

    @Synchronized
    fun pagesInChapter(): Int = pagination(chapterIndex).totalPages()

    @Synchronized
    fun warmAhead(pages: Int) {
        val p = pagination(chapterIndex)
        for (k in 1..pages) p.warmUp(pageIndex + k)
    }

    /** 段号是给人看的辅助锚点，权威仍然是 absoluteOffset。 */
    fun position(): BookPosition {
        val offset = cursor.takeIf { it in chapter.startOffset until totalChars }
            ?: currentPage()?.startOffset ?: chapter.startOffset
        val cursor = ParagraphCursor(book.cache)
        var start = chapter.startOffset
        var id = 0
        while (start < offset && id < MAX_PARAGRAPHS) {
            val para = cursor.paragraphAt(start)
            if (offset <= para.end) break
            val next = cursor.nextParagraphStart(para.end)
            if (next <= start) break
            start = next
            id++
        }
        return BookPosition(
            chapterIndex = chapterIndex,
            absoluteOffset = offset,
            paragraphId = id,
            charOffset = (offset - cursor.startAtOrBefore(offset)).toInt(),
        )
    }

    /**
     * 恢复位置：absoluteOffset 是权威，章节号只是它派生出来的记录。
     * 两者不一致时（重新入库过、章界变了、手改过存档）按偏移二分找章，不能信章号。
     */
    @Synchronized
    fun restore(pos: BookPosition): Page? = jumpToOffset(pos.absoluteOffset)

    /** 全书进度用字符偏移比例：它不随字号变化，章内页码才随字号变化。 */
    fun progress(): Float =
        ((currentPage()?.startOffset ?: chapter.startOffset).toFloat() / totalChars.toFloat()).coerceIn(0f, 1f)

    @Synchronized
    fun stats(): EngineStats = EngineStats(
        residentChunks = book.cache.residentChunks(),
        chunkHits = book.cache.hits,
        chunkMisses = book.cache.misses,
        decodedBytes = book.cache.decodedBytes,
        peakResidentChars = book.cache.peakResidentChars,
        pagesComposed = pagesComposedTotal,
        linesLaidOut = composer.linesLaidOut,
    )

    private fun newComposer(s: PageStyle): PageComposer = PageComposer(book.cache, breakerFactory(s), s, book.styles)

    @Synchronized
    private fun pagination(index: Int): ChapterPagination = paginations.getOrPut(index) {
        ChapterPagination(book.chapters[index], composer, maxCachedPages) { pagesComposedTotal++ }
    }

    override fun close() {
        paginations.clear()
        book.close()
    }

    companion object {
        private const val MAX_PARAGRAPHS = 200_000

        /** 没有设备时的默认测量器，Android 侧换成 Paint 实现。 */
        fun approximateBreaker(): (PageStyle) -> LineBreaker = {
            ApproximateBreaker(it)
        }
    }
}

data class EngineStats(
    val residentChunks: Int,
    val chunkHits: Long,
    val chunkMisses: Long,
    val decodedBytes: Long,
    val peakResidentChars: Long,
    val pagesComposed: Long,
    val linesLaidOut: Long,
) {
    val hitRatio: Float
        get() = if (chunkHits + chunkMisses == 0L) 0f else chunkHits.toFloat() / (chunkHits + chunkMisses).toFloat()
}
