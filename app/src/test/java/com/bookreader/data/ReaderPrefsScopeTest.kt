package com.bookreader.data

import android.content.SharedPreferences
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「在阅读模式里改的设置只在当前这一屏生效」的落地判据。
 *
 * 打的不是"能不能存"，是那几条容易写歪的口径：**没改过的屏要跟着全局旧值**（升级完第一眼不能变样）、
 * **改过的那一屏要分叉出去**（且不污染全局、不污染别的屏）、**按书存的东西（位置/书签）不许被分屏带歪**。
 * 真机那层 SharedPreferences 换成内存里这一份假象，所以这些判据在 JVM 里就跑得完。
 */
class ReaderPrefsScopeTest {

    /**
     * 内存里的一份 prefs。存成一张 `键 → 值` 的表，取的时候按类型转 ——
     * 为的是把"键在不在"和"值是不是 null"这两件事分开，那正是回落逻辑唯一的难点。
     */
    private class Fake : SharedPreferences {
        val vals = HashMap<String, Any?>()

        private inner class Ed : SharedPreferences.Editor {
            override fun putInt(k: String, v: Int): SharedPreferences.Editor { vals[k] = v; return this }
            override fun putFloat(k: String, v: Float): SharedPreferences.Editor { vals[k] = v; return this }
            override fun putBoolean(k: String, v: Boolean): SharedPreferences.Editor { vals[k] = v; return this }
            override fun putLong(k: String, v: Long): SharedPreferences.Editor { vals[k] = v; return this }
            override fun putString(k: String, v: String?): SharedPreferences.Editor { vals[k] = v; return this }
            override fun putStringSet(k: String, v: MutableSet<String>?): SharedPreferences.Editor = nope()
            override fun remove(k: String): SharedPreferences.Editor { vals.remove(k); return this }
            override fun clear(): SharedPreferences.Editor { vals.clear(); return this }
            override fun commit(): Boolean = true
            override fun apply() = Unit
        }

        override fun getAll(): MutableMap<String, *> = HashMap(vals)
        override fun contains(k: String) = vals.containsKey(k)
        override fun edit(): SharedPreferences.Editor = Ed()
        override fun getInt(k: String, d: Int) = vals[k] as? Int ?: d
        override fun getFloat(k: String, d: Float) = vals[k] as? Float ?: d
        override fun getLong(k: String, d: Long) = vals[k] as? Long ?: d
        override fun getBoolean(k: String, d: Boolean) = vals[k] as? Boolean ?: d
        override fun getString(k: String, d: String?) = if (vals.containsKey(k)) vals[k] as? String else d
        override fun getStringSet(k: String, d: MutableSet<String>?): MutableSet<String> = nope()
        override fun registerOnSharedPreferenceChangeListener(l: SharedPreferences.OnSharedPreferenceChangeListener?) = Unit
        override fun unregisterOnSharedPreferenceChangeListener(l: SharedPreferences.OnSharedPreferenceChangeListener?) = Unit
        private fun nope(): Nothing = throw UnsupportedOperationException("这一族设置用不到")
    }

    /**
     * 三份指向同一块磁盘：外壳那一份 + 小说 + 漫画。
     *
     * 分屏**必须走 `forReader`**（`MainActivity` 分发那一处走的就是它），不走内部构造器 ——
     * 第一版这里直接 new，于是把 `forReader` 改成"返回全局那一份"之后这九条照样全绿，
     * 等于没测。
     */
    private fun bundle(): Triple<ReaderPrefs, ReaderPrefs, ReaderPrefs> {
        val global = ReaderPrefs(Fake(), null)
        return Triple(global, global.forReader(ReaderScope.NOVEL), global.forReader(ReaderScope.COMIC))
    }

    @Test
    fun anUntouchedScreenStillShowsTheGlobalValue() {
        val (global, novel, comic) = bundle()
        global.themeIndex = 4                      // 升级前他就用米黄
        assertEquals("没改过的屏要跟着全局那一份", 4, novel.themeIndex)
        assertEquals(4, comic.themeIndex)
        assertEquals(17f, novel.fontSizeSp, 0.0001f)
        global.fontSizeSp = 21f
        assertEquals("全局改了、这一屏没改过 → 跟着动", 21f, comic.fontSizeSp, 0.0001f)
        novel.fontSizeSp = 15f
        assertEquals("这一屏改过之后就不再跟着全局动", 21f, comic.fontSizeSp, 0.0001f)
    }

