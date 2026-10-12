package com.bookreader.data

import java.io.File

/**
 * 一本书是从哪条源、哪个书页下来的，外加抓回来时顺手存的简介和大纲。
 *
 * 为什么要单独存：界面上「全本」和「更新」都要知道原来的书页地址，而 `:core` 的 `manifest.kt`
 * 只管正文本身 —— 往引擎里塞源站字段等于让引擎知道一个它用不上的概念。派生数据归壳管。
 *
 * 三个文件都在 `files/books/<id>/` 里，跟着「删除书籍」一起消失；`BookLibrary` 更新整本时
 * 清的是正文和索引，这三个要留着（那份简介是当时那页的，不该被下一次更新抹掉）。
 */
class BookProvenance(private val dir: File) {

    /** 没有 source.tsv 就是"这本不是从书源下来的"（本地导入的那一类），界面上不摆「全本」那颗。 */
    val known: Boolean get() = File(dir, SOURCE).isFile

    fun save(ruleId: String, bookUrl: String, intro: String, outline: List<String>) {
        if (ruleId.isEmpty() || bookUrl.isEmpty()) return
        dir.mkdirs()
        write(SOURCE, "rule=$ruleId\nurl=$bookUrl\n")
        write(INTRO, intro.trim())
        write(OUTLINE, outline.map { it.trim() }.filter { it.isNotEmpty() }.joinToString("\n"))
    }

    fun ruleId(): String = read(SOURCE).firstOrNull { it.startsWith("rule=") }?.substringAfter('=') ?: ""

    fun bookUrl(): String = read(SOURCE).firstOrNull { it.startsWith("url=") }?.substringAfter('=') ?: ""

    fun intro(): String = read(INTRO).joinToString("\n").trim()

    fun outline(): List<String> = read(OUTLINE).filter { it.isNotBlank() }

    /** `substringAfter('=')` 而不是 `split('=')`：书页地址本身就带等号，切一刀就把地址截断了。 */
    private fun read(name: String): List<String> =
        runCatching { File(dir, name).readLines() }.getOrDefault(emptyList()).map { it.trimEnd('\r') }

    /** 先写 .part 再改名（和 [DownloadHistory] 同一份写法）：中途被杀就留个半截临时文件，不会把好的那份弄坏。 */
    private fun write(name: String, text: String) {
        val f = File(dir, name)
        val part = File(dir, "$name.part")
        part.writeText(text)
        if (!part.renameTo(f)) {
            // Windows 上 renameTo 不覆盖已存在的文件（Android 覆盖），所以覆盖写要自己先删
            f.delete()
            if (!part.renameTo(f)) part.delete()
        }
    }

    companion object {
        private const val SOURCE = "source.tsv"
        private const val INTRO = "intro.txt"
        private const val OUTLINE = "outline.txt"
    }
}
