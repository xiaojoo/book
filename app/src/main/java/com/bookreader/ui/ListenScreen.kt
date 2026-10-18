package com.bookreader.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.Row
import androidx.compose.ui.unit.IntOffset
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.bookreader.R
import com.bookreader.core.model.BookMeta
import com.bookreader.core.layout.sentenceIndexByChars
import com.bookreader.core.layout.sentenceStarts
import com.bookreader.data.AiVoicePlayer
import kotlinx.coroutines.delay
import kotlin.math.roundToInt

/** 语速档位。0.75~2.0 是 `MediaPlayer.playbackParams` 支持的范围。 */
private val RATES = listOf(0.75f, 1.0f, 1.25f, 1.5f, 2.0f)
private val SLEEP_MINUTES = listOf(0, 10, 20, 30, 60)

/**
 * 听书页：点浮窗上的封面进这里。
 *
 * **这一屏不藏系统栏**：上一版按"参考图里没有那两条"把状态栏/导航栏 `hide()` 掉了，结果他报回来
 * 两条新问题 —— 那两条变成黑底，而阅读页的顶/底栏又压在听书页上面。黑底这一步**没抓到那一帧**，
 * 只是排除了两个候选：`BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE` 下"上滑短暂现身"的那两条平台本来就画成
 * 半透明深底；而我们的 Activity 是 `Theme.Material.Light.NoActionBar`，它的 `statusBarColor` 是平台灰
 * （量过 #757575），`show()` 之后有没有把它读回去没验过。他要的其实是"那两条的颜色和这一屏一致"，
 * 不是没有那两条，所以这里只涂色、不藏。
 *
 * **那几颗都做了实**：定时（到点自己停，倒计时活在这一屏之外，收起也继续走）、
 * 语速（`playbackParams` 本地变速 —— 音频已经在手里，不重新合成、不再花一次钱）、
 * 原文（图标，点它收起这一屏并把正文翻到正在念的那一段）、更多（连续播放 / 自动下一章 / 允许其他应用一起播 /
 * 挑音色 / 换背景 / 缓存历史 / 听书纠错 / 重新生成 —— 这一层没有标题也没有右上角的叉，点外面就收）。
 * 音色这一列来自设置页「可选音色」那一格（[voices]）：不在那一列里的音色这里选不到。
 * 没做实的是**目录**（你说先静态 —— 图标和字都在，点下去只回一句实话，不假装能跳章）
 * 和参考图里没圈的那两颗（评论、月票：没有书源账号也没有评论后端，摆上去就是死控件）。
 */
