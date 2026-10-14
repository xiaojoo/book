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
 * 抓一本有声书：目录 → 每一集那段音频的地址 → 每一段的字节。
 *
 * 形状上和 [ComicDownloader] 是同一族，不是小说那条路上加个分支：小说交出**一整串文字**（内存里装得下），
 * 有声书交出的是**几万个字节的文件**（一集几十 MB，整本几个 GB，装不下也不该装）。
 * 限速、重试、并发、断点这四件事的口径和漫画那边保持一致，判据也照同一族写。
 *
 * 落盘的形状（`AudioStore` 的根目录下）：
 * ```text
 * <集地址的 sha1>/parts.tsv           这一集的音频地址，一行一段，顺序就是播放顺序
 * <集地址的 sha1>/<音频地址的 sha1>    那一段本身（没有后缀：解码看的是字节，不是文件名）
 * ```
 * 键都用地址的散列而不是序号：和漫画同一份理由 —— 站点补一集、或者把某一集的段序动了，
 * 按序号存的会张冠李戴，而"文件名对、内容是另一段"这种错在音频上比在小说上更难发现。
 */
class AudioDownloader(
    private val rule: Rule,
    private val fetch: Fetcher,
    private val store: AudioStore,
    /** 限速要能被测：真实实现是 Thread.sleep + 系统时钟，测试里给假的，否则判据只能靠等。 */
    private val sleep: (Long) -> Unit = { if (it > 0) Thread.sleep(it) },
    private val clock: () -> Long = { System.currentTimeMillis() },
    private val random: Random = Random(),
) {

    /**
     * @param onProgress (已完成的集数, 总集数) —— 和小说/漫画那两条同一个口径，界面上那一行不用认第三种数。
     * @param limit 0 = 整本；>0 = 只抓目录开头这几集（「先听一集」那一颗：音频一集几十 MB，
     *   "先拉开头"拉十章的写法在这里等于拉十集几个 GB，所以这一族的"头"是一集）。
     * @param onChapter **每一集抓完就地**回调一次（几条线程并发，实现要自己受得住）。
     *   这一格是为"抓一半停了"准备的：集表只在整本结束时写一次的话，中途被杀或按暂停，
     *   磁盘上几十集的音频就没人记得是哪一集、叫什么名。
     */
    fun download(
        hit: BookHit,
        shouldStop: () -> Boolean = { false },
        limit: Int = 0,
        onChapter: (AudioChapter) -> Unit = {},
        onProgress: (Int, Int) -> Unit = { _, _ -> },
    ): AudioOutcome {
        val head = SearchEngine(rule, fetch).head(hit.bookUrl)
        val all = head.chapters
        if (all.isEmpty()) {
            return AudioOutcome(hit.bookName, hit.author, 0, 0, 0L, emptyList(), all.map { it.first }, head.intro)
        }
        val chapters = if (limit <= 0) all else all.take(limit)
        val total = chapters.size
        onProgress(0, total)
        val done = AtomicInteger(0)
        val failed = Collections.synchronizedList(ArrayList<String>())
        val got = Collections.synchronizedList(ArrayList<AudioChapter>())
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
                    val one = runCatching { fetchChapter(hit.bookUrl, url, shouldStop, gate) }
                        .onFailure { if (shouldStop()) return@Runnable }
                        .getOrNull()
                    if (one == null || one.parts == 0) failed += name else {
                        val c = AudioChapter(i, name, one.dir, one.parts)
                        // 落地就报，不等整本：抓一半被杀/按暂停时那一半在集表里已经是真的
                        got += c
                        onChapter(c)
                    }
                    onProgress(done.incrementAndGet(), total)
                }
            }
            for (t in tasks) pool.execute(t)
            pool.shutdown()
            // 一集几十 MB、慢站一段要拉几分钟：上限给得很松，网络卡的站不该把一次下载变成无限期挂住
            if (!pool.awaitTermination(120L, TimeUnit.MINUTES)) pool.shutdownNow()
        } finally {
            pool.shutdownNow()
        }
        return AudioOutcome(
            title = hit.bookName,
            author = hit.author,
            chapters = total,
            parts = store.parts(),
            bytes = store.bytes(),
            failed = failed.toList(),
            outline = all.map { it.first },
            intro = head.intro,
            // 按目录顺序交出去：写集表的那一头要的是"第几集"，不是"哪一条线程先跑完"
            got = got.sortedBy { it.index },
        )
    }

    /**
     * 一集：先拿那一段（或几段）地址，再逐段取音频。
     *
     * 地址表要**先落盘再取音频**：取到一半进程被杀，下次进来还知道这一集本来该有几段、
     * 缺的是哪几段。反过来会留下一堆没人认领的文件。
     *
     * `@self` 那一档（[Rule.selfAudio]）没有"集页"这一层：目录里那一条地址本身就是文件，
     * 所以**一次集页请求都不发** —— 发出去就是拿 10MB 的音频当一页 HTML 读回来再解一遍。
     */
    private fun fetchChapter(bookUrl: String, chapterUrl: String, shouldStop: () -> Boolean, gate: () -> Unit): One {
        val urls = if (rule.selfAudio) {
            listOf(chapterUrl)
        } else {
            val page = fetch.fetch(PageRequest(chapterUrl))
            chapterAudio(rule, page.text(rule.charset), page.finalUrl)
        }
        if (urls.isEmpty()) return One("", 0)
        val dir = store.putParts(chapterUrl, urls)
        var landed = 0
        for (u in urls) {
            // 每段之前问一次取消：一集几十 MB 要拉几分钟，只在集头问的话按了暂停还要等一整集
            if (shouldStop()) break
            if (store.has(dir, u)) { landed += 1; continue }
            val bytes = fetchPart(if (rule.selfAudio) bookUrl else chapterUrl, u, gate) ?: continue
            store.put(dir, u, bytes)
            landed += 1
        }
        // 成不成按**真落地的段数**算，不按地址表：一段地址写着一集该有三段、实际一段都没拿到，
        // 算成功的话表现就是"每一集都下到了，一集都放不出"。少一段也不行 —— 断在半截的一集
        // 播到一半就停，听着像"这本就这么长"；把它记进缺集，下次进来 has() 会把已有的段跳过、只补缺的
        return One(dir, if (landed == urls.size) urls.size else 0)
    }

    /** 一集抓到的一点结果：仓里的目录名 + 该有几段。 */
    private data class One(val dir: String, val parts: Int)

    /**
     * 一段音频：非 2xx、字节太小、开头不是那几个魔数之一，都算这一次没成，重试到 maxAttempts。
     *
     * "不是音频"要当失败而不是当成功：会话被这些站踢掉时回的是一张 **200 的 HTML 登录页**，
     * 存下来播放器解不开，表现是"每一集都下到了，一集都放不出"。
     */
    private fun fetchPart(chapterUrl: String, url: String, gate: () -> Unit): ByteArray? {
        repeat(max(1, rule.crawl.maxAttempts)) {
            gate()
            val bytes = runCatching {
                // 和漫画同一口径：把"这一段所在的那一集页面"自己当 Referer 发出去（防盗链的图床/音频 CDN 按这格决定给不给）
                fetch.fetch(PageRequest(url, referer = chapterUrl)).bytes
            }.getOrNull()
            if (bytes != null && bytes.size >= MIN_PART_BYTES && looksLikeAudio(bytes)) return bytes
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
        /** 小于一段真实音频下限的不当成"抓到了"：32kbps 的最低档也是一秒 4KB，HTML 登录页里能过魔数那关的没有。 */
        const val MIN_PART_BYTES = 4096
    }
}

