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
import com.bookreader.booksource.BookHit
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
        val store = FileChapterStore(BookSourceStore(ctx).cacheDir(ruleId, bookUrl))
        val outcome = try {
            BookDownloader(rule, HttpFetcher(), store)
                .download(hit, shouldStop = { isStopped }) { done, total ->
                    rec.done = done
                    rec.total = total
                    if (shouldWrite(rec, total)) {
                        history.put(rec)
                        setForegroundAsync(info(rec))
                    }
                }
        } catch (e: Exception) {
            if (isStopped) {
                withContext(NonCancellable) { cancelled(rec) }
                return Result.success()
            }
            // 网络那一类的错交给 WorkManager 退避重试（指数，首次 10 秒）；章级缓存在磁盘上，
            // 重试时已抓到的章一个请求都不发 —— 这就是"断点"在这里的真正落点
            if (e is IOException && runAttemptCount < MAX_ATTEMPTS) return Result.retry()
            rec.error = e.message ?: e.javaClass.simpleName
            rec.state = DownloadRecord.STATE_FAILED
            rec.endedAt = System.currentTimeMillis()
            history.put(rec)
            return Result.failure()
        }

        if (isStopped) {
            withContext(NonCancellable) { cancelled(rec) }
            return Result.success()
        }
        if (outcome.body.isBlank()) {
            rec.state = DownloadRecord.STATE_FAILED
            rec.error = "一本都没抓到（这一族源可能改了版式，或者它要跑 JS / 过代理）"
            rec.endedAt = System.currentTimeMillis()
            history.put(rec)
            return Result.failure()
        }
        rec.missing = outcome.failed.size
        rec.chars = outcome.chars
        val staged = File(ctx.cacheDir, "ingest-" + System.currentTimeMillis() + ".txt")
        val meta = runCatching {
            BookLibrary(ctx).importFile(writeBookText(staged, outcome), title, into = if (update) rec.bookId.ifEmpty { null } else null)
        }
        meta.onSuccess {
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
        return if (rec.state == DownloadRecord.STATE_DONE) Result.success() else Result.failure()
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
        const val HISTORY = "downloads/history.tsv"
        private const val NOTI_ID = 0x4D1
        private const val MAX_ATTEMPTS = 4

        fun input(rule: Rule, hit: BookHit, update: Boolean): Data = Data.Builder()
            .putString(KEY_RULE, rule.id)
            .putString(KEY_URL, hit.bookUrl)
            .putString(KEY_NAME, hit.bookName)
            .putString(KEY_AUTHOR, hit.author)
            .putBoolean(KEY_UPDATE, update)
            .build()
    }
}
