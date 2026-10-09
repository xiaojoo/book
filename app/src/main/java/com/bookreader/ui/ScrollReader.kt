package com.bookreader.ui

import android.graphics.Paint
import android.graphics.Typeface
import android.os.SystemClock
import android.util.Log
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import com.bookreader.core.engine.ReaderEngine
import com.bookreader.core.model.Page
import com.bookreader.core.model.PageStyle
import com.bookreader.data.TraditionalText
import com.bookreader.core.model.TextLine
import kotlinx.coroutines.delay
import kotlin.math.roundToInt

/**
 * 上下滚动模式：把连续的页首尾接成一条等高的行长条来画，滚到章尾直接接下一章，不用停。
 *
 * 仍然不给每行建 Compose 节点 —— 整条文档只有一个 Canvas。但文档高度是"页数 × 页高"，
 * Compose 的 Constraints 把宽高打包进一个数，273,331px 就抛 `Can't represent a width of 0 and
 * height of 273331` 直接崩（真机上一直往下滚、攒够一百多章就撞上了）。所以窗口只留
 * [SAFE_DOC_PX] 像素那么高，两头都要剪：
 * 往下够不着了就补一段、剪掉最上面那段，并把滚动量同帧减去剪掉的高度 —— 内容上移和滚动上移互相
 * 抵消，画面一格都不跳；往上顶到头了就往前补一段、剪掉最下面那段（那段本来就在视野外，不用校正）。
 */
