package com.bookreader.data

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import com.bookreader.booksource.HttpFetcher
import com.bookreader.booksource.PageRequest
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import java.util.concurrent.ConcurrentHashMap

/** 封面那一族图的内存上限：堆的八分之一，6..24MB（一张 100x100 解码后约 40KB，够放几百张）。 */
private val CACHE_BYTES = (Runtime.getRuntime().maxMemory() / 8)
    .coerceAtLeast(6L * 1024 * 1024)
    .coerceAtMost(24L * 1024 * 1024)
    .toInt()

/**
 * 列表页（搜书结果 / 精选榜单）那张封面：按地址现拉 + 按字节数限的内存缓存。
 *
 * 形状跟着 `ComicImages` 那一族走（限法、取样法同一套），差别是"列表"带来的三件：
 * - **同一地址只打一枪，且所有人都等同一枪**：一行滚出屏幕再滚回来会重新组合一次，不该为此
 *   再发一个请求；而"看到有人在打就直接空手返回"是错的 —— 见 [load] 里那段实测记录；
 * - **这一枪挂在进程上，不挂在调用方的那一行上**：那一行随时可能被回收，图不该跟着它一起没；
 * - **打过一次失败的也记住**：不然每滚一趟就把站点那台图床重新戳一遍，表现是列表越滚越卡。
 *   代价是这一张图在整个进程活着期间不会再试 —— 封面是装饰，挂了就挂着，默认封面本来就在下面兜着。
 *
 * 不发 `Referer`：随包十条源里真给封面的那四条（书海阁搜索页、图库漫画榜单、iTunes 接口、199mh 的
 * 榜单和搜索页）里，**前三条**实测同一张图带与不带回的是同一份字节（2026-10-26：62,437 / 26,268 /
 * 7,548 字节，两次逐位同）；199mh 是 2026-10-30 才进来的，没做那一次逐位同比对，但**不带 Referer
 * 在手机上出得来图**（精选那一屏的漫画分组当场从它的 `data-src` 画出两张封面，顶上写「6 条源 · 抓到 309 本」）。
 * 漫画正文那一族要 Referer 是另一家图床查防盗链（图库漫画 403 vs 200），不拿它给这一族预先加一层。
 */
object CoverImages {

    private val fetch = HttpFetcher(connectTimeoutMs = 8_000, readTimeoutMs = 12_000)
    private val cache = object : LruCache<String, Bitmap>(CACHE_BYTES) {
        override fun sizeOf(key: String, value: Bitmap) = value.byteCount
    }
    private val failed = ConcurrentHashMap.newKeySet<String>()

    /** 打图这一枪挂在进程上：调用方的那一行随时可能被回收，图不该跟着它一起没。 */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val jobs = ConcurrentHashMap<String, Deferred<Bitmap?>>()

    /** 已经在手上的先给：滚回来的一行不该空一帧再去拿。 */
    fun peek(url: String): Bitmap? = cache.get(url)

    /** 拿不到回 null，调用方退回默认封面。这一格不是功能，不值得为它报错。 */
    suspend fun load(url: String, targetPx: Int): Bitmap? {
        if (url.isEmpty() || targetPx <= 0 || url in failed) return null
        peek(url)?.let { return it }
        // 同一地址只打一枪，**而且这一枪挂在进程作用域上，不挂在调用方那一行上**。
        // 真机实测过的错法：挂在行上的话，图还在路上那一行就被回收，协程取消 → 站点明明回了
        // 26,268 字节并解出 180x240，赋值那一行却永远跑不到，界面上就一直是默认封面；
        // 而"看到有人在打就不等"的第二个调用方也永远不再回来问。
        val job = jobs.getOrPut(url) {
            scope.async {
                try {
                    val bytes = runCatching { fetch.fetch(PageRequest(url)).bytes }.getOrNull()
                    val bmp = bytes?.let { decode(it, targetPx) }
                    if (bmp == null) failed.add(url) else cache.put(url, bmp)
                    bmp
                } finally {
                    jobs.remove(url)
                }
            }
        }
        return job.await()
    }

    /** 先"只量不解"再按 2 的幂取样：原图那一份内存不该真的存在过（同一族理由见 `ComicImages`）。 */
    private fun decode(bytes: ByteArray, targetPx: Int): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        var s = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / (s * 2) >= targetPx) s *= 2
        val opts = BitmapFactory.Options().apply { inSampleSize = s }
        return runCatching { BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts) }.getOrNull()
    }
}
