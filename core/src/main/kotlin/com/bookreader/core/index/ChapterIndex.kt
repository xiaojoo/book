package com.bookreader.core.index

import com.bookreader.core.model.Chapter

/**
 * 章节索引：跳第 532 章是一次二分 + 一次 seek，不是从头读到 532。
 */
class ChapterIndex(val chapters: List<Chapter>) {

    val size: Int get() = chapters.size

    init {
        require(chapters.isNotEmpty()) { "章节索引不能为空" }
        for (i in 1 until chapters.size) {
            require(chapters[i].startOffset == chapters[i - 1].endOffset) {
                "章节区间不连续: $i ${chapters[i - 1].endOffset} -> ${chapters[i].startOffset}"
            }
        }
    }

    operator fun get(index: Int): Chapter = chapters[index]

    /** 包含 offset 的章节；offset 落在书末之后时返回最后一章。 */
    fun chapterAt(offset: Long): Chapter {
        var lo = 0
        var hi = chapters.size - 1
        while (lo < hi) {
            val mid = (lo + hi + 1) ushr 1
            if (chapters[mid].startOffset <= offset) lo = mid else hi = mid - 1
        }
        return chapters[lo]
    }

    fun indexOf(offset: Long): Int = chapterAt(offset).index

    /** 章节标题的浅层匹配，给"搜索章节名"用。 */
    fun findByTitle(query: String, limit: Int = 50): List<Chapter> {
        val q = query.trim()
        if (q.isEmpty()) return emptyList()
        return chapters.asSequence().filter { it.title.contains(q, ignoreCase = true) }.take(limit).toList()
    }
}
