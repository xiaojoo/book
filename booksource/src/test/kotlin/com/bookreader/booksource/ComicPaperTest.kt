package com.bookreader.booksource

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule as JUnitRule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * 漫画和文献这两族在规则层的判据。全部打在**手写的夹具**上，不联网 ——
 * 真实页面那一半在 `ComicPaperFixturesTest`，那一条在这台机之外是跳过的（夹具不进仓库）。
 */
class ComicPaperTest {

    @JvmField
    @JUnitRule
    val tmp = TemporaryFolder()

    private val comicJson = """
        {
          "id": "t", "name": "测试漫画", "homeUrl": "https://t.example/", "kind": "comic",
          "search": {"url": "https://t.example/s?k=%s", "item": ".row", "bookName": ".a", "author": "", "bookUrl": ".a @href"},
          "toc": {"item": "a.chap", "name": "span", "reverse": true},
          "chapter": {"image": "img.lazy @data-original"},
          "crawl": {"concurrency": 1, "minIntervalMs": 0, "maxIntervalMs": 0, "maxAttempts": 2}
        }
    """.trimIndent()

    private val bookPage = """
        <html><body>
          <div class="rows">
            <div class="row"><a class="a" href="/manga-1/">海贼王</a></div>
            <div class="row"><a class="a" href="https://t.example/manga-2/">火影</a></div>
          </div>
        </body></html>
    """.trimIndent()

    private val tocPage = """
        <html><body>
          <a class="chap" href="/chapter.php?id=3"><span>第3话</span><em>阅读</em></a>
          <a class="chap" href="/chapter.php?id=2"><span>第2话</span><em>阅读</em></a>
          <a class="chap" href="https://t.example/chapter.php?id=1"><span>第1话</span><em>阅读</em></a>
        </body></html>
    """.trimIndent()

    private val chapterPage = """
        <html><body>
          <img class="lazy" src="../assets/default.jpg" data-original="/img/1.jpg">
          <img class="lazy" src="../assets/default.jpg" data-original="https://img.example/2.jpg">
          <img class="lazy" src="../assets/default.jpg" data-original="https://img.example/3.jpg">
        </body></html>
    """.trimIndent()

    private fun rule(text: String, pages: Map<String, String>): Pair<Rule, Fetcher> =
        RuleLoader.load(text, "测试源") to MemoryFetcher(pages)

    @Test
    fun comicRuleReadsRowsChaptersAndImages() {
        val pages = mapOf(
            "https://t.example/s?k=%E6%B5%B7" to bookPage,
            "https://t.example/manga-1/" to tocPage,
            "https://t.example/chapter.php?id=1" to chapterPage,
        )
        val (r, fetch) = rule(comicJson, pages)
        val hits = SearchEngine(r, fetch).search("海")
        assertEquals("两行都该出来", 2, hits.size)
        assertEquals("相对链接要按这一页自己补成绝对", "https://t.example/manga-1/", hits[0].bookUrl)
        // 站点把目录排成 newest-first，`toc.reverse` 要把它翻回阅读顺序
        val toc = SearchEngine(r, fetch).head("https://t.example/manga-1/").chapters
        assertEquals(listOf("第1话", "第2话", "第3话"), toc.map { it.first })
        val urls = chapterImages(r, chapterPage, "https://t.example/chapter.php?id=1")
        assertEquals("三张都该数到（占位图那张靠的是 data-original，不是 src）", 3, urls.size)
        assertEquals("裸路径要按章页补全", "https://t.example/img/1.jpg", urls[0])
        assertEquals("页序就是页面上的顺序", "https://img.example/3.jpg", urls[2])
    }

    @Test
    fun chapterNameComesFromTheInnerSpanNotTheWholeAnchor() {
        val r = RuleLoader.load(comicJson, "测试源")
        val names = tocChapters(r, tocPage, "https://t.example/manga-1/")
        assertEquals("取 toc.name 那一格，不是整条 a 的文字", listOf("第1话", "第2话", "第3话"), names.map { it.first })
        assertTrue(
            "整条 a 的文字会把按钮那两个字粘进章名：" + names.map { it.first },
            names.all { !it.first.contains("阅读") },
        )
    }

