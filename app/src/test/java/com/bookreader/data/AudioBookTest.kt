package com.bookreader.data

import com.bookreader.booksource.FileAudioStore
import com.bookreader.booksource.audioPartName
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * 一本有声书在磁盘上的那两份表：`audio/chapters.tsv`（哪些集有音频）和每一集里的 `parts.tsv`
 * （那几段的地址，行序就是播放序）。
 *
 * 和 [ComicBookTest] 同一族：这两份表是"下载那一头"和"播放那一头"唯一的接头，
 * 写和读分在两个文件里，判据打在读这一头 —— 写进去什么形状，读回来就必须是什么形状。
 */
class AudioBookTest {

    @JvmField
    @Rule
    val tmp = TemporaryFolder()

    /** 造一本"集号 - 集名 - 那几段地址"的书；音频和真下载一样按地址散列落成文件。 */
    private fun book(vararg rows: Triple<Int, String, List<String>>): File {
        val dir = tmp.newFolder("book-" + System.nanoTime())
        val audio = File(dir, BookLibrary.AUDIO_FILE).apply { mkdirs() }
        File(audio, BookLibrary.CHAPTERS_TSV).writeText(
            rows.joinToString("\n") { (i, name, urls) -> "$i\td$i\t${urls.size}\t$name" } + "\n",
            Charsets.UTF_8,
        )
        rows.forEach { (i, _, urls) ->
            val box = File(audio, "d$i").apply { mkdirs() }
            File(box, "parts.tsv").writeText(urls.joinToString("\n") + "\n", Charsets.UTF_8)
            urls.forEach { u -> File(box, audioPartName(u)).writeBytes(ByteArray(4)) }
        }
        return dir
    }

    @Test
    fun episodesComeBackInCatalogOrderWithTheirPartCount() {
        val dir = book(
            Triple(0, "第一集 萧炎", listOf("https://c/a1.mp3", "https://c/a2.mp3")),
            Triple(1, "第二集 纳兰", listOf("https://c/b1.mp3")),
        )
        val b = AudioBook(dir)
        assertEquals(listOf("第一集 萧炎", "第二集 纳兰"), b.episodes().map { it.title })
        assertEquals(listOf(2, 1), b.episodes().map { it.parts })
        // 段序只能来自 parts.tsv 的行序：文件名是地址的散列，按它排序就是乱序
        assertEquals(
            listOf("https://c/a1.mp3", "https://c/a2.mp3").map { audioPartName(it) },
            b.parts(0).map { it.name },
        )
    }

    @Test
    fun anAbsentBookReadsAsNoEpisodes() {
        val b = AudioBook(File(tmp.root, "没有这一本"))
        assertEquals(emptyList<AudioBook.Episode>(), b.episodes())
        assertEquals(emptyList<File>(), b.parts(0))
    }

    /** 地址表写着三段、磁盘上只到了两段：读回来的只有真到了的那两段（少一段就是这一集断在半截）。 */
    @Test
    fun aPartWhoseBytesNeverArrivedIsNotAPart() {
        val dir = book(Triple(0, "第一集", listOf("https://c/1.mp3", "https://c/2.mp3")))
        File(dir, "${BookLibrary.AUDIO_FILE}/d0").let { File(it, audioPartName("https://c/2.mp3")).delete() }
        assertEquals("这一集该只读得出那一段真落地的音频", 1, AudioBook(dir).parts(0).size)
    }

    @Test
    fun missingEpisodesAreTheOnesInTheTocThatHaveNoAudio() {
        val dir = book(Triple(3, "第四集", listOf("https://c/9.mp3")))
        val b = AudioBook(dir)
        assertTrue(b.has(3))
        assertFalse("集号不是行号：第 1 行是第四集，别把第一集也算成有", b.has(0))
        assertEquals(listOf(0, 1, 2, 4), b.missing(5))
    }

    /** 集表是一张流水：抓一半停了、又补抓一遍，同一集会有两行 —— 取最后写的那一行。 */
    @Test
    fun theLastRowWrittenForAnEpisodeWins() {
        val dir = tmp.newFolder("append-" + System.nanoTime())
        val b = AudioBook(dir)
        b.record(0, "第一集", "d0", 1)
        b.record(1, "第二集", "d1", 1)
        // 第二次补抓：同一集的段数变了（站点把那集拆成了两段）
        b.record(0, "第一集", "d0", 2)
        val back = AudioBook(dir).episodes()
        assertEquals("两行同一集不该在目录里出现两次", 2, back.size)
        assertEquals("补抓之后该读到新的那个段数", 2, back.first { it.index == 0 }.parts)
    }

    /**
     * 同一集那份正文（"音频和正文在同一页"那一族才有，古文岛）读回来就是写进去的那一串。
     *
     * 读的一头在 `AudioBook.text`、写的一头在 `FileAudioStore.putText`，文件名只有一份定义 ——
     * 这一条钉的就是它俩认的确实是同一个文件。
     */
    @Test
    fun theTextStoredBesideAnEpisodeComesBackForThatEpisode() {
        val dir = book(Triple(0, "第一回", listOf("https://c/a.mp3")))
        File(dir, "${BookLibrary.AUDIO_FILE}/d0").let { File(it, FileAudioStore.TEXT).writeText("第一行\n\n第二行", Charsets.UTF_8) }
        assertEquals("第一行\n\n第二行", AudioBook(dir).text(0))
    }

    /** 播客那一族没有正文这一格：读回来是**空串**，界面上据此不开跟读，不是报错、也不是崩溃。 */
    @Test
    fun anEpisodeWithNoTextReadsAsEmptyNotMissing() {
        val dir = book(Triple(0, "第一集", listOf("https://c/a.mp3")))
        assertEquals("", AudioBook(dir).text(0))
        assertEquals("没有这一集也回空串，别抛", "", AudioBook(dir).text(9))
    }

    /** 段数为 0 的那一行不算"这一集有"：它只该在灰着的那几百行里。 */
    @Test
    fun aZeroPartRowIsNotPresent() {
        val dir = tmp.newFolder("empty-" + System.nanoTime())
        val audio = File(dir, BookLibrary.AUDIO_FILE).apply { mkdirs() }
        File(audio, BookLibrary.CHAPTERS_TSV).writeText("0\td0\t0\t第一集\n", Charsets.UTF_8)
        val b = AudioBook(dir)
        assertTrue(b.episodes().isEmpty())
        assertFalse(b.has(0))
    }

    /** 半截行（写到一半被杀）不该把整本目录判成坏：跳过它，别的行照样读得出。 */
    @Test
    fun aTruncatedRowIsSkippedInsteadOfPoisoningTheCatalogue() {
        val dir = tmp.newFolder("trunc-" + System.nanoTime())
        val audio = File(dir, BookLibrary.AUDIO_FILE).apply { mkdirs() }
        // 最后一行只写到了"集号 目录名"那两格就被杀：字段数不够，读的时候跳过
        File(audio, BookLibrary.CHAPTERS_TSV).writeText(
            "0\td0\t1\t第一集\n1\td1\t1\t第二集\n2\td2\t",
            Charsets.UTF_8,
        )
        assertEquals(2, AudioBook(dir).episodes().size)
    }
}
