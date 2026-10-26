package com.bookreader.booksource

import org.junit.Assert.assertEquals
import org.junit.Test
import java.nio.charset.Charset

/**
 * 取值这一层的判据：喂**本地 HTML 夹具**，断言抽出来的字段一字不差。
 *
 * 夹具是合成的（域名、书名都是假的），DOM 形状照真实站那一类来。所以这一组测试证明的是
 * "引擎按规则取得对"，**不证明"那个站今天还能搜"** —— 后者只能在真机上联网验，另开一轮。
 */
class SearchExtractionTest {

    private fun rule(name: String): Rule = RuleLoader.load(
        fixtureText("/rules/$name.json"),
        name,
    )

    /** 记下每一次请求：POST 那条要能证明关键词真的进了请求体，而不只是进了地址。 */
    private class Recording(val pages: Map<String, Page>) : Fetcher {
        val seen = ArrayList<PageRequest>()
        override fun fetch(req: PageRequest): Page {
            seen.add(req)
            return pages[req.url] ?: error("夹具里没有这一页：" + req.url)
        }
    }

    private fun page(url: String, body: String, charset: String = "UTF-8") =
        url to Page(url, null, body.toByteArray(Charset.forName(charset)))

    /** tianxi 那条源明写了 charset=gbk —— 夹具就得真用 GBK 编码，不然测的是夹具不是引擎。 */
    private fun pageGbk(url: String, body: String) = page(url, body, "GBK")

    private fun engine(name: String, vararg pages: Pair<String, Page>): Pair<Rule, Recording> {
        val r = rule(name)
        return r to Recording(mapOf(*pages))
    }

    /**
     * 列表页（搜索结果那一行）上的封面地址。
     *
     * 两件事要一起判住：站点把真地址挂在 `data-original` 上（懒加载，`src` 是空的或一张占位灰图），
     * 而 `pick` 只对 href/src 自动补全 —— 所以补全这一格得自己来，不然界面拿一个 `/cover/11.jpg`
     * 去请求，表现是"这一条源每张封面都裂"。
     */
    @Test
    fun theListPageCoverComesOutAndALazyRelativeAddressIsAbsolutized() {
        val r = RuleLoader.load(
            fixtureText("/rules/xhytd.json")
                .replace(
                    "    \"item\": \"#main > div.novelslist2 > ul > li\",",
                    "    \"item\": \"#main > div.novelslist2 > ul > li\",\n    \"cover\": \"img @data-original\",",
                ),
            "带列表封面的夹具",
        )
        val f = Recording(
            mapOf(
                page(
                    "http://www.example-xhytd.com/search.html?name=%E5%89%91",
                    """
                    <html><body><div id="main"><div class="novelslist2"><ul>
                    <li><span class="s2 wid"><img data-original="/cover/11.jpg" alt="剑影孤行"><a href="/xiaoshuo/11/">剑影孤行</a></span>
                    <span class="s4 wid"><a href="/author/laozhou/">老周</a></span></li>
                    <li><span class="s2 wid"><a href="/xiaoshuo/12/">第二个</a></span><span class="s4 wid"><a href="/a/">某人</a></span></li>
                    </ul></div></div></body></html>
                    """.trimIndent(),
                ),
            ),
        )
        val hits = SearchEngine(r, f).search("剑")
        assertEquals("两行都该算一条（封面缺不算缺）", 2, hits.size)
        assertEquals("懒加载的相对地址要按这一页自己补全", "http://www.example-xhytd.com/cover/11.jpg", hits[0].coverUrl)
        assertEquals("这一行没有图就是空串，界面自己退回默认封面", "", hits[1].coverUrl)
    }

