package com.bookreader.booksource

import com.bookreader.core.ingest.ChapterPattern
import java.util.Collections
import java.util.Random
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.max
import kotlin.math.min

/**
 * 抓一本：目录 → 逐章正文（并发 + 限速 + 重试 + 断点）→ 合成整本正文。
 *
 * 三个"看得见行为"的旋钮都是为真实站准备的：
 * 有站会因为下太快而**不报错、正文直接返回空**，所以"空正文"要当一次失败去重试，
 * 而不是当成一章空的写进书里；重试完还拿不到的章要留在 [DownloadOutcome.failed] 里，
 * 界面能原话说"这几章没抓到"，不能悄悄少几章。
 */
class BookDownloader(
    private val rule: Rule,
    private val fetch: Fetcher,
    private val store: ChapterStore,
    /** 限速要能被测：真实实现是 Thread.sleep + 系统时钟，测试里给假的，否则判据只能靠等。 */
    private val sleep: (Long) -> Unit = { if (it > 0) Thread.sleep(it) },
    private val clock: () -> Long = { System.currentTimeMillis() },
    private val random: Random = Random(),
) {

    /**
     * @param onProgress (已完成章数, 总章数)：按**完成**顺序回调，界面要的是"动了多少"。
     *   目录一拿到就先回调一次 (0, 总数) —— 不然进度那一行在第一章落地之前是空的。
     * @param shouldStop 每一章动手之前问一次。取消不是"把线程杀掉"，是"剩下的章不再要"：
     *   已抓到的都留在仓里，下次点它接着抓。
     */
    fun download(
        hit: BookHit,
        shouldStop: () -> Boolean = { false },
        /** 0 = 整本。>0 = 只拉目录开头这几章（界面上那颗「先拉开头」），剩下的等「全本」补。
         *  摆在 onProgress 之前：满项目都是尾随 lambda 传进度回调，尾随 lambda 永远绑最后一个形参。 */
        limit: Int = 0,
        onProgress: (Int, Int) -> Unit = { _, _ -> },
    ): DownloadOutcome {
        // 书页只请求一次：目录和简介都在那一页上
        val head = SearchEngine(rule, fetch).head(hit.bookUrl)
        val chapters = if (limit <= 0) head.chapters else head.chapters.take(limit)
        if (chapters.isEmpty()) return DownloadOutcome(hit.bookName, hit.author, 0, 0, emptyList(), "", head.intro, head.chapters.map { it.first })
        val total = chapters.size
        onProgress(0, total)
        val done = AtomicInteger(0)
        val pool = Executors.newFixedThreadPool(min(total, max(1, rule.crawl.concurrency)))
        val lock = Any()
        var nextAt = 0L
        try {
            val tasks = chapters.map { (name, url) ->
                Runnable {
                    if (shouldStop()) return@Runnable
                    val key = storeKey(hit.bookUrl, url)
                    if (store.get(key) == null) {
                        val text = fetchChapter(url) {
                            // 排队取一个发送时刻：并发几条线程时，"隔多久"必须是全局累加的槽位，
                            // 不是每条线程各自 sleep 一次 —— 否则四条线程会同一瞬间一起打过去
                            val wait = synchronized(lock) {
                                val now = clock()
                                val target = max(now, nextAt)
                                nextAt = target + intervalMs()
                                target - now
                            }
                            sleep(wait)
                        }
                        if (text != null) store.put(key, text)
                    }
                    onProgress(done.incrementAndGet(), total)
                }
            }
            for (t in tasks) pool.execute(t)
            pool.shutdown()
            // 上限按"最坏情况每章都重试满 + 每次都等到限速窗口"给，超了就放弃等：
            // 网络卡住的站不该把一次下载变成无限期挂住
            if (!pool.awaitTermination(30L, TimeUnit.MINUTES)) pool.shutdownNow()
        } finally {
            pool.shutdownNow()
        }
        return assemble(hit, chapters, head.intro, head.chapters.map { it.first })
    }

    /**
     * 抓一章 = 这一页 + 它后面所有"同一章的下一页"（`123.html` → `123_2.html` 那种一章切几页的站）。
     *
     * 空正文、抛异常、翻页翻不到头，都算**这一章失败**（返回 null，进"没抓到的章"那一列）。
     * 半截一章比缺一章坏：缺的那一行界面会写出来，半截的只表现为"读到一半下一章就来了"，
     * 没人会怀疑到下载这一步 —— 所以宁可缺，不接半截。
     */
    private fun fetchChapter(url: String, gate: () -> Unit): String? {
        val parts = ArrayList<String>(2)
        val seen = HashSet<String>()
        var page: String? = url
        while (page != null && seen.add(page)) {
            val got = fetchPage(page, gate) ?: return null
            parts += got.text
            page = nextPageUrl(rule, got.html, got.at)
            if (page != null && parts.size >= MAX_PAGES_PER_CHAPTER) return null
        }
        return parts.joinToString("\n\n")
    }

    /** 一页：重试到 `maxAttempts`。拿到的是原页 HTML（还要从里面找下一页）+ 它自己生效的地址 + 抽出来的正文。 */
    private fun fetchPage(url: String, gate: () -> Unit): ChapterPage? {
        repeat(max(1, rule.crawl.maxAttempts)) {
            // 每一次请求都排队，包括第一次：只在重试前排队的话，一开书就有 concurrency 条
            // 请求一个间隔都不隔地打过去，限流站正好就是在这一下把后面的章返空的
            gate()
            val got = runCatching {
                val p = fetch.fetch(PageRequest(url))
                val html = p.text(rule.charset)
                ChapterPage(html, p.finalUrl, chapterText(rule, html, p.finalUrl))
            }.getOrNull()
            if (got != null && got.text.isNotEmpty()) return got
        }
        return null
    }

    private fun intervalMs(): Long {
        val c = rule.crawl
        val lo = max(0L, c.minIntervalMs)
        val hi = max(lo, c.maxIntervalMs)
        return if (hi == lo) lo else lo + random.nextInt((hi - lo).toInt() + 1).toLong()
    }

    private fun assemble(
        hit: BookHit,
        chapters: List<Pair<String, String>>,
        intro: String,
        outline: List<String>,
    ): DownloadOutcome {
        val failed = ArrayList<String>()
        val b = StringBuilder()
        chapters.forEachIndexed { i, (name, url) ->
            val text = store.get(storeKey(hit.bookUrl, url))
            if (text == null) failed += name else {
                b.append(heading(i, name)).append('\n').append(text).append("\n\n")
            }
        }
        val body = b.toString()
        return DownloadOutcome(hit.bookName, hit.author, chapters.size, body.length, failed, body, intro, outline)
    }

    private fun storeKey(bookUrl: String, chapterUrl: String) = chapterCacheKey(rule.id, bookUrl, chapterUrl)

    /** 一章的一页：原文（还要从里面找"下一页"）、它自己生效的地址（相对链接按它解，限流站会跳走）、抽出来的正文。 */
    private class ChapterPage(val html: String, val at: String, val text: String)

    companion object {
        /**
         * 一章最多跟几页。到顶还有"下一页"就当这一章失败：站点把"下一章"标成分页链接、
         * 或者两个页面互相指（自环）的时候，没有这一条就是无限抓下去。
         * 20 是按真站看的：一章切两三页是常态，十几页已经是异常。
         */
        const val MAX_PAGES_PER_CHAPTER = 20
    }
}

