package com.bookreader.core

import com.bookreader.core.engine.ReaderEngine
import com.bookreader.core.ingest.EbookText
import com.bookreader.core.ingest.TxtIngestor
import com.bookreader.core.storage.Book
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 真书探针：用 tools/make_epub.py 从 G:\小说\剑来.txt 造出来的那本 18.7MB EPUB（1291 条 xhtml，
 * 每条都带真 EPUB 的 `<head><title>`），和同一本 txt 各走一遍入库。
 *
 * 判据是"同一条正文换个壳，抽出来的东西不许丢、不许多"：段间不许有空行、章数不许变少、
 * 字数只许差在行首缩进和 `------------` 分隔线上。壳里多出来的 head 会被算成正文这件事，
 * 只有真 EPUB 能暴露 —— 手搓的小夹具没带 head，它就永远不红。
 *
 * 夹具不在（换台机器、没跑生成脚本）就整条跳过，不算红 —— 它比的是这一台机上的真书。
 */
class EbookRealProbe {

    @Test
    fun jianlaiEpubMatchesJianlaiTxt() {
        val epub = File("""H:\book\scratch\jianlai.epub""")
        val txt = File("""G:\小说\剑来.txt""")
        if (!epub.isFile || !txt.isFile) {
            println("[ebook-real] SKIP 缺夹具 epub=${epub.isFile} txt=${txt.isFile}")
            return
        }
        val dir = Fixtures.tempRoot("ebook-real")
        assertTrue("EbookText 不认这本 epub", EbookText().supports(epub))

        val extracted = File(dir, "jianlai.txt")
        val t0 = System.nanoTime()
        val report = checkNotNull(EbookText().extractToText(epub, extracted)) { "抽正文失败" }
        val extractMs = (System.nanoTime() - t0) / 1_000_000
        val raw = extracted.readText(Charsets.UTF_8)
        val lines = raw.split('\n')
        println(
            "[ebook-real] 抽正文 $report ${extractMs}ms 行=${lines.size} 空行=${lines.count { it.isEmpty() }} " +
                "字节=${extracted.length()}",
        )

        val meta = TxtIngestor().ingest(extracted, File(dir, "epub-book"), title = "剑来(epub)").meta
        println("[ebook-real] epub 入库 章=${meta.chapterCount} 字=${meta.totalChars} 编码=${meta.sourceCharset}")
        val base = TxtIngestor().ingest(txt, File(dir, "txt-book"), title = "剑来(txt)").meta
        println("[ebook-real] txt 入库 章=${base.chapterCount} 字=${base.totalChars} 编码=${base.sourceCharset}")

        // 章数差在哪：ChapterPattern 的"第X章"是从行首认的，而 txt 里有些标题行前面有空格。
        // 抽正文把行首空白去掉了，所以 epub 那条路认出的章只会不比 txt 少 —— 这一条是把机制数出来，不是猜的
        val onlyAfterTrim = txt.readLines().count { ln ->
            val t = ln.trim()
            t != ln && t.length in 2..80 && com.bookreader.core.ingest.ChapterPattern.matches(t) &&
                !com.bookreader.core.ingest.ChapterPattern.matches(ln)
        }
        println("[ebook-real] 只在去掉行首空白后才认得的章标题=$onlyAfterTrim 行")

        // 抽出来的正文里不该有空行（那 1 条是文件末尾换行的切片产物，所以只看去掉末行之后）
        assertEquals("段间有空行", 0, lines.dropLast(1).count { it.isBlank() })
        // 同一本正文换个壳：章数不许变少，字数只许差在缩进、\r 和分隔线上。
        // 上限那一头是给 head/meta 兜底的 —— 每章的 <title> 要是被当成正文，章数会凭空多出一倍
        assertTrue(
            "epub 认出的章比同一本 txt 还少：epub=${meta.chapterCount} txt=${base.chapterCount}",
            meta.chapterCount >= base.chapterCount,
        )
        assertTrue(
            "epub 认出的章多得不合理（epub=${meta.chapterCount} txt=${base.chapterCount}）：像是把 head 里的东西读成了正文",
            meta.chapterCount <= base.chapterCount + (base.chapterCount / 20) + 8,
        )
        val gap = (base.totalChars - meta.totalChars).toDouble() / base.totalChars
        assertTrue("字数差得离谱（${"%.3f".format(gap)}），抽正文丢了东西", gap in 0.0..0.15)

        val book = Book.open(File(dir, "epub-book"), 6)
        val engine = ReaderEngine(book, ReaderEngine.approximateBreaker(), Fixtures.STYLE)
        val p = engine.openChapter(1)
        assertTrue("epub 入库后翻不出正文：$p", p != null && p.lines.isNotEmpty())
        println(
            "[ebook-real] 翻页 章=${book.chapters[1].title} lines=${p!!.lines.size} " +
                "本页字=${p.endOffset - p.startOffset} 首行=${book.cache.slice(p.startOffset, 40).replace('\n', '|')}",
        )
        engine.close()
    }
}
