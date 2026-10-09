package com.bookreader.core.ingest

import com.bookreader.core.model.BookMeta
import com.bookreader.core.model.Chapter
import com.bookreader.core.storage.ChunkOffsetTable
import com.bookreader.core.storage.FileTextSource
import com.bookreader.core.storage.IndexCodec
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.io.InputStreamReader

data class IngestReport(val meta: BookMeta, val millis: Long, val chapterTitlesFound: Int, val charset: String)

/**
 * TXT 入库：一次顺序扫描，产出规范化正文 + 块表 + 章节表。
 *
 * 规范化只做三件事：换行统一成 \n、去掉 BOM、去掉 NUL。除此之外不动正文，
 * 因为 absoluteOffset 是读者的位置锚点，任何"顺手清理"都会让旧位置失效。
 */
class TxtIngestor(private val chunkChars: Int = DEFAULT_CHUNK_CHARS) {

    fun ingest(
        source: File,
        destDir: File,
        title: String = source.nameWithoutExtension,
        author: String = "",
        onProgress: (Double) -> Unit = {},
    ): IngestReport {
        require(chunkChars in MIN_CHUNK_CHARS..MAX_CHUNK_CHARS) { "逻辑块大小要落在 4096~65536 字之间" }
        val startedAt = System.nanoTime()
        val charset = CharsetDetector.detect(source)
        val sourceBytes = source.length()
        destDir.mkdirs()

        val byteStarts = ArrayList<Long>(1024)
        val byteLens = ArrayList<Int>(1024)
        val titles = ArrayList<Pair<Long, String>>(1024)
        val chunkBuf = CharArray(chunkChars)
        val lineBuf = StringBuilder(160)
        val counted = CountingInputStream(BufferedInputStream(FileInputStream(source), 1 shl 16))
        val reader = InputStreamReader(counted, charset)
        val out = BufferedOutputStream(FileOutputStream(File(destDir, IndexCodec.CONTENT)), 1 shl 16)

        var totalChars = 0L
        var bytePos = 0L
        var chunkLen = 0
        var lineStart = 0L
        var atLineStart = true
        var lineTooLong = false
        var skipLf = false
        var firstChar = true

        fun flushChunk() {
            if (chunkLen == 0) return
            val bytes = String(chunkBuf, 0, chunkLen).toByteArray(Charsets.UTF_8)
            out.write(bytes)
            byteStarts.add(bytePos)
            byteLens.add(bytes.size)
            bytePos += bytes.size
            chunkLen = 0
        }

        fun finishLine() {
            if (!lineTooLong) {
                val t = lineBuf.toString().trimEnd(' ', '\t', '\u3000')
                if (t.length in 2..80 && ChapterPattern.matches(t)) titles.add(lineStart to t)
            }
            lineBuf.setLength(0)
            lineTooLong = false
            atLineStart = true
        }

        fun emitChar(c: Char) {
            chunkBuf[chunkLen++] = c
            totalChars++
            if (chunkLen == chunkChars) flushChunk()
        }

        var done = false
        try {
            val buf = CharArray(8192)
            while (true) {
                val n = reader.read(buf)
                if (n < 0) break
                var i = 0
                while (i < n) {
                    var c = buf[i++]
                    if (firstChar) {
                        firstChar = false
                        if (c == '\uFEFF') continue
                    }
                    if (skipLf) {
                        skipLf = false
                        if (c == '\n') continue
                    }
                    if (c == '\r') {
                        skipLf = true
                        c = '\n'
                    }
                    if (c == '\u0000') continue
                    if (c == '\n') {
                        finishLine()
                        emitChar('\n')
                    } else {
                        if (atLineStart) {
                            lineStart = totalChars
                            atLineStart = false
                        }
                        if (!lineTooLong) {
                            if (lineBuf.length < LINE_BUFFER) lineBuf.append(c) else lineTooLong = true
                        }
                        emitChar(c)
                    }
                }
                onProgress(counted.bytesRead.toDouble() / sourceBytes.coerceAtLeast(1L))
            }
            if (!atLineStart) finishLine()
            flushChunk()
            out.flush()
            done = true
        } finally {
            reader.close()
            out.close()
        }
        check(done) { "入库中途失败，目录内容不完整: ${destDir.path}" }
        require(totalChars > 0) { "正文是空的" }

        val chapters = splitOversized(
            buildChapters(titles, totalChars),
            FileTextSource(File(destDir, IndexCodec.CONTENT), totalChars, chunkChars, ChunkOffsetTable(byteStarts.toLongArray(), byteLens.toIntArray())),
            MAX_CHAPTER_CHARS,
        )
        IndexCodec.writeChunkIndex(
            File(destDir, IndexCodec.CHUNK_IDX),
            ChunkOffsetTable(byteStarts.toLongArray(), byteLens.toIntArray())
        )
        IndexCodec.writeChapters(File(destDir, IndexCodec.CHAPTER_IDX), chapters)
        val meta = BookMeta(
            id = destDir.name,
            title = title,
            author = author,
            totalChars = totalChars,
            chunkChars = chunkChars,
            chunkCount = byteStarts.size,
            chapterCount = chapters.size,
            contentBytes = bytePos,
            sourceBytes = sourceBytes,
            sourceCharset = charset.name(),
        )
        IndexCodec.writeManifest(
            File(destDir, IndexCodec.MANIFEST),
            linkedMapOf(
                "id" to meta.id,
                "title" to meta.title,
                "author" to meta.author,
                "totalChars" to meta.totalChars.toString(),
                "chunkChars" to meta.chunkChars.toString(),
                "chunkCount" to meta.chunkCount.toString(),
                "chapterCount" to meta.chapterCount.toString(),
                "contentBytes" to meta.contentBytes.toString(),
                "sourceBytes" to meta.sourceBytes.toString(),
                "sourceCharset" to meta.sourceCharset,
            )
        )
        return IngestReport(meta, (System.nanoTime() - startedAt) / 1_000_000, titles.size, charset.name())
    }