@Composable
fun ScrollReader(
    engine: ReaderEngine,
    style: PageStyle,
    theme: ReaderTheme,
    typeface: Typeface?,
    marginPx: Float,
    traditional: Boolean,
    bandTopPx: Float,
    bandBottomPx: Float,
    scroll: ScrollState,
    jumpToken: Int,
    onTap: (widthPx: Int, x: Float) -> Unit,
    onTopOffset: (Long) -> Unit,
    selection: LongRange?,
    selectionColor: Int,
    marks: List<LongRange>,
    markColor: Int,
    onLongPress: (Long) -> LongRange?,
    onSelectionRect: (topPx: Float, bottomPx: Float) -> Unit,
    modifier: Modifier = Modifier,
) {
    val density = LocalDensity.current
    val paint = readerPaint(theme, style.fontSizePx, typeface)
    val lineHeight = style.lineHeightPx
    // 上下两条缝由外层算好传进来（四档翻页模式共用同一个数，见 ReaderScreen）
    var blocks by remember(style.id, jumpToken) { mutableStateOf(listOf<Block>()) }
    /** 起锚时垫在锚上面的那段像素高；>=0 表示这一版窗口还没落位，首轮布局后把滚动量落到这条线上。 */
    var anchorPx by remember(style.id, jumpToken) { mutableIntStateOf(-1) }
    var viewport by remember { mutableIntStateOf(0) }
    /** 上一次提交位置和上一次交出去的偏移。用普通数组：写它不该触发重组。 */
    val lastCommit = remember { longArrayOf(0L) }
    val sent = remember { longArrayOf(-1L) }
    val textOf = remember(style.id, jumpToken) { mutableMapOf<Long, String>() }

    fun block(chapter: Int, from: Int, count: Int): Block =
        Block(chapter, from, engine.chapterPages(chapter, from, count))

    // 正文文本按页缓存，剪掉的那一段要一起放掉，否则滚完整本书会把读过的页全留在内存里
    fun evict(dropped: Block) {
        for (p in dropped.pages) textOf.remove(p.startOffset)
    }

    fun textOfPages(pages: List<Page>) {
        for (p in pages) if (!textOf.containsKey(p.startOffset)) {
            val raw = engine.book.cache.slice(p.startOffset, (p.endOffset - p.startOffset).toInt())
            textOf[p.startOffset] = if (traditional) TraditionalText.toSimplified(raw) else raw
        }
    }

    fun after(b: Block): Block? {
        val end = b.firstPage + b.pages.size
        if (b.pages.size == BLOCK_PAGES) {
            val same = block(b.chapter, end, BLOCK_PAGES)
            if (same.pages.isNotEmpty()) return same
        }
        var ch = b.chapter + 1
        var scanned = 0
        while (ch < engine.chapterCount && scanned < MAX_SKIP) {
            val next = block(ch, 0, BLOCK_PAGES)
            if (next.pages.isNotEmpty()) return next
            ch++
            scanned++
        }
        return null
    }

    fun before(b: Block): Block? {
        if (b.firstPage > 0) {
            val from = (b.firstPage - BLOCK_PAGES).coerceAtLeast(0)
            val same = block(b.chapter, from, b.firstPage - from)
            if (same.pages.isNotEmpty()) return same
        }
        var ch = b.chapter - 1
        var scanned = 0
        while (ch >= 0 && scanned < MAX_SKIP) {
            val total = engine.pageCount(ch)
            if (total > 0) {
                val from = (total - BLOCK_PAGES).coerceAtLeast(0)
                return Block(ch, from, engine.chapterPages(ch, from, total - from))
            }
            ch--
            scanned++
        }
        return null
    }

    // 起手以"当前页"为锚，锚上面这一段不在首屏里建（要往上翻时由 lookahead 那条路补，
    // 它补完会同帧把滚动量抬起来，画面不蹿）—— 首屏文档的第一页就是锚页，滚动量只要落到
    // 锚页里游标之前那几行的高度上，屏幕上第一行就是他在看的那一行。
    // 之前在这里先垫一段再靠 effect 去 scrollTo，第一帧画的是垫的那一段，切换时看着就是"界面刷新一遍"。
    if (blocks.isEmpty()) {
        val anchor = block(engine.currentChapterIndex, engine.currentPageIndex, BLOCK_PAGES).let {
            if (it.pages.isNotEmpty()) it else block(engine.currentChapterIndex, 0, BLOCK_PAGES)
        }
        if (anchor.pages.isNotEmpty()) {
            textOfPages(anchor.pages)
            // 锚页里排在游标之前的那几行不进文档也不行、进文档就不是"首屏第一行"：
            // 所以整页原样进文档，把这几行算进"上面垫了多少"，滚动量落到游标那一行上。
            // 之前是把锚页裁一份进文档，结果前一段到 60079 为止、锚段从 60662 起，
            // 中间 583 字在文档上凭空没了 —— 屏上就是一条比行距大两三倍的空隙。
            val head = headLines(anchor.pages.first().lines, engine.cursor)
            val list = ArrayList<Block>(2)
            list += anchor
            after(anchor)?.let { textOfPages(it.pages); list += it }
            blocks = list
            anchorPx = (head * lineHeight).roundToInt()
        }
    }

    val book = ScrollBook(blocks)
    // 提前一个半屏就开始接下一页段。coerceAtLeast 不是笔误：6 行是"至少看这么远"的下界，
    // 只留 6 行的话，滚到底前一刻才补，差一页高度就再也触发不了（top 已经等于 max，
    // 而 max 不会再变，effect 也就不会再跑）—— 那就是"滚到底就卡死"。
    val lookahead = (viewport * 1.5f).coerceAtLeast(lineHeight * 6f)
    // 一页最高就是整个视口高，所以"预算 ÷ 视口"就是窗口能装多少页；换屏幕/换字号都自动跟着走
    val maxPages = (SAFE_DOC_PX / viewport.coerceAtLeast(1)).toInt().coerceAtLeast(BLOCK_PAGES)

    // 唯一要长期留着的检查：文档高度逼近 Constraints 上限就先报警，正常滚动一声不出
    LaunchedEffect(blocks) {
        val h = book.lines * lineHeight
        if (h > SAFE_DOC_PX) Log.w(TAG_WINDOW, "滚动文档 ${h.roundToInt()}px 超预算 ${SAFE_DOC_PX.toInt()}px")
    }

    LaunchedEffect(scroll.value, blocks, viewport, lineHeight) {
        // 本轮用当前 blocks 现算文档：book 是组合时捕获的，effect 跑到这里 blocks 可能已经换过一版，
        // 拿旧文档去解释新的 scroll.value，量出来的首行就不是屏幕上那一行（换字号退一页就是这么来的）。
        val doc = ScrollBook(blocks)
        if (viewport == 0 || doc.lines == 0 || blocks.isEmpty()) return@LaunchedEffect
        if (anchorPx >= 0) {
            val px = anchorPx
            anchorPx = -1
            if (px > 0) scroll.scrollTo(px)
            return@LaunchedEffect
        }
        val top = scroll.value
        // 文档高度取布局自己算出来的 maxValue+viewport，不取本轮捕获的 book —— book 可能还是上一轮的
        val height = scroll.maxValue + viewport
        if (top < lookahead) {
            val added = before(blocks.first())
            if (added != null) {
                textOfPages(added.pages)
                val grow = added.lines * lineHeight
                var list = listOf(added) + blocks
                var pages = list.sumOf { it.pages.size }
                var total = height + grow
                // 往前补要把滚动量同帧加上补出来的那段高度，否则画面会往下蹿一章
                scroll.scrollTo((top + grow).roundToInt().coerceAtLeast(0))
                while (list.size > 1 && pages > maxPages) {
                    val tail = list.last()
                    val t = tail.lines * lineHeight
                    // 只剪整段还在视野下方的；正在看的那一段宁可暂时超限也不剪
                    if (total - t < top + grow + viewport) break
                    evict(tail)
                    list = list.dropLast(1)
                    pages -= tail.pages.size
                    total -= t
                }
                blocks = list
                // 补完这一轮就交卷：本轮捕获的 book 还是补之前的，按新的 scroll.value 去算首行
                // 会算到锚页下面几页去（量过：存 2486 开出来提交成 5802，正好多了补进来的那一段）。
                // blocks 是本 effect 的 key，下一轮拿到的是新文档，那时再报首行偏移。
                return@LaunchedEffect
            }
        } else if (height - top - viewport < lookahead) {
            val added = after(blocks.last())
            if (added != null) {
                textOfPages(added.pages)
                var list = blocks + added
                var pages = list.sumOf { it.pages.size }
                var shift = 0f
                while (list.size > 1 && pages > maxPages) {
                    val head = list.first()
                    val h = head.lines * lineHeight
                    // 只剪整段已经在视野上方去了一屏的；剪一段就要同帧把滚动量减去它，否则画面往上蹿
                    if (h > top - shift - viewport) break
                    evict(head)
                    list = list.drop(1)
                    pages -= head.pages.size
                    shift += h
                }
                if (shift > 0f) scroll.scrollTo((top - shift).roundToInt().coerceAtLeast(0))
                blocks = list
                // 同上：剪了头一段，本轮的 book 已经不对应新的滚动量，下一轮再报位置
                return@LaunchedEffect
            }
        }
        val offset = doc.offsetOf(lineAt(scroll.value, lineHeight, doc.lines)) ?: return@LaunchedEffect
        // 位置提交：最多 250ms 一次。自动阅读是连续滚的，"手停下来才提交"那种去抖永远等不到，
        // 所以这里改成节流 + 尾随一次（最后一下没人再触发 effect，就靠这个 delay 兜住）。
        val since = SystemClock.uptimeMillis() - lastCommit[0]
        if (since < COMMIT_MS) delay(since.let { COMMIT_MS - it })
        lastCommit[0] = SystemClock.uptimeMillis()
        if (offset != sent[0]) {
            sent[0] = offset
            onTopOffset(offset)
        }
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(theme.pageColor)
            // 顶缝底缝是给"文字带"的，不是给文档首末行的：滚动时行要从边底下穿过去，只垫首行等于没垫。
            // 所以整条带子往里挪、上下边缘都裁掉 —— 这样上下那一档的缝和按页那一档量出来是同一个数。
            .padding(top = with(density) { bandTopPx.toDp() }, bottom = with(density) { bandBottomPx.toDp() })
            .clipToBounds()
            .onSizeChanged { viewport = it.height }
            .pointerInput(Unit) {
                detectTapGestures(
                    onTap = { offset -> onTap(size.width, offset.x) },
                    // 长按落在哪一行，就交那一行的偏移；调用方给回整段的范围，
                    // 这里再把它换算成窗口坐标里的首末行 —— 动作栏要贴在段落上面，不能钉在屏幕底下
                    onLongPress = { offset ->
                        val line = lineAt(scroll.value + offset.y.toInt(), lineHeight, book.lines)
                        book.offsetOf(line)?.let { hit ->
                            onLongPress(hit)?.let { range ->
                                // 段间空行占位和它下面那一段的首行是同一个偏移，按偏移筛会把弹框抬高一行 —— 只认有字的行
                                var first = -1
                                var last = -1
                                for (i in 0 until book.lines) {
                                    val ln = book.lineOf(i) ?: continue
                                    if (ln.start == ln.end) continue
                                    if (ln.start < range.first || ln.start >= range.last) continue
                                    if (first < 0) first = i
                                    last = i
                                }
                                if (first >= 0) onSelectionRect(
                                    bandTopPx + first * lineHeight - scroll.value,
                                    bandTopPx + (last + 1) * lineHeight - scroll.value,
                                )
                            }
                        }
                    },
                )
            }
            .verticalScroll(scroll),
    ) {
        Canvas(
            Modifier
                .fillMaxWidth()
                // 左右边距和按页模式同一份版心；上内缩已经挪到外层那条带上，画布里只管行内坐标
                .padding(
                    start = with(density) { marginPx.toDp() },
                    end = with(density) { marginPx.toDp() },
                )
                .height(with(density) { (book.lines * lineHeight).toDp() }),
        ) {
            val first = lineAt(scroll.value, lineHeight, book.lines)
            val last = lineAt(scroll.value + viewport, lineHeight, book.lines)
            val ascent = -paint.fontMetrics.ascent
            book.forEachPageBetween(first, last) { page, pageBaseLine, from, to ->
                drawLines(
                    canvas = drawContext.canvas.nativeCanvas,
                    lines = page.lines.subList(from, to + 1),
                    text = textOf[page.startOffset] ?: "",
                    pageStart = page.startOffset,
                    baseY = pageBaseLine * lineHeight,
                    paint = paint,
                    ascent = ascent,
                    textSizePx = style.fontSizePx,
                    highlight = selection,
                    highlightColor = selectionColor,
                    lineHeightPx = lineHeight,
                    marks = marks,
                    markColor = markColor,
                    styles = engine.book.styles,
                    quoteColor = quoteArgb(theme),
                )
            }
        }
    }
}

