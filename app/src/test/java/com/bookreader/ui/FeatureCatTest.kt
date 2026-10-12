package com.bookreader.ui

import com.bookreader.booksource.BookHit
import org.junit.Assert.assertEquals
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
        // 表里放一档但两家站都没有那一类，那一排就会多一颗点了空白的 tab
        val real = listOf("玄幻奇幻", "修真仙侠", "都市青春", "历史穿越", "网游竞技", "科幻灵异")
        val hit = FEATURE_CATS.filter { c -> real.any { it.contains(c) } }
        assertEquals("类目表里有档命中不了真实类目：${FEATURE_CATS - hit.toSet()}", FEATURE_CATS.toSet(), hit.toSet())
    }

    @Test
    fun anUnknownCategoryGoesToOtherInsteadOfVanishing() {
        assertEquals(CAT_OTHER, featureCat("武侠小说"))
        assertEquals(CAT_OTHER, featureCat(""))
        assertEquals(CAT_OTHER, featureCat("灵异"))
    }

    @Test
    fun takingPerCategoryKeepsEveryCategoryOnThePage() {
        // 站点首页的真实排法：一块 12 本、六块连着排。整条取前 20 本就只剩头两个类目，
        // 那一排类目 tab 会静默少掉四颗 —— 这条判据在"取前 20 本"的写法下是红的
        val site = FEATURE_CATS.flatMap { c -> List(12) { i -> hit("$c-书名$i", c) } }
        val kept = site.perCategory(6)
        assertEquals("每个类目该留 6 本：${kept.groupBy { it.category }.mapValues { it.value.size }}",
            6, kept.groupBy { it.category }.values.first().size)
        assertEquals("六个类目都该还在", FEATURE_CATS.toSet(), kept.map { featureCat(it.category) }.toSet())
        assertEquals("整条取前 20 本只会剩两个类目", 2, site.take(20).map { featureCat(it.category) }.distinct().size)
    }

    private fun hit(name: String, category: String) = BookHit(
        sourceId = "s", sourceName = "测试源", bookName = name, author = "作者",
        bookUrl = "http://x/$name", category = category,
    )
}
