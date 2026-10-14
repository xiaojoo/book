package com.bookreader.data

import com.bookreader.booksource.audioPartName
import com.bookreader.booksource.audioPartOrder
import java.io.File

/**
 * 读一本有声书：目录里有哪些集、某一集的第 N 段是哪个文件。
 *
 * 写的那一头在 [AudioImport]（抓完落盘 + 写两份表），这一头只认那两份表的格式，
 * 所以格式改了不会有一处读、一处写各说各话。
 *
 * 集号是**源站目录里的顺序**，不是"下载完成的顺序"：并发抓几十集时完成顺序是乱的，
 * 而人要听的是"第 12 集"。
 */
class AudioBook(bookDir: File) {

    /** 一集：集号、集名、仓里那个目录名、以及当时数下的段数。 */
    data class Episode(val index: Int, val title: String, val dir: String, val parts: Int) {
        /** 这一集在磁盘上有音频（`parts.tsv` 在、至少一段在）。 */
        val present: Boolean get() = parts > 0
    }

    private val audio = File(bookDir, BookLibrary.AUDIO_FILE)

    /** 几条抓取线程同时在写这一张流水（下载器那一头是并发线程池）。 */
    private val lock = Any()

    /** `chapters.tsv` 只读一次：目录抽屉一屏就要几十行，每行开一次文件没必要。 */
    private val rows: List<Episode> by lazy { read() }

    /**
     * 抓完一集就记一行（append，不等整本结束）。
     *
     * 为什么要 append 而不是最后整张重写：一本有声书几 GB、抓一半被系统收回、或者你按了暂停是常态。
     * 只在结尾写一次的话，磁盘上那几十集的音频就没有人记得是哪一集、叫什么名
     * （漫画那边真机现场过一次「图几百张在盘上、chapters.tsv 0 行」，同一族同一个坑）。
     * 写失败（磁盘满、目录没建起来）不当崩：那一集下次补抓，集表少一行只是少一行。
     */
    fun record(index: Int, title: String, dir: String, parts: Int) = synchronized(lock) {
        runCatching {
            audio.mkdirs()
            File(audio, BookLibrary.CHAPTERS_TSV).appendText(
                "$index\t$dir\t$parts\t${title.replace('\t', ' ').replace('\n', ' ')}\n", Charsets.UTF_8,
            )
        }
        Unit
    }

    /**
     * `chapters.tsv` 是一张流水，不是一张快照：同一集补抓过两遍就有两行。
     * 按集号取**最后写进去的那一行**（那张才是现在磁盘上的段数），顺序按集号排。
     */
    private fun read(): List<Episode> {
        val byIndex = LinkedHashMap<Int, Episode>()
        runCatching { File(audio, BookLibrary.CHAPTERS_TSV).readLines(Charsets.UTF_8) }
            .getOrDefault(emptyList())
            .forEach { l ->
                val c = l.split('\t')
                // 半截行（写到一半被杀）解析不出来就跳过：它不该把整本目录判成坏
                if (c.size < 4) return@forEach
                val i = c[0].toIntOrNull() ?: return@forEach
                val n = c[2].toIntOrNull() ?: return@forEach
                byIndex[i] = Episode(i, c.drop(3).joinToString("\t").trim(), c[1], n)
            }
        return byIndex.values.sortedBy { it.index }
    }

    /** 已下载的集，按集号顺序。灰掉的那几百集不在这里（它们在整本目录里，见 [missing]）。 */
    fun episodes(): List<Episode> = rows.filter { it.present }

    /** 这一集那几段音频，按播放序。`parts.tsv` 是段序的唯一一份依据 —— 文件名是散列，排不出来。 */
    fun parts(index: Int): List<File> {
        val ep = rows.firstOrNull { it.index == index } ?: return emptyList()
        val box = File(audio, ep.dir)
        return audioPartOrder(box).map { File(box, audioPartName(it)) }.filter { it.isFile }
    }

    /** 目录里第几集有没有在本地。 */
    fun has(index: Int): Boolean = rows.any { it.index == index && it.present }

    /** 整本目录里那些还没下下来的集的集号（`tocChapters` 减去有的）。目录抽屉要把它们灰着摆出来。 */
    fun missing(tocCount: Int): List<Int> {
        val have = rows.filter { it.present }.map { it.index }.toSet()
        return (0 until tocCount).filter { it !in have }
    }
}
