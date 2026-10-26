package com.bookreader.core.creation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 创作那一族的判据：落盘格式往返、按章合并、模型回包的解析。
 *
 * 都是纯字符串的活，所以**不碰文件也不碰网络**就能全判住 —— 真机上只验"存下去读得回来"那一层。
 */
class CreationTest {

    @Test
    fun bookAndCardsSurviveAWriteReadRoundTrip() {
        val chapters = listOf(
            Chapter(1, "初入学府", "你好，世界"),
            Chapter(2, "有\t制表符\n和换行的章名", ""),
        )
        val text = CreationFormat.writeBook("标题里\t也有\ttab", 1_700_000_000_000L, chapters.map { it.toRow() })
        val book = CreationFormat.readBook(text)
        assertEquals("标题里\t也有\ttab", book.title)
        assertEquals(1_700_000_000_000L, book.updatedAt)
        assertEquals(
            "章名里的制表符和换行都要原样回来，字数跟着存一份",
            listOf(ChapterRow(1, "初入学府", 5), ChapterRow(2, "有\t制表符\n和换行的章名", 0)),
            book.chapters,
        )

        val cards = listOf(
            Card(CardKind.Person, "张三", "剑客\n沉默寡言", 1),
            Card(CardKind.Skill, "御风", "跑得快|代价是耗命", 2),
            Card(CardKind.Timeline, "主角入城", "第三日开门", 3),
        )
        assertEquals(cards, CreationFormat.readCards(CreationFormat.writeCards(cards)))
    }

    /** 反斜杠是转义符本身：不来回转对，`C:\path` 这种说明会吃掉一个字符，第二次存就变了。 */
    @Test
    fun backslashesRoundTrip() {
        for (s in listOf("一个反斜杠 \\", "路径 C:\\temp", "\\t 是字面的反斜杠t", "\\\\t", "")) {
            assertEquals(s, CreationFormat.unesc(CreationFormat.esc(s)))
        }
    }

    /** 重新提取同一章：那一章的旧卡整份换掉，别的章的不动（他点的是"逐章累加"）。 */
    @Test
    fun reExtractingOneChapterReplacesOnlyThatChaptersCards() {
        val old = listOf(
            Card(CardKind.Person, "张三", "剑客", 1),
            Card(CardKind.Skill, "御风", "旧说明", 1),
            Card(CardKind.Person, "李四", "捕头", 2),
        )
        val incoming = listOf(Card(CardKind.Person, "王五", "新人物", 1), Card(CardKind.Skill, "御风", "新说明", 1))
        val merged = CreationFormat.merge(old, incoming)
        assertEquals(
            "第 1 章的旧卡整份换掉、第 2 章的留着",
            listOf("李四", "王五", "御风"), merged.map { it.name },
        )
        assertEquals("同名卡片换成了新说明", "新说明", merged.first { it.name == "御风" }.note)
    }

    /** 不同章抽出同一个人：说明换成后一次抽到的，章号也跟着走（界面上写"第 N 章更新"）。 */
    @Test
    fun sameNameFromALaterChapterUpdatesIt() {
        val merged = CreationFormat.merge(
            listOf(Card(CardKind.Person, "张三", "剑客", 1)),
            listOf(Card(CardKind.Person, "张三", "其实是反派", 4)),
        )
        assertEquals(1, merged.size)
        assertEquals(Card(CardKind.Person, "张三", "其实是反派", 4), merged.first())
    }

    @Test
    fun theReplyIsParsedLineByLineAndOnlyKnownKindsCount() {
        val reply = """
            时间线|主角入城|第三日开门，遇伏
            人物|张三|剑客，沉默寡言
            技能|御风|日行百里|代价是耗命
            势力|天地会|一个组织
            这行根本没有竖线
            人物||没有名字不算一条
            
            无
        """.trimIndent()
        val p = CreationAi.parse(reply, 7)
        assertEquals("认出来的只有三档之内的", 3, p.cards.size)
        assertEquals("说明里再出现的竖线整段留着", "日行百里|代价是耗命", p.cards[2].note)
        assertEquals("章号按提取的那一章记", listOf(7, 7, 7), p.cards.map { it.chapter })
        assertEquals("认不出的原行要交回去说人话（类别不认识、没竖线、有类别但没名字，都算）", 3, p.dropped.size)
        assertTrue("有类别没名字的那行也要说出来", p.dropped.any { it.startsWith("人物|") })
        assertTrue("空的和「无」不算认不出", p.dropped.none { it.isEmpty() || it == "无" })
    }

    /** markdown 的列表符号要能吞掉：模型常回 `- 人物|张三|…`，不吞就整条丢掉。 */
    @Test
    fun bulletMarkersInTheReplyAreTolerated() {
        val p = CreationAi.parse("- 人物|张三|剑客\n* 技能|御风|跑得快", 1)
        assertEquals(2, p.cards.size)
    }

    @Test
    fun anEmptyOrRefusedReplyIsNotAnError() {
        val p = CreationAi.parse("无", 2)
        assertEquals(emptyList<Card>(), p.cards)
        assertEquals(emptyList<String>(), p.dropped)
        assertTrue("什么都没抽到不该算失败", p.ok)
        assertFalse("有认不出的行就要能被界面知道", CreationAi.parse("一堆散文", 1).ok)
    }

    /** 超长的一章只发开头，而且界面上必须能问出来"是不是截断发的"。 */
    @Test
    fun aLongChapterIsCutAndSaysSo() {
        val short = "甲".repeat(CreationAi.MAX_CHARS)
        val long = "甲".repeat(CreationAi.MAX_CHARS + 10)
        assertFalse(CreationAi.truncated(short))
        assertTrue(CreationAi.truncated(long))
        val u = CreationAi.user(3, "初入学府", long)
        assertTrue("提示里要带章号章名", u.startsWith("【第 3 章·初入学府】"))
        assertTrue("发出去的正文不超过上限", u.length <= CreationAi.MAX_CHARS + 40)
    }

    /** 字数口径必须和听书那把尺子同一条：两处各数各的，同一章会报出两个数。 */
    @Test
    fun charCountFollowsTheSameRulerAsTheReader() {
        assertEquals(0, Chapter(1, "空", "").chars)
        assertEquals("汉字字母数字各一格", 4, Chapter(1, "字", "abcd").chars)
        assertEquals("空白不算", 4, Chapter(1, "空", "你好 世界").chars)
        assertEquals("标点只算半格：4 字 + 1 逗号 = 5", 5, Chapter(1, "标", "你好，世界").chars)
    }
}
