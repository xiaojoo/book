package com.bookreader.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 气泡那一格的顶内衬。它喂的是 Compose 的 `padding`，负数不是"画歪一点"，是直接抛
 * `IllegalArgumentException: Padding must be non-negative` 把进程打死 ——
 * 全屏那两页的页头 ✕ 离容器顶只剩 34dp、气泡要 36dp，按住就崩过一次。
 */
class TipSlotTest {

    // 这台机 2.75 倍：药丸 26 + 三角 6 + 间隙 4 = 36dp = 99px
    private val rise = 99f
    private val gap = 11f

    @Test
    fun headerCloseToTheTopFlipsTheBubbleBelowInsteadOfCrashing() {
        val (above, top) = tipSlot(atTop = 132f, atBottom = 179f, rootTop = 37f, risePx = rise, gapPx = gap)
        assertFalse("上方只剩 95px，装不下 99px，必须翻到下面", above)
        assertTrue("内衬为负就是那条崩溃：${top}px", top >= 0f)
        assertEquals(179f - 37f + gap, top, 0.01f)
    }

    @Test
    fun aRowWithRoomStillSitsAboveTheIcon() {
        val (above, top) = tipSlot(atTop = 352f, atBottom = 399f, rootTop = 37f, risePx = rise, gapPx = gap)
        assertTrue(above)
        assertEquals(352f - 37f - rise, top, 0.01f)
    }

    @Test
    fun noIconPositionEverHandsOutANegativePadding() {
        for (rootTop in 0..200 step 7) {
            for (atTop in rootTop..2050 step 11) {
                val (_, top) = tipSlot(atTop.toFloat(), (atTop + 67).toFloat(), rootTop.toFloat(), rise, gap)
                assertTrue("rootTop=$rootTop atTop=$atTop 算出 ${top}px", top >= 0f)
            }
        }
    }
}