/** 一次进窗口的页数，以及"最多往前/往后翻过几章空章"。 */
private const val BLOCK_PAGES = 6
private const val MAX_SKIP = 24

/** 滚动时最多 250ms 把位置交出去一次（自动阅读连续滚，去抖式的"停下才提交"永远等不到）。 */
private const val COMMIT_MS = 250L

/**
 * 滚动文档的高度预算。Compose 的 Constraints 把宽高打包进一个数，超过约 26 万像素就抛
 * `Can't represent a width of 0 and height of 273331 in Constraints` —— 真机上一直往下滚、
 * 攒够一百多章就是这个数，所以这里留一倍余量，窗口按"预算 ÷ 视口高"折算成页数。
 */
private const val SAFE_DOC_PX = 120_000f
private const val TAG_WINDOW = "ScrollWindow"

/** 窗口里的一段：某一章里连续 [pages] 页，[firstPage] 是这页在章内的页号。段不跨章，跨章靠窗口拼接。 */
private class Block(val chapter: Int, val firstPage: Int, val pages: List<Page>) {
    private val starts = IntArray(pages.size + 1).also {
        for (i in pages.indices) it[i + 1] = it[i] + pages[i].lines.size
    }

    val lines: Int get() = starts[pages.size]

    fun pageLineStart(index: Int): Int = starts[index]
}