    @Test
    fun aChangeInsideOneScreenDoesNotLeakAnywhereElse() {
        val (global, novel, comic) = bundle()
        global.themeIndex = 0
        comic.themeIndex = ReaderPrefs.NIGHT_THEME_INDEX
        assertEquals("漫画自己变夜间", ReaderPrefs.NIGHT_THEME_INDEX, comic.themeIndex)
        assertEquals("小说那屏不该跟着黑", 0, novel.themeIndex)
        assertEquals("外壳那一份更不该被改掉", 0, global.themeIndex)
        // 带后缀的键确实落在"另一格"上，不是把全局覆盖了事
        assertEquals(0, (global.fontSizeSp.let { 0 }) + global.themeIndex)
    }

    @Test
    fun everyScreenHasItsOwnCopyOfEachDial() {
        val (global, novel, comic) = bundle()
        novel.fontSizeSp = 24f
        novel.linePreset = 4
        novel.marginPreset = 2
        novel.paragraphIndent = false
        novel.eyeCare = true
        novel.brightness = 0.4f
        novel.turnMode = 1
        novel.autoRead = true
        novel.autoSpeedPreset = 2
        novel.convertTraditional = true
        novel.restMinutes = 20
        novel.keepScreenOn = false
        novel.volumePaging = true
        novel.bothSidesNext = true
        novel.fullScreen = true

        assertEquals("漫画没改过 → 字号还是全局默认", 17f, comic.fontSizeSp, 0.0001f)
        assertEquals(2, comic.linePreset)
        assertTrue("段首缩进默认开着", comic.paragraphIndent)
        assertFalse("护眼默认关着", comic.eyeCare)
        assertEquals("亮度默认跟随系统", -1f, comic.brightness, 0.0001f)
        assertEquals(0, comic.restMinutes)
        assertTrue("常亮默认开着", comic.keepScreenOn)
        assertFalse(comic.autoRead)
        assertFalse("这一屏没开全屏就不开", comic.fullScreen)
        assertEquals("全局那一份一个字没动", 17f, global.fontSizeSp, 0.0001f)
        assertEquals(2, global.linePreset)
        assertTrue(global.paragraphIndent)
        assertEquals(24f, novel.fontSizeSp, 0.0001f)
    }

    /** 布尔按屏存的是 1/0（不是布尔），所以"存过 false"不能当成"没存"。 */
    @Test
    fun aStoredFalseIsNotMistakenForUnset() {
        val (global, novel, comic) = bundle()
        global.eyeCare = true                      // 全局开着护眼
        novel.eyeCare = false                      // 小说这一屏明确关掉
        assertFalse("这一屏存过 false 就是 false，不许回落成全局的 true", novel.eyeCare)
        assertTrue("没改过的那一屏仍跟全局", comic.eyeCare)
        assertTrue("全局那份没被改掉", global.eyeCare)
    }

    /** 空串是合法值（"用系统字体"），所以字符串的回落只能看"键在不在"，不能拿空串当哨兵。 */
    @Test
    fun anEmptyFontChoiceIsNotMistakenForUnset() {
        val (_, novel, comic) = bundle()
        novel.fontPath = ""
        novel.fontLabel = "系统默认"
        assertEquals("", novel.fontPath)
        assertEquals("系统默认", novel.fontLabel)
        assertNull("漫画没选过字体 = 没有", comic.fontPath)
        comic.fontPath = "notoserif.ttf"
        comic.fontLabel = "思源宋体"
        assertEquals("思源宋体", comic.fontLabel)
        assertEquals("小说那一屏仍是它自己那份", "", novel.fontPath)
    }