/** 抓完一本有声书的账面数字。音频本身在磁盘上，这里只报"够不够"。 */
data class AudioOutcome(
    val title: String,
    val author: String,
    val chapters: Int,
    /** 仓里一共几段（整本账面）。 */
    val parts: Int,
    /** 仓里音频的总字节数：有声书没有"字数"，书架上那一格账面写的是它。 */
    val bytes: Long,
    /** 一段都没抓到的集名，按目录顺序。空 = 每一集都有音频。 */
    val failed: List<String>,
    /** **整本**的集名，和 limit 无关 —— 「先听一集」那一本也该看得见全书有多少集。 */
    val outline: List<String>,
    /** 书页上那段简介（规则没写这一格就是空串）。 */
    val intro: String = "",
    /** 这一次真在磁盘上有音频的那几集（目录顺序）。落 `chapters.tsv` 的就是它。 */
    val got: List<AudioChapter> = emptyList(),
) {
    val complete: Boolean get() = failed.isEmpty()
}

/** 一集在磁盘上的样子：第几集、集名、仓里那个目录名、该有几段。 */
data class AudioChapter(val index: Int, val title: String, val dir: String, val parts: Int)

/**
 * 音频仓。`:app` 给的是落在书目录里的那一份，判据给的是内存里的那一份。
 *
 * 不复用 [ComicStore] 那份接口也不是零成本，但账面多了一格：漫画数"张"，有声书还要记"多少字节"
 * （清单的 contentBytes 写的是它），同一张接口塞两种账面会让漫画那边跟着认一个永远用不上的数。
 */
interface AudioStore {
    /** 记下这一集该有哪些段（一行一个地址），返回这一集的目录名。 */
    fun putParts(chapterUrl: String, urls: List<String>): String

