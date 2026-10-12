package com.bookreader.booksource

import org.junit.Assert.assertEquals
import org.junit.Assume.assumeTrue
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 这些判据跑的是**从真站取回来的页面**，不是照别人的规则抄一遍就完事。
 *
 * 夹具就是当时那一页的原样 HTML（`src/test/resources/live/`，2026-10-11 从这台机抓的）。
 * 所以每条判据同时钉住两件事：规则对这些 DOM 取得对，以及随包那条源文件解析得动。
 * 站点改版之后这条会红 —— 那是它应该做的工。
 *
 * 燃文只剩测试资源（`src/test/resources/booksource/ranwen.json`），不再随包：见下面那条 base64 的判据。
 */
class RealSiteFixturesTest {

    private fun res(path: String): ByteArray =
        checkNotNull(javaClass.getResourceAsStream(path)) { "缺资源 $path" }.use { it.readBytes() }

    private fun rule(id: String): Rule = RuleLoader.load(String(res("/booksource/$id.json"), Charsets.UTF_8), id)

    private fun live(name: String, base: String): Pair<String, String> {
        // `live/` 那批真站页面不进仓库（里面夹着原书正文），所以没有夹具时这一条按跳过算，不算红
        assumeTrue("缺夹具 /live/$name —— 这一条只在抓过那一页的机器上跑", javaClass.getResource("/live/$name") != null)
        return String(res("/live/$name"), Charsets.UTF_8) to base
    }

    @Test
    fun bundledAndTestOnlyRulesParseAndDeclareWhatTheEngineNeeds() {
        val a = rule("mengshu")
        val b = rule("ranwen")
        val c = rule("aixiashu")
        println("[源] ${a.name} / ${b.name} / ${c.name} 并发=${a.crawl.concurrency} 间隔=${a.crawl.minIntervalMs}..${a.crawl.maxIntervalMs}")
        assertEquals("mengshu", a.id)
        assertTrue("都得能搜", a.canSearch && b.canSearch && c.canSearch)
        assertEquals("POST", a.search!!.method)
        assertTrue("目录选择器不能空", a.toc.item.isNotEmpty() && b.toc.item.isNotEmpty() && c.toc.item.isNotEmpty())
        assertTrue("正文容器不能空", a.chapter.content.isNotEmpty() && b.chapter.content.isNotEmpty() && c.chapter.content.isNotEmpty())
    }

    @Test
    fun mengshuSearchPageFromTheLiveSiteYieldsTheRowsWeSaw() {
        val (html, base) = live("mengshu-search.html", "http://www.mcxs.la/search.html")
        val hits = searchResults(rule("mengshu"), html, base)
        println("[梦书] 行数=${hits.size} 第一条=${hits.firstOrNull()}")
        assertEquals("那一页上真有 100 行结果", 100, hits.size)
        val h = hits[0]
        assertEquals("剑来", h.bookName)
        assertEquals("烽火戏诸侯", h.author)
        assertEquals("第83章 今日无事", h.latestChapter)
        assertEquals("10-07", h.lastUpdateTime)
        assertEquals("http://www.mcxs.la/1_1639/", h.bookUrl)
        assertTrue("表头那一行没有链接，不该算一条", hits.none { it.bookName == "小说名称" })
    }

    @Test
    fun ranwenSearchPageYieldsRowsAndTheAuthorIsPlainTextInItsCell() {
        val (html, base) = live("ranwen-search.html", "https://www.ranwen8.cc/search.html")
        val hits = searchResults(rule("ranwen"), html, base)
        println("[燃文] 行数=${hits.size} 第一条=${hits.firstOrNull()}")
        assertEquals(43, hits.size)
        val h = hits[0]
        assertEquals("剑来", h.bookName)
        assertEquals("天蚕土豆", h.author)
        assertEquals("第349章 埋河封正，武庙借刀，白猿背剑", h.latestChapter)
        assertEquals("02-25", h.lastUpdateTime)
        assertEquals("https://www.ranwen8.cc/book/102322/", h.bookUrl)
        assertTrue("表头那行是 th，不该被算进来", hits.none { it.bookName == "文章名称" })
    }

