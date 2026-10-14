package com.bookreader.booksource

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule as JUnitRule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException
import java.util.Collections

/**
 * 有声这一族在规则层和下载层的判据。全部打在**手写的夹具**上，不联网。
 *
 * 和漫画同一族，所以判据也照同一族写；多出来的一条是**魔数**那关：
 * 会话被踢时这些站回的是 200 的 HTML 登录页，不认字节开头的话表现是
 * "每一集都下到了，一集都放不出" —— 那是要在判据里钉死的反模式。
 */
class AudioTest {

    @JvmField
    @JUnitRule
    val tmp = TemporaryFolder()

    /** HTML 页走一份表、音频走另一份表：MemoryFetcher 只会装 String，一段 MP3 塞进 String 再取回来是拿 UTF-8 装任意字节 —— 会坏。 */
    private class Script(pages: Map<String, String>, audio: Map<String, ByteArray>) : Fetcher {
        private val pages = LinkedHashMap(pages)
        private val audio = LinkedHashMap(audio)
        private val failsLeft = HashMap<String, Int>()
        val requests = Collections.synchronizedList(ArrayList<PageRequest>())

        fun fail(url: String, times: Int) { failsLeft[url] = times }
        fun count(url: String): Int = requests.count { it.url == url }
        fun referers(url: String): List<String?> = requests.filter { it.url == url }.map { it.referer }

        override fun fetch(req: PageRequest): Page {
            requests.add(req)
            failsLeft[req.url]?.let { left ->
                if (left > 0) {
                    failsLeft[req.url] = left - 1
                    throw IOException("源站把这一枪打回来了：" + req.url)
                }
            }
            audio[req.url]?.let { return Page(req.url, null, it) }
            val body = pages[req.url] ?: error("夹具里没有这一页：" + req.url)
            return Page(req.url, null, body.toByteArray(Charsets.UTF_8))
        }
    }

    private val audioJson = """
        {
          "id": "ta", "name": "测试有声", "homeUrl": "https://a.example/", "kind": "audio",
          "search": {"url": "https://a.example/s?k=%s", "item": ".row", "bookName": ".a", "author": "", "bookUrl": ".a @href"},
          "toc": {"item": "a.ep"},
          "chapter": {"audio": "audio source @src"},
          "crawl": {"concurrency": 1, "minIntervalMs": 0, "maxIntervalMs": 0, "maxAttempts": 2}
        }
    """.trimIndent()

    private val bookPage = """
        <html><body>
          <div class="rows">
            <div class="row"><a class="a" href="/book/1/">斗破苍穹（播讲版）</a></div>
          </div>
        </body></html>
    """.trimIndent()

    private val tocPage = """
        <html><body>
          <a class="ep" href="/ep/1/">第一集 萧炎</a>
          <a class="ep" href="/ep/2/">第二集 纳兰</a>
          <a class="ep" href="/ep/3/">第三集 药老</a>
        </body></html>
    """.trimIndent()

    private val ep1Page = """
        <html><body>
          <audio controls><source src="https://cdn.example/a1.mp3" type="audio/mpeg"></audio>
        </body></html>
    """.trimIndent()

    private val ep2Page = """
        <html><body>
          <audio controls><source src="/media/ep2.mp3"></audio>
        </body></html>
    """.trimIndent()

    private val ep3Page = """
        <html><body>
          <audio controls><source src="https://cdn.example/a3.mp3"></audio>
        </body></html>
    """.trimIndent()

    /** 懒加载那一族：真地址在 data-src，src 那一格是空/占位 —— 写法就得换成 @data-src。 */
    private val lazyPage = """
        <html><body>
          <audio controls><source src="" data-src="/media/lazy.mp3"></audio>
        </body></html>
    """.trimIndent()

    private val bookUrl = "https://a.example/book/1/"
    private val hit = BookHit("ta", "测试有声", "斗破苍穹（播讲版）", "天蚕", bookUrl)

