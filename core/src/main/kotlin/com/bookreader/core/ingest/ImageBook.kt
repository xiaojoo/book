package com.bookreader.core.ingest

import java.io.Closeable
import java.io.File
import java.net.URLDecoder
import java.util.zip.ZipFile

/**
 * 一本"图片书"：`.cbz`（一排扫描页的 zip）和 Kindle 那一族把整话图片装进 `.epub` 的那些。
 *
 * 它和 [EbookText] 是同一个 zip 的两种读法 —— 那边抽**文字**，这一边抽**页**。两种都要：
 * 网文 epub 抽得出段落、抽不出图；漫画 epub 反过来。谁先出东西谁说了算，分岔在导入那一头。
 *
 * 页序两件事分开定：
 * - **epub 读 spine**（`META-INF/container.xml` → OPF → `<itemref>` 的顺序），因为那种包里的文件名
 *   经常是 `img0001.jpg` 对着第 40 话，按名排会乱；
 * - **cbz 按名字自然序**（`2.jpg` 排在 `10.jpg` 前面）：做包的人就是按页号命名的，
 *   纯字典序会把第 10 页插到第 2 页前面 —— 一本 160 页的漫画会碎成一串跳页。
 *
 * 一次只解一张：[bytes] 按需从 zip 里取一条，读完就丢。整本摊平进内存的话，一卷 38MB 的件解出来
 * 是几十张位图一起挂在堆上 —— 手机上那不是慢，是被系统杀掉。
 */
