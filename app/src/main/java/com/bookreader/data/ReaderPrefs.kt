package com.bookreader.data

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import com.bookreader.core.model.BookPosition
import com.bookreader.core.model.PageStyle

/**
 * 哪一屏阅读器。设置按这一格分开存：六屏各有一份，在漫画里换的纸色不会串到小说里，
 * 在阅读页调的字号也不会改掉有声书（他点名的口径：阅读模式里改的东西只在**当前这一屏**生效）。
 */
enum class ReaderScope(val tag: String) {
    NOVEL("novel"), LISTEN("listen"), COMIC("comic"), PDF("pdf"), PPT("ppt"), AUDIO("audio"),
}

/**
 * 阅读偏好和阅读位置。位置只存 absoluteOffset（页码是派生值，存了就会在换字号后说谎）。
 *
 * **两副面孔**：`ReaderPrefs(context)` 是外壳那一份（全局键），`forReader(范围)` 是某一屏阅读器那一份。
 * 同一个 SharedPreferences，只有[下面标了「按屏」的那十几档]的键名带上 `.范围` 后缀。
 *
 * 读的时候**这一屏存过就读这一屏的，没存过就跟着全局那一份走** —— 不做一次性迁移，
 * 于是第一次翻开任何一屏，看到的还是他今天这一套设置，只有他真在某屏里改过，那一屏才分叉出去。
 * 写的时候永远写带后缀的那一份（不会去动全局，所以改漫画不会把外壳一起改掉）。
 *
 * 唯一的例外是 `themeIndex` 那一档的**全局夜间命令** [applyGlobalNight]：它反过来把全局和六屏一次全写，
 * 因为「我的」页那颗开关要的是"全部生效"，而各屏原来那档浅色另有 [KEY_PAPER] 一格接着，关掉还能还回去。
 */
class ReaderPrefs internal constructor(private val p: SharedPreferences, private val scope: ReaderScope?) {

    constructor(context: Context) : this(context.getSharedPreferences("reader", Context.MODE_PRIVATE), null)

    /** 翻开某一屏阅读器拿到的那一份设置。 */
    fun forReader(s: ReaderScope): ReaderPrefs = ReaderPrefs(p, s)

    /** 这一屏有没有存过这一格：哨兵值法，不用 `p.all`（那玩意每次调用复制一整张表，组合期要读十几格）。 */
    private fun intOf(name: String, def: Int): Int {
        val s = scope ?: return p.getInt(name, def)
        val v = p.getInt("$name.${s.tag}", Int.MIN_VALUE)
        return if (v == Int.MIN_VALUE) p.getInt(name, def) else v
    }

    private fun floatOf(name: String, def: Float): Float {
        val s = scope ?: return p.getFloat(name, def)
        val v = p.getFloat("$name.${s.tag}", Float.NaN)
        return if (v.isNaN()) p.getFloat(name, def) else v
    }

    /** 布尔没有可用的哨兵值，所以**带后缀那一份存成 1/0**；全局那一份仍是布尔（老键不动）。 */
    private fun boolOf(name: String, def: Boolean): Boolean {
        val s = scope ?: return p.getBoolean(name, def)
        val v = p.getInt("$name.${s.tag}", -1)
        return if (v < 0) p.getBoolean(name, def) else v != 0
    }

    private fun stringOf(name: String, def: String?): String? {
        val s = scope ?: return p.getString(name, def)
        val v = p.getString("$name.${s.tag}", null)
        return when {
            // 键不在 = 这一屏没存过 → 跟着全局那一份
            v == null -> p.getString(name, def)
            // 空串是合法值（"用系统字体"就是空），所以"明确清空"另有哨兵，不能靠 null 表达
            v == NULL_MARK -> null
            else -> v
        }
    }

    private fun putInt(name: String, v: Int) = p.edit { if (scope == null) putInt(name, v) else putInt("$name.${scope.tag}", v) }
    private fun putFloat(name: String, v: Float) = p.edit { if (scope == null) putFloat(name, v) else putFloat("$name.${scope.tag}", v) }
    private fun putBool(name: String, v: Boolean) = p.edit { if (scope == null) putBoolean(name, v) else putInt("$name.${scope.tag}", if (v) 1 else 0) }
    private fun putString(name: String, v: String?) = p.edit {
        if (scope == null) putString(name, v) else putString("$name.${scope.tag}", v ?: NULL_MARK)
    }

