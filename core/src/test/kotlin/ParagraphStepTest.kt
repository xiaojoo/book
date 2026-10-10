package com.bookreader.core

import com.bookreader.core.engine.ReaderEngine
import com.bookreader.core.ingest.TxtIngestor
import com.bookreader.core.storage.Book
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.File

/** 听书浮窗的「上一段 / 下一段」要在段落之间跳，还要跳过原文保留的空行。 */
class ParagraphStepTest {

    private fun engineWith(text: String): Pair<ReaderEngine, Book> {
        val dir = Fixtures.tempRoot("step")
        val txt = File(dir, "doc.txt").apply { writeText(text, Charsets.UTF_8) }
        val bookDir = File(dir, "book")
        TxtIngestor().ingest(txt, bookDir, title = "step")
        val book = Book.open(bookDir, 6)
        return ReaderEngine(book, ReaderEngine.approximateBreaker(), Fixtures.STYLE) to book
    }

    private fun body(book: Book, r: LongRange) = book.cache.slice(r.first, (r.last - r.first + 1).toInt())

    @Test
    fun stepsBetweenParagraphsAndSkipsBlankLines() {
        val text = "第一段的话。\n\n\n第二段的话，这一段的字比较多所以要占好几行。\n\n第三段的话。\n"
        val (engine, book) = engineWith(text)
        val second = engine.paragraphRange(text.indexOf("第二段").toLong())
        assertEquals("第二段的话，这一段的字比较多所以要占好几行。", body(book, second))

        val third = engine.nextParagraph(second)
        assertEquals("第三段的话。", third?.let { body(book, it) })
        val first = engine.prevParagraph(second)
        assertEquals("第一段的话。", first?.let { body(book, it) })
        // 跳过去的段必须落在正文上，中间那些空行不能被判成"下一段"（上面两条断言比的是正文本身，
        // 空行段是空白串，一旦误选就会在这里对不上）
        assertEquals(text.indexOf("第三段").toLong(), third?.first)
        assertEquals(text.indexOf("第一段").toLong(), first?.first)
        engine.close()
    }

    @Test
    fun endsReturnNullInsteadOfLooping() {
        val text = "唯一能算第一段的话。\n第二段的话。\n"
        val (engine, book) = engineWith(text)
        val p1 = engine.paragraphRange(0L)
        assertEquals("唯一能算第一段的话。", body(book, p1))
        assertNull("第一段之前没有段落", engine.prevParagraph(p1))
        val p2 = engine.nextParagraph(p1)
        assertEquals("第二段的话。", p2?.let { body(book, it) })
        assertNull("最后一段之后没有段落", engine.nextParagraph(p2!!))
        engine.close()
    }
}
