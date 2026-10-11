package com.bookreader.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 下载历史那张表。它没有数据库兜着，所以判据打在两件事上：
 * **换个实例（= 进程被杀后重启）还读得回来**，以及**一行不会被一句带换行的报错劈成两条**。
 */
class DownloadHistoryTest {

    private fun tmp(): File = File.createTempFile("hist", ".tsv").apply { delete() }

    private fun rec(url: String, error: String = "") = DownloadRecord(
        bookUrl = url, ruleId = "mengshu", bookName = "剑来", author = "烽火戏诸侯",
        sourceName = "梦书中文", state = DownloadRecord.STATE_RUNNING,
        total = 1308, done = 12, chars = 486, missing = 2, bookId = "1-x", error = error,
        startedAt = 1_700_000_000_000L, endedAt = 0L,
    )

    @Test
    fun aRecordSurvivesAFreshInstanceOverTheSameFile() {
        val f = tmp()
        DownloadHistory(f).put(rec("http://www.mcxs.la/1_1639/"))
        val back = DownloadHistory(f).load()
        println("[历史] 重启后读到 ${back.size} 条 = ${back.firstOrNull()}")
        assertEquals(1, back.size)
        assertEquals(rec("http://www.mcxs.la/1_1639/"), back[0])
    }

    @Test
    fun anErrorCarryingNewlinesStaysOneRow() {
        val f = tmp()
        val nasty = "抓到了但进不了书架：\t第 9 章超限\n第二行还带个 = 号"
        DownloadHistory(f).put(rec("u", error = nasty))
        val back = DownloadHistory(f).load()
        println("[历史] 行数=${back.size} 原话=${back.firstOrNull()?.error}")
        assertEquals("一句带制表符和换行的报错不能把一条记录劈成两行", 1, back.size)
        // 代价说清楚：一行一条是这张表的根，所以报错里的制表符和换行落盘前都拍成空格 ——
        // 服务端那句分了几行在界面上看不到，内容本身一个字不少
        assertEquals("抓到了但进不了书架： 第 9 章超限 第二行还带个 = 号", back[0].error)
    }

    @Test
    fun rewritingTheSameBookReplacesItsRowInsteadOfAddingOne() {
        val f = tmp()
        val h = DownloadHistory(f)
        h.put(rec("u").let { it.copy(state = DownloadRecord.STATE_DONE, done = 1308) })
        h.put(rec("u").let { it.copy(state = DownloadRecord.STATE_CANCELLED, done = 20) })
        val back = h.load()
        assertEquals(1, back.size)
        assertEquals(DownloadRecord.STATE_CANCELLED, back[0].state)
        assertEquals("更新的是同一行，不是又记一笔", 1308, back[0].total)
    }

    @Test
    fun aHalfWrittenRowIsDroppedInsteadOfCrashingTheWholeTable() {
        val f = tmp()
        DownloadHistory(f).put(rec("good"))
        f.appendText("http://only-three-columns\n\t\n")
        val back = DownloadHistory(f).load()
        println("[历史] 半截行之后读到 ${back.size} 条")
        assertEquals("坏一行只丢那一行，好那条还要在", 1, back.size)
        assertEquals("good", back[0].bookUrl)
        val after = DownloadHistory(f)
        after.remove("good")
        assertTrue("删掉之后就空了", after.load().isEmpty())
    }

    @Test
    fun anAbsentFileReadsAsEmptyRatherThanThrowing() {
        val f = File(tmp().parentFile, "never-created-" + System.nanoTime() + ".tsv")
        assertEquals(emptyList<DownloadRecord>(), DownloadHistory(f).load())
    }
}
