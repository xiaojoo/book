package com.bookreader.core

import com.bookreader.core.ingest.EbookText
import com.bookreader.core.ingest.TxtIngestor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * EPUB / DOCX 抽正文的判据。夹具是在测试里现造的合法 zip —— 造得出来才说明解析器认的是格式，
 * 不是某一本书。最后一张是畸形 XHTML：那正是我们不引 XMLReader 的理由，得有东西钉住。
 */
class EbookTextTest {

    private fun zip(file: File, entries: Map<String, String>) {
        ZipOutputStream(file.outputStream().buffered()).use { z ->
            entries.forEach { (name, body) ->
                z.putNextEntry(ZipEntry(name))
                z.write(body.toByteArray(Charsets.UTF_8))
                z.closeEntry()
            }
        }
    }

    private fun epub(path: File, chapters: Int, messy: Boolean = false) {
        val opf = StringBuilder()
            .append("""<?xml version="1.0"?><package xmlns="http://www.idpf.org/2007/opf" version="3.0"><metadata><title>测试书</title></metadata><manifest>""")
        val refs = StringBuilder()
        for (i in 1..chapters) {
            opf.append("""<item id="c$i" href="Text/c$i.xhtml" media-type="application/xhtml+xml"/>""")
            refs.append("""<itemref idref="c$i"/>""")
        }
        opf.append("""<item id="toc" href="nav.xhtml" media-type="application/xhtml+xml"/>""")
        refs.append("""<itemref idref="toc"/>""")
        opf.append("</manifest><spine>").append(refs).append("</spine></package>")
        val entries = LinkedHashMap<String, String>()
        entries["mimetype"] = "application/epub+zip"
        entries["META-INF/container.xml"] =
            """<?xml version="1.0"?><container><rootfiles><rootfile full-path="OEBPS/content.opf" media-type="application/oebps-package+xml"/></rootfiles></container>"""
        entries["OEBPS/content.opf"] = opf.toString()
        entries["OEBPS/nav.xhtml"] = "<html><body><nav>目录</nav></body></html>"
        for (i in 1..chapters) {
            val head = """<head><title>第${i}章 起风</title><meta name="author" content="某人"/></head>"""
            val body = if (messy) {
                // 转换器吐的畸形 XHTML：p 不闭合、属性带尖括号、注释里塞标签
                "<html>$head<body><h2>第${i}章 起风</h2><p>第一段&nbsp;有<a href=\"x\">链接里的字</p>" +
                    "<!-- <p>注释里的假段落</p> --><p>第二段 &#20013;&#25991;</p><script>var a=1<2;</script></body></html>"
            } else {
                "<html>$head<body><h2>第${i}章 起风</h2>" +
                    (1..4).joinToString("") { "<p>第${i}章第${it}段：风从崖底上来，$amp 少年收住脚。</p>" } +
                    "</body></html>"
            }
            entries["OEBPS/Text/c$i.xhtml"] = body
        }
        zip(path, entries)
    }

    private fun docx(path: File, paragraphs: Int) {
        val runs = (1..paragraphs).joinToString("") { i ->
            val style = if (i % 5 == 1) "<w:pStyle w:val=\"Heading1\"/>" else ""
            "<w:p><w:pPr>$style</w:pPr><w:r><w:t>第${i}段 少年&amp;剑</w:t></w:r>" +
                "<w:r><w:t>继续的话</w:t></w:r></w:p>"
        }
        zip(
            path,
            mapOf(
                "[Content_Types].xml" to """<?xml version="1.0"?><Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types"/>""",
                "_rels/.rels" to """<?xml version="1.0"?><Relationships xmlns="x"><Relationship Target="word/document.xml"/></Relationships>""",
                "word/document.xml" to """<?xml version="1.0"?><w:document xmlns:w="x"><w:body>$runs</w:body></w:document>""",
            ),
        )
    }

    private val amp = "&ldquo;引用&rdquo;"

    @Test
    fun epubSpineOrderEntitiesAndChapterTitles() {
        val dir = Fixtures.tempRoot("epub")
        val src = File(dir, "t.epub")
        epub(src, chapters = 3)
        val out = File(dir, "out.txt")
        val report = EbookText().extractToText(src, out)
        assertTrue("抽取失败：$report", report != null)
        val lines = out.readLines().filter { it.isNotBlank() }
        // 三个章标题各自成行，且顺序就是 spine 的顺序
        assertEquals(listOf("第1章 起风", "第2章 起风", "第3章 起风"), lines.filter { it.startsWith("第") && it.endsWith("起风") })
        assertEquals(15, lines.size) // 3 章 ×（1 标题 + 4 段）
        // 一段一行、段间不插空行：epub 自己用缩进分段，多出来的空行会把全书撑松一倍
        assertTrue("段间插了空行", !out.readText().contains("\n\n"))
        assertTrue("实体没解：${lines.first { it.contains("引用") }}", lines.any { it.contains("“引用”") })
        // nav.xhtml 在 spine 末尾，只有"目录"两个字，不该把整本书的正文重复一遍
        assertTrue(lines.none { it.contains("var a") })
    }

    @Test
    fun epubThenIngestGivesRealChapters() {
        val dir = Fixtures.tempRoot("epub-ingest")
        val src = File(dir, "t.epub")
        epub(src, chapters = 4)
        val txt = File(dir, "out.txt")
        EbookText().extractToText(src, txt)
        val book = File(dir, "book")
        val meta = TxtIngestor().ingest(txt, book, title = "测试书").meta
        assertEquals(4, meta.chapterCount)
        assertTrue("字数太少：${meta.totalChars}", meta.totalChars > 200)
    }

    @Test
    fun malformedEpubStillExtracts() {
        // 这一条是"为什么不用 XMLReader"的判据：未闭合标签、注释里塞标签、脚本里有尖括号
        val dir = Fixtures.tempRoot("epub-messy")
        val src = File(dir, "m.epub")
        epub(src, chapters = 2, messy = true)
        val out = File(dir, "out.txt")
        val report = EbookText().extractToText(src, out)
        assertTrue("畸形 XHTML 抽不出东西", report != null)
        val text = out.readText()
        assertTrue(text.contains("第一段 有链接里的字"))
        assertTrue("数字实体没解：$text", text.contains("第二段 中文"))
        assertTrue("注释里的假段落混进来了", !text.contains("注释里的假段落"))
        assertTrue("script 混进来了", !text.contains("var a"))
    }

    @Test
    fun docxParagraphsAndIngest() {
        val dir = Fixtures.tempRoot("docx")
        val src = File(dir, "t.docx")
        docx(src, paragraphs = 12)
        val out = File(dir, "out.txt")
        assertTrue(EbookText().extractToText(src, out) != null)
        val lines = out.readLines().filter { it.isNotBlank() }
        assertEquals(12, lines.size)
        assertTrue("段间插了空行", !out.readText().contains("\n\n"))
        // 同一段里两个 run 要拼成一行，不能拆成两段
        assertEquals("第1段 少年&剑继续的话", lines[0])
        val meta = TxtIngestor().ingest(out, File(dir, "book"), title = "t").meta
        assertTrue("docx 抽出来该能入库：$meta", meta.totalChars > 100)
    }

    @Test
    fun plainTxtIsNotClaimedByEbookText() {
        val dir = Fixtures.tempRoot("not-ebook")
        val src = File(dir, "a.txt")
        src.writeText("第1章 起风\n正文\n")
        assertTrue(!EbookText().supports(src))
        assertEquals(null, EbookText().extractToText(src, File(dir, "o.txt")))
    }
}
