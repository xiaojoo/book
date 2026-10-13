package com.bookreader.data

import android.content.Context
import com.bookreader.booksource.BookHit
import com.bookreader.booksource.HttpFetcher
import com.bookreader.booksource.PageRequest
import com.bookreader.booksource.Rule
import java.io.File

/**
 * 一篇文献从接口到书架：取那支 PDF → 按 [BookMeta] 的 pdf 那一类入库。
 *
 * 没有第三种阅读器 —— arXiv / OpenAlex / DOAJ 交出来的就是标准 PDF，而这一族已经有
 * `PdfReaderScreen`（翻页、双指缩放、缩略图、页码书签都在）。给它一个新种类等于把那一套重做一遍，
 * 还多一份"两处 pdf 渲染迟早不一致"的账。
 */
object PaperImport {

    /** @param bookId 架上已经有那一篇就传它的 id（重新下一遍同一个 doi 不该多出第二篇）。 */
    fun run(ctx: Context, rule: Rule, hit: BookHit, bookId: String, onProgress: (Int, Int) -> Unit): Done {
        onProgress(0, 1)
        val library = BookLibrary(ctx)
        val id = bookId.ifEmpty { library.newId(hit.bookName) }
        // 有些出版商看图给防盗链，按接口那一页当来路发（arXiv 不看，看了也不亏）
        val p = HttpFetcher().fetch(PageRequest(hit.bookUrl, referer = rule.homeUrl))
        val b = p.bytes
        // 开头五个字节不是 %PDF- 就不进书架：那一大半是"要登录"或者"转人工验证"的那张 HTML 页，
        // 存进书架就是一篇点开打不开的"文献"，比下载失败难懂十倍
        if (b.size < MIN_PDF_BYTES || String(b, 0, 5, Charsets.ISO_8859_1) != "%PDF-") {
            val head = if (b.isEmpty()) "" else "，开头是「" + String(b, 0, minOf(b.size, 24), Charsets.ISO_8859_1).trim() + "」"
            error(
                "这一条回的不是 PDF：" + p.finalUrl + " 回了 " + b.size + " 字节" + head +
                    "。这一篇要么要订阅、要么那一家只给摘要页 —— 换一条源，或者到浏览器里登录了再看",
            )
        }
        val staged = File(ctx.cacheDir, "paper-" + System.currentTimeMillis() + ".pdf")
        staged.writeBytes(b)
        // 书名原样传：接口给的那串标题里可能有斜杠，importFile 会把它洗成目录名
        val meta = library.importFile(staged, hit.bookName, into = if (bookId.isEmpty()) null else bookId)
        BookProvenance(library.dir(meta.id)).save(rule.id, hit.bookUrl, hit.intro, emptyList())
        onProgress(1, 1)
        return Done(meta.id, b.size)
    }

    /** 下好的一篇：书架上那本的 id + 那支 PDF 的字节数（界面上"多大"读它，不冒充字数）。 */
    data class Done(val bookId: String, val bytes: Int)

    private const val MIN_PDF_BYTES = 1024
}
