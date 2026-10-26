package com.bookreader.booksource

import java.net.URI

/**
 * 一条书源 = 一个 JSON 文件（导入即"安装"，删掉即"卸载"）。
 *
 * 字段少是有意的：这一族只做"搜到 → 抓完 → 合成一本"，
 * 所以只有四段取值（search / book / toc / chapter）加一段抓取节奏（crawl），
 * 外加一格 `kind` 说明这条源交出来的是**文字、图片、音频还是 PDF**（小说 / 漫画 / 有声 / 文献）。
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
    /** 这条源给的是哪一类东西：[Rule.NOVEL]（默认）/ [Rule.COMIC] / [Rule.AUDIO] / [Rule.PAPER]。 */
    val kind: String,
    /** 搜索那一段。文献站也要这一格，所以它不在 kind 里重复一遍。 */
    val search: SearchRule?,
    /** 书页上那张封面图的 pick 写法（`#fmimg img @src`）。空 = 这条源不取封面，书架上走默认封面。 */
    val cover: String,
    /**
     * 从**书页地址**推封面地址的模板，给列表页没有图的那一族用。
     *
     * 写法里 `{g}` = 书页地址倒数第二段（分组号），`{b}` = 最后一段（书本号，`171_171392` 这种取 `_` 后面那截）。
     * 例：`/files/article/image/{g}/{b}/{b}s.jpg` 配 `http://…/171_171392/` 推出 `…/files/article/image/171/171392/171392s.jpg`。
     * 笔趣阁那一族两家站实测 12/12 推得和书页上真写着的一模一样（2026-10-26，判据 `CoverTemplateTest`）。
     * 空 = 不推。这一格只在列表页那一格取不到图时才起作用，永远不会盖掉站点列表页给的真地址。
     */
    val coverFrom: String,
    /** 书页上"内容简介"那一格的 pick 写法（`#intro`）。空 = 这本书没有简介可取。 */
    val intro: String,
    /** 首页/榜单页上那些分类块。空 = 这条源没有热门可抓，精选那一页就不列它。 */
    val hot: HotRule?,
    /** 目录页。文献源没有"章"这一说，那一条源这一段整个可以缺。 */
    val toc: TocRule,
    val chapter: ChapterRule,
    val crawl: CrawlRule,
) {
    val canSearch: Boolean get() = search != null

    /** 一本漫画 = 一章若干张图，抓回来的是图片地址不是文字。 */
    val isComic: Boolean get() = kind == COMIC

    /** 一篇文献 = 一支可以直接下下来的 PDF，没有目录、没有正文容器。 */
    val isPaper: Boolean get() = kind == PAPER

    /** 一本有声书 = 一章（这一族叫"一集"）一段（少数站切成几段）音频，抓回来的是音频地址不是文字。 */
    val isAudio: Boolean get() = kind == AUDIO

    /** `chapter.audio` 写的是 [ChapterRule.SELF]：目录里那一条地址本身就是音频，**不再打一次集页**。 */
    val selfAudio: Boolean get() = chapter.audio == ChapterRule.SELF

    companion object {
        const val NOVEL = "novel"
        const val COMIC = "comic"
        const val PAPER = "paper"
        const val AUDIO = "audio"

        /** 导入时按这张表验 kind：写错的那一条源要在装上的那一刻就说，不该等到"下载下来一本空的"。 */
        val KINDS = mapOf(NOVEL to "小说", COMIC to "漫画", AUDIO to "有声", PAPER to "文献")
    }
}

/**
 * 热门/榜单：一条源往往把首页切成好几个分类块（`group`），块上写着分类名（`category`），
 * 块里每一本书是 `item`。分两级选是因为分类名挂在块上、不在每一行里 ——
 * 只按行选就把"这本书属于哪个分类"丢了，而"阅读爱好"筛的正是它。
 */
data class HotRule(
    val url: String,
    val group: String,
    val item: String,
    val bookName: String,
    val author: String,
    val category: String,
    val bookUrl: String,
    /** 榜单每一行上那张封面图的 pick 写法（`dt img @src`）。空 = 这一页不给封面，走默认封面。 */
    val cover: String,
)

