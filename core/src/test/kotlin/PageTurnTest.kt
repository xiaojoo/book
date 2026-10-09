package com.bookreader.core

import com.bookreader.core.engine.PageTurn
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/**
 * 翻页动画的方向判据：左右两翻必须严格互为镜像。
 * 每条都带一个"只破一边"的反证——把方向参数写死成往前翻，这几条必须红。
 */
class PageTurnTest {

    private fun assertClose(expected: Float, actual: Float, at: String) =
        assertEquals("$at 位移不对", expected, actual, 1e-4f)

    @Test
    fun slideStartsOffScreenAndEndsFlush() {
        // 往前翻：起手离场页占满屏、进场页整页在右边屏外；收笔时正好反过来
        val (outAt0, inAt0) = PageTurn.slideOffsets(0f, forward = true)
        assertClose(0f, outAt0, "往前 p=0 离场页")
        assertClose(1f, inAt0, "往前 p=0 进场页")
        val (outAt1, inAt1) = PageTurn.slideOffsets(1f, forward = true)
        assertClose(-1f, outAt1, "往前 p=1 离场页")
        assertClose(0f, inAt1, "往前 p=1 进场页")
    }

    @Test
    fun backwardSlideIsExactMirrorOfForward() {
        for (p in listOf(0f, 0.25f, 0.5f, 0.75f, 1f)) {
            val f = PageTurn.slideOffsets(p, forward = true)
            val b = PageTurn.slideOffsets(p, forward = false)
            assertClose(-f.first, b.first, "往后 p=$p 离场页")
            assertClose(-f.second, b.second, "往后 p=$p 进场页")
            // 反证：方向写死成"永远从右边进"，这条就会红
            assertNotEquals("往后翻不能和往前翻同向（进场页）", f.second, b.second)
        }
        // 中间那一帧：往前翻新页在右半边，往后翻新页在左半边
        assertClose(0.5f, PageTurn.slideOffsets(0.5f, true).second, "往前 p=0.5 进场页在右")
        assertClose(-0.5f, PageTurn.slideOffsets(0.5f, false).second, "往后 p=0.5 进场页在左")
    }

    @Test
    fun twoPagesAlwaysMeetAtOneSeam() {
        // 两页位移之差恒等于一个屏宽：接缝只有一条，不会重叠也不会露底
        for (p in listOf(0f, 0.3f, 0.5f, 0.9f, 1f)) {
            for (forward in listOf(true, false)) {
                val (out, inn) = PageTurn.slideOffsets(p, forward)
                assertClose(1f, kotlin.math.abs(inn - out), "p=$p forward=$forward 两页间距")
            }
        }
    }

    @Test
    fun coverLiftRunsBothWaysAroundTheSameSpine() {
        assertClose(0f, PageTurn.coverLift(0f, true), "往前起手")
        assertClose(1f, PageTurn.coverLift(1f, true), "往前收笔")
        assertClose(1f, PageTurn.coverLift(0f, false), "往后起手（新页还立在书脊上）")
        assertClose(0f, PageTurn.coverLift(1f, false), "往后收笔（新页铺平）")
        assertClose(-90f, PageTurn.coverAngle(1f), "侧棱角")
        assertClose(0f, PageTurn.coverAngle(0f), "平放角")
        // 反证：两档抬起量若与方向无关，往后翻就成了"掀新页"
        assertNotEquals(PageTurn.coverLift(0.4f, true), PageTurn.coverLift(0.4f, false), 1e-4f)
    }
}
