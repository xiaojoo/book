package com.bookreader.data

import android.content.Context
import com.bookreader.booksource.SearchEngine
import com.bookreader.core.model.BookMeta
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 书架下拉刷新干的那件事：挨本问一次「站点目录现在几章」，比我们上次知道的那份多出来的那几章就是连载更新了。
 *
 * 一本书一次请求（就是书页那一页），不抓任何正文：一屏七本 = 七条请求，串行打，
 * 不为省这几秒开并发 —— 这几家站的限速是按会话算的，四条线程一起打过去和被限流是一回事。
 *
 * 只问从书源下来的那几本：本地导入的那一本没有书页，问无可问，也不该算进「没查到」那一格。
 */
object UpdateCheck {

    /** 一次检查的账本：哪几本有更新（各多几章）、真去问了几本、几本没问到。 */
    data class Report(val updates: Map<String, Int>, val asked: Int, val missed: Int)

    /**
     * 站点比"上次知道的那份目录"多出几章。
     *
     * 判据是**上次知道的那份**，不是本机排到第几章：一本 886 章全本、读到第 18 章的书，
     * 按本地算会报"868 章更新" —— 那是假话，那 868 章早抓在盘上了，只是没读。
     */
    fun delta(siteChapters: Int, lastKnown: Int): Int = (siteChapters - lastKnown).coerceAtLeast(0)

    /**
     * "上次知道的那份目录"有多长：清单上那个数（下载时按站点整份目录写的）和来源里那份大纲，取大的。
     *
     * 为什么要取大：小说的清单写的是**入库了几章**（那是一串字的索引），漫画的清单写的才是整份目录；
     * 两档各存各的，取大的才不会被其中一档的口径带偏。
     */
    fun lastKnown(metaChapters: Int, outlineChapters: Int): Int = maxOf(metaChapters, outlineChapters)

    /** 存储那一格的形状：`id=3,id2=1`。书 id 里有中文和"-"，所以只用逗号和等号当分隔，写之前把这两个字符抹掉。 */
    fun encode(updates: Map<String, Int>): String =
        updates.entries.joinToString(",") { (k, v) -> k.filterNot { it == ',' || it == '=' } + "=" + v }

    /** 读回来：认不出的那一截整段丢掉（改了存储格式也不能让书架开不了）。 */
    fun decode(s: String): Map<String, Int> = s.split(',').mapNotNull {
        val k = it.substringBefore('=', "").trim()
        val v = it.substringAfter('=', "").toIntOrNull()
        if (k.isEmpty() || v == null || v <= 0) null else k to v
    }.toMap()

    /**
     * 跑一次检查。[books] 传书架上看得见的那几本（从架上移除的不问）。
     *
     * 一本问不到不算整次失败：那条源要账号、站点改版、限流，都只让它那一本没红点，
     * 剩下的照问 —— 界面上报一句「N 本没问到」，不摆一个看起来像成功的"全部已最新"。
     */
    suspend fun run(ctx: Context, library: BookLibrary, sources: List<StoredSource>, books: List<BookMeta>): Report =
        withContext(Dispatchers.IO) {
            val updates = LinkedHashMap<String, Int>()
            var asked = 0
            var missed = 0
            for (m in books) {
                val p = BookProvenance(library.dir(m.id))
                val rule = sources.firstOrNull { it.rule?.id == p.ruleId() }?.rule
                // 本地导入的那一本没有书页可问；那条源被删了也没处问 —— 两种都不算进「没问到」那一格
                if (!p.known || rule == null) continue
                val (fetch, why) = SourceAuth.fetcher(ctx, rule)
                if (why != null) {
                    missed++
                    continue
                }
                asked++
                val site = runCatching { SearchEngine(rule, fetch).head(p.bookUrl()).chapters.size }.getOrNull()
                if (site == null) {
                    missed++
                    continue
                }
                val d = delta(site, lastKnown(m.chapterCount, p.outline().size))
                if (d > 0) updates[m.id] = d
            }
            Report(updates, asked, missed)
        }
}
