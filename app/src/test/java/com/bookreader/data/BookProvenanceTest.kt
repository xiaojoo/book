package com.bookreader.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 「这本书是从哪条源下来的」那份记录。它决定界面上摆不摆「全本」那颗，
 * 所以最怕的是读回来一个截断的地址 —— 那表现是点下去抓不到东西，而不是报错。
 */
class BookProvenanceTest {

    // 每个用例一个独立目录：JUnit 是"一个类一个实例跑全部方法"，共用一个目录的话
    // 第三个用例会把第一个写的文件留着，判出来的"覆盖"就成了假象
    private fun dirFor(name: String): File =
        File(System.getProperty("java.io.tmpdir"), "prov-" + System.nanoTime() + "-" + name).apply { deleteOnExit() }

    @Test
    fun aBookUrlFullOfEqualsSurvivesTheRoundTrip() {
        val dir = dirFor("roundtrip")
        val url = "http://www.example-hu.com/0/321/?from=search&id=12345#x"
        BookProvenance(dir).save("shuhu", url, "  一段简介。  ", listOf("第一章 起", "  ", "第二章 承"))
        val back = BookProvenance(dir)
        assertTrue("存过就该认得", back.known)
        assertEquals(url, back.bookUrl())
        assertEquals("shuhu", back.ruleId())
        assertEquals("简介两头的空白要抹掉，中间不动", "一段简介。", back.intro())
        assertEquals("空的章名不算一条", listOf("第一章 起", "第二章 承"), back.outline())
    }

    @Test
    fun aBookThatNeverCameFromASourceReadsAsUnknown() {
        val dir = dirFor("unknown")
        dir.mkdirs()
        val p = BookProvenance(dir)
        assertFalse("本地导入的那本书没有这份记录，界面上不该摆「全本」", p.known)
        assertEquals("", p.bookUrl())
        assertEquals("", p.intro())
        assertEquals(emptyList<String>(), p.outline())
    }

    @Test
    fun savingTwiceReplacesTheOldRecord() {
        val dir = dirFor("twice")
        BookProvenance(dir).save("shuhu", "http://a/1/", "旧简介", listOf("甲"))
        BookProvenance(dir).save("mengshu", "http://b/2/", "新简介", listOf("乙", "丙"))
        val back = BookProvenance(dir)
        assertEquals("mengshu", back.ruleId())
        assertEquals("新简介", back.intro())
        assertEquals(listOf("乙", "丙"), back.outline())
    }

    /** 书架那一层的反查：第二次点下载要靠它找回架上那本，不然同一本书会摆两本。 */
    @Test
    fun theShelfKnowsWhichBookCameFromWhichSourcePage() {
        val root = dirFor("shelf")
        BookProvenance(File(root, "1-夜无疆")).save("mengshu", "http://m/1/", "", listOf())
        BookProvenance(File(root, "2-剑来")).save("aixiashu", "http://a/2/", "", listOf())
        File(root, "3-本地导入").mkdirs()
        val map = BookProvenance.downloadedBooks(root)
        assertEquals("两条记录 = 两本（没 source.tsv 的那本不算）", 2, map.size)
        assertEquals("1-夜无疆", map[BookProvenance.key("mengshu", "http://m/1/")])
        assertEquals("同书名不同源不能撞键", "2-剑来", map[BookProvenance.key("aixiashu", "http://a/2/")])
        assertEquals("这条源上没下过这本", null, map[BookProvenance.key("mengshu", "http://m/2/")])
    }

    /** 重复下过同一本（这一版之前留下的现场）：写回最近那本，别随机挑一本。 */
    @Test
    fun duplicatedCopiesResolveToTheNewestOne() {
        val root = dirFor("dup")
        BookProvenance(File(root, "1000-同一本")).save("mengshu", "http://m/1/", "", listOf())
        BookProvenance(File(root, "2000-同一本")).save("mengshu", "http://m/1/", "", listOf())
        assertEquals("2000-同一本", BookProvenance.downloadedBooks(root)[BookProvenance.key("mengshu", "http://m/1/")])
    }

    @Test
    fun anEmptyRootIsNotAFailure() {
        assertEquals(emptyMap<String, String>(), BookProvenance.downloadedBooks(File(rootOf("nope-" + System.nanoTime()))))
    }

    private fun rootOf(name: String): String =
        File(System.getProperty("java.io.tmpdir"), name).apply { deleteOnExit() }.path
}