    private fun buildChapters(titles: List<Pair<Long, String>>, totalChars: Long): List<Chapter> {
        val avg = if (titles.isEmpty()) 0L else totalChars / titles.size
        val raw = if (titles.size < 2 || avg < MIN_AVG_CHAPTER_CHARS) {
            listOf(Chapter(0, "全书", 0, totalChars))
        } else {
            val out = ArrayList<Chapter>(titles.size + 1)
            var index = 0
            if (titles[0].first > 0) out.add(Chapter(index++, "前言", 0, titles[0].first))
            for (i in titles.indices) {
                val end = if (i + 1 < titles.size) titles[i + 1].first else totalChars
                out.add(Chapter(index++, titles[i].second, titles[i].first, end))
            }
            out
        }
        return mergeTiny(raw, totalChars, MIN_CHAPTER_CHARS)
    }

    /**
     * 短到不像一章的区间不是章，是噪声（"------------" 这种分隔行会被当成"前言"，
     * 于是打开书第一眼是一屏破折号）。吞掉它，起点让给后面第一个够长的标题。
     */
    private fun mergeTiny(chapters: List<Chapter>, totalChars: Long, minChars: Long): List<Chapter> {
        if (chapters.size < 2) return chapters
        val out = ArrayList<Chapter>(chapters.size)
        var carryStart: Long? = null
        for (c in chapters) {
            val start = carryStart ?: c.startOffset
            if (c.endOffset - start < minChars) {
                carryStart = start
                continue
            }
            out.add(c.copy(index = out.size, startOffset = start))
            carryStart = null
        }
        if (carryStart != null) {
            if (out.isEmpty()) return listOf(Chapter(0, "全书", 0, totalChars))
            out[out.size - 1] = out.last().copy(endOffset = totalChars)
        }
        return out
    }

    /**
     * 排版单元必须有界。标题写得不规范的书（带玉前 2463 章是"001你到底是谁"这种裸编号）
     * 会整本并成一章，那时"翻一页=把整章从头排一遍"就是每页一秒多的卡顿。
     * 所以超长章在段落边界上切成"· 续N"，代价上限从"一章"回到一个固定窗口。
     */
    private fun splitOversized(chapters: List<Chapter>, text: FileTextSource, maxChars: Int): List<Chapter> {
        if (chapters.all { it.charCount <= maxChars }) return chapters
        val out = ArrayList<Chapter>(chapters.size + 16)
        for (c in chapters) {
            if (c.charCount <= maxChars) {
                out.add(c.copy(index = out.size))
                continue
            }
            var start = c.startOffset
            var part = 1
            while (start < c.endOffset) {
                val target = minOf(start + maxChars, c.endOffset)
                // 剩下不到一小段就别再切了，留一个 15 字的"续N"没有意义
                val end = when {
                    c.endOffset - target <= MAX_SPLIT_TAIL -> c.endOffset
                    target >= c.endOffset -> c.endOffset
                    else -> nearestNewline(text, target, start)
                }
                out.add(
                    Chapter(
                        index = out.size,
                        title = if (part == 1) c.title else "${c.title} · 续$part",
                        startOffset = start,
                        endOffset = end,
                    )
                )
                start = end
                part++
            }
        }
        return out
    }

    /** 在 target 之前找最近的换行；找不到（一整段没有换行）就硬切在 target。 */
    private fun nearestNewline(text: FileTextSource, target: Long, notBefore: Long): Long {
        var from = target
        var guard = 0
        while (from > notBefore && guard++ < 64) {
            val take = minOf(WINDOW.toLong(), from - notBefore).toInt()
            val windowStart = from - take
            val window = text.read(windowStart, take)
            val nl = window.lastIndexOf('\n')
            if (nl >= 0) return windowStart + nl + 1
            from = windowStart
        }
        return target
    }

    private class CountingInputStream(val wrapped: InputStream) : InputStream() {
        var bytesRead: Long = 0L
            private set

        override fun read(): Int = wrapped.read().also { if (it >= 0) bytesRead++ }

        override fun read(b: ByteArray, off: Int, len: Int): Int =
            wrapped.read(b, off, len).also { if (it > 0) bytesRead += it }

        override fun available(): Int = wrapped.available()

        override fun close() = wrapped.close()
    }

    companion object {
        const val DEFAULT_CHUNK_CHARS = 16_384
        const val MIN_CHUNK_CHARS = 4_096
        const val MAX_CHUNK_CHARS = 65_536
        private const val LINE_BUFFER = 160
        private const val MIN_AVG_CHAPTER_CHARS = 40L
        private const val WINDOW = 8_192

        /** 单个排版单元的字数上限：约 74 页，够把"排完整章"的代价压回几十毫秒。 */
        const val MAX_CHAPTER_CHARS = 200_000

        /** 切分时尾巴短于这个数就并进上一段。 */
        const val MAX_SPLIT_TAIL = 2_000

        /** 短于这个字数的"章"是噪声，吞进后面那一章。真章最短也有几百字，分隔行/广告行只有十几个字。 */
        const val MIN_CHAPTER_CHARS = 40L
    }
}
