package com.bookreader.core.layout

/**
 * 排版引擎的测量边界。Android 侧用 Paint.measureText，JVM 测试侧用近似字宽模型，
 * 这样分页算法本身可以在没有设备的情况下被验证。
 */
interface LineBreaker {
    val lineHeightPx: Float

    /** [from, until) 这段字的宽度（局部下标）。 */
    fun widthOf(text: CharSequence, from: Int, until: Int): Float

    /**
     * 在 [from, until) 内取不超过 availWidth 的最大前缀，返回断点（不含）。
     * 返回值一定 > from，保证分页一定前进。
     */
    fun fit(text: CharSequence, from: Int, until: Int, availWidth: Float): Int
}
