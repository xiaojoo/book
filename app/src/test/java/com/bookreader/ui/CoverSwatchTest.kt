package com.bookreader.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 默认封面的取色。它不是装饰：一排没封面的书全靠这一档颜色分彼此，
 * 所以"同一本每次进来同一个色"和"一排里别全是同一个色"两件事都得钉住。
 */
class CoverSwatchTest {

    private val palette = intArrayOf(0xFF111111.toInt(), 0xFF222222.toInt(), 0xFF333333.toInt(), 0xFF444444.toInt())

    @Test
    fun theSameTitleAlwaysLandsOnTheSameSwatch() {
        val a = coverSwatch("夜无疆", palette)
        repeat(20) { assertEquals("同一本换次哈希就变色，书架滚动时会看到色块跳", a, coverSwatch("夜无疆", palette)) }
    }

    @Test
    fun aShelfOfBooksWithoutCoversSpreadsOverThePalette() {
        val titles = listOf("夜无疆", "剑来", "凡人修仙传", "庆余年", "大奉打更人", "遮天", "雪中悍刀行", "间客")
        val picked = titles.map { coverSwatch(it, palette) }.toSet()
        println("[默认封面] $titles.size 本取到 ${picked.size} 档色")
        assertTrue("一排全是一个色就等于没分彼此：只取到 ${picked.size} 档", picked.size >= 3)
    }

    @Test
    fun aNegativeTitleHashNeverIndexesOffTheEnd() {
        // String.hashCode 会回负数（"BM" 就是 -1），直接取模是越界下标
        assertTrue(coverHash("BM") >= 0)
        assertTrue(coverHash("") >= 0)
        for (t in listOf("BM", "b", "Aa", "", "夜无疆", "bb".repeat(40))) {
            val c = coverSwatch(t, palette)
            assertTrue("$t 取到的色不在色板里", palette.contains(c))
        }
    }
}