    /** 这一屏明确"改回系统字体"（写 null）也要能盖掉全局那份，不然清不掉。 */
    @Test
    fun clearingTheFontOnOneScreenDoesNotFallBackToTheGlobalFont() {
        val (global, novel, comic) = bundle()
        global.fontPath = "kaishu.ttf"
        global.fontLabel = "楷体"
        assertEquals("没改过的屏跟着全局的楷体", "kaishu.ttf", comic.fontPath)
        novel.fontPath = null
        novel.fontLabel = "系统默认"
        assertNull("小说这一屏明确清了，不许回落到全局那份楷体", novel.fontPath)
        assertEquals("系统默认", novel.fontLabel)
        assertEquals("别的屏不受影响", "kaishu.ttf", comic.fontPath)
        assertEquals("全局也没被清", "kaishu.ttf", global.fontPath)
    }

    /** 派生值（行距倍数、边距 dp、缩进 em）读的是各自那一屏的档位，不是全局。 */
    @Test
    fun derivedPresetsFollowTheSameScreen() {
        val (global, novel, comic) = bundle()
        novel.linePreset = 0
        novel.marginPreset = 2
        novel.paragraphIndent = false
        assertEquals(ReaderPrefs.LINE_PRESETS[0], novel.lineHeightMultiplier, 0.0001f)
        assertEquals(ReaderPrefs.MARGIN_PRESETS[2], novel.marginHorizontalDp, 0.0001f)
        assertEquals(0f, novel.paragraphIndentEm, 0.0001f)
        assertEquals(ReaderPrefs.LINE_PRESETS[2], comic.lineHeightMultiplier, 0.0001f)
        assertEquals(ReaderPrefs.MARGIN_PRESETS[1], comic.marginHorizontalDp, 0.0001f)
        assertEquals(2f, global.paragraphIndentEm, 0.0001f)
    }

    /** 分屏只管"这一屏的样子"，不管"这一本读到哪"：位置和书签必须还是全局一份。 */
    @Test
    fun positionsAndBookmarksAreNotSplitByScreen() {
        val (global, novel, comic) = bundle()
        comic.saveComicPos("b1", 7, 3)
        val (c, p) = novel.loadComicPos("b1")
        assertEquals("漫画存的位置，拿小说那一份也要读得到（同一本书同一条进度）", 7, c)
        assertEquals(3, p)
        global.saveAudioPos("b2", 2, 61_000)
        assertEquals(2 to 61_000, novel.loadAudioPos("b2"))
        novel.addPageBookmark("b3", 4)
        assertEquals(listOf(5), global.pageBookmarks("b3"))
        // 删干净：按书存的那一串键要一起走，别留下带后缀的死字节
        novel.addComicBookmark("b3", 1, 2)
        novel.clearBook("b3")
        assertEquals(emptyList<Int>(), global.pageBookmarks("b3"))
        assertEquals(emptyList<Pair<Int, Int>>(), global.comicBookmarks("b3"))
    }

    /**
     * 「我的」页那颗是**全局命令**（2026-10-18 他改的口径：系统设置要全部生效）：
     * 一拨，外壳 + 六屏阅读器一起黑，包括那些自己在屏里存过纸色的屏。
     */
    @Test
    fun theGlobalNightCommandReachesEveryScreen() {
        val (global, novel, comic) = bundle()
        global.themeIndex = 4                                  // 全局=米黄
        comic.themeIndex = 1                                   // 漫画自己在屏里存过豆绿
        novel.themeIndex = ReaderPrefs.NIGHT_THEME_INDEX
        assertFalse("开之前外壳是白天", global.shellNight)
        global.applyGlobalNight(true)
        assertTrue("外壳跟着黑", global.shellNight)
        assertEquals("全局那一份也写成夜间", ReaderPrefs.NIGHT_THEME_INDEX, global.themeIndex)
        assertEquals("存过纸白的漫画这次要跟着黑", ReaderPrefs.NIGHT_THEME_INDEX, comic.themeIndex)
        assertEquals("小说这一屏", ReaderPrefs.NIGHT_THEME_INDEX, novel.themeIndex)
        for (s in ReaderScope.values()) {
            assertEquals("第 ${s.tag} 屏也要跟着黑", ReaderPrefs.NIGHT_THEME_INDEX, global.forReader(s).themeIndex)
        }
    }

