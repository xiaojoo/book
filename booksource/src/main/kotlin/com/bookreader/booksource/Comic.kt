package com.bookreader.booksource

import java.io.File
import java.security.MessageDigest
import java.util.Collections
import java.util.Random
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.max
import kotlin.math.min

/**
 * 抓一本漫画：目录 → 每一章那一排图的地址 → 每一张图的字节。
 *
 * 和 [BookDownloader] 是两条路，不是同一条路上加个分支，因为它们交出去的东西不一样：
 * 小说交出**一整串文字**（内存里装得下），漫画交出**几万个文件**（装不下，也不该装）。
 * 限速、重试、并发、断点这四件事的口径和小说那边保持一致，判据也照同一族写。
 *
 * 落盘的形状（`ComicStore` 的根目录下）：
 * ```text
 * <章地址的 sha1>/pages.tsv          这一章的图片地址，一行一张，顺序就是页序
 * <章地址的 sha1>/<图地址的 sha1>     那一张本身（没有后缀：解码看的是字节，不是文件名）
 * ```
 * 键都用地址的散列而不是序号：站点补一章、或者把某一章的页序动了，按序号存的会张冠李戴，
 * 而"文件名对、内容是另一页"这种错在漫画上比在小说上难发现得多 —— 人看到的是画面，不是字数。
 */
