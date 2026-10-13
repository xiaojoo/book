package com.bookreader.booksource

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Collections

private val bookUrl = "http://www.example-hu.com/0/321/"
private val one = bookUrl + "1.html"
private val two = bookUrl + "2.html"

/**
 * 一章切几页的站（`123.html` → `123_2.html` → …）：下载要把后面的半章跟回来。
 *
 * 全部离线 —— `Fetcher` 给脚本化的假页，所以"跟了几页"是数出来的请求条数，不是跑一遍看感觉。
 * 判据都成对：跟到底的那条旁边一定摆着"不该跟的"（那是下一章、那是书主页、自环、留空当对照），
 * 少的判据红了会分不清是机制坏了还是夹具变了。链接摆正文框里（梦书）还是框外（书海阁）两种都各有一条。
 */
class ChapterPageFollowTest {

    private val hit = BookHit("shuhu", "小说虎", "分页测试", "某某", bookUrl)

    private class Script(private val pages: Map<String, String>) : Fetcher {
        val requests = Collections.synchronizedList(ArrayList<String>())
        override fun fetch(req: PageRequest): Page {
            requests.add(req.url)
            val body = pages[req.url] ?: error("夹具里没有这一页：${req.url}")
            return Page(req.url, null, body.toByteArray(Charsets.UTF_8))
        }

        /** 书页只打一次拿目录，剩下的每一条都是"某一章的某一页"。 */
        fun chapterPages() = requests.filter { it != bookUrl }
    }

    private val toc = "<html><body><div id=\"intro\">一段简介。</div><div id=\"list\"><dl>" +
        "<dd><a href=\"/0/321/1.html\">第一章 起</a></dd><dd><a href=\"/0/321/2.html\">第二章 承</a></dd>" +
        "</dl></div></body></html>"

    /** 正文容器里带一个"下一页"链接；`href = null` 就是这一页到此为止。 */
    private fun page(body: String, href: String?) =
        "<html><body><div id=\"content\">$body" +
            (if (href == null) "" else "<p class=\"pag\"><a class=\"pagenext\" href=\"$href\">下一页</a></p>") +
            "</div></body></html>"

    /** 链接摆在正文容器**外面**（导航条那种）。 */
    private fun pageOutside(body: String, href: String) =
        "<html><body><div id=\"content\">$body</div><p><a class=\"pagenext\" href=\"$href\">下一页</a></p></body></html>"

    private fun rule(nextPage: String): Rule {
        val text = fixtureText("/rules/shuhu.json")
            .replace("\"content\": \"#content\",", "\"content\": \"#content\",\n    \"nextPage\": \"$nextPage\",")
        assertTrue("夹具规则里得先有 chapter.content 那一格，否则这条测的是别的东西", text.contains("\"nextPage\""))
        return RuleLoader.load(text, "shuhu")
    }

    private fun download(r: Rule, pages: Map<String, String>): Pair<DownloadOutcome, Script> {
        val s = Script(pages + mapOf(bookUrl to toc))
        val out = BookDownloader(r, s, MemoryStore(), sleep = {}, clock = { 1_000L }).download(hit)
        return out to s
    }

    /** 第一章的第 n 个分页（n=1 就是它自己）。 */
    private fun page(n: Int) = if (n == 1) one else bookUrl + "1_$n.html"

    @Test
    fun followsEveryPageOfTheSameChapterAndJoinsThemInOrder() {
        val (out, s) = download(
            rule(".pagenext"),
            mapOf(
                page(1) to page("第一段。", "1_2.html"),
                page(2) to page("第二段。", "1_3.html"),
                page(3) to page("第三段。", null),
                two to page("二章正文。", null),
            ),
        )
        println("[跟分页] 请求=" + s.chapterPages() + " 缺章=" + out.failed)
        assertTrue("不该有缺章：" + out.failed, out.complete)
        assertEquals("第一章的三页各打一次，一章一页的站也不许多发",
            listOf(one, page(2), page(3), two), s.chapterPages().sorted())
        assertEquals("第一章要拼成整章", "第一章 起\n第一段。\n\n第二段。\n\n第三段。", out.body.substringBefore("第二章 承").trim())
    }