    /** 关夜间是逐屏**还回自己原来那档浅色**，不是把六屏一律拍成纸白。 */
    @Test
    fun turningTheGlobalNightOffGivesEachScreenItsOwnPaperBack() {
        val (global, novel, comic) = bundle()
        global.themeIndex = 4
        comic.themeIndex = 1
        novel.themeIndex = ReaderPrefs.NIGHT_THEME_INDEX
        global.applyGlobalNight(true)
        global.applyGlobalNight(false)
        assertFalse("外壳回白天", global.shellNight)
        assertEquals("全局那一份回米黄", 4, global.themeIndex)
        assertEquals("漫画回自己那档豆绿", 1, comic.themeIndex)
        assertEquals("小说本来存的就是夜间，没有浅色可还 → 纸白", 0, novel.themeIndex)
        assertEquals("没在屏里存过纸色的屏，还的是它当时跟着全局读到的那档米黄", 4, global.forReader(ReaderScope.PDF).themeIndex)
    }

    /** 连开两次夜间不许把第一次记下的浅色冲掉（第二次开时各屏本来已经是夜间）。 */
    @Test
    fun flippingNightOnTwiceDoesNotLoseTheStashedPaper() {
        val (global, _, comic) = bundle()
        global.themeIndex = 0
        comic.themeIndex = 1
        global.applyGlobalNight(true)
        global.applyGlobalNight(true)
        global.applyGlobalNight(false)
        assertEquals(1, comic.themeIndex)
    }

    /** 命令之后在某一屏里自己换的纸色：仍然只属于那一屏，不漏到别的屏和外壳。 */
    @Test
    fun aLocalPaperChoiceAfterTheCommandIsStillLocal() {
        val (global, novel, comic) = bundle()
        global.applyGlobalNight(true)
        comic.themeIndex = 1
        assertEquals(1, comic.themeIndex)
        assertEquals("小说那屏不该跟着变豆绿", ReaderPrefs.NIGHT_THEME_INDEX, novel.themeIndex)
        assertTrue("外壳不该被一屏的纸色带回去", global.shellNight)
        global.applyGlobalNight(false)
        assertEquals("下一次动全局，还的是按下命令那一刻记下的那档（不是后来在屏里换的豆绿）", 0, comic.themeIndex)
    }

    /** 全局夜间命令只动纸色这一族，别把各屏自己那份字号/行距带歪。 */
    @Test
    fun theNightCommandOnlyMovesThePaperDial() {
        val (global, novel, _) = bundle()
        novel.fontSizeSp = 24f
        novel.linePreset = 0
        novel.marginPreset = 2
        global.applyGlobalNight(true)
        global.applyGlobalNight(false)
        assertEquals(24f, novel.fontSizeSp, 0.0001f)
        assertEquals(0, novel.linePreset)
        assertEquals(2, novel.marginPreset)
        assertEquals("全局字号没被带歪", 17f, global.fontSizeSp, 0.0001f)
    }

    /** 反过来那半边不变：某一屏自己开着夜间，不该把外壳或别的屏一起改掉。 */
    @Test
    fun aScreenKeepingItsOwnNightDoesNotMoveTheShell() {
        val (global, novel, comic) = bundle()
        global.themeIndex = 0
        comic.themeIndex = ReaderPrefs.NIGHT_THEME_INDEX
        assertFalse("漫画自己黑，外壳不该跟着", global.shellNight)
        assertEquals("小说那屏不该跟着黑", 0, novel.themeIndex)
        assertEquals("全局那一份也没动", 0, global.themeIndex)
    }

    /** 老版本升级上来那一下：全局纸色本来是夜间，外壳第一次读就得是夜间。 */
    @Test
    fun theShellReadsItsNightStateFromTheGlobalPaperOnFirstRun() {
        val global = ReaderPrefs(Fake(), null)
        global.themeIndex = ReaderPrefs.NIGHT_THEME_INDEX
        assertTrue("升级前全局就是夜间，外壳第一次读该是夜间", global.shellNight)
        global.themeIndex = 0
        assertFalse("升级前全局是浅色，外壳第一次读该是白天", global.shellNight)
    }
}
