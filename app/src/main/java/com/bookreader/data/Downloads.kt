package com.bookreader.data

import android.content.Context
import android.app.NotificationChannel
import android.app.NotificationManager
import androidx.work.BackoffPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.WorkInfo
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import com.bookreader.booksource.BookHit
import com.bookreader.booksource.HttpFetcher
import com.bookreader.booksource.Rule
import com.bookreader.booksource.SearchEngine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.security.MessageDigest

/**
 * 下载这一层的门面：排队、取消、读历史。
 *
 * 一本书 = 一个 unique work（名字 = `dl-` + 书页地址的 SHA-1）：连点两次只会排一次，
 * 排过了就 KEEP —— 重复排队会让两份 worker 抢同一份限速、往同一个章缓存里写。
 *
 * 点下去要**立刻**在表上留一行"下载中"：从点击到 worker 真起来有一两秒，那中间界面什么都没有，
 * 人会以为没点上再点一次。
 */
object Downloads {

    /** 一个实例一份 cookie 罐：这些站几乎每个都要"先访问一次首页拿会话"，别每次搜索都换个新罐。 */
    private val fetcher = HttpFetcher()

    /** 搜一条源。失败就抛，让界面把服务端那句原话贴出来（"搜不动：HTTP 503"比"搜索失败"有用）。 */
    suspend fun search(rule: Rule, keyword: String): List<BookHit> = withContext(Dispatchers.IO) {
        SearchEngine(rule, fetcher).search(keyword)
    }

    fun history(ctx: Context) = DownloadHistory(File(ctx.filesDir, DownloadWorker.HISTORY))

    fun list(ctx: Context): List<DownloadRecord> = history(ctx).load().sortedByDescending { it.startedAt }

    /**
     * 把"写着下载中、可队列里已经没有这个作业"的行改成"已停止"。
     *
     * 为什么必须有这一格：那一行是点下去时**乐观**写的（不然从点击到 worker 起来那一两秒里
     * 界面什么都没有，人会再点一次）。代价是反过来的情形 —— worker 没跑起来（系统挡了自启动、
     * 初始化失败、或者你直接把应用从最近任务里划掉），就再也没人改那一行，
     * 界面上会永远挂着一颗不动的「下载中」。
     */
    fun reconcile(ctx: Context): List<DownloadRecord> {
        val h = history(ctx)
        val stale = h.load().filter { it.running }
        if (stale.isNotEmpty()) {
            val wm = runCatching { WorkManager.getInstance(ctx) }.getOrNull()
            stale.forEach { r ->
                val live = runCatching {
                    wm?.getWorkInfosForUniqueWork(nameOf(r.bookUrl))?.get()
                        ?.any { it.state == WorkInfo.State.RUNNING || it.state == WorkInfo.State.ENQUEUED }
                }.getOrNull()
                when (live) {
                    null -> if (wm == null) {
                        r.state = DownloadRecord.STATE_CANCELLED
                        r.error = "后台队列起不来（这台机的 WorkManager 没初始化成功），章缓存在磁盘上没丢"
                        r.endedAt = System.currentTimeMillis()
                        h.put(r)
                    }
                    false -> {
                        r.state = DownloadRecord.STATE_CANCELLED
                        r.error = "没跑起来或被系统挡了（MIUI 要允许本应用自启动 + 省电策略设成无限制）—— 点「续下」从没抓的那一章接着来"
                        r.endedAt = System.currentTimeMillis()
                        h.put(r)
                    }
                    else -> {}
                }
            }
        }
        return h.load().sortedByDescending { it.startedAt }
    }

    /** @param head >0 = 只拉目录开头这几章（界面上那颗「先拉开头」）；0 = 整本。 */
    fun start(ctx: Context, rule: Rule, hit: BookHit, update: Boolean, head: Int = 0) {
        ensureChannel(ctx)
        val h = history(ctx)
        val rec = h.find(hit.bookUrl) ?: DownloadRecord(
            bookUrl = hit.bookUrl, ruleId = rule.id, bookName = hit.bookName,
            author = hit.author, sourceName = rule.name,
        )
        rec.state = DownloadRecord.STATE_RUNNING
        rec.error = ""
        if (rec.startedAt == 0L || update) rec.startedAt = System.currentTimeMillis()
        h.put(rec)
        WorkManager.getInstance(ctx).enqueueUniqueWork(
            nameOf(hit.bookUrl),
            ExistingWorkPolicy.KEEP,
            OneTimeWorkRequestBuilder<DownloadWorker>()
                .setInputData(DownloadWorker.input(rule, hit, update, head))
                // 退避是 WorkRequest 那一层的参数（Configuration.Builder 上没有这个方法）。
                // 指数而不是线性：源站限流时越急着重试越容易被继续挡。
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, java.time.Duration.ofSeconds(10))
                .build(),
        )
    }

    /** 取消只改队列；那一行由 worker 自己写成"已取消"（它知道已经抓到哪一章）。 */
    fun cancel(ctx: Context, bookUrl: String) {
        WorkManager.getInstance(ctx).cancelUniqueWork(nameOf(bookUrl))
    }

    /**
     * 暂停 = 先在这一行上写下"已暂停"（连同已经抓到第几章），再把队列里的作业撤掉。
     *
     * 顺序不能反：撤队列之后 worker 会立刻往这一行写"已取消"，那一来得比这次写更快，
     * 界面上就会闪一下"已取消"再变回"已暂停"。worker 那边也认这个状态，不覆盖它。
     */
    fun pause(ctx: Context, bookUrl: String) {
        val h = history(ctx)
        h.find(bookUrl)?.let {
            it.state = DownloadRecord.STATE_PAUSED
            it.endedAt = System.currentTimeMillis()
            h.put(it)
        }
        cancel(ctx, bookUrl)
    }

    /** 删记录 = 连它那份章缓存一起删：留着是永远读不到的垃圾字节。
     *  缓存目录让 BookSourceStore 自己算 —— 路径规则抄第二份，迟早和它俩不一致。 */
    fun forget(ctx: Context, sources: BookSourceStore, rec: DownloadRecord) {
        cancel(ctx, rec.bookUrl)
        sources.cacheDir(rec.ruleId, rec.bookUrl).deleteRecursively()
        history(ctx).remove(rec.bookUrl)
    }

    fun runningCount(ctx: Context): Int = history(ctx).load().count {
        it.state == DownloadRecord.STATE_RUNNING || it.state == DownloadRecord.STATE_FAILED
    }

    /**
     * 通知渠道：两处都要保证在（首次排队时、以及进程被杀后 WorkManager 自己重启 worker 时）。
     * 建渠道是幂等的，所以宁可各调用一次，也不去赌"应用一定先跑过 onCreate"。
     */
    fun ensureChannel(ctx: Context) {
        val m = ctx.getSystemService(NotificationManager::class.java)
        if (m.getNotificationChannel(CHANNEL) == null) {
            m.createNotificationChannel(
                NotificationChannel(CHANNEL, "下载", NotificationManager.IMPORTANCE_LOW)
                    .apply { description = "抓书时的进度；低优先级 = 不响也不弹，只在栏里待着" },
            )
        }
    }

    const val CHANNEL = "download"

    private fun nameOf(bookUrl: String) = "dl-" + sha1(bookUrl)

    /** 和 BookSourceStore 那颗同名不同体：这里只用来当队列名，不碰磁盘路径了。 */
    private fun sha1(s: String): String =
        MessageDigest.getInstance("SHA-1").digest(s.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
}
