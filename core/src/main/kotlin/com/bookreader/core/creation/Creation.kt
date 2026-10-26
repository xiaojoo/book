package com.bookreader.core.creation

import com.bookreader.core.layout.spokenChars

/**
 * 卡片只有这三档。模型回了别的类别（"势力"、"地点"）**一律不算**：
 * 抽屉里多一栏没人认识的标签，比少一条卡片更难解释。
 */
enum class CardKind(val label: String) {
    Timeline("时间线"),
    Person("人物"),
    Skill("技能"),
    ;

    companion object {
        /** 认中文标签，也认英文枚举名（有的模型会自己翻成 `Person`）。 */
        fun of(raw: String): CardKind? {
            val s = raw.trim()
            return entries.firstOrNull { it.label == s || it.name.equals(s, ignoreCase = true) }
        }
    }
}

/** 一张卡片。[chapter] 是**最后更新它的那一章**，界面上就写"第 N 章更新"，不说"出自"。 */
data class Card(val kind: CardKind, val name: String, val note: String, val chapter: Int)

/** 一章。正文原样落文件（不转义），所以这里只管取字数。 */
data class Chapter(val index: Int, val title: String, val text: String) {
    /** 和听书/跟读那一族**同一把尺子**：汉字字母数字一格、标点半格、空白不算。 */
    val chars: Int get() = spokenChars(text)
    fun toRow() = ChapterRow(index, title, chars)
}

/**
 * `book.tsv` 里的一行章目录。
 *
 * 字数为什么跟着存一份：篇列表那一屏要报"N 章 · N 字"，不存的话它得把每一篇每一章的正文
 * 全读进来才数得出 —— 为报一个数读几百万字。存的这一份只在写那一章时由 [Chapter.toRow] 盖进去，
 * 不会有人绕开正文去改它。
 */
data class ChapterRow(val index: Int, val title: String, val chars: Int)

/** 一部稿子：章按 [index] 排，卡片是整篇一份（逐章累加，见 [CreationFormat.merge]）。 */
data class Novel(
    val id: String,
    val title: String,
    val updatedAt: Long,
    val chapters: List<Chapter>,
    val cards: List<Card>,
) {
    val chars: Int get() = chapters.sumOf { it.chars }
    fun chapter(i: Int): Chapter? = chapters.firstOrNull { it.index == i }
}

/**
 * 落盘格式。两份 TSV，**每行字段一律单行**，所以正文不进 TSV（正文各存一个 `.txt`）。
 *
 * 为什么要 [esc]：章标题、人名、说明都是人写的和模型回的，里面出现制表符或换行是常事，
 * 不转义的话一行会被劈成两行 —— 表现是"卡片莫名其妙多了一条空的"，而没人会想到是字符里有个制表符。
 */
object CreationFormat {

    /** 标题 + 更新时间 + 章目录（序号、标题、字数）。正文不在这里，另文件。 */
    fun writeBook(title: String, updatedAt: Long, chapters: List<ChapterRow>): String =
        (listOf("T\t${esc(title)}\t$updatedAt") + chapters.map { "C\t${it.index}\t${esc(it.title)}\t${it.chars}" })
            .joinToString("\n")

    /** @return 标题、更新时间、章目录。坏行跳过而不是抛：一份里坏一行不该让整篇打不开。 */
    fun readBook(text: String): Book {
        var title = ""
        var updated = 0L
        val rows = ArrayList<ChapterRow>()
        for (line in text.lineSequence()) {
            val f = line.split('\t')
            when (f.firstOrNull()) {
                "T" -> { title = unesc(f.getOrNull(1) ?: ""); updated = f.getOrNull(2)?.toLongOrNull() ?: 0L }
                "C" -> {
                    val i = f.getOrNull(1)?.toIntOrNull() ?: continue
                    // 字数那一格是后加的：老那份读得出章号章名就继续用，缺的按 0 报，不整篇判死
                    rows += ChapterRow(i, unesc(f.getOrNull(2) ?: ""), f.getOrNull(3)?.toIntOrNull() ?: 0)
                }
            }
        }
        return Book(title, updated, rows)
    }

    /** `book.tsv` 读出来的样子。 */
    data class Book(val title: String, val updatedAt: Long, val chapters: List<ChapterRow>)

    fun writeCards(cards: List<Card>): String = cards.joinToString("\n") {
        "K\t${it.kind.label}\t${esc(it.name)}\t${it.chapter}\t${esc(it.note)}"
    }

