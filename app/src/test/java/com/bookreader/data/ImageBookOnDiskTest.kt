package com.bookreader.data

import com.bookreader.booksource.FileComicStore
import com.bookreader.booksource.unpackComicChapter
import com.bookreader.core.ingest.ImageBookFile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * 「图片本」落到书架上那一格的样子：一个件 = 一章，页落进 `comic/<章目录>/`，
 * 和抓站那一条（[ComicImport]）交出去的是同一份形状 —— 漫画屏、目录抽屉、翻页读的就是这两张表。
 *
 * 测的是 `unpackComicChapter` 那一格本体（导入和抓站都调它），不是照它抄一份 ——
 * 抄的那一份改了实现不会红，那种判据分不出好坏。
 */
class ImageBookOnDiskTest {

    private val dir = File(System.getProperty("java.io.tmpdir"), "ibdisk-" + System.nanoTime()).apply { mkdirs() }

    private fun jpg(mark: String) =
        byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte()) + mark.toByteArray(Charsets.UTF_8)

    private fun pack(entries: Map<String, ByteArray>): File {
        val f = File(dir, "第01卷.cbz")
        ZipOutputStream(f.outputStream().buffered()).use { z ->
            entries.forEach { (n, b) -> z.putNextEntry(ZipEntry(n)); z.write(b); z.closeEntry() }
        }
        return f
    }

    @Test
    fun aCbzBecomesOneChapterOfPagesInTheSameShapeTheCrawlerWrites() {
        val staged = pack(linkedMapOf("01.jpg" to jpg("第一页"), "02.jpg" to jpg("第二页"), "10.jpg" to jpg("第十页")))
        val book = ImageBookFile.open(staged)
        assertNotNull(book)
        val bookDir = File(dir, "book").apply { mkdirs() }
        val store = FileComicStore(File(bookDir, ComicBook.DIR))
        val key = "file://" + staged.name
        var reported = -1 to 0
        val one = unpackComicChapter(book!!, store, key) { d, t -> reported = d to t }

        assertEquals(3, one.pages)
        assertEquals("进度那一头报的是（已解出, 总页）", 3 to 3, reported)
        // 章表那一行由调用方写（抓站那一头每章写一行，导入这一头写一行）：页在盘上但没这一行，
        // 漫画屏的目录抽屉就是空的 —— 这一格故意留给调用方，判据要连着它一起看
        ComicBook(bookDir).record(0, "第01卷", one.dir, one.pages)

        val read = ComicBook(bookDir)
        assertEquals("章表那一行就是漫画屏目录那一列", listOf("第01卷"), read.chapters().map { it.title })
        assertEquals(3, read.pageCount(0))
        val pages = read.pages(0)
        assertEquals("三页都要在盘上、按页序读得回来", 3, pages.size)
        assertEquals("第一页", String(pages[0].readBytes().drop(4).toByteArray(), Charsets.UTF_8))
        assertEquals("第十页", String(pages[2].readBytes().drop(4).toByteArray(), Charsets.UTF_8))
        assertEquals("章目录名要由那一格交回来，调用方不该自己再算一次散列", one.dir, read.chapters().first().dir)
        dir.deleteRecursively()
    }

    @Test
    fun pagesThatAreNotImagesAreNotWritten() {
        // 「名字像图、字节不是图」的那种页：解包那一头就不认它，章表里那一行报的张数也不能把它算进去
        val staged = pack(linkedMapOf("01.jpg" to jpg("第一页"), "02.jpg" to "不是图".toByteArray(Charsets.UTF_8)))
        val book = ImageBookFile.open(staged)!!
        val bookDir = File(dir, "book2").apply { mkdirs() }
        val one = unpackComicChapter(book, FileComicStore(File(bookDir, ComicBook.DIR)), "file://x")
        ComicBook(bookDir).record(0, "只有真图算页", one.dir, one.pages)
        assertEquals(1, one.pages)
        assertEquals(1, ComicBook(bookDir).pages(0).size)
        dir.deleteRecursively()
    }

    @Test
    fun anEmptyPackageOpensAsNothing() {
        val staged = pack(linkedMapOf("readme.txt" to "这不是图".toByteArray()))
        val book = ImageBookFile.open(staged)
        assertEquals("开不成 = null，调用方那句错误要在界面上说清楚", null, book)
        dir.deleteRecursively()
    }
}
