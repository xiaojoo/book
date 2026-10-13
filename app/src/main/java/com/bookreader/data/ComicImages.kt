package com.bookreader.data

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import java.io.File

/**
 * 漫画页的位图缓存。
 *
 * 为什么不直接 `BitmapFactory.decodeFile` 交给 Compose：一张 980x1403 的页解码后是 5.5MB，
 * 一屏看得见 2 张、预取 3 张，加上往回翻的那几张，几十张就把堆顶满了（这台机的应用堆是 512MB，
 * 但翻页时回收不及时就是掉帧）。形状和 `PptxImages` / `PdfDoc` 同一套：按字节数限的 LruCache。
 *
 * 解码时就按目标宽度取样（`inSampleSize`），不是先解出原图再缩：原图那一份内存压根就不该存在过。
 */
class ComicImages(private val targetWidthPx: Int) {

    private val cache = object : LruCache<String, Bitmap>(MAX_BYTES) {
        override fun sizeOf(key: String, value: Bitmap) = value.byteCount
    }

    /** 取不到（文件不在、解不动）回 null：调用方画一格空白并写明这一页没下全，不是崩。 */
    fun page(file: File): Bitmap? {
        if (!file.isFile) return null
        val key = file.absolutePath + "@" + targetWidthPx
        cache.get(key)?.let { return it }
        // 尺寸问的是那次"只量不解"的解码：解出来的 Options.outWidth 才是站点那张图的真实宽，
        // 先解原图再缩等于把那份 5.5MB 的内存真的分配过一次
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.path, bounds)
        if (bounds.outWidth <= 0) return null
        val opts = BitmapFactory.Options().apply { inSampleSize = sample(bounds.outWidth) }
        val bmp = runCatching { BitmapFactory.decodeFile(file.path, opts) }.getOrNull() ?: return null
        cache.put(key, bmp)
        return bmp
    }

    /** 2 的整数档：1/2/4…，取"缩完仍不小于目标宽"的那一档（BitmapFactory 只会按 2 的幂降采样）。 */
    private fun sample(width: Int): Int {
        if (targetWidthPx <= 0 || width <= targetWidthPx) return 1
        var s = 1
        while (width / (s * 2) >= targetWidthPx) s *= 2
        return s
    }

    private companion object {
        /** 按可用堆的四分之一封顶，和 `PdfDoc.budget()` 同一口径（下限 16MB，上限 64MB）。 */
        val MAX_BYTES = (Runtime.getRuntime().maxMemory() / 4)
            .coerceAtLeast(16L * 1024 * 1024)
            .coerceAtMost(64L * 1024 * 1024)
            .toInt()
    }
}
