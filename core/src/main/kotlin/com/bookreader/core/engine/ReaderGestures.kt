package com.bookreader.core.engine

enum class TapZone { Prev, ToggleBars, Next }

/**
 * 翻页手势的判定，抽成纯函数是因为它决定"点哪翻哪页"，而这种东西在屏幕上看第二次才知道错。
 */
object ReaderGestures {

    /**
     * 左右各三分之一翻页（左三分之一往回翻、右三分之一往后翻），中间三分之一开关工具栏。
     * 分界线用 `w/3`、`2w/3` 而不是 `w * (1 - 1/3f)`：后者在 1080 上算出 719.99996，
     * 正好落在 720 的点击会被浮点误差判成"下一页"。
     */
    fun zoneOf(widthPx: Int, xPx: Float): TapZone = when {
        widthPx <= 0 -> TapZone.ToggleBars
        xPx < widthPx / 3f -> TapZone.Prev
        xPx >= widthPx * 2f / 3f -> TapZone.Next
        else -> TapZone.ToggleBars
    }

    /** 横向拖动超过阈值才翻页：手指往左滑 = 往后翻（+1），往右滑 = 往前翻（-1）。 */
    fun swipeDirection(dragPx: Float, thresholdPx: Float): Int? = when {
        thresholdPx <= 0f -> null
        dragPx <= -thresholdPx -> 1
        dragPx >= thresholdPx -> -1
        else -> null
    }
}
