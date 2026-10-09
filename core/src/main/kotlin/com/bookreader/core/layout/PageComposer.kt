package com.bookreader.core.layout

import com.bookreader.core.model.Page
import com.bookreader.core.model.PageStyle
import com.bookreader.core.model.TextLine
import com.bookreader.core.storage.ChunkCache
import com.bookreader.core.text.ParagraphCursor

/**
 * 一页的排法：从任意起始偏移开始，按段落的顺序取行，直到高度用尽。
 * 起始偏移可以落在段落中间（页中断行是正常的），所以先反推段首再取局部下标。
 */
class PageComposer(
    private val cache: ChunkCache,
    private val breaker: LineBreaker,
    private val style: PageStyle,
) {
    private val cursor = ParagraphCursor(cache)

    var linesLaidOut = 0L
        private set

    val lineHeightPx: Float get() = breaker.lineHeightPx

    fun pageStartingAt(offset: Long, chapterStart: Long, chapterEnd: Long, chapterIndex: Int): Page? {
        val total = cache.source.totalChars
        if (offset >= chapterEnd || offset >= total) return null
        val lines = ArrayList<TextLine>(64)
        val lineHeight = breaker.lineHeightPx
        var pos = offset
        var y = 0f
        // 页界正好切在段首时，段间那条空行原本记在上一段的尾巴上，上一页排不下就没了 ——
        // 上下滚动是整页首尾相接的，那一格就在每一页界上少一次（剑来 10~19 章实测：段尾丢空行 36 处，
        // 其中 27 处（75%）正好在页界上）。所以这一页自己补一条零长度的行占住那一格。
        if (offset > chapterStart && cursor.blankLinesBefore(offset) > 0 &&
            lineHeight <= style.contentHeightPx + TOLERANCE
        ) {
            lines += TextLine(offset, offset, 0f, y, 0f)
            linesLaidOut++
            y += lineHeight
        }
        while (y + lineHeight <= style.contentHeightPx + TOLERANCE) {
            if (pos >= chapterEnd || pos >= total) break
            val paragraphStart = cursor.startAtOrBefore(pos)
            val paragraph = cursor.paragraphAt(paragraphStart)
            val lead = leadingSpaces(paragraph.text)
            val limit = (minOf(paragraph.length.toLong(), chapterEnd - paragraphStart) - lead).toInt().coerceAtLeast(0)
            var local = (pos - paragraphStart).toInt() - lead
            if (local < 0) local = 0
            if (local >= limit) {
                pos = cursor.nextParagraphStart(paragraph.end)
                continue
            }
            val isTitle = paragraphStart == chapterStart
            // 章名那一行顶格（不吃段首缩进），放大和加粗由绘制端按 titleScale 办；
            // 可用宽度先按倍数除掉，量出来的自然宽才是放大后真正占的宽度，标题行才不会顶出右边界
            val titleScale = if (isTitle) TITLE_SCALE else 1f
            val indent = if (local == 0 && !isTitle) style.paragraphIndentPx else 0f
            val avail = (style.contentWidthPx - indent) / titleScale
            val fit = breaker.fit(paragraph.text, local + lead, limit + lead, avail).coerceAtLeast(local + lead + 1) - lead
            val natural = breaker.widthOf(paragraph.text, local + lead, fit + lead)
            val chars = fit - local
            val lastOfParagraph = fit >= limit
            // 两端对齐：段中行把余量摊到字距上，段末行不拉伸，否则右边界每行都参差
            val tracking = if (lastOfParagraph || chars < 2) 0f else {
                ((avail - natural) / chars).coerceIn(0f, style.fontSizePx * MAX_TRACK_RATIO)
            }
            lines += TextLine(
                start = paragraphStart + lead + local,
                end = paragraphStart + lead + fit,
                indentPx = indent,
                topPx = y,
                widthPx = natural,
                trackingPx = tracking,
                titleScale = titleScale,
            )
            linesLaidOut++
            y += lineHeight
            if (fit >= limit) {
                val next = cursor.nextParagraphStart(paragraph.end)
                // 这批 TXT 段与段之间本来就空着一行（content.txt 实测每段前一个空行），阅读里要保留：
                // 排一条零长度的行占住行高，绘制端跳过零长度行，看上去就是段间那条空隙。
                if (cursor.blankLinesAt(paragraph.end) > 0 &&
                    y + lineHeight <= style.contentHeightPx + TOLERANCE
                ) {
                    lines += TextLine(next, next, 0f, y, 0f)
                    linesLaidOut++
                    y += lineHeight
                }
                pos = next
            } else {
                pos = paragraphStart + lead + fit
            }
            if (lines.size >= MAX_LINES_PER_PAGE) break
        }
        if (lines.isEmpty()) return null
        return Page(offset, pos.coerceAtMost(chapterEnd), lines, chapterIndex)
    }

    companion object {
        private const val TOLERANCE = 0.5f
        private const val MAX_LINES_PER_PAGE = 500

        /** 章名相对正文的字号倍数（加粗由绘制端 fakeBold 给）。 */
        const val TITLE_SCALE = 1.25f

        /** 两端对齐最多补多少字距（相对字号），不然短句会被拉成稀疏的点线。 */
        private const val MAX_TRACK_RATIO = 0.40f

        /** 正文里手打的假缩进（半角/全角空格）不占版心，段首缩进由排版给，否则一段能缩进四个汉字。 */
        internal fun leadingSpaces(text: String): Int {
            var i = 0
            while (i < text.length && (text[i].isWhitespace() || text[i].code == 0xA0)) i++
            return i
        }
    }
}
