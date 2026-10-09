package com.bookreader.ui

import android.app.Activity
import android.util.Log
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
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
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.Icon
import androidx.compose.ui.res.painterResource
import androidx.compose.foundation.layout.size
import androidx.compose.ui.text.style.TextOverflow
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
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.drawBehind
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
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.bookreader.core.engine.PrefetchManager
import com.bookreader.core.engine.PageTurn
import com.bookreader.core.engine.ReaderEngine
import com.bookreader.core.engine.ReaderGestures
import com.bookreader.core.engine.TapZone
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

private enum class Panel { None, Chapters, Search, Marks, Quick, More, Fonts }

internal const val TAG_PERF = "ReaderPerf"

/** 顶栏和底栏各自占多高。正文区永远按这两个数预留，菜单开合就不动排版。 */
const val TOP_BAND_DP = 44f
const val BOTTOM_BAND_DP = 58f

@Composable
fun ReaderScreen(
    bookId: String,
    library: BookLibrary,
    prefs: ReaderPrefs,
    onBack: () -> Unit,
) {
    val density = LocalDensity.current
    val screen = LocalConfiguration.current
    val view = LocalView.current
    val activity = view.context as? Activity

    var theme by remember { mutableStateOf(ReaderTheme.of(prefs.themeIndex)) }
    var fontSizeSp by remember { mutableStateOf(prefs.fontSizeSp) }
    var linePreset by remember { mutableStateOf(prefs.linePreset) }
    var marginPreset by remember { mutableStateOf(prefs.marginPreset) }
    var indent by remember { mutableStateOf(prefs.paragraphIndent) }
    var keepOn by remember { mutableStateOf(prefs.keepScreenOn) }
    var volumePaging by remember { mutableStateOf(prefs.volumePaging) }
    var bothSidesNext by remember { mutableStateOf(prefs.bothSidesNext) }
    var eyeCare by remember { mutableStateOf(prefs.eyeCare) }
    var restMinutes by remember { mutableStateOf(prefs.restMinutes) }
    var autoRead by remember { mutableStateOf(prefs.autoRead) }
    var autoSpeedPreset by remember { mutableStateOf(prefs.autoSpeedPreset) }
    var traditional by remember { mutableStateOf(prefs.convertTraditional) }
    var brightness by remember { mutableStateOf(prefs.brightness) }
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
    val context = LocalContext.current
    val voice = remember { ParagraphVoice(context) }
    DisposableEffect(voice) { onDispose { voice.close() } }
    val selectionColor = theme.inkColor.copy(alpha = 0.18f).toArgb()
    val markColor = theme.accentColor.copy(alpha = 0.9f).toArgb()
    var restPrompt by remember { mutableStateOf(false) }
    val fontState = remember { mutableStateOf(prefs.fontPath) }
    var fontLabel by remember { mutableStateOf(prefs.fontLabel) }
    val typeface = remember(fontState.value) { DeviceFonts.typefaceOf(fontState.value) }

    val engine = remember(bookId) {
        val start = prefs.pageStyle(
            widthPx = screen.screenWidthDp * density.density,
            heightPx = screen.screenHeightDp * density.density,
            pxPerDp = density.density,
            fontScale = density.fontScale,
        )
        ReaderEngine(library.open(bookId), { PaintBreaker(it, DeviceFonts.typefaceOf(fontState.value) ?: android.graphics.Typeface.DEFAULT) }, start)
            .also { e -> prefs.loadPosition(bookId)?.let { p -> e.restore(p) } }
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
    ApplySystemBars(theme.page, theme.page, darkIcons = !theme.night)
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
    val style = prefs.pageStyle(
        widthPx = if (viewport.width > 0) viewport.width.toFloat() else screen.screenWidthDp * density.density,
        heightPx = if (viewport.height > 0) viewport.height.toFloat() else screen.screenHeightDp * density.density,
        pxPerDp = density.density,
        fontScale = density.fontScale,
        linePreset = linePreset,
        marginPreset = marginPreset,
        paragraphIndent = indent,
    ).copy(
        fontSizePx = fontSizeSp * density.density * density.fontScale,
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
        if (selection != null) {
            selection = null
            selectionRect = null
            selectionNote = null
            voice.stop()
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
            TapZone.ToggleBars -> bars = true
        }
    }

    LaunchedEffect(page) {
        page?.let {
            prefetch.prefetch(engine, 6)
            prefs.savePosition(bookId, engine.position())
        }
    }

    DisposableEffect(volumePaging) {
        val host = activity as? VolumeKeyHost
        if (host == null || !volumePaging) {
            onDispose { }
        } else {
            // 音量键翻页时菜单/弹层先收起来：手在侧边按键，屏幕上还压着一层面板没意义
            host.volumeHook = { up ->
                if (panel != Panel.None) {
                    panel = Panel.None
                    panelNote = null
                }
                bars = false
                turn(!up)
            }
            onDispose { host.volumeHook = null }
        }
    }

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

    // 系统返回键：先收面板，再收菜单，最后回书架，不直接退程序
    BackHandler(enabled = true) {
        when {
            selection != null -> {
                selection = null
                selectionNote = null
                voice.stop()
            }

            panel != Panel.None -> {
                panel = if (panel == Panel.More || panel == Panel.Fonts) Panel.Quick else Panel.None
            }

            bars -> bars = false
            else -> onBack()
        }
    }

    val pagesInChapter = remember(page) { engine.pagesInChapter() }
    val chapter = engine.chapter
    val percent = (engine.progress() * 1000).roundToInt() / 10f

    MaterialTheme(colorScheme = colorSchemeOf(theme)) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(theme.pageColor)
                .safeDrawingPadding(),
        ) {
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
                                    Modifier.graphicsLayer { translationX = outX * viewport.width })
                                TurnLayer(page, pageText, engine.style, theme, typeface, marginDp, slack, selection, selectionColor, markRanges, markColor, engine.book.styles,
                                    Modifier.graphicsLayer { translationX = inX * viewport.width })
                            }
                            // 仿真：都绕左边的书脊转。往前翻是把旧页掀起来（0→-90°），往后翻是把新页铺回去（-90°→0）
                            turning && turnMode == TurnMode.Cover -> {
                                val lift = PageTurn.coverLift(p, turnForward)
                                val under = if (turnForward) page to pageText else out
                                val onTop = if (turnForward) out else page to pageText
                                TurnLayer(under.first, under.second, engine.style, theme, typeface, marginDp, slack, selection, selectionColor, markRanges, markColor, engine.book.styles, Modifier)
                                TurnLayer(onTop.first, onTop.second, engine.style, theme, typeface, marginDp, slack, selection, selectionColor, markRanges, markColor, engine.book.styles,
                                    Modifier.graphicsLayer {
                                        rotationY = PageTurn.coverAngle(lift)
                                        transformOrigin = TransformOrigin(0f, 0.5f)
                                        cameraDistance = 16f * density.density
                                        alpha = 1f - 0.3f * lift
                                    })
                            }
                            else -> TurnLayer(page, pageText, engine.style, theme, typeface, marginDp, slack, selection, selectionColor, markRanges, markColor, engine.book.styles, Modifier)
                        }
                    }
                }
            selection?.let { range ->
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
                        Text(it, fontSize = 12.sp, color = theme.accentColor, modifier = Modifier.padding(bottom = 6.dp))
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
                                    val text = engine.book.cache.slice(range.first, (range.last - range.first).toInt())
                                    selectionNote = when {
                                        voice.speak(text) -> "正在朗读这一段"
                                        voice.state == 0 -> "语音引擎正在启动，再点一次听书"
                                        else -> "这台机没有可用的中文语音引擎"
                                    }
                                }
                                SelectionAction.Comment -> selectionNote = "评论要接书源服务端才有地方存"
                                SelectionAction.Dictionary -> selectionNote = "词典要词库，本机没接"
                                SelectionAction.Correct -> selectionNote = "纠错要能提交回书源，本机没接"
                            }
                        },
                    )
                    if (barAbove) SelectionArrow(theme.barColor, down = true)
                }
            }

                if (eyeCare) {
                    Box(Modifier.fillMaxSize().background(Color(0x26FF9A3C)))
                }
            }

            if (bars) {
                Row(
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .fillMaxWidth()
                        .height(TOP_BAND_DP.dp)
                        .background(theme.pageColor)
                        .drawBehind {
                            // 栏和正文同色，只剩这条发丝线说"这里有个栏"；1dp 在这台机渲成 3px
                            drawLine(theme.dividerColor, Offset(0f, size.height - 1f), Offset(size.width, size.height - 1f), 1f)
                        }
                        .padding(horizontal = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    BackChevron(theme, onBack)
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
                    modifier = Modifier.align(Alignment.BottomCenter),
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
    var volumeHook: ((Boolean) -> Unit)?
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
private fun PanelTitle(text: String, theme: ReaderTheme, onClose: () -> Unit) {
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
    move: Modifier,
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
        modifier = Modifier
            .fillMaxSize()
            .then(move)
            .padding(start = marginDp.dp, end = marginDp.dp, top = slackDp),
    )
}
