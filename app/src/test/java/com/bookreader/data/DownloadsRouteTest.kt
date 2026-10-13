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
    fun pullingTheHeadOntoAnExistingBookBecomesAFullPull() {
        // 反证：head 原样带过去时，入库会把这本整本重灌成前十章 —— 老位置还在，后面的章没了
        val r = Downloads.routeOf("1-夜无疆", update = false, head = 10)
        assertEquals("先拉开头对架上那本没意义，按全本补", 0, r.head)
        assertEquals(true, r.update)
    }

    @Test
    fun theTasksPanelUpdateKeepsItsOwnHead() {
        val r = Downloads.routeOf(null, update = true, head = 0)
        assertEquals(true, r.update)
        assertEquals("", r.bookId)
    }
}