    @Test
    fun aComicRuleWithoutAnImageSelectorIsRefusedAtInstall() {
        val bad = comicJson.replace("\"image\": \"img.lazy @data-original\"", "\"content\": \"#txt\"")
        val e = runCatching { RuleLoader.load(bad, "测试源") }.exceptionOrNull()
        assertTrue("得点名是哪一格、该怎么写：" + e?.message, e is RuleException && e.message!!.contains("chapter.image"))
    }

    /** 不带 @ 的写法会取到 alt 文字而不是地址：装上的那一刻就得说，否则表现是"每一章都抓到了，一张图也没有"。 */
    @Test
    fun anImageSelectorWithoutAnAttributeIsRefusedAtInstall() {
        val e = runCatching { RuleLoader.load(comicJson.replace("@data-original", ""), "测试源") }.exceptionOrNull()
        assertTrue(e is RuleException && e.message!!.contains("选择器 @属性"))
    }

    @Test
    fun unknownKindIsRefusedWhileMissingKindMeansNovel() {
        val e = runCatching { RuleLoader.load(comicJson.replace("\"comic\"", "\"manga\""), "测试源") }.exceptionOrNull()
        assertTrue("报错要把认的那几个列出来：" + e?.message, e is RuleException && e.message!!.contains("novel"))
        val novel = RuleLoader.load(OLD_NOVEL, "老源")
        assertEquals("老规则文件里没有 kind 这一格 = 小说，不能把已经装着的源判成坏的", Rule.NOVEL, novel.kind)
    }

    @Test
    fun aPaperRuleNeedsNoTocAndNoChapter() {
        val r = RuleLoader.load(PAPER_JSON, "文献源")
        assertTrue(r.isPaper)
        assertEquals("没写 toc 就是空表，界面上不摆「目录」那一行", "", r.toc.item)
        val e = runCatching { SearchEngine(r, MemoryFetcher(emptyMap())).head("https://arxiv.org/pdf/1") }.exceptionOrNull()
        assertTrue("走错路要报一句人话，不能回一本 0 章的书：" + e?.message, e is RuleException && e.message!!.contains("toc"))
    }

    // ---- JSON 那一套取值 ----

    @Test
    fun jsonRowsWalkDottedPathsWithArraysAndRebuildTheInvertedAbstract() {
        val r = RuleLoader.load(OA_JSON, "OpenAlex")
        val hits = jsonResults(r, OA_BODY)
        assertEquals("pdf_url 是 null 的那条不算一条：点下去没东西可下", 1, hits.size)
        assertEquals("Energy-Gated Attention", hits[0].bookName)
        assertEquals("数组里取第一个作者", "Ada", hits[0].author)
        assertEquals("https://example.org/a.pdf", hits[0].bookUrl)
        assertEquals("2026-05-25", hits[0].lastUpdateTime)
        assertEquals(
            "倒排索引还原回那句话",
            "Standard attention computes pairwise similarity .",
            hits[0].intro,
        )
    }

    /** 反证：位置有洞时不能说"读全了"。 */
    @Test
    fun anIncompleteInvertedAbstractSaysSo() {
        assertEquals("甲 甲（摘要读到这里为止）", invertAbstract(mapOf("甲" to listOf(0L, 2L))))
        assertEquals("不是那张表就什么都给不出", "", invertAbstract("不是表"))
    }

    // ---- 图片仓 ----

    @Test
    fun fileComicStoreSkipsWhatItAlreadyHasAndCountsOnlyRealImages() {
        val root = tmp.newFolder("comic")
        val store = FileComicStore(root)
        val urls = listOf("https://img.example/1.jpg", "https://img.example/2.jpg")
        val dir = store.putPages("https://t.example/chapter.php?id=1", urls)
        assertEquals("地址表先落盘，图一张还没取", urls, comicPageOrder(File(root, dir)))
        assertFalse(store.has(dir, urls[0]))
        store.put(dir, urls[0], jpeg())
        assertTrue(store.has(dir, urls[0]))
        assertEquals(1, store.images())
        // 再存一遍同一张：断点续抓就靠这一格不再发请求
        store.put(dir, urls[0], jpeg())
        assertEquals("同一张存两遍不该数出两张", 1, store.images())
    }

