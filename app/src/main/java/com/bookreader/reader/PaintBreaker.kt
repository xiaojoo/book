package com.bookreader.reader

import android.graphics.Paint
import android.graphics.Typeface
import com.bookreader.core.layout.BreakRules
import com.bookreader.core.layout.LineBreaker
import com.bookreader.core.layout.RunRange
import com.bookreader.core.model.InlineFlag
import com.bookreader.core.model.PageStyle
import kotlin.math.max

/**
 * 真机字宽测量。
 *
 * 断行用 [Paint.breakText]：一次调用返回"这一行放得下几个字"，走的是 minikin 的 Unicode 断行表，
 * 中文标点和拉丁词的断点判定都比逐字累加正确。
 * （试过 [Paint.getTextWidths] 的那个废弃重载：参数明明合法 —— strLen=97 k=67 block=30 widths=256 ——
 * 仍然抛 IndexOutOfBoundsException，所以不用它。）
 *
 * Paint 不是线程安全的，而排版会同时发生在主线程和预取线程，所以测量件放 ThreadLocal 里。
 */
class PaintBreaker(
    style: PageStyle,
    private val typeface: Typeface = Typeface.DEFAULT,
) : LineBreaker {

    override val lineHeightPx: Float = style.lineHeightPx

    private val textSizePx = style.fontSizePx
    private val spacingEm = if (style.fontSizePx > 0f) style.letterSpacingPx / style.fontSizePx else 0f

    private val local = object : ThreadLocal<HashMap<Int, Holder>>() {
        override fun initialValue() = HashMap<Int, Holder>()
    }

    private fun holder(flags: Int): Holder = local.get().getOrPut(flags) {
        Holder(textSizePx, spacingEm, faceFor(flags))
    }

    /** 样式位 → Typeface。等宽那一档单独走 MONOSPACE，粗体斜体用 create 合成。 */
    private fun faceFor(flags: Int): Typeface {
        val style = when {
            flags and InlineFlag.BOLD != 0 && flags and InlineFlag.ITALIC != 0 -> Typeface.BOLD_ITALIC
            flags and InlineFlag.BOLD != 0 -> Typeface.BOLD
            flags and InlineFlag.ITALIC != 0 -> Typeface.ITALIC
            else -> Typeface.NORMAL
        }
        return if (flags and InlineFlag.CODE != 0) Typeface.create(Typeface.MONOSPACE, style)
        else Typeface.create(typeface, style)
    }

    private class Holder(sizePx: Float, spacing: Float, face: Typeface) {
        val paint = Paint().apply {
            isAntiAlias = true
            textSize = sizePx
            typeface = face
            letterSpacing = spacing
        }
        val measured = FloatArray(1)
        val metrics = paint.fontMetrics
    }

    override fun widthOf(text: CharSequence, from: Int, until: Int): Float =
        if (until <= from) 0f else holder(0).paint.measureText(text, from, until)

    override fun widthOf(text: CharSequence, from: Int, until: Int, runs: List<RunRange>): Float {
        if (runs.isEmpty()) return widthOf(text, from, until)
        var acc = 0f
        for ((a, b, flags) in segments(runs, from, until)) if (b > a) acc += holder(flags).paint.measureText(text, a, b)
        return acc
    }

    override fun fit(text: CharSequence, from: Int, until: Int, availWidth: Float): Int =
        fit(text, from, until, availWidth, emptyList())

    /**
     * 带样式的一档：按 run 依次问每一段"这里放得下几个字"。
     * 用无样式的字体量、用样式的字体画，行会溢出右边界 —— 所以量必须跟着样式走。
     */
    override fun fit(
        text: CharSequence,
        from: Int,
        until: Int,
        availWidth: Float,
        runs: List<RunRange>,
        cjkRules: Boolean,
    ): Int {
        val str = if (text is String) text else text.toString()
        val start = from.coerceIn(0, str.length)
        val end = until.coerceIn(start, str.length)
        if (end <= start) return (start + 1).coerceAtMost(str.length)
        // 代码块不套中文标点规则：`fun main() {` 的结尾是开括号，套了会被拆成三行
        val adjust: (Int) -> Int = { raw ->
            if (cjkRules) BreakRules.adjust(str, start, raw, end) else raw.coerceIn(start + 1, end)
        }
        if (runs.isEmpty()) {
            val holder = holder(0)
            val fits = holder.paint.breakText(str, start, end, true, availWidth, holder.measured)
            return adjust((start + max(1, fits)).coerceAtMost(end))
        }
        var remaining = availWidth
        var i = start
        for ((a, b, flags) in segments(runs, start, end)) {
            if (i >= b) continue
            val at = maxOf(i, a)
            val holder = holder(flags)
            val fits = holder.paint.breakText(str, at, b, true, remaining, holder.measured)
            if (fits <= 0) {
                // 这一段第一个字就放不下：行只能断在它前面，但至少得前进一个字
                return adjust(maxOf(i, start + 1))
            }
            remaining -= holder.paint.measureText(str, at, at + fits)
            i = at + fits
            if (i < b) return adjust(i)
        }
        return adjust(end)
    }

    /** run 区间夹到 [from,until) 并排序；中间没样式的空隙按 flags=0 补上。 */
    private fun segments(runs: List<RunRange>, from: Int, until: Int): List<Triple<Int, Int, Int>> {
        val out = ArrayList<Triple<Int, Int, Int>>(runs.size + 1)
        var pos = from
        for (r in runs.sortedBy { it.from }) {
            val a = r.from.coerceIn(from, until)
            val b = r.until.coerceIn(from, until)
            if (b <= a) continue
            if (a > pos) out += Triple(pos, a, 0)
            out += Triple(a, b, r.flags)
            pos = b
        }
        if (pos < until) out += Triple(pos, until, 0)
        return out
    }

    fun baselineOffsetPx(): Float = -holder(0).metrics.ascent
}