class ImageBookFile private constructor(
    private val zip: ZipFile,
    /** 页序：每一项是包里的条目名。 */
    val pages: List<String>,
    private val temp: File?,
) : Closeable {

    /** 第 [index] 页的字节；条目没了或开头不是图片魔数就回 null（调用方把这一页算作缺，不骗界面）。 */
    fun bytes(index: Int): ByteArray? {
        val name = pages.getOrNull(index) ?: return null
        val entry = zip.getEntry(name) ?: return null
        val b = runCatching { zip.getInputStream(entry).use { it.readBytes() } }.getOrNull() ?: return null
        return if (isImage(b)) b else null
    }

    /** 认出来的总页数 —— 0 在这一类上不会出现（[open] 直接回 null）。 */
    val pageCount: Int get() = pages.size

    /** 关掉时把 [open] 为了解 zip 落下的那份临时件一起删掉：Windows 上开着句柄删不掉，所以只能留到这里。 */
    override fun close() {
        runCatching { zip.close() }
        temp?.delete()
    }

    companion object {

        /** 认不出是图片包就回 null（不是抛：导入那一头还有一条文字路要走）。 */
        fun open(file: File): ImageBookFile? = open(file, null)

        /**
         * 从内存里那份字节打开（下载层拿到的是整本字节，还没落盘）。
         * `ZipFile` 要随机访问 —— 中央目录在**文件末尾**，流式解读不到，所以必须先落成一份临时件。
         */
        fun open(bytes: ByteArray): ImageBookFile? {
            if (bytes.size < 4 || bytes[0] != 'P'.code.toByte() || bytes[1] != 'K'.code.toByte()) return null
            val tmp = runCatching {
                File.createTempFile("imagebook", ".zip").apply { writeBytes(bytes) }
            }.getOrNull() ?: return null
            val book = open(tmp, tmp)
            if (book == null) tmp.delete()
            return book
        }

        private fun open(file: File, temp: File?): ImageBookFile? {
            if (!file.isFile) return null
            val z = runCatching { ZipFile(file) }.getOrNull() ?: return null
            val names = runCatching { z.entries().toList().map { it.name } }.getOrDefault(emptyList())
            if (names.isEmpty()) { runCatching { z.close() }; temp?.delete(); return null }
            val ordered = if (names.any { it == "META-INF/container.xml" }) spineOrder(z, names) else imageEntries(names)
            // 认页的时候就顺手把"名字像图、字节不是图"的那几条剔掉：留下的每一条都读得开，
            // 界面上那句"共 160 页"才等于屏上真能翻动的页数（下载层同一族判据：占位灰图不算抓到）
            val real = ordered.filter { headIsImage(z, it) }
            if (real.isEmpty()) { runCatching { z.close() }; temp?.delete(); return null }
            return ImageBookFile(z, real, temp)
        }

        /**
         * 只读条目开头那几个字节 —— 一卷 38MB 的包解一百六十页，这里多花的是一百六十次四字节。
         *
         * 手写这四字节而不 `readNBytes`：SDK 那张 api-versions.xml 里 `InputStream.readNBytes` 三条签名
         * 全写着 since=33，而 minSdk 26 —— 这一模块编的是 JDK 21 的 bootclasspath，编得过、手机上必崩
         * （搜书那一格就为 `URLEncoder.encode(String, Charset)` 同一个跟头翻过一次）。
         */
        private fun headIsImage(z: ZipFile, name: String): Boolean {
            val e = z.getEntry(name) ?: return false
            val buf = ByteArray(4)
            var got = 0
            val ok = runCatching {
                z.getInputStream(e).use { ins ->
                    while (got < 4) {
                        val r = ins.read(buf, got, 4 - got)
                        if (r < 0) break
                        got += r
                    }
                }
                isImage(if (got == 4) buf else buf.copyOf(got))
            }.getOrDefault(false)
            return ok
        }

        /** epub：按 spine 的 XHTML 顺序，取每一篇里 `<img>` / SVG `<image>` 指到的那几张。 */
        private fun spineOrder(z: ZipFile, names: List<String>): List<String> {
            val docs = runCatching { EbookText().epubSpine(z) }.getOrNull()
            if (docs.isNullOrEmpty()) return imageEntries(names)
            val have = names.toHashSet()
            val out = ArrayList<String>(docs.size * 4)
            for (doc in docs) {
                val text = runCatching { z.getInputStream(doc).readBytes().toString(Charsets.UTF_8) }.getOrNull() ?: continue
                for (ref in imageRefs(text)) {
                    val path = resolveAgainst(doc.name, ref)
                    if (path != null && path in have) out += path
                }
            }
            // spine 一张图都没指到：那种包把图挂在 spine 外面（Kindle 转换出来的那一族常见），退回按名排
            return if (out.isEmpty()) imageEntries(names) else out.distinct()
        }

        /** 一篇 XHTML 里被引到的图，按它们在文档里出现的先后。 */
        private fun imageRefs(markup: String): List<String> =
            Regex("""<(?:img|image)\b[^>]*?(?:src|xlink:href|href)="([^"]+)"""", RegexOption.IGNORE_CASE)
                .findAll(markup).map { it.groupValues[1] }.toList()

        /** 图地址按 XHTML 自己所在那一层补全：`../images/p1.jpg`、`%20`、`#` 后面的锚都要还原成条目名。 */
        private fun resolveAgainst(docPath: String, ref: String): String? {
            val raw = ref.trim()
            if (raw.isEmpty() || raw.startsWith("data:") || raw.contains(":/") || raw.startsWith("//")) return null
            val clean = raw.substringBefore('#').substringBefore('?')
            if (clean.isEmpty()) return null
            val decoded = runCatching { URLDecoder.decode(clean, "UTF-8") }.getOrDefault(clean)
            val dir = docPath.substringBeforeLast('/', "")
            val joined = when {
                decoded.startsWith("/") -> decoded.removePrefix("/")
                dir.isEmpty() -> decoded
                else -> "$dir/$decoded"
            }
            val parts = ArrayList<String>()
            for (p in joined.split('/')) when (p) {
                "", "." -> {}
                ".." -> if (parts.isNotEmpty()) parts.removeAt(parts.size - 1)
                else -> parts += p
            }
            return parts.joinToString("/").ifEmpty { null }
        }

        /** 一排扫描页（cbz，和认不出 spine 的包）：只收图片后缀，按名字自然序。 */
        private fun imageEntries(names: List<String>): List<String> =
            names.filter { it.substringAfterLast('/', it).contains('.') }
                .filter { it.substringAfterLast('.').lowercase() in IMAGE_EXT }
                .filterNot { it.startsWith("__MACOSX") || it.substringAfterLast('/').startsWith(".") }
                .sortedWith { a, b -> naturalCompare(a, b) }

        /** 自然序：遇数字段按数值比（先去前导零再比长度，最后比字面），其余按字符。 */
        private fun naturalCompare(a: String, b: String): Int {
            var i = 0
            var j = 0
            while (i < a.length && j < b.length) {
                val ca = a[i]
                val cb = b[j]
                if (ca.isDigit() && cb.isDigit()) {
                    val na = a.substring(i).takeWhile { it.isDigit() }
                    val nb = b.substring(j).takeWhile { it.isDigit() }
                    val sa = na.trimStart('0')
                    val sb = nb.trimStart('0')
                    val d = if (sa.length != sb.length) sa.length - sb.length else sa.compareTo(sb)
                    if (d != 0) return d
                    i += na.length
                    j += nb.length
                    continue
                }
                if (ca != cb) return ca.compareTo(cb)
                i++
                j++
            }
            return (a.length - i) - (b.length - j)
        }

        /** 开头那几个魔数：这一页到底是不是一张图（和下载层那一条同一族判据）。 */
        fun isImage(bytes: ByteArray): Boolean {
            if (bytes.size < 4) return false
            fun p(vararg v: Int) = v.indices.all { bytes[it] == v[it].toByte() }
            return bytes[0] == 0xFF.toByte() && bytes[1] == 0xD8.toByte() ||  // JPEG
                p(0x89, 0x50, 0x4E, 0x47) ||   // PNG
                p(0x47, 0x49, 0x46, 0x38) ||   // GIF
                bytes[0] == 'R'.code.toByte() && bytes[1] == 'I'.code.toByte() && bytes[2] == 'F'.code.toByte() ||  // WEBP
                bytes[0] == 'B'.code.toByte() && bytes[1] == 'M'.code.toByte()   // BMP
        }

        private val IMAGE_EXT = setOf("jpg", "jpeg", "png", "gif", "webp", "bmp")
    }
}
