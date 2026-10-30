package com.bookreader.booksource

import com.bookreader.core.ingest.ImageBookFile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * 「一章 = 一个文件」那一支（199mh 那一族 Kindle 漫画站）：取址 POST、回包读地址、整本解成一排页落仓。
 *
 * 三条判据各钉一个真会错的地方：
 * - `{num}` 取的是章地址尾上那串号 —— 取错了就每一话都下成同一本；
 * - 回包里读不到地址时**不能算成功**（0 页的一章在界面上就是"这本下完了却翻不动"）；
 * - 解出来的页要走和抓图同一套仓（`pages.tsv` + 散列文件名），否则漫画屏读不到。
 */
private val bookPage = """
    <html><body>
      <div class="vol-item"><span class="vol-item-no">第01卷</span>
          <a href="javascript:void(0)" onclick="fileDownload(1176462)">下載</a></div>
      <div class="vol-item"><span class="vol-item-no">第02卷</span>
          <a href="javascript:void(0)" onclick="fileDownload(1176463)">下載</a></div>
      </body></html>
    """.trimIndent()

private fun cbz(pages: Int): ByteArray {
    val out = ByteArrayOutputStream()
    ZipOutputStream(out).use { z ->
        (1..pages).forEach {
            z.putNextEntry(ZipEntry(String.format("%03d.jpg", it)))
            z.write(byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte()) + "第${it}页".toByteArray())
            z.closeEntry()
        }
    }
    return out.toByteArray()
    }

class FileChapterTest {

    private val rule = RuleLoader.load(
        """
        {
          "id": "mh199", "name": "199 测试", "homeUrl": "https://199mh.com/", "kind": "comic",
          "toc": { "item": "div.vol-item", "name": ".vol-item-no", "href": "a @onclick@num" },
          "chapter": {
            "file": {
              "locate": "https://199mh.com/m/vol/download_add",
              "body": "ids[]={num}&ext=epub",
              "path": "result.0.url"
            }
          }
        }
        """.trimIndent(),
        "mh199",
    )



        /** 带 `login` 那一格的同一条规则：哈希那一档写不写得进、写歪了红不红，只问这一份。 */
    private fun ruleWithLogin(login: String): Rule = RuleLoader.load(
        """
        {
          "id": "mh199", "name": "199 测试", "homeUrl": "https://199mh.com/", "kind": "comic",
          "toc": { "item": "div.vol-item", "name": ".vol-item-no", "href": "a @onclick@num" },
          "chapter": { "file": { "locate": "https://199mh.com/m/vol/download_add", "body": "ids[]={num}&ext=epub", "path": "result.0.url" } },
          "login": $login
        }
        """.trimIndent(),
        "mh199",
    )

    private val loginBase = """{"url":"https://199mh.com/m/user/login","user":"email","pass":"password""""

    /**
     * 站点登录的 JS 写的是 `post['password'] = md5(post['password'])` —— 这一档没处写，应用就只能发明文，
     * 而它回的那句 `HTTP 400 {"code":4001001,"message":"登錄失敗"}` 和"号真的错了"长得一模一样（2026-10-30 实测）。
     * 所以：认 md5、不写就是原样发（别的站的密码就是密码）、认不得的值**当场红**。
     */
    @Test
    fun theLoginGridCarriesTheHashStageTheSiteAsksForAndRefusesMadeUpOnes() {
        assertEquals("md5 那一档要落进规则", LoginRule.MD5, ruleWithLogin("$loginBase,\"hash\":\"md5\"}").login!!.hash)
        assertEquals("大小写不敏感（规则是人手写的）", LoginRule.MD5, ruleWithLogin("$loginBase,\"hash\":\"MD5\"}").login!!.hash)
        assertEquals("不写 = 原样发，这一档不许默认打开", "", ruleWithLogin("$loginBase}").login!!.hash)
        val e = runCatching { ruleWithLogin("$loginBase,\"hash\":\"sha1\"}") }.exceptionOrNull()
        assertTrue(
            "认不得的哈希要当场红并点名那一格和那个值，实际：" + e?.message,
            e is RuleException && e.message!!.contains("login.hash") && e.message!!.contains("sha1"),
        )
    }

    /** 记请求的假站子：POST 取址回一条地址，GET 那条地址回一个包。 */
    private class Site(private var reply: String, private val file: ByteArray = cbz(3)) : Fetcher {
        val posts = ArrayList<String>()
        val gets = ArrayList<String>()
        var cookieSent = ""

        override fun fetch(req: PageRequest): Page {
            return when {
                req.method == "POST" -> {
                    posts += req.body
                    gets += req.url
                    Page(req.url, null, reply.toByteArray())
                }
                req.url.contains("/m/vol/") -> error("取址口不该被 GET")
                else -> {
                    gets += req.url
                    if (req.url.contains("199mh.com/comic/")) Page(req.url, null, bookPage.toByteArray())
                    else Page(req.url, null, file)
                }
            }
        }
    }

    private fun download(site: Site, store: ComicStore): ComicOutcome =
        ComicDownloader(rule, site, store, sleep = { }, clock = { 0L }).download(
            BookHit(sourceId = "mh199", sourceName = "199 测试", bookName = "脑洞学生会", author = "", bookUrl = "https://199mh.com/comic/Ddal"),
        )

