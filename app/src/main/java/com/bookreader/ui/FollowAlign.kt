package com.bookreader.ui

import kotlin.math.max

/** 一句（或一段里没有句末标点时的那一整段）：它的文字 + 它在音频里占的那一毫秒段。 */
data class FollowLine(val text: String, val startMs: Long, val endMs: Long)

/**
 * 一集的跟读表：正文切成段、段再切成句，每句带着**推算**出来的起止毫秒。
 *
 * 只做一件事 —— 给一个播放毫秒，还回该亮第几句、第几段。滚动、上色、点哪一句跳播都归界面那一头。
 *
 * [timed] 是"这张表的时刻是不是真的"：时长还没读出来（播放器还没 prepare 完）时整表时刻都是 0，
 * 那时**一句都不该亮** —— 亮在第几句上是编出来的。
 */
class FollowMap(private val paras: List<List<FollowLine>>, val timed: Boolean) {

    /** 全部句子拍平的一条线：[sentenceAt] 在它上面二分，靠 startMs 单调递增。 */
    private val flat: List<FollowLine> = paras.flatten()

    /** 每一段的第一句在 [flat] 里的下标（段号 → 全局句号）。 */
    val paragraphOffsets: List<Int> = run {
        var acc = 0
        paras.map { q -> acc.also { acc += q.size } }
    }

    val paragraphs: List<List<FollowLine>> get() = paras
    val sentences: Int get() = flat.size

    /**
     * 播放到 `ms` 该亮第几句（全局句号）。没正文、时长还没读到 → -1。
     *
     * 一张表是**连续**摊出来的（后一句的起点就是前一句的终点，中间不留缝），所以"落在哪一句"
     * 就是"起点不超过它的那一句里最靠后的那一句"。
     */
    fun sentenceAt(ms: Long): Int {
        if (flat.isEmpty() || !timed) return -1
        if (ms <= flat.first().startMs) return 0
        if (ms >= flat.last().startMs) return flat.lastIndex
        var lo = 0
        var hi = flat.lastIndex
        while (lo < hi) {
            val mid = (lo + hi + 1) / 2
            if (flat[mid].startMs <= ms) lo = mid else hi = mid - 1
        }
        return lo
    }

    /** 第几句落在第几段（画一行的时候要知道自己是谁，也用来只给当前那一段上色）。 */
    fun paragraphOf(sentence: Int): Int {
        if (sentence < 0) return -1
        var lo = 0
        var hi = paragraphOffsets.lastIndex
        while (lo < hi) {
            val mid = (lo + hi + 1) / 2
            if (paragraphOffsets[mid] <= sentence) lo = mid else hi = mid - 1
        }
        return lo
    }

    /**
     * 这一句在它那一段里走到哪儿了（0 = 段首，1 = 段末）。
     *
     * 用的是**时刻**而不是字符下标：这一张表的时刻本来就是按字数摊的，两者是同一条线，
     * 而界面那一头要拿它乘上"这一段实测有多高"去算红字在不在屏幕上（见 `FollowPane`）。
     */
    fun fractionInParagraph(i: Int): Float {
        val p = paragraphOf(i)
        if (p < 0) return 0f
        val lines = paras[p]
        val from = lines.first().startMs
        val to = lines.last().endMs
        if (to <= from) return 0f
        return (lines[i - paragraphOffsets[p]].startMs - from).toFloat() / (to - from)
    }
}

/**
 * 要把红字摆到"屏幕上三分之一"那一档，这一段的**哪一像素**该贴到视口上沿。
 *
 * 段高传 0 = 这一整段还没滚进屏幕、问不到高度 —— 那就先滚到段首，下一轮量到了再摆正
 * （`FollowPane` 里那个最多三轮的循环干的就是这件事；一上来就想知道段高是问不到的）。
 * 负数夹到 0：段很短、红字就在段首那一下，往下滚只会把上一段的尾巴顶掉。
 */
internal fun followScrollOffset(paragraphHeightPx: Int, fraction: Float, viewportHeightPx: Int): Int =
    (paragraphHeightPx * fraction).toInt().minus(viewportHeightPx / 3).coerceAtLeast(0)