    private fun rule(): Rule = RuleLoader.load(audioJson, "测试源")

    /** 一段真 MP3 的最小形状：ID3 头 + 一点内容。长度过 MIN_PART_BYTES 那道下限。 */
    private fun mp3(): ByteArray {
        val b = ByteArray(8192)
        b[0] = 'I'.code.toByte()
        b[1] = 'D'.code.toByte()
        b[2] = '3'.code.toByte()
        return b
    }

    @Test
    fun audioRuleReadsSearchTocAndEpisodeAudio() {
        val s = Script(
            mapOf(
                "https://a.example/s?k=%E6%B5%B7" to bookPage,
                bookUrl to tocPage,
                "https://a.example/ep/1/" to ep1Page,
                "https://a.example/ep/2/" to ep2Page,
            ),
            emptyMap(),
        )
        val r = rule()
        val hits = SearchEngine(r, s).search("海")
        assertEquals("一行命中", 1, hits.size)
        assertEquals("相对链接要按这一页自己补成绝对", bookUrl, hits[0].bookUrl)
        val toc = SearchEngine(r, s).head(bookUrl).chapters
        assertEquals(listOf("第一集 萧炎", "第二集 纳兰", "第三集 药老"), toc.map { it.first })
        val one = chapterAudio(r, ep1Page, "https://a.example/ep/1/")
        assertEquals("src 里的本来就是绝对地址", listOf("https://cdn.example/a1.mp3"), one)
        val two = chapterAudio(r, ep2Page, "https://a.example/ep/2/")
        assertEquals("裸路径要按集页补全", listOf("https://a.example/media/ep2.mp3"), two)
        val lazy = chapterAudio(r.copy(chapter = r.chapter.copy(audio = "audio source @data-src")), lazyPage, "https://a.example/ep/9/")
        assertEquals("懒加载写 data-src 的取那一格", listOf("https://a.example/media/lazy.mp3"), lazy)
    }

    @Test
    fun anAudioRuleWithoutAnAudioSelectorIsRefusedAtInstall() {
        val bad = audioJson.replace("\"audio\": \"audio source @src\"", "\"content\": \"#x\"")
        val e = runCatching { RuleLoader.load(bad, "测试源") }.exceptionOrNull()
        assertTrue("得点名是哪一格、该怎么写：" + e?.message, e is RuleException && e.message!!.contains("chapter.audio"))
    }

    /** 不带 @ 的写法会取到 <audio> 标签里的兜底文字而不是地址：装上的那一刻就得说。 */
    @Test
    fun anAudioSelectorWithoutAnAttributeIsRefusedAtInstall() {
        val e = runCatching { RuleLoader.load(audioJson.replace(" @src", ""), "测试源") }.exceptionOrNull()
        assertTrue(e is RuleException && e.message!!.contains("选择器 @属性"))
    }

    /**
     * 「先听一集」：目录整本读回来，但只有第一集发音频请求。
     * 音频一集几十 MB，"先拉开头"拉十章的写法在这里等于一次打出去几个 GB。
     */
    @Test
    fun aHeadDownloadFetchesOnlyTheFirstEpisodeAndStillHandsOutTheOutline() {
        val s = Script(
            mapOf(bookUrl to tocPage, "https://a.example/ep/1/" to ep1Page, "https://a.example/ep/2/" to ep2Page),
            mapOf("https://cdn.example/a1.mp3" to mp3(), "https://a.example/media/ep2.mp3" to mp3()),
        )
        val store = MemoryAudioStore()
        val chapters = ArrayList<AudioChapter>()
        val progress = ArrayList<Pair<Int, Int>>()
        val out = AudioDownloader(rule(), s, store)
            .download(hit, limit = 1, onChapter = { chapters.add(it) }, onProgress = { d, t -> progress.add(d to t) })
        println("[先听] 集=${out.chapters} 段=${out.parts} 字节=${out.bytes} 大纲=${out.outline.size}")
        assertEquals("成书只有第一集", 1, out.chapters)
        assertEquals("第二集第三集的音频一次请求都不该发", 1, s.count("https://cdn.example/a1.mp3") + s.count("https://a.example/media/ep2.mp3"))
        assertEquals("只拉一集，大纲仍要给全三集", 3, out.outline.size)
        assertEquals("第三集 药老", out.outline.last())
        assertEquals("目录一拿到就先报一次 (0, 总数)，之后每集一次", listOf(0 to 1, 1 to 1), progress)
        assertEquals("每集落地就报，不等整本", 1, chapters.size)
        assertEquals("第一集 萧炎", chapters[0].title)
        assertEquals("防盗链按 Referer 决定给不给：发的是这一段所在的集页自己", "https://a.example/ep/1/", s.referers("https://cdn.example/a1.mp3").single())
        assertTrue(out.complete)
    }

