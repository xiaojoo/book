package com.bookreader.data

import android.content.Context
import com.bookreader.core.creation.Card
import com.bookreader.core.creation.Chapter
import com.bookreader.core.creation.CreationFormat
import com.bookreader.core.creation.Novel
import java.io.File

/** 篇列表上的一行。不读正文，所以列表屏不该为报字数把几百万字搬进内存。 */
data class NovelMeta(
    val id: String,
    val title: String,
    val updatedAt: Long,
    val chapterCount: Int,
    val chars: Int,
    val cardCount: Int,
)

/**
 * 一部稿子 = `filesDir/creation/<id>/` 一个目录，里面三样：
 * `book.tsv`（标题 + 章目录 + 每章字数）、`chapters/<序号>.txt`（正文，原样，不转义）、
 * `cards.tsv`（抽出来的卡片）。
 *
 * 两件事是故意的：
 * - **id 由我们自己生成，绝不拿标题当文件名** —— 标题里可以有 `/`、`..`、任意字符，
 *   用它当文件名就是给自己埋一条路径穿越；书名存在 `book.tsv` 里。
 * - **不另存一份"篇索引"** —— 列目录就是列表。多一份索引就多一处会和目录本身不一致的地方
 *   （删一篇忘改索引，列表上就有一颗点不开的假行）。
 */
class CreationStore(context: Context) {

    private val root = File(context.filesDir, "creation").apply { mkdirs() }

    fun list(): List<NovelMeta> = root.listFiles { f: File -> f.isDirectory }
        ?.mapNotNull { dir ->
            val book = readBook(dir) ?: return@mapNotNull null
            NovelMeta(
                id = dir.name,
                title = book.title.ifBlank { "未命名" },
                updatedAt = book.updatedAt,
                chapterCount = book.chapters.size,
                chars = book.chapters.sumOf { it.chars },
                cardCount = CreationFormat.readCards(read(dir, CARDS)).size,
            )
        }
        // 最新动过的排最前：写作的人关心的就是"我刚才在写哪一篇"
        .orEmpty().sortedByDescending { it.updatedAt }

    /** 整篇读出来（含每一章的正文）。目录没了或 `book.tsv` 读不出标题时回 null。 */
    fun load(id: String): Novel? {
        val dir = dir(id)
        val book = readBook(dir) ?: return null
        return Novel(
            id = id,
            title = book.title,
            updatedAt = book.updatedAt,
            chapters = book.chapters.map { Chapter(it.index, it.title, read(dir, chapterFile(it.index))) },
            cards = CreationFormat.readCards(read(dir, CARDS)),
        )
    }

    /** @return 新建那一篇的 id；建不出来回 null */
    fun create(title: String): String? {
        val id = newId()
        val dir = File(root, id)
        if (!dir.mkdirs()) return null
        write(dir, BOOK, CreationFormat.writeBook(title.trim().ifBlank { "未命名" }, System.currentTimeMillis(), emptyList()))
        return id
    }

    /** 改名：只动 `book.tsv` 那一行，不碰正文（改一次名不该把几万字重写一遍）。 */
    fun rename(id: String, title: String): Boolean {
        val dir = dir(id)
        val book = readBook(dir) ?: return false
        write(dir, BOOK, CreationFormat.writeBook(title.trim().ifBlank { "未命名" }, book.updatedAt, book.chapters))
        return true
    }

    /** 存正文与章目录（新增章、改章名、删章都走这一颗）。删掉的章，它那个 `.txt` 一起带走。 */
    fun save(n: Novel) {
        val dir = dir(n.id)
        dir.mkdirs()
        val rows = n.chapters.map { it.toRow() }
        write(dir, BOOK, CreationFormat.writeBook(n.title.ifBlank { "未命名" }, System.currentTimeMillis(), rows))
        File(dir, "chapters").mkdirs()
        n.chapters.forEach { write(dir, chapterFile(it.index), it.text) }
        val keep = rows.map { it.index }.toSet()
        File(dir, "chapters").listFiles { f -> f.isFile }?.forEach { f ->
            val i = f.nameWithoutExtension.toIntOrNull()
            if (i != null && i !in keep) f.delete()
        }
    }

    /** 只换卡片那一格。刻意**不动 `updatedAt`**：那一个数是"什么时候写的"，抽卡片没写字。 */
    fun saveCards(id: String, cards: List<Card>) {
        val dir = dir(id)
        if (dir.isDirectory) write(dir, CARDS, CreationFormat.writeCards(cards))
    }

    /** 下一篇稿子里下一个章号：已有最大号 +1。**不回收删掉的号** —— 正文按号存文件，
     *  复用旧号会把那一章还留在磁盘上的字当成新章读回来。 */
    fun nextChapterIndex(n: Novel): Int = (n.chapters.maxOfOrNull { it.index } ?: 0) + 1

    fun delete(id: String): Boolean = dir(id).deleteRecursively()

    /** id 只可能由这颗生成，所以目录名一定是 `[a-z0-9]`，读回来时不需要再验一遍。 */
    private fun newId(): String {
        var base = System.currentTimeMillis().toString(36)
        while (File(root, base).exists()) base = base + ('a' + (base.length % 26))
        return base
    }

    private fun dir(id: String): File = File(root, id)

    private fun readBook(dir: File): CreationFormat.Book? {
        val f = File(dir, BOOK)
        if (!f.isFile) return null
        return CreationFormat.readBook(read(dir, BOOK))
    }

    private fun read(dir: File, name: String): String =
        runCatching { File(dir, name).readText() }.getOrDefault("")

    /** 先写 `.tmp` 再改名：写一半比不写更糟（一份截断的 `book.tsv` 会让整篇读不出来）。 */
    private fun write(dir: File, name: String, text: String) {
        val to = File(dir, name)
        val tmp = File(dir, "$name.tmp")
        if (runCatching { tmp.writeText(text) }.isFailure) {
            tmp.delete()
            return
        }
        if (!tmp.renameTo(to)) {
            to.delete()
            tmp.renameTo(to)
        }
        tmp.delete()
    }

    private companion object {
        const val BOOK = "book.tsv"
        const val CARDS = "cards.tsv"
        fun chapterFile(index: Int) = "chapters/$index.txt"
    }
}