    /** 按屏：字号。 */
    var fontSizeSp: Float
        get() = floatOf(KEY_FONT, 17f)
        set(v) = putFloat(KEY_FONT, v.coerceIn(12f, 34f))

    /** 下面四个都是"档位派生出来的值"：面板上按的是档，不是连续滑块。 */
    val lineHeightMultiplier: Float get() = LINE_PRESETS[linePreset]
    val marginHorizontalDp: Float get() = MARGIN_PRESETS[marginPreset]
    val paragraphIndentEm: Float get() = if (paragraphIndent) 2f else 0f

    /**
     * 纸色六档（含夜间）。某一屏阅读器里改它 = **只改这一屏**（`forReader` 那一份）；
     * 「我的」页那颗开关改的是全部七层，见 [applyGlobalNight]。
     */
    var themeIndex: Int
        get() = intOf(KEY_THEME, 0)
        set(v) = putInt(KEY_THEME, v)

    /** 外壳那一份的夜间状态：只有 [applyGlobalNight] 写它，「我的」页那颗开关没有第二条路。 */
    val shellNight: Boolean get() = p.getBoolean("shellNight", legacyShellNight())

    /**
     * 「我的 → 夜间模式」那颗开关：**一条命令打到底**，外壳加六屏阅读器一起换。
     *
     * 他点的口径（2026-10-18）：在「我的」里改的要**全部生效**，在某一屏阅读器里改的才只在那一屏生效。
     * 所以要**逐屏写进各屏自己那一格**，不能只动全局那一份 —— 只动全局的话，凡是他曾经在某一屏里
     * 自己换过纸色的那一屏（这台机上 `theme.novel=5`、`theme.comic=0`、`theme.audio=0` 三格都存过）
     * 仍读自己那一格，表现就是「设置里开了夜间，翻开漫画还是白的」。
     *
     * 各屏原来那档浅色先存进 [KEY_PAPER] 那一族同名的键，关夜间时逐屏还回去：统一命令不该顺手把
     * 「漫画=豆绿」抹成「漫画=纸白」。本来就读着夜间的那一屏没有浅色可存，所以已有的那份不动 ——
     * 连开两次夜间不会把 stash 冲掉；某一屏压根没存过浅色，关夜间就回纸白(0)。
     *
     * **先把七层各自的当前值读完再一次写**：这里要的是"命令之前"那一档，不能拿 apply 的时机来赌 ——
     * 测试里那份内存 prefs 是写一格落一格的，中途再读就会顺着「没存过就回落全局」读到刚被改掉的值。
     */
    fun applyGlobalNight(on: Boolean) {
        // 全局那一层按 `scope = null` 现造，不用 `this`：这一句要是从某一屏那一份上被叫起来，
        // `this` 带着范围，"全局"那一层就会写成 `theme.漫画`、还会把漫画算两遍。
        val layers = listOf(ReaderPrefs(p, null)) + ReaderScope.values().map { forReader(it) }
        val before = layers.map { it.themeIndex }
        p.edit {
            putBoolean("shellNight", on)
            if (on) {
                layers.zip(before).forEach { (layer, theme) ->
                    if (theme != NIGHT_THEME_INDEX) putInt(layer.suffixed(KEY_PAPER), theme)
                    putInt(layer.suffixed(KEY_THEME), NIGHT_THEME_INDEX)
                }
            } else {
                // `edit {}` 里读要走 `p`：editor 的写没 apply 之前，那一族的值还是**上一轮开夜间时存进磁盘的那份**，
                // 正是这里要还回去的那档浅色。
                layers.forEach { layer ->
                    putInt(layer.suffixed(KEY_THEME), p.getInt(layer.suffixed(KEY_PAPER), 0))
                    remove(layer.suffixed(KEY_PAPER))
                }
            }
        }
    }

