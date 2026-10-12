package com.bookreader.booksource

import com.bookreader.core.ingest.ChapterPattern
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.io.IOException
import java.util.Collections
import java.util.Random

/**
 * 抓取循环的判据。全部离线：`Fetcher` 给脚本化的假页，`sleep` 收进一个列表里断言，
 * 时钟给一个常数 —— 这样"限速到底等没等"是看得见的数，而不是"跑一遍看它慢不慢"。
 */
class BookDownloadTest {

    private class Script(initial: Map<String, String>) : Fetcher {
        private val pages = LinkedHashMap(initial)
        private val failsLeft = HashMap<String, Int>()
        private val latency = HashMap<String, Long>()
        val requests = Collections.synchronizedList(ArrayList<String>())

        fun fail(url: String, times: Int) { failsLeft[url] = times }
        fun slow(url: String, ms: Long) { latency[url] = ms }
        fun blank(url: String) { pages[url] = "<html><body><div id=\"content\">   </div></body></html>" }
        fun count(url: String): Int = requests.count { it == url }

        override fun fetch(req: PageRequest): Page {
            val url = req.url
            requests.add(url)
            failsLeft[url]?.let { left ->
                if (left > 0) {
                    failsLeft[url] = left - 1
                    throw IOException("源站把这一枪打回来了：" + url)
                }
            }
            latency[url]?.let { Thread.sleep(it) }
            val body = pages[url] ?: error("夹具里没有这一页：" + url)
            return Page(url, null, body.toByteArray(Charsets.UTF_8))
        }
    }

    private val bookUrl = "http://www.example-hu.com/0/321/"
    private val hit = BookHit("shuhu", "小说虎", "南方有乔木", "木木", bookUrl)

    private fun tocPage(names: List<String>) =
        "<html><body><div id=\"intro\">一段简介。</div><div id=\"list\"><dl>" +
            names.mapIndexed { i, n -> "<dd><a href=\"/0/321/${i + 1}.html\">$n</a></dd>" }.joinToString("") +
            "</dl></div></body></html>"

    private fun chapterPage(body: String) = "<html><body><div id=\"content\">$body</div></body></html>"

    private fun pages(chapters: Map<Int, String>, names: List<String>): Map<String, String> {
        val out = LinkedHashMap<String, String>()
        out[bookUrl] = tocPage(names)
        chapters.forEach { (i, body) -> out["http://www.example-hu.com/0/321/$i.html"] = chapterPage(body) }
        return out
    }

    private fun rule(crawlOverride: String? = null): Rule {
        var text = javaClass.getResourceAsStream("/rules/shuhu.json")!!.use { it.readBytes().toString(Charsets.UTF_8) }
        if (crawlOverride != null) {
            text = text.replace("  \"crawl\": {\n    \"concurrency\": 3\n  }", "  \"crawl\": $crawlOverride")
        }
        return RuleLoader.load(text, "shuhu")
    }

    private fun downloader(r: Rule, s: Script, store: ChapterStore, sleeps: MutableList<Long>) =
        BookDownloader(r, s, store, sleep = { sleeps.add(it) }, clock = { 1_000L }, random = Random(7))

    /**
     * 「先拉开头」：目录整本读回来（大纲要它、简介也在这一页），但**只有前 N 章发正文请求**。
     *
     * 这条要单独判是因为"多拉了"的表现不是报错，是安静地一次打人家站几百个请求 ——
     * 界面看着一切正常，只有请求数知道。
     */
    @Test
    fun aHeadDownloadFetchesOnlyTheFirstChaptersAndStillHandsOutTheIntro() {
        val six = listOf("第一章 起", "第二章 承", "第三章 转", "第四章 合", "第五章 外", "第六章 传")
        val s = Script(pages(mapOf(1 to "一。", 2 to "二。", 3 to "三。", 4 to "四。", 5 to "五。", 6 to "六。"), six))
        val out = downloader(rule(), s, MemoryStore(), mutableListOf()).download(hit, limit = 2)
        val hits = s.requests.count { it.endsWith(".html") }
        println("[开头] 章=${out.chapters} 正文请求=$hits 简介=${out.intro}")
        assertEquals("成书只有开头两章", 2, out.chapters)
        assertEquals("第三到第六章一次请求都不该发", 2, hits)
        assertEquals("书页只请求一次（目录和简介同一页拿）", 1, s.count("http://www.example-hu.com/0/321/"))
        assertTrue(out.body.contains("一。") && out.body.contains("二。"))
        assertTrue("后四章不该混进书里", !out.body.contains("三。") && !out.body.contains("六。"))
        assertEquals("简介要一起交出去，界面上「本书信息」读它", "一段简介。", out.intro)
        assertEquals("只拉两章，大纲仍要给全六章", 6, out.outline.size)
        assertEquals("第六章 传", out.outline.last())
        assertTrue("全本齐：开头包没有缺章，缺章那一栏要留空", out.complete)
    }

