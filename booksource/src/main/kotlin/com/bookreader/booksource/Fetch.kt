package com.bookreader.booksource

import java.nio.charset.Charset
import java.util.Locale

/**
 * 一次取页请求。
 *
 * `body` 一定是 `k1=v1&k2=v2` 那种已编码的表单串（书源里 `{k: v}` 那种写法由 [formBody] 转过来）。
 * `cookieSeed` 是书源明写的初始 cookie（真机探站时 书海阁 少它就是"找不到您要搜索的内容"）。
 */
data class PageRequest(
    val url: String,
    val method: String = "GET",
    val body: String = "",
    val cookieSeed: String? = null,
    /** 表单串的字符集：GBK 站点必须按它的字符集发，否则服务器直接吐排序规则冲突（365 就是这么回的）。 */
    val bodyCharset: String = "UTF-8",
)

/**
 * 取回来的一页：只带字节和"HTTP 头里写的字符集"。
 *
 * 不在这里就转成 String 是有原因的：这些站点的编码经常三处不一致（HTTP 头一份、
 * meta 标签一份、实际字节又是一份），先定字符集再解码这一步得留给 [text]。
 */
data class Page(val finalUrl: String, val headerCharset: String?, val bytes: ByteArray) {

    /** 解码顺序：书源明写的 > HTTP 头 > 页面里的 meta > UTF-8。写错的字符集名不当崩，退到下一档。 */
    fun text(ruleCharset: String = ""): String {
        for (n in listOfNotNull(ruleCharset.blankToNull(), headerCharset?.blankToNull(), metaCharset())) {
            val cs = runCatching { Charset.forName(n) }.getOrNull() ?: continue
            return String(bytes, cs)
        }
        return String(bytes, Charsets.UTF_8)
    }

    /** 只在前 2048 字节里找：charset 一定在 head 里，往后翻只会把正文里的字误当声明。 */
    private fun metaCharset(): String? {
        val head = String(bytes, 0, minOf(bytes.size, 2048), Charsets.ISO_8859_1).lowercase(Locale.ROOT)
        val i = head.indexOf("charset")
        if (i < 0) return null
        val rest = head.substring(i + "charset".length).trimStart(' ', '=', '"', '\'')
        val end = rest.indexOfFirst { !CHARSET_CHARS.contains(it) }
        val name = if (end < 0) rest else rest.substring(0, end)
        return name.blankToNull()
    }

    private fun String.blankToNull(): String? = trim().ifEmpty { null }

    private companion object {
        val CHARSET_CHARS = ('a'..'z') + ('0'..'9') + '-' + '.'
    }
}

/**
 * 取页这件事的接口 —— 网络实现放在 :app（和 AiClient 一样用 HttpURLConnection）。
 *
 * 这一层刻意是个接口：判据要能离线跑。搜书引擎的正确性是"给定这一页的 HTML，
 * 抽出来的字段对不对"，那就不该逼测试去联网；联网那一段单独在真机上验一次。
 */
interface Fetcher {
    fun fetch(req: PageRequest): Page
}

/** 只在测试里用：把本地夹具 HTML 当成"这一页"。 */
class MemoryFetcher(private val pages: Map<String, String>) : Fetcher {
    override fun fetch(req: PageRequest): Page =
        Page(req.url, null, (pages[req.url] ?: error("夹具里没有这一页：" + req.url)).toByteArray(Charsets.UTF_8))
}