/** 连续几段拼起来的滚动文档。blocks 必须是首尾相接的页段，下标 0 那页在最上面。 */
private class ScrollBook(val blocks: List<Block>) {
    private val bases = IntArray(blocks.size + 1).also {
        for (i in blocks.indices) it[i + 1] = it[i] + blocks[i].lines
    }

    val lines: Int get() = bases[blocks.size]

    fun offsetOf(line: Int): Long? = lineOf(line)?.start

    /** 全局行号 → 那一行。行到页的映射只靠各段的行数前缀和。 */
    fun lineOf(line: Int): TextLine? {
        if (blocks.isEmpty()) return null
        var lo = 0
        var hi = blocks.size - 1
        while (lo < hi) {
            val mid = (lo + hi + 1) / 2
            if (bases[mid] <= line) lo = mid else hi = mid - 1
        }
        val part = blocks[lo]
        if (part.pages.isEmpty()) return null
        val local = (line - bases[lo]).coerceIn(0, (part.lines - 1).coerceAtLeast(0))
        var page = part.pages.lastIndex
        while (page > 0 && part.pageLineStart(page) > local) page--
        val p = part.pages[page]
        if (p.lines.isEmpty()) return null
        val row = (local - part.pageLineStart(page)).coerceIn(0, p.lines.lastIndex)
        return p.lines[row]
    }