    /**
     * 老版本没有 `shellNight` 这一格：第一次读它时拿当时那档全局纸色是不是"夜间"顶上，
     * 别一升级外壳就自己跳回白天。`6` 档里只有最后一档是夜间（`ReaderTheme.Night`），
     * 这里不 import 那一层是 :data 不该反过来依赖 :ui —— 序号写死，改动在那边编译会红。
     */
    private fun legacyShellNight(): Boolean = p.getInt(KEY_THEME, 0) == NIGHT_THEME_INDEX

    private fun suffixed(name: String): String = if (scope == null) name else "$name.${scope.tag}"

    /**
     * 全局字号：只管**外壳那一层**（他点的「各算一档」）。阅读正文仍走上面那档 [fontSizeSp]，
     * 所以这一格改完，书架/搜书/精选/书签/我的跟着变大，翻开书那一页不动。
     */
    var uiFontScale: Float
        get() = p.getFloat("uiFontScale", 1f)
        set(v) = p.edit { putFloat("uiFontScale", v.coerceIn(0.85f, 1.35f)) }

    /** 全局字体：`filesDir/theme/` 下那个字体文件的文件名；空 = 用系统字体。只存文件名不存绝对路径。 */
    var uiFontFile: String
        get() = p.getString("uiFontFile", "") ?: ""
        set(v) = p.edit { putString("uiFontFile", v) }

    /** 阅读背景图：`filesDir/theme/` 下那张图的文件名；空 = 不用图，走纯色纸。 */
    var bgImageFile: String
        get() = p.getString("bgImageFile", "") ?: ""
        set(v) = p.edit { putString("bgImageFile", v) }

    /** 背景图上压的那层纸色有多浓：0 = 原图直接铺，1 = 完全压成纸色。默认压到文字读得清。 */
    var bgDim: Float
        get() = p.getFloat("bgDim", 0.82f)
        set(v) = p.edit { putFloat("bgDim", v.coerceIn(0f, 1f)) }

    /**
     * 全屏模式：阅读那一屏在**自己的菜单全收起来**的时候，把系统状态栏和底部导航条也盖过去。
     * 上下栏一亮起来就还回去 —— 顶着状态栏画我们那条栏就是两层叠在一起。
     */
    /** 按屏：菜单全收时要不要连系统那两条栏一起盖过去。 */
    var fullScreen: Boolean
        get() = boolOf("fullScreen", false)
        set(v) = putBool("fullScreen", v)

    /** 底栏藏掉哪几格：存 `MainTab` 的名字，`;` 分隔。只藏得掉 搜书 / 精选 两颗（书架/书签/我的藏了就没出口了）。 */
    var hiddenTabs: String
        get() = p.getString("hiddenTabs", "") ?: ""
        set(v) = p.edit { putString("hiddenTabs", v) }

    /** 按屏：读着不灭屏。 */
    var keepScreenOn: Boolean
        get() = boolOf(KEY_KEEP, true)
        set(v) = putBool(KEY_KEEP, v)

    /** 按屏：0=简洁(瞬切) 1=平移。上下/仿真没做，UI 里点了会提示，不写进这里。 */
    var turnMode: Int
        get() = intOf("turnMode", 0)
        set(v) = putInt("turnMode", v)

    /** 按屏：行间距档位 0松散 1较宽 2适中 3紧凑 4密集 */
    var linePreset: Int
        get() = intOf("linePreset", 2)
        set(v) = putInt("linePreset", v.coerceIn(0, 4))

    /** 按屏：边距档位 0较窄 1适中 2较宽 */
    var marginPreset: Int
        get() = intOf("marginPreset", 1)
        set(v) = putInt("marginPreset", v.coerceIn(0, 2))

    /** 按屏：段首缩进两字。 */
    var paragraphIndent: Boolean
        get() = boolOf("indentOn", true)
        set(v) = putBool("indentOn", v)

    /** 按屏：音量键翻页（漫画 / pdf / ppt 也各有一颗）。 */
    var volumePaging: Boolean
        get() = boolOf("volumePaging", false)
        set(v) = putBool("volumePaging", v)

