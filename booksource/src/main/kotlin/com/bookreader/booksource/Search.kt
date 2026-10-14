package com.bookreader.booksource

import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import java.net.URI
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
    // Atom 那一档（arXiv）用 XML 解析器解，选择器写法不变：`<entry>` 就是 `entry`
    return parseDoc(rule, base, html).select(s.item).mapNotNull { e ->
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
    return runCatching { pick(parseDoc(rule, base, html, tocFormat(rule)), rule.intro) }.getOrDefault("")
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

/**
 * 目录页 → 每一章的 (标题, 链接)。只取 item 选中的那些元素，顺序就是页面上的顺序（`reverse` 翻它）。
 *
 * 链接默认取这条元素自己的 `href`；条目不是 `<a>` 而是"容器里另一格挂地址"的那种源（播客 RSS 的
 * `<item>` 把音频地址写在 `<enclosure url="…">` 上）用 `toc.href` 指过去，写法同 [pick]。
 */
fun tocChapters(rule: Rule, html: String, base: String): List<Pair<String, String>> {
    val t = rule.toc
    val out = parseDoc(rule, base, html, tocFormat(rule)).select(t.item).mapNotNull { a ->
        val raw = (if (t.href.isBlank()) a.attr("abs:href") else pick(a, t.href)).trim()
        val url = if (raw.isEmpty()) "" else absolutize(base, raw)
        if (url.isEmpty()) return@mapNotNull null
        // 章名默认取这条 a 自己的文字；挂在里面那个 span 上的（后面还跟着"阅读"那颗按钮字那种，
        // 或者站点在名字后面又缀了一格"(45p)"页数）用 toc.name 指过去。
        // 指过去**取空**要退回 a 自己的文字，不能一路退到 URL 尾巴：那目录上就写出"166498.html"，
        // 站点少给一个 class 的表现不该是几百行网址
        val own = a.ownText().ifBlank { a.text() }.trim()
        val name = if (t.name.isBlank()) own else pick(a, t.name).trim().ifBlank { own }
        (if (name.isEmpty()) url.substringAfterLast('/') else name) to url
    }
    return if (t.reverse) out.asReversed() else out
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
    // 分页导航摆在正文框**里面**（多数站就是这样），先摘掉再抽文字，否则"下一页"会变成章里的一行
    dropPager(box, rule.chapter.nextPage)
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

/**
 * "下一页"那颗链接（连同挂它的那一小条导航）不是正文，抽文字之前要摘掉。
 *
 * 只摘链接不够：站点在这一颗旁边一定还摆着"上一章 / 返回目录"，跟着链接一起留在正文里
 * 就是每章中间多出来的几行废话。所以链接的父节点若整条都很短（导航那一行的长度），连父节点一起摘；
 * 长就不摘父节点 —— 那是正文里夹了个链接（有的站把分页写在这一章最后一句话里），砍掉整段会丢字。
 */
private fun dropPager(box: Element, sel: String) {
    if (sel.isBlank()) return
    box.select(sel).forEach { a ->
        val bar = a.parent()
        if (bar != null && bar != box && bar.text().length <= NAV_BAR_MAX_CHARS) bar.remove() else a.remove()
    }
}

/** 导航条那一行的长度上限（"上一章 返回目录 下一页" 也就十几个字）；正文段落几乎不可能这么短。 */
private const val NAV_BAR_MAX_CHARS = 24

/**
 * 一章切几页的站（`123.html` → `123_2.html` → `123_3.html`）：这一页之后还剩半章没有。
 *
 * 找链接的顺序：**正文框里的先试**，找不到再整页找。整页这一步不是可有可无的宽松 ——
 * 书海阁的 `#pager_next` 摆在页顶、`#A3` 摆在页尾 `.bottem2`，两个都在 `#content` 外面；
 * 只认框内的话，它那一章十页只会下回第一页，后面九页无声丢掉。
 *
 * 真正的闸是 [samePageFamily]：只跟"同一章的下一页"。这一条挡住的正是梦书中文摆在正文框里的
 * `class="next"`（那是**下一章**）和书海阁最后一跳指向的 `/shu_102752.html`（那是书主页）——
 * 这两处都在页面上写着"下一页/下一章"，光看文字或只看选择器都会把整本书吞进第一章。
 */
fun nextPageUrl(rule: Rule, html: String, at: String): String? {
    val sel = rule.chapter.nextPage
    if (sel.isBlank()) return null
    val doc = html(at, html)
    val box = doc.selectFirst(rule.chapter.content)
    val hrefs = ArrayList<String>()
    if (box != null) box.select(sel).forEach { hrefs += it.attr("href").trim() }
    doc.select(sel).forEach { hrefs += it.attr("href").trim() }
    return hrefs.filter { it.isNotEmpty() }.distinct().mapNotNull { abs(at, it) }.firstOrNull { samePageFamily(at, it) }
}

private fun abs(at: String, href: String): String? =
    runCatching { URI(at).resolve(href).normalize().toString() }.getOrNull()

/**
 * `123.html` 与 `123_2.html` 是同一章的两半；`124.html` 是下一章，不算。
 *
 * 判据是「拆掉分页后缀之后剩的名字一样」，不是「目标名以来源名打头」—— 后者会把 `1.html → 10.html`
 * 也算成同族（10 正好以 1 打头），而那是下一章：一章一页的站里 id 接一位就是下一本/下一章，
 * 跟过去等于把整本书并进第一章。分隔符（`_` 或 `-`）必须真的存在，光靠数字接尾不算分页。
 */
internal fun samePageFamily(from: String, to: String): Boolean {
    if (from == to) return false
    val a = runCatching { URI(from) }.getOrNull() ?: return false
    val b = runCatching { URI(to) }.getOrNull() ?: return false
    if (a.host != b.host || a.path == null || b.path == null) return false
    if (a.path.substringBeforeLast('/', "") != b.path.substringBeforeLast('/', "")) return false
    val (aS, aE) = fileParts(a.path.substringAfterLast('/'))
    val (bS, bE) = fileParts(b.path.substringAfterLast('/'))
    if (aE != bE) return false
    return pageStem(aS) == pageStem(bS)
}

/** `123_2` 和 `123` 属于同一章 `123`；`1232` 属于另一章。 */
private fun pageStem(name: String): String {
    val cut = name.indexOfLast { it == '_' || it == '-' }
    return if (cut > 0 && name.substring(cut + 1).isNotEmpty() && name.substring(cut + 1).all { it.isDigit() })
        name.substring(0, cut) else name
}

private fun fileParts(name: String): Pair<String, String> =
    if (name.contains('.')) name.substringBeforeLast('.') to name.substringAfterLast('.') else name to ""

/** 一个源 = 一个引擎实例。抓页这件事交给 [Fetcher]，这里只做"取值"。 */
class SearchEngine(private val rule: Rule, private val fetch: Fetcher) {

    /** 关键词搜一本。POST 那类源的请求体同样要填关键词，所以 data 也过一遍 expand。 */
    fun search(keyword: String, page: Int = 1): List<BookHit> {
        val s = rule.search ?: return emptyList()
        val url = expand(s.url, keyword, page, rule.charset)
        val body = if (s.method == "POST") formBody(s.data, keyword, rule.charset) else ""
        val p = fetch.fetch(PageRequest(url, s.method, body, s.cookies.ifBlank { null }, rule.charset.ifBlank { "UTF-8" }))
        val text = p.text(rule.charset)
        // JSON 那一档连"解成一棵树"都不是，取值写法是点号路径，所以在这里就分岔。
        // 出口统一按书页地址去重：iTunes 那个索引口会把同一本回两条，而结果那一列的 key 是
        // 「源 id + 书页地址」—— 重复就是撞 key，真机上整页闪退过一次
        // （`Key "podcast-rsshttps://www.ximalaya.com/album/78520485.xml" was already used`）。
        // 两条同地址的行本来也是一本书：在架查找和下载表都按 bookUrl 认，摆两行只会让人点错一次。
        return (if (s.format == FORMAT_JSON) jsonResults(rule, text) else searchResults(rule, text, p.finalUrl))
            .distinctBy { it.bookUrl }
    }

    /** 一本书的"头"：简介 + 完整目录。**一次书页请求拿全**，大纲就是目录的前若干条章名。 */
    fun head(bookUrl: String): BookHead {
        // 文献源（kind=paper）压根没有目录那一段：它一片 PDF 就是"一整本"。
        // 不在这里说的话，选不中任何元素的后果是"这本书 0 章"，而 0 章在界面上是一本空书，不是一句报错
        if (rule.toc.item.isBlank()) {
            throw RuleException("「" + rule.name + "」没有 toc 那一段：这一条源是一片一片的 PDF，没有目录可读")
        }
        val p = fetch.fetch(PageRequest(bookUrl))
        val text = p.text(rule.charset)
        return BookHead(introOf(rule, text, p.finalUrl), chaptersFrom(text, p.finalUrl))
    }

    /** 首页那一堆分类块上的热门书单。这条源没写 hot 就返回空，精选那一页不列它。 */
    fun hot(): List<BookHit> {
        val h = rule.hot ?: return emptyList()
        val p = fetch.fetch(PageRequest(h.url))
        // 和 search 同一个口径去重：首页上"同一本书既在玄幻块、又在热销块"是常态，
        // 而精选那两排卡片的 key 也是「源 id + 书页地址」—— 不去重就是同一颗撞 key 的雷
        return hotBooks(rule, p.text(rule.charset), p.finalUrl).distinctBy { it.bookUrl }
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