    /** 精选榜单那一屏：封面挂在每一行的 a 里面，取值写法和搜索页同一套，但是另一段配置。 */
    @Test
    fun theFeaturedListCarriesItsOwnCoverAddress() {
        val r = RuleLoader.load(
            fixtureText("/rules/xhytd.json").replace(
                "  \"search\": {",
                "  \"hot\": {\n" +
                    "    \"url\": \"http://www.example-xhytd.com/top/\",\n" +
                    "    \"group\": \"div.blk\",\n" +
                    "    \"item\": \"a.book\",\n" +
                    "    \"bookName\": \"a.book @title\",\n" +
                    "    \"bookUrl\": \"a.book @href\",\n" +
                    "    \"cover\": \"img @src\"\n" +
                    "  },\n  \"search\": {",
            ),
            "带榜单封面的夹具",
        )
        val f = Recording(
            mapOf(
                page(
                    "http://www.example-xhytd.com/top/",
                    """
                    <html><body>
                    <div class="blk"><a class="book" href="/xiaoshuo/22/" title="第二本"><img src="/cover/22.jpg">第二本</a></div>
                    <div class="blk"><a class="book" href="/xiaoshuo/23/" title="第三本">第三本</a></div>
                    </body></html>
                    """.trimIndent(),
                ),
            ),
        )
        val hits = SearchEngine(r, f).hot()
        assertEquals(2, hits.size)
        assertEquals("http://www.example-xhytd.com/cover/22.jpg", hits[0].coverUrl)
        assertEquals("这一块没图 → 空串", "", hits[1].coverUrl)
    }

    /**
     * 列表页没图的笔趣阁那一族：封面**从书页地址推**（`{g}`/`{b}` 两段），一本只多一个图片请求。
     *
     * 两件事一起判：推出来的地址要补成绝对的；而**列表页自己给了图就不许被模板盖掉**
     * —— 模板是退路，不是首选。真站对照过 12/12（两家站各 6 本，推的与书页上写着的一字不差）。
     */
    @Test
    fun theCoverTemplateFillsOnlyWhenTheRowHasNoImage() {
        val r = RuleLoader.load(
            fixtureText("/rules/xhytd.json")
                .replace("  \"charset\": \"\",", "  \"charset\": \"\",\n  \"coverFrom\": \"/files/article/image/{g}/{b}/{b}s.jpg\",")
                .replace("    \"bookUrl\": \"span.s2.wid > a@href\"", "    \"bookUrl\": \"span.s2.wid > a@href\",\n    \"cover\": \"img @src\""),
            "带封面模板的夹具",
        )
        val f = Recording(
            mapOf(
                page(
                    "http://www.example-xhytd.com/search.html?name=%E5%89%91",
                    """
                    <html><body><div id="main"><div class="novelslist2"><ul>
                    <li><span class="s2 wid"><a href="/171_171392/">剑影孤行</a></span></li>
                    <li><span class="s2 wid"><a href="/136/136835/">第二本</a><img src="/cover/real.jpg"></span></li>
                    <li><span class="s2 wid"><a href="/only-one-segment/">第三本</a></span></li>
                    </ul></div></div></body></html>
                    """.trimIndent(),
                ),
            ),
        )
        val hits = SearchEngine(r, f).search("剑")
        assertEquals(3, hits.size)
        assertEquals(
            "书页地址尾上取两段填模板，再按站点首页补成绝对地址",
            "http://www.example-xhytd.com/files/article/image/171/171392/171392s.jpg", hits[0].coverUrl,
        )
        assertEquals("列表页给了图就用站点那张，模板不许盖", "http://www.example-xhytd.com/cover/real.jpg", hits[1].coverUrl)
        assertEquals("地址里凑不出两段就交回空串，不编一个地址出来", "", hits[2].coverUrl)
    }

    @Test
    fun searchRowFieldsComeOutExactlyAndALinklessRowIsDropped() {
        val (r, f) = engine(
            "xhytd",
            page(
                "http://www.example-xhytd.com/search.html?name=%E5%89%91",
                """
                <html><body><div id="main"><div class="novelslist2"><ul>
                <li><span class="s1">1</span><span class="s2 wid"><a href="/xiaoshuo/11/">剑影孤行</a></span>
                <span class="s3 wid3"><a href="/xiaoshuo/11/900.html">第 900 章 归处</a></span>
                <span class="s4 wid"><a href="/author/laozhou/">老周</a></span><span class="s6 wid6">2026-10-09</span></li>
                <li><span class="s2 wid"><a href="/xiaoshuo/12/">第二个</a></span><span class="s4 wid"><a href="/a/">某人</a></span></li>
                <li><span class="s2 wid">这一行没有链接</span></li>
                </ul></div></div></body></html>
                """.trimIndent(),
            ),
        )
        val hits = SearchEngine(r, f).search("剑")
        println("[取值] 命中=${hits.size} 第一条=$hits")
        assertEquals("没有链接那一行不算一条结果", 2, hits.size)
        val h = hits[0]
        assertEquals("剑影孤行", h.bookName)
        assertEquals("老周", h.author)
        assertEquals("第 900 章 归处", h.latestChapter)
        assertEquals("2026-10-09", h.lastUpdateTime)
        assertEquals(
            "相对链接要按这一页自己的地址补全",
            "http://www.example-xhytd.com/xiaoshuo/11/", h.bookUrl,
        )
        assertEquals("源信息要跟着走，聚合搜索里才知道这条是哪来的", "xhytd", h.sourceId)
    }