    /** 只把和可视窗口有交集的页交给绘制回调；pageBaseLine 是这一页第一行的全局行号。 */

    /** 只把和可视窗口有交集的页交给绘制回调；pageBaseLine 是这一页第一行的全局行号。 */
    inline fun forEachPageBetween(first: Int, last: Int, body: (Page, Int, Int, Int) -> Unit) {
        for (i in blocks.indices) {
            val part = blocks[i]
            val base = bases[i]
            if (base > last) break
            if (base + part.lines <= first) continue
            for (j in part.pages.indices) {
                val page = part.pages[j]
                if (page.lines.isEmpty()) continue
                val pageBase = base + part.pageLineStart(j)
                if (pageBase > last) break
                if (pageBase + page.lines.size <= first) continue
                val from = (first - pageBase).coerceAtLeast(0)
                val to = (last - pageBase).coerceAtMost(page.lines.lastIndex)
                if (to >= from) body(page, pageBase, from, to)
            }
        }
    }
}

/** 滚动位置 → 第几行。行网格等高，所以这一步只是除法。 */
private fun lineAt(scrollPx: Int, lineHeight: Float, lines: Int): Int =
    ((scrollPx / lineHeight).toInt().coerceAtLeast(0)).coerceAtMost((lines - 1).coerceAtLeast(0))

/**
 * 起锚时屏幕第一行就是"从 [cursor] 开头的那一行"，它上面有几行要滚掉。
 * 不能按 `end <= cursor` 数：页界补出来的那条空行占位 start=end=cursor，按老写法会被当成"在上面"
 * 而滚掉 —— 空行白补了，而且按页那一档还留着它，两档又差一行（55px）。
 */
internal fun headLines(lines: List<TextLine>, cursor: Long): Int {
    val i = lines.indexOfFirst { it.start >= cursor }
    return if (i < 0) lines.size else i
}

/** 一行真正占的宽度：两端对齐的行被拉开字距后比量的自然宽长，选中底色和波浪线都要按这个长度铺。 */
/** 引用块那一档的灰：墨色压暗，六档纸色下都还看得见，不跟正文抢。 */
internal fun quoteArgb(theme: ReaderTheme): Int =
    theme.inkColor.copy(alpha = 0.62f).toArgb()

private fun drawnWidthPx(line: TextLine): Float =
    (line.widthPx + line.chars * line.trackingPx).coerceAtLeast(1f)