    @Test
    fun ranwenBookPageYieldsAnOrderedCatalogue() {
        val (html, base) = live("ranwen-book.html", "https://www.ranwen8.cc/book/102322/")
        val chapters = tocChapters(rule("ranwen"), html, base)
        println("[燃文目录] 章数=${chapters.size} 头三条=${chapters.take(3).map { it.first }}")
        assertEquals("那一页列了 100 章", 100, chapters.size)
        assertEquals("第一卷 笼中雀 第一章 惊蛰", chapters[0].first)
        assertEquals("https://www.ranwen8.cc/book/102322/50171982.html", chapters[0].second)
        assertTrue("标题行要能被阅读引擎认成章节", heading(0, chapters[0].first).let { com.bookreader.core.ingest.ChapterPattern.matches(it) })
    }

    /**
     * 梦书的书页 `#list > dl` 里有两个 `<dt>`：第一个后面挂的是"最新章节"那几章（倒序），
     * 第二个后面才是正文顺序的全目录。选错段的表现不是报错，而是**整本书章节顺序是乱的**，
     * 而且字数对得上、没人会立刻发现 —— 所以这条按真页面钉住第一段是哪一章。
     *
     * 1308 章、第一条是「新书感言」：这是那个站自己列的顺序（感言排在第一章前面），不是我裁出来的。
     */
    @Test
    fun mengshuCatalogSkipsTheLatestChaptersBlock() {
        val (html, base) = live("mengshu-book.html", "http://www.mcxs.la/1_1639/")
        val chapters = tocChapters(rule("mengshu"), html, base)
        println("[梦书目录] 章数=${chapters.size} 头三条=${chapters.take(3).map { it.first }}")
        assertEquals("第一个必须是正文块开头，不是那几章「最新章节」", "新书感言", chapters[0].first)
        assertEquals("第一章 惊蛰", chapters[1].first)
        assertEquals("整本 1308 章要都在", 1308, chapters.size)
    }

    /**
     * 正文这一格只有拿**真正文页**判过才算数：前面几条判的是搜索页和目录页，
     * 那两格都对、正文抽出来是垃圾的源，在下载进度上一路走到 100% 也不会红一次。
     *
     * 2026-10-11 那一页 54191 字节，`#content` 里 206 段、9299 字。站点往正文容器里塞了两处广告
     * （开头「天才一秒记住本站地址」+ 网址，结尾「章节错误,点此报送…」），而随包规则原来那三条 filter
     * 在这一页命中 0 字 —— 广告会跟着进书，听书还会把它念出来。现在这两条是按这页真字数掉的广告写的。
     */
    @Test
    fun mengshuChapterPageYieldsTheBodyWithoutTheSiteAds() {
        val (html, base) = live("mengshu-chapter.html", "http://www.mcxs.la/1_1639/65853108.html")
        val text = chapterText(rule("mengshu"), html, base)
        val lines = text.split('\n')
        // 段间现在是一个空行，按 '\n' 数会数出两倍，所以只数非空的那几行
        println("[梦书正文] 段数=${lines.count { it.isNotEmpty() }} 字数=${text.count { it != '\n' }}")
        assertEquals(
            "第一段是正文那句",
            "一到落魄山霁色峰，陈平安就醒了，身在祖师堂白玉广场，宁姚将他扶好，一起凭栏而立。",
            lines.first()
        )
        assertEquals("末段要收在正文那句，不是广告", "大概对于天地而言，我辈皆作人间少年游。", lines.last())
        for (ad in listOf("天才一秒记住", "无广告", "mcxs.la", "章节错误", "点此报送", "梦书中文"))
            assertTrue("广告不该留下：$ad", !text.contains(ad))
        assertTrue("整章 9000 字这个量级不能掉", text.count { it != '\n' } > 9000)
        assertTrue("&nbsp;（U+00A0）要换成能 trim 掉的空格", text.none { it.code == 160 })
        assertTrue("每段都不该带段首空白", lines.all { it == it.trim() })
    }

