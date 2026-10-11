package com.bookreader.booksource

import java.io.File
import java.security.MessageDigest

/**
 * 落盘的章节仓 —— 断点续抓要能跨进程活着（手机把应用杀了、下载中途退出），所以一章一个文件。
 *
 * 文件名取键的 SHA-1：键里带 URL 和站点路径，直接当文件名会撞上非法字符、超长、
 * 以及"两本书的 /1.html 是同一个文件"这种串档。键本身含源 id 和书页地址，见 [BookDownloader.storeKey]。
 */
class FileChapterStore(private val root: File) : ChapterStore {

    init {
        if (!root.isDirectory) root.mkdirs()
    }

    override fun get(key: String): String? {
        val f = file(key)
        if (!f.isFile) return null
        // 读不动（半截文件、被别的进程占着）当"没存过"处理：宁可重下一遍，也不能把一本书记成缺章
        return runCatching { f.readText() }.getOrNull()?.takeIf { it.isNotEmpty() }
    }

    /** 先写 .part 再改名：中途被杀只会留下一个没人认的临时件，不会留下"看着在、其实半截"的一章。 */
    override fun put(key: String, value: String) {
        val f = file(key)
        val tmp = File(f.parentFile, f.name + ".part")
        tmp.writeText(value, Charsets.UTF_8)
        if (!tmp.renameTo(f)) {
            f.delete()
            tmp.renameTo(f)
        }
    }

    private fun file(key: String) = File(root, sha1(key) + ".txt")

    /** 仓里已有多少章（给"接着上次"那句回话用）。 */
    fun stored(): Int = root.listFiles { f -> f.isFile && f.name.endsWith(".txt") }?.size ?: 0

    private companion object {
        fun sha1(s: String): String =
            MessageDigest.getInstance("SHA-1").digest(s.toByteArray(Charsets.UTF_8))
                .joinToString("") { "%02x".format(it) }
    }
}

/**
 * 整本落成一个 txt 文件。扩展名必须是 `.txt`：入库是按扩展名分派的
 * （`BookLibrary.importFile`），写成没有后缀的文件会被当成未知格式。
 */
fun writeBookText(out: File, outcome: DownloadOutcome): File {
    out.parentFile?.mkdirs()
    out.writeText(outcome.body, Charsets.UTF_8)
    return out
}
