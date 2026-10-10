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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.bookreader.R
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
 * 原文（图标，点它收起这一屏并把正文翻到正在念的那一段）、更多（自动读下一段 / 允许其他应用一起播 /
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
    coverRes: Int?,
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
    onAutoNext: (Boolean) -> Unit,
    onShareFocus: (Boolean) -> Unit,
    onHistory: () -> Unit,
    onFixes: () -> Unit,
) {
    // 那两条留着，但底色必须和这一屏同一个色（不涂的话回到阅读页那一侧，它们是平台灰）
    ApplySystemBars(theme.pageColor.toArgb(), theme.pageColor.toArgb(), !theme.night)

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
    // 所以"念到第几句"是按"已过时长 / 总时长"折算的 —— 语音等速念，最多差半句，不会整段错位
    val frac = if (dur > 0) (pos.toFloat() / dur).coerceIn(0f, 1f) else 0f
    val winScroll = rememberScrollState()
    val starts = remember(paragraph) { sentenceStarts(paragraph) }
    val idx = (starts.size * frac).toInt().coerceIn(0, starts.size - 1)
    // 窗口两行高。这段比两行高才谈得上"滚"；比两行矮的话根本没有可滚的（maxValue=0），
    // 那就改成把念过的那几句直接切掉 —— 不然整段只有两三行的时候这一屏从头到尾不动
    val winPx = with(LocalDensity.current) { 25.sp.toPx() * 2f }
    var taller by remember { mutableStateOf(false) }
    // 排版结果放在普通数组里而不是 State：它只被下面这个 effect 读，挂成可观察状态会在每次排版时
    // 多推一轮组合，而 onTextLayout 本身就是在排版过程里回调的
    val laidOut = remember { arrayOfNulls<TextLayoutResult>(1) }
    // 只在**换句**那一刻动一次，不跟着 250ms 的进度轮询重发：
    // 跟着轮询的话他自己用手指拨那两行，会被下一枪的 scrollTo 顶回去，看着就是"字排突然变了一下"
    LaunchedEffect(idx, paragraph, taller) {
        val l = laidOut[0] ?: return@LaunchedEffect   // 第一次组合还没量出来，换句时就有了
        if (!taller) return@LaunchedEffect            // 装不满窗口的段靠"切掉已读"表现进度，不滚
        // 把正在念的那一句顶到窗口上沿。animateScrollTo 自己会夹进 0..maxValue，
        // 末句的行顶落在窗口下沿之外时不用在这里补边界
        winScroll.animateScrollTo(
            if (starts.size > 1) l.getLineTop(l.getLineForOffset(starts[idx])).roundToInt()
            // 整段一个句末标点都没有：退回按字符比例滚
            else (winScroll.maxValue * frac).roundToInt(),
            animationSpec = tween(durationMillis = 420, easing = FastOutSlowInEasing),
        )
    }
    val shown = if (taller) paragraph else paragraph.substring(starts[idx])

    Box(Modifier.fillMaxSize().background(theme.pageColor)) {
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
                Box(modifier = Modifier.size(40.dp).tap(onStop), contentAlignment = Alignment.Center) {
                    Icon(
                        painter = painterResource(R.drawable.ic_close),
                        contentDescription = "停止听书",
                        tint = theme.faintColor,
                        modifier = Modifier.size(18.dp),
                    )
                }
            }

            Spacer(Modifier.height(10.dp))
            Text(chapterTitle, fontSize = 22.sp, fontWeight = FontWeight.Medium, color = theme.inkColor)
            Text(bookTitle, fontSize = 14.sp, color = theme.faintColor, modifier = Modifier.padding(top = 4.dp))

            Spacer(Modifier.weight(1f))
            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                if (coverRes != null) {
                    Image(
                        painter = painterResource(coverRes),
                        contentDescription = bookTitle,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.size(width = 170.dp, height = 227.dp).clip(RoundedCornerShape(6.dp)),
                    )
                } else {
                    Box(
                        modifier = Modifier
                            .size(width = 170.dp, height = 227.dp)
                            .clip(RoundedCornerShape(6.dp))
                            .background(theme.barColor),
                        contentAlignment = Alignment.Center,
                    ) { Text(bookTitle.take(2), fontSize = 30.sp, fontWeight = FontWeight.Medium, color = theme.inkColor) }
                }
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
                        .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
                        .drawWithContent {
                            drawContent()
                            val h = size.height
                            val fade = (8.dp.toPx() / h).coerceAtMost(0.4f)
                            drawRect(
                                // verticalGradient 默认就是从上到下铺满这块画布，不用再给起止点
                                brush = Brush.verticalGradient(
                                    0f to Color.Transparent,
                                    fade to Color.Black,
                                    1f - fade to Color.Black,
                                    1f to Color.Transparent,
                                ),
                                blendMode = BlendMode.DstIn,
                            )
                        },
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
                        },
                    )
                }
                Spacer(Modifier.width(10.dp))
                Box(
                    modifier = Modifier
                        .size(40.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .tap(onOriginal),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        painter = painterResource(R.drawable.ic_original),
                        contentDescription = "原文",
                        tint = theme.faintColor,
                        modifier = Modifier.size(22.dp),
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
                // 目录：他说先静态 —— 图标和字都在，点下去只回一句实话
                LabelledIcon(theme, R.drawable.ic_reader_toc, "目录", null) {
                    note = "目录还没接进这一屏：要跳章先点「原文」回正文"
                }
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
            val line = if (busy) "正在合成这一段…" else note
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
                SwitchRow(theme, "自动读下一段", "念完自己接下一段 —— 每多一段就多一次合成费", autoNext) { onAutoNext(it) }
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
                            "还没有可选音色：AI 服务 → 语音 → 可选音色 里抄进来",
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
            Box(
                Modifier
                    .size(11.dp)
                    .offset(x = with(density) { (track.width * frac).toDp() } - 5.5.dp)
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
