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
 * - `"@text"` / `"css@text"` —— 只要这个元素**自己**那截文字（不含子元素；选择器留空就是这一格本身）。
 *   榜单那一行常见 `<li><a href=…>书名</a>/作者</li>`：书名从 a 取，作者只能用这一档，行首那个 `/` 顺手去掉。
 * - `""` —— 这一项不取。
 */
fun pick(root: Element, spec: String): String {
    val t = spec.trim()
    if (t.isEmpty()) return ""
    val at = t.indexOf('@')
    val sel = (if (at < 0) t else t.substring(0, at)).trim()
    val attr = if (at < 0) null else t.substring(at + 1).trim().ifEmpty { null }
    val e = if (sel.isEmpty()) root else root.selectFirst(sel) ?: return ""
    if (attr == null) return (e.ownText().ifBlank { e.text() }).trim()
    if (attr == "text") return e.ownText().trim().trimStart('/', '·', '-', ':', ' ').trim()
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

/** 书页 → 那段内容简介。规则没写这一格、或者页上选不中 → 空串（简介没有就不显示，不编）。 */
fun introOf(rule: Rule, html: String, base: String): String {
    if (rule.intro.isBlank()) return ""
    return runCatching { pick(html(base, html), rule.intro) }.getOrDefault("")
}

/**
 * 首页/榜单页 → 热门书单。两级选：先按 [HotRule.group] 切出每个分类块，块上取分类名，
 * 块内再按 [HotRule.item] 挑每一本书。
 *
 * 书名或链接缺一样就不算一条（和搜索结果同一口径）：宁可少一条，也不给精选那一页摆一张点不动的卡。
 */
fun hotBooks(rule: Rule, html: String, base: String): List<BookHit> {
    val h = rule.hot ?: return emptyList()
    val doc = html(base, html)
    return doc.select(h.group).flatMap { g ->
        val category = pick(g, h.category)
        g.select(h.item).mapNotNull { e ->
            val name = pick(e, h.bookName)
            val url = pick(e, h.bookUrl)
            if (name.isEmpty() || url.isEmpty()) return@mapNotNull null
            BookHit(
                sourceId = rule.id,
                sourceName = rule.name,
                bookName = name,
                author = pick(e, h.author),
                bookUrl = url,
                category = category,
            )
        }
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

/**
 * 书页 → 封面图的地址。规则没写这条、页上选不中、选出来不是 http(s) → 一律空串。
 *
 * 空串**不算失败**：一大半书本来就没封面（站点会自己换成 nocov.jpg 那种图），
 * 这时候书架走默认封面就行，不该在任务那一行报一句红字吓人的。
 */
fun coverUrl(rule: Rule, html: String, base: String): String {
    if (rule.cover.isBlank()) return ""
    val url = runCatching { pick(html(base, html), rule.cover) }.getOrDefault("")
    return if (url.startsWith("http://") || url.startsWith("https://")) url else ""
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
    // 段与段之间交出一个**空行**（两个换行），不是一个换行：阅读器那一格空行是照"原文这里空了行"排的
    // （`PageComposer` 段末那一处），站里抓回来的正文原先 join 成 "\n"，整本就段段挤在一起。
    // 上面已经把空行滤干净了（`filter { it.isNotEmpty() }`），所以这里 join 成 "\n\n" 不会出现两道空隙
    // —— 也就是"原文有空行的就不用再加"。
    return text.replace(Regex("\\p{Zs}"), " ").split('\n').map { it.trim() }.filter { it.isNotEmpty() }.joinToString("\n\n")
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

    /** 一本书的"头"：简介 + 完整目录。**一次书页请求拿全**，大纲就是目录的前若干条章名。 */
    fun head(bookUrl: String): BookHead {
        val p = fetch.fetch(PageRequest(bookUrl))
        val text = p.text(rule.charset)
        return BookHead(introOf(rule, text, p.finalUrl), chaptersFrom(text, p.finalUrl))
    }

    /** 首页那一堆分类块上的热门书单。这条源没写 hot 就返回空，精选那一页不列它。 */
    fun hot(): List<BookHit> {
        val h = rule.hot ?: return emptyList()
        val p = fetch.fetch(PageRequest(h.url))
        return hotBooks(rule, p.text(rule.charset), p.finalUrl)
    }

    /** 目录 = 这一页上选到的章 + 下拉框里其它页的章。`base` 就是这一页自己的 URL，等于它就不重取。 */
    private fun chaptersFrom(text: String, base: String): List<Pair<String, String>> {
        val out = LinkedHashMap<String, String>()
        for ((name, url) in tocChapters(rule, text, base)) out[url] = name
        for (u in tocPageUrls(rule, text, base)) {
            if (u == base) continue
            val p = fetch.fetch(PageRequest(u))
            for ((name, url) in tocChapters(rule, p.text(rule.charset), p.finalUrl)) out[url] = name
        }
        return out.map { (url, name) -> name to url }
    }

    /** 一本书的完整目录（含下拉框分页）。抓正文的循环在下一阶段，这里先把"有哪些章"取全。 */
    fun catalog(bookUrl: String): List<Pair<String, String>> {
        val first = fetch.fetch(PageRequest(bookUrl))
        return chaptersFrom(first.text(rule.charset), first.finalUrl)
    }
}
