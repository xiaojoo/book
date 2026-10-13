package com.bookreader.data

import com.bookreader.booksource.comicImageName
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * 一本漫画在磁盘上的那两份表：`comic/chapters.tsv`（哪些章有图）和每一章里的 `pages.tsv`
 * （那几页的地址，行序就是页序）。
 *
 * 这两份表是"下载那一头"和"阅读那一头"唯一的接头，而写和读分在两个文件里 ——
 * 判据打在读这一头：写进去什么形状，读回来就必须是什么形状。
 */
class ComicBookTest {

    @JvmField
    @Rule
    val tmp = TemporaryFolder()

    /** 造一本"章号 - 章名 - 那几页地址"的书；图和真下载一样按地址散列落成文件。 */
    private fun book(vararg rows: Triple<Int, String, List<String>>): File {
        val dir = tmp.newFolder("book-" + System.nanoTime())
        val comic = File(dir, BookLibrary.COMIC_FILE).apply { mkdirs() }
        File(comic, BookLibrary.CHAPTERS_TSV).writeText(
            rows.joinToString("\n") { (i, name, urls) -> "$i\td$i\t${urls.size}\t$name" } + "\n",
            Charsets.UTF_8,
        )
        rows.forEach { (i, _, urls) ->
            val box = File(comic, "d$i").apply { mkdirs() }
            File(box, "pages.tsv").writeText(urls.joinToString("\n") + "\n", Charsets.UTF_8)
            urls.forEach { u -> File(box, comicImageName(u)).writeBytes(ByteArray(4)) }
        }
        return dir
    }

    @Test
    fun chaptersComeBackInReadingOrderWithTheirPageCount() {
        val dir = book(
            Triple(0, "第1话", listOf("https://i/1.jpg", "https://i/2.jpg")),
            Triple(1, "第2话", listOf("https://i/3.jpg")),
        )
        val b = ComicBook(dir)
        assertEquals(listOf("第1话", "第2话"), b.chapters().map { it.title })
        assertEquals(listOf(2, 1), b.chapters().map { it.pages })
        // 页序只能来自 pages.tsv 的行序：文件名是地址的散列，按它排序就是乱序
        assertEquals(
            listOf("https://i/1.jpg", "https://i/2.jpg").map { comicImageName(it) },
            b.pages(0).map { it.name },
        )
    }

    @Test
    fun anAbsentBookReadsAsNoChapters() {
        val b = ComicBook(File(tmp.root, "没有这一本"))
        assertEquals(emptyList<ComicBook.Chapter>(), b.chapters())
        assertEquals(emptyList<File>(), b.pages(0))
    }

    /** 站点补过章、或者那一页压根没下下来：只有真在磁盘上的那张才算一页。 */
    @Test
    fun aPageWhoseBytesNeverArrivedIsNotAPage() {
        val dir = book(Triple(0, "第1话", listOf("https://i/1.jpg", "https://i/2.jpg")))
        File(dir, "${BookLibrary.COMIC_FILE}/d0").let { File(it, comicImageName("https://i/2.jpg")).delete() }
        assertEquals("这一话该只读得出那一张真落地的图", 1, ComicBook(dir).pages(0).size)
    }

    @Test
    fun missingChaptersAreTheOnesInTheTocThatHaveNoImages() {
        val dir = book(Triple(3, "第4话", listOf("https://i/9.jpg")))
        val b = ComicBook(dir)
        assertTrue(b.has(3))
        assertFalse("章号不是行号：第 1 行是第 4 话，别把第 1 话也算成有", b.has(0))
        assertEquals(listOf(0, 1, 2, 4), b.missing(5))
    }

    /** 章名是站点给的，带制表符就不能把一行劈成两行 —— 写的时候换空格，读回来还是一行。 */
    /** 站点把页数缀在章名后面（图库漫画那种），存下的旧行读回来要只剩章名。 */
    @Test
    fun aPageCountSuffixedByTheSiteIsNotPartOfTheChapterName() {
        val dir = tmp.newFolder("suffix-" + System.nanoTime())
        val comic = File(dir, BookLibrary.COMIC_FILE).apply { mkdirs() }
        File(comic, BookLibrary.CHAPTERS_TSV).writeText(
            "0\td0\t45\t第1卷 (45p)\n1\td1\t48\t第2话（48P）\n2\td2\t3\t第3卷 神之谷 (大逃杀)\n",
            Charsets.UTF_8,
        )
        val got = ComicBook(dir).chapters().map { it.title }
        assertEquals(listOf("第1卷", "第2话", "第3卷 神之谷 (大逃杀)"), got)
    }

    /** 章表是一张流水：抓一半停了、又补抓一遍，同一章会有两行 —— 取最后写的那一行。 */
    @Test
    fun theLastRowWrittenForAChapterWins() {
        val dir = tmp.newFolder("append-" + System.nanoTime())
        val b = ComicBook(dir)
        b.record(0, "第1话", "d0", 3)
        b.record(1, "第2话", "d1", 2)
        // 第二次补抓：同一章的张数变了（站点把那章拆了两页出来）
        b.record(0, "第1话", "d0", 5)
        val back = ComicBook(dir).chapters()
        assertEquals("两行同一章不该在目录里出现两次", 2, back.size)
        assertEquals("补抓之后该读到新的那个张数", 5, back.first { it.index == 0 }.pages)
    }

    @Test
    fun aTabInsideAChapterTitleDoesNotSplitTheRow() {
        val dir = tmp.newFolder("tab-" + System.nanoTime())
        val comic = File(dir, BookLibrary.COMIC_FILE).apply { mkdirs() }
        File(comic, BookLibrary.CHAPTERS_TSV).writeText("0\td0\t1\t第1话\t加_more\n", Charsets.UTF_8)
        val d0 = File(comic, "d0")
        d0.mkdirs()
        File(d0, "pages.tsv").writeText("https://i/1.jpg\n", Charsets.UTF_8)
        File(d0, comicImageName("https://i/1.jpg")).writeBytes(ByteArray(4))
        val c = ComicBook(dir).chapters().single()
        assertEquals("第1话 加_more", c.title.replace('\t', ' '))
        assertEquals(0, c.index)
    }
}
