package com.bookreader.core.layout

import com.bookreader.core.model.InlineFlag
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

    private fun widthOf(c: Char, flags: Int = 0): Float {
        val base = when {
            BreakRules.isCjk(c) -> fontSizePx
            c == ' ' -> fontSizePx * 0.30f
            c in 'A'..'Z' || c in 'a'..'z' || c in '0'..'9' -> fontSizePx * 0.55f
            else -> fontSizePx * 0.50f
        }
        // 等宽那一档按 0.6 格给（拉丁等宽比比例字体宽、比汉字窄）；粗斜体只是略微变宽，
        // 近似模型要的是"确定、可复算"，不是冒充真机渲染
        val mono = flags and InlineFlag.CODE != 0
        val bump = if (flags and InlineFlag.BOLD != 0) 1.04f else 1f
        return (if (mono) fontSizePx * 0.60f else base) * bump
    }

    private fun flagsAt(runs: List<RunRange>, i: Int): Int {
        var acc = 0
        for (r in runs) if (i in r.from until r.until) acc = acc or r.flags
        return acc
    }

    override fun widthOf(text: CharSequence, from: Int, until: Int): Float {
        var acc = 0f
        var i = from
        while (i < until) acc += widthOf(text[i++])
        return acc
    }

    override fun widthOf(text: CharSequence, from: Int, until: Int, runs: List<RunRange>): Float {
        if (runs.isEmpty()) return widthOf(text, from, until)
        var acc = 0f
        var i = from
        while (i < until) {
            acc += widthOf(text[i], flagsAt(runs, i))
            i++
        }
        return acc
    }

    override fun fit(text: CharSequence, from: Int, until: Int, availWidth: Float): Int =
        fit(text, from, until, availWidth, emptyList())

    override fun fit(text: CharSequence, from: Int, until: Int, availWidth: Float, runs: List<RunRange>, cjkRules: Boolean): Int {
        if (until <= from) return until.coerceAtLeast(from + 1)
        var acc = 0f
        var lastBreak = from + 1
        var i = from
        val adjust: (Int) -> Int = { raw ->
            if (cjkRules) BreakRules.adjust(text, from, raw, until) else raw.coerceIn(from + 1, until)
        }
        while (i < until) {
            val w = if (runs.isEmpty()) widthOf(text[i]) else widthOf(text[i], flagsAt(runs, i))
            if (acc + w > availWidth) return adjust(minOf(lastBreak, i))
            acc += w
            i++
            if (i < until && BreakRules.isBreakOpportunity(text, i - 1, i)) lastBreak = i
        }
        return adjust(until)
    }
}