    @Test
    fun chaptersAssembleInCatalogOrderEvenWhenThreadsFinishOutOfOrder() {
        val names = listOf("第一章 起", "第二章 承", "第三章 转", "第四章 合")
        val s = Script(pages(mapOf(1 to "头一章。", 2 to "二章。", 3 to "三章。", 4 to "四章。"), names))
        s.slow("http://www.example-hu.com/0/321/1.html", 90L)
        val r = rule()
        val out = downloader(r, s, MemoryStore(), mutableListOf()).download(hit)
        val body = out.body
        println("[成书] 章=${out.chapters} 字=${out.chars}\n" + body.replace("\n", "⏎"))
        assertEquals(4, out.chapters)
        assertTrue("不该有缺章：" + out.failed, out.complete)
        val at = names.map { body.indexOf(it) }
        assertEquals("四章的先后必须按目录，不是按线程谁先跑完", true, at.zipWithNext().all { (a, b) -> a in 0 until b })
        assertTrue(
            "每一行标题都得让阅读引擎认出来",
            body.lines().filter { it.startsWith("第") }.all { ChapterPattern.matches(it) },
        )
    }

    @Test
    fun rateLimitSchedulesDispatchSlotsNotPerThreadDelays() {
        val names = listOf("第一章", "第二章", "第三章", "第四章")
        val s = Script(pages(mapOf(1 to "一", 2 to "二", 3 to "三", 4 to "四"), names))
        val sleeps = mutableListOf<Long>()
        // 单线程 + min=max=800：时钟给常数，所以"该排到什么时候"是能被精确断言的
        val r = rule("""{ "concurrency": 1, "minIntervalMs": 800, "maxIntervalMs": 800 }""")
        // 三个字段分开断言：listOf(1, 800L, 800L) 会被推成 List<Long>，那个 1 就变成 Long(1)，
        // 和实际的 Integer(1) 比不相等 —— 报出来是"expected [1,800,800] but was [1,800,800]"这种看不懂的假红
        assertEquals("夹具的 crawl 段要被换掉，否则这条测的是默认值", 1, r.crawl.concurrency)
        assertEquals(800L, r.crawl.minIntervalMs)
        assertEquals(800L, r.crawl.maxIntervalMs)
        downloader(r, s, MemoryStore(), sleeps).download(hit)
        println("[限速] sleeps=$sleeps")
        assertEquals("四章之间排三个 800ms 的窗口，且是累加的槽位不是各等各的", listOf(0L, 800L, 1600L, 2400L), sleeps)
    }

    @Test
    fun flakyChapterIsRetriedAndStillLandsInTheBook() {
        val url1 = "http://www.example-hu.com/0/321/1.html"
        val names = listOf("第一章 起", "第二章 承")
        val s = Script(pages(mapOf(1 to "补回来的正文。", 2 to "二章。"), names))
        s.fail(url1, 2)
        val out = downloader(rule(), s, MemoryStore(), mutableListOf()).download(hit)
        println("[重试] 请求数=" + s.requests + " 缺章=" + out.failed)
        assertEquals("前两次被打回、第三次成：正好 maxAttempts=3", 3, s.count(url1))
        assertTrue(out.complete)
        assertTrue("重试成功的那章要在书里", out.body.contains("补回来的正文。"))
    }

    /** 限流站的真实故障是"不报错、正文就是空的"：这种要当失败重试，重试完还空就报缺章，不能当一章空的写进书。 */
    @Test
    fun emptyChapterIsRetriedThenReportedMissingNotSilentlyKept() {
        val url1 = "http://www.example-hu.com/0/321/1.html"
        val names = listOf("第一章 起", "第二章 承")
        val s = Script(pages(mapOf(1 to "", 2 to "二章。"), names))
        s.blank(url1)
        val out = downloader(rule(), s, MemoryStore(), mutableListOf()).download(hit)
        println("[空正文] 对第一章的请求数=" + s.count(url1) + " 缺章=" + out.failed)
        assertEquals("空正文也要重试满 3 次", 3, s.count(url1))
        assertEquals(listOf("第一章 起"), out.failed)
        assertEquals("缺的那章不进书里：书里只剩实有的一章", 1, out.body.lines().count { it.startsWith("第二章") })
        assertTrue(out.body.contains("二章。"))
    }

