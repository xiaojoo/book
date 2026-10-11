package com.bookreader.core.ingest

import java.io.File
import java.util.zip.ZipFile

/** 一段同样式的文字。sizePt 是 PowerPoint 的原始字号（1pt = 12700 EMU），由查看器按屏宽换算。 */
data class PptxRun(val text: String, val sizePt: Float, val bold: Boolean, val italic: Boolean, val colorArgb: Int)

data class PptxPara(val runs: List<PptxRun>, val level: Int, val align: String)

/** 页面上的一块。坐标全是 EMU，core 不出像素。 */
sealed class PptxShape {
    abstract val x: Int
    abstract val y: Int
    abstract val cx: Int
    abstract val cy: Int
}

data class PptxText(
    override val x: Int, override val y: Int, override val cx: Int, override val cy: Int,
    val paras: List<PptxPara>,
) : PptxShape()

data class PptxPicture(
    override val x: Int, override val y: Int, override val cx: Int, override val cy: Int,
    val entry: String,
) : PptxShape()

data class PptxTable(
    override val x: Int, override val y: Int, override val cx: Int, override val cy: Int,
    val colWidths: List<Int>,
    val rows: List<List<List<PptxPara>>>,
) : PptxShape()

data class PptxSlide(val index: Int, val shapes: List<PptxShape>)

data class PptxDeck(val widthEmu: Int, val heightEmu: Int, val slides: List<PptxSlide>)

/** 查找命中：`page` 是**放映顺序里的下标（0 基）**，和查看器翻页用的是同一个数。 */
data class PptxHit(val page: Int, val line: String, val at: Int)

/**
 * 这一页上所有能读到的文字，一段一条。
 *
 * 表格单元也要算进来：命中只写在正文里、查表格里那几个字查不到，就是"查找"这颗功能是假的。
 */
fun PptxSlide.textLines(): List<String> = shapes.flatMap { shape ->
    when (shape) {
        is PptxText -> shape.paras.map { it.runs.joinToString("") { r -> r.text } }
        is PptxTable -> shape.rows.flatMap { row -> row.flatMap { cell -> cell.map { p -> p.runs.joinToString("") { r -> r.text } } } }
        is PptxPicture -> emptyList()
    }
}.filter { it.isNotBlank() }

/**
 * 全文查找：返回每个命中所在的页和那一行原文（`at` 是命中在这行里的下标，界面据此画高亮）。
 *
 * 只吃 pptx —— pdf 那一侧系统给的 `PdfRenderer` 没有任何取文字的口子（它只把页渲成位图），
 * 所以抽屉里那颗「查找」在 pdf 上是灰的，并写明为什么，不摆一颗点了没结果的按钮。
 */
fun PptxDeck.search(query: String, limit: Int = 200): List<PptxHit> {
    val q = query.trim()
    if (q.isEmpty() || limit <= 0) return emptyList()
    val out = ArrayList<PptxHit>()
    slides.forEachIndexed { page, slide ->
        for (line in slide.textLines()) {
            var i = line.indexOf(q, ignoreCase = true)
            while (i >= 0) {
                if (out.size >= limit) return out
                out += PptxHit(page, line, i)
                i = line.indexOf(q, i + q.length, ignoreCase = true)
            }
        }
    }
    return out
}

/**
 * pptx 的模型层：把 zip 里的 OOXML 读成"每页一张版面"。
 *
 * 故意不用 XML 解析器：转换器和 WPS 吐的 slideN.xml 里命名空间前缀、属性顺序、自闭合写法混用是常态，
 * 容错的扫标签器最多少画一个形状，不会整本打不开。缩放、换行、绘制都在 app 侧，core 保持纯 JVM 可测。
 *
 * 页序取 `p:sldIdLst` 而不是文件名里的数字：复制过页的 deck 两者会不一致，放映顺序才是用户要的顺序。
 */
class PptxDoc {

    fun supports(source: File): Boolean = hasEntry(source, "ppt/presentation.xml")

    fun rejectReason(source: File): String? =
        if (hasEntry(source, "ppt/presentation.xml")) null
        else "不是 pptx（包里找不到 ppt/presentation.xml）：老式 .ppt 是 OLE 复合文档，和 pptx 不是一种格式"

