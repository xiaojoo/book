package com.bookreader.core

import com.bookreader.core.engine.ReaderEngine
import com.bookreader.core.ingest.TxtIngestor
import com.bookreader.core.search.ChunkScanner
import com.bookreader.core.storage.Book
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 千万字的验收测试。每条判据都打印实测数字，绿不是结论，数字才是。
 * 堆被 Gradle 限在 512m，所以"不整体加载"这条除了计数器外也有硬约束兜着。
 */
class TenMillionCharTest {

    private fun ms(sinceNanos: Long): Long = (System.nanoTime() - sinceNanos) / 1_000_000

    @Test
    fun tenMillionCharNovelReadsWithinBudget() {
        val root = Fixtures.tempRoot("ten-m")
        val src = File(root, "novel.txt")

        val tGen = System.nanoTime()
        val written = Fixtures.writeNovel(src, chapters = 1_000, charsPerChapter = 10_000)
        val genMs = ms(tGen)
        println(String.format("[fixture] 字数=%,d 源文件=%,d bytes 生成=%,dms", written, src.length(), genMs))
        assertTrue("夹具没造出千万字: $written", written >= 9_500_000L)

        val dir = File(root, "book")
        val tIngest = System.nanoTime()
        val report = TxtIngestor().ingest(src, dir, title = "千万字测试书")
        val ingestMs = ms(tIngest)
        val meta = report.meta
        println(String.format("[ingest] %,dms 块数=%,d 块长=%d 正文=%,d bytes 章节=%d 编码=%s", ingestMs, meta.chunkCount, meta.chunkChars, meta.contentBytes, meta.chapterCount, report.charset))
        assertEquals(1_000, meta.chapterCount)

        val tOpen = System.nanoTime()
        val book = Book.open(dir, maxChunks = 6)
        val engine = ReaderEngine(book, ReaderEngine.approximateBreaker(), Fixtures.STYLE)
        val first = engine.openChapter(0)
        val openMs = ms(tOpen)
        assertNotNull(first)
        println(String.format("[open] 打开到第一页 %dms 行/页=%d 进度=%.4f", openMs, first!!.lines.size, engine.progress()))
        assertTrue("首次打开目标 < 1s，实测 ${openMs}ms", openMs < 1_000)

        val tJump = System.nanoTime()
        val jumped = engine.jumpToChapter(532)!!
        val jumpMs = ms(tJump)
        val ch532 = book.chapters[532]
        assertTrue("跳到的页必须落在 532 章里", jumped.startOffset in ch532.startOffset until ch532.endOffset)
        println(String.format("[jump] 第532章 %dms offset=%,d 标题=%s", jumpMs, jumped.startOffset, ch532.title))
        assertTrue("跳章目标 < 200ms，实测 ${jumpMs}ms", jumpMs < 200)

        val tTurn = System.nanoTime()
        var turns = 0
        var crossings = 0
        var lastChapter = engine.currentChapterIndex
        while (turns < 1_000) {
            val next = engine.nextPage() ?: break
            turns++
            if (engine.currentChapterIndex != lastChapter) {
                crossings++
                lastChapter = engine.currentChapterIndex
            }
            assertNotNull(next.lines.firstOrNull())
        }
        val turnMs = ms(tTurn)
        val perPage = turnMs.toDouble() / turns.coerceAtLeast(1)
        println(String.format("[turn] 连续翻页 %d 页 跨章 %d 次 共 %dms 每页 %.2fms", turns, crossings, turnMs, perPage))
        assertEquals(1_000, turns)
        assertTrue("每页均摊目标 < 16ms，实测 ${perPage}ms", perPage < 16.0)

        val bounded = engine.stats()
        println(String.format("[memory] 常驻块=%d 常驻字数峰值=%,d 解码字节累计=%,d 整本=%,d bytes 比例=%.3f 命中/未命中=%d/%d 排过页=%d 排行=%,d",
            bounded.residentChunks, bounded.peakResidentChars, bounded.decodedBytes, meta.contentBytes,
            bounded.decodedBytes.toDouble() / meta.contentBytes, bounded.chunkHits, bounded.chunkMisses,
            bounded.pagesComposed, bounded.linesLaidOut))
        assertTrue("常驻块数不能超过缓存上限", bounded.residentChunks <= 6)
        assertTrue("常驻字数不能超过 块上限×块长", bounded.peakResidentChars <= 6L * meta.chunkChars)
        assertTrue("翻页读过的字节必须远小于整本", bounded.decodedBytes * 2 < meta.contentBytes)

        val keep = engine.currentPage()!!.startOffset
        val oldPages = engine.pagesInChapter()
        val tStyle = System.nanoTime()
        val restyled = engine.applyStyle(Fixtures.BIG_STYLE)!!
        val styleMs = ms(tStyle)
        assertTrue("换字号后位置要找回", restyled.startOffset <= keep && keep < restyled.endOffset)
        assertEquals("换字号后屏幕第一行还是刚才那一行", keep, restyled.startOffset)
        val newPages = engine.pagesInChapter()
        println(String.format("[restyle] %dms 章内页数 %d -> %d keep=%,d", styleMs, oldPages, newPages, keep))
        assertTrue(newPages > oldPages)

        val tRestore = System.nanoTime()
        engine.jumpToOffset(keep)
        val restoreMs = ms(tRestore)
        println(String.format("[restore] 回到 %,d 用 %dms", keep, restoreMs))
        assertTrue("恢复位置目标 < 100ms，实测 ${restoreMs}ms", restoreMs < 100)

        val tChapters = System.nanoTime()
        val chapterPages = engine.pagesInChapter()
        val fullChapterMs = ms(tChapters)
        println(String.format("[chapter-layout] 整章排完 %d 页 用 %dms（章内总页数是排出来的，不是猜的）", chapterPages, fullChapterMs))

        val scanner = ChunkScanner(book.cache, book.chapters)
        val scan = scanner.scan("林动", limit = 200)
        println(String.format("[search] 关键词=林动 命中=%d 扫到第 %d 块 用时 %dms 截断=%b", scan.hits.size, scan.chunksScanned, scan.millis, scan.truncated))
        assertEquals(200, scan.hits.size)
        assertTrue("前 200 个命中不该扫完整本", scan.chunksScanned < meta.chunkCount)

        val full = scanner.scan("青萍之末不存在这个词", limit = 500)
        println(String.format("[search-full] 扫完全部 %,d 块 / %,d 字 用时 %dms", full.chunksScanned, full.charsScanned, full.millis))
        assertEquals(meta.chunkCount, full.chunksScanned)
        assertEquals(meta.totalChars, full.charsScanned)
        assertEquals(0, full.hits.size)
        println(String.format("[search-after] 常驻块=%d（扫完全书也不该把所有块留在内存里）", book.cache.residentChunks()))
        assertTrue(book.cache.residentChunks() <= 6)

        val rt = Runtime.getRuntime()
        rt.gc()
        val usedMb = (rt.totalMemory() - rt.freeMemory()) / (1024 * 1024)
        println(String.format("[heap] 测试进程已用 %dMB（上限 512m）", usedMb))
        engine.close()
    }
}
