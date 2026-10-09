package com.bookreader.core.model

/** 行内样式。一位一种，可以叠（粗体+斜体 = 3）。 */
object InlineFlag {
    const val BOLD = 1
    const val ITALIC = 2
    const val CODE = 4
    const val STRIKE = 8
    const val LINK = 16
}

/** 一段（一行）的块级样式。排版器用它决定放大倍数、缩进、字体和底色。 */
enum class BlockKind {
    PARAGRAPH,
    HEADING1,
    HEADING2,
    HEADING3,
    QUOTE,
    CODE,
    LIST,
    TABLE,
    RULE,
}

/** 绝对字符偏移上的一段行内样式，[start, end)。 */
data class InlineSpan(val start: Int, val end: Int, val flags: Int)

/** 块级样式挂在"这一段的第一字符"上。 */
data class BlockStyle(val start: Int, val kind: BlockKind, val level: Int = 0)

/**
 * 一本书的行内/块级样式。
 *
 * 它是一条**旁路**：正文还是那份纯文本、absoluteOffset 还是唯一的权威位置，
 * 样式只是"这段偏移上长什么样"。所以搜索、书签、翻页、位置恢复全都不用它，
 * 老书（txt/epub/mobi）没有这个文件，行为逐字节不变。
 */
class StyleIndex(val spans: List<InlineSpan>, val blocks: List<BlockStyle>) {

    private val spanStarts = IntArray(spans.size) { spans[it].start }
    private val blockByStart: Map<Int, BlockStyle> = blocks.associateBy { it.start }

    /** 落在 [from, until) 里、且和它有交集的样式段（返回的是绝对偏移）。 */
    fun spansIn(from: Int, until: Int): List<InlineSpan> {
        if (spanStarts.isEmpty() || until <= from) return emptyList()
        var lo = 0
        var hi = spanStarts.size
        while (lo < hi) {
            val mid = (lo + hi) ushr 1
            if (spans[mid].end <= from) lo = mid + 1 else hi = mid
        }
        val out = ArrayList<InlineSpan>()
        var i = lo
        while (i < spans.size && spans[i].start < until) {
            if (spans[i].end > from) out += spans[i]
            i++
        }
        return out
    }

    fun blockAt(start: Int): BlockStyle? = blockByStart[start]

    val blockCount: Int get() = blocks.size

    companion object {
        val EMPTY = StyleIndex(emptyList(), emptyList())
    }
}
