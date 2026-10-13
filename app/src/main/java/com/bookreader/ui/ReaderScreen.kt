package com.bookreader.ui

import android.app.Activity
import android.util.Log
import android.media.AudioManager
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.Image
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.Icon
import androidx.compose.ui.res.painterResource
import androidx.compose.foundation.layout.size
import androidx.compose.ui.text.style.TextOverflow
import java.io.File
import com.bookreader.R
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.drawBehind
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.foundation.ScrollState
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.bookreader.core.model.BookMeta
import com.bookreader.core.ai.AiProtocol
import com.bookreader.core.ai.AiSpeech
import com.bookreader.core.engine.PrefetchManager
import com.bookreader.core.engine.PageTurn
import com.bookreader.core.engine.ReaderEngine
import com.bookreader.core.engine.ReaderGestures
import com.bookreader.core.engine.TapZone
import com.bookreader.data.AiClient
import com.bookreader.data.AiError
import com.bookreader.data.AiVoiceCache
import com.bookreader.data.AiVoiceClient
import com.bookreader.data.AiVoicePlayer
import com.bookreader.data.SecretStore
import com.bookreader.core.model.BookPosition
import com.bookreader.core.model.Page
import com.bookreader.core.model.PageStyle
import com.bookreader.core.search.ChunkScanner
import com.bookreader.core.search.ScanResult
import com.bookreader.data.BookLibrary
import com.bookreader.data.DeviceFonts
import com.bookreader.data.ReaderPrefs
import com.bookreader.data.TraditionalText
import com.bookreader.reader.PaintBreaker
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

private enum class Panel { None, Chapters, Search, Marks, Dict, Quick, More, Fonts }

internal const val TAG_PERF = "ReaderPerf"

/** 顶栏和底栏各自占多高。正文区永远按这两个数预留，菜单开合就不动排版。 */
const val TOP_BAND_DP = 44f
const val BOTTOM_BAND_DP = 58f

