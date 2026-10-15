package com.bookreader.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 跟读那张时刻表的判据：**按字数把整集时长摊给每一句**。
 *
 * 站点不给时间轴（古文岛那一族只给整回一支 mp3 加一页纯正文），所以这一张是**估**出来的。
 * 判据因此不打在"准不准"上（准不准要拿真时间轴比，那一条得离线强制对齐，他否掉了），
 * 打在估法的**口径**上：字数怎么数、句在哪里断、时长没读出来时绝不假装亮着一句。
 * 这三样错一样，界面上的表现都是"高亮和声音对不上"，而且对不上的是整本。
 */
class FollowAlignTest {

    /** 正文是"段与段之间一个换行"那个形状（`chapterText` 交出来的就是它）。 */
    private fun body(vararg paragraphs: String): String = paragraphs.joinToString("\n")

    /** 这一段第一句从哪一毫秒起（表上摆的是句子，段只是它们的分组）。 */
    private fun startOf(m: FollowMap, paragraph: Int): Long = m.paragraphs[paragraph].first().startMs

    @Test
    fun timeIsSplitByCharacterCountNotByLineCount() {
        // 前一句 4 格（三个汉字 + 句号）、后一句 1 格，整集 1000ms → 8:2
        val m = FollowAlign.align(body("一二三。", "五"), 1_000L)
        assertEquals(2, m.sentences)
        assertEquals("第一句从 0 起", 0L, startOf(m, 0))
        assertEquals("四格对一格：第二句该从 800ms 起：" + m.paragraphs[1][0].startMs, 800L, startOf(m, 1))
        assertEquals("末句的终点就是整集时长（摊完不能剩尾巴）", 1_000L, m.paragraphs[1][0].endMs)
    }

    /** 句中的空格不占格：念的人不念空格，数的人也不能替它占一段时间。 */
    @Test
    fun spacesAreNotCountedAsCharacters() {
        val spaced = FollowAlign.align(body("一 二 三 四。", "五。"), 1_000L)
        val plain = FollowAlign.align(body("一二三四。", "五。"), 1_000L)
        assertEquals(
            "带空格的和不带空格必须摊出同一张表（读到 " + spaced.paragraphs[1][0].startMs + "）",
            plain.paragraphs[1][0].startMs,
            spaced.paragraphs[1][0].startMs,
        )
    }

    /** 收尾的引号跟着它前面那句走：切在 `……。”` 的引号中间，高亮就断在半句上。 */
    @Test
    fun aClosingQuoteBelongsToTheSentenceItCloses() {
        val m = FollowAlign.align("他说：“明天见。”天亮了。", 400L)
        val lines = m.paragraphs[0]
        assertEquals("那一句该带着收尾引号：" + lines[0].text, "他说：“明天见。”", lines[0].text)
        assertEquals("剩下的是第二句", "天亮了。", lines[1].text)
    }

    /** 时长还没读出来（播放器还没 prepare 完）时**一句都不亮** —— 亮在哪一句上是编出来的。 */
    @Test
    fun noDurationMeansNoHighlightedSentence() {
        val m = FollowAlign.align(body("一二三。", "四五六。"), 0L)
        assertEquals("正文还是要切出来的", 2, m.sentences)
        assertEquals("但没有一句该亮", -1, m.sentenceAt(123L))
    }

    @Test
    fun theHighlightedSentenceMovesAsThePlaybackAdvances() {
        // 三句各 4 格、整集 3000ms → 每句正好 1000ms
        val m = FollowAlign.align(body("一二三。", "四五六。", "七八九。"), 3_000L)
        assertEquals(0, m.sentenceAt(0L))
        assertEquals("999ms 还在第一句里", 0, m.sentenceAt(999L))
        assertEquals("第二句从 1000ms 起", 1, m.sentenceAt(1_000L))
        assertEquals("过界的每一毫秒都算最后那句（末尾不留一格空的）", 2, m.sentenceAt(3_000L))
        assertEquals("比整集还长也停在最后一句", 2, m.sentenceAt(9_999L))
    }

    /** 时刻必须单调不减：二分和自动跟随都靠这一条，乱了表现是"高亮来回跳"。 */
    @Test
    fun startTimesNeverGoBackwards() {
        val m = FollowAlign.align(body("第一句很长很长十个字呢。", "短些。", "第三句也不短六个字呀。", "末"), 3_000L)
        val flat = m.paragraphs.flatten()
        for (i in 1 until flat.size) {
            assertTrue("第 $i 句起点早于上一句：" + flat.map { it.startMs }, flat[i].startMs >= flat[i - 1].startMs)
            assertTrue("第 $i 句终点早于起点", flat[i].endMs >= flat[i].startMs)
        }
    }

    /** 一整段没有句末标点（一首诗、一条引文）：整段算一句，**这一段不能消失**。 */
    @Test
    fun aParagraphWithoutAnyEndPunctuationSurvivesAsOneLine() {
        val m = FollowAlign.align(body("窗前明月光", "疑是地上霜。"), 500L)
        assertEquals(2, m.paragraphs.size)
        assertEquals("窗前明月光", m.paragraphs[0][0].text)
        assertEquals("第一段从 0 起", 0L, startOf(m, 0))
        assertEquals(1, m.paragraphs[1].size)
    }

