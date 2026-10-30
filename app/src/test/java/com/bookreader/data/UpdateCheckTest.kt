package com.bookreader.data

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 书架下拉刷新那三把尺子：多几章怎么算、"上次知道的那份目录"取哪个数、红点怎么存进 prefs。
 *
 * 判的是两类会看得见的假话：
 * - 把"没读"报成"没下"：一本全本 886 章、读到 18 章的书，按本地章数算会报 868 章更新；
 * - 存储里一个认不出的片段把整串红点带崩（改了格式也要能读回能读的那几本）。
 */
class UpdateCheckTest {

    @Test
    fun onlyChaptersNewerThanTheOutlineWeKnownCount() {
        assertEquals(4, UpdateCheck.delta(siteChapters = 890, lastKnown = 886))
        assertEquals("站点没多就一句 0，不许报负数", 0, UpdateCheck.delta(886, 890))
        assertEquals(0, UpdateCheck.delta(886, 886))
        assertEquals("空目录（站点那一页没回章）不算多了整本", 0, UpdateCheck.delta(0, 886))
    }

    @Test
    fun theYardstickIsTheOutlineNotWhatWeIngested() {
        // 小说：清单上那个数是"入库了几章"（18），来源里那份大纲才是整本（886）
        assertEquals(886, UpdateCheck.lastKnown(metaChapters = 18, outlineChapters = 886))
        // 漫画：清单写的本来就是整份目录（188），盘上只有 1 章也不影响这把尺子
        assertEquals(188, UpdateCheck.lastKnown(metaChapters = 188, outlineChapters = 188))
        assertEquals("两边都空 = 没有可比的，别拿 0 当基准去报整本", 0, UpdateCheck.lastKnown(0, 0))
    }

    @Test
    fun aWholeBookReadToChapterEighteenIsNotAn868ChapterUpdate() {
        val site = 890
        val d = UpdateCheck.delta(site, UpdateCheck.lastKnown(metaChapters = 18, outlineChapters = 886))
        assertEquals("读没读到不关红点的事", 4, d)
    }

    @Test
    fun dotsSurviveAStorageRoundTrip() {
        val m = linkedMapOf("1730-夜无疆" to 4, "1731-剑来" to 1)
        assertEquals(m, UpdateCheck.decode(UpdateCheck.encode(m)))
        assertEquals("一条都没有就是空表，不是 null", emptyMap<String, Int>(), UpdateCheck.decode(""))
        assertEquals(emptyMap<String, Int>(), UpdateCheck.decode(UpdateCheck.encode(emptyMap())))
    }

    @Test
    fun garbageInThatCellDropsItselfNotTheWholeRow() {
        val back = UpdateCheck.decode("1-剑来=3,坏了,2-夜无疆=abc,3-吞神=0,4-神秘=7")
        assertEquals(mapOf("1-剑来" to 3, "4-神秘" to 7), back)
    }

    @Test
    fun separatorsCannotBeSmuggledInsideABookId() {
        // 书名里带逗号/等号（"第一章, 第二章" 那种标题会被削进 id）：写之前抹掉，否则读回来凭空多一条
        val m = UpdateCheck.decode(UpdateCheck.encode(mapOf("9-甲=乙,丙" to 2)))
        assertEquals("抹掉逗号和等号后只剩一个键", setOf("9-甲乙丙"), m.keys)
        assertEquals(2, m["9-甲乙丙"])
    }
}
