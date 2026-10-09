package com.bookreader.core.ingest

import java.io.File
import java.util.zip.ZipFile

/**
 * EPUB / DOCX 抽正文：这两种壳都是 zip + 标记文本，抽出来就是"一行一段"的纯文本，
 * 然后原样交给 [TxtIngestor] —— 章节识别、分块、偏移全走同一条路，不另起一套排版。
 *
 * 故意不引 Jsoup/XMLReader：
 * - 网上抓的中文 EPUB 大量是转换器吐的畸形 XHTML，XMLReader 一个未闭合标签就整篇抛错，
 *   容错的剥标签器只会少一段，不会整本打不开；
 * - 章节标题靠 [ChapterPattern] 认文本，不需要读 class/style 属性，所以不需要真解析器。
 */
class EbookText {

    /** 认壳：EPUB 看 container.xml（和 mimetype），DOCX 看 word/document.xml。 */
    fun supports(source: File): Boolean = runCatching {
        ZipFile(source).use { z ->
            z.getEntry("META-INF/container.xml") != null ||
                z.getEntry("mimetype")?.let { z.getInputStream(it).readBytes().toString(Charsets.US_ASCII).contains("epub") } == true ||
                z.getEntry("word/document.xml") != null
        }
    }.onFailure { println("[ebook] 不认这个壳 ${source.name} ${source.length()}B: ${it.javaClass.name}: ${it.message}") }
        .getOrDefault(false)

    /**
     * 抽出来的文本写进 [dest]。
     * @return null = 不是这两种格式或读不出；否则给一句人话说明抽了多少（要显示给用户）。
     */
    fun extractToText(source: File, dest: File): String? = runCatching {
        ZipFile(source).use { z ->
            val docs = z.getEntry("word/document.xml")?.let { listOf(it) } ?: epubSpine(z)
            if (docs.isNullOrEmpty()) {
                println("[ebook] 没有可读的正文条目：${source.name}（docx 缺 word/document.xml，epub 的 manifest/spine 里没给出 XHTML）")
                return null
            }
            val out = dest.bufferedWriter(Charsets.UTF_8)
            var paragraphs = 0
            var chars = 0L
            try {
                for (entry in docs) {
                    val text = z.getInputStream(entry).readBytes().toString(Charsets.UTF_8)
                    if (text.contains("encrypted", ignoreCase = true) && text.contains("drm", ignoreCase = true)) {
                        // 加密的 XHTML（Readium/Adobe DRM）：正文是密文，抽出来是乱码，不如直说
                        return null
                    }
                    for (para in paragraphsOf(text)) {
                        // 一段一行、段间不插空行：epub/docx 用缩进分段，插空行等于全书每个段
                        // 都多占一格，上下滚动的节奏会比原书松一倍
                        out.write(para)
                        out.write("\n")
                        paragraphs++
                        chars += para.length + 1
                    }
                }
            } finally {
                out.close()
            }
            "$paragraphs 段 / $chars 字"
        }
    }.onFailure { println("[ebook] 抽取异常 ${it.javaClass.name}: ${it.message} @ ${it.stackTraceToString().lineSequence().drop(1).firstOrNull()?.trim()}") }.getOrNull()

    /** container.xml → OPF 路径 → spine 顺序的 XHTML 条目（跳过非文本的和 ref 指向外部的）。 */
    internal fun epubSpine(z: ZipFile): List<java.util.zip.ZipEntry>? {
        val container = z.getEntry("META-INF/container.xml") ?: return null
        val opfPath = z.getInputStream(container).readBytes().toString(Charsets.UTF_8)
            .substringAfter("full-path=\"").substringBefore('"').ifBlank { return null }
        val opf = z.getEntry(opfPath) ?: return null
        val xml = z.getInputStream(opf).readBytes().toString(Charsets.UTF_8)
        val base = opfPath.substringBeforeLast('/', "")

        // manifest：id -> href（只收 xhtml/html）
        val hrefs = HashMap<String, String>()
        Regex("""<item\b[^>]*>""").findAll(xml).forEach { m ->
            val tag = m.value
            val id = tag.substringAfter("id=\"", "").substringBefore('"')
            val href = tag.substringAfter("href=\"", "").substringBefore('"')
            val type = tag.substringAfter("media-type=\"", "").substringBefore('"')
            if (id.isNotEmpty() && href.isNotEmpty() &&
                (type.contains("xml") || type.contains("html") || href.endsWith(".xhtml") || href.endsWith(".html") || href.endsWith(".htm"))
            ) hrefs[id] = href
        }
        // spine：idref 顺序就是阅读顺序；toc/nav 那一条目本身没有正文，重复出现会被下面去掉
        val order = Regex("""<itemref\b[^>]*>""").findAll(xml).map {
            it.value.substringAfter("idref=\"", "").substringBefore('"')
        }.filter { it.isNotEmpty() }.toList()
        val picked = (if (order.isEmpty()) hrefs.values.toList() else order.mapNotNull { hrefs[it] })
            .distinct()
            // 目录页本身没有正文，读进去就是全书开头多出一段"目录 第一章…"+一串章节名
            .filterNot { it.substringBefore('#').endsWith("nav.xhtml", true) || it.substringBefore('#').endsWith("toc.ncx", true) }
        return picked.mapNotNull { name ->
            val path = if (base.isEmpty()) name else "$base/${name.substringBefore('#')}"
            z.getEntry(path) ?: z.getEntry(name.substringBefore('#'))
        }
    }