/**
 * 搜索：url 里的 %s 换成关键词（自动按 URL 编码）；各取值字段见 [pick]。
 *
 * `format` 决定后面那些字段是按什么写法取的：html/xml 是 CSS 选择器（Atom 也是 XML，jsoup 同一套），
 * json 是点号路径（见 [jsonPick]）。文献接口回的是 JSON，那一条源的字段就得写成 `results[]` 那种。
 */
data class SearchRule(
    val url: String,
    val method: String,
    val data: String,
    /** 发搜索之前先种的 cookie（`k=v; k2=v2`）。书海阁少了 waf_sc 就直接回"找不到您要搜索的内容"。 */
    val cookies: String,
    /** 这一页回来的是 html / xml / json。 */
    val format: String,
    val item: String,
    val bookName: String,
    val author: String,
    val category: String,
    val latestChapter: String,
    val lastUpdateTime: String,
    val wordCount: String,
    val intro: String,
    val bookUrl: String,
    /** 搜索结果每一行上那张封面图的 pick 写法（`.cover img @src`）。空 = 这一页不给封面。 */
    val cover: String,
)

/**
 * 目录页：item 选出每一章那条 <a>。有下拉框分页的站 nextPage 指向 option 们，url 是带 %s 的模板。
 *
 * `name` 是章名**不在 a 自己的文字里**的那种站给的写法（瓜子漫画：`<a href=…><span>第 68 卷</span><em>阅读</em></a>`，
 * 整条 a 的文字会读成"第 68 卷阅读"）。留空 = 还用 a 自己的文字。
 *
 * `href` 是同一族道理给**链接那一格**的写法：条目不是 `<a>` 而是"一个容器 + 里面某格挂地址"的源
 * （播客 RSS 的 `<item>` 把音频地址写在 `<enclosure url="…">` 上）。留空 = 还用 item 自己的 `href`。
 *
 * `format` 是**这一页**用什么解析：`html` / `xml`。留空 = 跟 `search.format`。
 * 要这一格的原因是有一类源两页不是一种东西：搜索口回 JSON、书页回 RSS。
 * 而 RSS 按 HTML 解不是报错，是**名字变脏** —— HTML 里 `<title>` 是 RCDATA，
 * `<![CDATA[…]]>` 那两层壳被当成正文字符留着，界面上每一行都是 `<![CDATA[001 第一回…]]>`
 * （判据 `theSameFeedKeepsCdataShellInTitlesWhenParsedAsHtml`）。
 *
 * `reverse` 给"目录 newest-first"的那一类（瓜子漫画两格都是倒着排的）：不翻的话第 1 章是最长的那一话，
 * 进度、书签、"下一章"全都反。翻不翻是站点的事实，不是"看着顺不顺眼"，所以写在源里而不是在界面上给一颗开关。
 */
data class TocRule(
    val url: String,
    val item: String,
    val nextPage: String,
    val name: String,
    val href: String,
    val format: String,
    val reverse: Boolean,
)

