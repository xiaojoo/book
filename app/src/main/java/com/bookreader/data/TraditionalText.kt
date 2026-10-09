package com.bookreader.data

import android.os.Build
import android.util.Log
import com.bookreader.core.text.safeConvert

/**
 * 繁→简。直接用平台自带的 ICU transliterator，不往 APK 里塞转换表 ——
 * K40（API 33）上量过：`風雲變色，誰知我劍來？` 32 字进 32 字出、15 个字被换掉，
 * 而一段本来就简体的正文 `changed=0`（恒等），所以对简体书开这颗开关不会改一个字。
 *
 * 长度是硬约束（见 [safeConvert]）：ICU 偶尔会把一个字换成两个字，那种段整段退回原文。
 */
object TraditionalText {
    /** API 29 以下没有 `android.icu.text.Transliterator`，那颗开关在低版本上只能灰着。 */
    val available: Boolean get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q

    /** Transliterator 不是线程安全的，按线程各存一份；连"拿不到"也一起记住，别每行都重试。 */
    private class Cached(val transliterator: android.icu.text.Transliterator?)

    private val local = ThreadLocal<Cached>()

    fun toSimplified(source: String): String {
        if (source.isEmpty() || !available) return source
        val t = cached().transliterator ?: return source
        return safeConvert(source) { t.transliterate(source) }
    }

    private fun cached(): Cached {
        local.get()?.let { return it }
        val t = runCatching { android.icu.text.Transliterator.getInstance(ID) }
            .onFailure { Log.w(TAG, "这台机没有 $ID：${it.message}") }
            .getOrNull()
        return Cached(t).also { local.set(it) }
    }

    private const val ID = "Traditional-Simplified"
    private const val TAG = "T2s"
}
