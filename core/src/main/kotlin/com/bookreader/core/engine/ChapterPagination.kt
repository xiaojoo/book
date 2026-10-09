package com.bookreader.core.engine

import com.bookreader.core.layout.PageComposer
import com.bookreader.core.model.Chapter
import com.bookreader.core.model.Page

/**
 * 一章的分页结果。权威数据是"每一页的起始字符偏移"这张表，Page 只是它的缓存。
 *
 * 为什么不做全书分页：页起始表要连着排下去才知道，全书排等于把千万字排一遍。
 * 所以页码是章内页码，全书进度用 absoluteOffset/totalChars。
 */
class ChapterPagination(
    val chapter: Chapter,
    private val composer: PageComposer,
    maxCachedPages: Int,
    private val onComposed: () -> Unit = {},
) {
    private val starts = ArrayList<Long>(64)
    private val pages = LruPages(maxCachedPages)
    private var exhausted = false
    var pagesComposed = 0L
        private set

    init {
        starts.add(chapter.startOffset)
    }

    val isComplete: Boolean get() = exhausted

    /** 已经排出来的页数；未排完时是下界。 */
    val knownPageCount: Int get() = starts.size

    @Synchronized
    fun pageAt(index: Int): Page? {
        if (index < 0) return null
        extend(index)
        if (index >= starts.size) return null
        pages.get(index)?.let { return it }
        val page = composeAt(index) ?: return null
        pages.put(index, page)
        return page
    }

    /** 排到第 index 页为止（预取用，不返回内容）。 */
    @Synchronized
    fun warmUp(index: Int) {
        pageAt(index)
    }

    /** 含 offset 的那一页。要把章首到 offset 之间的页排出来，这是恢复位置的固有代价。 */
    @Synchronized
    fun indexOfOffset(offset: Long): Int {
        if (offset <= chapter.startOffset) return 0
        var i = 0
        while (i <= starts.size) {
            val page = pageAt(i) ?: return (starts.size - 1).coerceAtLeast(0)
            if (offset < page.endOffset) return i
            i++
            require(i < MAX_PAGES) { "一页一章里页数异常: ${chapter.title}" }
        }
        return (starts.size - 1).coerceAtLeast(0)
    }

    /** 整章排完，返回总页数；空章返回 0。 */
    @Synchronized
    fun totalPages(): Int {
        if (pageAt(0) == null) return 0
        pageAt(MAX_PAGES - 1)
        return starts.size
    }

    private fun extend(upTo: Int) {
        var guard = 0
        while (!exhausted && starts.size <= upTo) {
            val last = starts.size - 1
            val page = pages.get(last) ?: composeAt(last)?.also { pages.put(last, it) }
            if (page == null) {
                exhausted = true
                break
            }
            val next = page.endOffset
            if (next <= starts[last] || next >= chapter.endOffset) {
                exhausted = true
                break
            }
            starts.add(next)
            if (guard++ > MAX_PAGES) throw IllegalStateException("章节页数超限: ${chapter.title}")
        }
    }

    private fun composeAt(index: Int): Page? {
        pagesComposed++
        onComposed()
        return composer.pageStartingAt(starts[index], chapter.startOffset, chapter.endOffset, chapter.index)
    }

    private class LruPages(max: Int) {
        private val map = object : LinkedHashMap<Int, Page>(max * 2, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Int, Page>?): Boolean = size > max
        }

        fun get(index: Int): Page? = synchronized(map) { map[index] }
        fun put(index: Int, page: Page) { synchronized(map) { map[index] = page } }
    }

    companion object {
        const val MAX_PAGES = 20_000
    }
}