/** 抓下来的整本。body 不含书名/作者那类页眉：导入时标题是另传的，写进正文会被当成第一章前面的碎屑。 */
data class DownloadOutcome(
    val title: String,
    val author: String,
    val chapters: Int,
    val chars: Int,
    /** 没抓到的章名，按目录顺序。空 = 全本齐。 */
    val failed: List<String>,
    val body: String,
    /** 书页上那段内容简介（规则没写这一格就是空串）。 */
    val intro: String = "",
    /** **整本**的章名，按目录顺序 —— 和 limit 无关：只拉两章时大纲也该看得见全书。 */
    val outline: List<String> = emptyList(),
) {
    val complete: Boolean get() = failed.isEmpty()
}

/**
 * 已抓章节存哪儿 —— 断点续抓就靠它。
 *
 * 键是**章的 URL**，不是序号：同一本书两次抓的目录顺序可能因为源站补章而错位，
 * 按序号存会把第 80 章的内容当成第 79 章续上。
 */
interface ChapterStore {
    fun get(key: String): String?
    fun put(key: String, value: String)
}

/**
 * 一章在断点缓存里的那个键。唯一的一份写法放在这里：
 * 「重下这一节」那一条要自己算出键去删文件，格式在两处各写一遍迟早有一处改了另一处没改 ——
 * 那时表现就是"点了重下，抓回来的还是缓存里那一份"。
 */
fun chapterCacheKey(ruleId: String, bookUrl: String, chapterUrl: String) = ruleId + "|" + bookUrl + "|" + chapterUrl

/** 测试和一次性下载用这个；`:app` 里给的是落盘实现（进程被杀也能续）。 */
class MemoryStore(initial: Map<String, String> = emptyMap()) : ChapterStore {
    private val map = Collections.synchronizedMap(LinkedHashMap(initial))
    override fun get(key: String): String? = map[key]
    override fun put(key: String, value: String) { map[key] = value }
    val size: Int get() = synchronized(map) { map.size }
}

/**
 * 目录里那一行要变成正文里的一行"能被阅读引擎认成章节标题"的字。
 *
 * 站点目录五花八门：有的写"第一章 开门"（直接能用），有的只写"1"或者一行超过 80 字的长句
 * （`ChapterPattern` 都不认，整本书会被并成一章，分页代价从"一章"变成"一本书"）。
 * 所以不认的就补一个 `第 N 章`，补完还不认（比如章名本身带句号）就退到光杆"第 N 章"。
 */
fun heading(index: Int, name: String): String {
    val t = name.trim()
    if (ChapterPattern.matches(t)) return t
    val padded = "第 " + (index + 1) + " 章　" + t
    return if (ChapterPattern.matches(padded)) padded else "第 " + (index + 1) + " 章"
}