@Composable
fun ReaderScreen(
    bookId: String,
    library: BookLibrary,
    prefs: ReaderPrefs,
    /** >= 0 就是从目录那一行进来：位置让给指定的那一章。阅读进度照旧存着，下次从书架进还在原地。 */
    startChapter: Int = -1,
    onBack: () -> Unit,
) {
    val density = LocalDensity.current
    // 正文只认**系统**的 fontScale：「我的 → 字体管理」那档全局字号乘在 TipHost 的 density 上，
    // 读 LocalDensity 就会把外壳那一档也吃进来 —— 他要的是两档各算各的
    val sysFontScale = LocalConfiguration.current.fontScale
    val view = LocalView.current
    val activity = view.context as? Activity

    var theme by remember { mutableStateOf(ReaderTheme.of(prefs.themeIndex)) }
    var fontSizeSp by remember { mutableFloatStateOf(prefs.fontSizeSp) }
    var linePreset by remember { mutableIntStateOf(prefs.linePreset) }
    var marginPreset by remember { mutableIntStateOf(prefs.marginPreset) }
    var indent by remember { mutableStateOf(prefs.paragraphIndent) }
    var keepOn by remember { mutableStateOf(prefs.keepScreenOn) }
    var volumePaging by remember { mutableStateOf(prefs.volumePaging) }
    var bothSidesNext by remember { mutableStateOf(prefs.bothSidesNext) }
    var eyeCare by remember { mutableStateOf(prefs.eyeCare) }
    var restMinutes by remember { mutableIntStateOf(prefs.restMinutes) }
    var autoRead by remember { mutableStateOf(prefs.autoRead) }
    var autoSpeedPreset by remember { mutableIntStateOf(prefs.autoSpeedPreset) }
    var traditional by remember { mutableStateOf(prefs.convertTraditional) }
    var brightness by remember { mutableFloatStateOf(prefs.brightness) }
    var turnMode by remember { mutableStateOf(TurnMode.values()[prefs.turnMode]) }
    var panel by remember { mutableStateOf(Panel.None) }
    /** 从目录/书签/搜索跳章时要让滚动缓冲重铺；单纯翻页不算跳。 */
    var jumpToken by remember { mutableIntStateOf(0) }
    var bars by remember { mutableStateOf(true) }
    var viewport by remember { mutableStateOf(IntSize.Zero) }
    var panelNote by remember { mutableStateOf<String?>(null) }
    /** 长按选中的那一段（绝对偏移的 [start, end)）；null = 没选。 */
    var selection by remember { mutableStateOf<LongRange?>(null) }
    /** 选中段落在窗口坐标里的上下边：动作栏要贴在段落上面，得知道它画到哪儿。 */
    var selectionRect by remember { mutableStateOf<Pair<Float, Float>?>(null) }
    /** 动作栏下面那一行回话：哪颗没接后端，这里说清楚，不留哑按钮。 */
    var selectionNote by remember { mutableStateOf<String?>(null) }
    /** 词典面板的三样：查的那段、AI 回来的三行、还在不在路上。只在 `Panel.Dict` 开着时有意义。 */
    var dictQuery by remember { mutableStateOf("") }
    var dictAnswer by remember { mutableStateOf<String?>(null) }
    var dictBusy by remember { mutableStateOf(false) }
    /**
     * 听书的三样：正在念的那一段（**它才是选中高亮的主人** —— 动作栏收起来之后高亮要一直留到念完）、
     * 是不是在放（暂停键要跟着它换图标）、浮窗的位置（像素，可拖）。
     */
    var listening by remember { mutableStateOf<LongRange?>(null) }
    var aiPlaying by remember { mutableStateOf(false) }
    /** 合成在路上的那几秒：浮窗先不出现。它一出现就该能按，出现一颗按了不动的"继续"看起来是坏了。 */
    var listenBusy by remember { mutableStateOf(false) }
    /** 点浮窗上的封面展开的那一整屏（听书页）。收起它不影响播放。 */
    var listenPage by remember { mutableStateOf(false) }
    /**
     * 语速。存在这一层而不是听书页里：收起那一屏时组合会被销毁，而播放器手里的速率还在 ——
     * 存在这里才不会"界面写 1.0x、耳朵听着 1.5x"。
     */
    var listenRate by remember { mutableFloatStateOf(1.0f) }
    // 音色当前值：prefs 是 SharedPreferences，不是可观察状态，写完不重算的话界面上那颗勾还停在旧名字上
    var voiceNow by remember { mutableStateOf(prefs.aiVoiceId) }
    // 「更多」里那两颗开关：自动接下一段默认关（每多一段就多一次合成费），混播默认关（照旧抢焦点）
    var autoNext by remember { mutableStateOf(prefs.listenAutoNext) }
    /** 连续播放念到本章最后一句之后，要不要接着翻到下一章。默认关 —— 开着它是"整本连着扣"。 */
    var autoNextChapter by remember { mutableStateOf(prefs.listenAutoNextChapter) }
    var shareFocus by remember { mutableStateOf(prefs.listenShareFocus) }
    var historyPage by remember { mutableStateOf(false) }
    var fixesPage by remember { mutableStateOf(false) }
    var voiceHistory by remember { mutableStateOf<List<AiVoiceCache.Entry>>(emptyList()) }
    // 目录那一页：整本的章 + 每章右边那颗「缓存」；范围那一档只是选范围，点「立即缓存」才发请求
    var tocPage by remember { mutableStateOf(false) }
    var tocScope by remember { mutableIntStateOf(prefs.listenCacheScope) }
    var tocAuto by remember { mutableStateOf(prefs.listenCacheAuto) }
    // 哪些章整章存过音频。**存盘**（`prefs.cachedChapters`）：原来这是一个纯内存的 remember，
    // 退出这本书再进来就全空了 —— 音频明明还在本机，目录右边却又变回「缓存」（他报的这条）。
    // 键上有 `voiceNow`：换了音色，之前那批音频就不是这一档的了（缓存键里就有音色），标记要跟着重读
    var tocCached by remember(bookId, voiceNow) { mutableStateOf(prefs.cachedChapters(bookId)) }
    val voiceBatch = remember { VoiceBatch() }
    var floatPos by remember { mutableStateOf(Offset.Zero) }
    /** 动作栏单独一个开关：以前它和"有没有选中"绑死，于是"留着高亮、收起按钮"做不到。 */
    var barUp by remember { mutableStateOf(false) }
    val context = LocalContext.current
    val voice = remember { ParagraphVoice(context) }
    DisposableEffect(voice) { onDispose { voice.close() } }
    // AI 朗读（配了语音那一档才有意义）和系统 TTS 是两个主人，谁开口另一个就得停
    val aiVoice = remember {
        AiVoicePlayer().apply {
            // 混播那颗开关要的就是这个服务；不塞进去它会静默什么都不做
            audioManager = context.getSystemService(AudioManager::class.java)
        }
    }
    DisposableEffect(aiVoice) { onDispose { aiVoice.close() } }
    val selectionColor = theme.inkColor.copy(alpha = 0.18f).toArgb()
    val markColor = theme.accentColor.copy(alpha = 0.9f).toArgb()
    var restPrompt by remember { mutableStateOf(false) }
    val fontState = remember { mutableStateOf(prefs.fontPath) }
    var fontLabel by remember { mutableStateOf(prefs.fontLabel) }
    val typeface = remember(fontState.value) { DeviceFonts.typefaceOf(fontState.value) }

    // 种子要在 remember 外面取：@Composable 不能在 remember 的算子里调
    val seed = contentSizePx()
    val engine = remember(bookId, startChapter) {
        val start = prefs.pageStyle(
            widthPx = seed.width.toFloat(),
            heightPx = seed.height.toFloat(),
            pxPerDp = density.density,
            fontScale = sysFontScale,
        )
        ReaderEngine(library.open(bookId), { PaintBreaker(it, DeviceFonts.typefaceOf(fontState.value) ?: android.graphics.Typeface.DEFAULT) }, start)
            .also { e ->
                prefs.loadPosition(bookId)?.let { p -> e.restore(p) }
                // 先还原进度、再让位给指定的那一章：顺序反了会被 restore 盖回原地
                if (startChapter >= 0) e.jumpToChapter(startChapter)
            }
    }
    DisposableEffect(bookId) { onDispose { engine.close() } }

    /** 加过书签的段落下面要画红色波浪线，所以书签列表得是状态：加一条就重算。 */
    var marks by remember { mutableStateOf(prefs.bookmarks(bookId)) }
    val markRanges = remember(marks) { marks.map { engine.paragraphRange(it.absoluteOffset) } }

    val prefetch = remember { PrefetchManager(6) }
    DisposableEffect(Unit) { onDispose { prefetch.shutdown() } }

    var page by remember { mutableStateOf<Page?>(null) }

    LaunchedEffect(bookId) {
        val t0 = System.nanoTime()
        // 续读的位置在上面建引擎那一刻就恢复了，不在这里：滚动那一档的文档窗口是首轮组合就按引擎
        // 游标建的（ScrollReader 的 anchor），等这个 effect 再恢复已经晚了 —— 画面停在第一章开头，
        // 窗口自己提交的首行偏移还会把恢复出来的位置盖掉。
        page = engine.currentPage()
        Log.d(
            TAG_PERF,
            "open book=$bookId chars=${engine.totalChars} chapters=${engine.chapterCount} " +
                "restoreCh=${engine.currentChapterIndex} restoreOffset=${page?.startOffset} " +
                "ms=${(System.nanoTime() - t0) / 1_000_000} lines=${page?.lines?.size}",
        )
    }

    // 系统栏、常亮、亮度都归这管；亮度 -1 是"跟随系统"，要把窗口属性还原成默认
    SideEffect {
        view.keepScreenOn = keepOn
        val window = activity?.window ?: return@SideEffect
        window.attributes = window.attributes.apply {
            screenBrightness = if (brightness < 0f) -1f else brightness.coerceIn(0.02f, 1f)
        }
    }

    // 这三档一定要从本地状态读、不能读 prefs：Compose 只在本作用域读过的状态变化时才重算这个式子，
    // 而 prefs 不是状态。读 prefs 的话改了档位这里不动，就得退回书架重开才生效。
    val marginDp = ReaderPrefs.MARGIN_PRESETS[marginPreset]
    val seedSize = contentSizePx()
    val style = prefs.pageStyle(
        widthPx = if (viewport.width > 0) viewport.width.toFloat() else seedSize.width.toFloat(),
        heightPx = if (viewport.height > 0) viewport.height.toFloat() else seedSize.height.toFloat(),
        pxPerDp = density.density,
        fontScale = sysFontScale,
        linePreset = linePreset,
        marginPreset = marginPreset,
        paragraphIndent = indent,
    ).copy(
        fontSizePx = fontSizeSp * density.density * sysFontScale,
        fontTag = fontState.value ?: "sys",
    )

    // 上下两条缝 = 缝 + "装满一页"剩下的零头对半。四档翻页模式共用这一个矩形，短页多出来的空白留在底下
    //（书本来就是这样）。零头必须按装满一页算、不能按当前页算：一章最后一页只剩十几行时零头会顶到上限
    // 被夹成一行高，顶缝从 29px 跳到 68px，翻到章尾会看到整页往下掉一截。
    val lineHeightPx = style.lineHeightPx
    val fullPageLines = (style.contentHeightPx / lineHeightPx).toInt()
    val slackPx = ((style.contentHeightPx - fullPageLines * lineHeightPx) / 2f)
        .coerceIn(0f, lineHeightPx)
    val pagePadPx = style.topInsetPx + slackPx
    // 按页和滚动用同一个矩形：带子边缘 = 按页那一档行盒的顶边。之前滚动这边额外加了一个行盒上伸部
    //（8.5px），整块文字比按页低一截 —— 他两张截图量出来第一行墨迹 121 vs 115，切换时就是"内容动了一下"。
    val bandTopPx = pagePadPx
    val bandBottomPx = style.bottomInsetPx + slackPx
    val topSlackDp = with(density) { pagePadPx.toDp() }

    // 按页那一档翻页时两层是整屏平移/绕书脊转的，不裁切的话正文一路画到屏幕两边
    // （录屏帧量过：平移到中途整屏墨迹横跨 x=0..1079，而内容区只有 60.5..1019.5）。
    // 上下那一档本来就被"文字带"的两条边缘裁着，所以这里用同一个矩形：
    // 左右 = 版心边距，上下 = 带子边缘 —— 两档共用一条线，切换时排版不挪。
    val contentClip = Modifier.drawWithContent {
        val mx = marginDp * density.density
        clipRect(mx, bandTopPx, size.width - mx, size.height - bandBottomPx) {
            this@drawWithContent.drawContent()
        }
    }

    LaunchedEffect(style.id, viewport) {
        if (viewport.width == 0 || viewport.height == 0) return@LaunchedEffect
        if (engine.style.id != style.id) engine.applyStyle(style)
        page = engine.currentPage()
    }

    val pageText = remember(page, traditional) {
        val p = page
        if (p == null) "" else {
            val raw = engine.book.cache.slice(p.startOffset, (p.endOffset - p.startOffset).toInt())
            if (traditional) TraditionalText.toSimplified(raw) else raw
        }
    }

    val turnAnim = remember { Animatable(1f) }
    // 进"上下"那一档的那一刻就把滚动量落到锚行上。留给 ScrollReader 的 effect 去 scrollTo 的话，
    // 第一帧画的是锚页的页头，effect 再往下挪一截 —— 他报的"切换时界面会刷新一遍"就是这个。
    val scrollState = remember(turnMode) {
        ScrollState(
            if (turnMode == TurnMode.Scroll) {
                page?.lines?.let { headLines(it, engine.cursor) }?.let { (it * lineHeightPx).roundToInt() } ?: 0
            } else {
                0
            },
        )
    }
    /** 翻页中途要同时画两页，所以离场那一页连它的正文一起暂存。 */
    var turnOut by remember { mutableStateOf<Pair<Page, String>?>(null) }
    var turnForward by remember { mutableStateOf(true) }
    val scope = rememberCoroutineScope()

    fun textOfPage(p: Page?): String = p?.let {
        engine.book.cache.slice(it.startOffset, (it.endOffset - it.startOffset).toInt())
    } ?: ""

    fun glide(before: Page, beforeText: String, forward: Boolean) {
        if (turnMode == TurnMode.Simple || viewport.width == 0) return
        turnOut = before to beforeText
        turnForward = forward
        scope.launch {
            turnAnim.snapTo(0f)
            turnAnim.animateTo(1f, tween(if (turnMode == TurnMode.Slide) 170 else 260))
            turnOut = null
        }
    }

    /** @return 真的动了页没有 —— 自动阅读靠它判断是不是翻到全书最后一面了。 */
    fun turn(forward: Boolean): Boolean {
        if (turnMode == TurnMode.Scroll) {
            val from = scrollState.value
            val target = (from + viewport.height * if (forward) 1 else -1).coerceIn(0, scrollState.maxValue)
            scope.launch { scrollState.animateScrollTo(target) }
            return target != from
        }
        val t0 = System.nanoTime()
        val before = page
        val beforeText = pageText
        val next = if (forward) engine.nextPage() else engine.prevPage()
        val ms = (System.nanoTime() - t0) / 1_000_000
        if (next != null) {
            page = next
            // 离场那页必须在 page 被换掉之前抓走，否则动画抬起来的是刚到的新页
            if (before != null && beforeText.isNotEmpty()) glide(before, beforeText, forward)
        }
        Log.d(TAG_PERF, "turn forward=$forward hit=${next?.startOffset} ms=$ms resident=${engine.stats().residentChunks}")
        return next != null
    }

    // 菜单开着时，点屏幕任何一处都是收菜单，不误翻页
    fun surfaceTap(widthPx: Int, x: Float) {
        if (barUp) {
            // 只收动作栏。高亮**留着**：听书时点屏幕是为了翻页继续听，不是取消选中
            barUp = false
            selectionNote = null
            return
        }
        if (selection != null && listening == null) {
            selection = null
            selectionRect = null
            selectionNote = null
            voice.stop()
            aiVoice.stop()
            return
        }
        if (panel != Panel.None) {
            panel = Panel.None
            panelNote = null
            return
        }
        if (bars) {
            bars = false
            return
        }
        when (ReaderGestures.zoneOf(widthPx, x)) {
            TapZone.Prev -> if (bothSidesNext) turn(true) else turn(false)
            TapZone.Next -> turn(true)
            // 听书会话开着的时候，中间这一枪不叫菜单：上面那两条分支故意没把选中清掉（点屏是为了翻页继续听），
            // 于是它会一路走到这里 —— 表现就是他报的"听书中点屏幕，阅读菜单又冒出来了"
            TapZone.ToggleBars -> if (listening == null) bars = true
        }
    }

    LaunchedEffect(page) {
        page?.let {
            prefetch.prefetch(engine, 6)
            prefs.savePosition(bookId, engine.position())
        }
    }

    // 音量键这条规则四族阅读器共用一颗 `VolumePageTurn`（小说 / pdf / pptx / 漫画）：
    // 只在**屏幕上没压着东西**的时候翻页，压着哪一层就交回系统音量，那一层的名字进日志
    VolumePageTurn(
        enabled = volumePaging,
        blockedBy = {
            // 第一个命中的就是"当时压着的那一层"，写进日志才分得清是哪一颗挡的
            when {
                listening != null -> "听书会话"
                listenPage -> "听书页"
                tocPage -> "目录页"
                historyPage -> "缓存历史"
                fixesPage -> "多音字表"
                selection != null -> "选段动作栏"
                panel != Panel.None -> "抽屉$panel"
                bars -> "上下栏"
                restPrompt -> "休息提示"
                else -> null
            }
        },
        onTurn = { up -> turn(!up) },
    )

    /**
     * 自动阅读。两种模式共用一个口径「行/分钟」，所以换翻页模式时快慢感觉不变：
     * 上下那一档逐帧推滚动量（一帧推不到一行就攒着，所以 144Hz 上也是匀的）；
     * 按页那一档按"这页有几行"换算成秒，到点翻一页。
     * 滚不动了（全书最后一面）就自己关掉，不留一颗还亮着的开关。
     */
    LaunchedEffect(autoRead, autoSpeedPreset, turnMode, style.id, viewport) {
        if (!autoRead || viewport.height == 0) return@LaunchedEffect
        // 首页还没加载完时 nextPage() 也是 null —— 别把"还没翻开"当成"翻到书尾"把开关自己关掉
        while (page == null) { delay(120) }
        val linesPerMinute = ReaderPrefs.AUTO_LINES_PER_MIN[autoSpeedPreset]
        val lineHeightPx = style.lineHeightPx
        if (turnMode == TurnMode.Scroll) {
            val pxPerNano = lineHeightPx * linesPerMinute / 60_000_000_000.0
            var last = 0L
            var carry = 0.0
            var stuckMs = 0L
            while (true) {
                var to = -1
                // scrollTo 是挂起函数，只能在这段帧回调之外调用
                withFrameNanos { now ->
                    if (last != 0L) {
                        stuckMs += (now - last) / 1_000_000
                        carry += (now - last) * pxPerNano
                        val step = carry.toInt()
                        if (step > 0) {
                            carry -= step
                            val wanted = (scrollState.value + step).coerceAtMost(scrollState.maxValue)
                            if (wanted > scrollState.value) to = wanted
                        }
                    }
                    last = now
                }
                if (to >= 0) {
                    scrollState.scrollTo(to)
                    stuckMs = 0L
                }
                if (stuckMs > 3_000) { autoRead = false; prefs.autoRead = false; break }
            }
        } else {
            while (true) {
                val lines = (page?.lines?.size ?: 1).coerceAtLeast(1)
                delay((lines * 60_000L / linesPerMinute).coerceAtLeast(1_000L))
                if (restPrompt) continue
                if (!turn(true)) { autoRead = false; prefs.autoRead = false; break }
            }
        }
    }
    LaunchedEffect(restMinutes, page) {
        if (restMinutes <= 0) return@LaunchedEffect
        delay(restMinutes * 60_000L)
        restPrompt = true
    }

    // 系统返回键：先收整屏页，再收动作栏，再停听书，再收选中，再收面板，最后回书架，不直接退程序
    BackHandler(enabled = true) {
        when {
            historyPage -> historyPage = false
            tocPage -> tocPage = false

            fixesPage -> fixesPage = false

            barUp -> {
                barUp = false
                selectionNote = null
            }

            listening != null && listenPage -> listenPage = false

            listening != null -> {
                listening = null
                aiPlaying = false
                selection = null
                selectionRect = null
                aiVoice.stop()
            }

            selection != null -> {
                selection = null
                selectionRect = null
                selectionNote = null
                voice.stop()
                aiVoice.stop()
            }

            panel != Panel.None -> {
                panel = if (panel == Panel.More || panel == Panel.Fonts) Panel.Quick else Panel.None
            }

            // 菜单开着**不再"先收菜单"**（他点的口径：按返回就该回书架，不该按一下只把菜单收了）。
            // 收菜单还剩那一条老路：点屏幕中间那一格（`surfaceTap` 里 `if (bars) bars = false`）
            else -> onBack()
        }
    }

    /** 停掉听书：音频、高亮、浮窗、听书页一起退。 */
    fun stopListening() {
        listening = null
        listenBusy = false
        aiPlaying = false
        listenPage = false
        aiVoice.stop()
        selection = null
        selectionRect = null
    }

    /**
     * 合成并播这一段。听书那颗、以及听书页上的「上一段 / 下一段」都走这里 ——
     * 三处共用一份，才不会有一处的错误回话和别处不一样。
     *
     * 同一段（服务商+模型+音色+正文都一样）的音频落在 `cacheDir/voice/` 里，再点**不再发请求**：
     * 合成按字符计费，翻回去重听同一段不该再掏一次钱。要强制重合成只有听书页「更多 → 重新生成」。
     */
    fun speakParagraph(range: LongRange, force: Boolean = false) {
        // 换段（听书页的上一段/下一段、历史重放、目录跳章、暂停中另选一段）：手里那段要**立刻**收。
        // 不收到新音频就绪一直叠着念，而界面只有一个播放器状态，看着就是"念了两段、按钮还是暂停"
        val switching = listening != null && listening != range
        val text = engine.book.cache.slice(range.first, (range.last - range.first + 1).toInt())
        // 多音字白名单只改"发给接口的那串字"：屏幕上高亮和回显的仍是原文。缓存键跟着它走，
        // 所以改了表之后同一段会重新合成一次 —— 那是应该的，旧音频本来就是错读法
        val spoken = AiSpeech.applyFixes(text, prefs.aiFixes)
        // 整段没有可念的字（markdown 表格的 `|----|----|`、分隔线 `— — —`、光一个 `}`）：
        // 发过去阿里就是回 400，白等一趟。就地回话，不发请求、不计费
        if (!AiSpeech.speakable(spoken)) {
            selectionNote = "这一段没有念得出来的字（全是符号），没发请求"
            return
        }
        aiVoice.rate = listenRate
        aiVoice.shareFocus = shareFocus
        listening = range
        listenBusy = true
        voice.stop()
        if (switching) { aiVoice.stop(); aiPlaying = false }
        selection = range
        selectionNote = "正在合成…"
        scope.launch {
            val plain = withContext(Dispatchers.IO) { SecretStore.decrypt(prefs.aiVoiceKeyCipher) }
            val kind = runCatching { AiSpeech.Kind.valueOf(prefs.aiVoiceKind) }.getOrDefault(AiSpeech.Kind.OpenAi)
            val outcome = withContext(Dispatchers.IO) {
                runCatching {
                    val chosen = prefs.aiVoiceId
                    val f = AiVoiceCache.fileFor(context, kind, prefs.aiVoiceModel, chosen, spoken)
                    // 合成 + 落盘。存之前先把 wav 的两个长度字段写成真实字节数（阿里回的是 0x7FFFFFFF）
                    val synth: (String) -> File = { voice ->
                        AiVoiceCache.fileFor(context, kind, prefs.aiVoiceModel, voice, spoken).apply {
                            writeBytes(AiSpeech.wavSizesFixed(AiVoiceClient.synthesize(
                                prefs.aiVoiceBaseUrl, plain ?: "", kind, prefs.aiVoiceModel, voice, spoken,
                            )))
                            // 只有真发过请求才记一笔：缓存命中不重复记，否则「缓存历史」会被"重听"灌满。
                            // 字数记**原文**的：那一页要显示的是屏幕上那段，不是发给接口那串
                            AiVoiceCache.remember(context, this, bookId, range.first, text.length)
                        }
                    }
                    // 命中过的直接用手里那份；写坏/半截的文件长度是 0，落回合成那一条
                    if (!force && f.length() > 0L) {
                        // 这一档之前存进去的文件都带着"长度未知"的 wav 头，不补的话时长报成 745:39
                        AiVoiceCache.heal(f)
                        f to (null as String?)
                    } else try {
                        synth(chosen) to (null as String?)
                    } catch (e: AiError) {
                        // 他选的这个名字这一家不认：退回这一档的默认音色再试一次。
                        // 失败那一次没出音频、不按字计费，所以这一下重试不多花钱
                        val why = e.message ?: ""
                        val def = AiSpeech.defaultVoice(kind, prefs.aiVoiceModel)
                        if (
                            def == null || def.equals(chosen, ignoreCase = true) ||
                            !(why.contains("voice", true) || why.contains("音色") || why.contains("InvalidParameter"))
                        ) throw e
                        synth(def) to (def as String?)
                    }
                }
            }
            outcome.fold(
                onSuccess = { (file, usedFallback) ->
                    // 退回默认音色播成功了：把界面上选中那颗挪过去，不然它标着的还是那个不认的名字
                    if (usedFallback != null) {
                        prefs.aiVoiceId = usedFallback
                        voiceNow = usedFallback
                    }
                    listenBusy = false
                    // 合成这几秒里他又点了一次「听书」= 取消：不接着播，也不顶出浮窗
                    if (listening == range) {
                        val started = aiVoice.play(file) {
                            // 「更多 → 连续播放」开着：自然放完就自己接下一段（每多一段多一次合成费，
                            // 所以这颗默认关）。暂停后手动收的、和定时到点停的都不走这一条。
                            if (autoNext && listening == range) {
                                // 右界是**本章末尾**（他点名的"连续播放按章节结束"）：`nextParagraph`
                                // 只按偏移走、不看章界，所以章界在这里自己夹
                                val ch = engine.book.chapters.chapterAt(range.first)
                                val nx = engine.nextParagraph(range)
                                if (nx != null && nx.first < ch.endOffset) {
                                    speakParagraph(nx); return@play
                                }
                                // 本章念完了：只有「自动下一章」也开着才跨过去。跨的时候正文要跟着翻 ——
                                // 听的是下一章、屏幕上还停在上一章，回头点「上一段」就对不上
                                if (autoNextChapter && ch.index + 1 < engine.chapterCount) {
                                    val nc = engine.book.chapters[ch.index + 1]
                                    page = engine.jumpToOffset(nc.startOffset)
                                    jumpToken++
                                    speakParagraph(engine.paragraphRange(nc.startOffset))
                                    return@play
                                }
                            }
                            // 念完自己收：高亮和浮窗一起退，不用他再点一下
                            listening = null
                            aiPlaying = false
                            listenPage = false
                            selection = null
                            selectionRect = null
                        }
                        if (started) {
                            aiPlaying = true
                            // 开口之后才收动作栏：留着高亮，浮窗接管"暂停/停"
                            barUp = false
                            selectionNote = null
                        } else {
                            listening = null
                            selectionNote = "拿到了 ${file.length()} 字节音频，本机播不出来"
                        }
                    }
                },
                onFailure = {
                    listenBusy = false
                    listening = null
                    selectionNote = if (it is AiError) it.message ?: "合成失败"
                    else "合成失败：${it.javaClass.simpleName}"
                },
            )
        }
    }

    /** 定时停止：到点自己收。0 = 没设。放在这一层而不是听书页里，收起那一屏也要继续倒计时。 */
    var sleepEndsAt by remember { mutableLongStateOf(0L) }
    LaunchedEffect(sleepEndsAt) {
        while (sleepEndsAt > 0L) {
            val left = sleepEndsAt - System.currentTimeMillis()
            if (left <= 0L) {
                sleepEndsAt = 0L
                stopListening()
                break
            }
            delay(1000)
        }
    }

    /**
     * 目录那一页的「缓存」：把这几章的每一段按**播放时同一套键**合成落盘，之后真听到那一段是命中缓存。
     *
     * 三条约束都是钱给的：① 只有点过才发请求（选范围不算）；② 已经存过的段一个请求都不发；
     * ③ 出错就停下而不是跳过 —— 继续往下发只是把同一个错误重复按字计费。
     * 取正文那几步留在主线程（`ChunkCache` 里面是无锁的 access-order LinkedHashMap，
     * 换线程读就是和翻页/播放抢同一份表），慢的那一段（HTTP、写盘）才上 IO。
     */
    fun cacheChapters(indices: List<Int>) {
        if (voiceBatch.running || indices.isEmpty()) return
        voiceBatch.stop = false
        voiceBatch.error = null
        voiceBatch.running = true
        voiceBatch.done = 0
        voiceBatch.requests = 0
        voiceBatch.chars = 0L
        scope.launch {
            val plain = withContext(Dispatchers.IO) { SecretStore.decrypt(prefs.aiVoiceKeyCipher) } ?: ""
            val kind = runCatching { AiSpeech.Kind.valueOf(prefs.aiVoiceKind) }.getOrDefault(AiSpeech.Kind.OpenAi)
            val voice = prefs.aiVoiceId
            val finished = ArrayList<Int>()
            for (ci in indices) {
                if (voiceBatch.stop) break
                val ch = engine.book.chapters[ci]
                var r: LongRange? = engine.paragraphRange(ch.startOffset)
                while (r != null && r.first < ch.endOffset && !voiceBatch.stop) {
                    val range = r
                    val text = engine.book.cache.slice(range.first, (range.last - range.first + 1).toInt())
                    val spoken = AiSpeech.applyFixes(text, prefs.aiFixes)
                    // 一个可念的字都没有（markdown 表格的 `|----|----|`、只有星号的分割线）：直接跳过。
                    // 原来这里只判 isNotBlank，而竖线和连字符不是空白 —— 发过去阿里回 400
                    // "Please ensure input text is valid"，批量缓存把这一格当成失败，整个第一章停在第 13 段
                    if (AiSpeech.speakable(spoken)) {
                        val f = AiVoiceCache.fileFor(context, kind, prefs.aiVoiceModel, voice, spoken)
                        val haveIt = withContext(Dispatchers.IO) { f.length() > 0L }
                        if (haveIt) {
                            withContext(Dispatchers.IO) { AiVoiceCache.heal(f) }
                        } else {
                            try {
                                withContext(Dispatchers.IO) {
                                    f.writeBytes(AiSpeech.wavSizesFixed(AiVoiceClient.synthesize(
                                        prefs.aiVoiceBaseUrl, plain, kind, prefs.aiVoiceModel, voice, spoken,
                                    )))
                                    AiVoiceCache.remember(context, f, bookId, range.first, text.length)
                                }
                                voiceBatch.requests++
                                voiceBatch.chars += text.length
                            } catch (e: Exception) {
                                // 点名是哪一段（偏移 + 这段的字数）：只写"第 N 章有一没存上"没法对着目录那页查
                                voiceBatch.error = "第 ${ci + 1} 章 第 ${range.first} 字那段（${text.length} 字）没存上：" +
                                    "${e.message ?: e.javaClass.simpleName}，已停下"
                            }
                        }
                    }
                    voiceBatch.done++
                    if (voiceBatch.error != null) break
                    r = engine.nextParagraph(range)
                }
                if (voiceBatch.error != null) break
                finished.add(ci)
            }
            // 整章跑完才标"已缓存"：还差一段就不能装作这一章随时能听。存盘 —— 不存的话退出这本书
            // 再进来，标记全空、目录右边又变回「缓存」，看着像那一章白缓存了（他报的这条）
            tocCached = tocCached + finished.toSet()
            prefs.setCachedChapters(bookId, tocCached)
            voiceHistory = AiVoiceCache.history(context).filter { it.bookId == bookId }
            voiceBatch.running = false
        }
    }

    // 自动缓存开着：在听的前提下换到新的一章，就按那一档自己往后缓存（他点的"不用额外点立即缓存"）。
    // 只认"这一章跑过一次"：同一章里每段都在推进 listening，不记这一笔就会每段重发一遍
    var lastAutoChapter by remember { mutableIntStateOf(-1) }
    LaunchedEffect(listening, tocAuto, tocScope) {
        val ci = engine.currentChapterIndex
        if (tocAuto && tocScope != 0 && listening != null && ci != lastAutoChapter && !voiceBatch.running) {
            lastAutoChapter = ci
            cacheChapters(cacheRange(tocScope, ci, engine.chapterCount))
        }
    }

    val pagesInChapter = remember(page) { engine.pagesInChapter() }
    val chapter = engine.chapter
    val percent = (engine.progress() * 1000).roundToInt() / 10f

    // 「我的 → 皮肤管理」上传的那张图：铺在正文底下，上面压一层当前那档纸色（浓淡由那一屏的滑块定）。
    // 只有小说这一屏铺 —— pdf/pptx 要保留原件版面、漫画整屏是图（他点的口径）
    val bgFile = themeFile(context, prefs.bgImageFile)
    val bgBmp = scaledImage(bgFile, viewport.width)
    // 有图的时候正文那一层要透，否则 PageView / ScrollReader 自己那张纸就把图盖住了
    val bgPaper = if (bgBmp == null) theme.pageColor else Color.Transparent

    // 「全屏模式」（我的 → 偏好设置那颗）：这一屏自己的菜单全收干净了，才把系统状态栏和导航条也盖过去。
    // 判据和音量键那一串同一份层表 —— 上下栏还亮着就藏状态栏，等于两条栏叠在一起
    // 「全屏模式」只在**这一本挂了背景图**的阅读屏生效（他点的口径）：没有图，藏了状态栏只是把
    // 一块实心纸顶到屏幕上沿，什么都换不来；漫画 / pdf / pptx 三屏因此根本不藏。
    val immersive = prefs.fullScreen && bgFile != null && !bars && !restPrompt &&
        panel == Panel.None && selection == null && !listenPage && !tocPage && !historyPage && !fixesPage &&
        listening == null
    ApplySystemBars(darkIcons = !theme.night, immersive = immersive)
    // 我们这一层菜单（两条栏 + 背后那两段纸）和系统栏那两条**在同一条淡出里走**，
    // 判据是"什么时候**结束**"，不是"什么时候开始"：
    //  · 还回来：我们 tween(275)，平台报 311ms —— 两个几乎同刻结束 → 他认这一向"一样了"
    //  · 收下去：我们原来也 tween(275)，可状态栏那点墨比这晚得多才没 —— 我们比它早结束，
    //    那就是他现在点的"隐藏的时候不一致"
    //
    // 时长挂到平台报的那一段上（这台机 prepare 报 1500ms），系数 **0.82** 是录屏逐帧数出来的：
    // 录屏的 pts 钟**不能信**（`r_frame_rate` 是 90000/1、dts 非单调、开头空 750ms），
    // 所以只用"同一张帧网格上两件事各占几帧"这种和尺度无关的数。三档各三趟：
    //  · 0.62（930ms）：我们 40 帧、图标 55 帧 → 早收 15 帧
    //  · 0.85（1275ms）：我们 55/60/51、图标 52/55/47 → 晚收 3~5 帧（×0.92）
    //  · 0.78（1170ms）：我们 48/54/48、图标 53/59/52 → 早收 4~5 帧（×1.09）
    // 两头的因子对系数插值到 1.0 = 0.82。趟间本身有 ±3 帧的散布，再往下抠就是抠仪表的噪声了。
    // 为什么不照图标那个"憋大半秒再走"的形状：那等于点完 0.6 秒屏幕上没有任何反应，比现在更差。
    // 用 0f 收尾而不是把它移出组合：淡出这一路还要画着，淡完了才不占、也不接点击
    val chrome = remember { Animatable(1f) }
    // key 里必须有 `bars`：只挂 immersive 的话，**没挂背景图的那本书**（或全屏开关关着）immersive 永远是
    // false，chrome 就冻在 1f，而上面那两条栏的门槛是 `bars || chrome.value > 0f` —— 菜单再也收不下去
    LaunchedEffect(bars, immersive, Chrome.barsSynced) {
        // 藏系统栏那一枪被我们接管了（`barsSynced`）→ 两边本来就是同一个时长，不用再拟合；
        // 没接管到才退回逐帧量出来的那档 0.82。key 里带上它，是为了接管在 120ms 后报"没接到"时
        // 这条淡出能立刻改走拟合那一档，而不是已经按 275ms 淡完了才反应过来
        val ms =
            if (!bars && immersive && !Chrome.barsSynced) (Chrome.barHideMs * 0.82f).roundToInt()
            else Chrome.barShowMs
        chrome.animateTo(if (bars) 1f else 0f, tween(ms))
    }

    MaterialTheme(colorScheme = colorSchemeOf(theme)) {
        // 外层这一格铺**整块窗口**：底色、背景图、那层压淡的纸色都要伸到状态栏和导航条底下
        // （"背景撑满状态栏和导航栏"要的就是这个）。让安全区不在这层 —— 挪到里面那一格，
        // 而且两态用**同一个** fixedBarsPadding()：以前栏亮时走 safeDrawingPadding（读当前 insets）、
        // 栏收时走冻住的那一份，两个数不等，一收一放正文就整块跳一下（他报的"屏幕晃动"）。
        Box(Modifier.fillMaxSize().background(theme.pageColor)) {
        if (bgBmp != null) {
            // 图**永远铺满整块窗口**：它的高度不跟着藏栏变，所以收放菜单那几下它一个像素都不挪
            // （原来这一层自己让开系统栏，栏一收它整块跳上去 84px —— 那就是他看到的闪烁）
            Image(
                bitmap = bgBmp,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
            // 压的就是当前那档纸色：换纸色这一层跟着换，正文底下不会露出两张不同的底
            Box(Modifier.fillMaxSize().background(theme.pageColor.copy(alpha = prefs.bgDim)))
            // 菜单亮着的时候，状态栏 / 导航条那两条用实心纸色**盖住**图 —— 盖，不改任何一层的高度。
            // 高度取"藏了栏也不变"那一份（systemBarHeights），所以这一对在切换时不抖、也不会把图挤回去
            if (chrome.value > 0f) {
                val barInset = systemBarHeights()
                Box(
                    Modifier.align(Alignment.TopCenter).fillMaxWidth()
                        .height(barInset.calculateTopPadding())
                        .background(theme.pageColor.copy(alpha = chrome.value))
                )
                Box(
                    Modifier.align(Alignment.BottomCenter).fillMaxWidth()
                        .height(barInset.calculateBottomPadding())
                        .background(theme.pageColor.copy(alpha = chrome.value))
                )
            }
        }
        // 让开系统栏的那一层：正文和我们自己那两条栏都在这格里面（背景图在外面，才铺得满）
        Box(Modifier.fillMaxSize().fixedBarsPadding()) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .onSizeChanged { viewport = it }
                    .pointerInput(page, turnMode) {
                        detectTapGestures(
                            onTap = { offset -> if (turnMode != TurnMode.Scroll) surfaceTap(size.width, offset.x) },
                            // 行盒从 topSlack 那条线开始，所以减掉它再除以行高就是屏幕上第几行
                            onLongPress = { offset ->
                                val idx = ((offset.y - pagePadPx) / lineHeightPx).toInt()
                                val shown = page
                                shown?.lines?.getOrNull(idx)?.let { line ->
                                    val range = engine.paragraphRange(line.start)
                                    val touched = shown.lines.filter { it.end > range.first && it.start < range.last }
                                    selection = range
                                    barUp = true
                                    selectionRect = if (touched.isEmpty()) null else
                                        pagePadPx + touched.minOf { it.topPx } to
                                            pagePadPx + touched.maxOf { it.topPx } + lineHeightPx
                                    selectionNote = null
                                }
                            },
                        )
                    }
                    .pointerInput(Unit) {
                        var dx = 0f
                        val threshold = 56f * density.density
                        detectHorizontalDragGestures(
                            onDragStart = { dx = 0f },
                            onHorizontalDrag = { _, dragAmount -> dx += dragAmount },
                            onDragEnd = {
                                if (turnMode != TurnMode.Scroll) {
                                    when (ReaderGestures.swipeDirection(dx, threshold)) {
                                        1 -> turn(true)
                                        -1 -> turn(false)
                                        else -> Unit
                                    }
                                }
                                dx = 0f
                            },
                        )
                    },
            ) {
                if (turnMode == TurnMode.Scroll) {
                    ScrollReader(
                        engine = engine,
                        style = engine.style,
                        theme = theme,
                        typeface = typeface,
                        paper = bgPaper,
                        marginPx = marginDp * density.density,
                        traditional = traditional,
                        bandTopPx = bandTopPx,
                        bandBottomPx = bandBottomPx,
                        jumpToken = jumpToken,
                        scroll = scrollState,
                        onTap = { widthPx, x -> surfaceTap(widthPx, x) },
                        selection = selection,
                        selectionColor = selectionColor,
                        marks = markRanges,
                        markColor = markColor,
                        onLongPress = { offset ->
                            // 弹框由 selection 驱动，这里只写 selectionRect 的话上下那一档永远只亮不弹
                            engine.paragraphRange(offset).also {
                                selection = it
                                barUp = true
                                selectionNote = null
                            }
                        },
                        onSelectionRect = { top, bottom ->
                            selectionRect = top to bottom
                            selectionNote = null
                        },
                        onTopOffset = { offset ->
                            engine.jumpToOffset(offset)
                            page = engine.currentPage()
                        },
                        modifier = Modifier.fillMaxSize(),
                    )
                } else {
                    val out = turnOut
                    val p = turnAnim.value
                    val turning = out != null && p < 1f
                    val slack = topSlackDp
                    Box(Modifier.matchParentSize().then(contentClip)) {
                        when {
                            // 平移：两页一起走，往右翻旧页往左退、新页从右边进；往左翻整个镜像
                            turning && turnMode == TurnMode.Slide -> {
                                val (outX, inX) = PageTurn.slideOffsets(p, turnForward)
                                TurnLayer(out.first, out.second, engine.style, theme, typeface, marginDp, slack, selection, selectionColor, markRanges, markColor, engine.book.styles,
                                    Modifier.graphicsLayer { translationX = outX * viewport.width }, paper = bgPaper)
                                TurnLayer(page, pageText, engine.style, theme, typeface, marginDp, slack, selection, selectionColor, markRanges, markColor, engine.book.styles,
                                    Modifier.graphicsLayer { translationX = inX * viewport.width }, paper = bgPaper)
                            }
                            // 仿真：都绕左边的书脊转。往前翻是把旧页掀起来（0→-90°），往后翻是把新页铺回去（-90°→0）
                            turning && turnMode == TurnMode.Cover -> {
                                val lift = PageTurn.coverLift(p, turnForward)
                                val under = if (turnForward) page to pageText else out
                                val onTop = if (turnForward) out else page to pageText
                                TurnLayer(under.first, under.second, engine.style, theme, typeface, marginDp, slack, selection, selectionColor, markRanges, markColor, engine.book.styles, Modifier, paper = bgPaper)
                                TurnLayer(onTop.first, onTop.second, engine.style, theme, typeface, marginDp, slack, selection, selectionColor, markRanges, markColor, engine.book.styles,
                                    Modifier.graphicsLayer {
                                        rotationY = PageTurn.coverAngle(lift)
                                        transformOrigin = TransformOrigin(0f, 0.5f)
                                        cameraDistance = 16f * density.density
                                        alpha = 1f - 0.3f * lift
                                    }, paper = bgPaper)
                            }
                            else -> TurnLayer(page, pageText, engine.style, theme, typeface, marginDp, slack, selection, selectionColor, markRanges, markColor, engine.book.styles, Modifier, paper = bgPaper)
                        }
                    }
                }
            // 动作栏由 barUp 决定看不看得到，高亮由 selection 决定 —— 听书时要"留着高亮、收起按钮"，
            // 这两个必须能分开
            selection?.takeIf { barUp }?.let { range ->
                val rect = selectionRect
                // 尖端离段落边缘的缝、三角自己的高、动作栏的高，三者都要算进落点：
                // 之前朝上那一档只让开了栏高（70dp），三角整个压在段落首行上；朝下那一档
                // 尖端从段落底边往上 4dp 就开始画，栏体也只让了 4dp，等于把下一行削掉半截。
                val tipGap = 4f * density.density
                val arrowH = 8f * density.density
                // 动作栏自己的高度：SelectionBar 里写的是 58dp（真机量过那一整条色块 160px = 58.2dp）。
                // 先前按 70dp 预留，多让 12dp，尖端离段落 46px，看着就是三角没挨着段落。
                val barH = 58f * density.density
                // 「已加书签…」那行在栏上面，占一格高度，落点要把它算进去，否则一出现回话整栏就往下挤
                val noteH = if (selectionNote != null) 24f * density.density else 0f
                val stackH = noteH + barH + arrowH + tipGap
                // 上面放得下就贴在段落上面；放不下（选的是第一段）才放到下面
                val barAbove = rect != null && rect.first > 84f + stackH
                val barTopPx = when {
                    rect == null -> viewport.height - 190f
                    barAbove -> rect.first - stackH
                    else -> rect.second + tipGap
                }
                Column(
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .fillMaxWidth()
                        .padding(horizontal = 14.dp)
                        .offset(y = with(density) { barTopPx.coerceAtLeast(0f).toDp() }),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    selectionNote?.let {
                        // AI 在路上时给一个转圈的指示：这句回话要等一两秒，没有它看起来像没响应。
                        // 那 6dp 的下方留白原来挂在文字身上 —— 那样这一行居中时，对的是"字 + 6dp"那个盒子，
                        // 圈就比字的墨中心低 3dp（真机截图里就是它压到下面那行字）。挪到行上，两个中心才同一个
                        Row(Modifier.padding(bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                            if (listenBusy || dictBusy) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(12.dp),
                                    strokeWidth = 1.6.dp,
                                    color = theme.faintColor,
                                )
                                Spacer(Modifier.size(6.dp))
                            }
                            // 不用强调色（红）：这一行是"发生了什么"的回话，不是要他注意的危险提示
                            Text(it, fontSize = 12.sp, color = theme.faintColor)
                        }
                    }
                    if (!barAbove) SelectionArrow(theme.barColor, down = false)
                    SelectionBar(
                        theme = theme,
                        // 这一段里有没有书签 —— 按"落在选中范围里"判，不按偏移相等判：
                        // 老书签记的是当时那一行的行首，和段首差几十字（量过：存 59237、段首 59187），
                        // 按相等判会让一段明明画着红波浪线、弹框却还写"书签"
                        bookmarked = marks.any { it.absoluteOffset in range },
                        onAction = { act ->
                            when (act) {
                                SelectionAction.Bookmark -> {
                                    prefs.addBookmark(bookId, engine.position().copy(absoluteOffset = range.first))
                                    marks = prefs.bookmarks(bookId)
                                    selectionNote = "已加书签：第${engine.currentChapterIndex + 1}章 第${range.first + 1}字"
                                }
                                SelectionAction.Delete -> {
                                    prefs.bookmarks(bookId).filter { it.absoluteOffset in range }
                                        .forEach { prefs.removeBookmark(bookId, it) }
                                    marks = prefs.bookmarks(bookId)
                                    // 收掉弹框：那一段下面的红色波浪线一起没了，这就是删成功的回话
                                    selection = null
                                    selectionRect = null
                                }
                                SelectionAction.Listen -> {
                                    val text = engine.book.cache.slice(range.first, (range.last - range.first + 1).toInt())
                                    if (prefs.aiVoiceReady) {
                                        // 再点**同一段** = 停：合成要花钱，不能让人靠"等它念完"来取消。
                                        // 点**另一段** = 换过去播：暂停中重新选一段再点听书，原来这里只看
                                        // "有没有会话"，于是把旧的关掉之后新的根本不发声（他报的这条）
                                        if (listening == range) stopListening() else speakParagraph(range)
                                    } else when {
                                        voice.speak(text) -> selectionNote = "系统语音朗读这一段"
                                        voice.state == 0 -> selectionNote = "语音引擎正在启动，再点一次听书"
                                        else -> selectionNote = "这台机没有可用的中文语音引擎；在「我的 → AI 听书」里填一档语音地址、模型、音色和秘钥就能听"
                                    }
                                }
                                SelectionAction.Comment -> selectionNote = "评论要接书源服务端才有地方存"
                                SelectionAction.Dictionary -> {
                                    val q = engine.book.cache.slice(range.first, (range.last - range.first + 1).toInt())
                                    // 多音字和"一/不"变调只有上下文能定，所以把前面 60 字一起发过去
                                    val from = (range.first - 60).coerceAtLeast(0L)
                                    val ctx = engine.book.cache.slice(from, (range.first - from).toInt())
                                    selection = null
                                    selectionRect = null
                                    dictQuery = q
                                    val configured = prefs.aiBaseUrl.isNotBlank() && prefs.aiKeyCipher.isNotBlank()
                                    if (!configured) {
                                        dictBusy = false
                                        dictAnswer = "还没配 AI 翻译：我的 → AI 翻译 里填 API 地址、模型名和秘钥。"
                                    } else {
                                        dictBusy = true
                                        dictAnswer = null
                                        scope.launch {
                                            val plain = withContext(Dispatchers.IO) { SecretStore.decrypt(prefs.aiKeyCipher) }
                                            val outcome = withContext(Dispatchers.IO) {
                                                runCatching {
                                                    AiClient.chat(
                                                        prefs.aiBaseUrl, plain ?: "", prefs.aiModel,
                                                        AiProtocol.DICT_SYSTEM, AiProtocol.dictUser(q, ctx),
                                                    )
                                                }
                                            }
                                            dictBusy = false
                                            dictAnswer = outcome.fold(
                                                onSuccess = { it },
                                                onFailure = {
                                                    if (it is AiError) it.message ?: "查询失败"
                                                    else "查询失败：${it.javaClass.simpleName}"
                                                },
                                            )
                                        }
                                    }
                                    panel = Panel.Dict
                                }
                                SelectionAction.Correct -> selectionNote = "纠错要能提交回书源，本机没接"
                            }
                        },
                    )
                    if (barAbove) SelectionArrow(theme.barColor, down = true)
                }
            }

                if (listening != null && !listenBusy) {
                    val wPx = 116f * density.density
                    val hPx = 56f * density.density
                    // 默认落在左下角，往上留出底栏那一格；拖过之后用拖到的位置
                    val at = if (floatPos == Offset.Zero) {
                        Offset(12f * density.density, (viewport.height - hPx - 96f * density.density).coerceAtLeast(0f))
                    } else {
                        floatPos
                    }
                    ListenFloat(
                        theme = theme,
                        playing = aiPlaying,
                        meta = engine.book.meta,
                        title = engine.book.meta.title,
                        modifier = Modifier.offset { IntOffset(at.x.toInt(), at.y.toInt()) },
                        onDrag = { dx, dy ->
                            floatPos = Offset(
                                (at.x + dx).coerceIn(0f, (viewport.width - wPx).coerceAtLeast(0f)),
                                (at.y + dy).coerceIn(0f, (viewport.height - hPx).coerceAtLeast(0f)),
                            )
                        },
                        onToggle = { aiPlaying = aiVoice.toggle() },
                        onOpenPage = { listenPage = true },
                        onClose = { stopListening() },
                    )
                }

                val lr = listening
                if (lr != null && listenPage) {
                    // 上一段/下一段只在"有没有"上判，跳过去还要重新合成 —— 这两颗按字符计费，
                    // 所以不做"自动连念"，一次一下是他点出来的
                    val hasPrev = remember(lr) { engine.prevParagraph(lr) != null }
                    val hasNext = remember(lr) { engine.nextParagraph(lr) != null }
                    ListenScreen(
                        theme = theme,
                        chapterTitle = "第 ${engine.currentChapterIndex + 1} 章 " +
                            engine.book.chapters[engine.currentChapterIndex].title,
                        bookTitle = engine.book.meta.title,
                        meta = engine.book.meta,
                        paragraph = engine.book.cache.slice(lr.first, (lr.last - lr.first + 1).toInt()),
                        busy = listenBusy,
                        playing = aiPlaying,
                        hasPrev = hasPrev,
                        hasNext = hasNext,
                        player = aiVoice,
                        rate = listenRate,
                        sleepEndsAt = sleepEndsAt,
                        onCollapse = { listenPage = false },
                        onToggle = { aiPlaying = aiVoice.toggle() },
                        onStop = { stopListening() },
                        onPrev = { engine.prevParagraph(lr)?.let { speakParagraph(it) } },
                        onNext = { engine.nextParagraph(lr)?.let { speakParagraph(it) } },
                        onRate = { r ->
                            // 本地变速：音频已经在手里，不重新合成、不再花一次钱。
                            // 暂停的时候 setRate 只记下值，不会把播放顶起来（那是平台行为，播放器里挡掉了）
                            listenRate = r
                            aiVoice.rate = r
                            aiVoice.applyRate()
                        },
                        // 「原文」= 收起这一屏，把正文翻到正在念的那一段
                        onOriginal = {
                            listenPage = false
                            page = engine.jumpToOffset(lr.first)
                            jumpToken++
                        },
                        onSleep = { minutes ->
                            sleepEndsAt = if (minutes <= 0) 0L else System.currentTimeMillis() + minutes * 60_000L
                        },
                        onResynth = { speakParagraph(lr, force = true) },
                        voiceId = voiceNow,
                        // 可选那一列按服务商各存一份：OpenAI 的 alloy 不是阿里的音色名，混着列出来选了必 400
                        // 他自己抄的那一列排前面，内置的常见音色补后面（第一屏不是一排空的）
                        voices = AiSpeech.voiceOptions(
                            AiSpeech.voices(prefs.aiVoiceChoices(prefs.aiVoiceKind)),
                            AiSpeech.commonVoices(
                                runCatching { AiSpeech.Kind.valueOf(prefs.aiVoiceKind) }
                                    .getOrDefault(AiSpeech.Kind.OpenAi),
                                prefs.aiVoiceModel,
                            ),
                        ),
                        autoNext = autoNext,
                        autoNextChapter = autoNextChapter,
                        shareFocus = shareFocus,
                        onVoice = { v ->
                            prefs.aiVoiceId = v
                            voiceNow = v
                        },
                        onAutoNext = { v ->
                            autoNext = v
                            prefs.listenAutoNext = v
                        },
                        onAutoNextChapter = { v ->
                            autoNextChapter = v
                            prefs.listenAutoNextChapter = v
                        },
                        onShareFocus = { v ->
                            shareFocus = v
                            prefs.listenShareFocus = v
                            // 下一段新建播放器时套上去；手里这段已经开播了，改焦点属性要重开，不为了它打断
                            aiVoice.shareFocus = v
                        },
                        onHistory = {
                            voiceHistory = AiVoiceCache.history(context).filter { it.bookId == bookId }
                            historyPage = true
                        },
                        onFixes = { fixesPage = true },
                        onToc = { tocPage = true },
                        onPaper = { picked ->
                            // 和快捷面板那排色块同一个主人：改的就是 themeIndex，回阅读页也是这一档
                            theme = picked
                            prefs.themeIndex = picked.ordinal
                        },
                    )
                }

                if (historyPage) {
                    ListenHistoryPage(
                        theme = theme,
                        entries = voiceHistory,
                        preview = { off, n -> engine.book.cache.slice(off, n).replace('\n', ' ') },
                        totalBytes = voiceHistory.sumOf { it.bytes },
                        onBack = { historyPage = false },
                        onReplay = { e ->
                            historyPage = false
                            // 按偏移找回那一段（记的是原文偏移，所以表改过也能对上），再走同一条缓存路
                            val r = engine.paragraphRange(e.offset)
                            speakParagraph(r)
                            page = engine.jumpToOffset(r.first)
                            jumpToken++
                        },
                    )
                }

                if (fixesPage) {
                    ListenFixesPage(
                        theme = theme,
                        table = prefs.aiFixes,
                        hits = voiceHistory.size,
                        onBack = { fixesPage = false },
                        onSave = { prefs.aiFixes = it },
                    )
                }

                if (tocPage) {
                    ListenTocPage(
                        theme = theme,
                        chapters = engine.book.chapters.chapters,
                        current = engine.currentChapterIndex,
                        cached = tocCached,
                        batch = voiceBatch,
                        scope = tocScope,
                        auto = tocAuto,
                        onScope = {
                            tocScope = it
                            prefs.listenCacheScope = it
                        },
                        onAuto = { v ->
                            tocAuto = v
                            prefs.listenCacheAuto = v
                            // 开关拨到开：这一档当场就跑起来，不用再点「立即缓存」
                            if (v && tocScope != 0) {
                                lastAutoChapter = engine.currentChapterIndex
                                cacheChapters(cacheRange(tocScope, engine.currentChapterIndex, engine.chapterCount))
                            }
                        },
                        onBack = { tocPage = false },
                        onPick = { ci ->
                            // 跳章 = 落到那一章的第一个字，然后从那里开始听（这一段该花钱由你点这一行决定）
                            val r = engine.paragraphRange(engine.book.chapters[ci].startOffset)
                            tocPage = false
                            listenPage = true
                            page = engine.jumpToOffset(r.first)
                            jumpToken++
                            speakParagraph(r)
                        },
                        onCacheChapter = { ci -> cacheChapters(listOf(ci)) },
                        onCacheRange = { list -> cacheChapters(list) },
                        onBatchStop = { voiceBatch.stop = true },
                    )
                }

                if (eyeCare) {
                    Box(Modifier.fillMaxSize().background(Color(0x26FF9A3C)))
                }
            }

            // 顶/底栏画在听书页**之后**（同一层 Box 的后面），所以听书页开着时它俩会压在人家上面 ——
            // 收起听书页也不该顺手把菜单叫出来：`bars` 保持原值，只是这一会儿不画
            if ((bars || chrome.value > 0f) && !listenPage && !historyPage && !fixesPage && !tocPage) {
                Row(
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .fillMaxWidth()
                        .height(TOP_BAND_DP.dp)
                        .graphicsLayer { alpha = chrome.value }
                        // 淡出这一路它还画着，但**不能再当菜单点**：命中窗口跟着 `bars`，不跟着 1.2 秒那条曲线
                        // （原来 275ms 时这点重叠看不出来，拉长之后就成了"收了菜单还点得到栏"）。
                        // 这一枪转给正文那一套判据（`surfaceTap`）—— 菜单一收，整屏就都是翻页表面
                        .fadingTap(bars) { w, x -> surfaceTap(w, x) }
                        .background(theme.pageColor)
                        .drawBehind {
                            // 栏和正文同色，只剩这条发丝线说"这里有个栏"；1dp 在这台机渲成 3px
                            drawLine(theme.dividerColor, Offset(0f, size.height - 1f), Offset(size.width, size.height - 1f), 1f)
                        }
                        .padding(horizontal = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    BackChevron(theme, onClick = onBack)
                    Text(chapter.title, fontSize = 14.sp, color = theme.inkColor, modifier = Modifier.padding(start = 4.dp))
                    Spacer(Modifier.weight(1f))
                    Text(
                        if (pagesInChapter > 0) "${engine.currentPageIndex + 1}/$pagesInChapter 页" else "第 ${engine.currentPageIndex + 1} 页",
                        fontSize = 13.sp,
                        color = theme.faintColor,
                    )
                    Text("  $percent%", fontSize = 13.sp, color = theme.faintColor, modifier = Modifier.padding(end = 8.dp))
                }

                ReaderBottomBar(
                    theme = theme,
                    modifier = Modifier.align(Alignment.BottomCenter).graphicsLayer { alpha = chrome.value }
                        .fadingTap(bars) { w, x -> surfaceTap(w, x) },
                    onToc = { panel = flip(panel, Panel.Chapters) },
                    onMarks = {
                        page?.let { prefs.addBookmark(bookId, engine.position()) }
                        panel = flip(panel, Panel.Marks)
                    },
                    onToggleNight = {
                        val next = if (theme.night) ReaderTheme.Paper else ReaderTheme.Night
                        theme = next
                        prefs.themeIndex = next.ordinal
                    },
                    onSettings = {
                        panel = flip(panel, Panel.Quick)
                        panelNote = null
                    },
                )
            }

            // 弹层一路铺到窗口底边，圆角开在顶上；底栏在这一层之下，被弹层盖住
            Box(modifier = Modifier.fillMaxSize()) {
                when (panel) {
                    Panel.Chapters -> ChaptersPanel(engine, theme,
                        onPick = { index ->
                            page = engine.jumpToChapter(index)
                            jumpToken++
                            panel = Panel.None
                        }, onClose = { panel = Panel.None })

                    Panel.Search -> SearchPanel(engine, prefs, theme,
                        onPick = { offset ->
                            page = engine.jumpToOffset(offset)
                            jumpToken++
                            panel = Panel.None
                        }, onClose = { panel = Panel.None })

                    Panel.Marks -> MarksPanel(engine, prefs, bookId, theme,
                        onPick = { pos ->
                            page = engine.restore(pos)
                            jumpToken++
                            panel = Panel.None
                        }, onClose = { panel = Panel.None })

                    Panel.Dict -> DictPanel(dictQuery, dictAnswer, dictBusy, theme, onClose = { panel = Panel.None })

                    Panel.Quick -> QuickSettingsPanel(
                        theme = theme,
                        brightness = brightness,
                        onBrightness = { brightness = it; prefs.brightness = it },
                        onFollowSystem = { brightness = -1f; prefs.brightness = -1f },
                        fontSizeSp = fontSizeSp,
                        onFontStep = { step ->
                            fontSizeSp = (fontSizeSp + step).coerceIn(12f, 34f)
                            prefs.fontSizeSp = fontSizeSp
                        },
                        fontLabel = fontLabel,
                        turnMode = turnMode,
                        onTurnMode = { mode ->
                            panelNote = null
                            if (mode != turnMode) {
                                // 从"上下"切到按页那一档：显示的是整页的页首，游标要跟到页首，
                                // 不然切回来时起锚在中间那一行，整屏往下跳
                                if (mode != TurnMode.Scroll) engine.snapToPageTop()
                                turnMode = mode
                                prefs.turnMode = mode.ordinal
                            }
                        },
                        onPickTheme = { picked ->
                            theme = picked
                            prefs.themeIndex = picked.ordinal
                            panelNote = null
                        },
                        onFonts = { panel = Panel.Fonts },
                        eyeCare = eyeCare,
                        onEyeCare = { eyeCare = !eyeCare; prefs.eyeCare = eyeCare },
                        onSearch = { panel = Panel.Search },
                        onMore = { panel = Panel.More },
                        note = panelNote,
                    )

                    Panel.More -> MoreSettingsPanel(
                        theme = theme,
                        linePreset = linePreset,
                        onLinePreset = { linePreset = it; prefs.linePreset = it },
                        marginPreset = marginPreset,
                        onMarginPreset = { marginPreset = it; prefs.marginPreset = it },
                        indent = indent,
                        onIndent = { indent = it; prefs.paragraphIndent = it },
                        keepOn = keepOn,
                        onKeepOn = { keepOn = it; prefs.keepScreenOn = it },
                        volumePaging = volumePaging,
                        onVolumePaging = { volumePaging = it; prefs.volumePaging = it },
                        bothSidesNext = bothSidesNext,
                        onBothSidesNext = { bothSidesNext = it; prefs.bothSidesNext = it },
                        restMinutes = restMinutes,
                        onRestMinutes = { restMinutes = it; prefs.restMinutes = it },
                        autoRead = autoRead,
                        onAutoRead = {
                            autoRead = it
                            prefs.autoRead = it
                        },
                        autoSpeedPreset = autoSpeedPreset,
                        onAutoSpeedPreset = { autoSpeedPreset = it; prefs.autoSpeedPreset = it },
                        traditional = traditional,
                        onTraditional = {
                            traditional = it
                            prefs.convertTraditional = it
                            jumpToken++ // 滚动那条缓冲里存的是切好的正文，换转换开关要重铺
                        },
                        traditionalAvailable = TraditionalText.available,
                        note = null,
                        onBack = { panel = Panel.Quick },
                    )

                    Panel.Fonts -> FontPickerPanel(
                        theme = theme,
                        selectedPath = fontState.value,
                        onPick = { font ->
                            fontState.value = font.path
                            fontLabel = font.label
                            prefs.fontPath = font.path
                            prefs.fontLabel = font.label
                            panel = Panel.Quick
                        },
                        onBack = { panel = Panel.Quick },
                    )

                    Panel.None -> Unit
                }
            }
        }
        }
    }

    if (restPrompt) {
        AlertDialog(
            onDismissRequest = { restPrompt = false },
            title = { Text("该休息一下") },
            text = { Text("已经连续阅读 $restMinutes 分钟。") },
            confirmButton = { TextButton(onClick = { restPrompt = false }) { Text("继续看") } },
        )
    }
}