    /**
     * 标记文本 → 一段一行。块级标签结束就断段；`<br>` 也算断段（诗行/歌词里它是换行，
     * 但在小说里当新段读不会错读）。script/style 整段丢掉。
     */
    internal fun paragraphsOf(markup: String): List<String> {
        val out = ArrayList<String>(64)
        val sb = StringBuilder(256)
        var i = 0
        var skipUntil = -1
        while (i < markup.length) {
            val c = markup[i]
            if (c != '<') {
                if (i >= skipUntil) sb.append(c)
                i++
                continue
            }
            val close = markup.indexOf('>', i)
            if (close < 0) {
                i = markup.length
                continue
            }
            // 先把开头的 '/' 摘掉再取标签名：否则 </p> 这种闭合标签的名字会被 takeWhile 截成空串，
            // 只在开标签处断段的话，`<div>正文</div>后面直接跟的字` 会被并进上一段
            val raw = markup.substring(i + 1, close).trim()
            val closing = raw.startsWith("/")
            val name = raw.removePrefix("/")
                .takeWhile { !it.isWhitespace() && it != '/' && it != '!' }
                .lowercase()
            when {
                // 注释要整段跳过：注释里可以再嵌标签，第一个 '>' 会落在注释中间，按 close 走会把假段落读出来
                markup.startsWith("<!--", i) -> {
                    val end = markup.indexOf("-->", i)
                    i = if (end < 0) markup.length else end + 3
                    continue
                }
                // script/style 整块丢：闭合标签要跳过它自己，否则第二次进这一支时 indexOf 会指回原地 —— 死循环
                !closing && (name == "script" || name == "style" || name == "head") -> {
                    val end = markup.indexOf("</$name", close + 1, ignoreCase = true)
                    when {
                        // head 里是 <title>/<meta>，不是正文。真书里每条 xhtml 都带 head，
                        // 不丢的话每章标题会被读两遍（章数直接翻倍），量过：1291 章的 epub 抽出 275,173 段
                        end < 0 && name == "head" -> i = close + 1
                        end < 0 -> {
                            skipUntil = markup.length
                            i = markup.length
                        }
                        else -> {
                            skipUntil = end
                            i = close + 1
                        }
                    }
                    continue
                }
                closing && (name == "script" || name == "style" || name == "head") -> {
                    skipUntil = -1
                    i = close + 1
                    continue
                }
                name in BLOCKS && i >= skipUntil -> {
                    flush(sb, out)
                    // <br> 后面还有字的话要另起一段，所以这里只断不吞
                }
            }
            i = close + 1
        }
        flush(sb, out)
        return out
    }

    private fun flush(sb: StringBuilder, out: MutableList<String>) {
        val text = decodeEntities(sb.toString()).replace('\r', ' ').trim()
        sb.setLength(0)
        if (text.isNotEmpty()) out.add(text)
    }

    private fun decodeEntities(s: String): String {
        if (!s.contains('&')) return s
        val named = mapOf(
            "amp" to "&", "lt" to "<", "gt" to ">", "quot" to "\"", "apos" to "'",
            "nbsp" to " ", "ensp" to " ", "emsp" to " ", "ldquo" to "“", "rdquo" to "”",
            "lsquo" to "‘", "rsquo" to "’", "mdash" to "—", "ndash" to "–", "hellip" to "…",
            "middot" to "·", "bull" to "•",
        )
        val sb = StringBuilder(s.length)
        var i = 0
        while (i < s.length) {
            val c = s[i]
            if (c != '&') {
                sb.append(c); i++; continue
            }
            val semi = s.indexOf(';', i + 1)
            if (semi < 0 || semi - i > 12) {
                sb.append(c); i++; continue
            }
            val body = s.substring(i + 1, semi)
            val v = named[body.lowercase()]
                ?: body.takeIf { it.startsWith("#") }?.removePrefix("#")?.let { n ->
                    runCatching {
                        if (n.startsWith("x", true)) Integer.parseInt(n.drop(1), 16) else n.toInt()
                    }.getOrNull()?.takeIf { it in 0..0x10FFFF }?.let { String(Character.toChars(it)) }
                }
            if (v == null) {
                sb.append(c); i++; continue
            }
            sb.append(v); i = semi + 1
        }
        return sb.toString()
    }

    private companion object {
        val BLOCKS = setOf(
            "p", "div", "br", "li", "h1", "h2", "h3", "h4", "h5", "h6", "tr", "td", "th",
            "blockquote", "section", "article", "body", "header", "footer", "dt", "dd", "hr",
            "w:p", "w:br", "w:tab", "w:pPr",
        )
    }
}
