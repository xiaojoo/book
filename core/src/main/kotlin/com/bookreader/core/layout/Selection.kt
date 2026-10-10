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
