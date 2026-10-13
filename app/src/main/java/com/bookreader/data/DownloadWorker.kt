package com.bookreader.data

import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import com.bookreader.R
import com.bookreader.booksource.BookDownloader
import com.bookreader.booksource.PageRequest
import com.bookreader.booksource.coverUrl
import android.graphics.BitmapFactory
import com.bookreader.booksource.BookHit
import com.bookreader.booksource.DownloadOutcome
import com.bookreader.booksource.FileChapterStore
import com.bookreader.booksource.HttpFetcher
import com.bookreader.booksource.Rule
import com.bookreader.booksource.writeBookText
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException

/**
 * 一本书的下载 = 一个 WorkManager worker。
 *
 * 为什么要换到这里：原先 `center.start()` 收的是**那一行 composable 的** `rememberCoroutineScope()`，
 * 列表把它滚出去、切个 tab、退出搜书那一格，下载就跟着被取消 —— 一本书能不能下完，
 * 取决于那一行还在不在屏幕上。WorkManager 的 worker 挂在系统上，切走、锁屏、进程被杀都会续跑或重启。
 *
 * 进度落在 `history.tsv` 上（界面上那行数字读它），通知栏那条只是同一份数的另一种呈现 ——
 * 13 起通知要运行时权限，没给也能下载，所以数字不能只活在通知里。
 */
class DownloadWorker(appContext: Context, params: WorkerParameters) : CoroutineWorker(appContext, params) {

    private val history = DownloadHistory(File(appContext.filesDir, HISTORY))

    override suspend fun doWork(): Result {
        val ctx = applicationContext
        val ruleId = inputData.getString(KEY_RULE).orEmpty()
        val bookUrl = inputData.getString(KEY_URL).orEmpty()
        val title = inputData.getString(KEY_NAME).orEmpty()
        if (ruleId.isEmpty() || bookUrl.isEmpty()) return Result.failure()

        val rule = BookSourceStore(ctx).list().firstOrNull { it.rule?.id == ruleId }?.rule
        if (rule == null) {
            finish(DownloadRecord.STATE_FAILED, error = "这条源（$ruleId）已经不在了，剩下的章没法接着抓")
            return Result.failure()
        }
        val hit = BookHit(
            sourceId = ruleId,
            sourceName = rule.name,
            bookName = title,
            author = inputData.getString(KEY_AUTHOR).orEmpty(),
            bookUrl = bookUrl,
        )
        val rec = history.find(bookUrl) ?: DownloadRecord(bookUrl, ruleId, title, hit.author, rule.name)
        rec.state = DownloadRecord.STATE_RUNNING
        rec.error = ""
        if (rec.startedAt == 0L) rec.startedAt = System.currentTimeMillis()
        history.put(rec)
        // 进程被杀后 WorkManager 自己重启这个 worker，那时没人跑过「首次排队」，渠道可能不存在
        Downloads.ensureChannel(ctx)
        setForegroundAsync(info(rec))

        val update = inputData.getBoolean(KEY_UPDATE, false)
        val head = inputData.getInt(KEY_HEAD, 0)
        // 三类源交回来的东西不一样：小说是一整串文字、漫画是几万个图文件、文献是一支 PDF。
        // 抓和入库各走各的头，共用的是这一行的进度、取消、退避重试和那张下载表
        if (!rule.isPaper && !rule.isComic) {
            val store = FileChapterStore(BookSourceStore(ctx).cacheDir(ruleId, bookUrl))
            val novel = try {
                BookDownloader(rule, HttpFetcher(), store)
                    .download(hit, shouldStop = { isStopped }, limit = head) { done, total -> progress(rec, done, total) }
            } catch (e: Exception) {
                if (isStopped) {
                    withContext(NonCancellable) { cancelled(rec) }
                    return Result.success()
                }
                return failed(rec, e)
            }
            finishNovel(ctx, rule, hit, rec, novel, update)
            return if (rec.state == DownloadRecord.STATE_DONE) Result.success() else Result.failure()
        }
        if (rule.isComic) return runComic(ctx, rule, hit, rec, head)
        return runPaper(ctx, rule, hit, rec)
    }