    @Test
    fun resumeOnlyFetchesChaptersThatAreNotStoredYet() {
        val names = listOf("第一章 起", "第二章 承", "第三章 转", "第四章 合")
        val s = Script(pages(mapOf(1 to "一。", 2 to "二。", 3 to "三。", 4 to "四。"), names))
        val store = MemoryStore(
            mapOf(
                "shuhu|$bookUrl|http://www.example-hu.com/0/321/1.html" to "已经存过的第一章。",
                "shuhu|$bookUrl|http://www.example-hu.com/0/321/2.html" to "已经存过的第二章。",
            ),
        )
        val out = downloader(rule(), s, store, mutableListOf()).download(hit)
        println("[续抓] 请求=" + s.requests)
        assertEquals("已存过的两章一次都不该再要", 0, s.count("http://www.example-hu.com/0/321/1.html"))
        assertEquals(0, s.count("http://www.example-hu.com/0/321/2.html"))
        assertEquals("缺的两章各要一次", 1, s.count("http://www.example-hu.com/0/321/3.html"))
        assertEquals(1, s.count("http://www.example-hu.com/0/321/4.html"))
        assertTrue(out.complete)
        assertTrue("续上的那两章要照样进书", out.body.contains("已经存过的第一章。") && out.body.contains("四。"))
        assertEquals(4, out.chapters)
    }

    /**
     * 「更新一本连载」走的是同一条路：目录变长了，**只有没见过的章**才发请求。
     *
     * 这条单独判是因为它和"续抓"的失败方式不一样：续抓失败是同一本重复下，浪费流量而已；
     * 更新失败的表现是**新章被当成已存过而跳过**（界面显示"已入库 6 章"，翻开却只有前 4 章），
     * 那种错没人会去逐章数。所以这里同时钉请求数和成书章数。
     */
    @Test
    fun updatingASerializedBookFetchesOnlyTheChaptersTheCatalogueJustGrewBy() {
        val four = listOf("第一章 起", "第二章 承", "第三章 转", "第四章 合")
        val six = four + listOf("第五章 转", "第六章 合")
        val store = MemoryStore()
        val r = rule()
        // 第一遍：四章全本
        val first = Script(pages(mapOf(1 to "一。", 2 to "二。", 3 to "三。", 4 to "四。"), four))
        val a = downloader(r, first, store, mutableListOf()).download(hit)
        // 第二遍：站点多了两章，前两章的请求数必须还是 1（不是 2）
        val second = Script(pages(mapOf(5 to "五。", 6 to "六。"), six) + mapOf(
            "http://www.example-hu.com/0/321/1.html" to chapterPage("一。"),
            "http://www.example-hu.com/0/321/2.html" to chapterPage("二。"),
            "http://www.example-hu.com/0/321/3.html" to chapterPage("三。"),
            "http://www.example-hu.com/0/321/4.html" to chapterPage("四。"),
        ))
        val b = downloader(r, second, store, mutableListOf()).download(hit)
        println("[更新] 第一遍=${a.chapters}章 第二遍=${b.chapters}章 第二遍请求=${second.requests.count { it.contains(".html") }}次")
        assertEquals("第一遍四章", 4, a.chapters)
        assertEquals("第二遍要成六章，不是只把新两章拼一本小的", 6, b.chapters)
        assertEquals("老章一次都不该再要", 0, second.count("http://www.example-hu.com/0/321/1.html"))
        assertEquals(0, second.count("http://www.example-hu.com/0/321/4.html"))
        assertEquals("新章各要一次", 1, second.count("http://www.example-hu.com/0/321/5.html"))
        assertEquals(1, second.count("http://www.example-hu.com/0/321/6.html"))
        assertTrue("成书里四章老字要还在", b.body.contains("一。") && b.body.contains("四。"))
        assertTrue("新章的字也得在", b.body.contains("五。") && b.body.contains("六。"))
    }

