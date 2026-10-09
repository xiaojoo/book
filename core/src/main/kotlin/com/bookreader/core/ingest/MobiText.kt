package com.bookreader.core.ingest

import java.io.File
import java.io.RandomAccessFile

/**
 * MOBI / AZW3 抽正文：壳是 PalmDB（Palm 数据库）+ 一串文本记录，文本记录解压后是 HTML 标记，
 * 所以抽出来还是"一行一段"的 txt，再交给 [TxtIngestor] —— 章节、偏移、分页全复用。
 *
 * 解压规则是照 KindleUnpack 的 PalmdocReader（Calibre 用的那一份）逐条对过的，不是凭印象：
 * 0x01~0x08 是"照抄后面 c 个字节"，0xC0~0xFF 是"空格 + 去掉最高位的字面"，
 * 0x80~0xBF 才是距离/长度对。这三条记错任何一条都不会报错，只会安静地出乱码。
 *
 * 只支持不压缩和 PalmDOC LZ77。HUFF/CDIC 和 DRM 是**明确拒绝**的：
 * HUFF 要按另两条记录里的字典解，读错了不报错；DRM 的正文是密文，抽出来必然乱码。
 */
class MobiText {

    /** null = 能读；否则是一句给人看的话，说清为什么读不了。 */
    fun rejectReason(source: File): String? = runCatching {
        RandomAccessFile(source, "r").use { raf ->
            if (raf.length() < 86) return "文件比一个 PalmDB 头还短，不是 mobi/azw3"
            val type = readTag(raf, 60)
            val creator = readTag(raf, 64)
            if (type != "BOOK" || creator != "MOBI") {
                // PalmDB 也被别的软件用，认不出这两个四字节就当不是
                return "不是 PalmDB 电子书（type=$type creator=$creator）"
            }
            val records = readU16(raf, 76)
            if (records < 2) return "记录数为 $records，读不到文本"
            val rec0 = readU32(raf, 78)
            val compression = readU16(raf, rec0)
            val encryption = readU16(raf, rec0 + 12)
            when {
                encryption != 0 -> "这本是 DRM 加密的（encryptionType=$encryption）：正文是密文，我们不做解密"
                compression == HUFF -> "这本用的是 HUFF/CDIC 压缩，暂未实现（用 Calibre 转成 epub 再导入最快）"
                compression != NONE && compression != LZ77 ->
                    "未知的压缩方式 $compression（只支持不压缩和 PalmDOC LZ77）"
                readU32(raf, rec0 + 0x50) <= 1 -> "MOBI 头里的 firstNonText=$rec0 不合理，找不到文本记录"
                else -> null
            }
        }
    }.getOrElse { "读不出 mobi/azw3 的头：${it.javaClass.simpleName}" }

    fun supports(source: File): Boolean = rejectReason(source) == null

    /**
     * 解压文本记录 → 剥标签 → 一段一行写进 [dest]。
     * @return null = [rejectReason] 说的那种读不了。
     */
    fun extractToText(source: File, dest: File): String? {
        rejectReason(source)?.let {
            println("[mobi] ${source.name} 读不了：$it")
            return null
        }
        return runCatching {
            RandomAccessFile(source, "r").use { raf ->
                val records = readU16(raf, 76)
                val starts = LongArray(records) { i -> readU32(raf, 78L + i * 8) }
                val ends = LongArray(records) { i -> if (i + 1 < records) starts[i + 1] else raf.length() }
                val rec0 = readAt(raf, starts[0], (ends[0] - starts[0]).toInt())
                val encoding = beInt(rec0, 0x1C)
                val textLength = beInt(rec0, 0x04)
                // 文本记录 = 1 .. firstNonText-1。AZW3 是 MOBI6+KF8 双份，读的就是它自带的 MOBI6 那份
                val last = (beInt(rec0, 0x50) - 1).coerceIn(1, records - 1)
                val html = StringBuilder(if (textLength in 1..(1 shl 26)) textLength else 1 shl 20)
                for (i in 1..last) {
                    val body = readAt(raf, starts[i], (ends[i] - starts[i]).toInt())
                    // 每条文本记录开头 2 字节又是压缩方式，解压前要去掉
                    val payload = if (body.size > 2) body.copyOfRange(2, body.size) else ByteArray(0)
                    html.append(String(if (beU16(body, 0) == LZ77) lz77(payload) else payload, charsetOf(encoding)))
                }
                val paras = EbookText().paragraphsOf(html.toString())
                dest.bufferedWriter(Charsets.UTF_8).use { w ->
                    for (p in paras) {
                        w.write(p)
                        w.write("\n")
                    }
                }
                println("[mobi] ${source.name} 记录=1..$last html=${html.length} 编码=${charsetOf(encoding).name()} 段=${paras.size}")
                "${paras.size} 段 / ${html.length} 字"
            }
        }.onFailure { println("[mobi] 抽取异常 ${it.javaClass.name}: ${it.message}") }.getOrNull()
    }