    /** 按屏：点左右两侧都往后翻（参考截图里那颗开关） */
    var bothSidesNext: Boolean
        get() = boolOf("bothSidesNext", false)
        set(v) = putBool("bothSidesNext", v)

    /** 按屏：护眼模式，叠一层暖色，不是系统级滤光。 */
    var eyeCare: Boolean
        get() = boolOf("eyeCare", false)
        set(v) = putBool("eyeCare", v)

    /** 按屏：亮度，-1f 表示跟随系统。它本来就是"这一屏的窗口亮度"，所以按屏存才对得齐观感。 */
    var brightness: Float
        get() = floatOf("brightness", -1f)
        set(v) = putFloat("brightness", v)

    /** 按屏：休息提醒分钟数，0=不提醒。 */
    var restMinutes: Int
        get() = intOf("restMinutes", 0)
        set(v) = putInt("restMinutes", v)

    /** 按屏：繁→简。只对繁体正文有效果，简体书开了也一个字不改（ICU 是恒等变换）。 */
    var convertTraditional: Boolean
        get() = boolOf("t2s", false)
        set(v) = putBool("t2s", v)

    /** 按屏：自动阅读。 */
    var autoRead: Boolean
        get() = boolOf("autoRead", false)
        set(v) = putBool("autoRead", v)

    /** 按屏：自动阅读速度档，单位是"行/分钟" —— 上下和按页两种模式共用这一个口径 */
    var autoSpeedPreset: Int
        get() = intOf("autoSpeed", 1)
        set(v) = putInt("autoSpeed", v.coerceIn(0, AUTO_LINES_PER_MIN.lastIndex))

    val autoLinesPerMinute: Int get() = AUTO_LINES_PER_MIN[autoSpeedPreset]

    /**
     * 最近搜索：只存这台机上，最多 8 条。';' 是存储里的分隔符，所以从词条里剔掉，
     * 否则搜一次 "a;b" 会凭空多出两条历史。
     */
    var recentSearches: List<String>
        get() = (p.getString("recentSearch", "") ?: "").split(';').filter { it.isNotBlank() }
        set(v) = p.edit {
            putString(
                "recentSearch",
                v.map { it.replace(";", "").trim() }.filter { it.isNotEmpty() }.distinct().take(8).joinToString(";"),
            )
        }

    /** 按屏：正文字体。null = 系统默认。 */
    var fontPath: String?
        get() = stringOf("fontPath", null)
        set(v) = putString("fontPath", v)

    /** 昵称：只存在这台机上，没有账号体系。 */
    var nickname: String
        get() = p.getString("nickname", "未命名用户") ?: "未命名用户"
        set(v) = p.edit { putString("nickname", v.take(12)) }

    var fontLabel: String
        get() = stringOf("fontLabel", "系统默认") ?: "系统默认"
        set(v) = putString("fontLabel", v)

    /** 书架排布：false = 图墙（三列封面），true = 列表（一行一本）。默认图墙，和改之前一致。 */
    var shelfList: Boolean
        get() = p.getBoolean("shelfList", false)
        set(v) = p.edit { putBoolean("shelfList", v) }

    /**
     * AI 服务三项。地址和模型名不是秘密（它们只说"往哪发"），直接放 prefs；
     * 秘钥只存 [SecretStore] 封过的密文，读的时候才在本机解开。
     */
    var aiBaseUrl: String
        get() = p.getString("aiBaseUrl", "") ?: ""
        set(v) = p.edit { putString("aiBaseUrl", v.trim()) }

    var aiModel: String
        get() = p.getString("aiModel", "") ?: ""
        set(v) = p.edit { putString("aiModel", v.trim()) }

    /** Base64 的 AES/GCM 密文。解不开（换机、恢复备份、被改过）就当没有，界面会要求重填。 */
    var aiKeyCipher: String
        get() = p.getString("aiKeyCipher", "") ?: ""
        set(v) = p.edit { putString("aiKeyCipher", v) }

