package com.bookreader.core.storage

import com.bookreader.core.index.ChapterIndex
import com.bookreader.core.model.BookMeta
import com.bookreader.core.model.Chapter
import com.bookreader.core.model.StyleIndex
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
    /** markdown 那类书的样式旁路；没有 style.idx 就是空索引，排版走老路。 */
    val styles: StyleIndex,
) : AutoCloseable {

    val totalChars: Long get() = meta.totalChars

    fun chunkIndexOf(offset: Long): Int = (offset / meta.chunkChars).toInt().coerceIn(0, meta.chunkCount - 1)

    override fun close() {
        cache.close()
    }

    companion object {
        fun manifestOf(dir: File): Map<String, String> = IndexCodec.readManifest(File(dir, IndexCodec.MANIFEST))

        fun readMeta(dir: File): BookMeta? = runCatching { metaOf(manifestOf(dir)) }.getOrNull()

        /**
         * pdf / pptx / comic 的清单里没有 chunk/章节那一套（它们不进排版引擎），那几个键缺省。
         * 只在"确实没有这一套"的种类上放行 —— 对引擎书来说缺字段就是真损坏，不能被静默成一本空书。
         */
        fun metaOf(m: Map<String, String>): BookMeta {
            val kind = m["kind"] ?: BookMeta.TXT
            val plain = kind in BookMeta.NO_TEXT_INDEX
            fun long(key: String): Long = m[key]?.toLongOrNull() ?: if (plain) 0L else error("manifest 缺 $key")
            fun int(key: String): Int = m[key]?.toIntOrNull() ?: if (plain) 0 else error("manifest 缺 $key")
            return BookMeta(
                id = checkNotNull(m["id"]) { "manifest 缺 id" },
                title = m["title"] ?: "未命名",
                author = m["author"] ?: "",
                totalChars = long("totalChars"),
                chunkChars = int("chunkChars"),
                chunkCount = int("chunkCount"),
                chapterCount = int("chapterCount"),
                contentBytes = long("contentBytes"),
                sourceBytes = long("sourceBytes"),
                sourceCharset = m["sourceCharset"] ?: "UTF-8",
                kind = kind,
                // 引擎书本来就没有这个键，不算损坏
                pages = m["pages"]?.toIntOrNull() ?: 0,
            )
        }

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
            val styles = runCatching { IndexCodec.readStyleIndex(File(dir, IndexCodec.STYLE_IDX)) }.getOrDefault(StyleIndex.EMPTY)
            return Book(dir, meta, chapters, ChunkCache(source, maxChunks), styles)
        }

        fun chaptersOf(meta: BookMeta, chapters: List<Chapter>): Int = chapters.size
    }
}
