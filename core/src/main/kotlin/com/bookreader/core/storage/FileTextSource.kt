package com.bookreader.core.storage

import java.io.File
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.StandardOpenOption

/**
 * 按逻辑块随机访问正文文件。块边界是"字符"下标，所以每张表额外记住该块的起始字节位置，
 * 这样 absoluteOffset → (块号, 块内下标) 是 O(1)，落盘读取只用字节偏移。
 */
class FileTextSource(
    private val contentFile: File,
    override val totalChars: Long,
    override val chunkChars: Int,
    private val table: ChunkOffsetTable,
) : TextSource {

    override val chunkCount: Int get() = table.size

    private val channel: FileChannel = FileChannel.open(contentFile.toPath(), StandardOpenOption.READ)

    override fun decodeChunk(index: Int): String {
        val len = table.byteLen[index]
        if (len == 0) return ""
        val bytes = ByteArray(len)
        val buf = ByteBuffer.wrap(bytes)
        var pos = table.byteStart[index]
        while (buf.hasRemaining()) {
            val n = channel.read(buf, pos)
            if (n <= 0) break
            pos += n
        }
        return String(bytes, 0, buf.position(), Charsets.UTF_8)
    }

    override fun chunkByteLen(index: Int): Int = table.byteLen[index]

    override fun read(start: Long, count: Int): String {
        if (count <= 0 || start >= totalChars) return ""
        val sb = StringBuilder(minOf(count, 1 shl 22))
        var index = (start / chunkChars).toInt()
        var inner = (start - index.toLong() * chunkChars).toInt()
        var remaining = count
        while (remaining > 0 && index < chunkCount) {
            val chunk = decodeChunk(index)
            val take = minOf(remaining, chunk.length - inner)
            if (take > 0) {
                sb.append(chunk, inner, inner + take)
                remaining -= take
            }
            index++
            inner = 0
        }
        return sb.toString()
    }

    override fun close() {
        channel.close()
    }
}
