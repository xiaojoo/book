package com.bookreader.core.search

import com.bookreader.core.index.ChapterIndex
import com.bookreader.core.storage.ChunkCache

data class SearchHit(val chapterIndex: Int, val offset: Long, val snippet: String)

data class ScanResult(
    val query: String,
    val hits: List<SearchHit>,
    val chunksScanned: Int,
    val charsScanned: Long,
    val millis: Long,
    val truncated: Boolean,
    val stopped: Boolean,
)

/**
 * 全文检索第一版走"逐块扫描"：一次顺序读完整本书的块，不建倒排索引。
 * 块本来就是缓存单位，所以扫完还能把热块留在缓存里；量出来够快就先这么用。
 */
class ChunkScanner(
    private val cache: ChunkCache,
    private val chapters: ChapterIndex,
    private val snippetRadius: Int = 40,
) {

    fun scan(
        query: String,
        limit: Int = 400,
        ignoreCase: Boolean = false,
        onProgress: (Double) -> Unit = {},
        shouldStop: () -> Boolean = { false },
    ): ScanResult {
        val q = query.trim()
        val started = System.nanoTime()
        if (q.isEmpty()) return ScanResult(q, emptyList(), 0, 0, elapsed(started), false, false)

        val hits = ArrayList<SearchHit>(64)
        val carry = q.length - 1
        var prevTail = ""
        var chars = 0L
        var stopped = false
        var i = 0
        val n = cache.source.chunkCount
        while (i < n) {
            if (shouldStop()) {
                stopped = true
                break
            }
            val text = cache.chunk(i)
            chars += text.length
            val searchIn = if (prevTail.isEmpty()) text else prevTail + text
            val base = i.toLong() * cache.source.chunkChars - prevTail.length
            var idx = searchIn.indexOf(q)
            while (idx >= 0) {
                // 只有"起点落在上一块之外"的跨块命中才算这次的新命中，否则上一轮已经报过
                val crossesBoundary = prevTail.isNotEmpty() && idx < prevTail.length
                if (!crossesBoundary || idx + q.length > prevTail.length) {
                    val offset = base + idx
                    if (offset >= 0) {
                        hits.add(hit(q, offset, ignoreCase))
                        if (hits.size >= limit) {
                            return ScanResult(q, hits, i + 1, chars, elapsed(started), true, false)
                        }
                    }
                }
                idx = searchIn.indexOf(q, idx + 1)
            }
            prevTail = if (carry > 0 && text.length >= carry) text.substring(text.length - carry) else text
            i++
            onProgress(i.toDouble() / n)
        }
        return ScanResult(q, hits, n, chars, elapsed(started), false, stopped)
    }

    private fun hit(query: String, offset: Long, ignoreCase: Boolean): SearchHit {
        val from = (offset - snippetRadius).coerceAtLeast(0L)
        val count = minOf((snippetRadius * 2 + query.length).toLong(), cache.source.totalChars - from).toInt()
        val snippet = cache.slice(from, count).replace('\n', ' ')
        return SearchHit(chapters.indexOf(offset), offset, snippet)
    }

    private fun elapsed(startedNanos: Long): Long = (System.nanoTime() - startedNanos) / 1_000_000
}
