package com.bookreader.booksource

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * 漫画和文献那四条随包源，判据打在**从真站取回的页面**上（`src/test/resources/live/`，2026-10-13 从这台机抓的）。
 *
 * 夹具不进仓库（里面夹着原书正文和站点页面），所以新克隆出来的机器上这些条按跳过算 ——
 * 跳过和通过要一起报，别拿"全绿"当这两族验过了。
 *
 * 这一族最坏的不是报错，是**抓回去一本空漫画**：图片地址在 `data-original` 而不是 `src`、
 * 目录是倒着排的、章名挂在里面那个 span 上 —— 三处都写错的话下载进度照样能走到 100%。
 * 所以每条判据数的都是"真能不能翻到那一页"，不是"选不选得中元素"。
 */
class ComicPaperFixturesTest {

    private fun page(name: String): String? = object {}.javaClass.getResourceAsStream("/live/$name")
        ?.use { it.readBytes().toString(Charsets.UTF_8) }

    private fun rule(id: String): Rule {
        val text = object {}.javaClass.getResourceAsStream("/booksource/$id.json")
            ?.use { it.readBytes().toString(Charsets.UTF_8) }
        return checkNotNull(text) { "随包规则没打进资源里：$id" }.let { RuleLoader.load(it, id) }
    }

    // ---- 图库漫画 ----

    @Test
    fun tukuSearchRowsCarryANameAndABookUrl() {
        val html = assume("tuku-search.html")
        val hits = searchResults(rule("tuku"), html, "https://www.tuku.cc/search?title=%E9%AD%94%E6%B3%95")
        assertTrue("一行都没选到：" + hits.size, hits.size >= 20)
        assertTrue("书名要能读出来：" + hits.first(), hits.first().bookName.isNotEmpty())
        assertTrue("链接要补成绝对地址：" + hits.first().bookUrl, hits.first().bookUrl.startsWith("https://www.tuku.cc/manga-"))
    }

    @Test
    fun tukuBookPageGivesTheWholeChapterListInReadingOrder() {
        val html = assume("tuku-book.html")
        val at = "https://www.tuku.cc/manga-43386/"
        val chapters = SearchEngine(rule("tuku"), MemoryFetcher(mapOf(at to html))).head(at).chapters
        assertEquals("这一本的书页上数得出 435 话", 435, chapters.size)
        // 站点在章名后面挂了一格"(48p)"页数，`toc.name: "span.i-b"` 只指名字那一格。
        // 这条是"目录名不对"那个报告的判据：后缀留在名字里，几百行的目录每一行都拖着一串 p
        assertTrue("第 1 话要排在最前：" + chapters.take(2).map { it.first }, chapters.first().first.startsWith("第1话"))
        assertTrue(
            "章名里不许带着页数的「(48p)」：" + chapters.take(3).map { it.first },
            chapters.none { it.first.matches(Regex(""".*\(\d+p\).*""", RegexOption.IGNORE_CASE)) },
        )
        assertEquals("去重：同一话不能出现两次（站点把「开始阅读」那颗也排成同一类）", chapters.map { it.second }.distinct().size, chapters.size)
    }

    /** 漫画的"第三格"是图片地址能不能抽全 —— 这一条红了就是下回来的每一章都是空的。 */
    @Test
    fun tukuChapterPageGivesEveryPageImageAsAnAbsoluteUrl() {
        val html = assume("tuku-chapter.html")
        val urls = chapterImages(rule("tuku"), html, "https://www.tuku.cc/chapter33548/")
        assertEquals("那一话写着 28P，就该抽出 28 张", 28, urls.size)
        assertTrue("真地址在 data-original，占位图那张 default.jpg 不能算一页：" + urls.first(), urls.first().startsWith("https://image"))
        assertTrue("页序就是页面顺序：" + urls[1], urls[1].contains("/2_"))
    }

    @Test
    fun tukuRankFeedsTheComicTab() {
        val html = assume("tuku-rank.html")
        val hits = hotBooks(rule("tuku"), html, "https://www.tuku.cc/rank/")
        assertTrue("榜单抓不出 50 本的话，精选那一排的「漫画」就是一颗空 tab：" + hits.size, hits.size >= 50)
    }

    // ---- 瓜子漫画 ----

    @Test
    fun guaziSearchRowsCarryANameAndABookUrl() {
        val html = assume("guazi-search.html")
        val hits = searchResults(rule("guazi"), html, "https://www.guazimanhua.com/category.php?keyword=%E6%96%97%E7%BD%97%E5%A4%A7%E9%99%86")
        assertEquals("搜「斗罗大陆」那一片是 7 张卡", 7, hits.size)
        assertTrue("书名不能带卡里的按钮字：" + hits[0].bookName, hits[0].bookName.contains("斗罗"))
    }

