package com.bookreader.core

import com.bookreader.core.font.FontCmap
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File

/**
 * FontCmap 的判据必须能两头分辨：列了字的字体要过，没列的必须不通过。
 * 合成字体保证不依赖机器上装了什么；真字体那两条只在 Windows 上跑，跳过时会打印。
 */
class FontCmapTest {

    private fun u16(v: Int) = byteArrayOf(((v shr 8) and 0xFF).toByte(), (v and 0xFF).toByte())

    private fun u32(v: Int) = byteArrayOf(
        ((v shr 24) and 0xFF).toByte(), ((v shr 16) and 0xFF).toByte(),
        ((v shr 8) and 0xFF).toByte(), (v and 0xFF).toByte(),
    )

    /** 造一个只含 cmap(format 4) 的最小 sfnt：只有 codePoints 里的字有字形。 */
    private fun fontWith(vararg codePoints: Int): ByteArray {
        val cps = (codePoints.toList() + 0xFFFF).sorted()
        val seg = cps.size
        val body = ByteArrayOutputStream()
        val sub = ByteArrayOutputStream()
        sub.write(u16(4)); sub.write(u16(0)); sub.write(u16(0)) // format, length(占位), language
        sub.write(u16(seg * 2)); sub.write(u16(0)); sub.write(u16(0)); sub.write(u16(0))
        cps.forEach { sub.write(u16(it)) }
        sub.write(u16(0))
        cps.forEach { sub.write(u16(it)) }
        cps.forEach { sub.write(u16((1 - it) and 0xFFFF)) }
        repeat(seg) { sub.write(u16(0)) }
        val subBytes = sub.toByteArray().also {
            it[2] = ((it.size shr 8) and 0xFF).toByte(); it[3] = (it.size and 0xFF).toByte()
        }
        body.write(u16(0)); body.write(u16(1)) // cmap version, numTables
        body.write(u16(3)); body.write(u16(1)); body.write(u32(12)) // Windows Unicode BMP
        body.write(subBytes)
        val cmap = body.toByteArray()

        val out = ByteArrayOutputStream()
        out.write(u32(0x00010000))
        out.write(u16(1)); out.write(u16(0)); out.write(u16(0)); out.write(u16(0))
        out.write("cmap".toByteArray())
        out.write(u32(0)); out.write(u32(28)); out.write(u32(cmap.size))
        out.write(cmap)
        return out.toByteArray()
    }

    private fun write(bytes: ByteArray, name: String): File {
        val f = File(Fixtures.tempRoot("font"), name)
        f.writeBytes(bytes)
        return f
    }

    @Test
    fun coversExactlyTheCodepointsTheFontLists() {
        val full = write(fontWith(0x4E2D, 0x7684, 0x95E8), "full.ttf")
        val partial = write(fontWith(0x4E2D, 0x7684), "partial.ttf")
        val none = write(fontWith(0x0041), "latin.ttf")
        assertTrue("三个常用字都有就该认", FontCmap.coversAll(full))
        assertFalse("缺一个门字就不算能排正文", FontCmap.coversAll(partial))
        assertFalse("只有拉丁字母的字体必须被筛掉", FontCmap.coversAll(none))
        assertTrue("单个字形的定位不能顺手放行别的字", FontCmap.coversAll(full, intArrayOf(0x4E2D)))
        assertFalse(FontCmap.coversAll(none, intArrayOf(0x4E2D)))
    }

    /** ttcf 里每个 face 的表偏移是"从整个文件开头算"的绝对值，不是相对 face 头。 */
    private fun collection(sfnt: ByteArray, headerSize: Int): ByteArray {
        val out = ByteArrayOutputStream()
        out.write("ttcf".toByteArray())
        out.write(u32(0x00010000)); out.write(u32(1)); out.write(u32(headerSize))
        out.write(sfnt)
        val bytes = out.toByteArray()
        val recordOffsetField = headerSize + 12 + 8 // 表记录里的 offset 字段
        val absolute = headerSize + 28
        bytes[recordOffsetField] = ((absolute shr 24) and 0xFF).toByte()
        bytes[recordOffsetField + 1] = ((absolute shr 16) and 0xFF).toByte()
        bytes[recordOffsetField + 2] = ((absolute shr 8) and 0xFF).toByte()
        bytes[recordOffsetField + 3] = (absolute and 0xFF).toByte()
        return bytes
    }

    @Test
    fun readsFontZeroOfATtcCollection() {
        val sfnt = fontWith(0x4E2D, 0x7684, 0x95E8)
        val ttc = write(collection(sfnt, 16), "collection.ttc")
        assertTrue("ttc 要能读到第 0 个字体", FontCmap.coversAll(ttc))
        // 反证：把表偏移当成"相对 face 头"来写，读的人就会偏 16 个字节
        val wrong = write(collection(sfnt, 16).also {
            val at = 16 + 12 + 8
            val relative = 28
            it[at] = 0; it[at + 1] = 0; it[at + 2] = ((relative shr 8) and 0xFF).toByte(); it[at + 3] = (relative and 0xFF).toByte()
        }, "collection-relative.ttc")
        assertFalse("偏移按相对读出来的是垃圾，不能放行", FontCmap.coversAll(wrong))
    }

    @Test
    fun realWindowsFontsSplitTheSameWay() {
        val cjk = File("C:/Windows/Fonts/simhei.ttf")
        val latin = File("C:/Windows/Fonts/arial.ttf")
        assumeTrue("缺 simhei.ttf", cjk.exists())
        assumeTrue("缺 arial.ttf", latin.exists())
        assertTrue("黑体必须认中文", FontCmap.coversAll(cjk))
        assertFalse("Arial 不认中文，靠的是 fallback", FontCmap.coversAll(latin))
        println("[font-cmap] simhei=true arial=false（Paint.hasGlyph 对这两个都给 true）")
    }

    /** 手机里那两个 .ttc（adb pull 到 shots/ 后才跑）：face 0 的绝对偏移必须读得对。 */
    @Test
    fun realDeviceCollections() {
        val sans = File("../shots/NotoSansCJK.ttc")
        val serif = File("../shots/NotoSerifCJK.ttc")
        assumeTrue("缺 shots/NotoSansCJK.ttc（adb pull 后才有）", sans.exists())
        assertTrue("黑体集合第 0 面认中文", FontCmap.coversAll(sans))
        if (serif.exists()) {
            println("[font-cmap] serif(NotoSerifCJK) coversAll=" + FontCmap.coversAll(serif) +
                " 中=" + FontCmap.coversAll(serif, intArrayOf(0x4E2D)) +
                " 门=" + FontCmap.coversAll(serif, intArrayOf(0x95E8)))
        }
    }
}
