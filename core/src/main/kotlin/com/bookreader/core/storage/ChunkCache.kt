package com.bookreader.core.storage

/**
 * 文本层缓存：内存里只留最近若干逻辑块（设计文档第 8 节的 Text Cache）。
 * 命中数/解码字节数/常驻字数是故意记下来的——"不整体加载"这句话只能靠这三个数否证。
 */
class ChunkCache(
    val source: TextSource,
    val maxChunks: Int,
) : AutoCloseable {

    private val map = object : LinkedHashMap<Int, String>(maxChunks * 2, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Int, String>?): Boolean = size > maxChunks
    }

    var hits = 0L
        private set
    var misses = 0L
        private set
    var decodedBytes = 0L
        private set
    var peakResidentChars = 0L
        private set

    fun chunk(index: Int): String = synchronized(map) {
        val cached = map[index]
        if (cached != null) {
            hits++
            return cached
        }
        misses++
        val decoded = source.decodeChunk(index)
        decodedBytes += source.chunkByteLen(index)
        map[index] = decoded
        var resident = 0L
        for (v in map.values) resident += v.length
        if (resident > peakResidentChars) peakResidentChars = resident
        decoded
    }

    fun residentChunks(): Int = synchronized(map) { map.size }

    fun slice(start: Long, count: Int): String {
        if (count <= 0 || start >= source.totalChars) return ""
        val sb = StringBuilder(minOf(count, 1 shl 22))
        var index = (start / source.chunkChars).toInt()
        var inner = (start - index.toLong() * source.chunkChars).toInt()
        var remaining = count
        while (remaining > 0 && index < source.chunkCount) {
            val chunk = chunk(index)
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

    /** 预取用：只把块读进缓存，不关心返回内容。 */
    fun touch(index: Int) {
        if (index in 0 until source.chunkCount) chunk(index)
    }

    fun evictAll() = synchronized(map) { map.clear() }

    override fun close() {
        evictAll()
        source.close()
    }
}