    /** 瓜子那一条最险：目录是 newest-first，不翻的话「第 1 章」= 最长那一话，进度和下一章全反。 */
    @Test
    fun guaziChapterListIsReversedIntoReadingOrder() {
        val html = assume("guazi-book.html")
        val at = "https://www.guazimanhua.com/comic.php?id=15293"
        // 走 head() 而不是 tocChapters：去重发生在引擎那一层（站点的手机格和桌面格把同一话各排一遍，
        // 直接数 tocChapters 会把 1068 话数成 2148 条）
        val r = rule("guazi")
        val raw = tocChapters(r.copy(toc = r.toc.copy(reverse = false)), html, at).map { it.second }
        val distinct = raw.distinct()
        assertEquals("两格重复的目录按地址去重后是 1068 话", 1068, distinct.size)
        val chapters = SearchEngine(r, MemoryFetcher(mapOf(at to html))).head(at).chapters
        assertEquals(1068, chapters.size)
        // 站点把最长那一话（第1162话）排在页面第一行，所以"翻对了"的硬证据是它落到最后一行。
        // 不整表比 id 升序：那一家还有「全彩版68卷_1」这种再版话，页面里几条列表彼此交错，
        // 按 id 排会把这些再版插到别的章中间去 —— 我们要的是"页面倒着、我们正着"，不是另一种顺序
        assertEquals("页面第一行那一话该被翻到最后：" + chapters.last().second, distinct.first(), chapters.last().second)
        assertTrue("第一话那一端不能再是页面顶端那一话", chapters.first().second != distinct.first())
        assertTrue("章名里不该粘着「阅读」那颗按钮的字：" + chapters.first().first, !chapters.first().first.contains("阅读"))
    }

    @Test
    fun guaziChapterPageGivesEveryPageImage() {
        val html = assume("guazi-chapter.html")
        val urls = chapterImages(rule("guazi"), html, "https://www.guazimanhua.com/chapter.php?id=2161439")
        assertEquals("那一话是 105 张", 105, urls.size)
        assertTrue("地址在 src 上、不带签名：" + urls.first(), urls.first().startsWith("https://img.guazicdn.com/"))
    }

    // ---- 文献那两条 ----

    /** arXiv 回的是 Atom：整页要用 XML 解析器解，用 HTML 那一个会把 `<entry>` 吞成别的东西。 */
    @Test
    fun arxivEntriesGiveTitleAuthorsAndThePdfLink() {
        val xml = assume("arxiv-search.xml")
        val hits = searchResults(rule("arxiv"), xml, "https://export.arxiv.org/api/query?search_query=all:electron")
        assertEquals("夹具里那一片是 2 条", 2, hits.size)
        assertTrue("标题该是那句英文原标题：" + hits[0].bookName, hits[0].bookName.length > 20)
        assertTrue("第一作者：" + hits[0].author, hits[0].author.isNotEmpty())
        assertTrue("下的该是那支 PDF 而不是 abs 页：" + hits[0].bookUrl, hits[0].bookUrl.endsWith(".pdf") || hits[0].bookUrl.contains("/pdf/"))
        assertTrue("摘要：" + hits[0].intro.take(40), hits[0].intro.length > 80)
    }

    @Test
    fun openalexRowsWalkTheJsonPathAndRebuildTheAbstract() {
        val json = assume("openalex-search.json")
        val hits = jsonResults(rule("openalex"), json)
        assertTrue("抽不出一条的话这颗 tab 就是死的：" + hits.size, hits.isNotEmpty())
        val h = hits.first()
        assertTrue("标题：" + h.bookName, h.bookName.isNotEmpty())
        assertTrue("PDF 地址：" + h.bookUrl, h.bookUrl.startsWith("https://"))
        assertTrue("倒排索引要还原成一句人话：" + h.intro.take(60), h.intro.split(' ').size > 8)
        assertTrue("一条命中不该有两个 PDF 地址相同（那是同一篇被排了两遍）", hits.map { it.bookUrl }.distinct().size == hits.size)
    }

    /** 缺夹具的那台机（新克隆）按跳过算，别让它红 —— 这条判据在仓库里是"这台机验过"的存证。 */
    private fun assume(name: String): String {
        val body = page(name)
        assumeTrue("夹具没在这台机上（live/ 不进仓库）：$name", body != null)
        return body!!
    }
}
