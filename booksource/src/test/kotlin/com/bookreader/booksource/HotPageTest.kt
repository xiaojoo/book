package com.bookreader.booksource

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 榜单翻页那一格（`hot.page`）：第 1 页永远走 `hot.url`，第 2 页起才用模板；没写模板就没有第 2 页。
 *
 * 三条判据各挡一种"看着像在动其实没动"：
 * - 第 1 页也去套模板 → 首页地址和翻页地址不同形状的那家站，精选第一屏就是空的；
 * - 没模板还照打 → 每一页都拿回同一份榜单，「加载更多」滚到底一本都不增；
 * - 模板里忘了 %n → 同上，而且这种写法装得上、只在手机上才看得出来。
 */
class HotPageTest {

    private val page = """{"url":"https://x/","page":"https://x/list-%n.html","group":"div","item":"a","bookName":"a","bookUrl":"a @href"}"""

    private fun ruleOf(hotJson: String): Rule = RuleLoader.load(
        """{"id":"t","name":"测试源","homeUrl":"https://x/","toc":{"item":"a"},"chapter":{"content":"p"},"hot":$hotJson}""", "t",
    )

    private class Script(private val body: String) : Fetcher {
        val urls = ArrayList<String>()
        override fun fetch(req: PageRequest): Page {
            urls += req.url
            return Page(req.url, null, body.toByteArray())
        }
    }

    private val listHtml = """<html><body><div><a href="/b/1">一本</a><a href="/b/2">二本</a></div></body></html>"""

    @Test
    fun firstPageAlwaysUsesThePlainUrl() {
        val h = ruleOf(page).hot!!
        assertEquals("https://x/", h.pageUrl(1))
        assertEquals("https://x/list-2.html", h.pageUrl(2))
        assertEquals("https://x/list-17.html", h.pageUrl(17))
    }

    @Test
    fun aSourceWithoutATemplateHasNoSecondPage() {
        val h = ruleOf("""{"url":"https://x/","group":"div","item":"a","bookName":"a","bookUrl":"a @href"}""").hot!!
        assertEquals("https://x/", h.pageUrl(1))
        assertEquals("没写 page 就是翻不动：回空串让调用方停下", "", h.pageUrl(2))
        val f = Script(listHtml)
        assertEquals(0, SearchEngine(ruleOf("""{"url":"https://x/","group":"div","item":"a","bookName":"a","bookUrl":"a @href"}"""), f).hot(3).size)
        assertTrue("没有下一页就不该发那一枪（实际打了 ${f.urls}）", f.urls.isEmpty())
    }

    @Test
    fun secondPageFetchesThePagedUrl() {
        val r = ruleOf(page)
        val f = Script(listHtml)
        val got = SearchEngine(r, f).hot(2)
        assertEquals(listOf("https://x/list-2.html"), f.urls)
        assertEquals(listOf("一本", "二本"), got.map { it.bookName })
    }

    @Test
    fun aTemplateWithoutPageNumberIsRejectedOnInstall() {
        val e = runCatching {
            ruleOf("""{"url":"https://x/","page":"https://x/list.html","group":"div","item":"a","bookName":"a","bookUrl":"a @href"}""")
        }.exceptionOrNull()
        assertTrue("该拒装却装上了：${e?.message}", e is RuleException)
        assertTrue("说的是 %n：" + e?.message, e?.message?.contains("%n") == true)
    }
}
