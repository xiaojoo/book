package com.bookreader.booksource

import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import java.net.URLEncoder
import java.nio.charset.Charset

/**
 * 取值字段的写法只有三种，够用就行：
 * - `"css"` —— 第一个匹配元素的文字。先取 ownText（元素自己的那截），空了才取 text，
 *   因为"书名在 a 里、作者在后一个 span 里"那种行，用 text 会把整行都当成书名。
 * - `"css@href"` —— 第一个匹配元素的属性；href/src 自动补成绝对地址（这些站一半链接是相对的）。
 * - `""` —— 这一项不取。
 */
fun pick(root: Element, spec: String): String {
    val t = spec.trim()
    if (t.isEmpty()) return ""
    val at = t.indexOf('@')
    val sel = (if (at < 0) t else t.substring(0, at)).trim()
    val attr = if (at < 0) null else t.substring(at + 1).trim().ifEmpty { null }
    val e = root.selectFirst(sel) ?: return ""
    if (attr == null) return (e.ownText().ifBlank { e.text() }).trim()
    return (if (attr == "href" || attr == "src") e.attr("abs:" + attr) else e.attr(attr)).trim()
}

/**
 * 搜索结果里的链接模板：`%s` 换关键词（按**站点字符集**编码），`%n` 换页码。
 *
 * 为什么不是恒用 UTF-8：探站时 笔趣阁365 的库是 gbk_bin，UTF-8 的"剑"发过去直接吐
 * `Illegal mix of collations` 并且零结果 —— 编码这一格错了，表现就是"这条源搜不到东西"。
 */
fun expand(template: String, keyword: String, page: Int = 1, charset: String = "UTF-8"): String =
    template.replace("%s", enc(keyword, charset)).replace("%n", page.toString())

private fun enc(v: String, charset: String): String =
    URLEncoder.encode(v, runCatching { Charset.forName(charset) }.getOrDefault(Charsets.UTF_8))

/**
 * 书源里 `data` 那段的写法是 `{k1: v1, k2: v2}`，发出去必须是 `k1=v1&k2=v2`。
 * 原样发那串花括号是发不出去的（真接上时才暴露，所以在这里转，并且有判据）。
 * 不像表单的串（已经含 `=`）原样透传。
 */
fun formBody(data: String, keyword: String, charset: String = "UTF-8"): String {
    val t = data.trim()
    if (!t.startsWith("{") || !t.endsWith("}")) return expand(t, keyword, 1, charset)
    return t.removeSurrounding("{", "}")
        .split(',')
        .map { it.trim() }
        .filter { it.contains(':') }
        .joinToString("&") {
            val k = it.substringBefore(':').trim().removeSurrounding("\"")
            val v = it.substringAfter(':').trim().removeSurrounding("\"")
            // 只编一次：先 expand 再 enc 会把 %E5 变成 %25E5，站点收到的是字面百分号，搜出来就是零结果
            enc(k, charset) + "=" + enc(v.replace("%s", keyword), charset)
        }
}

/** 一整个搜索结果页 → 命中列表。`base` 是这一页自己的 URL，相对链接靠它补全。 */
fun searchResults(rule: Rule, html: String, base: String): List<BookHit> {
    val s = rule.search ?: return emptyList()
    return html(base, html).select(s.item).mapNotNull { e ->
        val name = pick(e, s.bookName)
        val url = pick(e, s.bookUrl)
        // 书名或链接缺一样就不算一条：宁可少一条，也不给界面一条点不动的假结果
        if (name.isEmpty() || url.isEmpty()) return@mapNotNull null
        BookHit(
            sourceId = rule.id,
            sourceName = rule.name,
            bookName = name,
            author = pick(e, s.author),
            bookUrl = url,
            category = pick(e, s.category),
            latestChapter = pick(e, s.latestChapter),
            lastUpdateTime = pick(e, s.lastUpdateTime),
            wordCount = pick(e, s.wordCount),
            intro = pick(e, s.intro),
        )
    }
}