/**
 * 正文页：content 选正文容器，filter 里每条正则命中的部分丢掉（站点水印、"本章完"、求收藏）。
 *
 * `nextPage` = "这一章的下一页"那颗链接的选择器（`123.html` → `123_2.html` 那种一章切几页的站）：
 * 正文框里的先试，框里找不到会整页找 —— 书海阁那两颗（`#pager_next`、`#A3`）一个在页顶一个在页尾，
 * 都在 `#content` 外面。留空 = 这一家一章一页（梦书、爱下书都是）。
 * 填了也只跟"同一章的下一页"，见 [nextPageUrl]。
 *
 * `image` 是漫画那一族用的：一章 = 一排图，这一格选出每一张（按页面上的先后顺序，就是页序）。
 * 写法还是 [pick] 那一套，只是取的是**每一个**匹配（见 [pickAll]）—— 懒加载的站把真地址放在
 * `data-original` 而不是 `src`，所以这一格通常得写成 `img.lazy @data-original`。
 *
 * `audio` 是有声那一族用的，和 [image] 同一套写法：一章（一集）= 一段（或几段）音频，
 * 这一格选出每一段的地址。写在 `src` 里的（`audio source @src`）取到的就是补全过的绝对地址；
 * 懒加载写在 `data-src` 的就写 `@data-src`。
 * 写 [ChapterRule.SELF]（`@self`）= **目录里那一条地址本身就是音频文件**，没有第二层集页可点
 * （播客 RSS 那一族：`<enclosure url="….m4a">` 就是那一集的全部）。
 * 属性那一格后面还能再挂一档函数后缀 `@url`：地址埋在 `href="javascript:Play(…,'https://x/a.mp3',…)"`
 * 的参数串里那种站（古文岛），正则抠第一个 http 地址、**不跑 JS**。
 *
 * 有声源**也可以同时写 [content]**：音频和正文在同一页的那一族（还是古文岛），抓一集时那一页已经在手上，
 * 顺手把正文一起抽走，落在同一集目录里的 `text.txt`（界面上"文字跟着读"读的就是它）。
 * 抽不到正文**不影响**这一集算成功 —— 音频才是这本书的主体，缺文字只等于"这一集没得跟读"。
 */
data class ChapterRule(
    val content: String,
    val filter: List<String>,
    val nextPage: String,
    val image: String,
    val audio: String,
) {
    companion object {
        /** `chapter.audio` 的这一档不是选择器，是一句声明：集地址就是文件本身，别再打一次集页。 */
        const val SELF = "@self"
    }
}

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

/** 一本书的"头"：简介全文 + 完整目录（章名, 章链接）。"大纲"就是目录的前若干条章名。 */
data class BookHead(val intro: String, val chapters: List<Pair<String, String>>)

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
    /**
     * 列表页（搜索结果 / 精选榜单）上那张封面图的地址，已经补成绝对地址。
     *
     * 和"下载进书架时抓的那份 `cover.jpg`"是两个来源：这一格只够在列表上摆一张图，
     * 空 = 这一条源在列表页根本不给封面（一大半小说站的搜索页就是这样），界面退回按书名哈希的默认封面。
     */
    val coverUrl: String = "",
)

object RuleLoader {

    private val TOP = setOf("id", "name", "homeUrl", "note", "charset", "kind", "cover", "coverFrom", "intro", "hot", "search", "toc", "chapter", "crawl")
    private val SEARCH = setOf(
        "url", "method", "data", "cookies", "format", "item", "bookName", "author", "category",
        "latestChapter", "lastUpdateTime", "wordCount", "intro", "bookUrl", "cover",
    )
    private val TOC = setOf("url", "item", "nextPage", "name", "href", "format", "reverse")
    private val HOT = setOf("url", "group", "item", "bookName", "author", "category", "bookUrl", "cover")
    private val CHAPTER = setOf("content", "filter", "nextPage", "image", "audio")
    private val CRAWL = setOf("concurrency", "minIntervalMs", "maxIntervalMs", "maxAttempts")

    /** 一頁回來的东西长什么样：决定了用哪一套取值写法。文献接口回 JSON，arXiv 回 Atom。 */
    private val FORMATS = mapOf("html" to "网页", "xml" to "XML/Atom", "json" to "JSON")

