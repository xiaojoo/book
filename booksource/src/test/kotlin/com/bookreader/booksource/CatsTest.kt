package com.bookreader.booksource

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 站点真实类目那一格（`cats`）：摘表、翻页模板、类目页用哪套取值。
 *
 * 夹具全是测试里现造的 —— 判的是格式，不是某一家站今天长什么样（真站那一份的量法在探针里）。
 */
class CatsTest {

    private val nav = """
        <html><body>
        <a href="/m/list-3-0-0-0-0-0-1">愛情</a>
        <a href="/m/list-2-0-0-0-0-0-1">幽默</a>
        <a href="/m/list-2-0-0-0-0-0-1">幽默重复</a>
        <a href="/">回到首页</a>
        <a href="/m/list-13-0-0-0-0-0-1">冒險</a>
        </body></html>
    """.trimIndent()

    private val listPage = """
        <html><body><div class="row comic-list">
          <a href="/comic/A1"><div class="cover"><img data-src="/c/a1.jpg"></div><div class="title">一本</div></a>
          <a href="/comic/A2"><div class="cover"><img data-src="/c/a2.jpg"></div><div class="title">二本</div></a>
        </div></body></html>
    """.trimIndent()

    private class Site(private val pages: Map<String, String>) : Fetcher {
        val urls = ArrayList<String>()
        override fun fetch(req: PageRequest): Page {
            urls += req.url
            return Page(req.url, null, (pages[req.url] ?: "").toByteArray())
        }
    }

    private fun ruleOf(catsJson: String, hotJson: String = ""): Rule = RuleLoader.load(
        """{"id":"t","name":"测试源","homeUrl":"https://x.com/","toc":{"item":"a"},"chapter":{"content":"p"},"cats":$catsJson""" +
            (if (hotJson.isEmpty()) "" else ""","hot":$hotJson""") + "}", "t",
    )

    private val hotJson = """{"url":"https://x.com/","group":"div.comic-list","item":"a[href^=\"/comic/\"]","bookName":"div.title","bookUrl":"@href","cover":"img @data-src"}"""

    @Test
    fun theTaxonomyDedupesByAddressAndSkipsTheHomeLink() {
        val r = ruleOf("""{"at":"https://x.com/","item":"a[href^=\"/m/list-\"]","link":"@href","max":10}""", hotJson)
        val got = SearchEngine(r, Site(mapOf("https://x.com/" to nav))).cats()
        assertEquals("重复的那颗只留一个、指向首页那颗不算类目：${got.map { it.first }}",
            listOf("愛情", "幽默", "冒險"), got.map { it.first })
        assertEquals("地址补全成绝对", "https://x.com/m/list-3-0-0-0-0-0-1", got[0].second)
    }

    @Test
    fun maxCapsTheList() {
        val r = ruleOf("""{"item":"a[href^=\"/m/list-\"]","max":2}""", hotJson)
        val got = SearchEngine(r, Site(mapOf("https://x.com/" to nav))).cats()
        assertEquals("站点首页一次给几十颗，取前 2 颗就该停", listOf("愛情", "幽默"), got.map { it.first })
    }

    @Test
    fun pageTemplatesCoverTheThreeShapesWeMeasured() {
        val tail = ruleOf("""{"item":"a","page":"%U%n"}""", hotJson).cats!!
        assertEquals("%U 只剥数字、不剥那个连字符（模板里就别再补一个 -）", "https://x.com/m/list-2-0-0-0-0-0-2", tail.pageUrl("https://x.com/m/list-2-0-0-0-0-0-1", 2))
        assertEquals("第 1 页永远走类目地址本身", "https://x.com/m/list-2-0-0-0-0-0-1", tail.pageUrl("https://x.com/m/list-2-0-0-0-0-0-1", 1))
        val query = ruleOf("""{"item":"a","page":"%u&page=%n"}""", hotJson).cats!!
        assertEquals("瓜子：查询参数追加", "https://x.com/category.php?tag=x&page=3", query.pageUrl("https://x.com/category.php?tag=x", 3))
        val none = ruleOf("""{"item":"a"}""", hotJson).cats!!
        assertEquals("没写模板就是翻不动：回空串，一枪都不发", "", none.pageUrl("https://x.com/a", 2))
    }

    @Test
    fun aCategoryPageReusesTheHotSelectorsUnlessOverridden() {
        val r = ruleOf("""{"item":"a[href^=\"/m/list-\"]","page":"%U%n"}""", hotJson)
        val site = Site(mapOf("https://x.com/m/list-3-0-0-0-0-0-2" to listPage))
        val got = SearchEngine(r, site).catBooks("https://x.com/m/list-3-0-0-0-0-0-1", 2)
        assertEquals(listOf("https://x.com/m/list-3-0-0-0-0-0-2"), site.urls)
        assertEquals(listOf("一本", "二本"), got.map { it.bookName })
        assertEquals("封面走 hot.cover 那一格", "https://x.com/c/a1.jpg", got[0].coverUrl)
    }

    @Test
    fun aDifferentCardShapeIsTakenFromCatsList() {
        // 图库：榜单页是 .top-card，类目页是 swiper-card-item —— 复用 hot 那一套就会抓到 0 本
        val other = """{"group":"div.swiper","item":"a","bookName":"a @title","bookUrl":"a @href","cover":"img @data-original"}"""
        val r = ruleOf("""{"item":"a[href^=\"/m/list-\"]","list":$other}""", hotJson)
        val html = """<html><body><div class="swiper">
            <div class="swiper-card-item"><a href="/manga-9/" title="某本"><img data-original="https://cdn/9.jpg"></a></div>
        </div></body></html>"""
        val got = SearchEngine(r, Site(mapOf("https://x.com/m/list-1" to html))).catBooks("https://x.com/m/list-1")
        assertEquals("类目页用它自己那套取值", listOf("某本"), got.map { it.bookName })
        assertEquals("封面走 cats.list 那一格", "https://cdn/9.jpg", got[0].coverUrl)
    }

    @Test
    fun badCatsSectionsAreRejectedOnInstall() {
        val noItem = runCatching { ruleOf("""{"at":"https://x.com/"}""", hotJson) }.exceptionOrNull()
        assertTrue("没写 item 该拒：" + noItem?.message, noItem is RuleException)
        val noPercent = runCatching { ruleOf("""{"item":"a","page":"https://x.com/list"}""", hotJson) }.exceptionOrNull()
        assertTrue("page 没带 %n 该拒：" + noPercent?.message, noPercent?.message?.contains("%n") == true)
        val noShape = runCatching { ruleOf("""{"item":"a"}""") }.exceptionOrNull()
        assertTrue("既没 hot 又没 cats.list 该拒：" + noShape?.message, noShape?.message?.contains("cats.list") == true)
    }
}