@Composable
fun ListenScreen(
    theme: ReaderTheme,
    chapterTitle: String,
    bookTitle: String,
    meta: BookMeta,
    paragraph: String,
    busy: Boolean,
    playing: Boolean,
    hasPrev: Boolean,
    hasNext: Boolean,
    player: AiVoicePlayer,
    rate: Float,
    sleepEndsAt: Long,
    voiceId: String,
    voices: List<String>,
    autoNext: Boolean,
    autoNextChapter: Boolean,
    shareFocus: Boolean,
    onCollapse: () -> Unit,
    onToggle: () -> Unit,
    onStop: () -> Unit,
    onPrev: () -> Unit,
    onNext: () -> Unit,
    onRate: (Float) -> Unit,
    onOriginal: () -> Unit,
    onSleep: (Int) -> Unit,
    onResynth: () -> Unit,
    onVoice: (String) -> Unit,
    onPaper: (ReaderTheme) -> Unit,
    /**
     * 「我的 → 皮肤管理」那张背景图（已按屏宽缩好的位图；没选图 = null）和压在上头那档纸色的浓淡。
     *
     * 从阅读屏传进来，不是这一屏自己去读 prefs：缩那一步要按**视口宽**算，而视口只在阅读屏那一头量着；
     * 两处各解一次还会得到两张不同尺寸的位图，同一张图在两屏上深浅就不一样了。
     */
    bgBmp: androidx.compose.ui.graphics.ImageBitmap?,
    bgDim: Float,
    onAutoNext: (Boolean) -> Unit,
    onAutoNextChapter: (Boolean) -> Unit,
    onShareFocus: (Boolean) -> Unit,
    onHistory: () -> Unit,
    onFixes: () -> Unit,
    onToc: () -> Unit,
) {
    // 那两条留着，但底色必须和这一屏同一个色（不涂的话回到阅读页那一侧，它们是平台灰）
    ApplySystemBars(darkIcons = !theme.night, bgArgb = theme.page)

    // 进度靠轮询：MediaPlayer 没有可观察状态
    var pos by remember { mutableIntStateOf(0) }
    var dur by remember { mutableIntStateOf(0) }
    LaunchedEffect(busy, playing) {
        while (true) {
            pos = player.positionMs
            dur = player.durationMs
            delay(250)
        }
    }
    var sleepOpen by remember { mutableStateOf(false) }
    var moreOpen by remember { mutableStateOf(false) }
    var note by remember { mutableStateOf<String?>(null) }
    val leftMin = if (sleepEndsAt > 0L)
        ((sleepEndsAt - System.currentTimeMillis() + 59_000L) / 60_000L).toInt().coerceAtLeast(1) else 0

    // 正文跟着播放走：读过的要藏起来、没读的露出来。这三家 TTS 的 HTTP 口都不回字级时间戳，
    // 所以"念到第几句"只能按"已过时长 / 总时长"折算 —— 折算那一步**按每句的字数分摊**，
    // 不是按句数等分（等分等于假定每句一样长，真段落里段内最长句能是最短句的 14 倍，
    // 量过的偏差：段内最大中位 6.8 秒、最大 23.6 秒 = 偏到 2.4 句）。数字与口径见 `sentenceIndexByChars`
    val frac = if (dur > 0) (pos.toFloat() / dur).coerceIn(0f, 1f) else 0f
    val winScroll = rememberScrollState()
    val starts = remember(paragraph) { sentenceStarts(paragraph) }
    val idx = sentenceIndexByChars(paragraph, starts, frac)
    // 窗口两行高。这段比两行高才谈得上"滚"；比两行矮的话根本没有可滚的（maxValue=0），
    // 那就改成把念过的那几句直接切掉 —— 不然整段只有两三行的时候这一屏从头到尾不动
    val winPx = with(LocalDensity.current) { 25.sp.toPx() * 2f }
    var taller by remember { mutableStateOf(false) }
    // 排版结果放在普通数组里而不是 State：它只被下面这个 effect 读，挂成可观察状态会在每次排版时
    // 多推一轮组合，而 onTextLayout 本身就是在排版过程里回调的
    val laidOut = remember { arrayOfNulls<TextLayoutResult>(1) }
    // 排版结果到了要能"叫醒"下面那个 effect：effect 是在第一次布局**之前**跑的，那时
    // laidOut[0] 还是 null 就直接 return —— 如果这一段之后句序再也不变（比如从半路打开听书页、
    // 或者整段只有两句而播放已经过了中点），就再也没有下一次触发，那一段就永远不滚了。
    // 真机抓到：pos 15751→19544（共 20480）、可滚 169、滚到一直是 0。
    // 只在高度真的变了才 +1：不写高度判断的话每次布局都推一轮组合，白耗
    var laidOutTick by remember { mutableIntStateOf(0) }
    var lastH by remember { mutableIntStateOf(-1) }
    // 只在**换句**那一刻动一次，不跟着 250ms 的进度轮询重发：
    // 跟着轮询的话他自己用手指拨那两行，会被下一枪的 scrollTo 顶回去，看着就是"字排突然变了一下"
    val laidOutTick0 = laidOutTick
    // 每次滚动 = 一个行高。目标和触发键都按"正在念的那一行"算：行顶之间的差正好是一个行高
    // （正文 lineHeight 25.sp、窗口 25.sp×2 = 两行），所以一步一行，不会出现半行的挪动。
    // 正在念的那个字：有句末标点取句首；整段一句话说到底的（真机抓到的是 句 0/1 这种）按进度×段长折算。
    // 原来这一条用的是 maxValue×进度 —— 可滚 113px 折成 6 档、每档挪 18px，不到半个行高，
    // 就是他报的"滚动的高度小了"
    val readingAt = if (starts.size > 1) starts[idx]
    else (paragraph.length * frac).toInt().coerceIn(0, (paragraph.length - 1).coerceAtLeast(0))
    // 键取行号而不是句序：同一行里换句根本不该动（动了就是把他手指那两行顶回去），行变了才动一次
    val step = laidOut[0]?.getLineForOffset(readingAt) ?: 0
    LaunchedEffect(step, paragraph, taller, laidOutTick0) {
        val l = laidOut[0] ?: return@LaunchedEffect   // 第一次组合还没量出来，布局那一枪会再来一次
        if (!taller) return@LaunchedEffect            // 装不满窗口的段靠"切掉已读"表现进度，不滚
        // 把正在念的那一行顶到窗口上沿。animateScrollTo 自己会夹进 0..maxValue，
        // 末几行的行顶落在窗口下沿之外时不用在这里补边界
        val target = l.getLineTop(l.getLineForOffset(readingAt)).roundToInt()
        // 他手指还按着 / 惯性还在甩的时候不抢：等到停下来再补这一次（最多等 4 秒，之后放弃这一枪）。
        // 注意不能把"正在拖"当成 effect 的键 —— `isScrollInProgress` 在**我们自己**那段 420ms 动画
        // 期间也是 true，拿它当键会自己把自己重启掉，动画被取消、滚到一直是 0（真机日志抓到的就是这条）
        var waited = 0
        while (winScroll.isScrollInProgress && waited < 40) {
            delay(100)
            waited++
        }
        if (waited >= 40) return@LaunchedEffect
        val from = winScroll.value
        winScroll.animateScrollTo(target, animationSpec = tween(durationMillis = 420, easing = FastOutSlowInEasing))
        android.util.Log.d(
            "ListenScroll",
            "滚：行 $step/${l.lineCount} 起于 $from 到达 ${winScroll.value} 位移 ${winScroll.value - from} " +
                "行高=${(l.getLineTop(1) - l.getLineTop(0)).roundToInt()}px 可滚=${winScroll.maxValue} pos=$pos dur=$dur",
        )
    }
    // 心跳：每 2 秒把这一屏"该不该滚"的全部输入吐一遍。上面那条只在真的滚了的时候才打，
    // 而"没滚"恰恰是什么都不打 —— 分不清是句序没推进、还是这段没超过两行、还是时长读成 0
    LaunchedEffect(paragraph) {
        while (true) {
            // 句序在这里现算：闭包里抓到的 idx 是"这一轮启动那一刻"的旧值，上次就是被它骗过一次。
            // 也必须和上面那一处同一个函数，否则日志报的句序和屏上亮的不是同一把尺子
            val f = if (dur > 0) (pos.toFloat() / dur).coerceIn(0f, 1f) else 0f
            val live = sentenceIndexByChars(paragraph, starts, f)
            // 正在念的那一行也现算：滚动单位已经从"句"换成"行"，心跳里得能看到同一把尺子
            val ll = laidOut[0]
            val off = if (starts.size > 1) starts[live] else (paragraph.length * f).toInt().coerceIn(0, (paragraph.length - 1).coerceAtLeast(0))
            android.util.Log.d(
                "ListenScroll",
                "句 $live/${starts.size} 行 ${ll?.getLineForOffset(off)}/${ll?.lineCount} " +
                    "taller=$taller 文高=${laidOut[0]?.size?.height} 窗口=${winPx.toInt()} " +
                    "滚到=${winScroll.value} 可滚=${winScroll.maxValue} pos=$pos dur=$dur " +
                    "拖=${winScroll.isScrollInProgress} 段长=${paragraph.length}",
            )
            delay(2000)
        }
    }
    /**
     * 窗口里那一串字，和**正在念的那一句**在其中的位置。
     *
     * 比两行高的段落走"滚"：整段都在，窗口滚到当前那一句。比两行矮的没有可滚的（maxValue=0），
     * 就把念过的那几句直接切掉 —— 两种情况下都把正在念的那一句染成强调色：不然整屏一个颜色，
     * "念到哪"只能靠滚动位置猜，而那正是他要看的对齐。口径和有声书跟读、目录抽屉同一条：
     * **只换字色，不加底色框**。
     */
    val shownFrom = if (taller) 0 else starts[idx]
    val shownText = if (taller) paragraph else paragraph.substring(starts[idx])
    val curFrom = starts[idx] - shownFrom
    val curTo = (if (idx + 1 < starts.size) starts[idx + 1] else paragraph.length) - shownFrom
    val shown = buildAnnotatedString {
        withStyle(SpanStyle(color = theme.inkColor)) { append(shownText.substring(0, curFrom)) }
        withStyle(SpanStyle(color = theme.accentColor)) { append(shownText.substring(curFrom, curTo)) }
        withStyle(SpanStyle(color = theme.inkColor)) { append(shownText.substring(curTo)) }
    }

    // 底色纸要先铺**不透明**的一层：听书页是盖在阅读屏正文上面的，只叠图和半透明那层纸会把身后的字透出来。
    // 图 + 压淡那一层和阅读屏同一份实现（`PaperBackground`），换纸色/换浓淡两屏一起换。
    Box(Modifier.fillMaxSize().background(theme.pageColor)) {
        PaperBackground(theme, bgBmp, bgDim)
        // 内容这一格才让开安全区：上面那层（底色纸 + 背景图）要铺到状态栏和导航条底下，
        // 而栏、进度条、抽屉仍然待在安全区里侧（和阅读屏同一个两层结构、同一个 fixedBarsPadding）
        Box(Modifier.fillMaxSize().fixedBarsPadding()) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 22.dp)
                    .pointerInput(Unit) { detectTapGestures { } },
            ) {
                // 这一屏不再藏状态栏，那 24dp 是藏栏时代留的，现在叠在状态栏下面太宽 —— 上下都收到一半
                Spacer(Modifier.height(12.dp))
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Box(modifier = Modifier.size(40.dp).tap(onCollapse), contentAlignment = Alignment.CenterStart) {
                        Icon(
                            painter = painterResource(R.drawable.ic_chevron_down),
                            contentDescription = "收起",
                            tint = theme.inkColor,
                            // 这个画形的墨左沿在 24 格视口的 5.64 处（path 里读出来的），按 22dp 画就白让出
                            // 5.17dp = 14px。不补这一笔，它和下面的标题差 14px —— 他要的是同一条左线
                            modifier = Modifier.size(22.dp).offset(x = (-5.2).dp),
                        )
                    }
                    Spacer(Modifier.weight(1f))
                    Box(modifier = Modifier.size(40.dp).tap(onStop), contentAlignment = Alignment.CenterEnd) {
                        Icon(
                            painter = painterResource(R.drawable.ic_close),
                            contentDescription = "停止听书",
                            tint = theme.faintColor,
                            // 左边那颗 ˅ 的墨左沿是贴着内容左线的（间隙 0），这颗要一样：
                            // ic_close 的墨在 24 格视口的 5.1..18.9（描边 1.8 + 圆头），18dp 下右沿白让
                            // 18×(24−18.9)/24 = 3.8dp。贴右沿摆框再补这一笔，右边间隙才是 0
                            modifier = Modifier.size(18.dp).offset(x = 3.8.dp),
                        )
                    }
                }

                Spacer(Modifier.height(10.dp))
                Text(chapterTitle, fontSize = 22.sp, fontWeight = FontWeight.Medium, color = theme.inkColor)
                Text(bookTitle, fontSize = 14.sp, color = theme.faintColor, modifier = Modifier.padding(top = 4.dp))

                Spacer(Modifier.weight(1f))
                Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                    // 封面三档退：抓来的 cover.jpg > 样例图 > 按书名哈希取色的默认封面（和书架同一颗）
                    BookCover(meta.title, meta.id, Modifier.size(width = 170.dp, height = 227.dp), radius = 6.dp)
                }
                Spacer(Modifier.weight(1f))

                // 窗口固定两行高，里面铺的是整段：正在念的那一句顶到上沿，读过的滚出上面、没读的露出来
                // —— 要看全文点右边那颗「原文」
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .height(with(LocalDensity.current) { winPx.toDp() })
                            .verticalScroll(winScroll)
                            // 上下各 8dp 淡出：滚到边缘的那半行不是被"切"掉，是淡出去
                            // （和有声书跟读格共用一份 `edgeFade`，两屏边缘同一条观感）
                            .edgeFade(8.dp),
                    ) {
                        Text(
                            shown,
                            fontSize = 15.sp,
                            color = theme.inkColor,
                            lineHeight = 25.sp,
                            onTextLayout = {
                                laidOut[0] = it
                                // 只在"比两行高"这件事变了的时候写状态：每帧无脑写会把排版再推一轮
                                val t = it.size.height > winPx + 0.5f
                                if (t != taller) taller = t
                                if (it.size.height != lastH) {
                                    lastH = it.size.height
                                    laidOutTick++
                                }
                            },
                        )
                    }
                    Spacer(Modifier.width(10.dp))
                    Box(
                        modifier = Modifier
                            .size(40.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .tap(onOriginal),
                        // 这颗要和下面那行「目录」的图标右沿同一条线（命中框还是 40dp，只挪墨）：
                        // 目录那颗 = 行右 − 6dp 内衬(16.5px) − ic_reader_toc 的墨右余量 7.6px = 行右 − 24.1px；
                        // 这颗贴右沿摆 = 行右 − ic_original 的墨右余量 10.1px ⇒ 再往左补 5.1dp 才对上
                        contentAlignment = Alignment.CenterEnd,
                    ) {
                        Icon(
                            painter = painterResource(R.drawable.ic_original),
                            contentDescription = "原文",
                            tint = theme.faintColor,
                            modifier = Modifier.size(22.dp).offset(x = (-5.1).dp),
                        )
                    }
                }
                Spacer(Modifier.height(20.dp))

                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    // 一次只开一层：两块抽屉共用一个屏幕，叠起来下面那块就点不到了
                    LabelledIcon(theme, R.drawable.ic_timer, "定时", if (leftMin > 0) "剩 $leftMin 分" else null) {
                        moreOpen = false
                        sleepOpen = !sleepOpen
                    }
                    Spacer(Modifier.weight(1f))
                    LabelledIcon(theme, R.drawable.ic_speed, "语速" + rateLabel(rate), null) {
                        onRate(RATES[(RATES.indexOf(rate).coerceAtLeast(0) + 1) % RATES.size])
                    }
                    Spacer(Modifier.weight(1f))
                    Spacer(Modifier.weight(1f))
                    LabelledIcon(theme, R.drawable.ic_more, "更多", null) {
                        sleepOpen = false
                        moreOpen = !moreOpen
                    }
                    Spacer(Modifier.weight(1f))
                    // 目录：这一颗现在真的进那一页（整本的章 + 每章右边那颗「缓存」）
                    LabelledIcon(theme, R.drawable.ic_reader_toc, "目录", null) { onToc() }
                }

                Spacer(Modifier.height(26.dp))
                SeekRow(theme = theme, posMs = pos, durMs = dur, onSeek = { player.seekTo(it) })

                Spacer(Modifier.height(20.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Spacer(Modifier.weight(1f))
                    Box(modifier = Modifier.size(44.dp).tap(enabled = hasPrev, onClick = onPrev), contentAlignment = Alignment.Center) {
                        Icon(
                            painter = painterResource(R.drawable.ic_prev_para),
                            contentDescription = "上一段",
                            tint = if (hasPrev) theme.inkColor else theme.faintColor.copy(alpha = 0.35f),
                            modifier = Modifier.size(24.dp),
                        )
                    }
                    Spacer(Modifier.weight(1f))
                    Box(
                        modifier = Modifier
                            .size(72.dp)
                            .clip(CircleShape)
                            .background(theme.barColor)
                            .tap(onToggle),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            painter = painterResource(if (playing) R.drawable.ic_pause else R.drawable.ic_play),
                            contentDescription = if (playing) "暂停" else "继续",
                            tint = theme.inkColor,
                            modifier = Modifier.size(30.dp),
                        )
                    }
                    Spacer(Modifier.weight(1f))
                    Box(modifier = Modifier.size(44.dp).tap(enabled = hasNext, onClick = onNext), contentAlignment = Alignment.Center) {
                        Icon(
                            painter = painterResource(R.drawable.ic_next_para),
                            contentDescription = "下一段",
                            tint = if (hasNext) theme.inkColor else theme.faintColor.copy(alpha = 0.35f),
                            modifier = Modifier.size(24.dp),
                        )
                    }
                    Spacer(Modifier.weight(1f))
                }

                // 他点的：这一屏不要"提示操作"的那行字。原来那句"上一段 / 下一段会重新合成，按字符计费"
                // 是常驻说明书，删掉；但这一格的高度要留着，不然有回话时下面整块会跳一下
                Spacer(Modifier.height(14.dp))
                // 合成中这一格不再写字：中间那颗转圈已经说了同一件事，两行字配一个圈就是重复（他点的
                // "底部那行红色小字不要了，只保留转圈动画"）。但**回话要留** —— 错误和"这一段没有念得出来的字"
                // 只有这一格会说，藏掉就只剩一个圈在转，出了错没人知道为什么
                val line = if (busy) null else note
                if (line != null) {
                    Text(
                        line,
                        fontSize = 12.sp,
                        color = theme.accentColor,
                        modifier = Modifier.fillMaxWidth(),
                        textAlign = TextAlign.Center,
                    )
                } else {
                    Spacer(Modifier.height(15.dp))
                }
                Spacer(Modifier.height(30.dp))
            }

            if (busy) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Column(
                        modifier = Modifier
                            .clip(RoundedCornerShape(10.dp))
                            .background(Color(0xCC000000))
                            .padding(horizontal = 22.dp, vertical = 16.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        CircularProgressIndicator(modifier = Modifier.size(22.dp), strokeWidth = 2.dp, color = Color.White)
                        Text("合成中", fontSize = 12.sp, color = Color.White, modifier = Modifier.padding(top = 8.dp))
                    }
                }
            }

            if (sleepOpen) {
                Sheet(theme.barColor, theme.inkColor, theme.faintColor, title = "定时停止播放", onDismiss = { sleepOpen = false }) {
                    SLEEP_MINUTES.forEach { m ->
                        Row(
                            modifier = Modifier.fillMaxWidth().height(52.dp).tap {
                                onSleep(m); sleepOpen = false
                            },
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(if (m == 0) "不开" else "$m 分钟", fontSize = 15.sp, color = theme.inkColor)
                            Spacer(Modifier.weight(1f))
                            if ((m == 0) == (sleepEndsAt == 0L)) {
                                Text("·", fontSize = 18.sp, color = theme.accentColor)
                            }
                        }
                    }
                }
            }

            if (moreOpen) {
                // 他点的：这一层不要标题、不要右上角那颗 X（点抽屉外面、或点任意一行都收得回去）
                Sheet(theme.barColor, theme.inkColor, theme.faintColor, title = null, onDismiss = { moreOpen = false }) {
                    SwitchRow(
                        theme, "连续播放",
                        "一直往下播，念到本章最后一句自己停 —— 每多一段就多一次合成费",
                        autoNext, true,
                    ) { onAutoNext(it) }
                    // 这一颗是上面那颗的延伸：连续播放关着就没有"念完"这一刻，跨章无从谈起 —— 灰着但不藏
                    SwitchRow(
                        theme, "自动下一章",
                        if (autoNext) "本章念完接着念下一章，正文跟着翻过去"
                        else "要先开「连续播放」",
                        autoNextChapter, autoNext,
                    ) { onAutoNextChapter(it) }
                    SwitchRow(theme, "允许其他应用一起播", "不抢音频焦点，音乐 / 播客继续响", shareFocus) { onShareFocus(it) }
                    Spacer(Modifier.height(8.dp))
                    // 标题和选项同一行：这一层已经够高了，名字占一行浪费。
                    // 行高 52dp = 下面「缓存历史」那一档（真机量过普通项行距 143px = 52.0dp）
                    Row(Modifier.fillMaxWidth().height(52.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text("音色", fontSize = 15.sp, color = theme.inkColor)
                        Spacer(Modifier.width(8.dp))
                        if (voices.isEmpty()) {
                            // 没配这一列时说清去哪配，而不是把当前值晾在屏幕上让人以为能点
                            Text(
                                "还没有可选音色：AI 听书 → 可选音色 里抄进来",
                                fontSize = 12.sp,
                                color = theme.faintColor,
                                modifier = Modifier.weight(1f),
                            )
                        } else {
                            // 选项卡：横着滑的一排胶囊，选中那颗描边（和 AI 服务那三颗服务商同一个组件）
                            Row(Modifier.weight(1f).horizontalScroll(rememberScrollState())) {
                                voices.forEachIndexed { i, v ->
                                    WholeChip(
                                        v,
                                        // 32dp → 28dp：这一行的主角是那一排名字，胶囊再高一点就把行顶满了
                                        modifier = Modifier.height(28.dp),
                                        selected = v == voiceId,
                                        radius = 14.dp,
                                    ) {
                                        onVoice(v)
                                        // 换音色只对"下一次合成"有效：手里这段已经是旧音色，重新生成要再扣一次钱
                                        note = "音色已换成 $v：下一段起生效，这一段要立刻换就点「重新生成」"
                                    }
                                    if (i < voices.lastIndex) Spacer(Modifier.size(8.dp))
                                }
                            }
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                    // 背景：和快捷面板那排色块同一批档，点了立刻换（这一屏和正文共用一个 themeIndex）。
                    // 行高同 52dp；色块高 = 上面那颗胶囊的高（28dp），宽按原来 34:22 的比例给到 44dp
                    Row(Modifier.fillMaxWidth().height(52.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text("背景", fontSize = 15.sp, color = theme.inkColor)
                        Spacer(Modifier.width(8.dp))
                        Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
                            ReaderTheme.SWATCHES.forEachIndexed { i, p ->
                                Box(
                                    modifier = Modifier
                                        .size(width = 44.dp, height = 28.dp)
                                        .clip(CircleShape)
                                        .background(p.pageColor)
                                        .then(
                                            if (p.page == theme.page) {
                                                Modifier.border(1.5.dp, theme.accentColor, CircleShape)
                                            } else {
                                                Modifier.border(0.6.dp, theme.dividerColor, CircleShape)
                                            },
                                        )
                                        .tap { onPaper(p) },
                                ) { }
                                if (i < ReaderTheme.SWATCHES.lastIndex) Spacer(Modifier.width(8.dp))
                            }
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                    MoreRow(theme, "缓存历史", arrow = true) { moreOpen = false; onHistory() }
                    MoreRow(theme, "听书纠错", arrow = true) { moreOpen = false; onFixes() }
                    MoreRow(theme, "重新生成") { moreOpen = false; onResynth() }
                    Spacer(Modifier.height(12.dp))
                }
            }
        }
    }
}

@Composable
private fun MoreRow(theme: ReaderTheme, text: String, arrow: Boolean = false, onClick: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().height(52.dp).tap(onClick),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(text, fontSize = 15.sp, color = theme.inkColor)
        Spacer(Modifier.weight(1f))
        // 会翻到另一页的那两行才给 >；「重新生成」是个动作，不是去哪个页面
        if (arrow) {
            Icon(
                painter = painterResource(R.drawable.ic_chevron_right),
                contentDescription = null,
                tint = theme.faintColor,
                modifier = Modifier.size(16.dp),
            )
        }
    }
}

/** 一行 = 标题 + 那句代价 + 右边一颗滑动胶囊开关。代价必须写在同一行：这一颗按下去是要扣钱的。 */
@Composable
private fun SwitchRow(
    theme: ReaderTheme,
    text: String,
    note: String,
    on: Boolean,
    enabled: Boolean = true,
    onChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp).padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(text, fontSize = 15.sp, color = theme.inkColor)
            Text(note, fontSize = 11.sp, color = theme.faintColor, lineHeight = 15.sp, modifier = Modifier.padding(top = 2.dp))
        }
        Spacer(Modifier.width(10.dp))
        Switch(
            checked = on,
            onCheckedChange = onChange,
            // 灰的那一档交给组件自己出：这版 Material3 的 SwitchDefaults 没有 disabledTrackColor 可填
            enabled = enabled,
            colors = SwitchDefaults.colors(
                checkedTrackColor = theme.accentColor,
                checkedThumbColor = theme.barColor,
                uncheckedTrackColor = theme.pageColor,
                uncheckedBorderColor = theme.dividerColor,
                uncheckedThumbColor = theme.faintColor,
            ),
        )
    }
}

