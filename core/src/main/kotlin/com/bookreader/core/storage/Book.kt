package com.bookreader.core.storage

import com.bookreader.core.index.ChapterIndex
import com.bookreader.core.model.BookMeta
import com.bookreader.core.model.Chapter
import java.io.File

/**
 * 一本书的落盘形态（设计文档第 13 节的 BookContainer）：
 *
 * ```text
 * <dir>/manifest.kv    元数据
 * <dir>/content.txt    规范化 UTF-8 正文，只按块随机读
 * <dir>/chunk.idx      块号 -> 字节起点/字节长度
 * <dir>/chapter.idx    章节 -> 字符偏移区间
 * ```
 */
class Book private constructor(
    val dir: File,
    val meta: BookMeta,
    val chapters: ChapterIndex,
    val cache: ChunkCache,
) : AutoCloseable {

    val totalChars: Long get() = meta.totalChars

    fun chunkIndexOf(offset: Long): Int = (offset / meta.chunkChars).toInt().coerceIn(0, meta.chunkCount - 1)

    override fun close() {
        cache.close()
    }

    companion object {
        fun manifestOf(dir: File): Map<String, String> = IndexCodec.readManifest(File(dir, IndexCodec.MANIFEST))

        fun readMeta(dir: File): BookMeta? = runCatching { metaOf(manifestOf(dir)) }.getOrNull()

        fun metaOf(m: Map<String, String>): BookMeta = BookMeta(
            id = checkNotNull(m["id"]) { "manifest 缺 id" },
            title = m["title"] ?: "未命名",
            author = m["author"] ?: "",
            totalChars = m.getValue("totalChars").toLong(),
            chunkChars = m.getValue("chunkChars").toInt(),
            chunkCount = m.getValue("chunkCount").toInt(),
            chapterCount = m.getValue("chapterCount").toInt(),
            contentBytes = m.getValue("contentBytes").toLong(),
            sourceBytes = m["sourceBytes"]?.toLong() ?: 0L,
            sourceCharset = m["sourceCharset"] ?: "UTF-8",
        )

        /** 书架：扫描根目录下每个子目录的 manifest。 */
        fun listBooks(root: File): List<BookMeta> {
            if (!root.isDirectory) return emptyList()
            return root.listFiles()?.sortedByDescending { it.name }?.mapNotNull { d ->
                if (d.isDirectory) readMeta(d) else null
            } ?: emptyList()
        }

        fun open(dir: File, maxChunks: Int = 6, wrap: (TextSource) -> TextSource = { it }): Book {
            val meta = readMeta(dir) ?: error("不是一本书的目录: ${dir.path}")
            val table = IndexCodec.readChunkIndex(File(dir, IndexCodec.CHUNK_IDX))
            require(table.size == meta.chunkCount) { "块索引数量不符: ${table.size} != ${meta.chunkCount}" }
            val source = wrap(FileTextSource(File(dir, IndexCodec.CONTENT), meta.totalChars, meta.chunkChars, table))
            val chapters = ChapterIndex(IndexCodec.readChapters(File(dir, IndexCodec.CHAPTER_IDX)))
            require(chapters.size == meta.chapterCount) { "章节索引数量不符: ${chapters.size} != ${meta.chapterCount}" }
            return Book(dir, meta, chapters, ChunkCache(source, maxChunks))
        }

        fun chaptersOf(meta: BookMeta, chapters: List<Chapter>): Int = chapters.size
    }
}
