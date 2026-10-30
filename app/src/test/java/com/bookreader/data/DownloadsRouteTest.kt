package com.bookreader.data

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 「第二次点同一本书」到底排的是哪一趟。判据盯的是三件界面上看得出来的事：
 * 写回架上那一本（不再多一本）、按增量补新章、以及「先拉开头」不能把一本全本裁成前十章。
 */
class DownloadsRouteTest {

    @Test
    fun aBookNotOnTheShelfStillDownloadsFresh() {
        val r = Downloads.routeOf(null, update = false, head = 0)
        assertEquals("", r.bookId)
        assertEquals(false, r.update)
        assertEquals(0, r.head)
    }

    @Test
    fun theSecondTimeWritesIntoTheBookAlreadyOnTheShelf() {
        val r = Downloads.routeOf("1791744564322-夜无疆", update = false, head = 0)
        assertEquals("1791744564322-夜无疆", r.bookId)
        assertEquals("架上已有 = 这一趟是增量，不是再来一本", true, r.update)
    }

    @Test
    fun pullingTheHeadOntoAnExistingBookNeverCutsItDown() {
        // 反证：head 原样带过去时，入库会把这本整本重灌成前十章 —— 老位置还在，后面的章没了
        val r = Downloads.routeOf("1-夜无疆", update = false, head = 10, onShelfChapters = 882)
        assertEquals("架上已有 882 章，head 只能长不能裁", 882, r.head)
        assertEquals(true, r.update)
    }

    @Test
    fun aPartialBookCanGrowChapterByChapter() {
        // 架上那本只补到第 6 章：点第 9 章那一行 = 长到 9 章，不是整本 1195 章
        val r = Downloads.routeOf("1-宿命之环", update = true, head = 9, onShelfChapters = 6)
        assertEquals(9, r.head)
        assertEquals("1-宿命之环", r.bookId)
    }

    @Test
    fun aFullPullStaysAFullPull() {
        val r = Downloads.routeOf("1-夜无疆", update = true, head = 0, onShelfChapters = 882)
        assertEquals("head=0 是「补齐全本」那颗，别被 clamp 成一个前缀", 0, r.head)
    }

    @Test
    fun theTasksPanelUpdateKeepsItsOwnHead() {
        val r = Downloads.routeOf(null, update = true, head = 0)
        assertEquals(true, r.update)
        assertEquals("", r.bookId)
    }
}
