package com.bookreader.core

import com.bookreader.core.engine.ReaderEngine
import com.bookreader.core.ingest.TxtIngestor
import com.bookreader.core.layout.ApproximateBreaker
import com.bookreader.core.layout.LineBreaker
import com.bookreader.core.model.PageStyle
import com.bookreader.core.storage.Book
import org.junit.Test
import java.io.File

/** 临时探针：在真书上检查 fit 的下标契约。跑完就删。 */
class ContractProbe {

    private class Checking(val inner: LineBreaker) : LineBreaker by inner {
        var calls = 0
        val bad = ArrayList<String>()

        override fun fit(text: CharSequence, from: Int, until: Int, availWidth: Float): Int {
            calls++
            val ok = from >= 0 && until >= from && until <= text.length && text.length >= 0
            if (!ok && bad.size < 8) {
                bad.add("fit(from=$from until=$until len=${text.length} avail=$availWidth) 片段=${
                    text.toString().take(24).replace('\n', '|')
                }")
            }
            val r = inner.fit(text, from, until, availWidth)
            if (r < from + 1 && bad.size < 12) bad.add("fit 没前进: from=$from until=$until -> $r")
            if (r > until && bad.size < 12) bad.add("fit 越过 until: from=$from until=$until -> $r")
            return r
        }

        override fun widthOf(text: CharSequence, from: Int, until: Int): Float {
            if ((from < 0 || until > text.length || until < from) && bad.size < 16) {
                bad.add("widthOf(from=$from until=$until len=${text.length})")
            }
            return inner.widthOf(text, from, until)
        }
    }

    @Test
    fun checkFitContractOnRealBook() {
        val src = File("G:/小说/剑来.txt")
        if (!src.exists()) {
            println("[contract] 没有那个文件，跳过")
            return
        }
        val dir = File(Fixtures.tempRoot("contract"), "jianlai")
        TxtIngestor().ingest(src, dir, "剑来")
        val book = Book.open(dir, 6)
        val style = Fixtures.STYLE
        val checker = Checking(ApproximateBreaker(style))
        val e = ReaderEngine(book, { checker }, style)
        var chapters = 0
        var pages = 0
        while (chapters < 6 && e.currentPage() != null) {
            pages++
            if (e.nextPage() == null) {
                chapters++
                if (chapters < 6) e.jumpToChapter(chapters)
            }
        }
        println("[contract] fit调用=${checker.calls} 页=$pages 违例=${checker.bad.size}")
        checker.bad.forEach { println("[contract]   $it") }
        e.close()
    }
}