@Composable
private fun LabelledIcon(theme: ReaderTheme, icon: Int, label: String, sub: String?, onClick: () -> Unit) {
    Column(
        modifier = Modifier.tap(onClick).padding(horizontal = 6.dp, vertical = 2.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(painter = painterResource(icon), contentDescription = label, tint = theme.inkColor, modifier = Modifier.size(22.dp))
        Text(label, fontSize = 11.sp, color = theme.faintColor, modifier = Modifier.padding(top = 3.dp))
        if (sub != null) Text(sub, fontSize = 10.sp, color = theme.accentColor)
    }
}

/** 自绘进度条：轨道 + 已过实心段 + 一颗头，整条可点可拖。 */
@Composable
private fun SeekRow(theme: ReaderTheme, posMs: Int, durMs: Int, onSeek: (Int) -> Unit) {
    var track by remember { mutableStateOf(Rect.Zero) }
    val density = LocalDensity.current
    val frac = if (durMs > 0) (posMs.toFloat() / durMs).coerceIn(0f, 1f) else 0f
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(clock(posMs), fontSize = 12.sp, color = theme.faintColor)
        Spacer(Modifier.width(10.dp))
        Box(
            modifier = Modifier
                .weight(1f)
                .height(30.dp)
                .pointerInput(durMs) {
                    detectTapGestures { off ->
                        if (durMs > 0 && track.width > 0f) onSeek(((off.x / track.width) * durMs).roundToInt())
                    }
                }
                .onGloballyPositioned { track = it.boundsInRoot() },
            contentAlignment = Alignment.CenterStart,
        ) {
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(3.dp)
                    .background(theme.dividerColor, RoundedCornerShape(2.dp)),
            )
            Box(
                Modifier
                    .fillMaxWidth(frac)
                    .height(3.dp)
                    .background(theme.accentColor, RoundedCornerShape(2.dp)),
            )
            // 半颗滑块宽：5.5dp 换成像素一次算好，lambda 版要的是整数位移
            val thumbPx = with(density) { 5.5.dp.toPx() }
            Box(
                Modifier
                    .size(11.dp)
                    .offset { IntOffset((track.width * frac - thumbPx).roundToInt(), 0) }
                    .background(theme.accentColor, CircleShape),
            )
        }
        Spacer(Modifier.width(10.dp))
        Text(clock(durMs), fontSize = 12.sp, color = theme.faintColor)
    }
}

private fun rateLabel(rate: Float): String =
    if ((rate * 100).roundToInt() % 100 == 0) "%.1fx".format(rate) else "${rate}x"

private fun clock(ms: Int): String {
    val s = (ms / 1000).coerceAtLeast(0)
    return "%d:%02d".format(s / 60, s % 60)
}
