package com.bookreader.data

import android.content.Context
import com.bookreader.booksource.AudioDownloader
import com.bookreader.booksource.BookHit
import com.bookreader.booksource.FileAudioStore
import com.bookreader.booksource.HttpFetcher
import com.bookreader.booksource.Rule

/**
 * 一本有声书从站子到书架。
 *
 * 它和漫画那一条是同一族：**下载目录就是书的目录**。音频拼不出一个"整串文字"，这里直接写进
 * `filesDir/books/<id>/audio/`，断点、增量、以及"这本书在不在架上"三件事由同一个目录说了算。
 *
 * 抓不到音频就抛：调用方（[DownloadWorker]）把 `it.message` 原话写进下载表那一行。
 */
object AudioImport {

    /**
     * @param bookId 那本书的 id。第一次下也是它 —— 由 [DownloadWorker] 现起并**先写进下载表**，
     *   这样中途被杀之后下一次还能认得同一本（见下面开抓前先写清单那一段）。
     * @param head 0 = 整本；>0 = 只抓目录开头这几集。
     */
    fun run(
        ctx: Context,
        rule: Rule,
        hit: BookHit,
        bookId: String,
        head: Int,
        shouldStop: () -> Boolean,
        onProgress: (Int, Int) -> Unit,
    ): Done {
        val library = BookLibrary(ctx)
        val id = bookId
        val store = FileAudioStore(library.audioDir(id))
        // 清单和来源记录在**开抓之前**先写一次空的：一本有声书要抓几个 GB、中间进程随时会被系统收回
        // （这是常态不是意外），那一本还在书架上、下载表上那一行还认得它的 id，下次点「下载全书」
        // 是接着补；不先写的话它会变成磁盘上一堆没人认领的音频 + 书架上什么都没有，而下一次
        // 只能另起一个 id —— 同一本有声书在架上就成两本了。
        // 集表不等整本结束：每一集抓完就地 append 一行（见 AudioBook.record），
        // 中途停住的那半本也读得到集名。
        val book = AudioBook(library.dir(id))
        library.writeAudioMeta(id, hit.bookName, hit.author, 0, 0, 0L)
        BookProvenance(library.dir(id)).save(rule.id, hit.bookUrl, "", emptyList())
        val out = AudioDownloader(rule, HttpFetcher(), store)
            .download(hit, shouldStop, head, onChapter = { book.record(it.index, it.title, it.dir, it.parts) }, onProgress = onProgress)
        // 「先听一集」抓一集也算一次入库：一段音频都没有就别在书架上留一本点开是空的有声书
        if (out.got.isEmpty() && book.episodes().isEmpty()) {
            val first = out.failed.firstOrNull()
            error(if (first == null) "一集都没抓到" else "一集都没抓到音频（「$first」那一集的页面里没有音频地址）")
        }
        val parts = store.parts()
        val bytes = store.bytes()
        library.writeAudioMeta(id, hit.bookName, hit.author, out.outline.size, parts, bytes)
        BookProvenance(library.dir(id)).save(rule.id, hit.bookUrl, out.intro, out.outline)
        return Done(id, out.chapters, book.episodes().size, parts, bytes, out.failed, out.outline)
    }

    /** 抓完并写好的一本有声书：书架上那本的 id + 界面上要报的那几个数。 */
    data class Done(
        val bookId: String,
        /** 这一次按目录顺序该有几集（`head` 那一本就只有那么几集）。 */
        val chapters: Int,
        /** 磁盘上现在有音频的那几集。 */
        val onDisk: Int,
        val parts: Int,
        val bytes: Long,
        val failed: List<String>,
        val outline: List<String>,
    )
}