private fun flip(current: Panel, target: Panel): Panel = if (current == target) Panel.None else target

/** MainActivity 实现它就能把音量键交给阅读页。 */
interface VolumeKeyHost {
    /**
     * @return true = 这一枪被翻页吃掉了；false = 我们不拦，交回系统去调音量。
     * 必须是 Boolean 而不是 Unit：听书正放着的时候音量键该管音量，
     * 而钩子是在 `DisposableEffect` 里一次性挂上去的，读不到当时的组合状态，只能由钩子自己回答。
     */
    var volumeHook: ((Boolean) -> Boolean)?
}

private fun colorSchemeOf(theme: ReaderTheme): ColorScheme {
    val primary = theme.accentColor
    val ink = theme.inkColor
    val bar = theme.barColor
    val page = theme.pageColor
    val faint = theme.faintColor
    return if (theme.night) {
        darkColorScheme(
            primary = primary, onPrimary = Color.White, background = page, onBackground = ink,
            surface = bar, onSurface = ink, surfaceVariant = bar, onSurfaceVariant = faint,
        )
    } else {
        lightColorScheme(
            primary = primary, onPrimary = Color.White, background = page, onBackground = ink,
            surface = bar, onSurface = ink, surfaceVariant = bar, onSurfaceVariant = faint,
        )
    }
}

