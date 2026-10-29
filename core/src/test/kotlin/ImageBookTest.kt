package com.bookreader.core

import com.bookreader.core.ingest.ImageBookFile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * 「图片本」解包：cbz 和 Kindle 那一族把整话扫描页装进 epub 的件。
 *
 * 夹具全是测试里现造的 —— 造得出来才说明认的是格式，不是某一个站子的那一本。
 * 页序是这一族的命门：跳页的漫画人眼一扫就看出来了，所以每个构型都给一条该红的判据。
 */
class ImageBookTest {

    private fun jpg(mark: String) = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte()) + mark.toByteArray(Charsets.UTF_8)
    private fun png(mark: String) = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47) + mark.toByteArray(Charsets.UTF_8)

    private fun zip(file: File, entries: Map<String, ByteArray>) {
        ZipOutputStream(file.outputStream().buffered()).use { z ->
            entries.forEach { (name, body) ->
                z.putNextEntry(ZipEntry(name))
                z.write(body)
                z.closeEntry()
            }
        }
    }

    private fun book(entries: Map<String, ByteArray>): File {
        val f = File.createTempFile("ibfixture", ".zip")
        zip(f, entries)
        return f
    }

    private fun epubText(page: String) =
        """<html><head><title>话</title></head><body>$page</body></html>""".toByteArray(Charsets.UTF_8)

    private fun epub(docs: Map<String, String>, images: Map<String, ByteArray>): Map<String, ByteArray> {
        val opf = StringBuilder("""<?xml version="1.0"?><package xmlns="http://www.idpf.org/2007/opf" version="3.0"><metadata><title>图片本</title></metadata><manifest>""")
        val refs = StringBuilder()
        docs.keys.forEachIndexed { i, id ->
            opf.append("""<item id="$id" href="Text/$id.xhtml" media-type="application/xhtml+xml"/>""")
            refs.append("""<itemref idref="$id"/>""")
        }
        opf.append("</manifest><spine>").append(refs).append("</spine></package>")
        val out = LinkedHashMap<String, ByteArray>()
        out["mimetype"] = "application/epub+zip".toByteArray()
        out["META-INF/container.xml"] =
            """<?xml version="1.0"?><container><rootfiles><rootfile full-path="OEBPS/content.opf" media-type="application/oebps-package+xml"/></rootfiles></container>""".toByteArray(Charsets.UTF_8)
        out["OEBPS/content.opf"] = opf.toString().toByteArray(Charsets.UTF_8)
        docs.forEach { (id, page) -> out["OEBPS/Text/$id.xhtml"] = epubText(page) }
        images.forEach { (name, bytes) -> out["OEBPS/images/$name"] = bytes }
        return out
    }

    @Test
    fun cbzSortsPagesByNumberNotByDictionary() {
        val f = book(
            linkedMapOf(
                "01.jpg" to jpg("第一页"),
                "02.jpg" to jpg("第二页"),
                "10.jpg" to jpg("第十页"),
                "cover.png" to png("封面"),
                "notes.txt" to "这不是图".toByteArray(),
                "__MACOSX/01.jpg" to jpg("苹果的垃圾"),
                ".DS_Store" to "也是垃圾".toByteArray(),
            )
        )
        ImageBookFile.open(f).use { b ->
            assertNotNull(b)
            assertEquals(listOf("01.jpg", "02.jpg", "10.jpg", "cover.png"), b!!.pages)
            assertTrue("第 10 页排到了第 2 页前面：那本漫画会碎成一串跳页", b.pages.indexOf("10.jpg") > b.pages.indexOf("02.jpg"))
            assertEquals("第一页", String(b.bytes(0)!!.drop(4).toByteArray(), Charsets.UTF_8))
        }
        f.delete()
    }

    @Test
    fun aHundredAndSixtyPageVolumeReadsInTheRightOrder() {
        val entries = LinkedHashMap<String, ByteArray>()
        (1..160).forEach { entries["%03d.jpg".format(it)] = jpg("p$it") }
        val f = book(entries)
        ImageBookFile.open(f).use { b ->
            assertEquals(160, b!!.pageCount)
            assertEquals("001.jpg", b.pages.first())
            assertEquals("160.jpg", b.pages.last())
            assertEquals("p42", String(b.bytes(41)!!.drop(4).toByteArray(), Charsets.UTF_8))
        }
        f.delete()
    }

    @Test
    fun epubFollowsSpineWhenNamesDisagree() {
        // 名字是 zz / aa，spine 才是阅读顺序；第二话那篇引了两张：一张和 xhtml 同层（带真空格）、
        // 一张在上一层的 images/ 里 —— 两种相对路径都要还原成条目名才取得到
        val entries = LinkedHashMap<String, ByteArray>(
            epub(
                linkedMapOf(
                    "c1" to """<img src="../images/zz.jpg"/>""",
                    "c2" to """<p>两张</p><img src="img/中 间.jpg" alt="带空格"><image xlink:href="../images/aa.jpg"/>""",
                    "c3" to """<img src="../images/aa.jpg"/>""",
                ),
                linkedMapOf("zz.jpg" to jpg("第一话"), "aa.jpg" to jpg("第三话")),
            )
        )
        entries["OEBPS/Text/img/中 间.jpg"] = png("第二话前半")
        val f = book(entries)
        ImageBookFile.open(f).use { b ->
            assertNotNull(b)
            assertEquals(
                "spine 说的顺序，不是名字",
                listOf("OEBPS/images/zz.jpg", "OEBPS/Text/img/中 间.jpg", "OEBPS/images/aa.jpg"),
                b!!.pages
            )
            assertEquals("第三话", String(b.bytes(2)!!.drop(4).toByteArray(), Charsets.UTF_8))
            assertEquals("第三话被两篇引到，只留一次", 3, b.pages.size)
        }
        f.delete()
    }

    @Test
    fun spineThatPointsAtNothingFallsBackToNames() {
        val entries = epub(
            linkedMapOf("c1" to "<p>这一篇没有图</p>", "c2" to "<p>也没有</p>"),
            linkedMapOf("10.jpg" to jpg("十"), "2.jpg" to jpg("二")),
        )
        val f = book(entries)
        ImageBookFile.open(f).use { b ->
            assertEquals(listOf("OEBPS/images/2.jpg", "OEBPS/images/10.jpg"), b!!.pages)
        }
        f.delete()
    }

    @Test
    fun bytesThatAreNotAnImageAreNotCountedAsAPage() {
        val f = book(
            linkedMapOf(
                "01.jpg" to jpg("第一页"),
                "02.jpg" to "<html>站子给的不是图</html>".toByteArray(),
                "03.jpg" to jpg("第三页"),
            )
        )
        ImageBookFile.open(f).use { b ->
            assertEquals("「名字像图、字节不是图」不能算一页：那样界面上报的页数比屏上能翻动的多",
                listOf("01.jpg", "03.jpg"), b!!.pages)
            assertEquals(2, b.pageCount)
        }
        f.delete()
    }

    @Test
    fun aZipWithoutPagesIsNotAnImageBook() {
        val f = book(linkedMapOf("readme.txt" to "一本纯文字的书".toByteArray()))
        assertNull(ImageBookFile.open(f))
        f.delete()
    }

    @Test
    fun openingFromBytesLeavesNoTempFile() {
        val bytes = LinkedHashMap<String, ByteArray>()
        (1..5).forEach { bytes["p$it.jpg"] = jpg("第${it}页") }
        val f = book(bytes)
        val packed = f.readBytes()
        f.delete()
        val before = File(System.getProperty("java.io.tmpdir")).listFiles { x -> x.name.startsWith("imagebook") }?.size ?: 0
        val b = ImageBookFile.open(packed)
        assertNotNull(b)
        assertEquals(5, b!!.pageCount)
        b.close()
        val after = File(System.getProperty("java.io.tmpdir")).listFiles { x -> x.name.startsWith("imagebook") }?.size ?: 0
        assertEquals("close() 要把为了解 zip 落下的那份临时件带走：一盘漫画几百卷就是几百份没人删的件", before, after)
    }

    @Test
    fun notAZipAtAllOpensAsNull() {
        assertNull(ImageBookFile.open("<html>这不是包</html>".toByteArray()))
        assertNull(ImageBookFile.open(File("不存在的件.cbz")))
    }
}
