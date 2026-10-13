package com.bookreader.booksource

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 书源文件这一层的判据。
 *
 * 要防的是同一类事：一条源"看着配好了"，实际每次取值都是空的。所以这里断言的不是
 * "能解析"，而是**写错的时候必须当场红**——字段名打错、必填漏了、同一个键写两遍、
 * 要跑 JS、限速区间反了、方法名瞎写。这些在界面上都会变成"搜不到"，没人会想到是配置文件写坏了。
 */
class RuleLoaderTest {

    private fun text(path: String): String = fixtureText(path)

    private fun rule(name: String): Rule = RuleLoader.load(text("/rules/$name.json"), name)

    /**
     * 抽屉里那颗「填一份模板进上面那格」发的是 `resources/booksource/template.json`。
     * 这份要自己解析不过，那颗按钮就是往框里塞一段装不上的东西 —— 所以它按"该装得上"判，
     * 而且三个取值口（搜索 / 目录 / 正文）都得填齐，照着改的人才不会改完还是一颗死的。
     */
    @Test
    fun theShippedTemplateLoadsAndDeclaresEveryGridTheEngineNeeds() {
        val t = text("/booksource/template.json")
        val r = RuleLoader.load(t, "template")
        println("[模板] id=${r.id} 能搜=${r.canSearch} 目录=${r.toc.item} 正文=${r.chapter.content} filter=${r.chapter.filter}")
        assertTrue("模板得能搜", r.canSearch)
        assertTrue("目录和正文两格都要有选择器", r.toc.item.isNotEmpty() && r.chapter.content.isNotEmpty())
        assertTrue("封面那格得写成「选择器 @src」，模板里也要写着", r.cover.contains("@"))
        assertTrue("限速区间要取得出", r.crawl.maxIntervalMs >= r.crawl.minIntervalMs)
        assertEquals("charset 要写明，别让人猜", "utf-8", r.charset)
    }

    @Test
    fun bundledRulesLoadWithTheirOwnFields() {
        val all = listOf(rule("xhytd"), rule("shuhu"), rule("tianxi"))
        println("[书源] " + all.map { it.id + " 搜索=" + it.canSearch + " 线程=" + it.crawl.concurrency })
        assertEquals(listOf("xhytd", "shuhu", "tianxi"), all.map { it.id })
        assertTrue("三条都得能搜", all.all { it.canSearch })
        assertEquals("没写 charset 就留空，交给 HTTP 头和 meta 去判", "", all[0].charset)
        assertEquals("明写的 gbk 要原样带出来", "gbk", all[2].charset)
        assertEquals("crawl 只写线程数时其余走默认", 1200L, all[1].crawl.maxIntervalMs)
        assertEquals("限速下限照文件里写的", 800L, all[2].crawl.minIntervalMs)
        assertEquals("重试次数", 4, all[2].crawl.maxAttempts)
        assertEquals("POST 源的方法要读出来", "POST", all[2].search!!.method)
        assertEquals("目录分页下拉框", "#indexselect > option", all[2].toc.nextPage)
    }

    @Test
    fun mistypedFieldNameIsRefusedByName() {
        val bad = text("/rules/xhytd.json").replace("\"item\":", "\"itme\":")
        val e = runCatching { RuleLoader.load(bad, "打错字") }.exceptionOrNull()
        assertTrue("要说清是哪个键不认识，实际：" + e?.message, e is RuleException && e.message!!.contains("itme"))
    }

    @Test
    fun unknownTopLevelKeyIsRefused() {
        val bad = text("/rules/xhytd.json").replace("\"charset\": \"\",", "\"charser\": \"\",")
        val e = runCatching { RuleLoader.load(bad, "多一个键") }.exceptionOrNull()
        assertTrue("实际：" + e?.message, e is RuleException && e.message!!.contains("charser"))
    }

    @Test
    fun ruleNeedingJavaScriptIsRefusedWithTheReason() {
        val bad = text("/rules/tianxi.json").replace(
            "\"url\": \"http://www.example-tx.com/s.php?q=%s\",",
            "\"url\": \"@js: sign(hex(keyword))\",",
        )
        val e = runCatching { RuleLoader.load(bad, "要跑 JS 的那条") }.exceptionOrNull()
        assertTrue("实际：" + e?.message, e is RuleException && e.message!!.contains("JavaScript"))
    }

    @Test
    fun missingRequiredFieldIsRefusedByName() {
        val bad = text("/rules/shuhu.json").replace(",\n    \"bookUrl\": \"dl > dt > a@href\"", "")
        val e = runCatching { RuleLoader.load(bad, "漏了必填") }.exceptionOrNull()
        assertTrue("实际：" + e?.message, e is RuleException && e.message!!.contains("bookUrl"))
    }

    @Test
    fun reversedIntervalIsRefused() {
        val bad = text("/rules/tianxi.json").replace("\"minIntervalMs\": 800", "\"minIntervalMs\": 9000")
        val e = runCatching { RuleLoader.load(bad, "区间反了") }.exceptionOrNull()
        assertTrue("实际：" + e?.message, e is RuleException && e.message!!.contains("maxIntervalMs"))
    }

