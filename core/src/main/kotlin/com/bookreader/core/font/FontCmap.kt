package com.bookreader.core.font

import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * 字体自己认不认识某些字：直接读 sfnt 的 cmap 表。
 *
 * 为什么不用 Paint.hasGlyph——它会顺着系统 fallback 链找，任何一款字体都"认识"中文，
 * 223 个文件全过，等于没有筛选。只有字体自己的 cmap 才能区分"它能写中文"和"它靠别人代写"。
 */
object FontCmap {

    /** 常用汉字里挑三个：缺一个就不算能排正文。 */
    val READING_PROBES = intArrayOf(0x4E2D, 0x7684, 0x95E8)

    fun coversAll(fontFile: File, codePoints: IntArray = READING_PROBES): Boolean {
        val cmap = readTable(fontFile, CMAP) ?: return false
        val sub = bestSubtable(cmap) ?: return false
        return codePoints.all { glyphId(sub, it) != 0 }
    }

    private const val CMAP = 0x636D6170 // 'cmap'
    private const val TTCF = 0x74746366 // 'ttcf'

    private fun readTable(fontFile: File, tag: Int): ByteArray? = runCatching {
        RandomAccessFile(fontFile, "r").use { raf ->
            val head = ByteArray(16)
            raf.readFully(head)
            val fontOffset = if (be32(head, 0) == TTCF) be32(head, 12).toLong() else 0L
            raf.seek(fontOffset)
            val dir = ByteArray(12)
            raf.readFully(dir)
            val tables = be16(dir, 4)
            for (i in 0 until tables) {
                val entry = ByteArray(16)
                raf.seek(fontOffset + 12 + i * 16)
                raf.readFully(entry)
                if (be32(entry, 0) != tag) continue
                val len = be32(entry, 12)
                if (len !in 4..(1 shl 24)) continue
                val body = ByteArray(len)
                // 表偏移一律按"从文件开头算"读：ttcf 的每个 face 目录里存的也是绝对偏移，
                // 再叠加 face 自己的起始偏移会读到别处的字节，format 号变成垃圾。
                raf.seek(be32(entry, 8).toLong())
                raf.readFully(body)
                return@use body
            }
            null
        }
    }.getOrNull()

    /** 优先 Unicode 全量（format 12/4），其次 BMP；platform 3 和 0 都认。 */
    private fun bestSubtable(cmap: ByteArray): ByteArray? {
        if (cmap.size < 12 || be16(cmap, 0) != 0) return null
        val count = minOf(be16(cmap, 2).toLong(), ((cmap.size - 4) / 8).toLong()).toInt()
        var bestRank = Int.MAX_VALUE
        var bestOffset = -1
        for (i in 0 until count) {
            val at = 4 + i * 8
            if (at + 8 > cmap.size) break
            val platform = be16(cmap, at)
            val encoding = be16(cmap, at + 2)
            val off = be32(cmap, at + 4)
            if (off < 0 || off + 2 > cmap.size) continue
            val rank = when {
                platform == 3 && encoding == 10 -> 0
                platform == 0 && (encoding == 4 || encoding == 6 || encoding == 10) -> 0
                platform == 0 && encoding == 3 -> 1
                platform == 3 && encoding == 1 -> 1
                platform == 0 && encoding == 0 -> 2
                platform == 3 && encoding == 0 -> 2
                platform == 1 -> 3
                else -> 4
            }
            if (rank < bestRank) {
                bestRank = rank
                bestOffset = off
            }
        }
        return if (bestOffset < 0) null else cmap.copyOfRange(bestOffset, cmap.size)
    }

    private fun glyphId(sub: ByteArray, codePoint: Int): Int = when (be16(sub, 0)) {
        0 -> glyphFormat0(sub, codePoint)
        4 -> glyphFormat4(sub, codePoint)
        6 -> glyphFormat6(sub, codePoint)
        12, 13 -> glyphFormat12(sub, codePoint)
        else -> 0
    }

    private fun glyphFormat0(sub: ByteArray, codePoint: Int): Int {
        if (codePoint > 255 || sub.size < 258) return 0
        return sub[2 + codePoint].toInt() and 0xFF
    }

    private fun glyphFormat4(sub: ByteArray, codePoint: Int): Int {
        if (sub.size < 14 || codePoint > 0xFFFF) return 0
        val segCount = be16(sub, 6) / 2
        val ends = 14
        val starts = ends + segCount * 2 + 2
        val deltas = starts + segCount * 2
        val ranges = deltas + segCount * 2
        if (ranges + segCount * 2 > sub.size) return 0
        for (i in 0 until segCount) {
            if (codePoint > be16(sub, ends + i * 2)) continue
            if (codePoint < be16(sub, starts + i * 2)) return 0
            val delta = be16(sub, deltas + i * 2)
            val range = be16(sub, ranges + i * 2)
            if (range == 0) return (codePoint + delta) and 0xFFFF
            val at = ranges + i * 2 + range + (codePoint - be16(sub, starts + i * 2)) * 2
            if (at + 1 >= sub.size) return 0
            val g = be16(sub, at)
            return if (g == 0) 0 else (g + delta) and 0xFFFF
        }
        return 0
    }

    private fun glyphFormat6(sub: ByteArray, codePoint: Int): Int {
        if (sub.size < 10) return 0
        val first = be16(sub, 6)
        val count = be16(sub, 8)
        val idx = codePoint - first
        if (idx < 0 || idx >= count) return 0
        val at = 10 + idx * 2
        return if (at + 1 >= sub.size) 0 else be16(sub, at)
    }

    private fun glyphFormat12(sub: ByteArray, codePoint: Int): Int {
        if (sub.size < 16) return 0
        val groups = be32(sub, 12)
        for (i in 0 until groups) {
            val at = 16 + i * 12
            if (at + 12 > sub.size) return 0
            if (codePoint in be32(sub, at)..be32(sub, at + 4)) return be32(sub, at + 8)
        }
        return 0
    }

    private fun be16(b: ByteArray, at: Int): Int =
        ((b[at].toInt() and 0xFF) shl 8) or (b[at + 1].toInt() and 0xFF)

    private fun be32(b: ByteArray, at: Int): Int =
        ByteBuffer.wrap(b, at, 4).order(ByteOrder.BIG_ENDIAN).int
}
