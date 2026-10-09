package com.bookreader.core

import com.bookreader.core.engine.ReaderGestures
import com.bookreader.core.engine.TapZone
import com.bookreader.core.ingest.TxtIngestor
import com.bookreader.core.storage.Book
import com.bookreader.core.storage.IndexCodec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** 界面会用到的两处算术，先在 JVM 上用数字定死，别等截图才发现。 */
class UiMathTest {

    private fun engine(chapters: Int, charsPerChapter: Int) = run {
        val root = Fixtures.tempRoot("uimath")
        val src = File(root, "book.txt")
        Fixtures.writeNovel(src, chapters, charsPerChapter)
        val dir = File(root, "book")
        TxtIngestor(4096).ingest(src, dir, "测试书")
        com.bookreader.core.engine.ReaderEngine(
            Book.open(dir, 6),
            com.bookreader.core.engine.ReaderEngine.approximateBreaker(),
            Fixtures.STYLE,
        )
    }

    @Test
    fun tapZonesSplitTheWidthIntoThree() {
        val w = 1080
        assertEquals(TapZone.Prev, ReaderGestures.zoneOf(w, 0f))
        assertEquals(TapZone.Prev, ReaderGestures.zoneOf(w, 359f))
        // 三等分：1/3 处 360px、2/3 处 720px。这两格是"从 45% 改成 1/3"之后才成立的判据。
        assertEquals(TapZone.ToggleBars, ReaderGestures.zoneOf(w, 360f))
        assertEquals(TapZone.ToggleBars, ReaderGestures.zoneOf(w, 485f))
        assertEquals(TapZone.ToggleBars, ReaderGestures.zoneOf(w, 540f))
        assertEquals(TapZone.ToggleBars, ReaderGestures.zoneOf(w, 719f))
        // 分界线本身必须落在"下一页"这一侧：用 w*(1-1/3f) 算会得出 719.99996，720 这点会被误判
        assertEquals(TapZone.Next, ReaderGestures.zoneOf(w, 720f))
        assertEquals(TapZone.Next, ReaderGestures.zoneOf(w, 721f))
        assertEquals(TapZone.Next, ReaderGestures.zoneOf(w, 1079f))
        // 还没量到宽度时不许误翻页
        assertEquals(TapZone.ToggleBars, ReaderGestures.zoneOf(0, 500f))
        // 中线两侧必须对称
        val left = ReaderGestures.zoneOf(w, 540f - 1f)
        val right = ReaderGestures.zoneOf(w, 540f + 1f)
        assertEquals(left, right)
    }

    @Test
    fun swipeDirectionMatchesFingerTravel() {
        assertEquals(1, ReaderGestures.swipeDirection(-56f, 56f))
        assertEquals(-1, ReaderGestures.swipeDirection(56f, 56f))
        assertNull(ReaderGestures.swipeDirection(-55.9f, 56f))
        assertNull(ReaderGestures.swipeDirection(0f, 56f))
        assertNull(ReaderGestures.swipeDirection(-100f, 0f))
    }

    @Test
    fun pageTextSlicesWithLocalLineIndices() {
        val e = engine(chapters = 4, charsPerChapter = 12_000)
        val content = File(e.book.dir, IndexCodec.CONTENT).readText(Charsets.UTF_8)
        e.jumpToChapter(1)
        val chapter = e.chapter
        var pages = 0
        var lines = 0
        do {
            val page = e.currentPage() ?: break
            pages++
            val size = (page.endOffset - page.startOffset).toInt()
            val pageText = e.book.cache.slice(page.startOffset, size)
            assertEquals("页文本长度要等于页区间", size.toLong(), pageText.length.toLong())
            // 页区间必须和整本正文一致，不能串到别的章去
            assertEquals(content.substring(page.startOffset.toInt(), page.endOffset.toInt()), pageText)
            assertTrue("章内页不能越界", page.endOffset <= chapter.endOffset)
            for (line in page.lines) {
                val from = (line.start - page.startOffset).toInt()
                val to = (line.end - page.startOffset).toInt()
                assertTrue("局部下标要落在页文本内: $from..$to / ${pageText.length}", from in 0..size && to in from..size)
                val byLine = e.book.cache.slice(line.start, (line.end - line.start).toInt())
                assertEquals("按局部下标取字必须和单独取字一致", byLine, pageText.substring(from, to))
                assertFalse("行里不该有换行符", byLine.contains('\n'))
                lines++
            }
        } while (e.nextPage() != null && e.currentChapterIndex == 1)
        println("[page-text] pages=$pages lines=$lines chapter=${chapter.title} chars=${chapter.charCount}")
        assertTrue(pages >= 2)
        e.close()
    }
}
