package com.bookreader.data

import android.content.Context
import com.bookreader.booksource.AudioDownloader
import com.bookreader.booksource.ComicDownloader
import com.bookreader.booksource.FileAudioStore
import com.bookreader.booksource.BookHit
import com.bookreader.booksource.FileChapterStore
import com.bookreader.booksource.FileComicStore
import com.bookreader.booksource.SearchEngine
import com.bookreader.booksource.chapterCacheKey
import com.bookreader.booksource.Rule
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 只补「这一章」：目录抽屉行右边那颗 ↓。
 *
 * 抓和落盘走的还是整本下载那一条（同一个 downloader、同一套仓、章号按整本目录记），
 * 但**不起 WorkManager 那一枪**：一本书一个任务的规矩是给整本下载准备的（两条 worker 会抢
 * 同一个限速、往同一个章缓存里写），而这一枪就在读的那一屏里跑、跑完就地翻进去读。
 *
 * 漫画和有声：一章 = 一个目录，[comic] / [audio] 就地补那一章，别的章一个字不碰；
 * 架上还没有这一本的话，先 [openShell] 开一本只有一章的空壳（清单 + 来源 + 整份大纲）再补。
 * 小说：一章是整串 `content.txt` 里的一段偏移，单补一章进不了那串字 —— [novel] 走的是
 * 「抹掉那一节的断点缓存 + 起『补齐全本』那一枪」：只有那一节重新抓，其余各节从缓存读，
 * 抓完整本重灌一次索引。慢一点，但那一节真的换了新内容。
 */
object ChapterFetch {

    /** 这一本从哪条源、哪个书页地址来。本地导入的、规则删了的、没留地址的回 null。 */
    data class From(val rule: Rule, val bookUrl: String)

    /** 这一本认不认得来源：本地导入的、那条源删了的、书页地址没留下的，回 null。 */
    fun from(ctx: Context, library: BookLibrary, bookId: String): From? {
        val p = BookProvenance(library.dir(bookId))
        val ruleId = p.ruleId()
        val url = p.bookUrl()
        if (ruleId.isEmpty() || url.isEmpty()) return null
        val rule = BookSourceStore(ctx).list().firstOrNull { it.rule?.id == ruleId }?.rule ?: return null
        return From(rule, url)
    }

    /** 漫画补第 [index] 话，落进这本书的目录。回这一话的张数；抓不到就抛，`message` 是给人看的那句话。 */
    suspend fun comic(ctx: Context, library: BookLibrary, bookId: String, index: Int, force: Boolean = false): Int =
        withContext(Dispatchers.IO) {
            val src = need(from(ctx, library, bookId))
            val (fetch, why) = SourceAuth.fetcher(ctx, src.rule)
            if (why != null) error(why)
            val ch = ComicDownloader(src.rule, fetch, FileComicStore(library.comicDir(bookId)))
                .chapterAt(src.bookUrl, index, force) ?: error(goneSays(index))
            ComicBook(library.dir(bookId)).record(ch.index, ch.title, ch.dir, ch.pages)
            syncBook(library, bookId, ComicBook(library.dir(bookId)).chapters().size, audio = false)
            ch.pages
        }

    /** 有声补第 [index] 集。回这一集有几段音频。 */
    suspend fun audio(ctx: Context, library: BookLibrary, bookId: String, index: Int, force: Boolean = false): Int =
        withContext(Dispatchers.IO) {
            val src = need(from(ctx, library, bookId))
            val (fetch, why) = SourceAuth.fetcher(ctx, src.rule)
            if (why != null) error(why)
            val ch = AudioDownloader(src.rule, fetch, FileAudioStore(library.audioDir(bookId)))
                .chapterAt(src.bookUrl, index, force) ?: error(goneSays(index))
            AudioBook(library.dir(bookId)).record(ch.index, ch.title, ch.dir, ch.parts)
            syncBook(library, bookId, AudioBook(library.dir(bookId)).episodes().size, audio = true)
            ch.parts
        }

