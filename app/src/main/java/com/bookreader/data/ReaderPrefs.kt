package com.bookreader.data

import android.content.Context
import com.bookreader.core.model.BookPosition
import com.bookreader.core.model.PageStyle

/**
 * 阅读偏好和阅读位置。位置只存 absoluteOffset（页码是派生值，存了就会在换字号后说谎）。
 */
class ReaderPrefs(context: Context) {

    private val p = context.getSharedPreferences("reader", Context.MODE_PRIVATE)

    var fontSizeSp: Float
        get() = p.getFloat(KEY_FONT, 17f)
        set(v) = p.edit().putFloat(KEY_FONT, v.coerceIn(12f, 34f)).apply()

    /** 下面四个都是"档位派生出来的值"：面板上按的是档，不是连续滑块。 */
    val lineHeightMultiplier: Float get() = LINE_PRESETS[linePreset]
    val marginHorizontalDp: Float get() = MARGIN_PRESETS[marginPreset]
    val paragraphIndentEm: Float get() = if (paragraphIndent) 2f else 0f

    var themeIndex: Int
        get() = p.getInt(KEY_THEME, 0)
        set(v) = p.edit().putInt(KEY_THEME, v).apply()

    var keepScreenOn: Boolean
        get() = p.getBoolean(KEY_KEEP, true)
        set(v) = p.edit().putBoolean(KEY_KEEP, v).apply()

    /** 0=简洁(瞬切) 1=平移。上下/仿真没做，UI 里点了会提示，不写进这里。 */
    var turnMode: Int
        get() = p.getInt("turnMode", 0)
        set(v) = p.edit().putInt("turnMode", v).apply()

    /** 行间距档位：0松散 1较宽 2适中 3紧凑 4密集 */
    var linePreset: Int
        get() = p.getInt("linePreset", 2)
        set(v) = p.edit().putInt("linePreset", v.coerceIn(0, 4)).apply()

    /** 边距档位：0较窄 1适中 2较宽 */
    var marginPreset: Int
        get() = p.getInt("marginPreset", 1)
        set(v) = p.edit().putInt("marginPreset", v.coerceIn(0, 2)).apply()

    var paragraphIndent: Boolean
        get() = p.getBoolean("indentOn", true)
        set(v) = p.edit().putBoolean("indentOn", v).apply()

    var volumePaging: Boolean
        get() = p.getBoolean("volumePaging", false)
        set(v) = p.edit().putBoolean("volumePaging", v).apply()

    /** 点左右两侧都往后翻（参考截图里那颗开关） */
    var bothSidesNext: Boolean
        get() = p.getBoolean("bothSidesNext", false)
        set(v) = p.edit().putBoolean("bothSidesNext", v).apply()

    /** 护眼模式：叠一层暖色，不是系统级滤光 */
    var eyeCare: Boolean
        get() = p.getBoolean("eyeCare", false)
        set(v) = p.edit().putBoolean("eyeCare", v).apply()

    /** 亮度：-1f 表示跟随系统 */
    var brightness: Float
        get() = p.getFloat("brightness", -1f)
        set(v) = p.edit().putFloat("brightness", v).apply()

    /** 休息提醒分钟数，0=不提醒 */
    var restMinutes: Int
        get() = p.getInt("restMinutes", 0)
        set(v) = p.edit().putInt("restMinutes", v).apply()

    /** 繁→简：只对繁体正文有效果，简体书开了也一个字不改（ICU 是恒等变换） */
    var convertTraditional: Boolean
        get() = p.getBoolean("t2s", false)
        set(v) = p.edit().putBoolean("t2s", v).apply()

    /** 自动阅读 */
    var autoRead: Boolean
        get() = p.getBoolean("autoRead", false)
        set(v) = p.edit().putBoolean("autoRead", v).apply()

    /** 自动阅读速度档，单位是"行/分钟" —— 上下和按页两种模式共用这一个口径 */
    var autoSpeedPreset: Int
        get() = p.getInt("autoSpeed", 1)
        set(v) = p.edit().putInt("autoSpeed", v.coerceIn(0, AUTO_LINES_PER_MIN.lastIndex)).apply()

    val autoLinesPerMinute: Int get() = AUTO_LINES_PER_MIN[autoSpeedPreset]

    /**
     * 最近搜索：只存这台机上，最多 8 条。';' 是存储里的分隔符，所以从词条里剔掉，
     * 否则搜一次 "a;b" 会凭空多出两条历史。
     */
    var recentSearches: List<String>
        get() = (p.getString("recentSearch", "") ?: "").split(';').filter { it.isNotBlank() }
        set(v) = p
            .edit()
            .putString(
                "recentSearch",
                v.map { it.replace(";", "").trim() }.filter { it.isNotEmpty() }.distinct().take(8).joinToString(";"),
            )
            .apply()

