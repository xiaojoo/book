package com.bookreader.core

import com.bookreader.core.ingest.MarkdownText
import com.bookreader.core.model.BlockKind
import com.bookreader.core.model.InlineFlag
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Markdown 解析的判据。核心是两件事：
 * 1. **标记字符一个不许留在正文里**（否则搜"剑"会命中一串 `**剑**`，字数也虚高）；
 * 2. **样式区间必须落在干净文本的偏移上**（画的时候查的就是这个偏移，错一位就粗错地方）。
 */
class MarkdownTextTest {

    private val md = MarkdownText()

    private fun linesOf(text: String) = text.split('\n').filter { it.isNotEmpty() }

    @Test
    fun blockKindsAndNoMarkersLeft() {
        val src = """
            # 一级
            
            ## 二级
            
            ### 三级
            
            > 引用的一句话
            
            - 苹果
              - 青苹果
            
            1. 第一
            2. 第二
            
            ---
            
            | 名 | 值 |
            | 剑 | 来 |
            
            ```
            fun main() {
                println("hi")
            }
            ```
        """.trimIndent()
        val p = md.parse(src)
        val lines = linesOf(p.text)
        assertEquals(
            listOf(
                "一级", "二级", "三级", "引用的一句话",
                // 无序列表换成真圆点（排版器不画列表符号），有序列表的编号是有信息量的，留在正文里
                "• 苹果", "• 青苹果", "1. 第一", "2. 第二",
                "— — — — — —", "| 名 | 值 |", "| 剑 | 来 |", "fun main() {", "    println(\"hi\")", "}",
            ),
            lines,
        )
        assertFalse("标记字符不许留在正文里：" + p.text, "[*_`#>]".any { it in p.text })
        val kinds = p.styles.blocks.map { Triple(it.start, it.kind, it.level) }
        assertEquals(BlockKind.HEADING1, kinds[0].second)
        assertEquals(BlockKind.HEADING2, kinds[1].second)
        assertEquals(BlockKind.HEADING3, kinds[2].second)
        assertEquals(BlockKind.QUOTE, kinds[3].second)
        assertEquals(BlockKind.LIST, kinds[4].second)
        assertEquals(0, kinds[4].third)
        assertEquals(BlockKind.LIST, kinds[5].second)
        assertEquals("两个空格一级", 1, kinds[5].third)
        assertEquals("有序列表也是列表，只是编号留下", BlockKind.LIST, kinds[6].second)
        assertEquals(BlockKind.RULE, kinds[8].second)
        assertEquals(BlockKind.TABLE, kinds[9].second)
        assertEquals(BlockKind.CODE, kinds[11].second)
        assertEquals("代码的缩进是有意义的，不能吃掉", "    ", lines[12].take(4))
    }

    @Test
    fun inlineSpansLandOnTheCleanText() {
        val p = md.parse("他说**你好**，*世界*和`代码`与~~删~~，见[文字](http://x)和![替代](a.png)")
        assertEquals("他说你好，世界和代码与删，见文字和〔图〕替代", linesOf(p.text).single())
        val clean = linesOf(p.text).single()
        fun flagsOf(word: String): Int {
            val at = clean.indexOf(word)
            assertTrue("找不到「$word」", at >= 0)
            return p.styles.spansIn(at, at + word.length).firstOrNull()?.flags ?: 0
        }
        assertEquals(InlineFlag.BOLD, flagsOf("你好"))
        assertEquals(InlineFlag.ITALIC, flagsOf("世界"))
        assertEquals(InlineFlag.CODE, flagsOf("代码"))
        assertEquals(InlineFlag.STRIKE, flagsOf("删"))
        assertEquals(InlineFlag.LINK, flagsOf("文字"))
        assertEquals("链接地址不该进正文", 0, flagsOf("〔图〕"))
        assertFalse("括号和星号都不该留下：$clean", clean.contains('[') || clean.contains('*'))
        // 区间必须正好盖住那个词：偏移错一位就粗到别处去
        val bold = p.styles.spansIn(0, clean.length).first { it.flags == InlineFlag.BOLD }
        assertEquals("你好", clean.substring(bold.start, bold.end))
    }

    @Test
    fun entitiesAndUnpairedMarkersAreLiteral() {
        fun one(src: String) = linesOf(md.parse(src).text).single()
        assertEquals("A&B <tag> \"q\" 'a'", one("A&amp;B &lt;tag&gt; &quot;q&quot; &apos;a&apos;"))
        // 只有一边有星号：当字面抄，不能把后半行吞掉
        assertEquals("2 * 3 = 6 还有字", one("2 * 3 = 6 还有字"))
        assertEquals("没闭合的**粗", one("没闭合的**粗"))
    }

