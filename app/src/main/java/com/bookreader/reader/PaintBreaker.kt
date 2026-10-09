package com.bookreader.reader

import android.graphics.Paint
import android.graphics.Typeface
import com.bookreader.core.layout.BreakRules
import com.bookreader.core.layout.LineBreaker
import com.bookreader.core.model.PageStyle

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

    private val local = object : ThreadLocal<Holder>() {
        override fun initialValue() = Holder(textSizePx, spacingEm, typeface)
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
        if (until <= from) 0f else local.get().paint.measureText(text, from, until)

    override fun fit(text: CharSequence, from: Int, until: Int, availWidth: Float): Int {
        val str = if (text is String) text else text.toString()
        val start = from.coerceIn(0, str.length)
        val end = until.coerceIn(start, str.length)
        if (end <= start) return (start + 1).coerceAtMost(str.length)
        val holder = local.get()
        val fits = holder.paint.breakText(str, start, end, true, availWidth, holder.measured)
        val raw = if (fits <= 0) 1 else fits
        return BreakRules.adjust(str, start, (start + raw).coerceAtMost(end), end)
    }

    fun baselineOffsetPx(): Float = -local.get().metrics.ascent
}
