package com.bookreader.core.ingest

import com.bookreader.core.model.BlockKind
import com.bookreader.core.model.BlockStyle
import com.bookreader.core.model.InlineFlag
import com.bookreader.core.model.InlineSpan
import com.bookreader.core.model.StyleIndex
import java.io.File

/**
 * Markdown → "一行一段"的纯文本 + 一份样式旁路。
 *
 * 正文里**不留任何标记字符**：`**粗**` 出去就是"粗"，在那个偏移上记一条 BOLD。
 * 这样搜索、书签、字数统计看到的都是干净正文。反过来把星号留在正文里，
 * 搜"剑"会命中一堆 `**剑**`，字数也虚高。
 *
 * 认：# 三级标题、`> ` 引用、`- * +` 与 `1.` 列表、``` 围栏代码、`---` 分隔线、
 * | 表格 |、**粗**、*斜*、~~删~~、`行内码`、[文字](链接)、![替代文字](地址)。
 * 不认内嵌 HTML 块（那种文档走 [EbookText] 更合适）。
 */
class MarkdownText {

    fun supports(source: File): Boolean {
        val name = source.name.lowercase()
        if (name.endsWith(".md") || name.endsWith(".markdown") || name.endsWith(".mdown")) return true
        // 没扩展名时看标记密度：不够就不认，免得把一本 txt 当 md 拆了
        return runCatching {
            val head = source.inputStream().use { ins ->
                val b = ByteArray(4096)
                val n = ins.read(b)
                if (n <= 0) "" else String(b, 0, n, Charsets.UTF_8)
            }
            MARKER.count { it in head } >= 3
        }.getOrDefault(false)
    }

    class Parsed(val text: String, val styles: StyleIndex) {
        val blocks: Int get() = styles.blockCount
        val spans: Int get() = styles.spans.size
    }

    fun parseFile(source: File): Parsed = parse(source.readText(CharsetDetector.detect(source)))

    fun parse(md: String): Parsed {
        val out = StringBuilder(md.length)
        val blocks = ArrayList<BlockStyle>()
        val spans = ArrayList<InlineSpan>()
        var inFence = false
        for (raw in md.replace("\r\n", "\n").replace('\r', '\n').split('\n')) {
            val line = raw.trimEnd()
            if (FENCE.matches(line.trimStart())) {
                inFence = !inFence
                continue
            }
            if (inFence) {
                // 围栏里的行原样保留：代码的缩进是有意义的，只去掉行尾空白
                if (line.isNotBlank()) {
                    val start = out.length
                    out.append(line).append('\n')
                    blocks += BlockStyle(start, BlockKind.CODE)
                }
                continue
            }
            when {
                line.isBlank() -> Unit // md 的空行只是段落分隔，不像 txt 那样是作者手打的空行
                RULE.matches(line) -> {
                    val start = out.length
                    out.append(RULE_TEXT).append('\n')
                    blocks += BlockStyle(start, BlockKind.RULE)
                }
                else -> emitBlock(line, out, blocks, spans)
            }
        }
        return Parsed(out.toString(), StyleIndex(spans, blocks))
    }

    private fun emitBlock(line: String, out: StringBuilder, blocks: ArrayList<BlockStyle>, spans: ArrayList<InlineSpan>) {
        var body = line
        var kind = BlockKind.PARAGRAPH
        var level = 0
        HEAD.find(line)?.destructured?.let { (hashes, rest) ->
            kind = when (hashes.length) {
                1 -> BlockKind.HEADING1
                2 -> BlockKind.HEADING2
                else -> BlockKind.HEADING3
            }
            body = rest
        }
        if (kind == BlockKind.PARAGRAPH) {
            QUOTE.find(line)?.let {
                kind = BlockKind.QUOTE
                body = it.groupValues[1]
            }
            if (kind == BlockKind.PARAGRAPH) LIST.find(line)?.let {
                kind = BlockKind.LIST
                // 嵌套层级看行首空白：两个空格一级，和常见写法一致
                level = line.takeWhile { c -> c == ' ' }.length / 2
                val marker = it.groupValues[1].trim()
                // 有序列表的编号是有信息量的，留在正文里；无序的换成一个真圆点（排版器不画列表符号）
                body = if (marker.endsWith(".") || marker.endsWith(")")) "$marker ${it.groupValues[2]}" else "• ${it.groupValues[2]}"
            }
            if (kind == BlockKind.PARAGRAPH && line.trimStart().startsWith("|")) kind = BlockKind.TABLE
        }
        val start = out.length
        val (text, inline) = stripInline(body.trim())
        out.append(text).append('\n')
        inline.forEach { spans += InlineSpan(it.start + start, it.end + start, it.flags) }
        blocks += BlockStyle(start, kind, level)
    }

