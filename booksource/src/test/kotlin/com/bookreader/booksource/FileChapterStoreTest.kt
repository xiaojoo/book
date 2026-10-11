package com.bookreader.booksource

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * 断点续抓的落盘那一半。要验的不是"能读写文件"，而是**进程死了它还得认得那些章**——
 * 手机在下载中途被杀是常态，认不出来就是白下几十兆。
 */
class FileChapterStoreTest {

    @get:Rule
    val dir = TemporaryFolder()

    private fun key(book: String, chapter: String) = "shuhu|$book|$chapter"

    @Test
    fun aChapterWrittenOnceIsReadableByAFreshInstance() {
        val k = key("http://h/0/321/", "http://h/0/321/7.html")
        FileChapterStore(dir.root).put(k, "第七章的正文。")
        // 新实例 = 重启进程之后
        assertEquals("第七章的正文。", FileChapterStore(dir.root).get(k))
        assertEquals(1, FileChapterStore(dir.root).stored())
    }

    @Test
    fun sameChapterPathInTwoBooksDoesNotCollide() {
        val a = key("http://h/0/321/", "http://h/0/321/1.html")
        val b = key("http://h/0/999/", "http://h/0/999/1.html")
        val store = FileChapterStore(dir.root)
        store.put(a, "甲书第一章")
        store.put(b, "乙书第一章")
        assertEquals("甲书第一章", store.get(a))
        assertEquals("乙书第一章", store.get(b))
        assertEquals("两章就是两个文件", 2, store.stored())
    }

    @Test
    fun keysWithIllegalAndLongNamesStillLandOnOneFileEach() {
        val nasty = key("http://h/a?b=c&d=中文/斜杠:冒号", "http://h/书名很长".repeat(20) + "/1.html")
        val store = FileChapterStore(dir.root)
        store.put(nasty, "内容")
        assertEquals("内容", store.get(nasty))
        assertEquals("文件名只能是十六进制摘要 + .txt", 1, dir.root.listFiles { f: File -> f.isFile }!!.size)
        assertTrue(dir.root.listFiles { f: File -> f.name.matches(Regex("[0-9a-f]{40}\\.txt")) }!!.isNotEmpty())
    }

    /** 半截文件（写到一半被杀）不能被当成"这一章有了"——那样一本会缺章还不报。 */
    @Test
    fun halfWrittenTempFileIsNotCountedAsAChapter() {
        val k = key("http://h/0/321/", "http://h/0/321/9.html")
        val store = FileChapterStore(dir.root)
        File(store.fileOf(k).path + ".part").writeText("只写到一半", Charsets.UTF_8)
        assertNull(store.get(k))
        assertEquals(".part 不算一章", 0, store.stored())
    }

    @Test
    fun emptyStoredChapterCountsAsMissing() {
        val store = FileChapterStore(dir.root)
        val k = key("http://h/", "http://h/1.html")
        store.put(k, "")
        assertNull("空正文不该占住一个章位", store.get(k))
    }

    private fun FileChapterStore.fileOf(key: String): File {
        // 和实现里同一个算法：SHA-1(UTF-8 键) 的十六进制
        val d = java.security.MessageDigest.getInstance("SHA-1").digest(key.toByteArray(Charsets.UTF_8))
        return File(dir.root, d.joinToString("") { "%02x".format(it) } + ".txt")
    }
}