@Composable
internal fun PanelTitle(text: String, theme: ReaderTheme, onClose: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(text, fontSize = 15.sp, color = theme.inkColor)
        Spacer(Modifier.weight(1f))
        // 不用 TextButton：它带 48dp 的最小可点尺寸并把内容居中，"关闭"右边就多出 52px（量过），
        // 和标题左边的 36px 对不上。这里要的是"右边的间隙和左边一样"。
        Box(modifier = Modifier.height(40.dp).tap(onClose), contentAlignment = Alignment.Center) {
            Text("关闭", fontSize = 14.sp, color = theme.accentColor)
        }
    }
}

@Composable
private fun BoxScope.ChaptersPanel(
    engine: ReaderEngine,
    theme: ReaderTheme,
    onPick: (Int) -> Unit,
    onClose: () -> Unit,
) {
    val listState = rememberLazyListState(initialFirstVisibleItemIndex = engine.currentChapterIndex)
    Column(
        modifier = Modifier
            .fillMaxHeight()
            .width(300.dp)
            .align(Alignment.CenterStart)
            // 目录是从左边推满高度的抽屉，露在外面的只有右边缘，所以圆角开在右侧
            .clip(RoundedCornerShape(topEnd = 16.dp, bottomEnd = 16.dp))
            .background(theme.barColor)
            .consumesPanelTaps(),
    ) {
        PanelTitle("目录 ${engine.chapterCount} 章", theme, onClose)
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxWidth().weight(1f),
            contentPadding = PaddingValues(bottom = 12.dp),
        ) {
            items(engine.book.chapters.chapters, key = { it.index }) { picked ->
                val current = picked.index == engine.currentChapterIndex
                // 不用 TextButton：它自带圆角的状态层，按下会浮一块圆角高亮。这行要的是"整条方方正正地响应"
                Row(
                    Modifier
                        .fillMaxWidth()
                        .height(44.dp)
                        .tap { onPick(picked.index) }
                        .padding(horizontal = 14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        picked.title,
                        fontSize = 14.sp,
                        color = if (current) theme.accentColor else theme.inkColor,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    Text(formatChars(picked.charCount), fontSize = 11.sp, color = theme.faintColor)
                }
            }
        }
    }
}