    /**
     * 去掉行内标记，返回干净文本和"输出下标区间→样式"。
     * 记的是这段文字里的局部下标，调用方再加块起点 —— 偏移只在这里算一次。
     */
    private fun stripInline(src: String): Pair<String, List<InlineSpan>> {
        val text = StringBuilder(src.length)
        val spans = ArrayList<InlineSpan>()
        var i = 0
        fun mark(from: Int, flags: Int) {
            if (text.length > from) spans += InlineSpan(from, text.length, flags)
        }
        while (i < src.length) {
            val c = src[i]
            val pair = if (c == '&') entity(src, i) else null
            when {
                pair != null -> { text.append(pair.first); i += pair.second }
                c == '\\' && i + 1 < src.length -> { text.append(src[i + 1]); i += 2 }
                c == '!' && src.startsWith("![", i) -> {
                    val open = src.indexOf('[', i)
                    val close = src.indexOf(']', open + 1)
                    val alt = if (open in 0 until close) src.substring(open + 1, close) else ""
                    text.append("〔图〕").append(alt)
                    val paren = src.indexOf(')', close + 1)
                    i = if (paren < 0) i + 2 else paren + 1
                }
                c == '[' -> {
                    val close = src.indexOf(']', i)
                    if (close < 0 || close + 1 >= src.length || src[close + 1] != '(') { text.append(c); i++ } else {
                        val from = text.length
                        text.append(src, i + 1, close)
                        mark(from, InlineFlag.LINK)
                        val end = src.indexOf(')', close + 2)
                        i = if (end < 0) close + 1 else end + 1
                    }
                }
                c == '`' -> {
                    val close = src.indexOf('`', i + 1)
                    if (close < 0) { text.append(c); i++ } else {
                        val from = text.length
                        text.append(src, i + 1, close)
                        mark(from, InlineFlag.CODE)
                        i = close + 1
                    }
                }
                src.startsWith("**", i) || src.startsWith("__", i) -> i = paired(src, i, 2, text, spans, InlineFlag.BOLD)
                src.startsWith("~~", i) -> i = paired(src, i, 2, text, spans, InlineFlag.STRIKE)
                (c == '*' || c == '_') && i + 1 < src.length && src[i + 1] != ' ' -> {
                    val close = src.indexOf(c, i + 1)
                    if (close < 0) { text.append(c); i++ } else {
                        val from = text.length
                        text.append(src, i + 1, close)
                        mark(from, InlineFlag.ITALIC)
                        i = close + 1
                    }
                }
                else -> { text.append(c); i++ }
            }
        }
        return text.toString() to spans
    }

    /** `xx内容xx` 这种成对标记；找不到闭合就当字面字符抄一个，不吞掉整行。返回下一个读位置。 */
    private fun paired(
        src: String,
        at: Int,
        skip: Int,
        text: StringBuilder,
        spans: ArrayList<InlineSpan>,
        flags: Int,
    ): Int {
        val token = src.substring(at, at + skip)
        val close = src.indexOf(token, at + skip)
        if (close < 0) {
            text.append(src[at])
            return at + 1
        }
        val from = text.length
        text.append(src, at + skip, close)
        if (text.length > from) spans += InlineSpan(from, text.length, flags)
        return close + skip
    }

    private fun entity(src: String, i: Int): Pair<String, Int>? {
        val semi = src.indexOf(';', i + 1)
        if (semi < 0 || semi - i > 8) return null
        return when (src.substring(i + 1, semi)) {
            "amp" -> "&" to 5
            "lt" -> "<" to 4
            "gt" -> ">" to 4
            "quot" -> "\"" to 6
            "apos" -> "'" to 6
            "nbsp" -> " " to 6
            else -> null
        }
    }

    private companion object {
        val MARKER = listOf("\n# ", "\n* ", "\n- ", "**", "```", "\n> ", "](")
        val FENCE = Regex("""^(```|~~~)""")
        val RULE = Regex("""^([-*_])(\s*\1){2,}\s*$""")
        val HEAD = Regex("""^(#{1,6})\s+(.*)$""")
        val QUOTE = Regex("""^>\s?(.*)$""")
        val LIST = Regex("""^(\s*[-*+]|\s*\d+[.)])\s+(.*)$""")

        /** 分隔线画不出真线条时至少要有东西：md 的 `---` 落到正文里是这一串点。 */
        const val RULE_TEXT = "— — — — — —"
    }
}