    fun load(source: File): PptxDeck? = runCatching {
        ZipFile(source).use { z ->
            val pres = z.readText("ppt/presentation.xml") ?: return null
            val sldSz = TAG_SLD_SZ.find(pres)?.value ?: ""
            val rels = relsOf(z, "ppt/_rels/presentation.xml.rels")
            val listed = Regex("""<p:sldId\b[^>]*""").findAll(pres).mapNotNull { rels[ridOf(it.value)] }
                // rels 里的 Target 是相对 ppt/ 的，不补上这个前缀就一条都读不到（真件里量过：形状=0）
                .map { if (it.startsWith("/")) it.drop(1) else "ppt/${it.removePrefix("../").removePrefix("./")}" }
                .toList()
            val parts = listed.ifEmpty {
                z.entries().asSequence().map { it.name }.filter { SLIDE_NAME.matches(it) }
                    .sortedBy { it.substringAfter("slide").substringBefore(".xml").toIntOrNull() ?: 0 }.toList()
            }
            PptxDeck(
                widthEmu = intAttr(sldSz, "cx", 12192000),
                heightEmu = intAttr(sldSz, "cy", 6858000),
                slides = parts.mapIndexed { i, part -> slideOf(z, i + 1, part) },
            )
        }
    }.onFailure { println("[pptx] 解析异常 ${it.javaClass.name}: ${it.message}") }.getOrNull()

    private fun slideOf(z: ZipFile, index: Int, part: String): PptxSlide {
        val xml = z.readText(part) ?: return PptxSlide(index, emptyList())
        val rels = relsOf(z, "ppt/slides/_rels/${part.substringAfterLast('/')}.rels")
        val shapes = ArrayList<PptxShape>()
        for (m in SHAPE.findAll(xml)) {
            val body = m.value
            val box = xfrmOf(body)
            when {
                body.startsWith("<p:pic") -> {
                    val blip = Regex("""<a:blip\b[^>]*""").find(body)?.value ?: ""
                    val target = rels[ridOf(blip)]
                    if (target != null) {
                        shapes += PptxPicture(box[0], box[1], box[2], box[3], "ppt/media/${target.substringAfterLast('/')}")
                    }
                }
                body.contains("<a:tbl>") -> shapes += tableOf(box, body)
                body.contains("<p:txBody>") || body.contains("<a:txBody>") -> {
                    val paras = parasOf(body)
                    if (paras.isNotEmpty()) shapes += PptxText(box[0], box[1], box[2], box[3], paras)
                }
            }
        }
        return PptxSlide(index, shapes)
    }

    private fun tableOf(box: IntArray, body: String): PptxTable {
        val cols = Regex("""<a:gridCol\b[^>]*""").findAll(body).map { intAttr(it.value, "w", 0) }.toList()
        val rows = Regex("""<a:tr\b[\s\S]*?</a:tr>""").findAll(body).map { tr ->
            Regex("""<a:tc\b[\s\S]*?(?=<a:tc\b|</a:tr>)""").findAll(tr.value).map { tc -> parasOf(tc.value) }.toList()
        }.toList()
        return PptxTable(box[0], box[1], box[2], box[3], cols, rows)
    }

