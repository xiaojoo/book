package com.bookreader.core

import com.bookreader.core.ingest.MobiText
import com.bookreader.core.ingest.TxtIngestor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer

/**
 * PalmDOC LZ77 和 MOBI 容器的判据。
 *
 * 距离/长度那几道向量是照 KindleUnpack 的 PalmdocReader（Calibre 用的那一份解压代码）**手算**出来的：
 * `0x80 0x57` → distance=(0x8057>>3)&0x7FF=10、length=(0x8057&7)+3=10。
 * 布局记错一位不会报错，只会安静地出乱码，所以这几道向量必须是人算的、不是编码器吐的。
 */
class MobiTextTest {

    private fun lz(b: ByteArray) = String(MobiText.lz77(b), Charsets.UTF_8)

    private fun bytesOf(vararg v: Int) = ByteArray(v.size) { i -> v[i].toByte() }

    @Test
    fun lz77VectorsFromTheReferenceDecoder() {
        assertEquals("abc", lz(bytesOf(0x61, 0x62, 0x63)))
        // 0x01~0x08 = 照抄后面 c 个字节：UTF-8 的高位字节靠这个进去
        assertEquals("中", lz(bytesOf(0x01, 0xE4, 0x01, 0xB8, 0x01, 0xAD)))
        // 0xC0~0xFF = 空格 + 去掉最高位的字面
        assertEquals(" A", lz(bytesOf(0xC1)))
        // 距离 10、长度 10：把刚抄过的 10 个字节再来一遍
        assertEquals("01234567890123456789", lz(bytesOf(0x30, 0x31, 0x32, 0x33, 0x34, 0x35, 0x36, 0x37, 0x38, 0x39, 0x80, 0x57)))
        // 重叠复制：距离 2、长度 10，要连着刚写出来的字节继续抄
        assertEquals("abababababab", lz(bytesOf(0x61, 0x62, 0x80, 0x17)))
    }

    /** 每条文本记录开头 2 字节是压缩方式：1=不压缩，2=PalmDOC LZ77。 */
    private fun textRecord(html: String, compression: Int = 1): ByteArray {
        // 按 UTF-8 的字节数算，不是字符数：一个中文 3 字节，用 length 会静默截断。
        // 这里不用 ByteBuffer 拼：putShort(0,..) 是绝对写、bulk put 是相对写，混用会把刚写的头盖掉
        val b = html.toByteArray(Charsets.UTF_8)
        return ByteArray(2 + b.size).also {
            it[0] = (compression shr 8).toByte()
            it[1] = (compression and 0xFF).toByte()
            b.copyInto(it, 2)
        }
    }

    /** 真 PalmDB：78 字节头 + 每条 8 字节的记录表 + 记录体（第 0 条是 PalmDOC/MOBI 头）。 */
    private fun palmDb(
        texts: List<ByteArray>,
        compression: Int = 1,
        encryption: Int = 0,
        codepage: Int = 65001,
        firstNonText: Int = 1 + texts.size,
        creator: String = "MOBI",
    ): ByteArray {
        val rec0 = ByteBuffer.allocate(16 + 232).array()
        ByteBuffer.wrap(rec0).putShort(0x00, compression.toShort())
        rec0.putShortBE(0x08, texts.size)
        rec0.putIntBE(0x04, texts.sumOf { it.size - 2 })
        rec0.putShortBE(0x0A, 4096)
        rec0.putShortBE(0x0C, encryption)
        "MOBI".toByteArray().copyInto(rec0, 0x10)
        rec0.putIntBE(0x14, 232)
        rec0.putIntBE(0x1C, codepage)
        rec0.putIntBE(0x24, 6)
        rec0.putIntBE(0x50, firstNonText)
        val body = listOf(rec0) + texts
        val head = ByteArray(78)
        "probe".toByteArray().copyInto(head, 0)
        "BOOK".toByteArray().copyInto(head, 60)
        creator.toByteArray().copyInto(head, 64)
        ByteBuffer.wrap(head).putShort(76, body.size.toShort())
        val out = ByteArrayOutputStream()
        out.write(head)
        var pos = 78 + 8 * body.size
        body.forEach { r ->
            out.write(ByteArray(4).apply { ByteBuffer.wrap(this).putInt(0, pos) })
            out.write(ByteArray(4))
            pos += r.size
        }
        body.forEach { out.write(it) }
        return out.toByteArray()
    }

    private fun ByteArray.putShortBE(at: Int, v: Int) {
        this[at] = (v shr 8 and 0xFF).toByte()
        this[at + 1] = (v and 0xFF).toByte()
    }

