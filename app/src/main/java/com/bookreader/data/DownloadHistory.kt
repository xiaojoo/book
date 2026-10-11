package com.bookreader.data

import java.io.File

/** 一条下载记录。state 只有这四个值，界面上那颗按钮的字全看它。 */
data class DownloadRecord(
    val bookUrl: String,
    val ruleId: String,
    val bookName: String,
    val author: String,
    val sourceName: String,
    var state: String = STATE_RUNNING,
    var total: Int = 0,
    var done: Int = 0,
    var chars: Int = 0,
    var missing: Int = 0,
    var bookId: String = "",
    var error: String = "",
    var startedAt: Long = 0L,
    var endedAt: Long = 0L,
) {
    val running get() = state == STATE_RUNNING

    /** 暂停和取消的区别只在下一颗按钮上写什么：磁盘上已抓到的章一样都不丢。 */
    val resumable get() = state == STATE_PAUSED || state == STATE_CANCELLED || state == STATE_FAILED

    companion object {
        const val STATE_RUNNING = "下载中"
        const val STATE_DONE = "已入库"
        const val STATE_FAILED = "失败"
        const val STATE_CANCELLED = "已取消"
        const val STATE_PAUSED = "已暂停"
    }
}

/**
 * 下载历史 = `filesDir/downloads/history.tsv`，一行一条，制表符分列。
 *
 * 为什么不是 prefs 里一个 JSON 串：那张表是用户唯一能回看"我下过什么、哪本缺章"的地方，
 * 一个串坏掉（写到一半被杀）就整张表读不出来；一行一条坏一行只丢一条，`adb pull` 出来也还能看。
 *
 * 唯一可能带制表符/换行的字段是 `error`（那是服务端原话），所以**读**的时候按前 13 列切、
 * 剩下的整段还回 error —— 否则一句带换行的报错会把一条记录劈成两行。
 */
class DownloadHistory(private val file: File) {

    fun load(): List<DownloadRecord> {
        if (!file.isFile) return emptyList()
        return runCatching {
            file.readLines(Charsets.UTF_8).mapNotNull { parse(it) }
        }.getOrDefault(emptyList())
    }

    fun find(bookUrl: String): DownloadRecord? = load().firstOrNull { it.bookUrl == bookUrl }

    /** 整表重写：这表最多几十行，逐行 append 反而要在读的时候处理半截行。 */
    fun put(r: DownloadRecord) = synchronized(lock) {
        val all = LinkedHashMap<String, DownloadRecord>()
        load().forEach { all[it.bookUrl] = it }
        all[r.bookUrl] = r
        write(all.values.toList())
    }

    fun remove(bookUrl: String) = synchronized(lock) {
        write(load().filterNot { it.bookUrl == bookUrl })
    }

    private fun write(all: List<DownloadRecord>) = synchronized(lock) {
        runCatching {
            file.parentFile?.mkdirs()
            // 半截文件比没有文件更糟：读的时候那一行解析失败就悄悄少一条记录
            val tmp = File(file.parentFile, file.name + ".part")
            tmp.bufferedWriter(Charsets.UTF_8).use { w ->
                if (all.isNotEmpty()) w.write(HEADER + "\n")
                all.forEach { w.write(line(it) + "\n") }
            }
            if (!tmp.renameTo(file)) {
                file.delete()
                tmp.renameTo(file)
            }
        }
    }

    private fun line(r: DownloadRecord) = listOf(
        r.bookUrl, r.ruleId, r.bookName, r.author, r.sourceName, r.state,
        r.total.toString(), r.done.toString(), r.chars.toString(), r.missing.toString(),
        r.bookId, r.startedAt.toString(), r.endedAt.toString(), flat(r.error),
    ).joinToString("\t")

    private fun parse(l: String): DownloadRecord? {
        if (l.isBlank() || l == HEADER) return null
        val c = l.split('\t')
        if (c.size < 14) return null
        return DownloadRecord(
            bookUrl = c[0], ruleId = c[1], bookName = c[2], author = c[3], sourceName = c[4],
            state = c[5], total = c[6].int(), done = c[7].int(), chars = c[8].int(), missing = c[9].int(),
            bookId = c[10], startedAt = c[11].long(), endedAt = c[12].long(),
            // 第 14 列之后的全部：报错原文里可能就有制表符，切它会丢内容
            error = c.drop(13).joinToString("\t"),
        )
    }

    private fun flat(s: String) = s.replace('\t', ' ').replace('\n', ' ').trim()

    private fun String.int() = toIntOrNull() ?: 0

    private fun String.long() = toLongOrNull() ?: 0L

    private val lock = Any()

    private companion object {
        const val HEADER = "#booksource-download-history-v1"
    }
}