    /**
     * 架上还没有这一本时先开一本空壳：清单 + 来源 + **整份**站点目录，回新书的 id。
     *
     * 大纲一次写全是关键：阅读页和「本书信息」的目录都按来源里那一份摆（没下的挂 ↓），
     * 只写点过的这一章，那一本在界面上就只剩一章 —— 看着像被抓坏了。
     * 清单先写、图后抓，和整本下载那一条（[ComicImport]）同一个次序：抓到一半进程被系统收回，
     * 那一本还在架上、还认得自己的来源，下次点它是接着补，不是另起一本。
     */
    suspend fun openShell(
        ctx: Context,
        library: BookLibrary,
        rule: Rule,
        bookUrl: String,
        title: String,
        author: String,
        outline: List<String>,
    ): String = withContext(Dispatchers.IO) {
        val id = library.newId(title)
        if (rule.kind == Rule.AUDIO) library.writeAudioMeta(id, title, author, outline.size, 0, 0L)
        else library.writeComicMeta(id, title, author, outline.size, 0)
        // 简介留空：手上那份是列表页给的，不一定有；来源和大纲才是读目录那两样要用的
        BookProvenance(library.dir(id)).save(rule.id, bookUrl, "", outline)
        id
    }

    /** 一章落了盘，把账面那两个数按磁盘重写：全书几章按来源里那份大纲（没有就按盘上有的章数）。 */
    private fun syncBook(library: BookLibrary, bookId: String, onDisk: Int, audio: Boolean) {
        val toc = BookProvenance(library.dir(bookId)).outline().size.coerceAtLeast(onDisk)
        if (audio) {
            val s = FileAudioStore(library.audioDir(bookId))
            library.syncAudioCounts(bookId, toc, s.parts(), s.bytes())
        } else {
            library.syncComicCounts(bookId, toc, FileComicStore(library.comicDir(bookId)).images())
        }
    }

    /**
     * 小说重下第 [index] 节：删掉那一节的断点缓存，再起那一枪（其余各节从缓存读，
     * 只有那一节重新抓）。回 false = 站点目录里已经没有这一节，没起枪。
     *
     * `title` 必须传架上那一本的书名：worker 重灌时拿它当书名，传错就等于顺手改了书名。
     * `head` = 这一趟抓到第几章为止（0 = 整本）。目录里那些**还没下过**的节要传 `index + 1`：
     * 不传就是整本，点一章等于把一千多章全拉下来 —— 架上那本已有多少章由 [Downloads.routeOf] 兜底，
     * 这里只负责把"我要到这一章"说清楚。
     */
    suspend fun novel(
        ctx: Context, library: BookLibrary, bookId: String, index: Int, title: String, head: Int = 0,
    ): Boolean =
        withContext(Dispatchers.IO) {
            val src = need(from(ctx, library, bookId))
            val (fetch, why) = SourceAuth.fetcher(ctx, src.rule)
            if (why != null) error(why)
            val at = SearchEngine(src.rule, fetch).head(src.bookUrl).chapters.getOrNull(index)
                ?: return@withContext false
            FileChapterStore(BookSourceStore(ctx).cacheDir(src.rule.id, src.bookUrl))
                .drop(chapterCacheKey(src.rule.id, src.bookUrl, at.second))
            val hit = BookHit(
                sourceId = src.rule.id, sourceName = src.rule.name, bookName = title,
                author = "", bookUrl = src.bookUrl,
            )
            Downloads.start(ctx, src.rule, hit, update = true, head = head)
            true
        }

    /** 站点目录里没这一号时那句实话（删了章，或者那个号是从一份旧目录里记的）。 */
    fun goneSays(index: Int): String = "站点目录里已经没有第 ${index + 1} 章了"

    private fun need(src: From?): From =
        src ?: error("这一本没有可下的来源：本地导入的书，或那条源已经删了")
}
