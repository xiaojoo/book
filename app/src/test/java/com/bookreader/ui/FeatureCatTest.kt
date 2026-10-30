package com.bookreader.ui

import com.bookreader.booksource.BookHit
import com.bookreader.booksource.RuleLoader
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 精选那一排类目的归并。它唯一的真本事是"两家站对同一类的不同叫法落到同一档"——
 * 等值匹配在这里会静默散架：梦书写「玄幻奇幻」、爱下书写「玄幻小说」，
 * 等值的话其中一档永远选不出来，那一排 tab 看着齐全、点一半是空的。
 */
class FeatureCatTest {

    @Test
    fun theSameCategoryFromTwoSitesLandsOnTheSameTab() {
        val pairs = listOf(
            "玄幻奇幻" to "玄幻小说", "修真仙侠" to "修真小说", "都市青春" to "都市小说",
            "历史穿越" to "穿越小说", "网游竞技" to "网游小说", "科幻灵异" to "科幻小说",
        )
        for ((a, b) in pairs) {
            assertEquals("「$a」和「$b」是同一类，不该分成两档", featureCat(a), featureCat(b))
        }
        assertEquals("玄幻", featureCat("玄幻奇幻"))
        assertEquals("修真", featureCat("修真小说"))
    }

    @Test
    fun everyTabInTheTableActuallyHitsARealSiteCategory() {
        // 表里放一档但两家站都没有那一类，那一排就会多一颗点了空白的 tab。
        // 「漫画」「有声」不在这条判据里：它们不靠站点的分类词命中，是 `kind=comic/audio` 那条源
        // 交回来的每一本在 [kindAs] 里被改成这一类的 —— 拿"真实类目"去要求它们，量的是不存在的东西
        val real = listOf("玄幻奇幻", "修真仙侠", "都市青春", "历史穿越", "网游竞技", "科幻灵异")
        val siteCats = FEATURE_CATS - CAT_COMIC - CAT_AUDIO
        val hit = siteCats.filter { c -> real.any { it.contains(c) } }
        assertEquals("类目表里有档命中不了真实类目：${siteCats - hit.toSet()}", siteCats.toSet(), hit.toSet())
    }

    @Test
    fun theComicTabIsFilledByTheKindNotByTheSiteLabel() {
        val comic = RuleLoader.load(COMIC_RULE, "测试漫画源")
        val novel = RuleLoader.load(NOVEL_RULE, "测试小说源")
        val rows = listOf(hit("海贼王", "热血 · 冒险"), hit("第一话", ""))
        assertEquals("漫画源的榜单每一本都落到「漫画」那一档", listOf(CAT_COMIC, CAT_COMIC), rows.kindAs(comic).map { featureCat(it.category) })
        assertEquals("小说源不改：那条源的分类词还是照站点归并", listOf(CAT_OTHER, CAT_OTHER), rows.kindAs(novel).map { featureCat(it.category) })
    }

    @Test
    fun theAudioTabIsFilledByTheKindToo() {
        val audio = RuleLoader.load(AUDIO_RULE, "测试有声源")
        val rows = listOf(hit("斗破苍穹（播讲版）", "都市 · 推理"), hit("第二集", ""))
        assertEquals("有声源的榜单每一本都落到「有声」那一档", listOf(CAT_AUDIO, CAT_AUDIO), rows.kindAs(audio).map { featureCat(it.category) })
    }

    @Test
    fun anUnknownCategoryGoesToOtherInsteadOfVanishing() {
        assertEquals(CAT_OTHER, featureCat("武侠小说"))
        assertEquals(CAT_OTHER, featureCat(""))
        assertEquals(CAT_OTHER, featureCat("灵异"))
    }

    @Test
    fun everyCategoryKeepsAllItsBooksInThePool() {
        // 站点首页的真实排法：一块 12 本、六块连着排。整条取前 20 本就只剩头两个类目，
        // 那一排类目 tab 会静默少掉四颗 —— 所以池子按类目整份留着，谁也不截断
        //（以前每类卡 6 本，「更多」那一屏就少一半：两家站的 24 本被砍成 12 本）
        val site = FEATURE_CATS.flatMap { c -> List(12) { i -> hit("$c-书名$i", c) } }
        val groups = site.groupBy { featureCat(it.category) }
        assertEquals("七个类目一颗都不能少", FEATURE_CATS.toSet(), groups.keys)
        assertEquals("每一类 12 本全留着", 12, groups[FEATURE_CATS.first()]?.size)
        assertEquals("八类 × 12 本，一本都没被截掉", 96, groups.values.sumOf { it.size })
        assertEquals("类目表本身是八颗（六类小说 + 漫画 + 有声）", 8, FEATURE_CATS.size)
        assertEquals("整条取前 20 本只会剩两个类目 —— 那种写法正是这条判据要挡的",
            2, site.take(20).map { featureCat(it.category) }.distinct().size)
    }

    /**
     * 分类页那一格什么时候摆方格骨架。这条判据管的是"闪一下"和"永远站着"两头：
     * 只认 loading 就只剩几百毫秒的一闪（真机量过 0.4 秒满屏封面），把"还翻得动"也算进来
     * 就会在压根翻不动的那一站站成一句等不到的假话。
     */
    @Test
    fun gridSkeletonStandsForTheWholeEmptyWindow() {
        assertTrue("外层下拉、这一格还空着", gridShowsSkeleton(true, true, false, false, false))
        assertTrue("正在要这一类的第一页", gridShowsSkeleton(true, false, true, true, false))
        assertTrue("翻得动、也还没说翻完 —— 这几秒不该是一片空墙", gridShowsSkeleton(true, false, false, true, false))
        assertFalse("已经有书：闪一张骨架看着像把列表清了", gridShowsSkeleton(false, true, true, true, false))
        assertFalse("这一站压根没写翻页模板：骨架站着就是等不到的假话", gridShowsSkeleton(true, false, false, false, false))
        assertFalse("当场说翻完了却一本没有：那句实话比骨架有用", gridShowsSkeleton(true, false, false, true, true))
    }

    private fun hit(name: String, category: String) = BookHit(
        sourceId = "s", sourceName = "测试源", bookName = name, author = "作者",
        bookUrl = "http://x/$name", category = category,
    )

    private companion object {
        val COMIC_RULE = """
            {"id":"t","name":"测试漫画源","homeUrl":"https://t.example/","kind":"comic",
             "search":{"url":"https://t.example/s?k=%s","item":".row","bookName":".a","author":"","bookUrl":".a @href"},
             "toc":{"item":"a.chap"},"chapter":{"image":"img @data-original"}}
        """.trimIndent()

        val NOVEL_RULE = """
            {"id":"n","name":"测试小说源","homeUrl":"https://n.example/",
             "search":{"url":"https://n.example/s?k=%s","item":".row","bookName":".a","author":".a","bookUrl":".a @href"},
             "toc":{"item":"a.chap"},"chapter":{"content":"#txt"}}
        """.trimIndent()

        val AUDIO_RULE = """
            {"id":"a","name":"测试有声源","homeUrl":"https://a.example/","kind":"audio",
             "search":{"url":"https://a.example/s?k=%s","item":".row","bookName":".a","author":"","bookUrl":".a @href"},
             "toc":{"item":"a.ep"},"chapter":{"audio":"audio source @src"}}
        """.trimIndent()
    }
}