/** 搜索页的一行：正文命中和章名命中共用一个形状。 */
private class SearchRow(
    val title: String,
    val snippet: String,
    val offset: Long,
    val meta: String,
)

/**
 * 全书搜索：按参考图改成整屏的一页 —— 顶上输入框 + 叉，没搜之前列最近搜索，
 * 搜完表头变成"查询词 / 清除 / 叉"，下面一排分类页签，再下面是命中列表。
 * 页签只放真能筛的两类（正文命中、章名命中），参考图里那排"图片/文档/项目"我们没有，不摆。
 */
@Composable
private fun BoxScope.SearchPanel(
    engine: ReaderEngine,
    prefs: ReaderPrefs,
    theme: ReaderTheme,
    onPick: (Long) -> Unit,
    onClose: () -> Unit,
) {
    var query by remember { mutableStateOf("") }
    var result by remember { mutableStateOf<ScanResult?>(null) }
    var running by remember { mutableStateOf(false) }
    var tab by remember { mutableIntStateOf(0) }
    var recents by remember { mutableStateOf(prefs.recentSearches) }
    val scope = rememberCoroutineScope()
    val input = remember { FocusRequester() }
    // 进来就聚焦：参考图那页顶上就是光标在闪，不用先点一下输入框
    LaunchedEffect(Unit) {
        withFrameNanos { }
        input.requestFocus()
    }

    fun submit(q: String) {
        val t = q.trim()
        if (t.isEmpty() || running) return
        query = t
        recents = listOf(t) + recents.filter { it != t }
        prefs.recentSearches = recents
        running = true
        tab = 0
        scope.launch {
            result = withContext(Dispatchers.IO) {
                ChunkScanner(engine.book.cache, engine.book.chapters).scan(t, limit = 300)
            }
            running = false
        }
    }

    val textRows = remember(result) {
        result?.hits?.map {
            SearchRow(
                title = engine.book.chapters[it.chapterIndex].title,
                snippet = it.snippet,
                offset = it.offset,
                meta = "第 ${it.offset} 字",
            )
        } ?: emptyList()
    }
    // 章名命中不用扫全书：章表本来就在内存里
    val titleRows = remember(result) {
        val q = result?.query ?: ""
        if (q.isEmpty()) {
            emptyList()
        } else {
            engine.book.chapters.chapters.filter { it.title.contains(q) }.map {
                SearchRow(
                    title = it.title,
                    // formatChars 自己带单位（"1 万字" / "7697 字"），这里不能再补一个"字"
                    snippet = "第 ${it.index + 1} 章 · 全章 ${formatChars(it.charCount)}",
                    offset = it.startOffset,
                    meta = "章首",
                )
            }
        }
    }
    val rows = when (tab) {
        1 -> textRows
        2 -> titleRows
        else -> (textRows + titleRows).sortedBy { it.offset }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(theme.pageColor)
            .consumesPanelTaps(),
    ) {
        // 三样东西同一个高度、内容都居中：底对齐时"清除"和叉各自在 44dp 的框里居中，
        // 中心比查询词高出 38px，看着就是三行字。
        // 44dp = 文字行盒 44px 上下各留 38px，和发丝线下面"药丸顶到线"的 39px 对齐：
        // 那条线就是输入区和页签区的公共中分线，两边间隙一样。
        Row(
            Modifier.fillMaxWidth().height(44.dp).padding(start = 16.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            BasicTextField(
                value = query,
                onValueChange = {
                    query = it
                    // 改了词就把上一轮的结果撤掉：留着会看到"标题是新词、结果是旧词"
                    result = null
                },
                singleLine = true,
                textStyle = TextStyle(fontSize = 17.sp, color = theme.inkColor),
                cursorBrush = SolidColor(theme.accentColor),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { submit(query) }),
                modifier = Modifier.weight(1f).focusRequester(input),
                decorationBox = { inner ->
                    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.CenterStart) {
                        if (query.isEmpty()) Text("搜索…", fontSize = 17.sp, color = theme.faintColor)
                        inner()
                    }
                },
            )
            if (query.isNotEmpty()) {
                Box(
                    Modifier.fillMaxHeight().widthIn(min = 48.dp).tap {
                        query = ""
                        result = null
                    }.padding(horizontal = 10.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text("清除", fontSize = 14.sp, color = theme.faintColor)
                }
            }
            Box(Modifier.fillMaxHeight().width(44.dp).tap(onClose), contentAlignment = Alignment.Center) {
                Icon(
                    painter = painterResource(R.drawable.ic_close),
                    contentDescription = "关闭",
                    tint = theme.inkColor,
                    modifier = Modifier.size(20.dp),
                )
            }
        }
        // 1dp 在这台机上铺成 3 行（2.75 倍密度取整），要的是 1 个物理像素那条线
        Box(
            Modifier
                .fillMaxWidth()
                .height(with(LocalDensity.current) { 1.toDp() })
                .drawBehind {
                    drawLine(
                        theme.dividerColor,
                        Offset(0f, size.height / 2f),
                        Offset(size.width, size.height / 2f),
                        1f,
                    )
                },
        )

        if (result == null) {
            Row(
                Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, top = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    if (recents.isEmpty()) "输入要查找的词，按回车搜全书" else "最近搜索",
                    fontSize = 13.sp,
                    color = theme.faintColor,
                    modifier = Modifier.weight(1f),
                )
                if (recents.isNotEmpty()) {
                    // 整条清掉；单条的叉在每一行右边
                    Box(
                        Modifier.height(32.dp).widthIn(min = 48.dp).tap {
                            recents = emptyList()
                            prefs.recentSearches = emptyList()
                        }.padding(horizontal = 8.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text("清除历史", fontSize = 13.sp, color = theme.faintColor)
                    }
                }
            }
            LazyColumn(
                modifier = Modifier.fillMaxWidth().weight(1f),
                contentPadding = PaddingValues(bottom = 12.dp),
            ) {
                items(recents) { q ->
                    SearchLine(q, "", "", theme, onDelete = {
                        recents = recents.filter { it != q }
                        prefs.recentSearches = recents
                    }) { submit(q) }
                }
            }
        } else {
            val r = result!!
            // 居中：药丸和右边那句统计落在同一条中心线上（原来都对齐到行顶，两者差 17.5px）。
            // 只给上间隙、不给下间隙：行高固定 58dp 居中会在药丸下面再垫 36px，
            // 加上列表行自己的 8dp 内衬，量出来药丸底到第一条是 70px，比上面两处的 36/37px 大一倍
            Row(
                Modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp, top = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                listOf(
                    "全部" to (textRows.size + titleRows.size),
                    "正文" to textRows.size,
                    "章名" to titleRows.size,
                ).forEachIndexed { i, (label, n) ->
                    val on = i == tab
                    Box(
                        Modifier
                            .padding(end = 6.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .background(if (on) theme.barColor else Color.Transparent)
                            .widthIn(min = 62.dp)
                            .tap { tab = i },
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            "$label $n",
                            fontSize = 14.sp,
                            fontWeight = if (on) FontWeight.Medium else FontWeight.Normal,
                            color = if (on) theme.inkColor else theme.faintColor,
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                        )
                    }
                }
                Spacer(Modifier.weight(1f))
                Text(
                    if (running) "搜索中" else "扫了 ${formatChars(r.charsScanned)} / ${r.millis}ms",
                    fontSize = 12.sp,
                    color = theme.faintColor,
                    modifier = Modifier.padding(end = 4.dp),
                )
            }
            LazyColumn(
                modifier = Modifier.fillMaxWidth().weight(1f),
                contentPadding = PaddingValues(bottom = 12.dp),
            ) {
                items(rows) { row ->
                    SearchLine(row.title, row.snippet, row.meta, theme) { onPick(row.offset) }
                }
                if (rows.isEmpty()) {
                    item {
                        Text(
                            "这一类没有命中",
                            fontSize = 13.sp,
                            color = theme.faintColor,
                            modifier = Modifier.padding(start = 16.dp, top = 14.dp),
                        )
                    }
                }
            }
        }
    }
}

