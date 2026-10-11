package com.bookreader.booksource

import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI
import java.net.URISyntaxException
import java.net.URL

/**
 * 真的去要一页。
 *
 * 三件事是这些站教的，不是凭想象写的：
 * 1. **必须带浏览器 UA**。默认的 `Java/21` 会被一大半站直接 403，表现却是"搜不到东西"，
 *    看起来像规则写错了 —— 所以 UA 写死在这里，并且有判据盯着（HttpFetcherTest 第一条）。
 * 2. **HTTP 状态不是 2xx 要抛**，不能交一张空页回去：交给空页会被当成"这一章正文是空的"，
 *    重试逻辑就白跑一趟。
 * 3. **字符集要带回去而不是在这里就解码**：这些站 HTTP 头、meta、实际字节三处经常不一致，
 *    顺序由 [Page.text] 定（书源明写 > HTTP 头 > meta > UTF-8）。
 */
class HttpFetcher(
    private val connectTimeoutMs: Int = 15_000,
    private val readTimeoutMs: Int = 30_000,
) : Fetcher {

    /**
     * 按主机存一小截 cookie。这些站几乎每一个都要"先访问一次首页拿会话，再发搜索"：
     * 真机探站时 书海阁 少了 `waf_sc`/`HMACCOUNT` 就直接回"找不到您要搜索的内容"。
     * 只做同名覆盖 + 过期就丢，不引一个 cookie 管理库。
     */
    private val jar = HashMap<String, MutableMap<String, String>>()

    override fun fetch(req: PageRequest): Page {
        // 走 URI 而不是 URL(String)：后者在 JDK 20 起废弃。顺带的结果是地址必须是合法 URI，
        // 竖线、空格、花括号那种写歪的模板在这里就报，不会变成"发出去一个被站点改写的地址"
        val uri = try {
            URI(req.url)
        } catch (e: URISyntaxException) {
            throw IOException("这个地址写歪了（" + e.reason + "）：" + req.url)
        }
        val host = uri.host.orEmpty()
        val conn = uri.toURL().openConnection() as? HttpURLConnection
            ?: throw IOException("这个地址不是 http/https：" + req.url)
        conn.instanceFollowRedirects = true
        conn.connectTimeout = connectTimeoutMs
        conn.readTimeout = readTimeoutMs
        conn.requestMethod = req.method
        conn.setRequestProperty("User-Agent", USER_AGENT)
        conn.setRequestProperty("Accept-Language", "zh-CN,zh;q=0.8")
        // 明说要 gzip：不问就收到的站点也会压（探站时 悠久小说网 就这么回了），问了就要自己解，
        // 两边行为统一，本机 JVM 的测试和真机才走同一条路
        conn.setRequestProperty("Accept-Encoding", "gzip")
        req.cookieSeed?.let { seed ->
            // 书源明写的 cookie 先种进罐子：有的站第一个请求就要它（书海阁的 waf_sc）
            seed.split(';', ' ').map { it.trim() }.filter { it.contains('=') }.forEach {
                jar.getOrPut(host) { HashMap() }[it.substringBefore('=').trim()] =
                    it.substringAfter('=').trim().removeSurrounding("'").removeSurrounding("\"")
            }
        }
        cookieHeader(host)?.let { conn.setRequestProperty("Cookie", it) }
        if (req.method == "POST") {
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded; charset=" + req.bodyCharset)
            conn.outputStream.use { it.write(req.body.toByteArray(charset(req.bodyCharset))) }
        }
        // 先取状态码（这一步才真的连上），再读 Set-Cookie：反过来的话 headerFields 还是空的
        val code = conn.responseCode
        takeCookies(host, conn)
        if (code !in 200..299) {
            conn.disconnect()
            throw IOException("HTTP " + code + " " + conn.responseMessage.orEmpty().trim())
        }
        // 跟着跳转走完之后的地址才是这一页真正的地址：相对链接要按它补全，
        // 按当初请求的那个补会补到跳转前的域名上
        val finalUrl = conn.url.toString()
        val charset = conn.contentType.orEmpty().substringAfter("charset=", "").substringBefore(';').trim()
        val raw = conn.inputStream.use { it.readBytes() }
        conn.disconnect()
        val bytes = if (conn.contentEncoding == "gzip") gzip(raw) else raw
        return Page(finalUrl, charset.ifEmpty { null }, bytes)
    }

    /** 站点压了又压不了的情况（截断、假 gzip）不当异常抛：交回原始字节，最坏是解出乱码，界面上看得见。 */
    private fun gzip(bytes: ByteArray): ByteArray = runCatching {
        java.util.zip.GZIPInputStream(bytes.inputStream()).readBytes()
    }.getOrDefault(bytes)

    private fun cookieHeader(host: String): String? =
        jar[host]?.takeIf { it.isNotEmpty() }?.entries?.joinToString("; ") { it.key + "=" + it.value }

    /** 只留 name=value：路径、域、有效期这些我们不按它们过滤，按主机名分格子已经够用。 */
    private fun takeCookies(host: String, conn: HttpURLConnection) {
        // 键的大小写按服务器原样进表（com.sun 那个测试服务器发的是 "Set-cookie"），所以按不区分大小写找
        val all = conn.headerFields.entries
            .firstOrNull { it.key != null && it.key.equals("Set-Cookie", ignoreCase = true) }?.value
            ?: conn.getHeaderField("Set-Cookie")?.let { listOf(it) }
            ?: return
        for (raw in all) {
            val pair = raw.substringBefore(';').trim()
            if (!pair.contains('=')) continue
            val value = pair.substringAfter('=').trim()
            val box = jar.getOrPut(host) { HashMap() }
            if (value.isEmpty() || value == "deleted") box.remove(pair.substringBefore('=').trim())
            else box[pair.substringBefore('=').trim()] = value
        }
    }

    companion object {
        /** 一个常见的桌面 Chrome UA。这些站只认"像浏览器"，不认默认 UA。 */
        const val USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) " +
                "Chrome/129.0.0.0 Safari/537.36"
    }
}
