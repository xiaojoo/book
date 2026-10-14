package com.bookreader.booksource

import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import org.jsoup.parser.Parser
import java.net.URI

/**
 * 一页回来的东西按 `search.format` 决定用哪一套取值写法。
 *
 * 为什么不自动认："这看起来像 XML 就按 XML 解"会把 HTML 里那段 `<?xml ... ?>` 处理指令
 * 当成整页的根，一张好端端的书页就被解成空表 —— 自动认出来的是"格式"，看不出"这一条源本来想怎么写"。
 * html 和 xml 两套都用 CSS 选择器（jsoup 把 Atom 也解成一棵树），只有 json 那一套是点号路径。
 */
private const val FORMAT_XML = "xml"
internal const val FORMAT_JSON = "json"

/** [pick] 的多值那一份：同一个写法选出**每一个**匹配，顺序就是页面上的顺序。 */
fun pickAll(root: Element, spec: String): List<String> {
    val t = spec.trim()
    if (t.isEmpty()) return emptyList()
    val at = t.indexOf('@')
    val sel = (if (at < 0) t else t.substring(0, at)).trim()
    val attr = if (at < 0) null else t.substring(at + 1).trim().ifEmpty { null }
    if (sel.isEmpty()) return listOf(pick(root, t))
    return root.select(sel).mapNotNull { e ->
        when {
            attr == null -> (e.ownText().ifBlank { e.text() }).trim().ifEmpty { null }
            attr == "text" -> e.ownText().trim().ifEmpty { null }
            attr == "href" || attr == "src" -> e.attr("abs:$attr").trim().ifEmpty { null }
            else -> e.attr(attr).trim().ifEmpty { null }
        }
    }
}

/** 一章漫画 = 一排图：这一章页面上 [ChapterRule.image] 选中的那些地址，按页面顺序。 */
fun chapterImages(rule: Rule, body: String, at: String): List<String> {
    if (rule.chapter.image.isBlank()) return emptyList()
    // 整页都选，不先夹到某个容器：漫画阅读器那一排 img 有的挂在 body 下、有的挂在 #images 里，
    // 而夹错一个容器就是"这一章抓到了、一张图也没有"
    return pickAll(parseDoc(rule, at, body), rule.chapter.image)
        .map { absolutize(at, it) }
        .filter { it.startsWith("http://") || it.startsWith("https://") }
}

/** 一集有声 = 一段（或几段）音频：这一集页面上 [ChapterRule.audio] 选中的那些地址，按页面顺序。 */
fun chapterAudio(rule: Rule, body: String, at: String): List<String> {
    if (rule.chapter.audio.isBlank()) return emptyList()
    // 整页都选，不先夹到某个容器：和漫画那一条同一口径，夹错一个容器就是"这一集抓到了、一段音频也没有"
    return pickAll(parseDoc(rule, at, body), rule.chapter.audio)
        .map { absolutize(at, it) }
        .filter { it.startsWith("http://") || it.startsWith("https://") }
}

/**
 * 懒加载的站把真地址写在 `data-original`，那是个裸路径或完整地址都可能：
 * `/15/14095/a.jpg` 这种得按"这一页自己的地址"补全，否则请求发不出去（URI 直接抛）。
 */
internal fun absolutize(at: String, v: String): String {
    if (v.startsWith("http://") || v.startsWith("https://") || v.startsWith("//")) return if (v.startsWith("//")) {
        runCatching { URI(at).scheme + ":" + v }.getOrDefault(v)
    } else v
    return runCatching { URI(at).resolve(v).normalize().toString() }.getOrDefault(v)
}

/**
 * 一页 → 一棵可选择的树。`html` 和 `xml` 两套都用 CSS 选择器（jsoup 把 Atom 也解成一棵树），
 * 只有 json 那一套走 [jsonResults] 的点号路径，所以这一格只负责前两档。
 *
 * [format] 留空 = 跟这条源 `search.format`（绝大多数源每一页都是同一种格式）。
 * **书页/目录页**那一头要显式传 [tocFormat]：有一类源搜索口和书页不是一种东西
 * （iTunes 搜索回 JSON、书页回 RSS），按 HTML 解 RSS 不会报错，会把 `<![CDATA[…]]>` 的壳留在集名里。
 */
internal fun parseDoc(rule: Rule, base: String, body: String, format: String = rule.search?.format ?: "html"): Element =
    if (format == FORMAT_XML) Jsoup.parse(body, base, Parser.xmlParser()) else Jsoup.parse(body, base)

/** 书页/目录页按哪一档解：`toc.format` 优先，没写就跟着 `search.format`。 */
internal fun tocFormat(rule: Rule): String = rule.toc.format.ifBlank { rule.search?.format ?: "html" }