/** 每行一次 drawText。baseY 是这一页第一条行的行顶，滚动位移由外层容器负责。 */
internal fun drawLines(
    canvas: android.graphics.Canvas,
    lines: List<TextLine>,
    text: String,
    pageStart: Long,
    baseY: Float,
    paint: Paint,
    ascent: Float,
    textSizePx: Float,
    highlight: LongRange? = null,
    highlightColor: Int = 0,
    lineHeightPx: Float = 0f,
    marks: List<LongRange> = emptyList(),
    markColor: Int = 0,
    /** markdown 那类书的样式旁路；空索引 = 老书，逐字节按原来那条路画。 */
    styles: com.bookreader.core.model.StyleIndex = com.bookreader.core.model.StyleIndex.EMPTY,
    quoteColor: Int = 0,
) {
    // 长按选中的那一段：先铺一层底色，再写字。整段可能跨页、跨屏，所以按行求交集逐行铺。
    // 宽度要用"拉开字距之后"的实际占宽：两端对齐的行 natural 比右边界短一截，
    // 直接拿 widthPx 铺就会看到选中底色在右边缺一条（他截图圈出来的就是这个）。
    if (highlight != null && highlightColor != 0) {
        val fill = android.graphics.Paint(paint)
        fill.color = highlightColor
        for (line in lines) {
            if (line.end <= highlight.first || line.start >= highlight.last) continue
            canvas.drawRect(
                line.indentPx,
                baseY + line.topPx,
                line.indentPx + drawnWidthPx(line),
                baseY + line.topPx + lineHeightPx,
                fill,
            )
        }
    }
    // 加过书签的段落下面画一条红色波浪线（和选中底色一样按行求交集，整段都标出来）
    if (marks.isNotEmpty() && markColor != 0) {
        val pen = android.graphics.Paint(paint)
        pen.color = markColor
        pen.style = android.graphics.Paint.Style.STROKE
        pen.strokeWidth = (lineHeightPx * 0.035f).coerceAtLeast(1f)
        pen.pathEffect = null
        for (line in lines) {
            if (marks.none { line.end > it.first && line.start < it.last }) continue
            val w = drawnWidthPx(line)
            val y = baseY + line.topPx + lineHeightPx * 0.94f
            val wave = (lineHeightPx * 0.16f).coerceAtLeast(3f)
            val path = android.graphics.Path()
            path.moveTo(line.indentPx, y)
            var x = line.indentPx
            var up = true
            while (x + wave < line.indentPx + w) {
                x += wave
                path.quadTo(x - wave / 2f, if (up) y - wave else y + wave, x, y)
                up = !up
            }
            canvas.drawPath(path, pen)
        }
    }
    // 行内样式要按 run 换字体画：用正文的字体量、用样式的字体画，行会溢出右边界；
    // 反过来量了样式却没换字体，粗体就只是"看起来一样"。所以字体件在这里也按 flags 缓存一份。
    val baseFace = paint.typeface ?: android.graphics.Typeface.DEFAULT
    val faces = HashMap<Int, android.graphics.Typeface>()
    fun faceOf(flags: Int): android.graphics.Typeface = faces.getOrPut(flags) {
        val style = when {
            flags and com.bookreader.core.model.InlineFlag.BOLD != 0 &&
                flags and com.bookreader.core.model.InlineFlag.ITALIC != 0 -> android.graphics.Typeface.BOLD_ITALIC
            flags and com.bookreader.core.model.InlineFlag.BOLD != 0 -> android.graphics.Typeface.BOLD
            flags and com.bookreader.core.model.InlineFlag.ITALIC != 0 -> android.graphics.Typeface.ITALIC
            else -> android.graphics.Typeface.NORMAL
        }
        if (flags and com.bookreader.core.model.InlineFlag.CODE != 0) {
            android.graphics.Typeface.create(android.graphics.Typeface.MONOSPACE, style)
        } else {
            android.graphics.Typeface.create(baseFace, style)
        }
    }
    val ink = paint.color
    for (line in lines) {
        val from = (line.start - pageStart).toInt().coerceIn(0, text.length)
        val to = (line.end - pageStart).toInt().coerceIn(from, text.length)
        if (to <= from) continue
        // 章名那一行：顶格（排版器已经把缩进给成 0）+ 放大 + 加粗。基线按倍数往下压，
        // 让它落在同一行盒子里，不会骑到上一行去。
        if (line.titleScale != 1f) {
            paint.textSize = textSizePx * line.titleScale
            paint.isFakeBoldText = true
        }
        // 两端对齐：letterSpacing 是"每个字之后加"，natural + 字数×tracking 正好落到右边界
        paint.letterSpacing = if (line.trackingPx > 0f) line.trackingPx / textSizePx else 0f
        val baseYLine = baseY + line.topPx + ascent * line.titleScale
        if (line.quote && quoteColor != 0) paint.color = quoteColor
        if (line.quote && lineHeightPx > 0f) {
            // 引用块左缘那条竖线：宽度就是 1 个物理像素，画在这一行的最左边
            val pen = android.graphics.Paint(paint)
            pen.color = if (quoteColor != 0) quoteColor else ink
            pen.strokeWidth = 3f
            pen.letterSpacing = 0f
            canvas.drawRect(line.indentPx - 12f, baseY + line.topPx, line.indentPx - 9f, baseY + line.topPx + lineHeightPx, pen)
        }
        var x = line.indentPx
        val ranges = if (line.mono) listOf(Triple(from, to, com.bookreader.core.model.InlineFlag.CODE))
        else runRangesOf(styles, line, from, to, pageStart)
        for ((a, b, flags) in ranges) {
            paint.typeface = faceOf(flags)
            // 这台机器的中日韩字体没有真斜体，Typeface.create(base, ITALIC) 画出来和正体逐位相同
            // （真机量过：普通与"斜体"的 山水风云 墨数 1098=1098、上/下 1/3 质心 77.74/68.78 全等），
            // 所以斜体自己合成。方向按 Paint 文档那句「For approximating oblique text, use values around -0.25」，
            // 剪切不改水平推进，所以排版器量出来的宽度不用跟着改。
            paint.textSkewX = if (flags and com.bookreader.core.model.InlineFlag.ITALIC != 0) -0.22f else 0f
            canvas.drawText(text, a, b, x, baseYLine, paint)
            paint.textSkewX = 0f
            val w = paint.measureText(text, a, b)
            if (flags and com.bookreader.core.model.InlineFlag.STRIKE != 0) {
                // 删除线画在字面中间：基线往上 1/3 字高，粗细跟字号走，小字号下不会糊成一团
                val pen = android.graphics.Paint(paint)
                pen.letterSpacing = 0f
                pen.strokeWidth = (textSizePx * line.titleScale * 0.06f).coerceAtLeast(2f)
                val y = baseYLine - ascent * 0.34f
                canvas.drawLine(x, y, x + w, y, pen)
            }
            x += w
        }
        paint.typeface = baseFace
        paint.color = ink
        if (line.titleScale != 1f) {
            paint.textSize = textSizePx
            paint.isFakeBoldText = false
        }
    }
}

