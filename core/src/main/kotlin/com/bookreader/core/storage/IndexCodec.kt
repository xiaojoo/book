package com.bookreader.core.storage

import com.bookreader.core.model.Chapter
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File

data class ChunkOffsetTable(val byteStart: LongArray, val byteLen: IntArray) {
    val size: Int get() = byteStart.size
}

/** 索引一律是定长二进制：千万字书里"读索引"必须是一次顺序读，不能是解析文本。 */
object IndexCodec {
    const val MANIFEST = "manifest.kv"
    const val CONTENT = "content.txt"
    const val CHUNK_IDX = "chunk.idx"
    const val CHAPTER_IDX = "chapter.idx"

    fun writeChunkIndex(file: File, table: ChunkOffsetTable) {
        DataOutputStream(file.outputStream().buffered()).use { out ->
            out.writeInt(table.size)
            for (i in 0 until table.size) {
                out.writeLong(table.byteStart[i])
                out.writeInt(table.byteLen[i])
            }
        }
    }

    fun readChunkIndex(file: File): ChunkOffsetTable {
        DataInputStream(file.inputStream().buffered()).use { ins ->
            val n = ins.readInt()
            require(n >= 0) { "坏索引: ${file.path} count=$n" }
            val s = LongArray(n)
            val l = IntArray(n)
            for (i in 0 until n) {
                s[i] = ins.readLong()
                l[i] = ins.readInt()
            }
            return ChunkOffsetTable(s, l)
        }
    }

    fun writeChapters(file: File, chapters: List<Chapter>) {
        DataOutputStream(file.outputStream().buffered()).use { out ->
            out.writeInt(chapters.size)
            for (c in chapters) {
                out.writeLong(c.startOffset)
                out.writeLong(c.endOffset)
                val t = c.title.toByteArray(Charsets.UTF_8)
                require(t.size <= UShort.MAX_VALUE.toInt()) { "章节标题过长: ${c.title}" }
                out.writeShort(t.size)
                out.write(t)
            }
        }
    }

    fun readChapters(file: File): List<Chapter> {
        DataInputStream(file.inputStream().buffered()).use { ins ->
            val n = ins.readInt()
            return List(n) { i ->
                val start = ins.readLong()
                val end = ins.readLong()
                val tl = ins.readShort().toInt() and 0xFFFF
                val t = ByteArray(tl)
                ins.readFully(t)
                Chapter(i, String(t, Charsets.UTF_8), start, end)
            }
        }
    }

    fun writeManifest(file: File, entries: Map<String, String>) {
        file.writeText(
            entries.entries.joinToString("\n") { (k, v) -> "$k=${v.replace('\n', ' ').replace('\r', ' ')}" } + "\n",
            Charsets.UTF_8
        )
    }

    fun readManifest(file: File): Map<String, String> {
        val out = LinkedHashMap<String, String>()
        file.readText(Charsets.UTF_8).lineSequence().forEach { line ->
            val i = line.indexOf('=')
            if (i > 0) out[line.substring(0, i)] = line.substring(i + 1)
        }
        return out
    }
}