    /** 命门：梦书中文把 `class="next"` 摆在正文框里，而它是**下一章**。跟过去就是把整本书吞进第一章。 */
    @Test
    fun doesNotFollowANextChapterLinkEvenInsideTheContentBox() {
        val (out, s) = download(rule(".pagenext"), mapOf(one to page("一章正文。", "2.html"), two to page("二章正文。", null)))
        assertEquals("下一章不能被当成分页重复抓：2.html 只该出现一次", 1, s.chapterPages().count { it == two })
        assertEquals("第一章只打自己那一页", 1, s.chapterPages().count { it == one })
        assertTrue(out.body.contains("一章正文。"))
        assertFalse("二章正文不能混进第一章那一格", out.body.indexOf("二章正文。") < out.body.indexOf("第二章 承"))
        assertTrue(out.complete)
    }

    /** 书海阁的真页就是这样：`#pager_next` 在页顶、`#A3` 在页尾 `.bottem2`，两个都在 `#content` 外面。 */
    @Test
    fun followsThePagerLinkEvenWhenItSitsOutsideTheContentBox() {
        val (out, s) = download(
            rule(".pagenext"),
            mapOf(
                one to pageOutside("一章前半。", "1_2.html"),
                page(2) to page("一章后半。", null),
                two to page("二章正文。", null),
            ),
        )
        assertTrue("框外那颗也得跟，否则后半章无声丢掉：" + out.failed, out.complete)
        assertEquals("整章拼齐", "第一章 起\n一章前半。\n\n一章后半。", out.body.substringBefore("第二章 承").trim())
        assertFalse("正文框外面的链接文字不进正文", out.body.contains("下一页"))
    }

    /** 框内外各有一颗时取框内那颗：那是站点自己给这段正文配的翻页控件。 */
    @Test
    fun prefersTheLinkInsideTheContentBoxWhenThePageHasBoth() {
        val both = "<html><body><div id=\"content\"><p>正文。</p>" +
            "<a class=\"pagenext\" href=\"1_2.html\">框内</a></div>" +
            "<p><a class=\"pagenext\" href=\"1_9.html\">框外</a></p></body></html>"
        val (out, s) = download(
            rule(".pagenext"),
            mapOf(
                one to both,
                page(2) to page("第二页。", null),
                bookUrl + "1_9.html" to page("不该来的那一页。", null),
                two to page("二章正文。", null),
            ),
        )
        assertEquals("只该跟框内那颗", listOf(one, page(2), two), s.chapterPages().sorted())
        assertFalse(out.body.contains("不该来的那一页"))
    }

    @Test
    fun stopsWhenTheSitePointsBackAtAPageAlreadyTaken() {
        val (out, s) = download(
            rule(".pagenext"),
            mapOf(
                page(1) to page("第一段。", "1_2.html"),
                page(2) to page("第二段。", "1.html"),
                two to page("二章正文。", null),
            ),
        )
        assertEquals("绕回走过的页要停，不能无限跟", listOf(one, page(2), two), s.chapterPages().sorted())
        assertTrue("第一章两段都在", out.body.contains("第一段。") && out.body.contains("第二段。"))
        assertTrue("这一章算齐，不算缺：" + out.failed, out.complete)
    }

    /** 到上限还没完 = 这一章**失败**，不交半截：缺章界面会写出来，半截的只表现为"读到一半下一章就来了"。 */
    @Test
    fun aChapterThatNeverEndsIsReportedFailedInsteadOfLandingTruncated() {
        val pages = LinkedHashMap<String, String>()
        for (i in 1..BookDownloader.MAX_PAGES_PER_CHAPTER + 5) {
            pages[bookUrl + "1_" + i + ".html"] = page("第$i 页。", "1_" + (i + 1) + ".html")
        }
        pages[one] = page("第一段。", "1_1.html")
        pages[two] = page("二章正文。", null)
        val (out, s) = download(rule(".pagenext"), pages)
        println("[分页上限] 第一章打了几页=" + s.chapterPages().count { it != two } + " 缺章=" + out.failed)
        assertEquals("上限写的就是 20 页（改了它要知道，一章的代价按它算）", 20, BookDownloader.MAX_PAGES_PER_CHAPTER)
        assertEquals("到顶就收手", BookDownloader.MAX_PAGES_PER_CHAPTER, s.chapterPages().count { it != two })
        assertEquals("第一章要整章记为缺", listOf("第一章 起"), out.failed)
        assertFalse("半截正文不许混进书里", out.body.contains("第3 页。"))
        assertTrue("第二章照常入库", out.body.contains("二章正文。"))
    }