    private fun charsetOf(codepage: Int) = if (codepage == 125 || codepage == 65001) Charsets.UTF_8 else Charsets.ISO_8859_1

    companion object {
        private const val NONE = 1
        private const val LZ77 = 2
        private const val HUFF = 17437

        /**
         * PalmDOC LZ77。距离 11 位、长度 3 位（+3），最高位那 1 bit 是"这是距离/长度对"的标记：
         * `distance = (pair >> 3) and 0x7FF`、`length = (pair and 7) + 3`。
         */
        internal fun lz77(input: ByteArray): ByteArray {
            val out = ArrayList<Byte>(input.size * 2)
            var i = 0
            while (i < input.size) {
                val c = input[i].toInt() and 0xFF
                i++
                when {
                    c in 1..8 -> {
                        // "照抄后面 c 个字节"：高位字节（UTF-8 的中文字）就是这么进去的
                        val n = minOf(c, input.size - i)
                        for (k in 0 until n) out += input[i + k]
                        i += n
                    }
                    c < 0x80 -> out += c.toByte()
                    c < 0xC0 -> {
                        if (i >= input.size) continue
                        val pair = (c shl 8) or (input[i].toInt() and 0xFF)
                        i++
                        val distance = (pair ushr 3) and 0x7FF
                        val length = (pair and 0x07) + 3
                        val start = out.size - distance
                        if (distance <= 0 || start < 0) {
                            // 坏数据：抄字面而不是崩，宁可少几段
                            out += c.toByte(); out += input[i - 1]
                        } else {
                            for (k in 0 until length) out += out[start + k]
                        }
                    }
                    else -> {
                        out += ' '.code.toByte()
                        out += (c xor 0x80).toByte()
                    }
                }
            }
            return ByteArray(out.size) { out[it] }
        }

        private fun readTag(raf: RandomAccessFile, at: Long): String =
            String(readAt(raf, at, 4), Charsets.US_ASCII)

        private fun readU16(raf: RandomAccessFile, at: Long): Int {
            raf.seek(at)
            return (raf.readByte().toInt() and 0xFF) shl 8 or (raf.readByte().toInt() and 0xFF)
        }

        private fun readU32(raf: RandomAccessFile, at: Long): Long {
            raf.seek(at)
            var v = 0L
            repeat(4) { v = v shl 8 or (raf.readByte().toInt().toLong() and 0xFF) }
            return v
        }

        private fun readAt(raf: RandomAccessFile, at: Long, len: Int): ByteArray {
            raf.seek(at)
            return ByteArray(len.coerceAtLeast(0)).also { raf.readFully(it) }
        }

        private fun beU16(b: ByteArray, at: Int): Int =
            if (b.size < at + 2) 0 else (b[at].toInt() and 0xFF) shl 8 or (b[at + 1].toInt() and 0xFF)

        private fun beInt(b: ByteArray, at: Int): Int =
            if (b.size < at + 4) 0 else (b[at].toInt() and 0xFF) shl 24 or
                (b[at + 1].toInt() and 0xFF) shl 16 or
                (b[at + 2].toInt() and 0xFF) shl 8 or
                (b[at + 3].toInt() and 0xFF)
    }
}
