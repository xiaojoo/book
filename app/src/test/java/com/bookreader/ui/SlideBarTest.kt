package com.bookreader.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 亮度滑轨的取值。它唯一的真本事是"值只看手指在哪，不看上一次是多少"——
 * 上一版拖不动就是因为拖拽算的是 `旧值 × 轨宽 + 本次位移`，而那个"旧值"被
 * `pointerInput(Unit)` 关在第一次组合的闭包里再也不更新。
 */
class SlideBarTest {

    @Test
    fun theValueFollowsTheFingerNotThePreviousValue() {
        // 同一条轨道上先后拖到两个位置：读数就是那两个位置，不会互相叠加
        assertEquals(0.8f, slideRatio(800f, 1000f), 1e-6f)
        assertEquals(0.2f, slideRatio(200f, 1000f), 1e-6f)
        assertEquals("再拖回 800 还是 0.8（旧写法会算成 0.2 + 位移/宽）", 0.8f, slideRatio(800f, 1000f), 1e-6f)
    }

    @Test
    fun theEndsAreClampedToTheTrack() {
        assertEquals("最左端不留 0：0 亮度等于黑屏，按下去就再也调不回来了", 0.04f, slideRatio(0f, 1000f), 1e-6f)
        assertEquals(1f, slideRatio(1000f, 1000f), 1e-6f)
        assertEquals("手指划出轨道也夹在两端", 1f, slideRatio(4000f, 1000f), 1e-6f)
        assertEquals(0.04f, slideRatio(-4000f, 1000f), 1e-6f)
    }

    /** 宽还没量到（首帧给的是 0）时不能除出 0 或 Infinity。 */
    @Test
    fun anUnmeasuredTrackDoesNotDivideByZero() {
        assertEquals("量到宽之前按下去只会落到最左端，不会 NaN/Infinity", 0.04f, slideRatio(0f, 0f), 1e-6f)
        assertTrue(slideRatio(10f, 0f).isFinite())
    }
}