    /**
     * 燃文为什么从随包默认源里摘掉：这一页 `#content` 抽出来 726 字，全是「本站推荐」那串书名和
     * 「上一章 章节目录 下一章」，正文一个字都没有 —— 它的正文写成 document.writeln(qsbs.bb('BASE64'))，
     * 靠浏览器执行脚本才出得来，而这一层按设计只跑「CSS 选择器 + 正则」，剥掉脚本是必须的
     * （不然脚本源码本身会被当成正文混进书里）。
     *
     * 最坏的地方是它不报错：搜得到、目录 100 章、下载进度走到 100%，下完是一本每章 700 字广告的书。
     */
    @Test
    fun ranwenBodyIsBase64InsideScriptsSoTheRuleCannotReachIt() {
        val (html, base) = live("ranwen-chapter.html", "https://www.ranwen8.cc/book/102322/50171982.html")
        val text = chapterText(rule("ranwen"), html, base)
        val scripts = Regex("<script").findAll(html).count()
        println("[燃文正文] 段数=${text.split('\n').count { it.isNotEmpty() }} 字数=${text.count { it != '\n' }} 脚本块=$scripts")
        assertTrue("抽到的只有广告，不该过一千字", text.count { it != '\n' } < 1000)
        assertTrue("第一章讲泥瓶巷，一个字都没抽到", !text.contains("泥瓶巷"))
        assertTrue("这页确实是靠脚本拼正文的", html.contains("document.writeln(qsbs.bb("))
    }

    /**
     * 爱下书小说网（他 2026-10-11 点名要加的那条）。搜索口不在首页上 —— 首页**一个 `<form>` 都没有**，
     * 口是 `/modules/article/search.php`（POST `searchkey`），和 香书/笔尖 那一族同一个模板。
     * 表头那一行是 6 个 `<th>`、没有 `<a>`，所以按"没有书名或链接就不算一条"自己就被丢掉了。
     */
    @Test
    fun aixiashuSearchPageYieldsRowsAndTheWordCountCell() {
        val (html, base) = live("aixiashu-search.html", "http://www.aixiashu.la/modules/article/search.php")
        val hits = searchResults(rule("aixiashu"), html, base)
        println("[爱下书] 行数=${hits.size} 第一条=${hits.firstOrNull()}")
        assertEquals("50 行结果 + 1 行表头，表头不该算进来", 50, hits.size)
        val h = hits[0]
        assertEquals("剑来", h.bookName)
        assertEquals("烽火戏诸侯", h.author)
        assertEquals("第83章 今日无事", h.latestChapter)
        assertEquals("24179789", h.wordCount)
        assertEquals("10-07", h.lastUpdateTime)
        assertEquals("连载中", h.category)
        assertEquals("http://www.aixiashu.la/2/2196/", h.bookUrl)
    }

    /**
     * 目录这一格在这个站上有两处要认：
     *
     * ① `#list` 里只有**一个 `<dl>`、两个 `<dt>`**。第一个 dt（「《剑来》最新章节（提示：已启用缓存技术…」」）
     *    下面挂 9 条**倒序**链接，第二个 dt（「《剑来》正文」）之后才是正序。选错段的表现是整本书开头一串是倒的。
     * ② 正序那 1307 条**不是**一路递增的：到「第一千一百八十七章」之后还接着往下走
     *    （第一千一百八十八章…），再往后 83 条换成「第1章 少年游 … 第83章 今日无事」——
     *    是《剑来》最新那一卷在卷内重新编号，梦书那 1308 章是同一个形状。所以尾巴不是垃圾，别裁。
     */
    @Test
    fun aixiashuCatalogTakesTheOrderedBlockAndKeepsTheRenumberedTail() {
        val (html, base) = live("aixiashu-book.html", "http://www.aixiashu.la/2/2196/")
        val chapters = tocChapters(rule("aixiashu"), html, base)
        println("[爱下书目录] 章数=${chapters.size} 头两条=${chapters.take(2).map { it.first }} " +
            "1192/1193=${chapters[1191].first} / ${chapters[1192].first} 末条=${chapters.last().first}")
        assertEquals(1307, chapters.size)
        assertEquals("新书感言", chapters[0].first)
        assertEquals("第一章 惊蛰", chapters[1].first)
        assertEquals("http://www.aixiashu.la/2/2196/1660742.html", chapters[1].second)
        assertEquals("第一千一百八十七章 接剑于十四", chapters[1191].first)
        assertEquals("第一千一百八十八章 长生事太平人", chapters[1192].first)
        assertEquals("第83章 今日无事", chapters.last().first)
        assertTrue(
            "倒序那 9 条不能混进来：第83章 只该出现一次（尾巴上卷内重编号的那条）",
            chapters.count { it.first == "第83章 今日无事" } == 1
        )
    }