    @Test
    fun relativeLinksAndIntroComeOutFromAnotherShapeOfPage() {
        val (r, f) = engine(
            "shuhu",
            page(
                "http://www.example-hu.com/?q=%E5%8D%97",
                """
                <html><body><div class="wrap"><div class="result"><div>
                <dl><dt><a href="/0/321/">南方有乔木</a><span>作者：木木</span></dt><dd>一句话简介。</dd></dl>
                </div></div></div></body></html>
                """.trimIndent(),
            ),
        )
        val hits = SearchEngine(r, f).search("南")
        assertEquals(1, hits.size)
        assertEquals("http://www.example-hu.com/0/321/", hits[0].bookUrl)
        assertEquals("作者：木木", hits[0].author)
        assertEquals("一句话简介。", hits[0].intro)
    }

    @Test
    fun postSourcePutsTheKeywordInTheBodyToo() {
        val (r, f) = engine(
            "tianxi",
            pageGbk(
                "http://www.example-tx.com/s.php?q=%B2%E2%CA%D4",
                """
                <html><body><ul class="shulist"><li>
                <a class="book" href="/book/77/">边城浪子</a><span class="au">古龙</span>
                <span class="cat">武侠</span><span class="size">82万字</span>
                <span class="new"><a href="/book/77/9.html">第 9 章</a></span>
                </li></ul></body></html>
                """.trimIndent(),
            ),
        )
        val hits = SearchEngine(r, f).search("测试")
        assertEquals(1, hits.size)
        assertEquals("82万字", hits[0].wordCount)
        assertEquals("武侠", hits[0].category)
        assertEquals("第 9 章", hits[0].latestChapter)
        val req = f.seen[0]
        assertEquals("POST", req.method)
        assertEquals(
            "书源里那串 {k: v} 必须转成真正发得出去的表单串，%s 换成编码后的关键词",
            "submit=Search&keyword=%B2%E2%CA%D4", req.body,
        )
    }

    @Test
    fun gbkPageIsDecodedByTheCharsetNameTheRuleWrites() {
        val body = """
            <html><body><ul class="shulist"><li>
            <a class="book" href="/book/88/">旧版书名</a><span class="au">某作者</span>
            </li></ul></body></html>
        """.trimIndent()
        val url = "http://www.example-tx.com/s.php?q=%BE%C9"
        val (r, f) = engine("tianxi", url to Page(url, null, body.toByteArray(Charset.forName("GBK"))))
        val hits = SearchEngine(r, f).search("旧")
        assertEquals("GBK 的页按 gbk 解才不是乱码", "旧版书名", hits[0].bookName)
    }

    @Test
    fun chapterParagraphsSurviveAndWatermarksAreGone() {
        val (r, _) = engine("xhytd")
        val text = chapterText(
            r,
            """<html><body><div id="content">第一行话。<br>第二行话。<br>(本章完)<br>""" + "</div></body></html>",
            "http://www.example-xhytd.com/xiaoshuo/11/900.html",
        )
        println("[正文] " + text.replace("\n", "⏎"))
        assertEquals("第一行话。\n\n第二行话。", text)
    }

    /** 段间要交出一个**空行**（两个换行），不是一个换行：阅读器那一格空行照原文判，给一个换行就段段挤在一起。 */
    @Test
    fun paragraphsAreSeparatedByABlankLine() {
        val (r, _) = engine("shuhu")
        val text = chapterText(
            r,
            "<html><body><div id=\"content\"><p>第一段。</p><p>第二段。</p><p>第三段。</p></div></body></html>",
            "http://www.example-hu.com/0/321/1.html",
        )
        assertEquals("第一段。\n\n第二段。\n\n第三段。", text)
    }