class ComicDownloader(
    private val rule: Rule,
    private val fetch: Fetcher,
    private val store: ComicStore,
    /** 限速要能被测：真实实现是 Thread.sleep + 系统时钟，测试里给假的，否则判据只能靠等。 */
    private val sleep: (Long) -> Unit = { if (it > 0) Thread.sleep(it) },
    private val clock: () -> Long = { System.currentTimeMillis() },
    private val random: Random = Random(),
) {

    /**
     * @param onProgress (已完成的章数, 总章数) —— 和小说那一条同一个口径，界面上那一行不用认两种数。
     * @param limit 0 = 整本；>0 = 只抓目录开头这几章（「先拉开头」那一颗）。
     * @param onChapter **每一章抓完就地**回调一次（几条线程并发，实现要自己受得住）。
     *   这一格是为"抓一半停了"准备的：章表只在整本结束时写一次的话，中途被杀或按暂停，
     *   磁盘上几十章的图就没人记得是哪一话、叫什么名（真机现场：`chapters.tsv` 0 行、图几百张）。
     */
    fun download(
        hit: BookHit,
        shouldStop: () -> Boolean = { false },
        limit: Int = 0,
        onChapter: (ComicChapter) -> Unit = {},
        onProgress: (Int, Int) -> Unit = { _, _ -> },
    ): ComicOutcome {
        val head = SearchEngine(rule, fetch).head(hit.bookUrl)
        val all = head.chapters
        if (all.isEmpty()) {
            return ComicOutcome(hit.bookName, hit.author, 0, 0, emptyList(), all.map { it.first }, head.intro)
        }
        val chapters = if (limit <= 0) all else all.take(limit)
        val total = chapters.size
        onProgress(0, total)
        val done = AtomicInteger(0)
        val failed = Collections.synchronizedList(ArrayList<String>())
        val got = Collections.synchronizedList(ArrayList<ComicChapter>())
        val pool = Executors.newFixedThreadPool(min(total, max(1, rule.crawl.concurrency)))
        val lock = Any()
        var nextAt = 0L
        // 排队取一个发送时刻：并发几条线程时"隔多久"必须是全局累加的槽位，
        // 不是每条线程各自 sleep 一次 —— 否则四条线程会同一瞬间一起打过去
        val gate: () -> Unit = {
            val wait = synchronized(lock) {
                val now = clock()
                val target = max(now, nextAt)
                nextAt = target + intervalMs()
                target - now
            }
            sleep(wait)
        }
        try {
            val tasks = chapters.mapIndexed { i, (name, url) ->
                Runnable {
                    if (shouldStop()) return@Runnable
                    val one = runCatching { fetchChapter(url, shouldStop, gate) }
                        .onFailure { if (shouldStop()) return@Runnable }
                        .getOrNull()
                    if (one == null || one.pages == 0) failed += name else {
                        val c = ComicChapter(i, name, one.dir, one.pages)
                        // 落地就报，不等整本：抓一半被杀/按暂停时那一半在章表里已经是真的
                        got += c
                        onChapter(c)
                    }
                    onProgress(done.incrementAndGet(), total)
                }
            }
            for (t in tasks) pool.execute(t)
            pool.shutdown()
            // 上限给得很松：一章几十张图、每张都可能重试满，网络卡的站不该把一次下载变成无限期挂住
            if (!pool.awaitTermination(120L, TimeUnit.MINUTES)) pool.shutdownNow()
        } finally {
            pool.shutdownNow()
        }
        return ComicOutcome(
            title = hit.bookName,
            author = hit.author,
            chapters = total,
            images = store.images(),
            failed = failed.toList(),
            outline = all.map { it.first },
            intro = head.intro,
            // 按目录顺序交出去：写 chapters.tsv 的那一头要的是"第几章"，不是"哪一条线程先跑完"
            got = got.sortedBy { it.index },
        )
    }

    /**
     * 一章：先拿那一排地址，再逐张取图。
     *
     * 地址表要**先落盘再取图**：图取到一半进程被杀，下次进来还知道这一章本来该有几张、
     * 缺的是哪几张。反过来（先取图再写表）会留下一堆没人认领的文件。
     */
    private fun fetchChapter(chapterUrl: String, shouldStop: () -> Boolean, gate: () -> Unit): One {
        val page = fetch.fetch(PageRequest(chapterUrl))
        val urls = chapterImages(rule, page.text(rule.charset), page.finalUrl)
        if (urls.isEmpty()) return One("", 0)
        val dir = store.putPages(chapterUrl, urls)
        for (u in urls) {
            // 每张之前问一次取消：一本几千张，只在章头问的话按了暂停还要等一整章跑完
            if (shouldStop()) break
            if (store.has(dir, u)) continue
            val bytes = fetchImage(chapterUrl, u, gate) ?: continue
            store.put(dir, u, bytes)
        }
        return One(dir, urls.size)
    }

    /** 一章抓到的一点结果：仓里的目录名 + 该有几张。 */
    private data class One(val dir: String, val pages: Int)

    /**
     * 一张图：非 2xx、字节太小、开头不是那四个魔数之一，都算这一次没成，重试到 maxAttempts。
     *
     * "太小"和"不是图"要当失败而不是当成功：图床被防盗链挡住时回的不是 403 而是一张
     * 几十 KB 的占位灰图（探站时见过），存下来就是"这本下完了，前面二百页是一样的灰图"。
     */
    private fun fetchImage(chapterUrl: String, url: String, gate: () -> Unit): ByteArray? {
        repeat(max(1, rule.crawl.maxAttempts)) {
            gate()
            val bytes = runCatching {
                // 图床按 Referer 决定给不给：图库漫画那一本实测不带回 403、带了回 200 的一张 JPEG。
                // 发的是"这张图所在的那一章页面"，和浏览器点进去时发出去的那一条一模一样
                fetch.fetch(PageRequest(url, referer = chapterUrl)).bytes
            }.getOrNull()
            if (bytes != null && bytes.size >= MIN_IMAGE_BYTES && looksLikeImage(bytes)) return bytes
        }
        return null
    }

    private fun intervalMs(): Long {
        val c = rule.crawl
        val lo = max(0L, c.minIntervalMs)
        val hi = max(lo, c.maxIntervalMs)
        return if (hi == lo) lo else lo + random.nextInt((hi - lo).toInt() + 1).toLong()
    }

    companion object {
        /** 小于一张真实页面下限的（1x1 占位、被防盗链换的那张灰图）不当成"抓到了"。 */
        const val MIN_IMAGE_BYTES = 1024
    }
}

/** 抓完一本漫画的账面数字。图片本身在磁盘上，这里只报"够不够"。 */
data class ComicOutcome(
    val title: String,
    val author: String,
    val chapters: Int,
    val images: Int,
    /** 一张图都没抓到的章名，按目录顺序。空 = 每一章都有图。 */
    val failed: List<String>,
    /** **整本**的章名，和 limit 无关 —— 「先拉开头」那一本也该看得见全书有多少章。 */
    val outline: List<String>,
    /** 书页上那段简介（规则没写这一格就是空串）。 */
    val intro: String = "",
    /** 这一次真在磁盘上有图的那几章（目录顺序）。落 `chapters.tsv` 的就是它。 */
    val got: List<ComicChapter> = emptyList(),
) {
    val complete: Boolean get() = failed.isEmpty()
}

/** 一章在磁盘上的样子：第几章、章名、仓里那个目录名、该有几张图。 */
data class ComicChapter(val index: Int, val title: String, val dir: String, val pages: Int)

/**
 * 图片仓。`:app` 给的是落在书目录里的那一份，判据给的是内存里的那一份。
 *
 * 不复用 [ChapterStore]：那一接口只会存 String，而一张 JPEG 塞进 String 再取回来
 * 是拿 UTF-8 去装任意字节 —— 会坏。
 */
