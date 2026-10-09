package com.bookreader.core

import com.bookreader.core.engine.ReaderEngine
import com.bookreader.core.ingest.TxtIngestor
import com.bookreader.core.layout.ApproximateBreaker
import com.bookreader.core.model.PageStyle
import com.bookreader.core.storage.Book
import com.bookreader.core.storage.TextSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class PagingTest {

    private fun engine(
        chapters: Int = 8,
        charsPerChapter: Int = 20_000,
        style: PageStyle = Fixtures.STYLE,
        chunkChars: Int = 4096,
        maxChunks: Int = 6,
        wrap: (TextSource) -> TextSource = { it },
        indentBodyWith: String = "",
    ): ReaderEngine {
        val root = Fixtures.tempRoot("paging")
        val src = File(root, "book.txt")
        Fixtures.writeNovel(src, chapters, charsPerChapter, indentBodyWith = indentBodyWith)
        val dir = File(root, "book")
        TxtIngestor(chunkChars).ingest(src, dir, "测试书")
        return ReaderEngine(Book.open(dir, maxChunks, wrap), ReaderEngine.approximateBreaker(), style)
    }

    /**
     * 网上抓的 TXT 十有八九在段首手打了全角/半角空格。排版再给 2 字缩进，
     * 一段就能缩进四个汉字——所以正文里的假缩进必须当成不存在，
     * 而且不能靠改正文（absoluteOffset 是位置锚点）。
     */
    @Test
    fun fakeIndentInTheSourceIsInvisibleToLayout() {
        val style = Fixtures.STYLE
        val plain = engine(chapters = 2, charsPerChapter = 20_000, style = style)
        val faked = engine(chapters = 2, charsPerChapter = 20_000, style = style, indentBodyWith = "　　    ")
        // 只比第 0 章：两本书的第 0 章正文逐字相同，多出来的空格只让章尾少一段
        assertNotNull(plain.jumpToChapter(0))
        assertNotNull(faked.jumpToChapter(0))

        val a = ArrayList<Pair<Int, Float>>()
        val b = ArrayList<Pair<Int, Float>>()
        var pages = 0
        while (pages < 3) {
            val pa = plain.currentPage()!!
            val pb = faked.currentPage()!!
            assertEquals("同一章的页数要一致", pa.lines.size, pb.lines.size)
            for (i in pa.lines.indices) {
                val la = pa.lines[i]
                val lb = pb.lines[i]
                assertEquals("段首空格不该改变一行装几个字", la.chars, lb.chars)
                assertEquals("段首空格不该改变缩进", la.indentPx, lb.indentPx)
                val first = faked.book.cache.source.read(lb.start, 1)
                assertTrue("行首第 $i 行是个空白字符: [$first]", first.isNotEmpty() && !first[0].isWhitespace())
                if (la.indentPx > 0f) assertEquals("段首缩进就是 2 个汉字", style.paragraphIndentPx, la.indentPx, 0.01f)
                a += la.chars to la.indentPx
                b += lb.chars to lb.indentPx
            }
            pages++
            if (plain.nextPage() == null) break
            faked.nextPage()
        }
        assertTrue("至少要比对 3 页才有意义: $pages", pages >= 3)
        assertEquals(a, b)
        println("[fake-indent] pages=$pages lines=${a.size} indent=${style.paragraphIndentPx}")
        plain.close()
        faked.close()
    }

    @Test
    fun pagesTileAChapterWithoutGapsOrOverlap() {
        val e = engine()
        val chapter = e.book.chapters[3]
        assertNotNull(e.jumpToChapter(3))
        val total = e.pagesInChapter()
        assertTrue("一章应该排出多页才有意义: $total", total > 3)
        var expect = chapter.startOffset
        for (i in 0 until total) {
            val p = e.currentPage()
            assertNotNull("第 $i 页排空了", p)
            assertEquals("第 $i 页的起点要接得上上一页的终点", expect, p!!.startOffset)
            assertTrue("页里必须有行", p.lines.isNotEmpty())
            assertTrue("页尾不能越过章尾", p.endOffset <= chapter.endOffset)
            expect = p.endOffset
            if (i + 1 < total) assertNotNull(e.nextPage())
        }
        assertEquals("最后一页要正好收在章尾", chapter.endOffset, expect)
        println("[tile] ch3 pages=$total chars=${chapter.charCount} lines/page=${e.pagesInChapter()}")
        e.close()
    }

    @Test
    fun everyLineFitsTheContentWidth() {
        val style = Fixtures.STYLE
        val breaker = ApproximateBreaker(style)
        val e = engine(chapters = 3, charsPerChapter = 9000, style = style)
        e.jumpToChapter(0)
        val total = e.pagesInChapter()
        var lines = 0
        var pages = 0
        var blanks = 0
        var longest = 0f
        do {
            val p = e.currentPage() ?: break
            pages++
            for (line in p.lines) {
                val text = e.book.cache.slice(line.start, (line.end - line.start).toInt())
                val w = breaker.widthOf(text, 0, text.length) * line.titleScale
                longest = maxOf(longest, w)
                assertTrue(
                    "行宽 $w 超过可用 ${style.contentWidthPx - line.indentPx}: [$text]",
                    w <= style.contentWidthPx - line.indentPx + 0.5f,
                )
                // 段与段之间原文就有空行，阅读里保留：那一条是零长度的占位行，宽度检查照样过
                assertTrue("行不能倒着排", line.end >= line.start)
                if (line.end == line.start) blanks++
                lines++
            }
        } while (e.nextPage() != null && e.currentChapterIndex == 0)
        println("[width] pages=$pages/$total lines=$lines blanks=$blanks longest=${longest}f avail=${style.contentWidthPx}f")
        assertTrue("原文段间的空行没被保留（整章一条都没有）", blanks > 0)
        assertTrue("翻页要真的走遍整章", pages >= total)
        assertTrue(lines > 200)
        e.close()
    }

    @Test
    fun nextPageThenPrevPageReturnsTheSamePage() {
        val e = engine()
        e.jumpToChapter(2)
        var turns = 0
        var cur = e.currentPage()!!
        while (turns < 120) {
            val next = e.nextPage() ?: break
            assertEquals("翻过去要接得上", cur.endOffset, next.startOffset)
            val back = e.prevPage()!!
            assertEquals("再翻回来必须是原来那页", cur.startOffset, back.startOffset)
            cur = e.currentPage()!!
            turns++
        }
        assertTrue("翻了 $turns 页", turns >= 20)
        e.close()
    }

    @Test
    fun styleChangeKeepsReadingPosition() {
        val e = engine(charsPerChapter = 30_000)
        e.jumpToChapter(1)
        repeat(3) { e.nextPage() }
        val page = e.currentPage()!!
        val keep = page.startOffset
        val before = e.pagesInChapter()
        val after = e.applyStyle(Fixtures.BIG_STYLE)!!
        assertTrue("位置 $keep 必须落在新版式的某一页里", after.startOffset <= keep && keep < after.endOffset)
        // 引擎交整页：换字号后这一页可以从更早就开始（裁到行会把整页裁短，屏上底下空一大截）。
        // "屏幕上第一行还是刚才那一行"是滚动窗口的事，它自己按游标裁副本。
        assertEquals("游标不能因为重排而漂移", keep, e.cursor)
        val bigger = e.pagesInChapter()
        assertTrue("字变大页数要变多: $before -> $bigger", bigger > before)
        assertEquals("位置不能因为重排而漂移", keep, e.position().absoluteOffset)
        println("[restyle] keep=$keep oldPages=$before newPages=$bigger")
        e.close()
    }

    /**
     * 顶缝是从版心高度里扣的，不是画的时候往下推：推的话行数不变，最后一行就会顶到导航键上。
     * 而且顶缝必须进 style.id —— 不进的话改"边距"那一档只动了 padding，分页器不会重建。
     */
    @Test
    fun topInsetComesOutOfTheLayoutNotTheDrawing() {
        val noInset = Fixtures.STYLE.copy(topInsetPx = 0f)
        val inset = Fixtures.STYLE.copy(topInsetPx = 82.5f, contentHeightPx = Fixtures.STYLE.contentHeightPx - 82.5f)
        // 只改顶缝、别的都不动：这条才是"改边距会不会重排"的判据。
        // （拿 contentHeightPx 也一起变的两个样式比是假判据 —— contentHeight 本来就在 id 里，怎么都过。）
        assertNotEquals(
            "顶缝变了必须让 id 变，否则分页器不重建",
            noInset.id,
            noInset.copy(topInsetPx = 82.5f).id,
        )

        fun firstPage(style: PageStyle): Pair<Int, Float> {
            val e = engine(chapters = 6, charsPerChapter = 30_000, style = style)
            e.openChapter(1)
            val page = e.currentPage()!!
            val last = page.lines.last()
            e.close()
            return page.lines.size to last.topPx + style.fontSizePx * style.lineHeightMultiplier
        }

        val (nLoose, bottomLoose) = firstPage(noInset)
        val (nTight, bottomTight) = firstPage(inset)
        assertTrue("加了 82.5px 顶缝行数必须变少（$nLoose -> $nTight）", nTight < nLoose)
        assertTrue(
            "最后一行底边 $bottomTight 必须落在版心 ${inset.contentHeightPx} 之内",
            bottomTight <= inset.contentHeightPx + 0.5f,
        )
        println("[top-inset] 无顶缝 $nLoose 行/底 ${bottomLoose.toInt()}，顶缝 82.5px → $nTight 行/底 ${bottomTight.toInt()}")
    }

    @Test
    fun jumpToChapterOnlyReadsNearThatChapter() {
        val e = engine(chapters = 40, charsPerChapter = 20_000)
        val t0 = System.nanoTime()
        val page = e.jumpToChapter(37)!!
        val ms = (System.nanoTime() - t0) / 1_000_000
        val ch = e.book.chapters[37]
        assertTrue("跳章要落在章首", page.startOffset in ch.startOffset until ch.endOffset)
        val stats = e.stats()
        println("[jump] ${ms}ms decodedBytes=${stats.decodedBytes} resident=${stats.residentChunks} contentBytes=${e.book.meta.contentBytes}")
        assertTrue("跳一章不该把前面的章都读一遍: decoded=${stats.decodedBytes}", stats.decodedBytes < e.book.meta.contentBytes / 4)
        e.close()
    }

    @Test
    fun byteBudgetTrapFiresOnlyWhenBudgetIsTooSmall() {
        // 反证要两个构型：宽预算下 100 页翻下来不碰天花板；天花板压到装不下一页时必须红
        val wide = engine(chapters = 20, charsPerChapter = 20_000, maxChunks = 6, wrap = { BudgetedSource(it, 4L * 1024 * 1024) })
        var turned = 0
        val wideOutcome = runCatching {
            wide.jumpToChapter(5)
            while (turned < 100 && wide.nextPage() != null) turned++
        }
        assertTrue("宽预算下不该越界: ${wideOutcome.exceptionOrNull()}", wideOutcome.isSuccess)
        assertTrue("翻了 $turned 页", turned >= 60)
        println("[trap-wide] turned=$turned decoded=${wide.book.cache.decodedBytes}")
        wide.close()

        val tight = engine(chapters = 20, charsPerChapter = 20_000, maxChunks = 6, wrap = { BudgetedSource(it, 24L * 1024) })
        val outcome = runCatching {
            tight.jumpToChapter(5)
            repeat(60) { tight.nextPage() }
        }
        println("[trap-tight] threw=${outcome.exceptionOrNull()?.javaClass?.simpleName}")
        assertTrue("预算尺子必须是活的：小预算要真拦得住", outcome.isFailure)
        tight.close()
    }

    @Test
    fun restoreTrustsTheOffsetNotTheChapterNumber() {
        val e = engine(chapters = 12, charsPerChapter = 20_000)
        e.jumpToChapter(9)
        repeat(2) { e.nextPage() }
        val good = e.position()
        val lying = good.copy(chapterIndex = 0)
        val restored = e.restore(lying)!!
        assertEquals("偏移是权威：章号错了也要按偏移找回同一页", good.absoluteOffset, restored.startOffset)
        assertEquals(9, e.currentChapterIndex)
        e.close()
    }

    /**
     * 引擎任何时候都交整页：跳进页中、换字号都不许把分页缓存里那一页裁短。
     * 裁过一次，按页那一档整屏只剩几行、底下空一大截（量过：7 行、空 1774px）。
     * "首屏第一行是游标那一行"由滚动窗口自己解决：整页原样进文档，把游标之前那几行算进垫高
     * （ScrollReader 的 anchorPx），所以文档上每一字都在、没有洞。
     */
    @Test
    fun engineAlwaysHandsOutWholePagesAndTheCursorIsTheAuthority() {
        val e = engine(chapters = 12, charsPerChapter = 20_000)
        val whole = e.openChapter(0, 0L)!!
        val lines = whole.lines.size
        val mid = whole.lines[lines / 2].start
        assertTrue("这一章一页只有 $lines 行，取不到页中行的偏移", lines > 4 && mid > whole.startOffset)

        val jumped = e.openChapter(0, mid)!!
        assertEquals("跳进页中那一行，交出来的还是整页", lines, jumped.lines.size)
        assertEquals("游标记的是那一行，不是页头", mid, e.cursor)
        assertEquals("存档位置以游标为准", mid, e.position().absoluteOffset)
        assertTrue(
            "整页里游标之前那几行还在（滚动窗口靠这个数垫高，文档才不缺字）",
            jumped.lines.count { it.end <= mid } in 1 until lines,
        )

        val halfHeight = Fixtures.STYLE.copy(contentHeightPx = Fixtures.STYLE.contentHeightPx / 2f)
        val restyled = e.applyStyle(halfHeight)!!
        assertTrue("换字号后这一页仍然含游标那一行", restyled.startOffset <= mid && mid < restyled.endOffset)
        assertEquals("换字号不许挪游标", mid, e.cursor)
        assertEquals("重排后交出来的还是整页", restyled.lines.size, e.currentPage()!!.lines.size)
        e.close()
    }

    /**
     * 滚动模式要按段取页，但它只是"看一眼别的章"——
     * 把阅读游标挪走的话，用户正读着的地方会被自己滚掉的缓冲改掉。
     * 段取还要能在章尾停下：窗口靠"没给满"这个信号判断该不该接下一章。
     */
    @Test
    fun chapterPagesReadsAChapterWithoutMovingTheCursor() {
        val e = engine(chapters = 12, charsPerChapter = 20_000)
        e.jumpToChapter(5)
        val here = e.position()
        val total = e.pageCount(8)
        val pages = e.chapterPages(8, from = 0, count = total)
        assertTrue("第 8 章一页都没排出来", pages.isNotEmpty())
        assertEquals("看一眼别的章不该挪走章游标", 5, e.currentChapterIndex)
        assertEquals("看一眼别的章不该挪走页游标", here.absoluteOffset, e.position().absoluteOffset)

        val chapter = e.book.chapters[8]
        assertEquals("整章要正好从章首开始铺", chapter.startOffset, pages.first().startOffset)
        assertEquals("整章要正好铺到章尾", chapter.endOffset, pages.last().endOffset)
        for (i in 1 until pages.size) {
            assertEquals("第 $i 页要接得上上一页", pages[i - 1].endOffset, pages[i].startOffset)
        }

        val tail = e.chapterPages(8, from = total - 2, count = 16)
        assertEquals("章尾只剩 2 页，要照实给 2 页而不是凑满", 2, tail.size)
        assertEquals("章尾那页要真是章尾", chapter.endOffset, tail.last().endOffset)
        assertEquals("要越界了不该顺手把游标挪过去", 5, e.currentChapterIndex)
        assertEquals("取 0 页要给空", 0, e.chapterPages(8, from = 0, count = 0).size)
        assertEquals("页号越界要给空", 0, e.chapterPages(8, from = total, count = 4).size)
        assertEquals("越界要给空，不能抛", 0, e.chapterPages(-1, from = 0, count = 4).size)
        assertEquals(0, e.chapterPages(e.chapterCount, from = 0, count = 4).size)
        e.close()
    }

    private class BudgetedSource(val delegate: TextSource, private val maxBytes: Long) : TextSource {
        private var decoded = 0L

        override val totalChars: Long get() = delegate.totalChars
        override val chunkChars: Int get() = delegate.chunkChars
        override val chunkCount: Int get() = delegate.chunkCount
        override fun chunkByteLen(index: Int): Int = delegate.chunkByteLen(index)

        override fun decodeChunk(index: Int): String {
            val s = delegate.decodeChunk(index)
            decoded += delegate.chunkByteLen(index)
            check(decoded <= maxBytes) { "超出字节预算: decoded=$decoded max=$maxBytes" }
            return s
        }

        override fun read(start: Long, count: Int): String = delegate.read(start, count)

        override fun close() = delegate.close()
    }
}