/** 目录页 → 每一章的 (标题, 链接)。只取 item 选中的那些 <a>，顺序就是页面上的顺序。 */
fun tocChapters(rule: Rule, html: String, base: String): List<Pair<String, String>> =
    html(base, html).select(rule.toc.item).mapNotNull { a ->
        val url = a.attr("abs:href").trim()
        if (url.isEmpty()) return@mapNotNull null
        val name = a.ownText().ifBlank { a.text() }.trim()
        (if (name.isEmpty()) url.substringAfterLast('/') else name) to url
    }

/**
 * 目录分页：`toc.nextPage` 指向的是那种"第 1/2/3 页"的下拉框，取每个 option 的 value
 * （没有 value 就用文字）填进 `toc.url` 模板。没有下拉框的站返回空列表，调用方就只抓当前这一页。
 */
fun tocPageUrls(rule: Rule, html: String, base: String): List<String> {
    val t = rule.toc
    if (t.nextPage.isBlank() || t.url.isBlank()) return emptyList()
    return html(base, html).select(t.nextPage).mapNotNull { o ->
        val v = o.attr("value").trim().ifEmpty { o.text().trim() }
        if (v.isEmpty()) null else t.url.replace("%s", v)
    }
}

/** 正文页 → 去完广告的文字。返回空串 = 这一页什么都没抽到（限流站就是这样，不报错，就是空）。 */
fun chapterText(rule: Rule, html: String, base: String): String {
    val box = html(base, html).selectFirst(rule.chapter.content) ?: return ""
    // 分段靠 <br>（这类站一大半如此）或 </p>；先换成换行，再剥标签。
    // 必须用 wholeText 不能用 text：text() 会把换行压成空格，一整本就成一坨了。
    val raw = box.html()
        .replace(Regex("(?i)<script[\\s\\S]*?</script>"), "")
        .replace(Regex("(?i)<style[\\s\\S]*?</style>"), "")
        .replace(Regex("(?i)<br\\s*/?\\s*>"), "\n")
        .replace(Regex("(?i)</p\\s*>"), "\n")
        .let { Jsoup.parseBodyFragment(it).body().wholeText() }
    var text = raw
    for (f in rule.chapter.filter) text = text.replace(Regex(f), "")
    // 站点用 &nbsp;（U+00A0，Unicode 类别 Zs）当段首缩进，而 String.trim() 只认
    // Character.isWhitespace，U+00A0 不在里面：不换的话删掉广告那句会留下"看不见但非空"的一行，
    // 并且每段前面都挂着四个清不掉的空格（阅读器自己已经缩进 2em）。用 \p{Zs} 写免得源码里放实体空格。
    return text.replace(Regex("\\p{Zs}"), " ").split('\n').map { it.trim() }.filter { it.isNotEmpty() }.joinToString("\n")
}

private fun html(base: String, body: String): Element = Jsoup.parse(body, base)

/** 一个源 = 一个引擎实例。抓页这件事交给 [Fetcher]，这里只做"取值"。 */
class SearchEngine(private val rule: Rule, private val fetch: Fetcher) {

    /** 关键词搜一本。POST 那类源的请求体同样要填关键词，所以 data 也过一遍 expand。 */
    fun search(keyword: String, page: Int = 1): List<BookHit> {
        val s = rule.search ?: return emptyList()
        val url = expand(s.url, keyword, page, rule.charset)
        val body = if (s.method == "POST") formBody(s.data, keyword, rule.charset) else ""
        val p = fetch.fetch(PageRequest(url, s.method, body, s.cookies.ifBlank { null }, rule.charset.ifBlank { "UTF-8" }))
        return searchResults(rule, p.text(rule.charset), p.finalUrl)
    }

    /** 一本书的完整目录（含下拉框分页）。抓正文的循环在下一阶段，这里先把"有哪些章"取全。 */
    fun catalog(bookUrl: String): List<Pair<String, String>> {
        val first = fetch.fetch(PageRequest(bookUrl))
        val text = first.text(rule.charset)
        val out = LinkedHashMap<String, String>()
        for ((name, url) in tocChapters(rule, text, first.finalUrl)) out[url] = name
        for (u in tocPageUrls(rule, text, first.finalUrl)) {
            if (u == first.finalUrl) continue
            val p = fetch.fetch(PageRequest(u))
            for ((name, url) in tocChapters(rule, p.text(rule.charset), p.finalUrl)) out[url] = name
        }
        return out.map { (url, name) -> name to url }
    }
}
