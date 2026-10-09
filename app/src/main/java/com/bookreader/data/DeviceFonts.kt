package com.bookreader.data

import android.graphics.Typeface
import com.bookreader.core.font.FontCmap
import java.io.File

/** 手机上能直接用的字体：/system/fonts 是公开可读的，逐个读它自己的 cmap 判它认不认识中文。 */
object DeviceFonts {

    data class Font(val label: String, val path: String?, val cjk: Boolean)

    val DEFAULT = Font("系统默认", null, true)

    fun list(cjkOnly: Boolean = true): List<Font> {
        val dirs = listOf("/system/fonts", "/system/fonts/GMSFonts")
        val out = ArrayList<Font>()
        for (dir in dirs) {
            val files = File(dir).listFiles { f ->
                val n = f.name.lowercase()
                n.endsWith(".ttf") || n.endsWith(".otf") || n.endsWith(".ttc")
            } ?: continue
            for (f in files.sortedBy { it.name.lowercase() }) {
                val cjk = FontCmap.coversAll(f)
                if (cjkOnly && !cjk) continue
                if (runCatching { Typeface.createFromFile(f) }.getOrNull() == null) continue
                out.add(Font(f.name.substringBeforeLast('.'), f.absolutePath, cjk))
            }
        }
        return listOf(DEFAULT) + out.distinctBy { it.path }
    }

    fun typefaceOf(path: String?): Typeface? = path?.let {
        runCatching { Typeface.createFromFile(File(it)) }.getOrNull()
    }
}