interface ComicStore {
    /** 记下这一章该有哪些图（一行一个地址），返回这一章的目录名。 */
    fun putPages(chapterUrl: String, urls: List<String>): String

    /** 这一张已经在仓里了（断点续抓就靠这一格，不再打站子）。 */
    fun has(dir: String, imageUrl: String): Boolean

    fun put(dir: String, imageUrl: String, bytes: ByteArray)

    /** 仓里一共几张图（界面上那句"下了多少张"读它）。 */
    fun images(): Int
}

/** 落盘的漫画仓：根目录 = 那本书目录下的 `comic/`。 */
class FileComicStore(private val root: File) : ComicStore {

    init {
        if (!root.isDirectory) root.mkdirs()
    }

    override fun putPages(chapterUrl: String, urls: List<String>): String {
        val dir = sha1(chapterUrl)
        val box = File(root, dir)
        box.mkdirs()
        // 先 .part 再改名（和 FileChapterStore 同一份写法）：中途被杀只留一个没人认的临时件，
        // 不会留下一份"看着在、其实少三行"的地址表 —— 那表现是这一章无声少三页
        val f = File(box, PAGES)
        val tmp = File(box, "$PAGES.part")
        tmp.writeText(urls.joinToString("\n") + "\n", Charsets.UTF_8)
        if (!tmp.renameTo(f)) {
            f.delete()
            tmp.renameTo(f)
        }
        return dir
    }

    override fun has(dir: String, imageUrl: String): Boolean =
        File(File(root, dir), comicImageName(imageUrl)).isFile

    override fun put(dir: String, imageUrl: String, bytes: ByteArray) {
        val box = File(root, dir).apply { mkdirs() }
        val f = File(box, comicImageName(imageUrl))
        val tmp = File(box, f.name + ".part")
        tmp.writeBytes(bytes)
        if (!tmp.renameTo(f)) {
            f.delete()
            tmp.renameTo(f)
        }
    }

    /**
     * 只数"那一章该有 N 张"里数出来的真图：`pages.tsv` 是地址表不是图，`.part` 是没写完的垃圾。
     * 半截件混进来的话，界面上那句"共 4,120 张"会比屏上能翻动的页数多。
     */
    override fun images(): Int = root.listFiles()?.sumOf { d ->
        if (!d.isDirectory) 0
        else d.listFiles { f -> f.isFile && f.name != PAGES && !f.name.endsWith(".part") }?.size ?: 0
    } ?: 0

    companion object {
        /** 一章的地址表叫什么：读它的那一头（阅读页）和写它的那一头（这里）必须是同一个名字。 */
        const val PAGES = "pages.tsv"
    }
}

/** `pages.tsv` 里那一行一个地址的表，读回页序。调用方（阅读那一头）按这个顺序翻。 */
fun comicPageOrder(chapterDir: File): List<String> =
    runCatching { File(chapterDir, FileComicStore.PAGES).readLines(Charsets.UTF_8).filter { it.isNotBlank() } }
        .getOrDefault(emptyList())

/** 一张图的落盘名：地址的散列，没有后缀。阅读那一头和写入那一头必须按同一个算法认它。 */
fun comicImageName(imageUrl: String): String = sha1(imageUrl)

/**
 * 内容开头那几个魔数 → 这是不是一张图。
 *
 * 不看后缀也不看 Content-Type：瓜子漫画的 CDN 同一个 `.webp` 地址会按协商一会儿回
 * `image/webp`、一会儿回 `image/jpeg`，而 `BitmapFactory` 认的是字节开头 —— 按名字判会把好图丢掉。
 */
fun looksLikeImage(b: ByteArray): Boolean = when {
    b.size > 3 && b[0] == 0xFF.toByte() && b[1] == 0xD8.toByte() -> true
    b.size > 8 && b[0] == 0x89.toByte() && b[1] == 'P'.code.toByte() && b[2] == 'N'.code.toByte() -> true
    b.size > 12 && b.at(0, "RIFF") && b.at(8, "WEBP") -> true
    b.size > 6 && b.at(0, "GIF8") -> true
    else -> false
}

/** 从 [from] 起是不是那串 ASCII（ByteArray 没有 regionMatches 那套重载，比字节就行）。漫画、有声两族都用它。 */
internal fun ByteArray.at(from: Int, s: String): Boolean {
    if (from + s.length > size) return false
    for (i in s.indices) if (this[from + i] != s[i].code.toByte()) return false
    return true
}

private fun sha1(s: String): String =
    MessageDigest.getInstance("SHA-1").digest(s.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