    /** 会话被踢时回的是 200 的 HTML 登录页：重试满也不能把它存成音频，这一集要进"缺集"那一栏。 */
    @Test
    fun anHtmlLoginPageIsNeverStoredAsAudioAndTheEpisodeIsReportedMissing() {
        val s = Script(
            mapOf(bookUrl to tocPage, "https://a.example/ep/1/" to ep1Page, "https://a.example/ep/2/" to ep2Page, "https://a.example/ep/3/" to ep3Page),
            mapOf(
                "https://cdn.example/a1.mp3" to "<html><body>请先登录后收听</body></html>".toByteArray(),
                "https://a.example/media/ep2.mp3" to mp3(),
                "https://cdn.example/a3.mp3" to mp3(),
            ),
        )
        val store = MemoryAudioStore()
        val out = AudioDownloader(rule(), s, store).download(hit)
        println("[登录页] 对第一段的请求数=" + s.count("https://cdn.example/a1.mp3") + " 缺集=" + out.failed)
        assertEquals("HTML 不是音频：重试满 maxAttempts=2", 2, s.count("https://cdn.example/a1.mp3"))
        assertEquals(listOf("第一集 萧炎"), out.failed)
        assertFalse(out.complete)
        assertEquals("登录页一个字节都不该进仓", 0, store.have.keys.count { it.contains("a1.mp3") })
        assertEquals("第二集第三集照常落地", 2, s.count("https://a.example/media/ep2.mp3") + s.count("https://cdn.example/a3.mp3"))
    }

    @Test
    fun flakyEpisodeIsRetriedAndLandsInTheBook() {
        val s = Script(
            mapOf(bookUrl to tocPage, "https://a.example/ep/1/" to ep1Page, "https://a.example/ep/2/" to ep2Page, "https://a.example/ep/3/" to ep3Page),
            mapOf("https://cdn.example/a1.mp3" to mp3(), "https://a.example/media/ep2.mp3" to mp3(), "https://cdn.example/a3.mp3" to mp3()),
        )
        s.fail("https://cdn.example/a1.mp3", 1)
        val out = AudioDownloader(rule(), s, MemoryAudioStore()).download(hit)
        println("[重试] 请求数=" + s.count("https://cdn.example/a1.mp3") + " 缺集=" + out.failed)
        assertEquals("前一次被打回、第二次成：正好 maxAttempts=2", 2, s.count("https://cdn.example/a1.mp3"))
        assertTrue(out.complete)
        assertEquals(3, out.parts)
        assertEquals("仓里的字节就是账面：有声书没有字数", mp3().size.toLong() * 3, out.bytes)
    }

