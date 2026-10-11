package com.bookreader.data

import android.graphics.Bitmap
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import android.util.Log
import android.util.LruCache
import androidx.core.graphics.createBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File

/** 一页的宽高（像素）。 */
data class PdfPageSize(val widthPx: Int, val heightPx: Int)

/**
 * 一本 pdf 的整页渲染器。
 *
 * PdfRenderer 有两个硬约束，这份封装就是为了它们：
 * 1. 同一时刻只能开一页，而且不是线程安全的 —— 所以整本书共用一把 [Mutex]，
 *    谁要位图谁排队，渲染完立刻关掉那一页；
 * 2. 它只认位图尺寸：想要清晰就把位图开大，没有字号、行距、边距这些旋钮
 *    （版面是原件的，这正是"保留原格式"的代价，主流阅读器一样）。
 *
 * 页宽一律缩放到 [targetWidthPx]（= 屏宽），所以每页的显示高度就是它的版面比例，
 * 不用等位图渲出来就能先占好格子 —— 列表不会在渲染时跳一下。
 */
class PdfDoc private constructor(
    private val pfd: ParcelFileDescriptor,
    private val renderer: PdfRenderer,
    private var targetWidthPx: Int,
    val pageSizes: Array<PdfPageSize>,
) : AutoCloseable {

    val pageCount: Int get() = pageSizes.size

    private val lock = Mutex()

    /**
     * 缩放改了：换一个渲染宽度重开那批位图。已经缓存的那几张不是这个分辨率，整批丢掉 ——
     * [pageSizes] 是比例，不跟着变，所以列表里那些格子不会跳。
     */
    fun setRenderWidth(px: Int) {
        if (px <= 0 || px == targetWidthPx) return
        targetWidthPx = px
        cache.evictAll()
    }

    /**
     * 淘汰出来的位图交给 GC（API 26 起像素在 native 堆上，跟着 Java 对象一起回收），
     * 这里不主动 recycle —— Compose 可能正拿着同一块像素在画，回收会直接崩画布。
     */
    private val cache = object : LruCache<Int, Bitmap>(budget()) {
        override fun sizeOf(key: Int, value: Bitmap): Int = value.byteCount
    }

    /**
     * 缩略图那一档的小位图，和正文那批**各一格缓存**：两者分辨率不同，混在同一格里，
     * 打开缩略图页会把正在看的那一页挤成糊的（缓存按页号命中，不看分辨率）。
     */
    private val thumbs = object : LruCache<Int, Bitmap>(budget() / 4) {
        override fun sizeOf(key: Int, value: Bitmap): Int = value.byteCount
    }

    /**
     * 渲一张缩略图。宽度由界面按格子实际像素给，这里只钉上下限：
     * 再小就是糊的，再大不是更清楚、只是把缓存吃满（一格 480px 宽在这台机上是 0.9MB）。
     */
    suspend fun thumbnail(index: Int, widthPx: Int): Bitmap? = withContext(Dispatchers.IO) {
        lock.withLock {
            if (index < 0 || index >= pageCount) return@withLock null
            thumbs.get(index)?.let { return@withLock it }
            val w = widthPx.coerceIn(96, 480)
            val size = pageSizes[index]
            val h = (size.heightPx.toLong() * w / size.widthPx).coerceAtLeast(1L).toInt()
            val bmp = createBitmap(w, h, Bitmap.Config.ARGB_8888)
            val outcome = runCatching {
                renderer.openPage(index).use { it.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY) }
            }
            if (outcome.isFailure) {
                bmp.recycle()
                Log.w(TAG, "pdf 第 $index 页缩略图渲染失败: ${outcome.exceptionOrNull()?.message}")
                return@withLock null
            }
            thumbs.put(index, bmp)
            bmp
        }
    }

    /** 渲染一页。越界或原件坏了返回 null，调用方留着占位，不崩。 */
    suspend fun page(index: Int): Bitmap? = withContext(Dispatchers.IO) {
        lock.withLock {
            if (index < 0 || index >= pageCount) return@withLock null
            cache.get(index)?.let { return@withLock it }
            val size = pageSizes[index]
            val h = (size.heightPx.toLong() * targetWidthPx / size.widthPx).coerceAtLeast(1L).toInt()
            val bmp = createBitmap(targetWidthPx, h, Bitmap.Config.ARGB_8888)
            val outcome = runCatching {
                renderer.openPage(index).use { it.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY) }
            }
            if (outcome.isFailure) {
                bmp.recycle()
                Log.w(TAG, "pdf 第 $index 页渲染失败: ${outcome.exceptionOrNull()?.message}")
                return@withLock null
            }
            cache.put(index, bmp)
            bmp
        }
    }

    override fun close() {
        cache.evictAll()
        thumbs.evictAll()
        runCatching { renderer.close() }
        runCatching { pfd.close() }
    }

    companion object {
        private const val TAG = "ReaderPerf"

        /** 位图预算：堆上限的 1/4。再多不是变快，是在长列表里把堆顶爆。 */
        private fun budget(): Int {
            val max = Runtime.getRuntime().maxMemory()
            return (max / 4L).coerceAtMost((Int.MAX_VALUE - 1).toLong()).toInt()
        }

        /**
         * 打开并把每页的版面比例先数一遍。这一步要遍历全部页（开一页只读宽高），
         * 放在 [Dispatchers.IO] 上跑：一千页的原件也就几百毫秒，但绝不能占主线程。
         */
        fun open(file: File, targetWidthPx: Int): PdfDoc {
            val t0 = System.nanoTime()
            val pfd = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
            val renderer = try {
                PdfRenderer(pfd)
            } catch (e: Exception) {
                pfd.close()
                throw e
            }
            val n = renderer.pageCount
            require(n > 0) { "这个 pdf 一页都没有" }
            val sizes = Array(n) { i ->
                renderer.openPage(i).use { PdfPageSize(it.width.coerceAtLeast(1), it.height.coerceAtLeast(1)) }
            }
            Log.d(TAG, "pdf open pages=$n w=$targetWidthPx sizes=${sizes.take(3).joinToString { "${it.widthPx}x${it.heightPx}" }} ms=${(System.nanoTime() - t0) / 1_000_000}")
            return PdfDoc(pfd, renderer, targetWidthPx, sizes)
        }
    }
}
