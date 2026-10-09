package com.bookreader.core.layout

import com.bookreader.core.model.PageStyle

/** 中文排版的断行约束，两个 LineBreaker 实现共用。 */
object BreakRules {
    private const val NO_LINE_START = "，。、；：？！）〕］》〉」』】”’・…—－,.;:!?)]}%％"
    private const val NO_LINE_END = "（〔［《〈「『“‘([{"

    /** 行首别是指点符号、行尾别是开括号、行尾别留空格。 */
    fun adjust(text: CharSequence, from: Int, fit: Int, until: Int): Int {
        var f = fit.coerceIn(from + 1, until)
        while (f > from + 1 && f < until && text[f] in NO_LINE_START) f--
        while (f > from + 1 && text[f - 1] in NO_LINE_END) f--
        while (f > from + 1 && (text[f - 1] == ' ' || text[f - 1] == '\t')) f--
        return f
    }

    fun isCjk(c: Char): Boolean = c.code >= 0x2E80

    /** 两个字之间能不能断。 */
    fun isBreakOpportunity(text: CharSequence, prev: Int, next: Int): Boolean {
        val a = text[prev]
        val b = text[next]
        if (a == ' ') return true
        if (isCjk(a)) return true
        if (isCjk(b)) return true
        // 拉丁词中间不许断
        return false
    }
}

/**
 * 无设备时用的近似字宽：中文算一整格，拉丁按半格多一点。
 * 目的是让分页算法在 JVM 上有确定、可复算的数字，而不是冒充真实渲染宽度。
 */
class ApproximateBreaker(style: PageStyle) : LineBreaker {

    private val fontSizePx = style.fontSizePx

    override val lineHeightPx: Float = style.lineHeightPx

    private fun widthOf(c: Char): Float = when {
        BreakRules.isCjk(c) -> fontSizePx
        c == ' ' -> fontSizePx * 0.30f
        c in 'A'..'Z' || c in 'a'..'z' || c in '0'..'9' -> fontSizePx * 0.55f
        else -> fontSizePx * 0.50f
    }

    override fun widthOf(text: CharSequence, from: Int, until: Int): Float {
        var acc = 0f
        var i = from
        while (i < until) acc += widthOf(text[i++])
        return acc
    }

    override fun fit(text: CharSequence, from: Int, until: Int, availWidth: Float): Int {
        if (until <= from) return until.coerceAtLeast(from + 1)
        var acc = 0f
        var lastBreak = from + 1
        var i = from
        while (i < until) {
            val w = widthOf(text[i])
            if (acc + w > availWidth) {
                return BreakRules.adjust(text, from, minOf(lastBreak, i), until)
            }
            acc += w
            i++
            if (i < until && BreakRules.isBreakOpportunity(text, i - 1, i)) lastBreak = i
        }
        return BreakRules.adjust(text, from, until, until)
    }
}
