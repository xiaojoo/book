package com.bookreader.ui

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 「本书信息」和阅读页抽屉里那一串目录行的行数从哪来。
 *
 * 这一条是踩过才知道要防的：一本 3066 章的小说点了第五章，那一枪补的是从头到第五章那一截，
 * 本地于是只有 5 章 —— 列表要是按本地那份摆，这一屏当场缩成 5 行，其余三千章看着像被删了。
 */
class CatalogRowsTest {

    private val outline = (1..3066).map { "第${it}章" }
    private val local = (1..5).map { i -> "第${i}章" to "${3000 + i}字" }

    @Test
    fun theListStaysTheWholeOutlineAfterDownloadingAPrefix() {
        val rows = chapterRows(outline, local)
        assertEquals("补了 5 章，列表还是整份 3066 行", 3066, rows.size)
        assertEquals("前 5 行有字数（本地索引给得出的那个数）", "3001字", rows[0].second)
        assertEquals("第 6 章没下，字数那一格空着不编一个数", "", rows[5].second)
    }

    @Test
    fun aRenamedChapterDoesNotBorrowAnotherOnesWordCount() {
        // 站点把那一章改名了：名字按站点摆，字数那一格空着 —— 把旧章的字数配到新名上就是一句假话。
        // 也不把盘上那份追加成第二行：行数按大纲算，追加会让同一章在列表里出现两遍
        val rows = chapterRows(listOf("第一章 新的名字"), listOf("第一章 旧的" to "10字"))
        assertEquals(listOf("第一章 新的名字" to ""), rows)
    }

    @Test
    fun skippedChaptersDoNotSlideEveryLaterWordCountByOneRow() {
        // 这就是夜无疆末尾那几章看着"重复"的形状：大纲有第 3 章，下载时它正文是空的、被跳过，
        // 于是引擎的第 2 章其实是大纲的第 3 章。按下标填会把第 2 章的字数挂到第 3 章那一行上。
        val outline = listOf("第1章", "第2章", "第3章", "第4章")
        val local = listOf("第1章" to "10字", "第2章" to "20字", "第4章" to "40字")
        val rows = chapterRows(outline, local)
        assertEquals(4, rows.size)
        assertEquals("第3章没抓到，字数那一格就该空着", "", rows[2].second)
        assertEquals("第4章的字数只能挂在第4章那一行", "40字", rows[3].second)
    }

    @Test
    fun moreChaptersOnDiskThanInTheOutlineDoNotAddRows() {
        // 《夜无疆》真机形状：站点那份目录 882 条（编号到第858章，中间夹着「休息一天」那些没编号的单章），
        // 入库那本却检出了 917 章 —— 正文里有几行被标题规则误判成新章。
        // 多出来的那些不许摆成行：那会让同一章在列表里出现两遍，顶上那个章数也跟着变假
        val outline = listOf("第1章 永夜", "第2章 火泉")
        val local = listOf("第1章 永夜", "第1章 永夜", "第2章 火泉", "第2章 火泉")
        val rows = chapterRows(outline, local.map { it to "10字" })
        assertEquals(2, rows.size)
        assertEquals(listOf("第1章 永夜", "第2章 火泉"), rows.map { it.first })
        assertEquals("每一行还是只挂一份字数", listOf("10字", "10字"), rows.map { it.second })
    }

    @Test
    fun aLocalImportWithNoPageFallsBackToItsOwnChapters() {
        val rows = chapterRows(emptyList(), listOf("第一章" to "100字", "第二章" to "200字"))
        assertEquals(listOf("第一章" to "100字", "第二章" to "200字"), rows)
        assertEquals("两头都空就是一句空列表，不炸", emptyList<Pair<String, String>>(), chapterRows(emptyList(), emptyList()))
    }
}

/** 引擎章表对回站点大纲的那把尺子：目录两处（阅读页抽屉、本书信息）共用它。 */
class AlignOutlineTest {

    @Test
    fun aSubsequenceLinesUpEvenWithRepeatedNames() {
        // 作者真写过两遍"休息一天"：只往前走的那颗指针才分得开它们，按"名字出现过"配对会串
        val outline = listOf("第1章", "休息一天", "第2章", "休息一天", "第3章")
        val local = listOf("第1章", "休息一天", "第2章", "休息一天", "第3章")
        assertEquals(listOf(0, 1, 2, 3, 4), alignOutline(outline, local).toList())
    }

    @Test
    fun skippedAndRenamedChaptersGetTheirOwnAnswer() {
        val outline = listOf("一", "二", "三", "四")
        assertEquals("抓丢了第三、第四章", listOf(0, 1, -1, -1), alignOutline(outline, listOf("一", "二", "x", "y")).toList())
        assertEquals("大纲里根本没有的那一章不占位置", -1, alignOutline(emptyList(), listOf("一"))[0])
    }

    @Test
    fun whitespaceInAChapterNameDoesNotMakeItAThirdChapter() {
        // 站点 <a> 里那串带空格/全角空格，写进正文的那一行是 trim 过的：
        // 按字面相等配会把同一章当成"对不上号"追加到末尾，列表里就出现两行、章数还虚高
        val outline = listOf(" 第1章 永夜", "第2章　火泉", "第3章 野外世界 ")
        val local = listOf("第1章 永夜", "第2章 火泉", "第3章 野外世界")
        assertEquals(listOf(0, 1, 2), alignOutline(outline, local).toList())
        val rows = outlineRows(outline, local)
        assertEquals("三章就是三行，不许多出追加的那几行", 3, rows.size)
        assertEquals(listOf(0, 1, 2), rows.map { it.second })
    }

    @Test
    fun rowsAreTheOutlineAndLineUpByPosition() {
        val rows = outlineRows(listOf("一", "二", "三"), listOf("一", "三"))
        assertEquals(3, rows.size)
        assertEquals("二是没下的那一行", "二", rows[1].first)
        assertEquals(null, rows[1].second)
        assertEquals("一对上了引擎第 0 章", 0, rows[0].second)
        assertEquals("三对的是引擎第 1 章（不是下标 2）", 1, rows[2].second)
    }

    @Test
    fun nothingOnDiskStillShowsTheWholeOutline() {
        val rows = outlineRows(listOf("一", "二"), emptyList())
        assertEquals(listOf("一" to null, "二" to null), rows)
    }
}
