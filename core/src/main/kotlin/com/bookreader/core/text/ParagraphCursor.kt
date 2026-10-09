package com.bookreader.core.text

import com.bookreader.core.storage.ChunkCache

data class Paragraph(val start: Long, val end: Long, val text: String) {
    val length: Int get() = text.length
}

/**
 * 段落游标。分页只在段落内断行，所以"这一页从哪个字符开始"必须能从任意偏移反推出段首，
 * 否则往前翻排出的行和往后翻不一致。
 */
class ParagraphCursor(private val cache: ChunkCache) {

    private val total: Long get() = cache.source.totalChars

    /** offset 所在段落的段首（offset 本身就是段首时原样返回）。 */
    fun startAtOrBefore(offset: Long): Long {
        if (offset <= 0L) return 0L
        if (charAt(offset - 1) == '\n') return offset
        var cursor = offset
        while (cursor > 0L) {
            val take = minOf(WINDOW.toLong(), cursor).toInt()
            val windowStart = cursor - take
            val nl = cache.slice(windowStart, take).lastIndexOf('\n')
            if (nl >= 0) return windowStart + nl + 1
            cursor = windowStart
        }
        return 0L
    }

    /** 段首之后的这一段（不含换行）。end 就是那个换行的位置，或书末。 */
    fun paragraphAt(start: Long): Paragraph {
        val sb = StringBuilder()
        var cursor = start
        while (cursor < total) {
            val take = minOf(PARAGRAPH_WINDOW.toLong(), total - cursor).toInt()
            if (take <= 0) break
            val w = cache.slice(cursor, take)
            val nl = w.indexOf('\n')
            if (nl >= 0) {
                sb.append(w, 0, nl)
                cursor += nl
                break
            }
            sb.append(w)
            cursor += w.length
            if (w.length < take) break
        }
        return Paragraph(start, cursor, sb.toString())
    }

    /** 下一段段首（跳过空行）；到书末返回 total。 */
    fun nextParagraphStart(paragraphEnd: Long): Long {
        var cursor = paragraphEnd
        while (cursor < total) {
            val c = charAt(cursor)
            if (c != '\n' && c != '\r') return cursor
            cursor++
        }
        return total
    }

    /** [from]（一段末尾的换行处）之后连着几个换行；>0 就是原文这里空了行。 */
    fun blankLinesAt(from: Long): Int {
        var newlines = 0
        var cursor = from
        while (cursor < total) {
            val c = charAt(cursor)
            if (c != '\n' && c != '\r') break
            if (c == '\n') newlines++
            cursor++
        }
        return newlines - 1
    }

    /**
     * [offset] 往前数连着几个换行（减掉本段自己那一下）。页与页相接的那一刀正好落在段首时，
     * 空行是记在上一段尾下的，上一段排不进这一页就丢了 —— 这一页要自己补回那一格。
     * 不在段首（offset 落在段中）时返回 0。
     */
    fun blankLinesBefore(offset: Long): Int {
        if (offset <= 0L || startAtOrBefore(offset) != offset) return 0
        var newlines = 0
        var cursor = offset - 1
        while (cursor >= 0L) {
            val c = charAt(cursor)
            if (c != '\n' && c != '\r') break
            if (c == '\n') newlines++
            cursor--
        }
        return newlines - 1
    }

    private fun charAt(offset: Long): Char {
        if (offset < 0L || offset >= total) return '\u0000'
        val s = cache.slice(offset, 1)
        return if (s.isEmpty()) '\u0000' else s[0]
    }

    companion object {
        private const val WINDOW = 4096
        private const val PARAGRAPH_WINDOW = 8192
    }
}
