package com.bookreader.ui

import com.bookreader.data.StoredSource
import com.bookreader.booksource.RuleLoader
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

/**
 * 搜书那一排 chip 的名字。他点名把「全部 / 小说 / 漫画 / 文献」那一行 tab 撤了，
 * 类别改说到每颗链接上 —— 判的是"漫画那颗一眼认得出、小说那排不因此变长"。
 */
class SourceChipTest {

    private fun stored(kind: String?, name: String) = StoredSource(
        file = File("/tmp/$name.json"),
        rule = RuleLoader.load(
            """
            {"id":"$name","name":"$name","homeUrl":"https://$name.example/",
             ${if (kind == null) "" else "\"kind\":\"$kind\","}
             "search":{"url":"https://$name.example/s?k=%s","item":".row","bookName":".a","author":".a","bookUrl":".a @href"},
             "toc":{"item":"a.chap"},"chapter":{"content":"#txt","image":"img @src"}}
            """.trimIndent(), name,
        ),
        error = null,
    )

    @Test
    fun novelsCarryNoSuffixWhileComicAndPaperDo() {
        assertEquals("梦书中文", sourceChip(stored(null, "梦书中文")))
        assertEquals("图库漫画·漫画", sourceChip(stored("comic", "图库漫画")))
        assertEquals("arXiv·文献", sourceChip(stored("paper", "arXiv")))
    }

    /** 反证用：小说那一类要是也带上后缀，整排 chip 一起变长，右边那颗「任务」就被挤出去。 */
    @Test
    fun theDefaultKindIsNotMarked() {
        assertEquals("爱下书小说网", sourceChip(stored("novel", "爱下书小说网")))
    }
}