    private fun ByteArray.putIntBE(at: Int, v: Int) {
        this[at] = (v shr 24 and 0xFF).toByte()
        this[at + 1] = (v shr 16 and 0xFF).toByte()
        this[at + 2] = (v shr 8 and 0xFF).toByte()
        this[at + 3] = (v and 0xFF).toByte()
    }

    @Test
    fun uncompressedMobiRoundTripsIntoChapters() {
        val dir = Fixtures.tempRoot("mobi")
        val html = "<html><body>" + (1..3).joinToString("") { i ->
            "<h1>第${i}章 起风</h1>" + (1..3).joinToString("") { "<p>第${i}章第${it}段：风从崖底上来。</p>" }
        } + "</body></html>"
        val file = File(dir, "t.mobi")
        file.writeBytes(palmDb(listOf(textRecord(html))))
        assertTrue("不认这本 mobi：${MobiText().rejectReason(file)}", MobiText().supports(file))
        val out = File(dir, "out.txt")
        val report = MobiText().extractToText(file, out)
        assertTrue("抽正文失败：$report", report != null)
        val lines = out.readLines().filter { it.isNotBlank() }
        println("[mobi-unit] 行数=${lines.size} 内容=${lines.joinToString(" | ")}")
        assertEquals(12, lines.size)
        assertEquals("第1章 起风", lines[0])
        val meta = TxtIngestor().ingest(out, File(dir, "book"), title = "t").meta
        assertEquals(3, meta.chapterCount)
        assertTrue("字数太少：${meta.totalChars}", meta.totalChars > 100)
    }

    @Test
    fun drmAndHuffAreRefusedWithAReason() {
        val dir = Fixtures.tempRoot("mobi-refuse")
        val html = "<html><body><p>正文</p></body></html>"
        val drm = File(dir, "drm.mobi").apply { writeBytes(palmDb(listOf(textRecord(html)), encryption = 1)) }
        val huff = File(dir, "huff.mobi").apply { writeBytes(palmDb(listOf(textRecord(html)), compression = 17437)) }
        val other = File(dir, "other.mobi").apply { writeBytes(palmDb(listOf(textRecord(html)), creator = "TEXT")) }

        assertTrue(MobiText().supports(drm).not())
        assertTrue("拒绝理由要点名 DRM：${MobiText().rejectReason(drm)}", MobiText().rejectReason(drm)!!.contains("DRM"))
        assertTrue(MobiText().supports(huff).not())
        assertTrue("拒绝理由要点名 HUFF：${MobiText().rejectReason(huff)}", MobiText().rejectReason(huff)!!.contains("HUFF"))
        assertTrue("creator 不对就不是 mobi：${MobiText().rejectReason(other)}", MobiText().rejectReason(other)!!.contains("PalmDB"))
        assertEquals(null, MobiText().extractToText(drm, File(dir, "o.txt")))
    }

    /** 真书：tools/make_mobi.py 从剑来造的那本（LZ77 压缩，且 KindleUnpack 读得通）。 */
    @Test
    fun realJianlaiMobi() {
        val mobi = File("""H:\book\scratch\jianlai.mobi""")
        val expected = File("""H:\book\scratch\mobi-expected.txt""")
        if (!mobi.isFile || !expected.isFile) {
            println("[mobi-real] SKIP 缺夹具")
            return
        }
        val exp = expected.readLines().filter { it.contains('=') }
            .associate { it.substringBefore('=') to it.substringAfter('=').toLong() }
        val blocks = exp.getValue("blocks")
        val chapters = exp.getValue("chapters")
        assertTrue("不认这本 mobi：${MobiText().rejectReason(mobi)}", MobiText().supports(mobi))
        val dir = Fixtures.tempRoot("mobi-real")
        val out = File(dir, "out.txt")
        val t0 = System.nanoTime()
        val report = checkNotNull(MobiText().extractToText(mobi, out)) { "抽正文失败" }
        val ms = (System.nanoTime() - t0) / 1_000_000
        println("[mobi-real] 抽正文 $report ${ms}ms 造文件时块=$blocks 章=$chapters")
        // 段数必须等于造进去的标签块数：少一段就是解压或剥标签在某一条记录上断了
        assertEquals("抽出来的段数和造进去的块数不一致", blocks, report.substringBefore(" 段").toLong())
        val meta = TxtIngestor().ingest(out, File(dir, "book"), title = "剑来(mobi)").meta
        println("[mobi-real] 入库 章=${meta.chapterCount} 字=${meta.totalChars} 造进去的章数=$chapters")
        assertTrue("章数少得太离谱：${meta.chapterCount} vs $chapters", meta.chapterCount in (chapters - 5)..chapters)
    }
}
