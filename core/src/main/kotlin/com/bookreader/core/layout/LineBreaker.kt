package com.bookreader.core.layout

/** 一段同样式文字的局部下标区间（[from, until)），flags 见 `model/InlineFlag`。 */
data class RunRange(val from: Int, val until: Int, val flags: Int)

/**
 * 排版引擎的测量边界。Android 侧用 Paint.measureText，JVM 测试侧用近似字宽模型，
 * 这样分页算法本身可以在没有设备的情况下被验证。
 *
 * 带 `runs` 的那两个是 markdown 的行内样式那一档：粗体/斜体/等宽的字宽和正文不一样，
 * 用正文的字体量、再用样式的字体画，行就会溢出右边界。默认实现直接转发到无样式那一版，
 * 所以 txt/epub/mobi 这些没有样式索引的书，走的还是原来那条路。
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

    fun widthOf(text: CharSequence, from: Int, until: Int, runs: List<RunRange>): Float =
        widthOf(text, from, until)

    /**
     * [cjkRules] = false 时不套中文标点那几条断行约束：代码块不是中文散文，
     * "行尾不许是开括号"会把 `fun main() {` 拆成三行（真件里量到过）。
     */
    fun fit(text: CharSequence, from: Int, until: Int, availWidth: Float, runs: List<RunRange>, cjkRules: Boolean = true): Int =
        fit(text, from, until, availWidth)
}