/**
 * 一条 JSON 命中页 → 列表。
 *
 * 取值写法从 CSS 选择器换成**点号路径**，因为 OpenAlex/DOAJ 那两家回的就是 JSON：
 * - `results[]` —— `results` 那个数组，数组里每一项算一条
 * - `bibjson.title` —— 一层一个点
 * - `authorships[].author.display_name` —— 中间遇到 `[]` 就摊开这一层数组继续往下走，取**第一个**取到的
 * - `abstract_inverted_index@invert` —— 见 [invertAbstract]（OpenAlex 只给倒排的那一份摘要）
 * - 键名里可以带点以外的任何字符；不支持数组下标（`a[2]`）—— 三家接口都没有靠下标认字段的地方
 */
internal fun jsonResults(rule: Rule, body: String): List<BookHit> {
    val s = rule.search ?: return emptyList()
    val root = Json.parse(body)
    val rows = jsonRows(root, s.item)
    return rows.mapNotNull { row ->
        val name = jsonPick(row, s.bookName)
        val url = jsonPick(row, s.bookUrl)
        // 和 HTML 那一条路同一口径：书名或链接缺一样就不算一条，宁可少一条也不给界面一条点不动的假结果
        if (name.isEmpty() || url.isEmpty()) return@mapNotNull null
        BookHit(
            sourceId = rule.id,
            sourceName = rule.name,
            bookName = name,
            author = jsonPick(row, s.author),
            bookUrl = absolutize(rule.homeUrl, url),
            category = jsonPick(row, s.category),
            latestChapter = jsonPick(row, s.latestChapter),
            lastUpdateTime = jsonPick(row, s.lastUpdateTime),
            wordCount = jsonPick(row, s.wordCount),
            intro = jsonPick(row, s.intro),
        )
    }
}

/** 路径末段带 `[]` 的就是"这一层是个数组"。 */
private fun jsonRows(root: Any?, path: String): List<Any?> {
    val (stem, wantList) = if (path.endsWith("[]")) path.dropLast(2) to true else path to false
    var v: Any? = root
    for (seg in stem.split('.').filter { it.isNotEmpty() }) {
        v = (v as? Map<*, *>)?.get(seg) ?: return emptyList()
    }
    return when {
        v is List<*> -> if (wantList) v.filterNotNull() else listOf(v)
        v == null -> emptyList()
        else -> listOf(v)
    }
}

/** 一条路径 → 一个字符串。取不到回空串（调用方自己决定这一条要不要，引擎不替它编）。 */
internal fun jsonPick(row: Any?, path: String): String {
    val t = path.trim()
    if (t.isEmpty()) return ""
    val at = t.indexOf('@')
    val spec = (if (at < 0) t else t.substring(0, at)).trim()
    val fn = if (at < 0) null else t.substring(at + 1).trim().ifEmpty { null }
    val leaf = walk(row, spec) ?: return ""
    return when (fn) {
        null -> scalar(leaf)
        "invert" -> invertAbstract(leaf)
        else -> scalar(leaf)
    }.trim()
}

private fun walk(node: Any?, path: String): Any? {
    var cur: Any? = node
    for (raw in path.split('.').filter { it.isNotEmpty() }) {
        val seg = raw
        val wantList = seg.endsWith("[]")
        val key = if (wantList) seg.dropLast(2) else seg
        if (key.isNotEmpty()) {
            cur = (cur as? Map<*, *>)?.get(key) ?: return null
        }
        if (wantList) {
            val list = cur as? List<*> ?: return null
            cur = list.firstOrNull { it != null }
        }
    }
    return cur
}

private fun scalar(v: Any?): String = when (v) {
    null -> ""
    is String -> v
    is Long, is Double, is Boolean -> v.toString()
    // 一家接口把标题写成数组（Crossref 那种 `title: ["..."]`）：第一项就是它，别的都是别名
    is List<*> -> v.firstOrNull { it != null }?.let { scalar(it) } ?: ""
    else -> ""
}

/**
 * OpenAlex 只给 `abstract_inverted_index`（词 → 出现的位置），得还原成那一段话。
 * 这不是"顺手多写一个函数"：那一家是文献这一族里覆盖期刊最全的一条，而它没有明文的摘要字段。
 */
internal fun invertAbstract(v: Any?): String {
    val map = v as? Map<*, *> ?: return ""
    val pairs = buildList {
        for ((word, pos) in map) {
            val w = word as? String ?: continue
            for (p in (pos as? List<*> ?: emptyList<Any>())) {
                val i = (p as? Long ?: (p as? Double)?.toLong())?.toInt() ?: continue
                add(i to w)
            }
        }
    }
    if (pairs.isEmpty()) return ""
    val last = pairs.maxOf { it.first }
    val slots = arrayOfNulls<String>(last + 1)
    for ((i, w) in pairs) slots[i] = w
    // 位置有洞就不假装读得全：把已知的按位置拼回去，末尾说一句"到这里为止"，比交出一串乱序的词有用
    val hole = slots.indexOf(null)
    val text = slots.filterNotNull().joinToString(" ")
    return if (hole < 0) text else text + "（摘要读到这里为止）"
}