    /** 站点自己已经空了行的，不能加成两道：先滤空行再统一 join，所以几道换行出来都只剩一条空隙。 */
    @Test
    fun blankLinesInTheSourceAreNotDoubled() {
        val (r, _) = engine("xhytd")
        val text = chapterText(
            r,
            "<html><body><div id=\"content\">第一段。<br><br><br>第二段。<br></div></body></html>",
            "http://www.example-xhytd.com/xiaoshuo/11/900.html",
        )
        assertEquals("第一段。\n\n第二段。", text)
    }

    @Test
    fun pTagSiteSplitsParagraphsToo() {
        val (r, _) = engine("shuhu")
        val text = chapterText(
            r,
            "<html><body><div id=\"content\"><p>第一段。</p><p>第二段。</p></div></body></html>",
            "http://www.example-hu.com/0/321/1.html",
        )
        assertEquals("第一段。\n\n第二段。", text)
    }

    /** 限流站的真实故障是"不报错、正文就是空的"——这里必须交出空串，不能抛，也不能返回一堆标签。 */
    @Test
    fun rateLimitedPageComesOutEmptyInsteadOfThrowing() {
        val (r, _) = engine("xhytd")
        assertEquals("", chapterText(r, "<html><body><div id=\"content\">   </div></body></html>", "http://x/"))
        assertEquals("", chapterText(r, "<html><body><div id=\"other\">有内容但容器不对</div></body></html>", "http://x/"))
    }

    @Test
    fun catalogMergesOptionPagesInOrderWithoutDuplicates() {
        val (r, f) = engine(
            "tianxi",
            pageGbk(
                "http://www.example-tx.com/book/77/",
                """
                <html><body><select id="indexselect"><option value="1">1</option><option value="2">2</option></select>
                <dl><dt>正文</dt><dd><a href="/book/77/1.html">第一章</a></dd><dd><a href="/book/77/2.html">第二章</a></dd></dl>
                </body></html>
                """.trimIndent(),
            ),
            pageGbk(
                "http://www.example-tx.com/book/77/index1.html",
                "<html><body><dl><dd><a href=\"/book/77/1.html\">第一章</a></dd><dd><a href=\"/book/77/2.html\">第二章</a></dd></dl></body></html>",
            ),
            pageGbk(
                "http://www.example-tx.com/book/77/index2.html",
                "<html><body><dl><dd><a href=\"/book/77/3.html\">第三章</a></dd><dd><a href=\"/book/77/4.html\">第四章</a></dd></dl></body></html>",
            ),
        )
        val chapters = SearchEngine(r, f).catalog("http://www.example-tx.com/book/77/")
        println("[目录] " + chapters.map { it.first } + " 请求数=" + f.seen.size)
        assertEquals(
            listOf("第一章" to "http://www.example-tx.com/book/77/1.html", "第二章" to "http://www.example-tx.com/book/77/2.html", "第三章" to "http://www.example-tx.com/book/77/3.html", "第四章" to "http://www.example-tx.com/book/77/4.html"),
            chapters,
        )
        assertEquals("首页 + 两个分页", 3, f.seen.size)
    }

    @Test
    fun catalogWithoutPulldownJustTakesThePage() {
        val (r, f) = engine(
            "xhytd",
            page(
                "http://www.example-xhytd.com/xiaoshuo/11/",
                """
                <html><body><div id="list"><dl>
                <dt>简介</dt><dd>这本书讲的是什么。</dd>
                <dt>正文</dt><dd><a href="/xiaoshuo/11/1.html">第一章 开门</a></dd>
                <dd><a href="/xiaoshuo/11/2.html">第二章 下山</a></dd>
                </dl></div></body></html>
                """.trimIndent(),
            ),
        )
        val chapters = SearchEngine(r, f).catalog("http://www.example-xhytd.com/xiaoshuo/11/")
        assertEquals(listOf("第一章 开门", "第二章 下山"), chapters.map { it.first })
    }
}