    fun load(text: String, where: String = "书源"): Rule {
        val o = Json.obj(Json.parse(text))
        unknown(o, TOP, where)
        for ((k, v) in o) noJs(where, k, v)
        val kind = oneOf(o, "kind", where, Rule.NOVEL, Rule.KINDS)
        val search = o["search"]?.let { s ->
            val m = Json.obj(s)
            unknown(m, SEARCH, "$where.search")
            SearchRule(
                url = str(m, "url", "$where.search", required = true).also { legalUrl(it, "$where.search.url") },
                method = word(m, "method", "$where.search", "GET"),
                data = str(m, "data", "$where.search"),
                cookies = str(m, "cookies", "$where.search"),
                format = oneOf(m, "format", "$where.search", "html", FORMATS),
                item = str(m, "item", "$where.search", required = true),
                bookName = str(m, "bookName", "$where.search", required = true),
                author = str(m, "author", "$where.search", required = kind != Rule.PAPER),
                category = str(m, "category", "$where.search"),
                latestChapter = str(m, "latestChapter", "$where.search"),
                lastUpdateTime = str(m, "lastUpdateTime", "$where.search"),
                wordCount = str(m, "wordCount", "$where.search"),
                intro = str(m, "intro", "$where.search"),
                bookUrl = str(m, "bookUrl", "$where.search", required = true),
                cover = str(m, "cover", "$where.search"),
            )
        }
        // 文献源没有"章"这一说（一篇就是一支 PDF），那两段整个可以不写；
        // 小说/漫画缺了就是在装一条抓不出东西的源，当场说，别等"下载下来一本空的"
        val toc = o["toc"]?.let { Json.obj(it) } ?: emptyMap()
        unknown(toc, TOC, "$where.toc")
        val chapter = o["chapter"]?.let { Json.obj(it) } ?: emptyMap()
        unknown(chapter, CHAPTER, "$where.chapter")
        val crawl = (o["crawl"]?.let { Json.obj(it) } ?: emptyMap())
        unknown(crawl, CRAWL, "$where.crawl")
        return Rule(
            id = str(o, "id", where, required = true),
            name = str(o, "name", where, required = true),
            homeUrl = str(o, "homeUrl", where, required = true),
            note = str(o, "note", where),
            charset = str(o, "charset", where),
            kind = kind,
            cover = str(o, "cover", where),
            coverFrom = str(o, "coverFrom", where),
            intro = str(o, "intro", where),
            hot = o["hot"]?.let { h ->
                val m = Json.obj(h)
                unknown(m, HOT, "$where.hot")
                HotRule(
                    url = str(m, "url", "$where.hot").also { legalUrl(it, "$where.hot.url") },
                    group = str(m, "group", "$where.hot", required = true),
                    item = str(m, "item", "$where.hot", required = true),
                    bookName = str(m, "bookName", "$where.hot", required = true),
                    author = str(m, "author", "$where.hot"),
                    category = str(m, "category", "$where.hot"),
                    bookUrl = str(m, "bookUrl", "$where.hot", required = true),
                    cover = str(m, "cover", "$where.hot"),
                )
            },
            search = search,
            toc = TocRule(
                url = str(toc, "url", "$where.toc").also { legalUrl(it, "$where.toc.url") },
                item = str(toc, "item", "$where.toc", required = kind != Rule.PAPER),
                nextPage = str(toc, "nextPage", "$where.toc"),
                name = str(toc, "name", "$where.toc"),
                href = str(toc, "href", "$where.toc"),
                // 留空 = 跟 search.format；写错的那一档当场报，别让它变成"目录一行都是网址"
                format = oneOf(toc, "format", "$where.toc", "", FORMATS),
                reverse = bool(toc, "reverse", "$where.toc"),
            ),
            chapter = ChapterRule(
                content = str(chapter, "content", "$where.chapter", required = kind == Rule.NOVEL),
                filter = Json.arr(chapter["filter"] ?: emptyList<Any?>()).map {
                    it as? String ?: throw RuleException("$where.chapter.filter 里每一项都得是字符串")
                },
                nextPage = str(chapter, "nextPage", "$where.chapter"),
                image = str(chapter, "image", "$where.chapter"),
                audio = str(chapter, "audio", "$where.chapter"),
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
            // 封面必须写成 `选择器 @src`：不带 @ 的话 pick 会老老实实回那个元素的**文字**，
            // 于是"取封面"变成拿 alt 属性当图片地址去请求 —— 不报错，就是永远存不下封面
            if (it.cover.isNotEmpty() && !it.cover.contains('@')) {
                throw RuleException("$where.cover：得写成「选择器 @src」这种取值写法（例：#fmimg img @src），现在只会取到元素的文字")
            }
            // 列表页那两格（搜索结果 / 精选榜单）同一族错法。
            // **JSON 那一档不要求 @**：它的取值写法本来就是点号路径（iTunes 的 `artworkUrl100`），
            // 只有 html/xml 上"不带 @"才会退化成取回元素的文字
            val searchCover = it.search?.cover ?: ""
            val searchIsJson = it.search?.format == FORMAT_JSON
            val listCovers = listOfNotNull(
                if (searchCover.isNotEmpty() && !searchIsJson) "search.cover" to searchCover else null,
                it.hot?.cover?.takeIf { c -> c.isNotEmpty() }?.let { c -> "hot.cover" to c },
            )
            listCovers.filter { (_, spec) -> !spec.contains('@') }.forEach { (key, _) ->
                throw RuleException("$where.$key：得写成「选择器 @src」这种取值写法（例：dt img @src），现在只会取到元素的文字")
            }
            // 同一族错法在漫画那一格上：图的地址一定挂在属性上（src / data-original / data-src），
            // 不带 @ 取回来的就是那张图的 alt 文字，表现是"每一章都抓到了，一张图也没有"
            if (it.isComic) {
                if (it.chapter.image.isBlank()) {
                    throw RuleException("$where.chapter.image：这是一条漫画源（\"kind\": \"comic\"），得指出每一页那张图在哪一格")
                }
                if (!it.chapter.image.contains('@')) {
                    throw RuleException("$where.chapter.image：得写成「选择器 @属性」，例：#images img @data-original")
                }
            }
            // 同一族错法在有声那一格上：音频地址也一定挂在属性上（src / data-src），
            // 不带 @ 取回来的就是 <audio> 标签里那段"您的浏览器不支持"的兜底文字，
            // 表现是"每一集都抓到了，一段音频也没有"
            if (it.isAudio) {
                if (it.chapter.audio.isBlank()) {
                    throw RuleException("$where.chapter.audio：这是一条有声源（\"kind\": \"audio\"），得指出每一集那段音频在哪一格")
                }
                if (!it.chapter.audio.contains('@')) {
                    throw RuleException("$where.chapter.audio：得写成「选择器 @属性」，例：audio source @src")
                }
            }
            if (it.isPaper && it.search == null) {
                throw RuleException("$where：文献源全靠 search 那一段（arXiv/OpenAlex 那种接口），没写就等于一条搜不动的源")
            }
        }
    }

    /**
     * 从表里取一个词，并且只认 [allowed] 里那几个（键就是要写的值，值是用来拼报错的中文名）。
     * 留空 = 用 [dflt]。大小写不认：写成 `Comic` 的源文件是手抄的，不该因为一个大写字母就装不上。
     */
    private fun oneOf(o: Map<String, Any?>, k: String, where: String, dflt: String, allowed: Map<String, String>): String {
        val v = str(o, k, where).lowercase()
        if (v.isEmpty()) return dflt
        if (!allowed.containsKey(v)) {
            throw RuleException(
                "$where.$k 只认这些：" + allowed.entries.joinToString("、") { "\"" + it.key + "\"（" + it.value + "）" } +
                    "，写的是 \"" + v + "\"",
            )
        }
        return v
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
    /** 一个布尔格。写不成布尔的就当场说，而不是让它静默成 false —— "目录明明倒着排、界面却说第 1 章是最长那一话"没人会怀疑到这一格。 */
    private fun bool(o: Map<String, Any?>, k: String, where: String): Boolean {
        val v = o[k] ?: return false
        return v as? Boolean ?: throw RuleException("$where.$k 要的是 true/false，写的是 \"" + v + "\"")
    }

    private fun longAt(o: Map<String, Any?>, k: String, where: String, dflt: Long, lo: Long, hi: Long): Long {
        val v = o[k] ?: return dflt
        val n = v as? Long ?: (v as? Double)?.toLong() ?: throw RuleException("$where." + k + " 要的是整数")
        if (n < lo || n > hi) throw RuleException("$where." + k + " 要在 " + lo + ".." + hi + " 之间，写的是 " + n)
        return n
    }

    private fun intAt(o: Map<String, Any?>, k: String, where: String, dflt: Int, lo: Int, hi: Int): Int =        longAt(o, k, where, dflt.toLong(), lo.toLong(), hi.toLong()).toInt()
}