    /** 小说：整串文字抓完 → 进 [BookLibrary.importFile] 那一条路，抽出正文、建 chunk/章索引。 */
    private suspend fun finishNovel(
        ctx: Context,
        rule: Rule,
        hit: BookHit,
        rec: DownloadRecord,
        outcome: DownloadOutcome,
        update: Boolean,
    ) {
        val title = hit.bookName
        val bookUrl = hit.bookUrl
        if (isStopped) {
            withContext(NonCancellable) { cancelled(rec) }
            return
        }
        if (outcome.body.isBlank()) {
            rec.state = DownloadRecord.STATE_FAILED
            rec.error = "一本都没抓到（这一族源可能改了版式，或者它要跑 JS / 过代理）"
            rec.endedAt = System.currentTimeMillis()
            history.put(rec)
            return
        }
        rec.missing = outcome.failed.size
        rec.chars = outcome.chars
        val staged = File(ctx.cacheDir, "ingest-" + System.currentTimeMillis() + ".txt")
        val meta = runCatching {
            BookLibrary(ctx).importFile(writeBookText(staged, outcome), title, into = if (update) rec.bookId.ifEmpty { null } else null)
        }
        meta.onSuccess {
            // 来源 + 简介 + 大纲：「本书信息」那一页读这三个文件，「全本」那一颗也靠它找回书页
            BookProvenance(BookLibrary(ctx).dir(it.id)).save(rule.id, bookUrl, outcome.intro, outcome.outline)
            // 封面单独一趟：抓不到不算这次下载失败（一大半书本来就没封面），什么都不写，
            // 书架上那本走默认封面。放在正文抓完之后再发，是为了不为一幅图挡住目录那一枪
            runCatching { saveCover(it.id, rule, bookUrl) }
            rec.state = DownloadRecord.STATE_DONE
            rec.bookId = it.id
            rec.done = outcome.chapters
            rec.total = outcome.chapters
            rec.endedAt = System.currentTimeMillis()
        }.onFailure {
            staged.delete()
            rec.state = DownloadRecord.STATE_FAILED
            rec.error = "抓到了但进不了书架：" + (it.message ?: it.javaClass.simpleName)
            rec.endedAt = System.currentTimeMillis()
        }
        history.put(rec)
    }

    /**
     * 一本漫画：图直接写进书目录，写完把清单和数字落到下载表那一行。
     *
     * id 在这里就定下来并写进表：一本漫画要抓几千张、中间进程随时会被系统收回，
     * 那一行要是还不认得自己写的是哪一本，下一次点「下载全书」就只能另起一本 —— 架上又多一本。
     */
    private suspend fun runComic(ctx: Context, rule: Rule, hit: BookHit, rec: DownloadRecord, head: Int): Result {
        val id = shelfId(ctx, hit, rec)
        val done = try {
            ComicImport.run(ctx, rule, hit, id, head, { isStopped }) { d, t -> progress(rec, d, t) }
        } catch (e: Exception) {
            if (isStopped) {
                withContext(NonCancellable) { cancelled(rec) }
                return Result.success()
            }
            return failed(rec, e)
        }
        if (isStopped) {
            withContext(NonCancellable) { cancelled(rec) }
            return Result.success()
        }
        rec.missing = done.failed.size
        // 漫画数的是"张"，不是"字"：把张数塞进 chars 那一列，界面上那行就写成"1,830 字"的假话
        rec.chars = 0
        rec.state = DownloadRecord.STATE_DONE
        rec.bookId = done.bookId
        rec.done = done.chapters
        rec.total = done.chapters
        rec.endedAt = System.currentTimeMillis()
        runCatching { saveCover(done.bookId, rule, hit.bookUrl) }
        history.put(rec)
        android.util.Log.d("ComicDl", "入库 ${done.bookId} 章=${done.onDisk}/${done.chapters} 图=${done.images} 缺=${done.failed.size}")
        return Result.success()
    }

    /** 一篇文献：那一支 PDF 直接进书目录，开的是现成的整页阅读器。 */
    private suspend fun runPaper(ctx: Context, rule: Rule, hit: BookHit, rec: DownloadRecord): Result {
        val id = shelfId(ctx, hit, rec)
        val done = try {
            PaperImport.run(ctx, rule, hit, id) { d, t -> progress(rec, d, t) }
        } catch (e: Exception) {
            if (isStopped) {
                withContext(NonCancellable) { cancelled(rec) }
                return Result.success()
            }
            return failed(rec, e)
        }
        if (isStopped) {
            withContext(NonCancellable) { cancelled(rec) }
            return Result.success()
        }
        rec.missing = 0
        rec.chars = 0
        rec.state = DownloadRecord.STATE_DONE
        rec.bookId = done.bookId
        rec.done = 1
        rec.total = 1
        rec.endedAt = System.currentTimeMillis()
        history.put(rec)
        return Result.success()
    }

    /** 这一行该写进哪一本书：表上那本还在就用它，没有就现起一个 id 并马上落表（理由见 [runComic]）。 */
    private fun shelfId(ctx: Context, hit: BookHit, rec: DownloadRecord): String {
        if (rec.bookId.isNotEmpty() && BookLibrary(ctx).dir(rec.bookId).isDirectory) return rec.bookId
        val id = BookLibrary(ctx).newId(hit.bookName)
        rec.bookId = id
        history.put(rec)
        return id
    }

    /**
     * 进度：写表 + 通知，节流那一格在 [shouldWrite]（千万字一本时逐章重写整张表会把磁盘当成瓶颈）。
     *
     * 这一格**不是** suspend：它是当回调传给下载器的，而下载器那一头的签名是普通的 `(Int, Int) -> Unit`
     * —— 挂起函数当不进那个签名，编译期就拦住了（挂上去的第一反应应该是把这两个调用摊平，不是把签名改成 suspend）。
     */
    private fun progress(rec: DownloadRecord, done: Int, total: Int) {
        rec.done = done
        rec.total = total
        if (shouldWrite(rec, total)) {
            history.put(rec)
            setForegroundAsync(info(rec))
        }
    }

