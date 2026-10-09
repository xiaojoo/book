package com.bookreader.core

import com.bookreader.core.engine.ReaderEngine
import com.bookreader.core.model.PageStyle
import com.bookreader.core.storage.Book
import org.junit.Test
import java.io.File

/**
 * 探针：段间空行在"页与页相接的那一刀"上到底丢不丢。
 * 上下滚动是把整页首尾相接的，所以页尾丢掉的那条空行在滚动里就是少了一格间距 ——
 * 这就是他说的"简洁切到上下，间距还有细微变化"里唯一能落到像素上的东西。
 */
class SeamBlankProbe {

    /** K40 真机那一档：17sp、行距 1.45、左右 30dp、内容区 1080x2184。 */
    private val deviceStyle = PageStyle(
        fontSizePx = 17f * 2.75f * 0.81f,
        lineHeightMultiplier = 1.45f,
        contentWidthPx = 1080f - 2f * 30f * 2.75f,
        contentHeightPx = 2184f,
        paragraphIndentPx = 17f * 2.75f * 0.81f * 2f,
    )

    @Test
    fun countBlanksLostAtPageSeams() {
        val dir = File(Fixtures.tempRoot("seam"), "jianlai")
        val src = File("G:/小说/剑来.txt")
        if (!src.exists()) {
            println("[seam] 没有 $src，跳过")
            return
        }
        com.bookreader.core.ingest.TxtIngestor().ingest(src, dir, title = "jianlai")
        val book = Book.open(dir, 6)
        val e = ReaderEngine(book, ReaderEngine.approximateBreaker(), deviceStyle)
        var seams = 0
        var lost = 0
        var lostAtSeam = 0
        var paragraphs = 0
        val examples = ArrayList<String>()
        for (ch in 10..19) {
            val total = e.pageCount(ch)
            val pages = e.chapterPages(ch, 0, total)
            val flat = pages.flatMap { it.lines }
            val pageEnd = HashMap<Int, Int>()
            var acc = 0
            for (p in pages) {
                acc += p.lines.size
                pageEnd[acc - 1] = p.startOffset.toInt()
            }
            for (i in flat.indices) {
                val line = flat[i]
                if (line.start == line.end) continue
                if (book.cache.slice(line.end, 1) != "\n") continue
                paragraphs++
                val next = flat.getOrNull(i + 1) ?: continue
                val hasBlank = next.start == next.end
                if (!hasBlank) {
                    lost++
                    if (pageEnd.containsKey(i)) {
                        seams++
                        lostAtSeam++
                        if (examples.size < 5) {
                            examples += "章$ch 页首${pageEnd[i]} 段尾偏移${line.end} 下一行=${book.cache.slice(next.start, 12)}"
                        }
                    }
                }
            }
        }
        println(
            "[seam] 17sp/1.45 版式：段落=$paragraphs 页=$seams 段尾丢空行=$lost " +
                "其中在页界上丢=$lostAtSeam 占比=${"%.1f".format(lostAtSeam * 100.0 / lost.coerceAtLeast(1))}%"
        )
        examples.forEach { println("[seam]   $it") }
        e.close()
    }
}
