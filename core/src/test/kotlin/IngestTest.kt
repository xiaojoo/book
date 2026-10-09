package com.bookreader.core

import com.bookreader.core.ingest.CharsetDetector
import com.bookreader.core.ingest.TxtIngestor
import com.bookreader.core.storage.Book
import com.bookreader.core.storage.IndexCodec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.charset.Charset

class IngestTest {

    private val body = "少年握剑立在崖底，风从很远的地方吹上来，把雾气撕开一道口子。他想起那句关于传承的话，握剑的手紧了紧，指节发白。"

    private fun ingest(source: File, name: String = source.nameWithoutExtension): Pair<File, Book> {
        val dir = File(Fixtures.tempRoot("ingest"), name)
        val report = TxtIngestor(chunkChars = 4096).ingest(source, dir, title = name)
        println("[ingest] $name chars=${report.meta.totalChars} chunks=${report.meta.chunkCount} chapters=${report.meta.chapterCount} found=${report.chapterTitlesFound} charset=${report.charset} ${report.millis}ms")
        return dir to Book.open(dir, maxChunks = 4)
    }

    @Test
    fun chapterTitlesBecomeIndex() {
        val src = File(Fixtures.tempRoot("src"), "titles.txt")
        src.writeText("作者：某人\n出版说明：这一段说明文字要足够长，长到它确实是一节前言，而不是一个分隔行或者一句书迷群广告，所以它不该被后面的第一章吞掉。\n\n第1章 起风\n$body\n\n第2章 崖底\n$body\n\n番外 后记\n$body\n")
        val (_, book) = ingest(src, "titles")
        val titles = book.chapters.chapters.map { it.title }
        println("[chapters] $titles")
        assertEquals(4, book.chapters.size)
        assertEquals("前言", titles[0])
        assertEquals("第1章 起风", titles[1])
        assertEquals("番外 后记", titles[3])
        assertEquals(0L, book.chapters[0].startOffset)
        assertEquals(book.totalChars, book.chapters.chapters.last().endOffset)
        for (i in 1 until book.chapters.size) {
            assertEquals(book.chapters[i - 1].endOffset, book.chapters[i].startOffset)
        }
        assertEquals(book.totalChars, IndexCodec.readChapters(File(book.dir, IndexCodec.CHAPTER_IDX)).last().endOffset)
        book.close()
    }

    @Test
    fun noChaptersFallsBackToSingleChapter() {
        val src = File(Fixtures.tempRoot("src"), "plain.txt")
        src.writeText(body + body + body)
        val (_, book) = ingest(src, "plain")
        assertEquals(1, book.chapters.size)
        assertEquals("全书", book.chapters[0].title)
        assertEquals(book.totalChars, book.chapters[0].endOffset)
        book.close()
    }

    @Test
    fun tooManyFakeTitlesFallsBack() {
        val src = File(Fixtures.tempRoot("src"), "lists.txt")
        src.writeText((1..400).joinToString("\n") { "$it、短行" })
        val report = TxtIngestor(chunkChars = 4096).ingest(src, File(Fixtures.tempRoot("bk"), "lists"), "lists")
        println("[fallback] titlesFound=${report.chapterTitlesFound} chapters=${report.meta.chapterCount}")
        assertEquals(400, report.chapterTitlesFound)
        assertEquals(1, report.meta.chapterCount)
    }

    @Test
    fun crlfAndBomAreNormalized() {
        val src = File(Fixtures.tempRoot("src"), "crlf.txt")
        val text = "第1章 甲\r\n$body\r\n第2章 乙\r\n$body\r\n"
        src.writeBytes(byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()) + text.toByteArray(Charsets.UTF_8))
        val (dir, book) = ingest(src, "crlf")
        val content = File(dir, IndexCodec.CONTENT).readText(Charsets.UTF_8)
        assertFalse("规范化后不该留 \\r", content.contains('\r'))
        assertFalse("BOM 不该进正文", content.contains('\uFEFF'))
        assertEquals(2, book.chapters.size)
        assertEquals('第', book.cache.slice(0L, 1)[0])
        assertTrue("正文要能原样读回来", content.contains("少年握剑立在崖底"))
        book.close()
    }

    @Test
    fun gb18030SourceIsDetectedAndConverted() {
        val root = Fixtures.tempRoot("src")
        val utf8 = File(root, "utf8.txt")
        utf8.writeText("第1章 少年\n$body\n第2章 雷光\n$body\n")
        assertEquals("UTF-8", CharsetDetector.detect(utf8).name())

        val gbk = File(root, "gb18030.txt")
        gbk.writeText("第1章 少年\n$body\n第2章 雷光\n$body\n", Charset.forName("GB18030"))
        val detected = CharsetDetector.detect(gbk)
        println("[charset] detected=${detected.name()}")
        assertEquals("GB18030", detected.name())
        val (_, book) = ingest(gbk, "gb18030")
        assertTrue(book.cache.slice(0L, 60).contains("少年握剑立在崖底"))
        assertEquals(2, book.chapters.size)
        book.close()
    }

    @Test
    fun randomReadsAcrossChunkBoundaryMatchSequentialScan() {
        val src = File(Fixtures.tempRoot("src"), "novel.txt")
        Fixtures.writeNovel(src, chapters = 12, charsPerChapter = 900)
        val (dir, book) = ingest(src, "novel")
        val whole = File(dir, IndexCodec.CONTENT).readText(Charsets.UTF_8)
        assertEquals(whole.length.toLong(), book.totalChars)
        assertEquals((whole.length + 4095) / 4096, book.meta.chunkCount)
        val rng = java.util.Random(7)
        repeat(300) {
            val start = rng.nextLong().let { Math.abs(it) % (book.totalChars - 500) }
            val count = 1 + rng.nextInt(500)
            assertEquals(
                "offset=$start count=$count",
                whole.substring(start.toInt(), start.toInt() + count),
                book.cache.slice(start, count)
            )
        }
        assertTrue("常驻块数不能超过缓存上限", book.cache.residentChunks() <= 4)
        println("[random-read] resident=${book.cache.residentChunks()} hits=${book.cache.hits} misses=${book.cache.misses} decodedBytes=${book.cache.decodedBytes}")
        book.close()
    }
}