    @Test
    fun bothWaysOfReadingAComicChapterIsRejected() {
        // 两种章同时写：引擎先走"一个文件"那一支，`chapter.image` 那格就成了没人读的写法 ——
        // 那不是"多写一格保险"，那是下一次改规则的人照着没人读的那一格改
        val e = runCatching {
            RuleLoader.load(
                """
                { "id": "both", "name": "两种都写", "homeUrl": "https://199mh.com/", "kind": "comic",
                  "toc": { "item": "div.vol-item", "name": ".vol-item-no" },
                  "chapter": { "image": "img @src",
                    "file": { "locate": "https://199mh.com/m/vol/download_add", "path": "result.0.url" } }
                }
                """.trimIndent(),
                "both",
            )
        }.exceptionOrNull()
        assertTrue("该拒装却装上了：${e?.message}", e is RuleException)
        assertTrue("说的是哪两格冲突：" + e?.message, e?.message?.contains("两种章") == true)
    }

    @Test
    fun fileChaptersAreOnlyForComics() {
        // 配在小说上不会报错，只会"下载完成、点开是空的"：那种静默失效在装的时候就该挡
        val e = runCatching {
            RuleLoader.load(
                """
                { "id": "novel", "name": "小说配了文件章", "homeUrl": "https://x.com/",
                  "toc": { "item": "a" },
                  "chapter": { "content": "#bd",
                    "file": { "locate": "https://x.com/locate", "path": "u" } }
                }
                """.trimIndent(),
                "novel",
            )
        }.exceptionOrNull()
        assertTrue("该拒装却装上了：${e?.message}", e is RuleException)
        assertTrue("说的是 kind 不配：" + e?.message, e?.message?.contains("comic") == true)
    }

    @Test
    fun numTakesTheFirstDigitRunFromAnAttribute() {
        val doc = org.jsoup.Jsoup.parse("""<div class="vol-item"><a href="javascript:void(0)" onclick="fileDownload(1176462)">下載</a></div>""")
        val a = doc.selectFirst("div.vol-item")!!
        assertEquals("1176462", pick(a, "a @onclick@num"))
        // href 那一格是 `javascript:void(0)`，里面确实有一串数字 —— `@num` 只抠第一串、不认语义，
        // 所以这一档必须指对属性：拿它去读 href 会老老实实回一个 "0"，两卷就下成同一本"第 0 卷"
        assertEquals("0", pick(a, "a @href@num"))
        assertEquals("那一格里没数字就该回空串（不编一个 0 出来）", "", pick(a, "span @onclick@num"))
    }

    @Test
    fun jsonPathWalksKeysAndIndices() {
        val text = """{"result":[{"url":"https://cdn/a.epub","n":2}],"ok":true}"""
        assertEquals("https://cdn/a.epub", Json.path(text, "result.0.url"))
        assertEquals("2", Json.path(text, "result.0.n"))
        assertEquals("取不到回空串，调用方才好把回包原文带上报错", "", Json.path(text, "result.0.nokey"))
        assertEquals("", Json.path(text, "nope"))
        assertEquals("", Json.path("这不是 JSON", "a"))
    }

    @Test
    fun locateGetsTheNumberFromTheChapterUrl() {
        val site = Site("""{"result":[{"url":"https://cdn.199mh.com/v/1176462.epub","file_name":"第01卷.epub"}]}""")
        val dir = File(System.getProperty("java.io.tmpdir"), "filechapter-" + System.nanoTime())
        val out = download(site, FileComicStore(dir))
        assertEquals(2, out.chapters)
        // 两章各打一枪取址，body 里带的必须是各自那一串号 —— 抄成同一串的话两卷会下成同一本
        assertTrue("取址 body：${site.posts}", site.posts.containsAll(listOf("ids[]=1176462&ext=epub", "ids[]=1176463&ext=epub")))
        assertEquals(6, out.images)
        dir.deleteRecursively()
    }

    @Test
    fun pagesLandInTheSameStoreTheImageCrawlerWrites() {
        val site = Site("""{"result":[{"url":"https://cdn.199mh.com/v/1176462.epub"}]}""")
        val dir = File(System.getProperty("java.io.tmpdir"), "filechapter2-" + System.nanoTime())
        val store = FileComicStore(dir)
        assertNotNull(download(site, store))
        // 页序表 + 散列文件名：漫画屏读的就是这两样（comicPageOrder → comicImageName）
        val box = dir.listFiles { f: File -> f.isDirectory }!!.first()
        val urls = comicPageOrder(box)
        assertEquals(3, urls.size)
        assertEquals(3, urls.map { File(box, comicImageName(it)) }.count { it.isFile })
        assertTrue("落盘的字节得真是那张页",
            ImageBookFile.isImage(File(box, comicImageName(urls[1])).readBytes()))
        dir.deleteRecursively()
    }

    @Test
    fun aReplyWithoutAnAddressFailsTheChapterInsteadOfReportingZeroPages() {
        // 站点不给内容时回的是 {"code":4001002,"message":"請先登錄"} —— 那一章不能算"抓到了但没页"
        val site = Site("""{"code":4001002,"message":"請先登錄"}""")
        val dir = File(System.getProperty("java.io.tmpdir"), "filechapter3-" + System.nanoTime())
        val out = download(site, FileComicStore(dir))
        assertEquals(listOf("第01卷", "第02卷"), out.failed)
        assertEquals("一章节都没成就不能在仓里留下 6 张图", 0, out.images)
        assertTrue(out.got.isEmpty())
        dir.deleteRecursively()
    }
}
