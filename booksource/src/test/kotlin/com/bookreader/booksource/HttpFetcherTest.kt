package com.bookreader.booksource

import com.sun.net.httpserver.HttpServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.net.InetSocketAddress
import java.nio.charset.Charset
import java.util.Collections

/**
 * 真发请求那一层。测的是本机起的 http 服务（不是外网），所以这条不需要联网也能跑，
 * 但它验的是"发出去的字节和收回来的头对不对"——那是 mock 验不到的部分。
 */
class HttpFetcherTest {

    private lateinit var server: HttpServer
    private var base = ""
    private val headers = Collections.synchronizedMap(LinkedHashMap<String, String>())
    private var lastBody = ByteArray(0)

    @Before
    fun start() {
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/ua") { ex -> reply(ex, 200, "ok".toByteArray(), "text/html") }
        val gbk = "第一章 开门".toByteArray(Charset.forName("GBK"))
        server.createContext("/gbk") { ex -> reply(ex, 200, gbk, "text/html; charset=gbk") }
        server.createContext("/down") { ex -> reply(ex, 503, ByteArray(0), "text/html") }
        server.createContext("/redir") { ex ->
            ex.responseHeaders.add("Location", "$base/final")
            ex.sendResponseHeaders(302, -1)
            ex.close()
        }
        server.createContext("/final") { ex -> reply(ex, 200, "落地的这一页".toByteArray(), "text/html; charset=utf-8") }
        // 两个前缀分开挂：HttpServer 的 context 按路径前缀匹配，挂一个 "/whoami" 收不到 "/used"
        server.createContext("/whoami") { ex ->
            ex.responseHeaders.add("Set-Cookie", "sid=abc42; Path=/")
            reply(ex, 200, "种下了".toByteArray(), "text/html; charset=utf-8")
        }
        server.createContext("/used") { ex ->
            reply(ex, 200, ex.requestHeaders.getFirst("Cookie").orEmpty().toByteArray(), "text/html; charset=utf-8")
        }
        server.createContext("/gz") { ex ->
            val gz = java.io.ByteArrayOutputStream().also { o ->
                java.util.zip.GZIPOutputStream(o).use { it.write("压过的一段".toByteArray()) }
            }.toByteArray()
            ex.responseHeaders.add("Content-Encoding", "gzip")
            reply(ex, 200, gz, "text/html; charset=utf-8")
        }
        server.createContext("/echo") { ex ->
            lastBody = ex.requestBody.readBytes()
            reply(ex, 200, lastBody, "text/html; charset=utf-8")
        }
        server.start()
        base = "http://127.0.0.1:" + server.address.port
    }

    private fun reply(ex: com.sun.net.httpserver.HttpExchange, code: Int, body: ByteArray, type: String) {
        headers[ex.requestURI.path] = ex.requestHeaders.getFirst("User-Agent") ?: ""
        ex.responseHeaders.add("Content-Type", type)
        ex.sendResponseHeaders(code, if (body.isEmpty()) -1L else body.size.toLong())
        if (body.isNotEmpty()) ex.responseBody.use { it.write(body) } else ex.close()
    }

    @After
    fun stop() {
        server.stop(0)
    }

    @Test
    fun requestGoesOutWithABrowserUserAgentNotTheJvmOne() {
        HttpFetcher().fetch(PageRequest("$base/ua"))
        val ua = headers["/ua"].orEmpty()
        println("[UA] $ua")
        assertTrue("默认那个是 Java/21，这些站直接 403，表现却像规则写错了：" + ua, ua.startsWith("Mozilla/5.0"))
        assertTrue(ua.contains("Chrome/"))
    }

    @Test
    fun headerCharsetIsHandedBackNotDecodedHere() {
        val p = HttpFetcher().fetch(PageRequest("$base/gbk"))
        assertEquals("gbk", p.headerCharset)
        assertEquals("第一章 开门", p.text())
        assertNotEquals(
            "同一批字节按 UTF-8 解必须是另一个结果，否则这条测不出字符集有没有生效",
            "第一章 开门", String(p.bytes, Charsets.UTF_8),
        )
    }

    @Test
    fun non2xxThrowsInsteadOfHandingBackAnEmptyPage() {
        val e = runCatching { HttpFetcher().fetch(PageRequest("$base/down")) }.exceptionOrNull()
        assertTrue("实际：" + e, e is java.io.IOException && e.message!!.contains("503"))
    }

    @Test
    fun finalUrlIsWhereTheRedirectLanded() {
        val p = HttpFetcher().fetch(PageRequest("$base/redir"))
        assertTrue("实际：" + p.finalUrl, p.finalUrl.endsWith("/final"))
        assertEquals("落地的这一页", p.text())
    }

    /** 这些站几乎每一个都要"先拿会话、再发搜索"：少这一环，书海阁直接回"找不到您要搜索的内容"。 */
    @Test
    fun cookieSetByTheFirstResponseIsSentBackOnTheNext() {
        val f = HttpFetcher()
        f.fetch(PageRequest("$base/whoami"))
        val back = f.fetch(PageRequest("$base/used")).text()
        println("[cookie] 第二枪带回去的：" + back)
        assertEquals("sid=abc42", back)
    }

    @Test
    fun seededCookieFromTheRuleGoesOutOnTheFirstRequest() {
        val f = HttpFetcher()
        f.fetch(PageRequest("$base/used", cookieSeed = "waf_sc=''; HMACCOUNT='x1'"))
        assertEquals("HMACCOUNT=x1", f.fetch(PageRequest("$base/used2")).text().let { seed ->
            // 第二枪只该带回罐子里有的那两条，顺序不保证，按名字取
            Regex("HMACCOUNT=[^;]*").find(seed)?.value ?: "无"
        })
    }

    @Test
    fun gzippedBodyIsInflated() {
        assertEquals("压过的一段", HttpFetcher().fetch(PageRequest("$base/gz")).text())
    }

    @Test
    fun postBodyArrivesByteForByte() {
        val body = "submit=Search&keyword=%E6%B5%8B%E8%AF%95"
        val p = HttpFetcher().fetch(PageRequest("$base/echo", "POST", body))
        assertEquals(body, String(lastBody, Charsets.UTF_8))
        assertEquals(body, p.text())
    }
}
