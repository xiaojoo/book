package com.bookreader.core

import com.bookreader.core.layout.sentenceStarts
import org.junit.Assert.assertEquals
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
}