    /** 段号 ↔ 句号的换算：自动跟随滚的是段，上色的是句，两头必须指同一处。 */
    @Test
    fun paragraphAndSentenceIndicesPointAtTheSamePlace() {
        val m = FollowAlign.align(body("甲一。甲二。甲三。", "乙一。乙二。"), 1_000L)
        assertEquals(5, m.sentences)
        assertEquals("前三句在段 0", listOf(0, 0, 0), (0..2).map { m.paragraphOf(it) })
        assertEquals("后两句在段 1", listOf(1, 1), (3..4).map { m.paragraphOf(it) })
        assertEquals("段 1 的第一句是全局第 3 句", 3, m.paragraphOffsets[1])
    }

    /** 一句在它那一段里走到哪儿了：界面拿它乘上"这一段实测多高"算红字在不在屏幕上。 */
    @Test
    fun fractionInsideAParagraphRunsFromZeroToOne() {
        val m = FollowAlign.align(body("甲一。甲二。甲三。甲四。", "乙一。乙二。"), 1_000L)
        assertEquals("段首那一句在 0", 0f, m.fractionInParagraph(0), 0.001f)
        assertEquals("四等分的一段：第三句在该段的中间", 0.5f, m.fractionInParagraph(2), 0.001f)
        assertEquals("下一段的第一句回到 0", 0f, m.fractionInParagraph(4), 0.001f)
        assertEquals("第二段的第二句在该段中间", 0.5f, m.fractionInParagraph(5), 0.001f)
    }

    /** 红字要摆到屏幕上三分之一：偏移 = 段高 × 进度 − 视口/3，负数夹到 0。 */
    @Test
    fun theScrollOffsetPutsTheLineInTheUpperThirdOfTheViewport() {
        assertEquals("长段过半：段里第 1800px 那一格贴到上沿，再往上抬半屏的三分之一", 1300,
            followScrollOffset(3000, 0.6f, 1500))
        assertEquals("段很短、红字就在段首：不往下滚", 0, followScrollOffset(1000, 0.1f, 1500))
        assertEquals("还没量到段高（整段不在屏幕上）：先滚到段首，下一轮再摆正", 0,
            followScrollOffset(0, 0.9f, 1500))
        assertEquals("红字在段尾：滚到段里 2700px 那一格", 2200, followScrollOffset(3000, 0.9f, 1500))
    }

    /**
     * 录音开头先把**回目**念一遍才念正文（四回书实测第一处长停顿在 6.14/3.76/7.00/6.18 秒），
     * 而站点的正文里没有这一句。调用方把集名摆成第一行之后，正文第一句就不该还钉在 0 秒 ——
     * 钉在 0 秒就是"声音还在念回目，红字已经在正文第三句"。
     */
    /**
     * 分母必须数的是分子那一批量。`count` 的半格是**往上取整**，一段里每句都进一位：
     * 「一二三。四五六。」整段 = 7 格，可两句各自 4 格、加起来 8 格。原来分母拿整段（7）、
     * 分子拿每句（8）累加，于是 `cum/total` 一路超过 1，**整张表随段数往后拖** ——
     * 真机上 67 段那一回量到 30:04 处晚了 13.9 秒（≈ 两句）。
     */
    @Test
    fun theWeightsOfTheSentencesAreExactlyTheWholeTimeline() {
        val m = FollowAlign.align(body("一二三。四五六。"), 8_000L)
        assertEquals("两句各 4 格 → 总 8 格，第二句从 4000 起", 1, m.sentenceAt(4_000L))
        assertEquals("末句终点必须正好是整集时长，不能冲过去：" + m.paragraphs[0][1].endMs,
            8_000L, m.paragraphs[0][1].endMs)
        assertTrue("任何一句的终点都不许超过时长", m.paragraphs.all { q -> q.all { it.endMs <= 8_000L } })
        // 段数越多，原来那个"每段进一位"的差额积得越多：这里 6 段 × 每段多 1 格
        val six = FollowAlign.align(body(*Array(6) { "一二三。四五六。" }), 1_000L)
        assertEquals("6 段 12 句 48 格：979ms 处该在最后一句（全局第 11 句）", 11, six.sentenceAt(47 * 1000L / 6))
        assertEquals("末句终点 = 时长（差额没处藏）", 1_000L, six.paragraphs[5][1].endMs)
    }

    @Test
    fun anAnnouncedTitleOwnsTheSecondsItIsReadAloud() {
        // 格数：回目 19 个汉字 = 19 格；「此开卷第一回也。」7 字 + 句号 = 8 格；
        // 「作者自云，曾历一梦。」8 字 + 两个标点 = 9 格。合计 36 格，时长给 36000ms → 一格一毫秒。
        val title = "第一回 甄士隐梦幻识通灵　贾雨村风尘怀闺秀"
        val m = FollowAlign.align(body(title, "此开卷第一回也。作者自云，曾历一梦。"), 36_000L)
        assertEquals("回目 + 两句正文", 3, m.sentences)
        assertEquals("回目念完之前红字不许进正文", 0, m.sentenceAt(18_999L))
        assertEquals("第 19000ms 起才是正文第一句", 1, m.sentenceAt(19_000L))
        assertEquals("正文第二句在 27000ms", 2, m.sentenceAt(27_000L))
        // 不摆回目（= 这一条改动之前）：正文第一句就钉在 0 秒，而那 19 秒声音还在念回目
        assertEquals(0L, startOf(FollowAlign.align(body("此开卷第一回也。作者自云，曾历一梦。"), 36_000L), 0))
    }

    @Test
    fun emptyOrBlankTextYieldsNothingToHighlight() {
        assertEquals(0, FollowAlign.align("", 1_000L).sentences)
        assertEquals(0, FollowAlign.align("\n\n   \n", 1_000L).sentences)
        assertEquals(-1, FollowAlign.align("", 1_000L).sentenceAt(500L))
    }
}