    fun readCards(text: String): List<Card> = text.lineSequence().mapNotNull { line ->
        val f = line.split('\t')
        if (f.firstOrNull() != "K") return@mapNotNull null
        val kind = CardKind.of(f.getOrNull(1) ?: "") ?: return@mapNotNull null
        val name = unesc(f.getOrNull(2) ?: "")
        val chapter = f.getOrNull(3)?.toIntOrNull() ?: return@mapNotNull null
        if (name.isEmpty()) return@mapNotNull null
        Card(kind, name, unesc(f.getOrNull(4) ?: ""), chapter)
    }.toList()

    /** 按章合并：重新提取同一章 → 先把**这一章**的旧卡摘掉，再并进来；同名（同类别）的换说明、换章号。 */
    fun merge(existing: List<Card>, incoming: List<Card>): List<Card> {
        val touched = incoming.map { it.chapter }.toSet()
        val out = LinkedHashMap<String, Card>()
        for (c in existing.filterNot { it.chapter in touched }) out[c.key()] = c
        for (c in incoming) out[c.key()] = c
        return out.values.toList()
    }

    private fun Card.key() = kind.name + "\u0000" + name

    /** 单行字段里的制表符/换行/反斜杠：转成 `\\t` `\\n` `\\\\`。 */
    fun esc(s: String): String = buildString(s.length + 8) {
        for (c in s) when (c) {
            '\\' -> append("\\\\")
            '\t' -> append("\\t")
            '\n' -> append("\\n")
            '\r' -> { }
            else -> append(c)
        }
    }

    fun unesc(s: String): String = buildString(s.length) {
        var i = 0
        while (i < s.length) {
            val c = s[i]
            if (c != '\\' || i + 1 >= s.length) {
                append(c)
                i++
                continue
            }
            when (s[i + 1]) {
                't' -> append('\t')
                'n' -> append('\n')
                '\\' -> append('\\')
                else -> append(s[i + 1])
            }
            i += 2
        }
    }
}

/** 一次提取的结果：认出来的卡片 + 没认出来的原行（界面上要能说"有 N 行没看懂"，不能静默丢）。 */
data class Parsed(val cards: List<Card>, val dropped: List<String>) {
    val ok: Boolean get() = cards.isNotEmpty() || dropped.isEmpty()
}

/**
 * 提示词与回包解析。
 *
 * 让模型回**竖线分隔的行**而不是 JSON：小模型回 JSON 时坏一个引号就整包解不开，
 * 而按行解析坏一行只丢那一行 —— 这一族"部分成功"比"全有全无"有用得多。
 */
object CreationAi {

    /** 发多少字给模型。一章一般几千字，超了的只发开头（界面上会说出来）。 */
    const val MAX_CHARS = 8_000

    const val SYSTEM =
        "你在帮人整理一部小说的设定。只回若干行，每行一条，格式严格是：类别|名字|说明\n" +
            "类别只能是这三个词之一：时间线、人物、技能。\n" +
            "时间线：名字写这件事的简称（例：主角入城），说明写什么时候发生了什么。\n" +
            "人物：名字写人名，说明写身份与性格。\n" +
            "技能：名字写功法或能力名，说明写它做什么、有什么代价。\n" +
            "原文里没有的就不要编。不要编号、不要 markdown、不要空行、不要多余解释。\n" +
            "这一章什么都没有可提取的，就只回一个字：无"

    fun user(index: Int, title: String, text: String): String {
        val cut = if (text.length > MAX_CHARS) text.substring(0, MAX_CHARS) else text
        val head = "【第 $index 章" + (if (title.isBlank()) "" else "·$title") + "】\n"
        return head + cut
    }

    /** 这一章是不是被截断发的（界面上要如实说一句，不能让人以为抽的是全文）。 */
    fun truncated(text: String): Boolean = text.length > MAX_CHARS

    fun parse(reply: String, chapter: Int): Parsed {
        val cards = ArrayList<Card>()
        val dropped = ArrayList<String>()
        for (raw in reply.lineSequence()) {
            val line = raw.trim().removePrefix("-").removePrefix("*").trim()
            if (line.isEmpty() || line == "无") continue
            // 全角竖线也要认：中文模型很爱把分隔符打成 ｜
            val f = line.split('|', '｜').map { it.trim() }
            val kind = f.getOrNull(0)?.let { CardKind.of(it) }
            val name = f.getOrNull(1).orEmpty()
            if (kind == null || name.isEmpty()) {
                dropped += line
                continue
            }
            // 说明里再出现竖线就不切了：第一段是类别、第二段是名字，剩下的整段都是说明
            cards += Card(kind, name, f.drop(2).joinToString("|"), chapter)
        }
        return Parsed(cards, dropped)
    }
}