/** 搜索页的一行：左边气泡、中间标题 + 摘要、右边位置（或一颗删掉这条历史的叉）。
 * 标题不截断 —— 它是跳过去的目标。 */
@Composable
private fun SearchLine(
    title: String,
    snippet: String,
    meta: String,
    theme: ReaderTheme,
    onDelete: (() -> Unit)? = null,
    on: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 54.dp)
            .tap(on)
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            painter = painterResource(R.drawable.ic_row_chat),
            contentDescription = null,
            tint = theme.faintColor,
            modifier = Modifier.size(20.dp),
        )
        Column(Modifier.weight(1f).padding(start = 10.dp)) {
            Text(title, fontSize = 15.sp, color = theme.inkColor)
            if (snippet.isNotEmpty()) {
                Text(
                    snippet,
                    fontSize = 13.sp,
                    color = theme.faintColor,
                    lineHeight = 18.sp,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
        }
        if (meta.isNotEmpty()) {
            Text(meta, fontSize = 12.sp, color = theme.faintColor, modifier = Modifier.padding(start = 8.dp))
        }
        onDelete?.let { del ->
            Box(
                Modifier.fillMaxHeight().width(44.dp).tap(del),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    painter = painterResource(R.drawable.ic_close),
                    contentDescription = "删掉这条搜索记录",
                    tint = theme.faintColor,
                    modifier = Modifier.size(16.dp),
                )
            }
        }
    }
}