/**
 * 一本有声书的"文字跟着读"：把整集正文的时刻摊出来。
 *
 * **站点不给时间轴**。古文岛那一族的集页上只有整回一支 mp3 加一整页纯正文，页面里翻遍了没有
 * `.lrc`/`.srt`/时间戳那一样（把它的播放器和 JS 都翻过：进度条只管音频自己，没有文字那条轨）。
 * 所以只能估，估法取最朴素的一条：**按字数线性分摊** —— 一句占的时长 = 整集时长 ×
 * 这一句的字数 ÷ 整集字数，**每一集开头归零重来**。
 *
 * 为什么敢这么摊，量过（红楼梦抽十回，跨越全书）：
 *  - 同一本书抽十回（跨全书）量到的朗读速率：0.2684–0.2923 秒/字，均值 0.2804，展幅 8.5%
 *    ⇒ 字数几乎就是时间（第一回 67 段、非空白 7768 格、音频 2272.3 秒 = 0.2925 秒/格）；
 *  - "每回归零"不是省事，是必要：拿全书平均速率去推**单**回，最偏的那一回（第 40 回 9346 字）
 *    到回末已经差出 **97 秒** —— 而一回内部最大只差几秒，见下。
 *
 * 回**内部**漂多少：两把尺子都上了。一把拿这回的 489 条停顿当真值做整体单调配对，
 * "按字数线性分"打得过 120 次"把句序打乱"的对照（代价 465.5，对照最好的一次 535.7）——
 * 线性这条假设是真的；但那把尺子的逐句读数不能当误差用（停顿密到平均 4.6 秒一条，
 * 任何预测都能就近吸附上一条，打乱句序照样量出 1.19 秒，也就是说 1.1 秒是**地板**不是误差）。
 * 另一把独立的从音频数音节起头（四种参数各跑一遍），四把之间互差 16–25 秒，比信号还大，
 * 只能读出量级：回内偏移在**几秒**这一档。
 *
 * 合起来的结论：**回内的漂移不超过一句的长度** —— 句均 28 字 ≈ 8 秒，最好读数 ≤6 秒、最坏读数 ≤13 秒，
 * 所以按句上色不会一句一句地错位。（这一屏上"跟随"为什么按句滚、又为什么只在看不见时才滚，
 * 写在 `AudioReaderScreen` 的 `FollowPane` 顶上：古文岛一段能有 700 多字，跟着段滚会把红字顶到屏幕外面。）
 *
 * **但零点摆错过一整句**：录音开头先把回目**连作者**念一遍才念正文。四回书（红楼 1/3/118、三国 1）
 * 实测第一处长停顿（≥0.4 秒）的起点 **6.14 / 3.76 / 7.00 / 6.18 秒**，按「(回目＋作者) 字数 × 0.28 秒/字」
 * 推出来是 6.16 / 6.16 / 7.00 / 6.16 秒 —— 三回对到 0.1 秒内（第三回那处落在回目中间）。
 * 站点交回来的正文里没有这一句，表从 0 秒摊正文就等于整张表一开始就早一句。
 * 补在调用方（`AudioReaderScreen.followText` 把「集名 + 作者」摆成第一行），不补在这一格 ——
 * 这一格只拿"文本＋时长"，它不知道集名和作者是什么。只补集名、不补作者会剩约 1 秒的头偏，
 * 那一条的数字写在 `AudioReaderScreen` 的 `announce` 上头。
 *
 * 字数口径：**汉字 / 字母 / 数字一格、标点半格、空白不算**（`count`）。上面那些速率数字是用
 * "非空白全算"量的。半格这把尺子本身在那停顿尺子的分辨率以下（同一回密判据 1699 条停顿上
 * 0 / 0.5 / 1 / 1.5 / 2 格量出的 |误差| 中位都是 0.38~0.47 秒，分不开），留着只是因为没证据它变坏。
 */
object FollowAlign {

    /** 一句在哪里结束：中文那几个句末标点。逗号不算 —— 一句里逗号一大把。 */
    private const val ENDERS = "。！？；…"