    /** 词典这一档整体开关：关掉之后长按那颗「词典」回到原来那句实话，不发请求 */
    var aiDict: Boolean
        get() = p.getBoolean("aiDict", true)
        set(v) = p.edit { putBoolean("aiDict", v) }

    /**
     * 语音合成（听书）这一档和词典**分开填**：它们通常不是一家（DeepSeek 没有语音口），
     * 而且秘钥也不同。四项齐全才走 AI，缺任何一项就退回系统语音引擎。
     * `aiVoiceKind` 存的是 `AiSpeech.Kind` 的名字，读的时候兜底到 OpenAi（老版本没这个键）。
     */
    var aiVoiceKind: String
        get() = p.getString("aiVoiceKind", "OpenAi") ?: "OpenAi"
        set(v) = p.edit { putString("aiVoiceKind", v) }

    var aiVoiceBaseUrl: String
        get() = p.getString("aiVoiceBaseUrl", "") ?: ""
        set(v) = p.edit { putString("aiVoiceBaseUrl", v.trim()) }

    var aiVoiceModel: String
        get() = p.getString("aiVoiceModel", "") ?: ""
        set(v) = p.edit { putString("aiVoiceModel", v.trim()) }

    /** 音色：OpenAI 那档是 alloy / nova…，MiniMax 那档是 voice_id */
    var aiVoiceId: String
        get() = p.getString("aiVoiceId", "") ?: ""
        set(v) = p.edit { putString("aiVoiceId", v.trim()) }

    var aiVoiceKeyCipher: String
        get() = p.getString("aiVoiceKeyCipher", "") ?: ""
        set(v) = p.edit { putString("aiVoiceKeyCipher", v) }

    /**
     * 他自己在设置页填的"这个模型能用的音色"那一列，逗号 / 空格 / 换行分隔，**按服务商各存一份**：
     * OpenAI 的 `alloy` 和阿里的 `longanhuan_v3.1` 不通用，混在一列里选了必然 400。
     * 音色名各家没有能查的列表接口，所以这一列只能人抄一遍；听书页「更多」里能挑的就是它。
     */
    fun aiVoiceChoices(kind: String): String = p.getString("aiVoiceChoices.$kind", "") ?: ""

    fun setAiVoiceChoices(kind: String, v: String) {
        p.edit { putString("aiVoiceChoices.$kind", v.trim()) }
    }

    /**
     * 听书纠错那张白名单，每行一条 `银行=银航`。发语音接口之前替换，**屏幕上的正文不变**。
     * 这三家接口只吃纯文本（没有 SSML / 音素口），所以"指定读音"只有换成单音字这一条路走得通。
     */
    var aiFixes: String
        get() = p.getString("aiFixes", "") ?: ""
        set(v) = p.edit { putString("aiFixes", v) }

    /**
     * 连续播放：念完这一段自己接下一段。默认关：合成按字符计费，开着就是连着扣。
     * **到本章最后一句就停** —— 跨不跨章是下面那颗「自动下一章」的事。
     */
    var listenAutoNext: Boolean
        get() = p.getBoolean("listenAutoNext", false)
        set(v) = p.edit { putBoolean("listenAutoNext", v) }

    /** 本章念完接着念下一章（正文跟着翻过去）。只在「连续播放」开着时点得动。 */
    var listenAutoNextChapter: Boolean
        get() = p.getBoolean("listenAutoNextChapter", false)
        set(v) = p.edit { putBoolean("listenAutoNextChapter", v) }

    /** 不抢音频焦点：别的音乐/播客继续响，两边混着放。 */
    var listenShareFocus: Boolean
        get() = p.getBoolean("listenShareFocus", false)
        set(v) = p.edit { putBoolean("listenShareFocus", v) }

