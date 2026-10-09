package com.bookreader.core.storage

/**
 * 正文的随机访问接口：只按"逻辑块"取字，永不整本加载。
 * 偏移一律是规范化正文（UTF-8、换行统一为 \n）里的字符下标。
 */
interface TextSource : AutoCloseable {
    val totalChars: Long
    val chunkChars: Int
    val chunkCount: Int

    /** 解码第 index 个逻辑块，长度可小于 chunkChars（最后一块）。 */
    fun decodeChunk(index: Int): String

    /** 该块在文件里占多少字节，缓存只记这个数就能算出常驻体积。 */
    fun chunkByteLen(index: Int): Int

    /** 跨块拼接 [start, start+count) 的字。 */
    fun read(start: Long, count: Int): String
}
