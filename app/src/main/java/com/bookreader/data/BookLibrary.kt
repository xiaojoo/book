package com.bookreader.data

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import com.bookreader.core.ingest.TxtIngestor
import com.bookreader.core.model.BookMeta
import com.bookreader.core.storage.Book
import java.io.File

/**
 * 书架落在 filesDir/books/<id>/，每目录一本（core 的 BookContainer）。
 * SAF 给的 uri 不是文件路径，所以先流式复制到 cacheDir 再入库，入库产物才进 filesDir。
 */
class BookLibrary(private val context: Context) {

    private val root = File(context.filesDir, "books").apply { mkdirs() }
    private val staging = File(context.cacheDir, "import").apply { mkdirs() }

    fun list(): List<BookMeta> = Book.listBooks(root)

    fun dir(id: String): File = File(root, id)

    fun open(id: String, maxChunks: Int = 6): Book = Book.open(dir(id), maxChunks)

    fun delete(id: String): Boolean = dir(id).deleteRecursively()

    fun displayName(uri: Uri): String {
        // file:// 没有 DISPLAY_NAME 可查，只能拿路径末段，否则书架上会全叫"未命名小说"
        if (uri.scheme == "file") return uri.lastPathSegment?.substringAfterLast('/') ?: "未命名小说"
        val name = context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst() && !c.isNull(0)) c.getString(0) else null
        }
        return name ?: "未命名小说"
    }

    /** 同步阻塞，调用方要在 IO 线程上跑。 */
    fun import(uri: Uri, onProgress: (Double) -> Unit = {}): BookMeta {
        val title = displayName(uri).substringBeforeLast('.')
        val staged = File(staging, "staged-${System.currentTimeMillis()}.txt")
        context.contentResolver.openInputStream(uri).use { input ->
            checkNotNull(input) { "读不到所选文件" }
            input.copyTo(staged.outputStream().buffered(1 shl 16))
        }
        val id = "${System.currentTimeMillis()}-${title.replace(UNSAFE, "").take(40)}"
        val dir = File(root, id)
        val t0 = System.nanoTime()
        return try {
            val meta = TxtIngestor().ingest(staged, dir, title = title, onProgress = onProgress).meta
            android.util.Log.d(
                "ReaderPerf",
                "ingest chars=${meta.totalChars} chunks=${meta.chunkCount} contentBytes=${meta.contentBytes} " +
                    "chapters=${meta.chapterCount} charset=${meta.sourceCharset} ms=${(System.nanoTime() - t0) / 1_000_000}",
            )
            meta
        } finally {
            staged.delete()
        }
    }

    private companion object {
        val UNSAFE = Regex("""[\\/:*?"<>|\s]""")
    }
}