    /** 这一段已经在仓里了（断点续抓就靠这一格，不再打站子）。 */
    fun has(dir: String, audioUrl: String): Boolean

    fun put(dir: String, audioUrl: String, bytes: ByteArray)

    /** 仓里一共几段。 */
    fun parts(): Int

    /** 仓里一共多少字节。 */
    fun bytes(): Long
}

/** 落盘的音频仓：根目录 = 那本书目录下的 `audio/`。 */
class FileAudioStore(private val root: File) : AudioStore {

    init {
        if (!root.isDirectory) root.mkdirs()
    }

    override fun putParts(chapterUrl: String, urls: List<String>): String {
        val dir = sha1(chapterUrl)
        val box = File(root, dir)
        box.mkdirs()
        // 先 .part 再改名（和 FileComicStore 同一份写法）：中途被杀只留一个没人认的临时件，
        // 不会留下一份"看着在、其实少三行"的地址表
        val f = File(box, PARTS)
        val tmp = File(box, "$PARTS.part")
        tmp.writeText(urls.joinToString("\n") + "\n", Charsets.UTF_8)
        if (!tmp.renameTo(f)) {
            f.delete()
            tmp.renameTo(f)
        }
        return dir
    }

    override fun has(dir: String, audioUrl: String): Boolean =
        File(File(root, dir), audioPartName(audioUrl)).isFile

    override fun put(dir: String, audioUrl: String, bytes: ByteArray) {
        val box = File(root, dir).apply { mkdirs() }
        val f = File(box, audioPartName(audioUrl))
        val tmp = File(box, f.name + ".part")
        tmp.writeBytes(bytes)
        if (!tmp.renameTo(f)) {
            f.delete()
            tmp.renameTo(f)
        }
    }

    /** 只数"那一集该有 N 段"里数出来的真音频：`parts.tsv` 是地址表不是音频，`.part` 是没写完的垃圾。 */
    private fun realFiles(d: File): Array<File>? =
        d.listFiles { f -> f.isFile && f.name != PARTS && !f.name.endsWith(".part") }

    override fun parts(): Int = root.listFiles()?.sumOf { d ->
        if (!d.isDirectory) 0 else realFiles(d)?.size ?: 0
    } ?: 0

    override fun bytes(): Long = root.listFiles()?.sumOf { d ->
        if (!d.isDirectory) 0L else realFiles(d)?.sumOf { it.length() } ?: 0L
    } ?: 0L

    companion object {
        /** 一集的地址表叫什么：读它的那一头（播放页）和写它的那一头（这里）必须是同一个名字。 */
        const val PARTS = "parts.tsv"
    }
}

/** `parts.tsv` 里那一行一个地址的表，读回播放顺序。调用方（播放那一头）按这个顺序连播。 */
fun audioPartOrder(chapterDir: File): List<String> =
    runCatching { File(chapterDir, FileAudioStore.PARTS).readLines(Charsets.UTF_8).filter { it.isNotBlank() } }
        .getOrDefault(emptyList())

/** 一段音频的落盘名：地址的散列，没有后缀。播放那一头和写入那一头必须按同一个算法认它。 */
fun audioPartName(audioUrl: String): String = sha1(audioUrl)

/**
 * 内容开头那几个魔数 → 这是不是一段音频。
 *
 * 不看后缀也不看 Content-Type：有声站把地址写成 `.mp3`、CDN 回的却可能是
 * `application/octet-stream`（一部分站就这么发真音频），而会话被踢时回的又是一张
 * `text/html` 的登录页 —— 按名字判要么把好音频丢掉、要么把登录页存进去。
 * 认的几族：ID3 / MPEG 帧同步（MP3）、`ftyp`（MP4/M4A）、`OggS`、`fLaC`、`RIFF…WAVE`。
 */
fun looksLikeAudio(b: ByteArray): Boolean = when {
    b.size > 3 && b.at(0, "ID3") -> true
    // MPEG 帧同步：首字节 0xFF，次字节高三位是 111（同步 + 版本位），低几位才是各码率/采样率的槽 ——
    // CBR/VBR 的 MP3 都从这里开始；HTML/JSON 的开头（'<' '{'）撞不上
    b.size > 2 && b[0] == 0xFF.toByte() && (b[1].toInt() and 0xE0) == 0xE0 -> true
    b.size > 8 && b.at(4, "ftyp") -> true
    b.size > 4 && b.at(0, "OggS") -> true
    b.size > 4 && b.at(0, "fLaC") -> true
    b.size > 12 && b.at(0, "RIFF") && b.at(8, "WAVE") -> true
    else -> false
}

private fun sha1(s: String): String =
    MessageDigest.getInstance("SHA-1").digest(s.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