    @Test
    fun onlyRealImageBytesCountAsAnImage() {
        assertTrue(looksLikeImage(jpeg()))
        assertTrue(looksLikeImage(png()))
        assertTrue("WebP 看的是 RIFF....WEBP 那八个字节", looksLikeImage(webp()))
        assertFalse("被防盗链换成的一张 HTML 报错页不算图", looksLikeImage("<html><body>403</body></html>".toByteArray()))
        assertFalse("1x1 的占位像素不算图", looksLikeImage(ByteArray(0)))
    }

    /** 一张真 JPEG 的最小形状：SOI + 一点内容 + EOI。 */
    private fun jpeg(): ByteArray {
        val b = ByteArray(2048)
        b[0] = 0xFF.toByte()
        b[1] = 0xD8.toByte()
        b[2046] = 0xFF.toByte()
        b[2047] = 0xD9.toByte()
        return b
    }

    private fun png(): ByteArray = ByteArray(2048).also {
        byteArrayOf(0x89.toByte(), 'P'.code.toByte(), 'N'.code.toByte(), 'G'.code.toByte()).copyInto(it, 0)
    }

    private fun webp(): ByteArray = ByteArray(2048).also {
        "RIFF".toByteArray(Charsets.ISO_8859_1).copyInto(it, 0)
        "WEBP".toByteArray(Charsets.ISO_8859_1).copyInto(it, 8)
    }

    private companion object {
        val OLD_NOVEL = """
            {
              "id": "old", "name": "老小说源", "homeUrl": "https://old.example/",
              "search": {"url": "https://old.example/s?k=%s", "item": ".row", "bookName": ".a", "author": ".a", "bookUrl": ".a @href"},
              "toc": {"item": "a.chap"},
              "chapter": {"content": "#txt"}
            }
        """.trimIndent()

        val PAPER_JSON = """
            {
              "id": "arx", "name": "测试文献", "homeUrl": "https://arxiv.org/", "kind": "paper",
              "search": {
                "url": "https://export.arxiv.org/api/query?search_query=all:%s", "format": "xml",
                "item": "entry", "bookName": "title", "author": "author name", "intro": "summary",
                "bookUrl": "link[title=pdf] @href"
              }
            }
        """.trimIndent()

        val OA_JSON = """
            {
              "id": "oa", "name": "OpenAlex", "homeUrl": "https://openalex.org/", "kind": "paper",
              "search": {
                "url": "https://api.openalex.org/works?search=%s&filter=open_access.is_oa:true&per-page=30", "format": "json",
                "item": "results[]", "bookName": "title", "author": "authorships[].author.display_name",
                "lastUpdateTime": "publication_date", "intro": "abstract_inverted_index @invert",
                "bookUrl": "best_oa_location.pdf_url"
              }
            }
        """.trimIndent()

        val OA_BODY = """
            {"meta":{"count":2},"results":[
              {"id":"https://openalex.org/W1","title":"Energy-Gated Attention",
               "authorships":[{"author":{"display_name":"Ada"}},{"author":{"display_name":"Bob"}}],
               "publication_date":"2026-05-25",
               "best_oa_location":{"pdf_url":"https://example.org/a.pdf"},
               "abstract_inverted_index":{"Standard":[0],"attention":[1],"computes":[2],"pairwise":[3],"similarity":[4],".":[5]}},
              {"id":"https://openalex.org/W2","title":"Second",
               "authorships":[{"author":{"display_name":"Cy"}}],
               "publication_date":"2026-01-01",
               "best_oa_location":{"pdf_url":null}}
            ]}
        """.trimIndent()
    }
}
