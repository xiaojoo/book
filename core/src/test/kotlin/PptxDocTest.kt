package com.bookreader.core

import com.bookreader.core.ingest.PptxDoc
import com.bookreader.core.ingest.PptxPicture
import com.bookreader.core.ingest.PptxTable
import com.bookreader.core.ingest.PptxText
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * pptx 模型层的判据。夹具是 tools/make_pptx.py 用 python-pptx 真写的（PowerPoint 那套包结构），
 * 断言的是"版面上该有的块一个都不能少、run 级样式必须按 run 分开" ——
 * 少一个形状或少一个 run 都会画成一张"看着像但缺字"的页，那种错最难发现。
 */
class PptxDocTest {

    private val fixture = File("""H:\book\scratch\jianlai.pptx""")

    @Test
    fun realDeckParsesIntoFiveSlides() {
        if (!fixture.isFile) {
            println("[pptx] SKIP 缺夹具")
            return
        }
        val doc = PptxDoc()
        assertTrue("不认这本 pptx", doc.supports(fixture))
        val deck = checkNotNull(doc.load(fixture)) { "解析失败" }
        assertEquals("页宽 EMU 要和 sldSz 一致", 12192000, deck.widthEmu)
        assertEquals("页高 EMU 要和 sldSz 一致", 6858000, deck.heightEmu)
        assertEquals("页数", 5, deck.slides.size)
        val shapes = deck.slides.sumOf { it.shapes.size }
        val runs = deck.slides.sumOf { s -> s.shapes.sumOf { sh -> runsOf(sh) } }
        println("[pptx] 页=${deck.slides.size} 形状=$shapes run=$runs 文件=${fixture.length()}B")
        assertEquals("形状总数要和造进去的一致", 7, shapes)
        assertEquals("正文 run 数（不含表格单元）", 15L, (runs - tableRuns(deck)).toLong())

        val s2 = deck.slides[1]
        val body = (s2.shapes.first() as PptxText).paras
        assertEquals("第 2 页段落数", 5, body.size)
        assertEquals("第二章 开门", body[0].runs.single().text)
        assertEquals("标题字号 32pt", 32f, body[0].runs.single().sizePt, 0.01f)
        assertTrue("标题该是粗体", body[0].runs.single().bold)
        assertEquals("红字那条的颜色", 0xFFE30000.toInt(), body[3].runs.single().colorArgb)
        assertEquals("列表层级 lvl=1", 1, body[1].level)
        // 同一段里两个 run：一个正常、一个斜体灰字。整段一个样就是"没保留格式"
        assertEquals("末段该有两个 run", 2, body[4].runs.size)
        assertTrue("第二个 run 是斜体", body[4].runs[1].italic)
        assertTrue("第一个 run 不是斜体", !body[4].runs[0].italic)
        assertEquals("（老话）", body[4].runs[1].text)

        val pic = deck.slides[2].shapes.filterIsInstance<PptxPicture>().single()
        assertEquals("图片条目路径要从 slideN.xml.rels 解出来", "ppt/media/image1.png", pic.entry)
        assertTrue("图片条目必须真在包里", ZipFileEntries(fixture).contains(pic.entry))

        val table = deck.slides[3].shapes.filterIsInstance<PptxTable>().single()
        // 表格的 xfrm 是 <p:xfrm>（文字块才是 <a:xfrm>）：只认前者的话这里会是 0，屏幕上挤成一列竖排字
        assertEquals("表格左上角 x（EMU）", 914400, table.x)
        assertEquals("表格宽（EMU）", 10363200, table.cx)
        assertEquals("表格高（EMU）", 2400000, table.cy)
        assertEquals("表格行数", 3, table.rows.size)
        assertEquals("表格列数", 3, table.rows[0].size)
        assertTrue("表头该是粗体", table.rows[0][0].single().runs.single().bold)
        assertEquals("陈平安", table.rows[1][0].single().runs.single().text)
        assertNotNull("列宽要读出来才能对齐格子", table.colWidths.ifEmpty { error("没读到 gridCol") })

        assertEquals("第 5 页段落数", 6, (deck.slides[4].shapes.first() as PptxText).paras.size)
    }

    /**
     * 页序取 `p:sldIdLst`，不取文件名里的数字。夹具是特意反着存的：文件还是 slide1/2/3，
     * 放映顺序是 第二页→第三页→第一页。按文件名读的话这条会红，而界面看起来完全正常 —— 只是翻页翻错了。
     */
    @Test
    fun slideOrderFollowsTheSldIdListNotTheFileNames() {
        val f = File("""H:\book\scratch\pptx-order.pptx""")
        if (!f.isFile) {
            println("[pptx] SKIP 缺顺序夹具")
            return
        }
        val deck = checkNotNull(PptxDoc().load(f)) { "解析失败" }
        val titles = deck.slides.map { s -> (s.shapes.single() as PptxText).paras.single().runs.single().text }
        println("[pptx] 放映顺序读回=$titles")
        assertEquals(listOf("第二页", "第三页", "第一页"), titles)
    }

    @Test
    fun otherZipFormatsAreNotClaimedAsPptx() {
        val doc = PptxDoc()
        for (name in listOf("jianlai.epub", "jianlai.docx")) {
            val f = File("""H:\book\scratch\$name""")
            if (f.isFile) {
                assertTrue("$name 不该被当成 pptx", !doc.supports(f))
                assertTrue("拒绝理由要说清缺什么：${doc.rejectReason(f)}", doc.rejectReason(f)!!.contains("pptx"))
            }
        }
        val txt = File("""H:\book\scratch\novel-10m.txt""")
        if (txt.isFile) assertTrue("txt 更不该被当成 pptx", !doc.supports(txt))
    }

    private fun runsOf(shape: com.bookreader.core.ingest.PptxShape): Int = when (shape) {
        is PptxText -> shape.paras.sumOf { it.runs.size }
        is PptxTable -> shape.rows.sumOf { row -> row.sumOf { parasOfCell(it) } }
        else -> 0
    }

    private fun parasOfCell(cell: List<com.bookreader.core.ingest.PptxPara>): Int = cell.sumOf { it.runs.size }

    private fun tableRuns(deck: com.bookreader.core.ingest.PptxDeck): Int = deck.slides.sumOf { s ->
        s.shapes.filterIsInstance<PptxTable>().sumOf { t -> t.rows.sumOf { row -> row.sumOf { parasOfCell(it) } } }
    }

    private fun ZipFileEntries(f: File): Set<String> = java.util.zip.ZipFile(f).use { z -> z.entries().toList().map { it.name }.toSet() }
}