    /** 正文字体：null = 系统默认 */
    var fontPath: String?
        get() = p.getString("fontPath", null)
        set(v) = p.edit().putString("fontPath", v).apply()

    /** 昵称：只存在这台机上，没有账号体系。 */
    var nickname: String
        get() = p.getString("nickname", "2015XIAO") ?: "2015XIAO"
        set(v) = p.edit().putString("nickname", v.take(12)).apply()

    var fontLabel: String
        get() = p.getString("fontLabel", "系统默认") ?: "系统默认"
        set(v) = p.edit().putString("fontLabel", v).apply()

    /**
     * 版心。三档设置**当参数传进来而不是在这里读 prefs** —— SharedPreferences 不是可观察状态，
     * 在这里读它，调用方的作用域就不会在档位变化时重算，表现就是"改了行间距/边距要退回书架重开才生效"。
     *
     * @param widthPx/heightPx 正文可用区域（已经减掉顶栏和底栏）
     * @param pxPerDp 密度；sp 还要再乘 fontScale，所以由调用方把 toPx() 的结果传进来
     */
    fun pageStyle(
        widthPx: Float,
        heightPx: Float,
        pxPerDp: Float,
        fontScale: Float,
        linePreset: Int = this.linePreset,
        marginPreset: Int = this.marginPreset,
        paragraphIndent: Boolean = this.paragraphIndent,
    ): PageStyle {
        val fontSizePx = fontSizeSp * pxPerDp * fontScale
        val marginHorizontalDp = MARGIN_PRESETS[marginPreset]
        return PageStyle(
            fontSizePx = fontSizePx,
            lineHeightMultiplier = LINE_PRESETS[linePreset],
            contentWidthPx = widthPx - 2f * marginHorizontalDp * pxPerDp,
            // 上下两条缝都是从这份高度里扣的，不是画的时候往外推：推的话行数不变，
            // 最后一行会顶到系统导航键上。工具栏仍然是浮层、不占版心。
            contentHeightPx = heightPx - 2f * EDGE_INSET_PX,
            paragraphIndentPx = fontSizePx * (if (paragraphIndent) 2f else 0f),
            topInsetPx = EDGE_INSET_PX,
            bottomInsetPx = EDGE_INSET_PX,
        )
    }

    fun savePosition(bookId: String, pos: BookPosition) {
        p.edit().putString("pos.$bookId", "${pos.chapterIndex}|${pos.absoluteOffset}").apply()
    }

    fun loadPosition(bookId: String): BookPosition? {
        val raw = p.getString("pos.$bookId", null) ?: return null
        val parts = raw.split('|')
        if (parts.size < 2) return null
        return BookPosition(parts[0].toIntOrNull() ?: 0, parts[1].toLongOrNull() ?: 0L, 0, 0)
    }

    /** 书签：一本书一串 "chapterIndex|absoluteOffset"。 */
    fun bookmarks(bookId: String): List<BookPosition> =
        (p.getString("marks.$bookId", "") ?: "").split(';')
            .mapNotNull { raw ->
                val parts = raw.split('|')
                if (parts.size < 2) null else BookPosition(parts[0].toIntOrNull() ?: 0, parts[1].toLongOrNull() ?: 0L, 0, 0)
            }

    fun addBookmark(bookId: String, pos: BookPosition) {
        val list = bookmarks(bookId).filter { it.absoluteOffset != pos.absoluteOffset } + pos
        p.edit().putString("marks.$bookId", list.joinToString(";") { "${it.chapterIndex}|${it.absoluteOffset}" }).apply()
    }

    fun removeBookmark(bookId: String, pos: BookPosition) {
        val list = bookmarks(bookId).filter { it.absoluteOffset != pos.absoluteOffset }
        p.edit().putString("marks.$bookId", list.joinToString(";") { "${it.chapterIndex}|${it.absoluteOffset}" }).apply()
    }

    companion object {
        const val KEY_FONT = "fontSizeSp"
        const val KEY_THEME = "theme"
        const val KEY_KEEP = "keepScreenOn"

        /** 参考截图的 5 档行间距：松散/较宽/适中/紧凑/密集 */
        val LINE_PRESETS = floatArrayOf(2.10f, 1.90f, 1.65f, 1.45f, 1.30f)

        /** 3 档边距：较窄/适中/较宽（dp） */
        val MARGIN_PRESETS = floatArrayOf(14f, 22f, 30f)

        /** 顶缝/底缝：正文离内容区上下边各留多宽（px，不跟「边距」那一档走）。 */
        const val EDGE_INSET_PX = 5f

        /** 自动阅读三档速度，单位"行/分钟"：约 1.5 / 3 / 6 秒一行。 */
        val AUTO_LINES_PER_MIN = intArrayOf(12, 24, 48)
    }
}