    /** 对照：`nextPage` 留空（梦书、爱下书那种一章一页的源）时，一章只打一次，后面有链接也不跟。 */
    @Test
    fun aRuleWithoutNextPageStillFetchesExactlyOnePagePerChapter() {
        val (out, s) = download(
            rule(""),
            mapOf(one to page("一章正文。", "1_2.html"), page(2) to page("半章。", null), two to page("二章正文。", null)),
        )
        assertEquals("没写这一格就不该跟任何链接", listOf(one, two), s.chapterPages().sorted())
        assertFalse(out.body.contains("半章。"))
        assertTrue(out.complete)
    }

    /** 分页导航就摆在正文框里面（多数站如此）：它的字不能变成章节正文里的一行。 */
    @Test
    fun thePagerNavigationBarIsNotGluedIntoTheChapterText() {
        val nav = "<div class=\"pag\"><a href=\"/0/321/1.html\">上一章</a><a href=\"/0/321/\">返回目录</a>" +
            "<a class=\"pagenext\" href=\"1_2.html\">下一页</a></div>"
        val (out, s) = download(
            rule(".pagenext"),
            mapOf(
                page(1) to "<html><body><div id=\"content\"><p>第一段正文。</p>$nav</div></body></html>",
                page(2) to "<html><body><div id=\"content\"><p>第二段正文。</p>$nav</div></body></html>".replace("1_2.html", "1_3.html"),
                page(3) to "<html><body><div id=\"content\"><p>第三段正文。</p></div></body></html>",
                two to page("二章正文。", null),
            ),
        )
        assertEquals("三页都要拿到", 3, s.chapterPages().count { it != two })
        assertTrue("正文要干净：" + out.body, out.body.contains("第一章 起\n第一段正文。\n\n第二段正文。\n\n第三段正文。"))
        assertFalse("导航那几个字一行都不许留", listOf("上一章", "返回目录", "下一页").any { out.body.contains(it) })
    }

    /** 反过来：链接夹在一整句话里时只摘那颗链接，不能把整段砍了 —— 砍父节点的判据是"这条很短"。 */
    @Test
    fun aLinkBuriedInALongParagraphTakesTheLinkOutButKeepsTheSentence() {
        val lead ="这一段是正文，句子里莫名其妙地夹了一颗"
        val (out, _) = download(
            rule(".pagenext"),
            mapOf(
                one to "<html><body><div id=\"content\"><p>${lead}<a class=\"pagenext\" href=\"1_2.html\">下一页</a>" +
                    "接着把这段话说完，后面还剩很长一截，长得不会被人当成导航条。</p></div></body></html>",
                page(2) to page("第二页正文。", null),
                two to page("二章正文。", null),
            ),
        )
        assertTrue("前半句要留着：" + out.body, out.body.contains(lead))
        assertTrue("后半句也要留着：" + out.body, out.body.contains("接着把这段话说完"))
        assertFalse("只有那颗链接的字该没", out.body.contains("下一页"))
    }

    @Test
    fun onlyAProperlySeparatedPageSuffixCountsAsTheSameChapter() {
        fun fam(from: String, to: String) = samePageFamily(bookUrl + from, bookUrl + to)
        assertTrue("首尾两页", fam("123.html", "123_2.html"))
        assertTrue("两位数页码", fam("123.html", "123_10.html"))
        assertTrue("从中间那页接着跟", fam("123_2.html", "123_3.html"))
        assertTrue("回头指向首", fam("123_2.html", "123.html"))
        assertTrue("用短横分隔的站", fam("123.html", "123-2.html"))
        assertFalse("下一章", fam("123.html", "124.html"))
        assertFalse("回头一章", fam("123.html", "122.html"))
        assertFalse("id 接一位就是下一章，没有分隔符不算分页", fam("1.html", "10.html"))
        assertFalse("同理：123 之后接个 2 是另一本/另一章", fam("123.html", "1232.html"))
        assertFalse("不是数字后缀", fam("123.html", "123x.html"))
        assertFalse("换了扩展名", fam("123.html", "123_2.shtm"))
        assertFalse("同一页", fam("123.html", "123.html"))
        assertFalse("换了目录", samePageFamily(bookUrl + "123.html", "http://www.example-hu.com/0/999/123_2.html"))
        assertFalse("换了站", samePageFamily(bookUrl + "123.html", "http://other-hu.com/0/321/123_2.html"))
    }
}
