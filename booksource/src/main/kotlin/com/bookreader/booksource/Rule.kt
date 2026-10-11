package com.bookreader.booksource

import java.net.URI

/**
 * 一条书源 = 一个 JSON 文件（导入即"安装"，删掉即"卸载"）。
 *
 * 字段少是有意的：这一族只做"搜到 → 抓完 → 合成一本 txt"，
 * 所以只有四段取值（search / book / toc / chapter）加一段抓取节奏（crawl）。
 * 明确不支持的：需要跑 JS 才能算参数或解密的源（手机端不内嵌 V8）、CF 盾的站。
 * 这两类在加载时就拒绝并说清原因，不让它变成"按了没反应"。
 */
data class Rule(
    val id: String,
    val name: String,
    val homeUrl: String,
    val note: String,
    /** 站点响应体的字符集；空 = 先看 HTTP 头，再看 meta 标签，最后按 UTF-8。老站一大半是 GBK。 */
    val charset: String,
    val search: SearchRule?,
    val toc: TocRule,
    val chapter: ChapterRule,
    val crawl: CrawlRule,
) {
    val canSearch: Boolean get() = search != null
}

/** 搜索：url 里的 %s 换成关键词（自动按 URL 编码）；各取值字段见 [pick]。 */
data class SearchRule(
    val url: String,
    val method: String,
    val data: String,
    /** 发搜索之前先种的 cookie（`k=v; k2=v2`）。书海阁少了 waf_sc 就直接回"找不到您要搜索的内容"。 */
    val cookies: String,
    val item: String,
    val bookName: String,
    val author: String,
    val category: String,
    val latestChapter: String,
    val lastUpdateTime: String,
    val wordCount: String,
    val intro: String,
    val bookUrl: String,
)

/** 目录页：item 选出每一章那条 <a>。有下拉框分页的站 nextPage 指向 option 们，url 是带 %s 的模板。 */
data class TocRule(val url: String, val item: String, val nextPage: String)

/** 正文页：content 选正文容器，filter 里每条正则命中的部分丢掉（站点水印、"本章完"、求收藏）。 */
data class ChapterRule(val content: String, val filter: List<String>)

/**
 * 抓取节奏。区间和重试次数不是"调快点省时间"的参数：
 * 有真实站点会因为下太快而返回空正文（不报错，就是空的），所以这两条下限是判据，见 RuleLoaderTest。
 */
data class CrawlRule(
    val concurrency: Int,
    val minIntervalMs: Long,
    val maxIntervalMs: Long,
    val maxAttempts: Int,
)

/** 一条搜索命中。sourceId/sourceName 带着走：聚合搜索里同一本书会在好几个源里出现。 */
data class BookHit(
    val sourceId: String,
    val sourceName: String,
    val bookName: String,
    val author: String,
    val bookUrl: String,
    val category: String = "",
    val latestChapter: String = "",
    val lastUpdateTime: String = "",
    val wordCount: String = "",
    val intro: String = "",
)

object RuleLoader {

    private val TOP = setOf("id", "name", "homeUrl", "note", "charset", "search", "toc", "chapter", "crawl")
    private val SEARCH = setOf(
        "url", "method", "data", "cookies", "item", "bookName", "author", "category",
        "latestChapter", "lastUpdateTime", "wordCount", "intro", "bookUrl",
    )
    private val TOC = setOf("url", "item", "nextPage")
    private val CHAPTER = setOf("content", "filter")
    private val CRAWL = setOf("concurrency", "minIntervalMs", "maxIntervalMs", "maxAttempts")