    /**
     * 整集正文 + 整集时长 → [FollowMap]。
     *
     * 正文按"空一行分段"（[com.bookreader.booksource.chapterText] 交出来的就是这个形状），
     * 段内再按句末标点切句。`durationMs <= 0`（时长还没读出来）时交出来的表 [timed] 是 false，
     * 时刻全 0 —— 界面上那一句都不亮，比编一个时长诚实。
     *
     * **分母必须和分子数的是同一批量**：先切好句、把每句的格数加起来当总格数。原来分母写的是
     * `paras.sumOf { count(整段) }`，而分子一格一格加的是 `count(每句)` —— `count` 里那个
     * `(半格 + 1) / 2` 是**往上取整**，一段里每多一句就多进一位，于是"每句格数之和"总比"整段格数"
     * 多出零点几到几格。分子跑得比分母快，`cum / total` 越到后面越大，**整张表随段数往后拖**：
     * 红楼梦第一回 67 段，量到的是分母少 56 格 = 每格被放大 0.77%，机器上真机读到 30:04 那一句
     * 表上写 1801927ms、按音频算该是 1788 秒 —— **晚了 13.9 秒 ≈ 两句**，正是他报的"又慢了"。
     */
    fun align(text: String, durationMs: Long): FollowMap {
        val paras = text.split('\n').map { it.trim() }.filter { it.isNotEmpty() }
        // 先切句：段里一个句末标点都没有（整段是一条引文、一首诗）时整段算一句，别让这一段凭空消失
        val lines = paras.map { p -> sentences(p).ifEmpty { listOf(p) } }
        val total = lines.sumOf { q -> q.sumOf { count(it) } }
        if (total == 0) return FollowMap(emptyList(), false)
        val dur = max(0L, durationMs)
        var before = 0
        val out = ArrayList<List<FollowLine>>(lines.size)
        for (q in lines) {
            var used = 0
            val one = ArrayList<FollowLine>(q.size)
            for (s in q) {
                val n = count(s)
                one += FollowLine(s, ms(before + used, total, dur), ms(before + used + n, total, dur))
                used += n
            }
            out += one
            before += used
        }
        return FollowMap(out, dur > 0L)
    }

    /**
     * 段内切句：句末标点**后面**断。紧跟在标点后的收尾引号/括号归这一句的尾巴
     * （不然"……都完了。”"会被切在引号里面，高亮断在半句上）。
     */
    private fun sentences(paragraph: String): List<String> {
        val out = ArrayList<String>()
        val cur = StringBuilder()
        var i = 0
        while (i < paragraph.length) {
            val c = paragraph[i]
            cur.append(c)
            if (c in ENDERS) {
                while (i + 1 < paragraph.length && paragraph[i + 1] in "”’》」』）)") {
                    i++
                    cur.append(paragraph[i])
                }
                out += cur.toString()
                cur.setLength(0)
            }
            i++
        }
        if (cur.isNotEmpty()) out += cur.toString()
        // 只有一格的"句"（脏数据里会有）并回前一句：高亮成一格孤字一跳一跳的
        val merged = ArrayList<String>(out.size)
        for (s in out) {
            if (merged.isNotEmpty() && count(s) < 2) merged[merged.lastIndex] += s else merged += s
        }
        return merged
    }

    /**
     * 这一串占多少格：**汉字 / 字母 / 数字一格，标点半格**，空白不算。
     * 和 `:core` 的 `spokenChars`（听书页那把尺子）同一条口径，两屏不能各数各的。
     * 半格本身在停顿尺子的分辨率以下（红楼梦第一回密判据 1699 条停顿上，0 / 0.5 / 1 / 1.5 / 2 格
     * 量出的 |误差| 中位都在 0.38~0.47 秒这一档，分不开）—— 数字和口径的老实话写在
     * [com.bookreader.core.layout.sentenceIndexByChars] 上面。
     */
    private fun count(s: String): Int {
        var half = 0
        for (c in s) half += if (c.isLetterOrDigit()) 2 else if (!c.isWhitespace()) 1 else 0
        return (half + 1) / 2
    }

    private fun ms(chars: Int, total: Int, durationMs: Long): Long =
        if (durationMs <= 0L || total <= 0) 0L else durationMs * chars / total
}