/** 这一行上的样式分段（绝对偏移换成 text 里的局部下标；没被任何一段盖住的部分 flags=0）。 */
private fun runRangesOf(
    styles: com.bookreader.core.model.StyleIndex,
    line: TextLine,
    from: Int,
    to: Int,
    pageStart: Long,
): List<Triple<Int, Int, Int>> {
    val spans = styles.spansIn(line.start.toInt(), line.end.toInt())
    if (spans.isEmpty()) return listOf(Triple(from, to, 0))
    val shift = from - line.start.toInt()
    val out = ArrayList<Triple<Int, Int, Int>>(spans.size + 1)
    var pos = from
    for (s in spans.sortedBy { it.start }) {
        val a = (s.start + shift).coerceIn(from, to)
        val b = (s.end + shift).coerceIn(from, to)
        if (b <= a) continue
        if (a > pos) out += Triple(pos, a, 0)
        out += Triple(a, b, s.flags)
        pos = b
    }
    if (pos < to) out += Triple(pos, to, 0)
    return out
}

@Composable
internal fun readerPaint(theme: ReaderTheme, textSizePx: Float, typeface: Typeface?): Paint =
    remember(theme.ink, textSizePx, typeface) {
        Paint().apply {
            isAntiAlias = true
            color = theme.ink
            textSize = textSizePx
            if (typeface != null) this.typeface = typeface
        }
    }