    /** 正文这一格：`#content` 里是 `<br>` 分段（这一页 102 个 `<br>`、0 个 `<p>`），两头没有站点广告。 */
    @Test
    fun aixiashuChapterPageYieldsTheRealOpeningParagraphs() {
        val (html, base) = live("aixiashu-chapter.html", "http://www.aixiashu.la/2/2196/1660742.html")
        val text = chapterText(rule("aixiashu"), html, base)
        // 段与段之间现在交出一个空行（阅读器那一格空隙照原文判），所以按 "\n\n" 数段落
        val paras = text.split("\n\n")
        println("[爱下书正文] 段数=${paras.size} 字数=${text.count { it != '\n' }}")
        assertEquals("二月二，龙抬头。", paras.first())
        assertTrue("开头那段点名泥瓶巷", paras[1].startsWith("暮色里，小镇名叫泥瓶巷的僻静地方"))
        assertEquals("ps3:好久不见，剑来！", paras.last())
        assertTrue("整章近 3000 字这个量级不能掉", text.count { it != '\n' } > 2900)
        assertTrue("&nbsp; 要换成能 trim 掉的空格", text.none { it.code == 160 })
        assertTrue("每段都不该带段首空白", paras.all { it == it.trim() })
        assertTrue("段间只有一条空行（原文的空行不能被加成两道）", !text.contains("\n\n\n"))
    }

    /**
     * 这条记的是**站点自己的数据错**，不是规则错：`/2/2196/64638193.html` 的 `<h1>` 和面包屑都写着
     * 「爱下书小说网 > 剑来 > 第82章 东道主」，抽出来的 1865 字正文却是另一本现代题材的书
     * （「温沐遥和陈拓呼哧呼哧喘着气」「大石」「孙安琪」），一个剑来的人物都没有。
     * 同一块里往前数 第1188 章（/54253407.html）抽出来 8455 字、有「宁姚」9 次 = 对的。
     *
     * 也就是说按这条源下载《剑来》，最后那 83 条目录会抓回别的书。DOM 里没有任何线索可判
     * （站点把假的那页也标成了剑来），所以这一格修不了，只能钉在这里当明账：
     * 它随包进界面时，「这本书最后 83 章是串页的」就是 Known，而不是等下完才发现。
     */
    @Test
    fun bothShippedSourcesReadTheIntroOffTheirRealBookPage() {
        val m = live("mengshu-book.html", "http://www.mcxs.la/1_1639/")
        val a = live("aixiashu-book.html", "http://www.aixiashu.la/2/2196/")
        val im = introOf(rule("mengshu"), m.first, m.second)
        val ia = introOf(rule("aixiashu"), a.first, a.second)
        println("[简介] 梦书=${im.length} 字  爱下书=${ia.length} 字")
        // 两家这条是同一本书（剑来）的同一句简介，逐字对得上才算这条规则真的取到了那一格
        assertTrue("梦书那页的 #intro 要取到正文，实际：$im", im.startsWith("大千世界，无奇不有"))
        assertTrue("爱下书同一格（它在 #intro 里多套了一层 p），实际：$ia", ia.startsWith("大千世界，无奇不有"))
        // 只差一个空格：梦书那格句末带 &nbsp;，爱下书不带。抹掉空白再比，比的是"取到的是同一段话"
        assertEquals("两家这条简介抹掉空白后该逐字相同", im.filterNot { it.isWhitespace() }, ia.filterNot { it.isWhitespace() })
        assertTrue("简介里不该带 HTML 标签", !im.contains('<') && !ia.contains('<'))
    }