    @Test
    fun aHotBlockMissingItsRowSelectorIsRefusedAndAnUnknownKeyInsideItIsNamed() {
        val base = text("/rules/shuhu.json")
        val e1 = runCatching {
            RuleLoader.load(base.replace("\"toc\":", "\"hot\": { \"url\": \"http://x/\", \"group\": \"c\" }, \"toc\":"), "缺行选择器")
        }.exceptionOrNull()
        assertTrue("要指名缺的是哪一格，实际：" + e1?.message,
            e1 is RuleException && e1.message!!.contains("hot") && e1.message!!.contains("item"))
        val e2 = runCatching {
            RuleLoader.load(
                base.replace("\"toc\":", "\"hot\": { \"group\": \"c\", \"item\": \"li\", \"bookName\": \"a\", \"bookUrl\": \"a@href\", \"ittm\": \"x\" }, \"toc\":"),
                "拼错键名",
            )
        }.exceptionOrNull()
        assertTrue("要说清是哪个键不认识，实际：" + e2?.message, e2 is RuleException && e2.message!!.contains("ittm"))
        val ok = RuleLoader.load(
            base.replace("\"toc\":", "\"hot\": { \"url\": \"http://x/\", \"group\": \"div.content\", \"item\": \"ul li\", \"bookName\": \"a\", \"bookUrl\": \"a@href\" }, \"toc\":"),
            "一条带榜单的源",
        )
        assertEquals("div.content", ok.hot?.group)
        assertEquals("", ok.hot?.author)
        assertEquals("夹具那份写着的 intro 要原样带出来", "#intro", ok.intro)
    }

    @Test
    fun aCoverSelectorWithoutAnAttributeIsRefusedAtLoadTime() {
        // 不带 @ 的话 pick 会回那个元素的**文字**（alt 属性那种），"取封面"就变成拿一句话当图片地址去请求，
        // 不报错、只是永远存不下封面 —— 所以导入时就拒
        // 夹具那份本来就写着 `#fmimg img @src`，所以是"把它换成不带 @ 的"而不是再注入一格
        // （再注入会被重复键检查先拦掉，那条判据在 duplicatedKeyIsRefused 里）
        val e = runCatching {
            RuleLoader.load(text("/rules/shuhu.json").replace("\"cover\": \"#fmimg img @src\"", "\"cover\": \"#fmimg img\""), "无@的封面")
        }.exceptionOrNull()
        assertTrue("实际：" + e?.message, e is RuleException && e.message!!.contains("cover"))
        val ok = RuleLoader.load(text("/rules/shuhu.json"), "带@的封面")
        assertEquals("#fmimg img @src", ok.cover)
    }

    @Test
    fun unsupportedHttpMethodIsRefused() {
        val bad = text("/rules/tianxi.json").replace("\"method\": \"POST\"", "\"method\": \"PUT\"")
        val e = runCatching { RuleLoader.load(bad, "方法写歪") }.exceptionOrNull()
        assertTrue("实际：" + e?.message, e is RuleException && e.message!!.contains("PUT"))
    }

    @Test
    fun duplicatedKeyIsRefused() {
        val e = runCatching { Json.parse("""{"a": 1, "a": 2}""") }.exceptionOrNull()
        assertTrue("实际：" + e?.message, e is RuleException && e.message!!.contains("\"a\" 写了两遍"))
    }

    @Test
    fun cookieSeedAndFormBodyAreReadAndConverted() {
        val withCookies = text("/rules/shuhu.json")
            .replace("\"method\": \"GET\"", "\"method\": \"POST\",\"cookies\": \"waf_sc=''; HMACCOUNT=''\"")
        val r = RuleLoader.load(withCookies, "带 cookie 的那条")
        assertEquals("waf_sc=''; HMACCOUNT=''", r.search!!.cookies)
        assertEquals(
            "{k: v} 那种写法要转成真正发得出去的表单串",
            "searchkey=%E5%89%91&searchtype=all",
            formBody("{searchkey: %s, searchtype: all}", "剑"),
        )
        assertEquals(
            "GBK 站点必须按它的字符集编码关键词：UTF-8 发过去服务器直接吐排序规则冲突",
            "q=%BD%A3",
            formBody("{q: %s}", "剑", "GBK"),
        )
    }

    @Test
    fun malformedUrlTemplateIsRefusedAtLoadTime() {
        val bad = text("/rules/xhytd.json").replace("?name=%s", "?name = %s|")
        val e = runCatching { RuleLoader.load(bad, "地址写歪的那条") }.exceptionOrNull()
        assertTrue("实际：" + e?.message, e is RuleException && e.message!!.contains("不是一个合法地址"))
    }

    @Test
    fun trailingGarbageIsRefused() {
        val e = runCatching { Json.parse("""{"a": 1} }""") }.exceptionOrNull()
        assertTrue("实际：" + e?.message, e is RuleException && e.message!!.contains("多余内容"))
    }
}
