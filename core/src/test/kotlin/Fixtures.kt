package com.bookreader.core

import com.bookreader.core.model.PageStyle
import java.io.BufferedWriter
import java.io.File
import java.io.OutputStreamWriter
import java.util.Random

/** 测试夹具：确定性生成一本中文长篇，字符数可控。 */
object Fixtures {

    private val WORDS = arrayOf(
        "少年", "林动", "山壁", "长剑", "夜色", "石洞", "雷光", "古老", "传承", "天赋",
        "修炼", "血脉", "古族", "令牌", "崖底", "雾气", "掌法", "符纸", "残卷", "宗门",
        "他抬起手", "风停了半刻", "远处传来钟声", "石阶上满是青苔", "那一剑极慢",
    )

    val STYLE = PageStyle(
        fontSizePx = 22f,
        lineHeightMultiplier = 1.65f,
        contentWidthPx = 984f,
        contentHeightPx = 2240f,
        paragraphIndentPx = 44f,
    )

    /** 1080x2400、左右 48、上下 80 的手机版面，44 字一行、61 行一页。 */
    val BIG_STYLE = STYLE.copy(fontSizePx = 26f, contentWidthPx = 936f, contentHeightPx = 2080f, paragraphIndentPx = 52f)

    fun tempRoot(name: String): File {
        val dir = File(System.getProperty("java.io.tmpdir"), "bookreader-test/$name-${System.currentTimeMillis()}")
        dir.mkdirs()
        return dir
    }

    fun sentence(rng: Random): String {
        val n = 6 + rng.nextInt(14)
        return buildString {
            repeat(n) { append(WORDS[rng.nextInt(WORDS.size)]) }
            append('。')
        }
    }

    fun paragraph(rng: Random): String = buildString {
        val n = 2 + rng.nextInt(5)
        repeat(n) { append(sentence(rng)) }
    }

    /**
     * 写一本 chapters 章、每章约 charsPerChapter 字的小说，返回写下的字符数。
     * 用 Writer 流式写，避免夹具自己在堆上造出一个千万字的 String。
     */
    fun writeNovel(
        target: File,
        chapters: Int,
        charsPerChapter: Int,
        charsetName: String = "UTF-8",
        seed: Long = 20261007L,
        indentBodyWith: String = "",
    ): Long {
        val rng = Random(seed)
        var written = 0L
        BufferedWriter(OutputStreamWriter(target.outputStream(), charsetName), 1 shl 16).use { w ->
            repeat(chapters) { i ->
                val title = "第${i + 1}章 ${WORDS[rng.nextInt(WORDS.size)]}之上"
                w.write(title)
                w.write("\r\n")
                written += title.length + 1
                var count = 0
                while (count < charsPerChapter) {
                    val p = paragraph(rng)
                    w.write(indentBodyWith)
                    w.write(p)
                    w.write("\n")
                    count += p.length + 1 + indentBodyWith.length
                    written += p.length + 1 + indentBodyWith.length
                }
                w.write("\n")
                written += 1
            }
        }
        return written
    }
}