    @Test
    fun hotBlocksOnBothHomepagesYieldBooksWithTheirCategory() {
        val m = live("mengshu-home.html", "http://www.mcxs.la/")
        val a = live("aixiashu-home.html", "http://www.aixiashu.la/")
        val hm = hotBooks(rule("mengshu"), m.first, m.second)
        val ha = hotBooks(rule("aixiashu"), a.first, a.second)
        println("[热门] 梦书=${hm.size} 本（分类=${hm.map { it.category }.distinct().take(4)}）  爱下书=${ha.size} 本")
        assertTrue("梦书首页六个分类块不该只抓出这么几条：${hm.size}", hm.size >= 30)
        assertTrue("爱下书首页四个分类块不该只抓出这么几条：${ha.size}", ha.size >= 20)
        assertTrue("每一条都得带分类名（阅读爱好筛的就是它）", hm.all { it.category.isNotEmpty() } && ha.all { it.category.isNotEmpty() })
        assertTrue("每一条都得带绝对书链接", hm.all { it.bookUrl.startsWith("http") } && ha.all { it.bookUrl.startsWith("http") })
        // 两家把作者排在不同地方：梦书是第二个 <a>，爱下书是行里 a 后面那截裸字（`@text` + 去行首斜杠）
        val jianlai = ha.firstOrNull { it.bookName == "剑来" }
        assertTrue("爱下书榜单里《剑来》的作者要从「/烽火戏诸侯」那截裸字里取出来，实际取到：" + jianlai?.author, jianlai?.author == "烽火戏诸侯")
        val first = hm.first()
        assertTrue("梦书那行的作者取的是第二个 a，不是整行文字：" + first.author, first.author.isNotEmpty() && !first.author.contains(first.bookName))
    }

    @Test
    fun bothShippedSourcesFindTheCoverOnTheirRealBookPage() {
        val m = live("mengshu-book.html", "http://www.mcxs.la/1_1639/")
        val a = live("aixiashu-book.html", "http://www.aixiashu.la/2/2196/")
        val cm = coverUrl(rule("mengshu"), m.first, m.second)
        val ca = coverUrl(rule("aixiashu"), a.first, a.second)
        println("[封面] 梦书=$cm  爱下书=$ca")
        assertEquals("http://www.mcxs.la/files/article/image/1/1639/1639s.jpg", cm)
        assertEquals("http://www.aixiashu.la/files/article/image/2/2196/2196s.jpg", ca)
    }

    @Test
    fun aSourceThatDeclaresNoCoverYieldsAnEmptyUrlNotACrash() {
        val bare = RuleLoader.load(
            String(res("/booksource/ranwen.json"), Charsets.UTF_8).replace("\"id\": \"ranwen\"", "\"id\": \"ranwen\", \"cover\": \"\""),
            "ranwen-无封面",
        )
        assertEquals("没写 cover 就是空串", "", bare.cover)
        val m = live("mengshu-book.html", "http://www.mcxs.la/1_1639/")
        assertEquals("规则没写就不去页上乱找", "", coverUrl(bare, m.first, m.second))
    }

    @Test
    fun aixiashuLastVolumeChaptersPointAtAnotherBooksTextAndNothingInDomSaysSo() {
        val (html, base) = live("aixiashu-chapter-scrambled.html", "http://www.aixiashu.la/2/2196/64638193.html")
        val text = chapterText(rule("aixiashu"), html, base)
        println("[爱下书串页] 段数=${text.split('\n').size} 字数=${text.count { it != '\n' }} 首行=${text.lineSequence().first()}")
        assertTrue("页面自己声称是剑来", html.contains("剑来") && html.contains("第82章 东道主"))
        assertTrue("抽出来的是另一本书：三个主角名一个都没有", listOf("陈平安", "宁姚", "郑居中").all { !text.contains(it) })
        assertTrue("另一本现代题材的词却在", listOf("温沐遥", "孙安琪").any { text.contains(it) })
    }
}