    @Test
    fun recognizesMdAndRefusesPlainNovel() {
        val dir = Fixtures.tempRoot("md")
        val f = File(dir, "n.md")
        f.writeText("# 标题\n\n正文一段\n", Charsets.UTF_8)
        assertTrue(f.name.endsWith(".md") && md.supports(f))
        val novel = File(dir, "novel.txt")
        novel.writeText("第一章 起风\n少年收住脚，风从崖底上来。\n", Charsets.UTF_8)
        assertFalse("小说 txt 不该被当成 md", md.supports(novel))
        val noExt = File(dir, "notes")
        noExt.writeText("# 甲\n\n- 一\n\n**粗**\n\n> 引\n", Charsets.UTF_8)
        assertTrue("没扩展名但标记够多也该认", md.supports(noExt))
    }

    /**
     * 端到端：md → 抽文本 → 入库 → 回读书里那段偏移上的字。
     * 样式的偏移是相对抽出来的文本算的，入库只要多吃/少吃一个字符，这里指回去就不是那个词了 ——
     * 界面上的表现是"粗体粗到隔壁词上"，那种错没人会报，所以必须在这里钉住。
     */
    @Test
    fun styleOffsetsSurviveIngest() {
        val dir = Fixtures.tempRoot("md-ingest")
        val md = "# 章名\n\n少年**收住脚**，风从崖底*上来*。\n\n```\nfun main() {}\n```\n\n> 引用一句\n\n- 列表项\n"
        val parsed = MarkdownText().parse(md)
        val src = File(dir, "doc.txt")
        src.writeText(parsed.text, Charsets.UTF_8)
        val bookDir = File(dir, "book")
        val meta = com.bookreader.core.ingest.TxtIngestor().ingest(src, bookDir, title = "doc", styles = parsed.styles).meta
        val book = com.bookreader.core.storage.Book.open(bookDir, 4)
        assertEquals("样式索引要跟着入库", parsed.styles.spans.size, book.styles.spans.size)
        assertEquals("块样式索引要跟着入库", parsed.styles.blocks.size, book.styles.blocks.size)
        for (s in book.styles.spans) {
            val word = book.cache.slice(s.start.toLong(), s.end - s.start)
            val flags = when (s.flags) {
                InlineFlag.BOLD -> "粗"
                InlineFlag.ITALIC -> "斜"
                else -> "?"
            }
            assertTrue("样式段指回去不是原词（$flags）：「$word」", word.length in 2..6 && word.none { it == '*' || it == ' ' })
            assertEquals("偏移越界", s.end - s.start, word.length)
        }
        assertTrue("入库字数比抽出来的文本还多：${meta.totalChars}", meta.totalChars <= parsed.text.length)

        val engine = com.bookreader.core.engine.ReaderEngine(book, com.bookreader.core.engine.ReaderEngine.approximateBreaker(), Fixtures.STYLE)
        val page = checkNotNull(engine.openChapter(0)) { "排不出一页" }
        assertEquals("标题行要按 h1 放大", com.bookreader.core.layout.PageComposer.HEADING1_SCALE, page.lines.first().titleScale, 0.001f)
        assertTrue("代码块那行该走等宽", page.lines.any { it.mono })
        assertTrue("引用那行该有标记", page.lines.any { it.quote })
        engine.close()
    }

    /** md 的章是 `#` 决定的：正文那套正则会把 "1. 有序列表" 认成章标题（真机上切出过 7 行的第一章）。 */
    @Test
    fun orderedListsDoNotBecomeChapters() {
        val dir = Fixtures.tempRoot("md-chapters")
        // 章正文要够长：入库有"平均章长 < 40 字就并成一章"的防碎章规则，太短的夹具会走到那条路上，
        // 测不到这里想测的东西
        val body = "少年收住脚，风从崖底上来。".repeat(6)
        val md = "# 甲章\n\n$body\n\n1. 有序第一\n2. 有序第二\n\n- 无序一项\n\n# 乙章\n\n$body\n"
        val parsed = MarkdownText().parse(md)
        val src = File(dir, "doc.txt").apply { writeText(parsed.text, Charsets.UTF_8) }
        val bookDir = File(dir, "book")
        val meta = com.bookreader.core.ingest.TxtIngestor().ingest(src, bookDir, title = "doc", styles = parsed.styles).meta
        val book = com.bookreader.core.storage.Book.open(bookDir, 4)
        val titles = book.chapters.chapters.map { it.title }
        assertEquals("章数该等于 # 一级标题的个数", listOf("甲章", "乙章"), titles)
        assertEquals(2, meta.chapterCount)
        // 对照：不带样式索引走同一条路，正文那套正则会把列表行认成章标题 ——
        // 真机上量到的就是这个形状：[前言, 2. 有序第二]
        val plainDir = File(dir, "plain")
        com.bookreader.core.ingest.TxtIngestor().ingest(src, plainDir, title = "doc").meta
        val plainTitles = com.bookreader.core.storage.Book.open(plainDir, 4).chapters.chapters.map { it.title }
        assertTrue(
            "没有样式索引时该按正文正则认章，而那会把列表行当成章标题（这才是上面那条判据的对照）：$plainTitles",
            plainTitles.any { "有序第" in it },
        )
    }
}
