package com.bookreader.booksource

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule as JUnitRule
import org.junit.rules.TemporaryFolder
import org.junit.Test

/**
 * 「只补目录里那一章」那一颗的章号。
 *
 * 这条判据钉的是最容易错的那一格：整本下载走的是 `mapIndexed`，章号 = 这一次抓的列表里的位置；
 * 单章那一枪如果照抄，补第 37 话就会落成章号 0 —— 章表 append 一行 0，界面上第 37 行还是灰的，
 * 而磁盘上那一章的图明明在（和"chapters.tsv 0 行、图几百张"是同一族事故）。
 * 站点目录是 newest-first（`toc.reverse` 翻回阅读顺序），所以第 1 号该是「第1话」而不是「第3话」。
 */
    /** 一张够格的假 JPEG：仓只认"像图且不小于一张真页面下限"那两样（1024 字节那条判据在 downloader 里）。 */
private val jpeg = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte()) +
    ByteArray(1024) { (it % 251).toByte() }

class ChapterAtTest {

    @JvmField
    @JUnitRule
    val tmp = TemporaryFolder()

    private val rule = RuleLoader.load(
        """
        {
          "id": "t", "name": "测试漫画", "homeUrl": "https://t.example/", "kind": "comic",
          "toc": {"item": "a.chap", "name": "span", "reverse": true},
          "chapter": {"image": "img.lazy @data-original"},
          "crawl": {"concurrency": 1, "minIntervalMs": 0, "maxIntervalMs": 0, "maxAttempts": 1}
        }
        """.trimIndent(),
        "测试源",
    )

    private val tocPage = """
        <html><body>
          <a class="chap" href="/chapter.php?id=3"><span>第3话</span></a>
          <a class="chap" href="/chapter.php?id=2"><span>第2话</span></a>
          <a class="chap" href="/chapter.php?id=1"><span>第1话</span></a>
        </body></html>
    """.trimIndent()

    private val chapterPage = """
        <html><body>
          <img class="lazy" src="../assets/default.jpg" data-original="https://img.example/1.jpg">
          <img class="lazy" src="../assets/default.jpg" data-original="https://img.example/2.jpg">
          <img class="lazy" src="../assets/default.jpg" data-original="https://img.example/3.jpg">
        </body></html>
    """.trimIndent()

/** 记每一枪打了哪个地址：单章那一枪不许顺手把整本抓一遍。 */
    private class Site(private val toc: String, private val chapter: String) : Fetcher {
        val gets = ArrayList<String>()
        override fun fetch(req: PageRequest): Page {
            gets += req.url
            return when {
                req.url.endsWith("/manga-1/") -> Page(req.url, null, toc.toByteArray())
                req.url.contains("chapter.php") -> Page(req.url, null, chapter.toByteArray())
                else -> Page(req.url, null, jpeg)
            }
        }
    }

    @Test
    fun oneChapterKeepsItsPlaceInTheWholeBookOutline() {
        val site = Site(tocPage, chapterPage)
        val store = FileComicStore(tmp.newFolder("comic"))
        val got = ComicDownloader(rule, site, store, sleep = { }, clock = { 0L })
            .chapterAt("https://t.example/manga-1/", 2)
        assertNotNull("第 2 号该抓得到", got)
        val ch = got!!
        assertEquals("第 2 号是阅读顺序里的「第3话」（站点那一页是 newest-first，翻过一遍才算号）", "第3话", ch.title)
        assertEquals("章号按整本目录记，不是按「这一次只抓了一章」的位置", 2, ch.index)
        assertTrue("打的该是第3话那一页：" + site.gets, site.gets.any { it.contains("id=3") })
        assertEquals("三张都该数到", 3, ch.pages)
        assertEquals("图真的落进仓了", 3, store.images())
    }

    @Test
    fun oneChapterDoesNotFetchTheRestOfTheBook() {
        val site = Site(tocPage, chapterPage)
        ComicDownloader(rule, site, FileComicStore(tmp.newFolder("one")), sleep = { }, clock = { 0L })
            .chapterAt("https://t.example/manga-1/", 2)
        // 目录一枪 + 章页一枪 + 三张图；另外两话的章页一次都不该被打
        assertEquals("打了 ${site.gets} 枪", 5, site.gets.size)
        assertTrue("只该打第3话那一页：" + site.gets, site.gets.count { it.contains("chapter.php") } == 1)
        assertTrue(site.gets.any { it.contains("id=3") })
    }

    @Test
    fun anIndexTheSiteNoLongerHasSaysSoInsteadOfGuessing() {
        val site = Site(tocPage, chapterPage)
        val store = FileComicStore(tmp.newFolder("gone"))
        val got = ComicDownloader(rule, site, store, sleep = { }, clock = { 0L })
            .chapterAt("https://t.example/manga-1/", 9)
        assertNull("目录里只有三话，第 9 号该回 null 让界面说那句话", got)
        assertEquals("只该打目录那一枪，别拿一个不存在的号去猜章页：" + site.gets, 1, site.gets.size)
        assertEquals("一张都不该落仓", 0, store.images())
    }
}
