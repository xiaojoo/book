package com.bookreader.core

import com.bookreader.core.ingest.ChapterPattern
import com.bookreader.core.ingest.TxtIngestor
import com.bookreader.core.storage.Book
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** 真实书里踩到的两种标题写法，和"排版单元必须有界"这条保证。 */
class ChapterRulesTest {

    private fun ingest(text: String, chunkChars: Int = 4096): Pair<File, Book> {
        val root = Fixtures.tempRoot("rules")
        val src = File(root, "in.txt")
        src.writeText(text, Charsets.UTF_8)
        val dir = File(root, "book")
        TxtIngestor(chunkChars).ingest(src, dir, "规则书")
        return dir to Book.open(dir, 4)
    }

    private val paragraph = "他把手里的剑放下，风从崖底一路吹上来，把雾吹散了又聚起来。这一段足够长，用来把章长顶到平均线以上。"

    @Test
    fun bareNumberTitlesCountAsChapters() {
        val text = buildString {
            repeat(12) { i ->
                append(String.format("%03d你到底是谁%n", i + 1))
                append(paragraph).append('\n').append(paragraph).append('\n').append(paragraph).append('\n')
            }
        }
        val (_, book) = ingest(text)
        println("[rules] 裸编号: 章=${book.chapters.size} 首章=${book.chapters[0].title}")
        assertEquals(12, book.chapters.size)
        assertEquals("001你到底是谁", book.chapters[0].title)
        book.close()
    }

    @Test
    fun sentenceLikeLinesAreNotTitles() {
        assertTrue(ChapterPattern.matches("第一章 小二上酒"))
        assertTrue(ChapterPattern.matches("001你到底是谁"))
        assertTrue(ChapterPattern.matches("1、初入江湖"))
        assertFalse("广告行带句号，不是标题", ChapterPattern.matches("第一章，十二点前上传第二章。"))
        assertFalse("清单行带句号，不是标题", ChapterPattern.matches("1.拥有5张日红票纵横用户并且需要进群截图投票历史记录。"))
        assertFalse("正文里的年份句不是标题", ChapterPattern.matches("1998年冬天，他离开了家"))
        assertFalse("正文整段不是标题", ChapterPattern.matches(paragraph))
    }

    @Test
    fun separatorLineDoesNotBecomeAChapter() {
        // 剑来的真实开头就是 "------------" 被当成了前言，打开书第一眼是一屏破折号
        val text = "------------\n\n第1章 惊蛰\n$paragraph\n$paragraph\n$paragraph\n\n第2章 雷光\n$paragraph\n$paragraph\n$paragraph\n"
        val (_, book) = ingest(text)
        println("[rules] 分隔行: 章=${book.chapters.size} 首章=${book.chapters[0].title} 起点=${book.chapters[0].startOffset}")
        assertEquals(2, book.chapters.size)
        assertEquals("第1章 惊蛰", book.chapters[0].title)
        assertEquals(0L, book.chapters[0].startOffset)
        assertTrue(book.chapters.chapters.all { it.charCount >= TxtIngestor.MIN_CHAPTER_CHARS })
        book.close()
    }

    @Test
    fun oversizedChaptersAreSplitAtParagraphBoundaries() {
        // 没有任何可识别标题的一本长书：整本会先并成"全书"，再被切成有界的段
        val long = buildString { repeat(6_000) { append(paragraph).append('\n') } }
        assertTrue("夹具不够长，测不出切分", long.length > TxtIngestor.MAX_CHAPTER_CHARS)
        val (_, book) = ingest(long, chunkChars = 8192)
        val lens = book.chapters.chapters.map { it.charCount }
        println("[rules] 无标题长书: 章=${lens.size} 最长=${lens.max()} 上限=${TxtIngestor.MAX_CHAPTER_CHARS}")
        assertTrue("每段都必须有界: 最长=${lens.max()}", lens.max() <= TxtIngestor.MAX_CHAPTER_CHARS + TxtIngestor.MAX_SPLIT_TAIL)
        assertEquals(0, book.chapters.chapters.count { it.charCount < 40 })
        assertTrue(book.chapters[0].title.startsWith("全书"))
        assertTrue(book.chapters[1].title.contains("续"))
        // 区间必须仍然首尾相接、覆盖全书
        assertEquals(0L, book.chapters[0].startOffset)
        assertEquals(book.totalChars, book.chapters.chapters.last().endOffset)
        for (i in 1 until book.chapters.size) assertEquals(book.chapters[i - 1].endOffset, book.chapters[i].startOffset)
        book.close()
    }
}
