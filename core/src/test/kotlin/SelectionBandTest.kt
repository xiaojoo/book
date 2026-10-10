package com.bookreader.core

import com.bookreader.core.engine.ReaderEngine
import com.bookreader.core.ingest.TxtIngestor
import com.bookreader.core.layout.coversLine
import com.bookreader.core.storage.Book
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** 选中底色的行判定。`:core` 里判，不靠真机截图才发现整行丢了。 */
class SelectionBandTest {

    @Test
    fun closedRangeAgainstHalfOpenLine() {
        val hl = 28L..51L
        // 段内第一行、中间行
        assertTrue(hl.coversLine(28, 40))
        assertTrue(hl.coversLine(40, 51))
        // 折行的最后一行：正好从 last 那个字符开头 —— 旧判据 `start >= last` 在这里把整行丢掉
        assertTrue("折行的末行没盖上底色", hl.coversLine(51, 53))
        // 上一段的末行、下一段的首行都不该盖
        assertFalse(hl.coversLine(20, 28))
        assertFalse(hl.coversLine(52, 80))
    }

    @Test
    fun everyLineOfAParagraphIsCoveredByItsOwnRange() {
        val dir = Fixtures.tempRoot("band")
        val text = "陈平安愣了愣，将信将疑地转身返回，坐在摊子前的长凳上。\n" +
            "一朴素道士，一寒酸少年，两个大小穷光蛋，相对而坐，说了一句很长的话以至于这一段的末尾必须折到第二行去才能装得下剩下的字。\n" +
            "道人笑着伸出手，示意少年拿起签筒。\n"
        val txt = File(dir, "doc.txt").apply { writeText(text, Charsets.UTF_8) }
        val bookDir = File(dir, "book")
        TxtIngestor().ingest(txt, bookDir, title = "band")
        val book = Book.open(bookDir, 6)
        val engine = ReaderEngine(book, ReaderEngine.approximateBreaker(), Fixtures.STYLE)
        val page = checkNotNull(engine.openChapter(0))
        val bad = page.lines.filter { !engine.paragraphRange(it.start).coversLine(it.start, it.end) }
        assertEquals("这些行没被自己那段选中范围盖上：${bad.map { it.start to it.end }}", 0, bad.size)
        // 段落区间必须是闭区间，而且末字符就是段落最后一个字（换行不算在内）——
        // 以前 `until (p.end - 1)` 把句号也削掉了，表现是听书少念最后一个字
        val at = text.indexOf("一朴素道士").toLong()
        val hl = engine.paragraphRange(at)
        assertEquals(at, hl.first)
        assertEquals("。", book.cache.slice(hl.last, 1))
        assertEquals("整段长度要对上", text.substring(at.toInt()).substringBefore('\n').length.toLong(), hl.last - hl.first + 1)
        engine.close()
    }
}
