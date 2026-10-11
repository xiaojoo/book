package com.bookreader.data

import android.content.Context
import androidx.core.content.edit
import com.bookreader.core.model.BookPosition
import com.bookreader.core.model.PageStyle

/**
 * 阅读偏好和阅读位置。位置只存 absoluteOffset（页码是派生值，存了就会在换字号后说谎）。
 */
class ReaderPrefs(context: Context) {

    private val p = context.getSharedPreferences("reader", Context.MODE_PRIVATE)

    var fontSizeSp: Float
        get() = p.getFloat(KEY_FONT, 17f)
        set(v) = p.edit { putFloat(KEY_FONT, v.coerceIn(12f, 34f)) }

    /** 下面四个都是"档位派生出来的值"：面板上按的是档，不是连续滑块。 */
    val lineHeightMultiplier: Float get() = LINE_PRESETS[linePreset]
    val marginHorizontalDp: Float get() = MARGIN_PRESETS[marginPreset]
    val paragraphIndentEm: Float get() = if (paragraphIndent) 2f else 0f

    var themeIndex: Int
        get() = p.getInt(KEY_THEME, 0)
        set(v) = p.edit { putInt(KEY_THEME, v) }

    var keepScreenOn: Boolean
        get() = p.getBoolean(KEY_KEEP, true)
        set(v) = p.edit { putBoolean(KEY_KEEP, v) }

    /** 0=简洁(瞬切) 1=平移。上下/仿真没做，UI 里点了会提示，不写进这里。 */
    var turnMode: Int
        get() = p.getInt("turnMode", 0)
        set(v) = p.edit { putInt("turnMode", v) }

    /** 行间距档位：0松散 1较宽 2适中 3紧凑 4密集 */
    var linePreset: Int
        get() = p.getInt("linePreset", 2)
        set(v) = p.edit { putInt("linePreset", v.coerceIn(0, 4)) }

    /** 边距档位：0较窄 1适中 2较宽 */
    var marginPreset: Int
        get() = p.getInt("marginPreset", 1)
        set(v) = p.edit { putInt("marginPreset", v.coerceIn(0, 2)) }

    var paragraphIndent: Boolean
        get() = p.getBoolean("indentOn", true)
        set(v) = p.edit { putBoolean("indentOn", v) }

    var volumePaging: Boolean
        get() = p.getBoolean("volumePaging", false)
        set(v) = p.edit { putBoolean("volumePaging", v) }

    /** 点左右两侧都往后翻（参考截图里那颗开关） */
    var bothSidesNext: Boolean
        get() = p.getBoolean("bothSidesNext", false)
        set(v) = p.edit { putBoolean("bothSidesNext", v) }

    /** 护眼模式：叠一层暖色，不是系统级滤光 */
    var eyeCare: Boolean
        get() = p.getBoolean("eyeCare", false)
        set(v) = p.edit { putBoolean("eyeCare", v) }

    /** 亮度：-1f 表示跟随系统 */
    var brightness: Float
        get() = p.getFloat("brightness", -1f)
        set(v) = p.edit { putFloat("brightness", v) }

    /** 休息提醒分钟数，0=不提醒 */
    var restMinutes: Int
        get() = p.getInt("restMinutes", 0)
        set(v) = p.edit { putInt("restMinutes", v) }

    /** 繁→简：只对繁体正文有效果，简体书开了也一个字不改（ICU 是恒等变换） */
    var convertTraditional: Boolean
        get() = p.getBoolean("t2s", false)
        set(v) = p.edit { putBoolean("t2s", v) }

    /** 自动阅读 */
    var autoRead: Boolean
        get() = p.getBoolean("autoRead", false)
        set(v) = p.edit { putBoolean("autoRead", v) }

    /** 自动阅读速度档，单位是"行/分钟" —— 上下和按页两种模式共用这一个口径 */
    var autoSpeedPreset: Int
        get() = p.getInt("autoSpeed", 1)
        set(v) = p.edit { putInt("autoSpeed", v.coerceIn(0, AUTO_LINES_PER_MIN.lastIndex)) }

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

    /** 正文字体：null = 系统默认 */
    var fontPath: String?
        get() = p.getString("fontPath", null)
        set(v) = p.edit { putString("fontPath", v) }

    /** 昵称：只存在这台机上，没有账号体系。 */
    var nickname: String
        get() = p.getString("nickname", "2015XIAO") ?: "2015XIAO"
        set(v) = p.edit { putString("nickname", v.take(12)) }

    var fontLabel: String
        get() = p.getString("fontLabel", "系统默认") ?: "系统默认"
        set(v) = p.edit { putString("fontLabel", v) }

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