    /**
     * run 的样式要按 OOXML 的继承来读：python-pptx 和 PowerPoint 把"整段统一设置的字号/粗体"
     * 写进 `<a:pPr><a:defRPr>`，run 自己一个属性都没有 —— 只读 rPr 的话表头那行会全部掉回默认值（真件里量过）。
     * rPr 上显式写了 `b="0"` 时以它为准，所以判的是"有没有这个属性"，不是"值是不是 1"。
     */
    private fun parasOf(body: String): List<PptxPara> =
        PARA.findAll(body).map { chunk ->
            val p = chunk.value
            val pPr = Regex("""<a:pPr\b[\s\S]*?(?:/>|</a:pPr>)""").find(p)?.value ?: ""
            val defRPr = Regex("""<a:defRPr\b[\s\S]*?(?:/>|</a:defRPr>)""").find(pPr)?.value ?: ""
            RUN.findAll(p).map { r ->
                // rPr 的颜色是子元素（<a:rPr ...><a:solidFill><a:srgbClr val="E30000"/>），
                // 只截开标签的话字号读得到、颜色永远读不到 —— 真件里量过：红字那条解出来是 0
                val rpr = Regex("""<a:rPr\b[\s\S]*?(?:/>|</a:rPr>)""").find(r.value)?.value ?: ""
                PptxRun(
                    text = unescape(Regex("""<a:t\b[^>]*>([\s\S]*?)</a:t>""").find(r.value)?.groupValues?.get(1).orEmpty()),
                    sizePt = (intOpt(rpr, "sz") ?: intOpt(defRPr, "sz") ?: 1800) / 100f,
                    bold = flagOf(rpr, defRPr, "b"),
                    italic = flagOf(rpr, defRPr, "i"),
                    colorArgb = colorOf(rpr).takeIf { it != 0 } ?: colorOf(defRPr),
                )
            }.filter { it.text.isNotEmpty() }.toList()
                .let { PptxPara(it, intAttr(pPr, "lvl", 0), Regex("""algn="(\w+)"""").find(pPr)?.groupValues?.get(1) ?: "l") }
        }.filter { it.runs.isNotEmpty() }.toList()

    /**
     * 形状框。文字块用 `<a:xfrm>`，表格那种 `<p:graphicFrame>` 用的是 `<p:xfrm>` ——
     * 只认前者的话表格会拿到一个零宽的框，屏幕上就挤成一列竖排字（真机上就是这么发现的）。
     * 没有 xfrm 的是继承版式的占位，按整页给。
     */
    private fun xfrmOf(body: String): IntArray {
        val xfrm = Regex("""<[ap]:xfrm\b[\s\S]*?</[ap]:xfrm>|<[ap]:xfrm\b[^>]*/>""").find(body)?.value
            ?: return intArrayOf(0, 0, 0, 0)
        val off = Regex("""<[ap]:off\b[^>]*""").find(xfrm)?.value ?: ""
        val ext = Regex("""<[ap]:ext\b[^>]*""").find(xfrm)?.value ?: ""
        return intArrayOf(intAttr(off, "x", 0), intAttr(off, "y", 0), intAttr(ext, "cx", 0), intAttr(ext, "cy", 0))
    }

    private fun relsOf(z: ZipFile, entry: String): Map<String, String> =
        Regex("""<Relationship\b[^>]*""").findAll(z.readText(entry) ?: "").mapNotNull { m ->
            val id = Regex("""Id="([^"]+)"""").find(m.value)?.groupValues?.get(1)
            val target = Regex("""Target="([^"]+)"""").find(m.value)?.groupValues?.get(1)
            if (id == null || target == null) null else id to target
        }.toMap()

    /** 图片挂的是 `r:embed`，形状/页挂的是 `r:id` —— 只认后者的话图片会安静地消失（真件里就这么丢过一张）。 */
    private fun ridOf(tag: String): String =
        Regex("""r:embed="([^"]+)"""").find(tag)?.groupValues?.get(1)
            ?: Regex("""r:id="([^"]+)"""").find(tag)?.groupValues?.get(1) ?: ""

    private fun intAttr(tag: String, name: String, default: Int): Int = intOpt(tag, name) ?: default

    /** 属性名前加 `\b`：不然 `sz=` 会匹配到 `kernsz=` 这种尾巴上带同样字母的属性。 */
    private fun intOpt(tag: String, name: String): Int? =
        Regex("""\b$name="(-?\d+)"""").find(tag)?.groupValues?.get(1)?.toIntOrNull()

    /** rPr 上写了这个属性就以它为准（`b="0"` 是显式不加粗），没写才退回段落默认。 */
    private fun flagOf(rpr: String, defRPr: String, name: String): Boolean =
        flagAttr(rpr, name) ?: flagAttr(defRPr, name) ?: false

    private fun flagAttr(tag: String, name: String): Boolean? =
        Regex("""\b$name="([^"]+)"""").find(tag)?.groupValues?.get(1)?.let { it == "1" || it == "true" }

    /** 主题色（accent1 那种）要读 theme1.xml 才解得开，这里不猜：返回 0 让查看器用自己的默认墨色。 */
    private fun colorOf(rpr: String): Int =
        Regex("""<a:srgbClr val="([0-9A-Fa-f]{6})"""").find(rpr)?.groupValues?.get(1)
            ?.let { runCatching { 0xFF000000.toInt() or Integer.parseInt(it, 16) }.getOrNull() } ?: 0

    private fun unescape(s: String): String = s.replace("&lt;", "<").replace("&gt;", ">")
        .replace("&quot;", "\"").replace("&apos;", "'").replace("&amp;", "&")

    private fun ZipFile.readText(name: String): String? =
        getEntry(name)?.let { getInputStream(it).readBytes().toString(Charsets.UTF_8) }

    private fun hasEntry(source: File, name: String): Boolean = runCatching {
        ZipFile(source).use { it.getEntry(name) != null }
    }.getOrDefault(false)

    private companion object {
        val TAG_SLD_SZ = Regex("""<p:sldSz\b[^>]*""")
        val SLIDE_NAME = Regex("""ppt/slides/slide\d+\.xml""")
        val SHAPE = Regex("""<p:sp\b[\s\S]*?</p:sp>|<p:pic\b[\s\S]*?</p:pic>|<p:graphicFrame\b[\s\S]*?</p:graphicFrame>""")
        val PARA = Regex("""<a:p\b[\s\S]*?(?=<a:p\b|</a:txBody>|</p:txBody>|$)""")
        val RUN = Regex("""<a:r\b[\s\S]*?</a:r>""")
    }
}