/**
 * 词典结果面板：AI 回的那三行（拼音 / 释义 / 译文）。
 * 外壳和书签、目录那一档同一份（底部推上来、16dp 顶角、`theme.barColor`、自己吃点击）。
 * 三行按"第一个冒号"拆成标签和值 —— 这是我们自己在提示词里要求的格式；它没照格式回的时候
 * 整行原样显示，不去猜，也不省略号收尾（查词的结果被截断等于这颗没做）。
 */
@Composable
private fun BoxScope.DictPanel(
    query: String,
    answer: String?,
    busy: Boolean,
    theme: ReaderTheme,
    onClose: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .fillMaxHeight(0.5f)
            .align(Alignment.BottomCenter)
            .clip(RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp))
            .background(theme.barColor)
            .consumesPanelTaps(),
    ) {
        PanelTitle("词典", theme, onClose)
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 19.dp)
                .padding(bottom = 16.dp),
        ) {
            Text(query, fontSize = 17.sp, fontWeight = FontWeight.Medium, color = theme.inkColor, lineHeight = 25.sp)
            Spacer(Modifier.height(10.dp))
            if (busy) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(14.dp),
                        strokeWidth = 1.8.dp,
                        color = theme.accentColor,
                    )
                    Spacer(Modifier.size(8.dp))
                    Text("正在查…", fontSize = 14.sp, color = theme.faintColor)
                }
            } else {
                (answer ?: "").lineSequence().map { it.trim() }.filter { it.isNotEmpty() }.forEach { line ->
                    val i = line.indexOf('：')
                    if (i in 1..6) {
                        Row(Modifier.fillMaxWidth().padding(bottom = 8.dp), verticalAlignment = Alignment.Top) {
                            Text(
                                line.substring(0, i),
                                fontSize = 13.sp,
                                color = theme.faintColor,
                                modifier = Modifier.width(46.dp),
                            )
                            Text(
                                line.substring(i + 1),
                                fontSize = 15.sp,
                                color = theme.inkColor,
                                lineHeight = 23.sp,
                                modifier = Modifier.weight(1f),
                            )
                        }
                    } else {
                        Text(line, fontSize = 15.sp, color = theme.inkColor, lineHeight = 23.sp, modifier = Modifier.padding(bottom = 8.dp))
                    }
                }
            }
        }
    }
}

