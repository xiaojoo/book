package com.bookreader.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver

/**
 * 阅读配色。5 档纸色 + 夜间，是从参考截图的 5 个色块反算的：
 * 色块本身是被压暗的预览（实测 #B2B2B2 / #959E8B / #8F9CA5 / #AC9A9A / #AFA389，约 0.72 倍），
 * 所以 tile 存截图量到的值（面板上画它才和参考一致），page 存反算后的真实底色。
 */
enum class ReaderTheme(
    val label: String,
    val page: Int,
    val ink: Int,
    val faint: Int,
    val bar: Int,
    val accent: Int,
    val tile: Int,
    val night: Boolean,
) {
    Paper("纸白", 0xFFFFFFFF.toInt(), 0xFF22262E.toInt(), 0xFF8B93A1.toInt(), 0xFFF2F4F7.toInt(), 0xFFE30000.toInt(), 0xFFB2B2B2.toInt(), false),
    Green("豆绿", 0xFFCFDBC1.toInt(), 0xFF2A3325.toInt(), 0xFF6E7A66.toInt(), 0xFFC3D0B4.toInt(), 0xFFB03A2E.toInt(), 0xFF959E8B.toInt(), false),
    Blue("青灰", 0xFFC7D9E5.toInt(), 0xFF22303A.toInt(), 0xFF677986.toInt(), 0xFFBCCFDD.toInt(), 0xFFB03A2E.toInt(), 0xFF8F9CA5.toInt(), false),
    Pink("藕粉", 0xFFEFD6D6.toInt(), 0xFF3A2525.toInt(), 0xFF8C6E6E.toInt(), 0xFFE4C8C8.toInt(), 0xFFB03A2E.toInt(), 0xFFAC9A9A.toInt(), false),
    Cream("米黄", 0xFFF3E2BE.toInt(), 0xFF3A2F1C.toInt(), 0xFF8C7A5E.toInt(), 0xFFE8D6AE.toInt(), 0xFFB03A2E.toInt(), 0xFFAFA389.toInt(), false),
    Night("夜间", 0xFF0F0F0F.toInt(), 0xFFB9BFC9.toInt(), 0xFF6A7079.toInt(), 0xFF17181A.toInt(), 0xFFFF6B6B.toInt(), 0xFF2B2B2B.toInt(), true),
    ;

    val pageColor: Color get() = Color(page)
    val inkColor: Color get() = Color(ink)
    val faintColor: Color get() = Color(faint)
    val barColor: Color get() = Color(bar)
    val accentColor: Color get() = Color(accent)
    val tileColor: Color get() = Color(tile)

    /** 工具栏和正文同色之后只剩这条发丝线分界：墨色按 12% 压进纸色，六档共用一条规则，不各写一个 hex。 */
    val dividerColor: Color get() = inkColor.copy(alpha = 0.12f).compositeOver(pageColor)

    companion object {
        val ALL = values()
        /** 色块行只给 5 档纸色，夜间走底栏那颗按钮。 */
        val SWATCHES = listOf(Paper, Green, Blue, Pink, Cream)
        fun of(index: Int): ReaderTheme = ALL[index.mod(ALL.size)]
    }
}
