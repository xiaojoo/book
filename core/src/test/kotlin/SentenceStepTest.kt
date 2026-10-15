package com.bookreader.core

import com.bookreader.core.layout.sentenceIndexByChars
import com.bookreader.core.layout.sentenceStarts
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 听书页上"读过的滚出窗口、没读的露出来"靠的就是这一列句子起始下标 ——
 * 所以它必须在 `:core` 里判，不能等真机上"文字没动"才发现切错了。
 */
class SentenceStepTest {

    @Test
    fun splitsOnChineseSentenceEndsOnly() {
        assertEquals(listOf(0, 3, 6), sentenceStarts("甲乙。丙丁。戊己"))
        assertEquals("逗号不切：切到逗号，两行的窗口每念两个字就跳一次", listOf(0, 6), sentenceStarts("甲，乙，丙。丁，戊。"))
        assertEquals(listOf(0), sentenceStarts("只有一句没有标点"))
        assertEquals(listOf(0), sentenceStarts(""))
        // 末尾那个句号不产生新句子：后面没有字了
        assertEquals(listOf(0), sentenceStarts("整段就一句。"))
    }

    @Test
    fun closingQuoteStaysWithTheSentenceItCloses() {
        // 「好。」后面那颗收尾引号属于上一句，不然下一句从半个括号开头
        assertEquals(listOf(0, 7), sentenceStarts("他说：“好。”走了"))
    }

    /**
     * 时间往句上摊，摊的**分母是字数不是句数**。
     *
     * 这一条是听书页"音和字对不上"的根：按句数等分等于假定每句一样长，而真段落里
     * 段内最长句能是最短句的 14 倍（红楼梦第一回抽 32 段量的）。下面那几条用例的句长
     * 都直接取自真段落的形状，不是随手编的。
     */
    @Test
    fun timeIsSharedByCharacterCountNotBySentenceCount() {
        // 真段落里那种"三句、58/54/9 字"的形状：整段过半时还在那句 54 字的中句里
        val long = "甲".repeat(57) + "。" + "乙".repeat(53) + "。" + "丙".repeat(8) + "。"
        val st = sentenceStarts(long)
        assertEquals(3, st.size)
        assertEquals("整段走到 40% 还在第一句（它占 48%）", 0, sentenceIndexByChars(long, st, 0.40f))
        assertEquals("走到 50% 才进第二句", 1, sentenceIndexByChars(long, st, 0.50f))
        // 同一句长形状下，按句数等分会在 33% 就跳到第二句 —— 那正是他看到的"字跑在声音前面"
        assertEquals("旧算法（句数等分）在这里会早跳一句", 1, (st.size * 0.40f).toInt())
    }

    /** 短句连着来、后面跟一句长的：按句数等分会偏到两句以上。 */
    @Test
    fun aShortRunFollowedByALongSentenceMissesByTwoSentencesUnderEqualSplit() {
        val text = "一。二。三。四。" + "五".repeat(94) + "。"
        val st = sentenceStarts(text)
        assertEquals(5, st.size)
        assertEquals("半程时该已经在那句 95 字里", 4, sentenceIndexByChars(text, st, 0.5f))
        assertEquals("按句数等分只数到第 2 句 —— 差 2 句", 2, (st.size * 0.5f).toInt())
    }

    @Test
    fun theEndsOfTheRangeLandOnTheFirstAndLastSentence() {
        val text = "甲乙。丙丁。戊己。"
        val st = sentenceStarts(text)
        assertEquals(0, sentenceIndexByChars(text, st, 0f))
        assertEquals("整段放完停在最后一句，不越界", 2, sentenceIndexByChars(text, st, 1f))
        assertEquals("比例写过头也夹住", 2, sentenceIndexByChars(text, st, 3f))
        assertEquals("只有一句、或整段没标点：永远是第 0 句", 0, sentenceIndexByChars("整段一句到底", listOf(0), 0.7f))
    }

    /** 段首缩进（全角空格）不占格：占格的话每段开头那几格会把整段的位置往后顶。 */
    @Test
    fun indentationDoesNotTakePartOfTheTimeline() {
        val spaced = "　　甲乙丙丁。　　戊。"
        val plain = "甲乙丙丁。戊。"
        val a = sentenceStarts(spaced)
        val b = sentenceStarts(plain)
        for (f in listOf(0.1f, 0.3f, 0.5f, 0.7f, 0.9f))
            assertEquals("比例 $f 处两版该在同一句", sentenceIndexByChars(plain, b, f), sentenceIndexByChars(spaced, a, f))
    }

    /** 两个句末标点连着来（中间只剩一个换行）会切出一句"零字句"：它不该占一段时间、更不该卡住后面。 */
    @Test
    fun anEmptySentenceTakesNoTimeAndDoesNotStallTheIndex() {
        val text = "甲。\n\n乙乙乙乙乙乙乙乙乙乙。"
        val st = sentenceStarts(text)
        assertEquals("零字句确实在表里", true, st.size >= 2)
        assertEquals("开头那一下就该跳到有字的那句，不停在零字句上", st.lastIndex, sentenceIndexByChars(text, st, 0.9f))
        assertEquals(0, sentenceIndexByChars(text, st, 0f))
    }

    @Test
    fun theSentenceIndexNeverGoesBackwardsAsPlaybackAdvances() {
        val text = "甲。" + "乙".repeat(40) + "。丙。" + "丁".repeat(30) + "。"
        val st = sentenceStarts(text)
        var last = -1
        var k = 0
        while (k <= 100) {
            val i = sentenceIndexByChars(text, st, k / 100f)
            assertTrue("进度 $k% 时句序倒退回 $i（上一格是 $last）", i >= last)
            last = i
            k++
        }
    }

    /** 整段全是空白（脏数据）：不越界、不除零。 */
    @Test
    fun aParagraphWithNothingCountableStillReturnsAValidIndex() {
        val text = " \t \n "
        val st = sentenceStarts(text)
        val i = sentenceIndexByChars(text, st, 0.5f)
        assertTrue("回到的下标必须落在句表里", i in st.indices)
    }
}
