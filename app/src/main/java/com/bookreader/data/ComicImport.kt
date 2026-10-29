package com.bookreader.data

import android.content.Context
import com.bookreader.booksource.BookHit
import com.bookreader.booksource.ComicDownloader
import com.bookreader.booksource.FileComicStore
import com.bookreader.booksource.HttpFetcher
import com.bookreader.booksource.Rule

/**
 * 一本漫画从站子到书架。
 *
 * 它和小说那一条最大的不同：**下载目录就是书的目录**。小说是"抓到缓存 → 拼成一整串文字 →
 * 再入库"，漫画几万个文件拼不出一个"整串"，拼过去也只会多一份重复的磁盘。这里图直接写进
 * `filesDir/books/<id>/comic/`，于是断点、增量、以及"这本书在不在架上"三件事由同一个目录说了算。
 *
 * 抓不到图就抛：调用方（[DownloadWorker]）把 `it.message` 原话写进下载表那一行。
 */
object ComicImport {

    /**
     * @param bookId 那本书的 id。第一次下也是它 —— 由 [DownloadWorker] 现起并**先写进下载表**，
     *   这样中途被杀之后下一次还能认得同一本（见下面开抓前先写清单那一段）。
     * @param head 0 = 整本；>0 = 只抓目录开头这几章。
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
        val store = FileComicStore(library.comicDir(id))
        // 清单和来源记录在**开抓之前**先写一次空的：图抓到一半进程被杀（一本漫画要抓几千张，
        // 这是常态不是意外），那一本还在书架上、下载表上那一行还认得它的 id，下次点「下载全书」
        // 是接着补；不先写的话它会变成磁盘上一堆没人认领的图 + 书架上什么都没有，而下一次
        // 只能另起一个 id —— 同一本漫画在架上就成两本了。
        // 章表不等整本结束：每一章抓完就地 append 一行（见 ComicBook.record），
        // 中途停住的那半本也读得到章节名 —— 真机现场过一次「图几百张在盘上、chapters.tsv 0 行」。
        val book = ComicBook(library.dir(id))
        library.writeComicMeta(id, hit.bookName, hit.author, 0, 0)
        BookProvenance(library.dir(id)).save(rule.id, hit.bookUrl, "", emptyList())
        // 要先登录的那一族：这一枪在这里打，会话跟着这个 fetcher 进下载。
        // 没登录上就不开那几百枪 —— 每一章的取址口都会回一句「請先登錄」，界面上看到的还是"这本书没图"
        val (fetch, why) = SourceAuth.fetcher(ctx, rule)
        if (why != null) error(why)
        val out = ComicDownloader(rule, fetch, store)
            .download(hit, shouldStop, head, onChapter = { book.record(it.index, it.title, it.dir, it.pages) }, onProgress = onProgress)
        // 「先拉开头」抓两章也算一次入库：一张图都没有就别在书架上留一本点开是空的漫画
        if (out.got.isEmpty() && book.chapters().isEmpty()) {
            val first = out.failed.firstOrNull()
            error(if (first == null) "一章都没抓到" else "一章都没抓到图（「$first」那一章的页面里没有图片地址）")
        }
        val images = store.images()
        library.writeComicMeta(id, hit.bookName, hit.author, out.outline.size, images)
        BookProvenance(library.dir(id)).save(rule.id, hit.bookUrl, out.intro, out.outline)
        return Done(id, out.chapters, book.chapters().size, images, out.failed, out.outline)
    }

    /** 抓完并写好的一本漫画：书架上那本的 id + 界面上要报的那几个数。 */
    data class Done(
        val bookId: String,
        /** 这一次按目录顺序该有几章（`head` 那一本就只有那么几章）。 */
        val chapters: Int,
        /** 磁盘上现在有图的那几章。 */
        val onDisk: Int,
        val images: Int,
        val failed: List<String>,
        val outline: List<String>,
    )
}