    /** 取消/续抓同一格：仓里已有的段不再发请求，站子只收到缺的那几段。 */
    @Test
    fun resumeSkipsPartsAlreadyStored() {
        val store = MemoryAudioStore()
        val s = Script(
            mapOf(bookUrl to tocPage, "https://a.example/ep/1/" to ep1Page, "https://a.example/ep/2/" to ep2Page, "https://a.example/ep/3/" to ep3Page),
            mapOf("https://cdn.example/a1.mp3" to mp3(), "https://a.example/media/ep2.mp3" to mp3(), "https://cdn.example/a3.mp3" to mp3()),
        )
        // 先把第一集按它自己的键存进仓（putParts 回什么键，has/put 就认什么键）
        val dir = store.putParts("https://a.example/ep/1/", listOf("https://cdn.example/a1.mp3"))
        store.put(dir, "https://cdn.example/a1.mp3", mp3())
        val out = AudioDownloader(rule(), s, store).download(hit)
        println("[续抓] 请求=" + s.requests.count { it.url.endsWith(".mp3") })
        assertEquals("已存过的第一段一次都不该再要", 0, s.count("https://cdn.example/a1.mp3"))
        assertEquals("缺的两段各要一次", 2, s.count("https://a.example/media/ep2.mp3") + s.count("https://cdn.example/a3.mp3"))
        assertTrue(out.complete)
        assertEquals(3, out.parts)
    }

    // ---- 播客 RSS 那一形状：目录直接给文件地址，没有第二层集页 ----

    /** 一份真形状缩小的 RSS：集名在 CDATA 里、音频地址挂在 `<enclosure url>` 上、条目不是 `<a>`。 */
    private val rssFeed = """
        <?xml version="1.0" encoding="UTF-8"?>
        <rss version="2.0"><channel>
          <title>西游记评书</title>
          <item>
            <itunes:episode>1</itunes:episode>
            <title><![CDATA[001 第一回 石猴出世]]></title>
            <link>https://www.ximalaya.com/sound/1</link>
            <enclosure type="audio/x-m4a" length="55174700" url="https://aod.example/storages/e1.m4a"/>
          </item>
          <item>
            <title><![CDATA[002 第二回 大闹天宫]]></title>
            <enclosure type="audio/x-m4a" url="/storages/e2.m4a"/>
          </item>
        </channel></rss>
    """.trimIndent()

    private val rssJson = """
        {
          "id": "rss", "name": "测试播客源", "homeUrl": "https://www.ximalaya.com/", "kind": "audio",
          "search": {"url": "https://itunes.example/search?term=%s", "format": "json", "item": "results[]",
                     "bookName": "collectionName", "author": "artistName", "bookUrl": "feedUrl"},
          "toc": {"item": "item", "name": "title", "href": "enclosure @url", "format": "xml"},
          "chapter": {"audio": "@self"},
          "crawl": {"concurrency": 1, "minIntervalMs": 0, "maxIntervalMs": 0, "maxAttempts": 2}
        }
    """.trimIndent()

    /**
     * `toc.format=xml` + `toc.href` 两格一起把这份 RSS 读成"两集、每集的地址就是文件本身"。
     *
     * 这一条同时钉三件事：集名要从 CDATA 里出来（不是退成网址尾巴）、链接取自 `<enclosure url>`
     * 而不是条目的 href（RSS 的 `<item>` 根本没有 href）、相对地址按书页自己补全。
     */
    @Test
    fun aPodcastFeedReadsItsEpisodesFromCdataAndTheEnclosureUrl() {
        val r = RuleLoader.load(rssJson, "测试播客源")
        assertTrue("`@self` 得被认成\"集地址就是文件\"，不是当成一个选择器：" + r.chapter.audio, r.selfAudio)
        val toc = tocChapters(r, rssFeed, "https://www.ximalaya.com/album/1.xml")
        assertEquals("两条 item 都该出来", 2, toc.size)
        assertEquals(listOf("001 第一回 石猴出世", "002 第二回 大闹天宫"), toc.map { it.first })
        assertEquals("绝对地址原样", "https://aod.example/storages/e1.m4a", toc[0].second)
        assertEquals("相对的要按书页自己补全", "https://www.ximalaya.com/storages/e2.m4a", toc[1].second)
    }

