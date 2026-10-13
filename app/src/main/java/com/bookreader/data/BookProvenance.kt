package com.bookreader.data

import java.io.File

/**
 * 一本书是从哪条源、哪个书页下来的，外加抓回来时顺手存的简介和大纲。
 *
 * 为什么要单独存：界面上「全本」和「更新」都要知道原来的书页地址，而 `:core` 的 `manifest.kt`
 * 只管正文本身 —— 往引擎里塞源站字段等于让引擎知道一个它用不上的概念。派生数据归壳管。
 *
 * 三个文件都在 `files/books/<id>/` 里，跟着「删除书籍」一起消失；`BookLibrary` 整本重灌（`into` 那一档）
 * 清的是正文和索引，这三份跟着原件一起留（BookLibrary.KEEP_ON_UPDATE）—— 那份简介是当时那一页的，
 * 不该被下一次更新抹掉，而 source.tsv 一没，「补齐全本」就再也找不回书页地址了。
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
        // 这三个文件名归这里管：BookLibrary 更新整本时要原样留下它们，名字抄第二份迟早和这边不一致
        const val SOURCE = "source.tsv"
        const val INTRO = "intro.txt"
        const val OUTLINE = "outline.txt"

        /** 一本"从书源下来的书"在磁盘上的身份证。书页地址本身就带等号，所以整串当键、不按 = 切。 */
        fun key(ruleId: String, bookUrl: String) = "$ruleId|$bookUrl"

        /**
         * 书架上从书源下来的那些本：`key(源 id, 书页地址)` → 书 id。
         *
         * 靠它才分得清"第二次点下载"是要另一本，还是同一本又出了新章 —— 下载历史那张表是
         * 可以删的（任务页那颗删除），书架上那份 `source.tsv` 才是"这本确实在架上"的证据。
         * 同一个键撞上几本（改了规则之前重复下过的那种）留**目录名最大**的那本：id 打头是时间戳，
         * 也就是最近一次入库的那本，界面上下一次读写的都该是它。
         */
        fun downloadedBooks(root: File): Map<String, String> {
            val dirs = root.listFiles()?.filter { it.isDirectory }?.sortedByDescending { it.name }
                ?: return emptyMap()
            val out = HashMap<String, String>()
            for (d in dirs) {
                if (!File(d, SOURCE).isFile) continue
                val rule = lineValue(File(d, SOURCE), "rule=")
                val url = lineValue(File(d, SOURCE), "url=")
                if (rule.isEmpty() || url.isEmpty()) continue
                // 排过序 + 只认第一个见到的键 = 最新的目录名赢（同名不覆盖）
                out.putIfAbsent(key(rule, url), d.name)
            }
            return out
        }

        private fun lineValue(f: File, prefix: String): String =
            runCatching { f.readLines().firstOrNull { it.trimStart().startsWith(prefix) }?.substringAfter('=')?.trim() }
                .getOrDefault("")
                .orEmpty()
    }
}
