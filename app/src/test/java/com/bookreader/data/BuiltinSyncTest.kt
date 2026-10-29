package com.bookreader.data

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 随包源"手机上那份旧了要不要跟着包换"的对账。
 *
 * 判据只有三个哈希，全部纯字符串：不碰文件、不碰 prefs、不碰 Context —— 这一档要错，两种表现都很贵：
 * 一种是把人家手改过的一份静默吃掉，一种是包里修好了规则却永远到不了手机
 * （2026-10-26 华为上"精选只有漫画有封面"量的就是后者：包里的规则 16:3x 就改了，手机上那份还是 14:31 那一版）。
 */
class BuiltinSyncTest {

    private val old = "hash-我们写下去的那一版"
    private val new = "hash-包里换过的新一版"

    @Test
    fun missingAndNeverSeededSeedsIt() {
        assertEquals(BuiltinSync.SEED, decideBuiltin(null, null, new, everSeeded = false))
    }

    @Test
    fun deletedBuiltinNeverComesBackByItself() {
        // 种过而文件没了 = 他按过那颗 ✕。自动换版不许把它复活，否则那颗删除是假的
        assertEquals(BuiltinSync.SKIP, decideBuiltin(null, old, new, everSeeded = true))
        // 换了十个版本也不复活
        repeat(10) { v -> assertEquals(BuiltinSync.SKIP, decideBuiltin(null, old, "第${v}版", everSeeded = true)) }
    }

    @Test
    fun fileAlreadyMatchingPackageOnlyNeedsTheBookkeeping() {
        assertEquals(BuiltinSync.CURRENT, decideBuiltin(new, null, new, everSeeded = true))
        // 账上记的还是上一版也算 CURRENT：手上这份已经是包里这一版了，没什么可换
        assertEquals(BuiltinSync.CURRENT, decideBuiltin(new, old, new, everSeeded = true))
    }

    @Test
    fun unmodifiedStaleFileFollowsThePackage() {
        // 记过账，而手上那份和记账那版一字不差 ⇒ 那份是我们自己写下去的、他没动 ⇒ 跟着包走
        assertEquals(BuiltinSync.UPDATE, decideBuiltin(old, old, new, everSeeded = true))
    }

    @Test
    fun handEditedFileIsLeftAlone() {
        // 手上这份和账上那版对不上：那是他自己粘一条同名的源改出来的，只有「恢复随包」换得掉
        assertEquals(BuiltinSync.KEEP, decideBuiltin("他改的那一份", old, new, everSeeded = true))
    }

    @Test
    fun aLegacyInstallIsNeverGuessed() {
        // 这套账是后加的：老机器上没记过账，那份旧文件到底是"旧包"还是"他改过的"分不清 ⇒ 不许自动写
        assertEquals(BuiltinSync.KEEP, decideBuiltin(old, null, new, everSeeded = true))
    }

    @Test
    fun onePressOfRestorePutsALegacyFileOnTheBooks() {
        // 老机器的自愈路径：按一次「恢复随包」→ 文件就是包里那一版 → CURRENT 把账记上 →
        // 从下一个版本起这一条自己跟着包走。这两拍连起来才看得出"不猜"没有把老机器永远冻在旧规则上
        assertEquals(BuiltinSync.KEEP, decideBuiltin(old, null, new, everSeeded = true))
        assertEquals(BuiltinSync.CURRENT, decideBuiltin(new, null, new, everSeeded = true))
        val recordedAfterRestore = new
        assertEquals(BuiltinSync.UPDATE, decideBuiltin(new, recordedAfterRestore, "更新的版本", everSeeded = true))
    }

    @Test
    fun anEditedFileStaysEditedAcrossVersions() {
        // KEEP 不记账，所以往后包里再换版也还是 KEEP —— 不许"改过一次"熬几版之后被自动冲掉
        repeat(4) { v ->
            assertEquals(BuiltinSync.KEEP, decideBuiltin("他改的那一份", old, "包里的第${v + 2}版", everSeeded = true))
        }
    }
}