    /**
     * 反证：同一份 RSS 少了 `toc.format` 那一格，它会跟着 `search.format=json` 走 HTML 解析器，
     * 集名就脏了 —— HTML 里 `<title>` 是 RCDATA，`<![CDATA[…]]>` 那两层壳被当成正文字符留着，
     * 界面上每一行都是 `<![CDATA[001 第一回…]]>` 这种。地址那一头倒是没事（自定义标签和属性都留着）。
     * 这一条就是 `toc.format` 存在的理由：不写不会报错，只会把名字弄脏。
     */
    @Test
    fun theSameFeedKeepsCdataShellInTitlesWhenParsedAsHtml() {
        val noFormat = rssJson.replace(", \"format\": \"xml\"", "")
        val r = RuleLoader.load(noFormat, "漏写 toc.format 的源")
        val toc = tocChapters(r, rssFeed, "https://www.ximalaya.com/album/1.xml")
        assertEquals("地址还是取得到（HTML 解析器留自定义标签和属性）", 2, toc.size)
        assertEquals("绝对地址那一条不受影响", "https://aod.example/storages/e1.m4a", toc[0].second)
        assertTrue(
            "按 HTML 解，CDATA 的两层壳留在名字里：" + toc.map { it.first },
            toc.all { it.first.contains("CDATA") },
        )
    }

    /**
     * `@self` 那一档**一次集页请求都不发**：目录里那条地址本身就是文件。
     * 夹具里故意不放任何"集页"，代码要是还去要那一页，假 fetcher 会直接报"夹具里没有这一页"。
     */
    @Test
    fun selfAudioDownloadsTheLinkItselfAndNeverAsksForAnEpisodePage() {
        val feed = """
            <?xml version="1.0" encoding="UTF-8"?>
            <rss><channel><item>
              <title><![CDATA[第一集 石猴出世]]></title>
              <enclosure url="https://cdn.example/e1.mp3"/>
            </item></channel></rss>
        """.trimIndent()
        val s = Script(mapOf(bookUrl to feed), mapOf("https://cdn.example/e1.mp3" to mp3()))
        val r = RuleLoader.load(rssJson, "测试播客源")
        val out = AudioDownloader(r, s, MemoryAudioStore()).download(hit)
        println("[@self] 集=${out.chapters} 段=${out.parts} 字节=${out.bytes} 请求=" + s.requests.map { it.url })
        assertTrue(out.complete)
        assertEquals("除了书页那一枪，只该有一次文件请求", 2, s.requests.size)
        assertEquals("发出去的正是目录里那一条地址本身", "https://cdn.example/e1.mp3", s.requests[1].url)
        assertEquals("Referer 发的是书页那一页（集地址自己当自己的来源没道理）", bookUrl, s.referers("https://cdn.example/e1.mp3").single())
        assertEquals("账面字节就是那支文件", mp3().size.toLong(), out.bytes)
    }

    /**
     * 同一个书页地址回两条，只能算一条。
     *
     * 这条不是"少显示一行"的美观判据：结果那一列的 key 是「源 id + 书页地址」，重复就是撞 key，
     * **真机上整页闪退过**（iTunes 那个索引口确实会把同一本回两条，栈里的原话是
     * `Key "podcast-rsshttps://www.ximalaya.com/album/78520485.xml" was already used`）。
     */
    @Test
    fun theSameBookUrlReturnedTwiceCountsAsOneRow() {
        val body = """
            {"resultCount":2,"results":[
              {"collectionName":"西游记评书","artistName":"青雪","feedUrl":"https://www.ximalaya.com/album/1.xml"},
              {"collectionName":"西游记评书 重上架","artistName":"青雪","feedUrl":"https://www.ximalaya.com/album/1.xml"}
            ]}
        """.trimIndent()
        val s = Script(mapOf("https://itunes.example/search?term=%E6%B5%B7" to body), emptyMap())
        val hits = SearchEngine(RuleLoader.load(rssJson, "测试播客源"), s).search("海")
        assertEquals("同地址的两条只留一条：" + hits.map { it.bookName }, 1, hits.size)
        assertEquals("留的是先出现那一条（页面上的顺序就是站点给的顺序）", "西游记评书", hits[0].bookName)
    }

