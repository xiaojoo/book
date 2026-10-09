package com.bookreader.core.ingest

import java.io.File
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.Charset
import java.nio.charset.StandardCharsets

/**
 * 中文小说的实际编码就是 UTF-8 和 GB18030 两家。判错会整本乱码，所以只能"严格解一遍"来判。
 *
 * 注意 Charset.decode(ByteBuffer) 会把非法字节**替换**掉而不是抛异常，拿它当"严格判"永远是绿的；
 * 必须用 newDecoder()（默认对畸形输入报错）。
 */
object CharsetDetector {

    private val GB18030: Charset = Charset.forName("GB18030")

    fun detect(file: File): Charset {
        val head = ByteArray(minOf(65_536L, file.length()).toInt())
        val read = file.inputStream().use { ins -> ins.read(head) }
        if (read <= 0) return StandardCharsets.UTF_8
        val bytes = head.copyOf(read)
        if (bytes.size >= 3 && bytes[0] == 0xEF.toByte() && bytes[1] == 0xBB.toByte() && bytes[2] == 0xBF.toByte()) {
            return StandardCharsets.UTF_8
        }
        if (bytes.size >= 2 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xFE.toByte()) return StandardCharsets.UTF_16LE
        if (bytes.size >= 2 && bytes[0] == 0xFE.toByte() && bytes[1] == 0xFF.toByte()) return StandardCharsets.UTF_16BE
        // 缓冲区末尾可能正好切在一个多字节序列中间，退让最多 3 字节再判
        var end = bytes.size
        var backedOff = 0
        while (end > 0 && backedOff <= 3) {
            if (strictlyDecodable(StandardCharsets.UTF_8, bytes, end)) return StandardCharsets.UTF_8
            end--
            backedOff++
        }
        return if (strictlyDecodable(GB18030, bytes, bytes.size)) GB18030 else StandardCharsets.ISO_8859_1
    }

    private fun strictlyDecodable(charset: Charset, bytes: ByteArray, length: Int): Boolean = try {
        charset.newDecoder().decode(ByteBuffer.wrap(bytes, 0, length))
        true
    } catch (_: CharacterCodingException) {
        false
    }
}