    @Test
    fun headingsTheSiteNumberedBarelyStillIndexAsChapters() {
        val names = listOf("1", "001你到底是谁", "这是一行长得不能再长的目录文字".repeat(4))
        val s = Script(pages(mapOf(1 to "一。", 2 to "二。", 3 to "三。"), names))
        val out = downloader(rule(), s, MemoryStore(), mutableListOf()).download(hit)
        val heads = out.body.lines().filter { it.startsWith("第 ") || ChapterPattern.matches(it) }.distinct()
        println("[标题] " + out.body.lines().take(6))
        assertTrue("光杆数字那种必须补成可识别的标题", ChapterPattern.matches(heading(0, "1")))
        assertEquals("真实书里那种裸编号原样就能认", "001你到底是谁", heading(1, "001你到底是谁"))
        assertEquals("长到超纲的，退成光杆第 N 章，也要能认", "第 3 章", heading(2, names[2]))
        assertEquals(3, out.chapters)
    }

    /**
     * 整条链接上一次的判据：下载器合成的那本，交给**阅读引擎自己的入库**，
     * 必须被切成一章一章。这条不测取值也不测网络，测的是"抓回来的东西我们读得懂"——
     * 那一头 `ChapterPattern` 改一个字，这里就会红。
     */
    @Test
    fun assembledBookIsIngestableByTheReaderItself() {
        val names = listOf("第一章 起", "第二章 承", "第三章 转")
        val bodies = (1..3).associateWith { i -> ("这是第" + i + "章的内容。".repeat(30)) }
        val s = Script(pages(bodies, names))
        val out = downloader(rule(), s, MemoryStore(), mutableListOf()).download(hit)
        val dir = java.nio.file.Files.createTempDirectory("bs-ingest").toFile()
        try {
            val txt = writeBookText(File(dir, "dl-book.txt"), out)
            val report = com.bookreader.core.ingest.TxtIngestor().ingest(txt, File(dir, "book"), title = hit.bookName) { }
            println("[成书入库] 引擎认到标题=${report.chapterTitlesFound} 章=${report.meta.chapterCount} " +
                "字=${report.meta.totalChars} 下载器字数=${out.chars} 编码=${report.charset}")
            assertEquals("三章都得被引擎切成独立章节", 3, report.meta.chapterCount)
            assertEquals(3, report.chapterTitlesFound)
            // 量过的数：下载器交出 486 字，引擎入库后读回 486 字 —— 不是差不多，是一个字都不差
            assertEquals("正文一个字都不该丢", out.chars.toLong(), report.meta.totalChars)
        } finally {
            dir.deleteRecursively()
        }
    }

    /** 取消不是"把线程杀掉"，是"剩下的章不再要"：已抓到的留在仓里，下次接着抓。 */
    @Test
    fun cancelStopsAskingForTheRestAndKeepsWhatWasFetched() {
        val names = listOf("第一章 起", "第二章 承", "第三章 转", "第四章 合")
        val s = Script(pages(mapOf(1 to "一。", 2 to "二。", 3 to "三。", 4 to "四。"), names))
        val store = MemoryStore()
        var stop = false
        // 必须钉成单线程：默认那份夹具是 3 条线程，三条会一起过了"还没取消"那道检查，
        // 那测的就不是"取消之后不再要"，而是"线程池有几条"
        val d = BookDownloader(rule("""{ "concurrency": 1 }"""), s, store, sleep = { }, clock = { 1_000L }, random = Random(7))
        d.download(hit, shouldStop = { stop }) { done, _ -> if (done == 1) stop = true }
        println("[取消] 请求=" + s.requests + " 仓里=" + store.size)
        assertEquals("目录 + 第一章，之后就停了", 2, s.requests.size)
        assertEquals("已经抓到的那一章要留在仓里", 1, store.size)
    }

    @Test
    fun progressFiresOncePerChapterIncludingTheFailedOnes() {
        val names = listOf("第一章 起", "第二章 承", "第三章 转")
        val s = Script(pages(mapOf(1 to "一。", 2 to "二。", 3 to "三。"), names))
        val seen = Collections.synchronizedList(mutableListOf<Pair<Int, Int>>())
        val out = downloader(rule(), s, MemoryStore(), mutableListOf()).download(hit) { done, total ->
            seen.add(done to total)
        }
        assertEquals(3, out.chapters)
        assertEquals(
            "目录一拿到就先报一次 (0, 总数)：不然进度那一行在第一章落地之前是空的；之后每章一次",
            listOf(0 to 3, 1 to 3, 2 to 3, 3 to 3), seen.sortedBy { it.first },
        )
    }
}
