package com.bookreader.booksource

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 随包那几条源（`src/main/resources/booksource` 下那几份 json）在**装上之前**先验一遍。
 *
 * 别在块注释里写 `booksource/` 加通配符那串字面：Kotlin 的块注释会嵌套，那个斜杠星会把这一格点开到文件末。
 *
 * 为什么值得单独一条：管理页只在解析失败时贴一句原因，而"包里就带着一条装不上的源"的表现是
 * 每个人手机上都多一条灰着的源 —— 那不是我改坏一处他当场能看到，是发布出去才坏。
 * 文件名和文件里的 `id` 也必须对上：种一次记的是 `seeded.<文件名>`，而缓存目录/摘掉名单按 `id` 走，
 * 两边不一致的话"删了不自愈"和「恢复随包」会各认一半。
 */
class ShippedRulesTest {

    private fun text(name: String) = fixtureText("/booksource/$name.json")

    @Test
    fun everyShippedRuleLoadsAndItsIdMatchesItsFileName() {
        val shipped = listOf("mengshu", "aixiashu", "shuhaige", "tuku", "guazi", "arxiv", "openalex", "podcast-rss", "gushiwen-audio", "mh199")
        val all = shipped.map { RuleLoader.load(text(it), it) }
        println("[随包] " + all.map { it.id + "=" + it.kind })
        assertEquals("文件名和 id 必须一一对上", shipped, all.map { it.id })
        // 古文岛那一条**故意没有 search**（站内搜索 302 到登录页），它走「精选」的 hot 入口，
        // 所以这一条判据不能写成"每一条都得能搜"——那会把一个真源判成坏的
        assertTrue("除古文岛外每条都得能搜", all.filter { it.id != "gushiwen-audio" }.all { it.canSearch })
        assertTrue("没有搜索口的那条必须有 hot，否则它在界面上根本没有入口",
            all.filter { !it.canSearch }.all { it.hot != null })
    }

    /**
     * 有声那一条的三个新格是它能不能出东西的全部依赖：
     * `toc.format=xml` 少了 → 集名带着 CDATA 两层壳；`toc.href` 少了 → 一集都选不出来；
     * `@self` 写错 → 拿 10MB 的音频当一页 HTML 读回来再解一遍，然后报"一集都没抓到"。
     */
    @Test
    fun theShippedAudioRuleCarriesTheThreeGridsItNeeds() {
        val r = RuleLoader.load(text("podcast-rss"), "podcast-rss")
        assertEquals(Rule.AUDIO, r.kind)
        assertEquals("书页是 RSS，和 JSON 的搜索口不是一种东西", "xml", r.toc.format)
        assertEquals("地址挂在 enclosure 的 url 上，不是条目的 href", "enclosure @url", r.toc.href)
        assertTrue("目录里那一条地址本身就是文件", r.selfAudio)
        assertEquals("一集几十 MB，四条线程一起读整段会顶到堆上限", 2, r.crawl.concurrency)
    }

    /**
     * 「一章 = 一个包」那一族的第一条随包源（199mh）。它出不出得了东西全押在四格上：
     * `chapter.file` 三格少任一格 = 下载进度走到 100% 却一本都打不开；`login` 少了 = 未登录把
     * HTTP 200 当成功（那个取址口把「請先登錄」写在 **body** 里，状态码上看不出来）；
     * 号埋在 `onclick="fileDownload(1176462)"` 里，所以 `toc.href` 必须是 `@num` 那一档，
     * 少这一格目录就全是同一个地址。翻页那两格（`hot.page` / `cats.page`）是量过才写的。
     */
    @Test
    fun theShippedFileChapterRuleCarriesLocateBodyLoginAndTheNumberExtractor() {
        val r = RuleLoader.load(text("mh199"), "mh199")
        assertEquals("这一条是漫画（一章交回来的是一个包，不是一排图）", Rule.COMIC, r.kind)
        val f = r.chapter.file
        assertTrue("没写 chapter.file 就不是包那一族 —— 那这条不该随包", f != null)
        assertEquals("ids[]={num}&ext=epub", f!!.body)
        assertEquals("result.0.url", f.path)
        val lg = r.login
        assertTrue("要先登录的源没写登录口，界面上就没地方填账号", lg != null)
        assertEquals("email", lg!!.user)
        assertEquals("password", lg.pass)
        // 站点的登录 JS 先 md5 再发（`post['password'] = md5(post['password'])`）：
        // 这一档丢了，这条源在谁手机上都登不进去，而屏幕上只回一句「登錄失敗」
        assertEquals(LoginRule.MD5, lg.hash)
        assertTrue("号在 onclick 里：目录取址得走 @num 那一档", r.toc.href.contains("@num"))
        assertTrue("榜单翻得动才给 hot.page", r.hot!!.page.contains("%n"))
        assertTrue("类目页翻得动才给 cats.page（第 2 页 20 条全新是量的）", r.cats!!.page.contains("%n"))
    }
}