    /** 一次抓取抛出来的错。网络那一类交给 WorkManager 退避重试，其余写进那一行让人看得见。 */
    private fun failed(rec: DownloadRecord, e: Exception): Result {
        // 章级/图级缓存在磁盘上，重试时已经抓到的那些一个请求都不发 —— 这就是"断点"在这里的落点
        if (e is IOException && runAttemptCount < MAX_ATTEMPTS) return Result.retry()
        rec.error = e.message ?: e.javaClass.simpleName
        rec.state = DownloadRecord.STATE_FAILED
        rec.endedAt = System.currentTimeMillis()
        history.put(rec)
        return Result.failure()
    }

    /** 书页 → 封面地址 → 图。任何一环取不到就静默返回（没封面是正常态，不是故障）。 */
    private suspend fun saveCover(bookId: String, rule: Rule, bookUrl: String) {
        if (rule.cover.isBlank()) return
        val fetch = HttpFetcher()
        val page = runCatching { fetch.fetch(PageRequest(bookUrl)) }.getOrNull() ?: return
        val url = coverUrl(rule, page.text(rule.charset), page.finalUrl)
        if (url.isEmpty()) return
        val img = runCatching { fetch.fetch(PageRequest(url)) }.getOrNull() ?: return
        // 站点没封面时往往回一张 1x1 或几十 KB 的通用图：太小/太大都不当封面存下来，
        // 存了会比"没有"更难看（书架上一排同一张缩糊图）
        if (img.bytes.size < MIN_COVER_BYTES || img.bytes.size > MAX_COVER_BYTES) return
        if (runCatching { BitmapFactory.decodeByteArray(img.bytes, 0, img.bytes.size) }.getOrNull() == null) return
        val f = BookLibrary(applicationContext).coverFile(bookId)
        f.parentFile?.mkdirs()
        f.writeBytes(img.bytes)
    }

    private suspend fun cancelled(rec: DownloadRecord) {
        // 用户按的是「暂停」时这一行已经是"已暂停"：那是他自己选的字，别覆盖成"已取消"
        if (history.find(rec.bookUrl)?.state == DownloadRecord.STATE_PAUSED) return
        rec.state = DownloadRecord.STATE_CANCELLED
        rec.endedAt = System.currentTimeMillis()
        history.put(rec)
    }

    private fun finish(state: String, error: String) {
        val bookUrl = inputData.getString(KEY_URL).orEmpty()
        val rec = history.find(bookUrl) ?: return
        rec.state = state
        rec.error = error
        rec.endedAt = System.currentTimeMillis()
        history.put(rec)
    }

    /** 每 20 章或每 3 秒写一次：千万字一本时逐章重写整张表，会把磁盘当成瓶颈。 */
    private fun shouldWrite(rec: DownloadRecord, total: Int): Boolean {
        val now = System.currentTimeMillis()
        if (rec.done >= total) return true
        if (rec.done - lastWriteChapter >= 20 || now - lastWriteAt >= 3_000L) {
            lastWriteChapter = rec.done
            lastWriteAt = now
            return true
        }
        return false
    }

    private var lastWriteChapter = -20
    private var lastWriteAt = 0L

    private fun info(rec: DownloadRecord) = ForegroundInfo(
        NOTI_ID,
        NotificationCompat.Builder(applicationContext, Downloads.CHANNEL)
            .setSmallIcon(R.drawable.ic_download)
            .setContentTitle(rec.bookName)
            .setContentText(rec.state + " " + rec.done + " / " + rec.total + " 章")
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setProgress(rec.total.coerceAtLeast(1), rec.done, false)
            .build(),
        if (Build.VERSION.SDK_INT >= 29) ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC else 0,
    )

    companion object {
        const val KEY_RULE = "rule"
        const val KEY_URL = "url"
        const val KEY_NAME = "name"
        const val KEY_AUTHOR = "author"
        const val KEY_UPDATE = "update"
        const val KEY_HEAD = "head"
        const val HISTORY = "downloads/history.tsv"
        private const val NOTI_ID = 0x4D1
        private const val MAX_ATTEMPTS = 4
        private const val MIN_COVER_BYTES = 4 * 1024
        private const val MAX_COVER_BYTES = 2 * 1024 * 1024

        fun input(rule: Rule, hit: BookHit, update: Boolean, head: Int = 0): Data = Data.Builder()
            .putString(KEY_RULE, rule.id)
            .putString(KEY_URL, hit.bookUrl)
            .putString(KEY_NAME, hit.bookName)
            .putString(KEY_AUTHOR, hit.author)
            .putBoolean(KEY_UPDATE, update)
            .putInt(KEY_HEAD, head)
            .build()
    }
}