    fun load(text: String, where: String = "书源"): Rule {
        val o = Json.obj(Json.parse(text))
        unknown(o, TOP, where)
        for ((k, v) in o) noJs(where, k, v)
        val search = o["search"]?.let { s ->
            val m = Json.obj(s)
            unknown(m, SEARCH, "$where.search")
            SearchRule(
                url = str(m, "url", "$where.search", required = true).also { legalUrl(it, "$where.search.url") },
                method = word(m, "method", "$where.search", "GET"),
                data = str(m, "data", "$where.search"),
                cookies = str(m, "cookies", "$where.search"),
                item = str(m, "item", "$where.search", required = true),
                bookName = str(m, "bookName", "$where.search", required = true),
                author = str(m, "author", "$where.search", required = true),
                category = str(m, "category", "$where.search"),
                latestChapter = str(m, "latestChapter", "$where.search"),
                lastUpdateTime = str(m, "lastUpdateTime", "$where.search"),
                wordCount = str(m, "wordCount", "$where.search"),
                intro = str(m, "intro", "$where.search"),
                bookUrl = str(m, "bookUrl", "$where.search", required = true),
            )
        }
        val toc = Json.obj(o["toc"] ?: throw RuleException("$where：缺 \"toc\" 那一段"))
        unknown(toc, TOC, "$where.toc")
        val chapter = Json.obj(o["chapter"] ?: throw RuleException("$where：缺 \"chapter\" 那一段"))
        unknown(chapter, CHAPTER, "$where.chapter")
        val crawl = (o["crawl"]?.let { Json.obj(it) } ?: emptyMap())
        unknown(crawl, CRAWL, "$where.crawl")
        return Rule(
            id = str(o, "id", where, required = true),
            name = str(o, "name", where, required = true),
            homeUrl = str(o, "homeUrl", where, required = true),
            note = str(o, "note", where),
            charset = str(o, "charset", where),
            search = search,
            toc = TocRule(
                url = str(toc, "url", "$where.toc").also { legalUrl(it, "$where.toc.url") },
                item = str(toc, "item", "$where.toc", required = true),
                nextPage = str(toc, "nextPage", "$where.toc"),
            ),
            chapter = ChapterRule(
                content = str(chapter, "content", "$where.chapter", required = true),
                filter = Json.arr(chapter["filter"] ?: emptyList<Any?>()).map {
                    it as? String ?: throw RuleException("$where.chapter.filter 里每一项都得是字符串")
                },
            ),
            crawl = CrawlRule(
                concurrency = intAt(crawl, "concurrency", "$where.crawl", 4, 1, 16),
                minIntervalMs = longAt(crawl, "minIntervalMs", "$where.crawl", 300L, 0L, 60_000L),
                maxIntervalMs = longAt(crawl, "maxIntervalMs", "$where.crawl", 1200L, 0L, 120_000L),
                maxAttempts = intAt(crawl, "maxAttempts", "$where.crawl", 3, 1, 10),
            ),
        ).also {
            if (it.crawl.maxIntervalMs < it.crawl.minIntervalMs) {
                throw RuleException("$where.crawl：maxIntervalMs 比 minIntervalMs 还小，这样取到的区间是空的")
            }
        }
    }

        /**
     * 地址模板当场验一遍（`%s`/`%n` 先填上占位值）。
     * 放到抓取时才报的话，表现是"这本书缺了 40 章"，没人会想到是源文件里少写一个百分号。
     */
    private fun legalUrl(template: String, where: String) {
        if (template.isBlank()) return
        val probe = template.replace("%s", "x").replace("%n", "1")
        runCatching { URI(probe) }.exceptionOrNull()?.let {
            throw RuleException(
                "$where 不是一个合法地址（${it.message}）：$probe —— 空格、竖线、花括号不能有，中文要写成百分号编码",
            )
        }
    }

    private fun unknown(o: Map<String, Any?>, known: Set<String>, where: String) {
        val extra = o.keys.filter { it !in known }
        if (extra.isNotEmpty()) {
            throw RuleException(
                "$where 里有不认识的键：" + extra.joinToString("、") { "\"" + it + "\"" } +
                    "。写错字段名的话这一项会一直是空的，所以这里不当没看见",
            )
        }
    }

    /** 带 @js: 的规则要执行 JS 才出得来结果，这一族不跑 JS —— 现在就拒，别等搜出空白页再猜。 */
    private fun noJs(where: String, key: String, v: Any?) {
        when (v) {
            is String -> if (v.contains("@js:")) {
                throw RuleException("$where.$key 用了 @js:：这一条要跑 JavaScript，本应用不内嵌 JS 引擎，换一条源")
            }
            is Map<*, *> -> for ((k, x) in v) noJs("$where.$key", "$k", x)
            is List<*> -> v.forEachIndexed { i, x -> noJs("$where.$key[$i]", "item", x) }
        }
    }

    private fun str(o: Map<String, Any?>, k: String, where: String, required: Boolean = false): String {
        val v = o[k]
        if (v == null) {
            if (required) throw RuleException("$where 缺了必填的 \"" + k + "\"")
            return ""
        }
        return (v as? String ?: throw RuleException("$where." + k + " 要的是字符串")).trim()
    }

    private fun word(o: Map<String, Any?>, k: String, where: String, dflt: String): String {
        val v = str(o, k, where).uppercase()
        if (v.isEmpty()) return dflt
        if (v != "GET" && v != "POST") throw RuleException("$where." + k + " 只支持 GET/POST，写的是 \"" + v + "\"")
        return v
    }

    /**
     * 整数取值 + 上下限。两个 helper 故意不叫同一个名字：`int(o,k,w,4,1,16)` 这种实参是字面量，
     * Kotlin 允许整数字面量当 Long 用，重载就会撞成"实际是 Int、要 Long"那种看不懂的报错。
     */
    private fun longAt(o: Map<String, Any?>, k: String, where: String, dflt: Long, lo: Long, hi: Long): Long {
        val v = o[k] ?: return dflt
        val n = v as? Long ?: (v as? Double)?.toLong() ?: throw RuleException("$where." + k + " 要的是整数")
        if (n < lo || n > hi) throw RuleException("$where." + k + " 要在 " + lo + ".." + hi + " 之间，写的是 " + n)
        return n
    }

    private fun intAt(o: Map<String, Any?>, k: String, where: String, dflt: Int, lo: Int, hi: Int): Int =
        longAt(o, k, where, dflt.toLong(), lo.toLong(), hi.toLong()).toInt()
}