/**
 * 听书浮窗：封面 + 暂停/继续 + 关。可以拖着走；翻页、点正文都不碰它，念完自己收。
 * 拖动只改 offset —— 按下去不给它任何几何反馈（这个项目里按下/悬停只许换颜色，不长宽、不变厚）。
 */
@Composable
private fun ListenFloat(
    theme: ReaderTheme,
    playing: Boolean,
    meta: BookMeta,
    title: String,
    modifier: Modifier = Modifier,
    onDrag: (Float, Float) -> Unit,
    onToggle: () -> Unit,
    onOpenPage: () -> Unit,
    onClose: () -> Unit,
) {
    // `pointerInput(Unit)` 的闭包抓的是**第一次组合**那份 `onDrag`，而 `onDrag` 里带着当时的 `at`
    // （默认左下角那一格）。所以拖动时每一枪都是"默认位置 + 这一帧的位移"，看着就是拖不动 ——
    // 用 rememberUpdatedState 把回调换成当下那一份
    val drag by rememberUpdatedState(onDrag)
    Row(
        modifier = modifier
            .size(width = 116.dp, height = 56.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(theme.barColor)
            // 拖动和"吃掉点击"必须在同一个手势循环里做：两颗 pointerInput 挂在同一个节点上会互相抢，
            // 结果是 detectTapGestures 先把 down 消费掉，detectDragGestures 再也等不到移动 —— 拖不动
            .pointerInput(Unit) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    while (true) {
                        // Initial 这一趟：孩子（封面/暂停/× 那三颗 clickable）还没机会把这一枪消费掉。
                        // 走 Main 的话子级一旦 consume，父级读到的 position 差值就是 0 —— 表现就是拖不动
                        val e = awaitPointerEvent(PointerEventPass.Initial)
                        val c = e.changes.firstOrNull { it.id == down.id } ?: break
                        if (!c.pressed) break
                        // 手算位移：`positionChange()` 在这版 Compose 里和同名 Boolean 属性撞了
                        val d = Offset(c.position.x - c.previousPosition.x, c.position.y - c.previousPosition.y)
                        if (d != Offset.Zero) drag(d.x, d.y)
                        c.consume()
                        if (e.changes.none { it.pressed }) break
                    }
                }
            }
            .padding(start = 8.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // 浮窗上这一小格和书架、听书页是同一颗：抓到过封面就显示那张，没有就按书名哈希取色
        BookCover(
            meta.title, meta.id,
            Modifier.size(width = 32.dp, height = 44.dp).clip(RoundedCornerShape(3.dp)).tap(onOpenPage),
            radius = 3.dp,
            titleOn = false,   // 这一小格只有 32x44dp，写书名会挤成一列竖字
        )
        Spacer(Modifier.size(8.dp))
        Box(
            modifier = Modifier.size(32.dp).clip(CircleShape).background(theme.pageColor).tap(onToggle),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                painter = painterResource(if (playing) R.drawable.ic_pause else R.drawable.ic_play),
                contentDescription = if (playing) "暂停" else "继续",
                tint = theme.inkColor,
                modifier = Modifier.size(17.dp),
            )
        }
        // 原来这里是一条 weight(1f) 把 × 顶到最右边，两颗之间空出一大截；改成固定 2dp
        Spacer(Modifier.size(2.dp))
        Box(
            modifier = Modifier.size(width = 26.dp, height = 40.dp).tap(onClose),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                painter = painterResource(R.drawable.ic_close),
                contentDescription = "停止听书",
                tint = theme.faintColor,
                modifier = Modifier.size(16.dp),
            )
        }
    }
}

@Composable
private fun BoxScope.MarksPanel(
    engine: ReaderEngine,
    prefs: ReaderPrefs,
    bookId: String,
    theme: ReaderTheme,
    onPick: (BookPosition) -> Unit,
    onClose: () -> Unit,
) {
    var marks by remember { mutableStateOf(prefs.bookmarks(bookId)) }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .fillMaxHeight(0.6f)
            .align(Alignment.BottomCenter)
            .clip(RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp))
            .background(theme.barColor)
            .consumesPanelTaps(),
    ) {
        PanelTitle("书签 ${marks.size} 条", theme, onClose)
        LazyColumn(
            modifier = Modifier.fillMaxWidth().weight(1f),
            contentPadding = PaddingValues(start = 10.dp, end = 10.dp, top = 2.dp, bottom = 12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(marks, key = { it.absoluteOffset }) { pos ->
                var open by remember(pos) { mutableStateOf(false) }
                val full = engine.book.cache.slice(pos.absoluteOffset, 800).substringBefore('\n')
                // 只有"折起来确实被截掉了一段"才允许展开：两行放得下的内容点开箭头什么也不会多出来
                var overTwo by remember(pos) { mutableStateOf(false) }
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(10.dp))
                        .background(theme.pageColor)
                        // 整张卡片 = 跳转；第一行右边那颗箭头 = 展开整段。删书签在读书时长按那一段来做
                        .tap { onPick(pos) }
                        .padding(start = 14.dp, end = 6.dp, top = 8.dp, bottom = 8.dp),
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            "${engine.book.chapters[pos.chapterIndex].title} · 第 ${pos.absoluteOffset} 字",
                            fontSize = 12.sp,
                            color = theme.faintColor,
                            modifier = Modifier.weight(1f),
                        )
                        // 箭头跟着第一行走：它单独占一行时，卡片底下凭空多出一截空白
                        Box(
                            modifier = Modifier.size(40.dp).tap(enabled = overTwo) { open = !open },
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(
                                painter = painterResource(R.drawable.ic_expand),
                                contentDescription = if (open) "收起" else "展开",
                                // 不足两行时置灰（不藏起来：藏了别人以为这里本来就没有控件）
                                tint = if (overTwo) theme.faintColor else theme.faintColor.copy(alpha = 0.35f),
                                modifier = Modifier
                                    .size(18.dp)
                                    .graphicsLayer { rotationZ = if (open) 180f else 0f },
                            )
                        }
                    }
                    Text(
                        full,
                        fontSize = 13.sp,
                        color = theme.inkColor,
                        lineHeight = 19.sp,
                        maxLines = if (open) Int.MAX_VALUE else 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(top = 2.dp),
                        onTextLayout = { r ->
                            // 只在折着的时候判：展开后末行当然报到文本末尾，会把标记冲成 false、再也收不回去。
                            // 判据是"末行可见结束下标 < 文本长度"，不是 lineCount > 2 ——
                            // 真机 log 过：maxLines=2 时 lineCount 永远等于 2，那条判据会把每颗箭头都判成禁用
                            if (!open) {
                                overTwo = r.getLineEnd(r.lineCount - 1, visibleEnd = true) < full.length
                            }
                        },
                    )
                }
            }
        }
    }
}

/**
 * 翻页中途要同时画两页。版心内缩和静态页用同一份 padding，动画层只管位移/旋转，
 * 所以 graphicsLayer 必须套在 padding 外面 —— 否则转的是"内容框"，书脊会跟着内缩量漂。
 */
@Composable
private fun TurnLayer(
    page: Page?,
    pageText: String,
    style: PageStyle,
    theme: ReaderTheme,
    typeface: android.graphics.Typeface?,
    marginDp: Float,
    slackDp: androidx.compose.ui.unit.Dp,
    selection: LongRange?,
    selectionColor: Int,
    marks: List<LongRange>,
    markColor: Int,
    styles: com.bookreader.core.model.StyleIndex,
    modifier: Modifier,
    paper: Color,
) {
    PageView(
        page = page,
        pageText = pageText,
        pageStart = page?.startOffset ?: 0L,
        style = style,
        theme = theme,
        typeface = typeface,
        selection = selection,
        selectionColor = selectionColor,
        marks = marks,
        markColor = markColor,
        // 这一行不转就等于整条按页那档没有行内样式：PageView 的默认值是空索引，
        // 编译和判据都不会红，只有把同一组词量一遍才发现粗体没画上去（真机量过：两组都是 22.54%）
        styles = styles,
        paper = paper,
        modifier = Modifier
            .fillMaxSize()
            .then(modifier)
            .padding(start = marginDp.dp, end = marginDp.dp, top = slackDp),
    )
}

/**
 * 菜单收下去之后那一条淡出要跟着系统栏走到 ~1.2 秒，这一会儿那两条栏**还画着**，
 * 但不能再生成"点得到"的样子：命中窗口跟着 `on`（= `bars`），不跟着那条淡出曲线。
 *
 * `on` 为真时这里一个事件都不接 —— 栏自己的按钮照旧。为假时在 **Initial pass** 就把这一枪吃掉：
 * 父层只挂 `detectTapGestures` 拦不住孩子（Main 那一轮孩子是先拿到事件的，真机就是这么退到书架的），
 * 而 Initial 的消费会带到后面每一轮，孩子那一层就再也起不了 `awaitFirstDown`。
 * 抬指时位移没过 touchSlop 才按正文那套判据补发一次（左/右三分之一翻页、中间把菜单开回来）：
 * 菜单一收，整屏就都是翻页表面，正在淡的那两条栏底下不藏任何还能点的控件。
 */
private fun Modifier.fadingTap(on: Boolean, onTap: (widthPx: Int, x: Float) -> Unit): Modifier =
    pointerInput(on) {
        if (!on) awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
            down.consume()
            var moved = 0f
            while (true) {
                val e = awaitPointerEvent(PointerEventPass.Initial)
                val c = e.changes.firstOrNull { it.id == down.id } ?: break
                moved = moved.coerceAtLeast((c.position - down.position).getDistance())
                c.consume()
                if (!c.pressed) break
            }
            if (moved < viewConfiguration.touchSlop) onTap(size.width, down.position.x)
        }
    }
