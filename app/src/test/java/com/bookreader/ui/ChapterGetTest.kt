package com.bookreader.ui

import com.bookreader.booksource.Rule
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 目录行右边那一颗的两种判断：它此刻是什么脸、点它算什么。
 *
 * 这两条都是"错了不会崩、只会看不出来"的那类：
 * - 让勾和箭头同时抢一颗，正在下的那一行就看不出它在下；
 * - 让 `Wait` 那一档漏掉，连点两下就是同一章起两枪、两枪往同一个章目录里写图。
 */
class ChapterGetTest {

    @Test
    fun theRowBeingFetchedShowsTheRingAndNothingElse() {
        assertEquals(GetFace.Fetching, faceOf(has = false, busy = true))
        assertEquals("正在重下的那一章也是圈，不许圈和箭头同时在一行上", GetFace.Redoing, faceOf(has = true, busy = true))
    }

    @Test
    fun downloadedIsRedownloadAndMissingIsArrow() {
        assertEquals("勾撤了：已下载那一行只剩重下那颗", GetFace.Have, faceOf(has = true, busy = false))
        assertEquals(GetFace.Absent, faceOf(has = false, busy = false))
    }

    @Test
    fun downloadedRowOpensWithoutTouchingTheNetwork() {
        assertEquals(RowTap.Open, onRowTap(has = true, busy = false))
    }

    @Test
    fun missingRowFetchesThenReads() {
        assertEquals("他要的口径是「没下的点击就直接下载，然后阅读」", RowTap.FetchThenOpen, onRowTap(has = false, busy = false))
    }

    @Test
    fun eachKindGetsItsOwnMeaningOfTheButton() {
        assertEquals("漫画：一章 = 一排图，就地补那一章", GetKind.Chapter, getKindOf(Rule.COMIC))
        assertEquals("有声：一集 = 一段音频，同上", GetKind.Chapter, getKindOf(Rule.AUDIO))
        assertEquals("小说：一章是整串字里的一段，那颗补的是从头到它那一截", GetKind.Prefix, getKindOf(Rule.NOVEL))
        assertEquals("文献：一篇就是一份文件，没有「某一章」可补", GetKind.None, getKindOf(Rule.PAPER))
        assertEquals("说不清是什么书就不摆", GetKind.None, getKindOf(null))
    }

    @Test
    fun aSecondTapWhileFetchingFiresNothing() {
        assertEquals(RowTap.Wait, onRowTap(has = false, busy = true))
        assertEquals("已经在本机的那一行也一样不重发", RowTap.Wait, onRowTap(has = true, busy = true))
    }

    @Test
    fun reversedOrderKeepsEveryChapterNumberAndAddsNone() {
        assertEquals(listOf(0, 1, 2, 3), chapterOrder(4, false))
        assertEquals(listOf(3, 2, 1, 0), chapterOrder(4, true))
        assertEquals("空目录不炸", emptyList<Int>(), chapterOrder(0, true))
        assertEquals("一话的本倒过来还是它", listOf(0), chapterOrder(1, true))
        assertEquals("倒序不许多一话少一话", 341, chapterOrder(341, true).size)
    }

    @Test
    fun theChapterBeingReadStaysAtItsSlotWhenYouFlipTheOrder() {
        // 30 话的本子在读第 10 话（号 9）：正序它排第 9，倒序它排 30-1-9 = 20
        assertEquals(9, chapterSlot(30, 9, false))
        assertEquals(20, chapterSlot(30, 9, true))
        assertEquals("倒序时第 1 话跑到最后一个", 29, chapterSlot(30, 0, true))
        assertEquals("倒序时最新那话排第一", 0, chapterSlot(30, 29, true))
        assertEquals("两种排法的屏幕位加起来还是总数减一", 29, chapterSlot(30, 9, false) + chapterSlot(30, 9, true))
    }
}
