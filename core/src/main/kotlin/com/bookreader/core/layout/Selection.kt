package com.bookreader.core.layout

/**
 * 选中底色要不要铺在这一行上。
 *
 * 两边的区间约定不一样，这是差一错误的老窝：`paragraphRange` 交回来的是**闭区间**（`first..last`，
 * `last` 是段落最后一个字符本身），而排版出来的行是**左闭右开**（`[start, end)`，`end` 是行后第一个字符）。
 * 所以"行在范围右边之外"必须判 `start > last`，写成 `start >= last` 就会把**折行的最后一行整行丢掉** ——
 * 那一行正好从 `last` 那个字符开头（真机截图：「…相对而」上了底色，下一行「坐。」没上）。
 */
fun LongRange.coversLine(start: Long, end: Long): Boolean = end > first && start <= last

/**
 * 把一段切成若干句，返回每句在原文里的**起始下标**（空段回 `[0]`，调用方按"至少有一句"处理）。
 *
 * 只按中文句末标点切（。！？；… 和换行），**逗号不切**：切到逗号的话两行的窗口每念两个字就跳一次，
 * 反而看不出读到哪了。句末后面紧跟的收尾引号/括号（`”』」）》`）算在上一句里，
 * 不然「他说：“好。”」会被切成 `他说：“好。` + `」走了` 那种把半个括号甩到下一句的样子。
 */
fun sentenceStarts(text: String): List<Int> {
    val out = ArrayList<Int>()
    var start = 0
    for (i in text.indices) {
        if (text[i] in "。！？；…\n" && i + 1 < text.length) {
            var next = i + 1
            while (next < text.length && text[next] in "”』」）》") next++
            if (next > start) {
                out.add(start)
                start = next
            }
        }
    }
    if (start < text.length) out.add(start)
    return if (out.isEmpty()) listOf(0) else out
}

/**
 * 播放进度（0f..1f = 已过时长 ÷ 整段时长）折算到"念到第几句"—— **按每句的字数分摊**，不按句数等分。
 *
 * 三家 TTS 的 HTTP 口都不回字级时间戳，所以"念到哪"只能按时间推。按句数等分等于假定"每句一样长"，
 * 真段落里不是：红楼梦第一回抽 32 段量过，一段中位 7 句、一句中位 20.9 字，
 * **段内最长句和最短句能差到 14.1 倍**。等分句数推出来的位置，段内最大偏差中位 **6.8 秒**、
 * 最大 **23.6 秒**（= 整段时长的 22–26%），按句位算最多偏到 **2.4 句** ——
 * 他报的"听书的播放音和文字显示不太对齐"就是这一条。
 *
 * 字数口径和有声书跟读那一屏（`FollowAlign`）**是同一把尺子**：
 * **汉字 / 字母 / 数字全算一格，标点算半格，空白不算**。两边各数各的，换屏时高亮会跳一下。
 *
 * **标点为什么记半格 —— 以及这一条其实量不出名堂**。半格是这么来的：拿红楼梦第一回的停顿做锚点
 * （**停顿结束那一刻才是下一句开口** —— 一开始我错用了停顿开始，结论整个反了一次），
 * 489 条稀疏锚点、单调配对 270 句：标点全算 |误差| 中位 1.17 秒、带号 +0.22 秒；半格 0.88 秒、+0.01 秒。
 * 但换**同一回、同一支 mp3、密判据**（ffmpeg `-40dB / d=0.05`，1699 条停顿）重跑一遍半格/全算/
 * 每标点 1.5 格 / 2 格的对照：|误差| 中位 0.38~0.47 秒，**几档彼此分不开**（打乱句序的对照却是 1.7~5.1 秒，
 * 说明这把尺子分得出真假、分不出这些权重）。也就是说：**停顿太密，任何预测都能就近吸附上一条**，
 * 半格 versus 全格差的那点钱在它的分辨率以下。这一格留着是因为它没有证据变坏，不是因为它有证据变好。
 *
 * 真正在开头咬人的不是权重，是**整张表摆错了零点**：录音先把回目念了一遍才念正文，
 * 那一截 4~8 秒在正文里根本没有对应的字。数字和出处见 `AudioReaderScreen` 的 `followText`。
 */
fun sentenceIndexByChars(text: String, starts: List<Int>, frac: Float): Int {
    if (starts.size <= 1 || text.isEmpty()) return 0
    val bounds = starts + text.length
    val w = IntArray(starts.size) { k -> spokenChars(text, bounds[k], bounds[k + 1]) }
    val total = w.sum()
    // 整段一个可数的字都没有（全是空白那种脏数据）：退回等分，至少不越界
    if (total <= 0) return (starts.size * frac).toInt().coerceIn(0, starts.lastIndex)
    val at = frac.coerceIn(0f, 1f) * total
    var acc = 0
    for (k in w.indices) {
        acc += w[k]
        if (at < acc) return k
    }
    return starts.lastIndex
}

/**
 * 这一段区间里"要念出来的"占多少格：汉字 / 字母 / 数字一格，标点**半格**，空白不算。
 * 半格先按 2 倍记整数、最后除回去，免得这里到处飘 Float（`(x+1)/2` 是往上取整）。
 */
private fun spokenChars(text: String, from: Int, to: Int): Int {
    var half = 0
    for (i in from until to) {
        val c = text[i]
        if (c.isLetterOrDigit()) half += 2
        else if (!c.isWhitespace()) half += 1
    }
    return (half + 1) / 2
}
