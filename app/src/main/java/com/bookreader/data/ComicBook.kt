package com.bookreader.data

import com.bookreader.booksource.comicImageName
import com.bookreader.booksource.comicPageOrder
import java.io.File

/**
 * 读一本漫画：目录里有哪些章、某一章的第 N 页是哪个文件。
 *
 * 写的那一头在 [ComicImport]（抓完落盘 + 写两份表），这一头只认那两份表的格式，
 * 所以格式改了不会有一处读、一处写各说各话。
 *
 * 章号是**源站目录里的顺序**，不是"下载完成的顺序"：并发抓几十章时完成顺序是乱的，
 * 而人要翻的是"第 37 话"。
 */
class ComicBook(bookDir: File) {

    /** 一章：章号、章名、仓里那个目录名、以及当时数下的张数。 */
    data class Chapter(val index: Int, val title: String, val dir: String, val pages: Int) {
        /** 这一章在磁盘上有图（`pages.tsv` 在、至少一张图在）。 */
        val present: Boolean get() = pages > 0
    }

    private val comic = File(bookDir, BookLibrary.COMIC_FILE)

    /** 几条抓取线程同时在写这一张流水（下载器那一头是并发线程池）。 */
    private val lock = Any()

    /** `chapters.tsv` 只读一次：目录抽屉一屏就要几十行，每行开一次文件没必要。 */
    private val rows: List<Chapter> by lazy { read() }

    /**
     * 抓完一章就记一行（append，不等整本结束）。
     *
     * 为什么要 append 而不是最后整张重写：一本几千张图，抓一半被系统收回、或者你按了暂停是常态。
     * 只在结尾写一次的话，磁盘上那几十章的图就没有人记得是哪一话、叫什么名 ——
     * 真机现场：`chapters.tsv` 0 行、`comic/` 下几百个章目录各装着 16~21 张图，界面上那句
     * 「这一本还没下到图」就成了假话（图明明在）。
     * 写失败（磁盘满、目录没建起来）不当崩：那一章下次补抓，章表少一行只是少一行。
     */
    fun record(index: Int, title: String, dir: String, pages: Int) = synchronized(lock) {
        runCatching {
            comic.mkdirs()
            File(comic, BookLibrary.CHAPTERS_TSV).appendText(
                "$index\t$dir\t$pages\t${title.replace('\t', ' ').replace('\n', ' ')}\n", Charsets.UTF_8,
            )
        }
        Unit
    }

    /**
     * `chapters.tsv` 是一张流水，不是一张快照：同一章补抓过两遍就有两行。
     * 按章号取**最后写进去的那一行**（那张才是现在磁盘上的张数），顺序按章号排。
     */
    private fun read(): List<Chapter> {
        val byIndex = LinkedHashMap<Int, Chapter>()
        runCatching { File(comic, BookLibrary.CHAPTERS_TSV).readLines(Charsets.UTF_8) }
            .getOrDefault(emptyList())
            .forEach { l ->
                val c = l.split('\t')
                // 半截行（写到一半被杀）解析不出来就跳过：它不该把整本目录判成坏
                if (c.size < 4) return@forEach
                val i = c[0].toIntOrNull() ?: return@forEach
                val n = c[2].toIntOrNull() ?: return@forEach
                // 尾部那截「(45p)」是站点挂在章名后面标的页数，不是名字的一部分（图库漫画那种）。
                // 新下载在规则那一头就用 `toc.name` 只取名字那一格；这里管的是**已经躺在架上**的那些行 ——
                // 它们是在那格加上之前存下的，不洗一下目录里几百行每行都拖着一串 p
                byIndex[i] = Chapter(i, c.drop(3).joinToString("\t").replace(TRAILING_PAGES, "").trim(), c[1], n)
            }
        return byIndex.values.sortedBy { it.index }
    }

    /** 已下载的章，按章号顺序。灰掉的那几百章不在这里（它们在整本目录里，见 [missing]）。 */
    fun chapters(): List<Chapter> = rows.filter { it.present }

    /** 这一章那些图，按页序。`pages.tsv` 是页序的唯一一份依据 —— 文件名是散列，排不出来。 */
    fun pages(index: Int): List<File> {
        val ch = rows.firstOrNull { it.index == index } ?: return emptyList()
        val box = File(comic, ch.dir)
        return comicPageOrder(box).map { File(box, comicImageName(it)) }.filter { it.isFile }
    }

    /** 这一章的第一张图 —— 目录那一行行首那颗小缩略图就是它。没下过 = null。 */
    fun thumbnail(index: Int): File? = pages(index).firstOrNull()

    /** 这一章有几页在本地（0 = 没下）。 */
    fun pageCount(index: Int): Int = rows.firstOrNull { it.index == index }?.pages ?: 0

    /** 目录里第几章有没有在本地。 */
    fun has(index: Int): Boolean = rows.any { it.index == index && it.present }

    /** 整本目录里那些还没下下来的章的章号（`tocChapters` 减去有的）。目录抽屉要把它们灰着摆出来。 */
    fun missing(tocCount: Int): List<Int> {
        val have = rows.filter { it.present }.map { it.index }.toSet()
        return (0 until tocCount).filter { it !in have }
    }

    companion object {
        /** 一本漫画的图都在这一层：`filesDir/books/<id>/comic/<章目录>/`。 */
        const val DIR = "comic"

        /** 章名尾部那截站点标的页数：`第1卷 (45p)` / `第1话（48P）`。全角半号都收，只收在结尾的。 */
        private val TRAILING_PAGES = Regex("""\s*[（(]\d+\s*[pP]\s*[)）]\s*$""")
    }
}
