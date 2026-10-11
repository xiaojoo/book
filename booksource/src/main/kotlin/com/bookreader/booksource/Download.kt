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
        onProgress: (Int, Int) -> Unit = { _, _ -> },
    ): DownloadOutcome {
        val chapters = SearchEngine(rule, fetch).catalog(hit.bookUrl)
        if (chapters.isEmpty()) return DownloadOutcome(hit.bookName, hit.author, 0, 0, emptyList(), "")
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
        return assemble(hit, chapters)
    }

    /** 抓一章：空正文和抛异常都算失败，重试到 maxAttempts。成功返回正文，彻底失败返回 null。 */
    private fun fetchChapter(url: String, gate: () -> Unit): String? {
        repeat(max(1, rule.crawl.maxAttempts)) { attempt ->
            // 每一次请求都排队，包括第一次：只在重试前排队的话，一开书就有 concurrency 条
            // 请求一个间隔都不隔地打过去，限流站正好就是在这一下把后面的章返空的
            gate()
            val text = runCatching {
                val p = fetch.fetch(PageRequest(url))
                chapterText(rule, p.text(rule.charset), p.finalUrl)
            }.getOrNull()
            if (!text.isNullOrEmpty()) return text
        }
        return null
    }

    private fun intervalMs(): Long {
        val c = rule.crawl
        val lo = max(0L, c.minIntervalMs)
        val hi = max(lo, c.maxIntervalMs)
        return if (hi == lo) lo else lo + random.nextInt((hi - lo).toInt() + 1).toLong()
    }

    private fun assemble(hit: BookHit, chapters: List<Pair<String, String>>): DownloadOutcome {
        val failed = ArrayList<String>()
        val b = StringBuilder()
        chapters.forEachIndexed { i, (name, url) ->
            val text = store.get(storeKey(hit.bookUrl, url))
            if (text == null) failed += name else {
                b.append(heading(i, name)).append('\n').append(text).append("\n\n")
            }
        }
        val body = b.toString()
        return DownloadOutcome(hit.bookName, hit.author, chapters.size, body.length, failed, body)
    }

    private fun storeKey(bookUrl: String, chapterUrl: String) = rule.id + "|" + bookUrl + "|" + chapterUrl
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