    /**
     * 听书是否走 AI：四项齐全才算。缺任何一项就退回系统语音引擎 ——
     * 半配的状态（有地址没音色）发出去必然 400，不如不发，让界面能说清缺哪一项。
     */
    val aiVoiceReady: Boolean
        get() = aiVoiceBaseUrl.isNotBlank() && aiVoiceModel.isNotBlank() && aiVoiceId.isNotBlank() &&
            aiVoiceKeyCipher.isNotBlank()

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
        p.edit { putString("pos.$bookId", "${pos.chapterIndex}|${pos.absoluteOffset}") }
    }

    fun loadPosition(bookId: String): BookPosition? {
        val raw = p.getString("pos.$bookId", null) ?: return null
        val parts = raw.split('|')
        if (parts.size < 2) return null
        return BookPosition(parts[0].toIntOrNull() ?: 0, parts[1].toLongOrNull() ?: 0L, 0, 0)
    }

    /**
     * 整页渲染那一类（pdf / pptx）读到第几页（0 基）。
     * 不复用 [savePosition]：那个的权威字段是 absoluteOffset，对不排版的原件没有意义 ——
     * 一本没有字符偏移的书，位置只能是一页一页的号。
     */
    fun saveWholePage(bookId: String, page: Int) {
        p.edit { putInt("page.$bookId", page.coerceAtLeast(0)) }
    }

    fun loadWholePage(bookId: String): Int = p.getInt("page.$bookId", 0)

    /**
     * 一本漫画读到哪了：`章号|页号`，两个都是 0 基。
     *
     * 不复用 [saveWholePage] 那个单页号：漫画的位置是**二维**的（第 37 话第 4 页），
     * 把章和页压成一个数就得在每一处读它的地方按"这一章有几页"再除一次，而章的页数只有磁盘上知道。
     */
    fun saveComicPos(bookId: String, chapter: Int, page: Int) {
        p.edit { putString("comic.$bookId", chapter.coerceAtLeast(0).toString() + "|" + page.coerceAtLeast(0)) }
    }

    /** 没存过 = 从第一话第一页开始。串写歪了也当没存过，别把一个人扔进一个不存在的页号。 */
    fun loadComicPos(bookId: String): Pair<Int, Int> {
        val c = (p.getString("comic.$bookId", "") ?: "").split('|')
        return (c.getOrNull(0)?.toIntOrNull() ?: 0) to (c.getOrNull(1)?.toIntOrNull() ?: 0)
    }

    /** 漫画的进度口径：读的是「第几话」，不是字数 —— 它没有字符偏移，硬算字数就是一句假话。 */
    fun comicProgress(bookId: String): String {
        val (c, _) = loadComicPos(bookId)
        return "第 ${c + 1} 话"
    }

    /**
     * 一本有声书听到哪了：`集号|毫秒`。
     *
     * 毫秒进这一格和漫画的页号同一份理由：位置是**二维**的（第 12 集第 41 分 07 秒），
     * 集内的毫秒只有磁盘上那份音频自己知道，压成一个数就得在每一处读它的地方再除一次。
     */
    fun saveAudioPos(bookId: String, episode: Int, ms: Int) {
        p.edit { putString("audio.$bookId", episode.coerceAtLeast(0).toString() + "|" + ms.coerceAtLeast(0)) }
    }

    /** 没存过 = 从第一集 0 秒开始。串写歪了也当没存过，别把一个人扔进一个不存在的集号。 */
    fun loadAudioPos(bookId: String): Pair<Int, Int> {
        val c = (p.getString("audio.$bookId", "") ?: "").split('|')
        return (c.getOrNull(0)?.toIntOrNull() ?: 0) to (c.getOrNull(1)?.toIntOrNull() ?: 0)
    }

    /** 听书的语速，全本共用一格（换集、换本都不重置）：0.5~2.0。 */
    var audioRate: Float
        get() = p.getFloat("audioRate", 1.0f)
        set(v) = p.edit { putFloat("audioRate", v) }

    /**
     * 一本漫画的书签：一串「章号|页号」，两个都是 0 基（和 [saveComicPos] 同一口径，
     * 存 1 基的话每一处读它的地方要各自再加一次 1 —— 页码书签那一族已经栽过一次）。
     */
    fun comicBookmarks(bookId: String): List<Pair<Int, Int>> =
        (p.getString("comicMarks.$bookId", "") ?: "").split(';')
            .mapNotNull { raw ->
                val c = raw.split('|')
                val i = c.getOrNull(0)?.toIntOrNull() ?: return@mapNotNull null
                i to (c.getOrNull(1)?.toIntOrNull() ?: 0)
            }.distinct()

    /** 记当前这一页。同一页按两次不出两行（`distinct`）—— 「插入书签」按快了是常事。 */
    fun addComicBookmark(bookId: String, chapter: Int, page: Int) {
        val list = (comicBookmarks(bookId) + (chapter to page)).distinct().sortedWith(compareBy({ it.first }, { it.second }))
        p.edit { putString("comicMarks.$bookId", list.joinToString(";") { "${it.first}|${it.second}" }) }
    }

    fun removeComicBookmark(bookId: String, chapter: Int, page: Int) {
        val list = comicBookmarks(bookId) - (chapter to page)
        p.edit { if (list.isEmpty()) remove("comicMarks.$bookId") else putString("comicMarks.$bookId", list.joinToString(";") { "${it.first}|${it.second}" }) }
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
        p.edit { putString("marks.$bookId", list.joinToString(";") { "${it.chapterIndex}|${it.absoluteOffset}" }) }
    }

    fun removeBookmark(bookId: String, pos: BookPosition) {
        val list = bookmarks(bookId).filter { it.absoluteOffset != pos.absoluteOffset }
        p.edit { putString("marks.$bookId", list.joinToString(";") { "${it.chapterIndex}|${it.absoluteOffset}" }) }
    }

    /**
     * 整页那一族（pdf / pptx）的书签：**只有页码**。
     *
     * 不复用 [bookmarks]：那一串存的是"第几章 + 第几个字"，而 pdf/pptx 走的是原版面渲染，
     * 既没有章节也没有字符偏移 —— 把页码硬塞进 offset 字段，书签页上就会写出"第 0 章 第 5 字"这种假话。
     * 存的也是 1 基的页号：它就是要显示出来的那个数，别再在每一处读它的地方各加一次 1。
     */
    fun pageBookmarks(bookId: String): List<Int> =
        (p.getString("pageMarks.$bookId", "") ?: "").split(';')
            .mapNotNull { it.trim().toIntOrNull() }.filter { it > 0 }.distinct().sorted()

    fun addPageBookmark(bookId: String, page: Int) {
        val on = page + 1
        // distinct：同一页按两次「插入书签」不该在列表里出现两行"第 5 页"
        val list = (pageBookmarks(bookId) + on).distinct().sorted()
        p.edit { putString("pageMarks.$bookId", list.joinToString(";")) }
    }

    /**
     * 删一条页码书签。参数和 [addPageBookmark] 一样是**查看器那一头的 0 基页号**（界面传进来的都是它），
     * 存的才是 1 基：两边口径不一致就是"按了 ✕ 列表一动不动"——上一版减的是 0 基那个数，
     * 而列表里全是 1 基，一条都减不掉。
     */
    fun removePageBookmark(bookId: String, page: Int) {
        val list = pageBookmarks(bookId) - (page + 1)
        p.edit { if (list.isEmpty()) remove("pageMarks.$bookId") else putString("pageMarks.$bookId", list.joinToString(";")) }
    }

    /**
     * 从书架上移除：只是这一本不再出现在列表里，本机那份正文 / 索引 / 书签 / 阅读位置一个字都不动 ——
     * 再导入同一个文件会重新出现（那是另一条新记录，旧记录还在这里，两边都不丢东西）。
     */
    fun isRemoved(bookId: String): Boolean = p.getBoolean("removed.$bookId", false)

    fun setRemoved(bookId: String, on: Boolean) {
        // 复活时把键摘掉而不是写个 false：架上每本都留一条 false 就是没人读的噪声
        p.edit { if (on) putBoolean("removed.$bookId", true) else remove("removed.$bookId") }
    }

    /** 置顶：书架两种排法都排在前头（排序在界面那一层做，这里只存开关）。 */
    fun isPinned(bookId: String): Boolean = p.getBoolean("pin.$bookId", false)

    fun setPinned(bookId: String, on: Boolean) {
        p.edit { putBoolean("pin.$bookId", on) }
    }

    /**
     * 真删一本书时把它那串"按书存"的键一起摘掉：`pos. / marks. / page. / auto. / zoom. / fontscale. /
     * pdfPage. / removed. / pin. / voiceCh.` 都是 `<前缀>.<bookId>`，留着就是永远读不到的死字节
     * （书架按目录列，这些键再没有任何一处会遍历）。
     */
    fun clearBook(bookId: String) {
        val suffix = ".$bookId"
        val doomed = p.all.keys.filter { it.endsWith(suffix) }
        if (doomed.isEmpty()) return
        p.edit { doomed.forEach { remove(it) } }
    }

    /**
     * 已经**整章**缓存过的章号（写的是全集，不是增量）。
     *
     * **为什么要存盘**：这一份原来只是阅读页里的一个 `remember`，退出这本书再进来就空了 ——
     * 音频明明还在本机，目录页右边却又变回「缓存」（他报的这条）。
     *
     * 值前面带**语音签名**（服务商 + 模型 + 音色）：换掉任何一项，之前存的那些音频就不是这一档的了
     * （缓存键里本来就有这三样），整套标记当场作废，而不是继续写着「已缓存」骗人。
     *
     * 一个已知的偏乐观：音频落在 `cacheDir` 里，系统"清除缓存"能整片清掉，这个标记不会跟着掉。
     * 偏乐观只让他少点一次预缓存 —— 真去听的那一段缺文件时照样会当场合成，不多掏一次钱；
     * 反过来（标记掉了、文件还在）才是会让他把同一章缓存两遍的那一侧，所以那一侧修死了。
     */
    fun cachedChapters(bookId: String): Set<Int> {
        val raw = p.getString("voiceCh.$bookId", "") ?: ""
        val at = raw.indexOf(':')
        if (at < 0 || raw.substring(0, at) != voiceSignature()) return emptySet()
        return raw.substring(at + 1).split(',').mapNotNull { it.toIntOrNull() }.toSet()
    }

    fun setCachedChapters(bookId: String, indices: Set<Int>) {
        p.edit {
            if (indices.isEmpty()) remove("voiceCh.$bookId")
            else putString("voiceCh.$bookId", "${voiceSignature()}:${indices.sorted().joinToString(",")}")
        }
    }

    private fun voiceSignature() = "$aiVoiceKind/$aiVoiceModel/$aiVoiceId"

    /**
     * 听书页目录里那个「缓存范围」：0 = 不自动（默认 —— 合成按字符计费，不该有任何东西在你没按过的情况下掏钱），
     * 5 / 15 / 50 = 从正在听这一章之后数几章，-1 = 全部章节。
     */
    var listenCacheScope: Int
        get() = p.getInt("listenCacheScope", 0)
        set(v) {
            p.edit { putInt("listenCacheScope", v) }
        }

    /**
     * 自动缓存那颗开关。**默认关**：开着它 = 每换一章就自己往后发一批合成请求，那是会一直扣钱的东西，
     * 不该是任何人没点过的默认态。
     */
    var listenCacheAuto: Boolean
        get() = p.getBoolean("listenCacheAuto", false)
        set(v) {
            p.edit { putBoolean("listenCacheAuto", v) }
        }

    companion object {
        const val KEY_FONT = "fontSizeSp"
        const val KEY_THEME = "theme"
        const val KEY_KEEP = "keepScreenOn"

        /** 「开全局夜间之前那一档浅色」存在这一族键里（同样按屏分格），关夜间时逐屏还回去。 */
        const val KEY_PAPER = "paper"

        /** `ReaderTheme.ALL` 里「夜间」那一档的序号。:data 不反向依赖 :ui，所以这里写死一个数。 */
        const val NIGHT_THEME_INDEX = 5

        /**
         * 「这一屏明确没有值」的哨兵。字体文件名那一格要能表达三件事：没选过（跟全局）、
         * 选了某个文件、**明确改回系统字体** —— 前两件靠键在不在，第三件没哨兵就写不出来。
         * 真文件名不可能长成这样（都是 `xxx.ttf` / `xxx.otf` 这种带后缀的）。
         */
        private const val NULL_MARK = "__none__"

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