    // ---- 魔数 ----

    @Test
    fun onlyRealAudioBytesCountAsAudio() {
        assertTrue("ID3 头（大多数 MP3）", looksLikeAudio(mp3()))
        assertTrue("MPEG 帧同步（无 ID3 的裸 MP3）", looksLikeAudio(byteArrayOf(0xFF.toByte(), 0xFB.toByte(), 0x90.toByte(), 0, 0)))
        assertTrue("MP4/M4A 看第 4 到第 8 个字节的 ftyp", looksLikeAudio(m4a()))
        assertTrue("OggS", looksLikeAudio(ogg()))
        assertTrue("fLaC", looksLikeAudio(flac()))
        assertTrue("RIFF....WAVE", looksLikeAudio(wav()))
        assertFalse("被会话踢出来的一张 HTML 登录页不算音频", looksLikeAudio("<html><body>请登录</body></html>".toByteArray()))
        assertFalse("一段 JSON 报错也不算", looksLikeAudio("{\"error\":\"-2\"}".toByteArray()))
        assertFalse("空字节不算", looksLikeAudio(ByteArray(0)))
    }

    // ---- 音频仓 ----

    @Test
    fun fileAudioStoreSkipsWhatItAlreadyHasAndCountsOnlyRealParts() {
        val root = tmp.newFolder("audio")
        val store = FileAudioStore(root)
        val urls = listOf("https://cdn.example/1.mp3", "https://cdn.example/2.mp3")
        val dir = store.putParts("https://a.example/ep/1/", urls)
        assertEquals("地址表先落盘，音频一段还没取", urls, audioPartOrder(File(root, dir)))
        assertFalse(store.has(dir, urls[0]))
        store.put(dir, urls[0], mp3())
        assertTrue(store.has(dir, urls[0]))
        assertEquals(1, store.parts())
        assertEquals("账面字节数 = 落盘那几段加起来", mp3().size.toLong(), store.bytes())
        // 再存一遍同一段：断点续抓就靠这一格不再发请求
        store.put(dir, urls[0], mp3())
        assertEquals("同一段存两遍不该数出两段", 1, store.parts())
    }

    private fun m4a(): ByteArray = ByteArray(8192).also {
        it[4] = 'f'.code.toByte(); it[5] = 't'.code.toByte(); it[6] = 'y'.code.toByte(); it[7] = 'p'.code.toByte()
    }

    private fun ogg(): ByteArray = ByteArray(8192).also { "OggS".toByteArray(Charsets.ISO_8859_1).copyInto(it, 0) }

    private fun flac(): ByteArray = ByteArray(8192).also { "fLaC".toByteArray(Charsets.ISO_8859_1).copyInto(it, 0) }

    private fun wav(): ByteArray = ByteArray(8192).also {
        "RIFF".toByteArray(Charsets.ISO_8859_1).copyInto(it, 0)
        "WAVE".toByteArray(Charsets.ISO_8859_1).copyInto(it, 8)
    }

    /** 内存里的音频仓：判据给的是这一份（`:app` 给的是落在书目录里的 FileAudioStore）。 */
    private class MemoryAudioStore : AudioStore {
        val have = HashMap<String, ByteArray>()
        override fun putParts(chapterUrl: String, urls: List<String>): String {
            return chapterUrl
        }

        override fun has(dir: String, audioUrl: String): Boolean = have.containsKey(dir + "|" + audioUrl)

        override fun put(dir: String, audioUrl: String, bytes: ByteArray) {
            have[dir + "|" + audioUrl] = bytes
        }

        override fun parts(): Int = have.size

        override fun bytes(): Long = have.values.sumOf { it.size.toLong() }
    }
}
