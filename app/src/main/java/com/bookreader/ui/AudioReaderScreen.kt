package com.bookreader.ui

import android.app.Activity
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.AudioManager
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.bookreader.data.AiVoicePlayer
import com.bookreader.data.AudioBook
import com.bookreader.data.BookLibrary
import com.bookreader.data.BookProvenance
import com.bookreader.data.ReaderPrefs
import com.bookreader.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.File

/** 底栏开的是哪一层。一次只开一层（和小说/漫画那一屏同一条规矩）。 */
private enum class AudioSheet { None, Episodes, Rates, Settings }

/** 语速那几档：底栏「语速」那一格里选一档，选中的那档立刻套到当前这段上。 */
private val RATES = listOf(0.75f, 1.0f, 1.25f, 1.5f, 2.0f)

/**
 * 听一本有声书。这一屏不是小说那条"一串字 → 排成页"的路，和漫画那种"一屏一张"也不一样 ——
 * 它就是一个播放器：封面、集名、一条进度、三颗键。
 *
 * 一集 = 一段（或几段）音频文件：段放完自动接下一段，整集放完自动进下一集（下一集没下到就停下说一句）。
 * 断点记到**毫秒**（`audio.<id>` = "集号|毫秒"），换字号换屏那些事和它无关。
 *
 * 复用 [AiVoicePlayer]，不是再造一台播放器：听书（TTS）那一头已经把"暂停中塞速率会自己开播"、
 * "prepareAsync 没回来时 seek 白跑"这些平台的坑都趟过了，同一件事不留两个主人。
 */
@Composable
fun AudioReaderScreen(
    bookId: String,
    title: String,
    library: BookLibrary,
    prefs: ReaderPrefs,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val activity = LocalView.current.context as? Activity
    val am = remember { context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager }
    var themeIndex by remember { mutableIntStateOf(prefs.themeIndex) }
    val theme = remember(themeIndex) { ReaderTheme.of(themeIndex) }
    var brightness by remember { mutableFloatStateOf(prefs.brightness) }
    BackHandler(onBack = onBack)
    // 亮度 -1 = 跟随系统，那时要把窗口属性还原成默认（和小说/漫画那一屏同一条写法）
    LaunchedEffect(brightness, activity) {
        val window = activity?.window ?: return@LaunchedEffect
        window.attributes = window.attributes.apply {
            screenBrightness = if (brightness < 0f) -1f else brightness.coerceIn(0.02f, 1f)
        }
    }
    // 换背景/夜间要连着外壳一起换（和漫画那一屏同一条写法）
    LaunchedEffect(themeIndex) { prefs.themeIndex = themeIndex; Chrome.night = theme.night }

    val book = remember(bookId) { AudioBook(library.dir(bookId)) }
    var episodes by remember { mutableStateOf<List<AudioBook.Episode>>(emptyList()) }
    // 整本的集名来自下载时存下的那份目录：架上那行写"全书 1068 集"，抽屉就该能看见 1068 行
    var outline by remember { mutableStateOf<List<String>>(emptyList()) }
    LaunchedEffect(bookId) {
        episodes = withContext(Dispatchers.IO) { book.episodes() }
        outline = withContext(Dispatchers.IO) { BookProvenance(library.dir(bookId)).outline() }
    }

    val start = remember(bookId) { prefs.loadAudioPos(bookId) }
    var episode by remember { mutableIntStateOf(start.first.coerceAtLeast(0)) }
    /** 进这一集时要从多少毫秒开始：只对第一段有效，起播完就归零。 */
    var resumeMs by remember { mutableIntStateOf(start.second.coerceAtLeast(0)) }
    var parts by remember { mutableStateOf<List<File>>(emptyList()) }
    var partIndex by remember { mutableIntStateOf(0) }
    var sheet by remember { mutableStateOf(AudioSheet.None) }
    /** 上下那两条栏在不在：顶上那行（‹ 书名/集名 + 时间）和底下那排四颗。中间那一格只管它，点屏幕收放。 */
    var bars by remember { mutableStateOf(true) }
    var note by remember { mutableStateOf<String?>(null) }
    var playing by remember { mutableStateOf(false) }
    /**
     * 要不要播。**进这一屏它是 false**（他点名的：翻开就出声是吓人的），
     * 按过中间那颗、或从目录里挑了一集，才变 true —— 变 true 之后跨段/跨集才自动接着放。
     */
    var wantPlay by remember { mutableStateOf(false) }
    var positionMs by remember { mutableIntStateOf(0) }
    var durationMs by remember { mutableIntStateOf(0) }
    var rate by remember { mutableFloatStateOf(prefs.audioRate) }

    val have = remember(episodes) { episodes.associateBy { it.index } }
    val names = if (outline.size >= episodes.size) outline else episodes.map { it.title }
    val total = maxOf(names.size, episodes.size)

    val player = remember { AiVoicePlayer() }
    DisposableEffect(bookId) {
        player.audioManager = am
        player.rate = rate
        // 走的那一刻把进度落一次表：每秒那格 tick 最多差 1 秒，退出时不能再差这一秒
        onDispose {
            prefs.saveAudioPos(bookId, episode, player.positionMs)
            player.close()
        }
    }

    /** 放一段。段尾的"接下来"也在这一格里：接下一段 → 进下一集 → 都没有了就停。 */
    fun playPart(at: Int, fromMs: Int) {
        val files = parts
        if (at !in files.indices) return
        partIndex = at
        player.rate = rate
        val ok = player.play(
            files[at],
            onEnd = {
                val next = at + 1
                when {
                    next < parts.size -> playPart(next, 0)
                    episode + 1 < total && have[episode + 1] != null -> {
                        resumeMs = 0
                        episode = episode + 1
                    }
                    else -> playing = false
                }
            },
            startAtMs = if (at == 0) fromMs else 0,
        )
        playing = ok
        if (!ok) note = "这一段放不出来 —— 到「本书信息」点「下载全书」重新补一遍"
    }

    /**
     * 换集：把断点和集号写上，装段交给下面那格 LaunchedEffect（它在集号变化时接手）。
     * 从目录里点一集 = 明确要听这一集，所以默认连着把 wantPlay 写上；⏮ 按当前状态传。
     */
    fun goEpisode(to: Int, atMs: Int = 0, andPlay: Boolean = true) {
        if (to < 0 || to >= total) return
        if (have[to] == null) {
            note = "第 ${to + 1} 集还没下到本机 —— 到「本书信息」点「下载全书」补齐"
            return
        }
        sheet = AudioSheet.None
        note = null
        if (andPlay) wantPlay = true
        resumeMs = atMs
        episode = to
    }

    /** ⏮ / ⏭：集内还有一段就切段，没有就跨集。退的那颗在 3 秒内算"回到这一集开头"。 */
    fun step(next: Boolean) {
        if (next) {
            if (partIndex < parts.lastIndex) { wantPlay = true; playPart(partIndex + 1, 0) }
            else goEpisode(episode + 1, andPlay = wantPlay)
        } else {
            if (player.positionMs > 3_000) player.seekTo(0) else goEpisode(episode - 1, andPlay = wantPlay)
        }
    }

    /**
     * 中间那颗：三种状态分开走。
     *
     * **暂停走 [AiVoicePlayer.pause] 而不是 [AiVoicePlayer.toggle]** —— toggle 停下来之后还会
     * 顺手塞一个 0 倍速，而 `setSpeed` 在部分机型上会把播放器又顶起来，表现就是"按了暂停、
     * 图标也变了、声音照旧"（他报的这条）。听书那一屏仍用 toggle，它那条行为他已认可，不动。
     */
    fun centerKey() {
        when {
            player.playing -> { player.pause(); playing = false }
            // 已经建好（准备好或暂停中）：直接续，断点还在播放器身上，不要再建一次
            player.active -> { wantPlay = true; playing = player.resume() }
            else -> { wantPlay = true; playPart(partIndex, resumeMs) }
        }
        positionMs = player.positionMs
        durationMs = player.durationMs
        prefs.saveAudioPos(bookId, episode, player.positionMs)
    }

    fun seekTo(ratio: Float) {
        if (durationMs <= 0) return
        val ms = (ratio.coerceIn(0f, 1f) * durationMs).toInt()
        if (player.seekTo(ms)) positionMs = ms
    }

    /** 选一档语速：立刻套到当前这一段上（没在放也存着，下一次起播就是这一档）。 */
    fun pickRate(v: Float) {
        rate = v
        player.rate = v
        player.applyRate()
        prefs.audioRate = v
    }

    // 换集（含整本第一次进来）就装这一集的段。**装完不一定起播**：wantPlay 是 false 的时候
    // 只把这一集的段数、时长读出来摆在屏上，按了那颗才出声（进界面就播 = 半夜点开一本书会炸耳朵）
    LaunchedEffect(bookId, episode) {
        positionMs = 0
        durationMs = 0
        parts = withContext(Dispatchers.IO) { book.parts(episode) }
        if (parts.isEmpty()) {
            playing = false
            if (have[episode] == null) note = "第 ${episode + 1} 集还没下到本机 —— 到「本书信息」点「下载全书」补齐"
            return@LaunchedEffect
        }
        if (wantPlay) {
            playPart(0, resumeMs)
        } else if (player.prepare(parts[0], resumeMs)) {
            // 不开口，但进度条和"共 26:22"要是真的：prepareAsync 是异步的，最多等 2 秒，
            // 等不到就把那格留成 --:-- —— 编一个时长比留空更糟
            var waited = 0
            while (durationMs == 0 && waited < 2_000) {
                delay(120)
                waited += 120
                durationMs = player.durationMs
                positionMs = player.positionMs
            }
        }
        resumeMs = 0
    }

    // 播着的时候每秒读一次进度 + 落一次表：锁屏、被系统杀掉，回来还在这一秒
    LaunchedEffect(playing) {
        while (playing) {
            positionMs = player.positionMs
            durationMs = player.durationMs
            prefs.saveAudioPos(bookId, episode, positionMs)
            delay(1_000)
        }
    }

    ApplySystemBars(darkIcons = !theme.night)

    Box(
        Modifier
            .fillMaxSize()
            // **先铺满整块窗口，再让内容让开安全区** —— 顺序反了底色就只铺到安全区里，
            // 状态栏和导航条背后露的是窗口默认色，换夜间/换背景看不出变化（ApplySystemBars 上面那条注释
            // 写的就是这个坑；小说那一屏的外层正是 `fillMaxSize().background(pageColor)`）
            .background(theme.pageColor)
            .safeDrawingPadding()
            // 点屏幕：开着一层就先收那一层；什么都没开，就是收放上下那两条栏（他点名的第二条）
            .pointerInput(Unit) {
                detectTapGestures {
                    if (sheet != AudioSheet.None) sheet = AudioSheet.None else bars = !bars
                }
            },
    ) {
        Column(Modifier.fillMaxSize()) {
            // 顶栏：‹ 书名 + 集名（多段的集带上"第几段"）/ 右边这一段的时间。点屏幕收这一条（他点名的）
            if (bars) Row(
                Modifier.fillMaxWidth().height(52.dp).padding(horizontal = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                BackSlot("返回书架", onBack)
                Column(Modifier.weight(1f).padding(start = 2.dp)) {
                    Text(title, fontSize = 15.sp, fontWeight = FontWeight.Medium, color = theme.inkColor, maxLines = 1)
                    Text(
                        (names.getOrNull(episode) ?: "第 ${episode + 1} 集") +
                            (if (parts.size > 1) " · 第 ${partIndex + 1}/${parts.size} 段" else ""),
                        fontSize = 12.sp,
                        color = theme.faintColor,
                        maxLines = 1,
                    )
                }
                Text(
                    if (durationMs > 0) mmss(positionMs) + " / " + mmss(durationMs) else "--:--",
                    fontSize = 13.sp,
                    color = theme.faintColor,
                )
            }

            // 中间：封面 + 集名 + 那句回话
            Column(
                Modifier.fillMaxWidth().weight(1f),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                AudioCover(library.coverFile(bookId), theme)
                Spacer(Modifier.height(24.dp))
                Text(
                    names.getOrNull(episode)?.ifBlank { "第 ${episode + 1} 集" } ?: "第 ${episode + 1} 集",
                    fontSize = 17.sp,
                    color = theme.inkColor,
                    textAlign = TextAlign.Center,
                    maxLines = 2,
                    modifier = Modifier.padding(horizontal = 32.dp),
                )
                if (parts.isEmpty()) {
                    Text(
                        "这一集的音频不在本机",
                        fontSize = 12.sp,
                        color = theme.faintColor,
                        modifier = Modifier.padding(top = 10.dp),
                    )
                }
                note?.let {
                    Text(
                        it,
                        fontSize = 12.sp,
                        color = theme.accentColor,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(top = 10.dp, start = 24.dp, end = 24.dp),
                    )
                }
            }

            // 控制区：进度条 + 时间两个角 + 三颗键（那一排文字按钮已经收进下面的底栏了）
            Column(Modifier.fillMaxWidth().padding(start = 24.dp, end = 24.dp, bottom = 14.dp)) {
                SlideBar(
                    value = if (durationMs > 0) positionMs.toFloat() / durationMs else 0f,
                    enabled = durationMs > 0,
                    theme = theme,
                    onChange = { seekTo(it) },
                    modifier = Modifier.fillMaxWidth(),
                )
                Row(Modifier.fillMaxWidth().padding(top = 6.dp)) {
                    Text(mmss(positionMs), fontSize = 11.sp, color = theme.faintColor)
                    Spacer(Modifier.weight(1f))
                    Text("-" + mmss((durationMs - positionMs).coerceAtLeast(0)), fontSize = 11.sp, color = theme.faintColor)
                }
                Row(
                    Modifier.fillMaxWidth().padding(top = 8.dp),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    TransportKey(R.drawable.ic_prev_para, "上一集", theme.inkColor) { step(false) }
                    Spacer(Modifier.width(28.dp))
                    Box(
                        Modifier
                            .size(64.dp)
                            .clip(CircleShape)
                            .background(theme.accentColor)
                            .tap { centerKey() },
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            painterResource(if (playing) R.drawable.ic_pause else R.drawable.ic_play),
                            contentDescription = if (playing) "暂停" else "播放",
                            tint = Color.White,
                            modifier = Modifier.size(32.dp),
                        )
                    }
                    Spacer(Modifier.width(28.dp))
                    TransportKey(R.drawable.ic_next_para, "下一集", theme.inkColor) { step(true) }
                }
            }

            // 底栏：和小说/漫画**同一颗** ReaderBottomBar（图标上、文字下、58dp、顶部一条发丝线）。
            // 第二颗给的是「语速」而不是「书签」—— 这一族没有"第几页"可记，那一格空着就是颗死控件。
            // 和顶栏同一条命运：点屏幕收放（栏收着时那句回话还在中间那一格里看得见，不会变成"点了没反应"）
            if (bars) ReaderBottomBar(
                theme = theme,
                onToc = { sheet = if (sheet == AudioSheet.Episodes) AudioSheet.None else AudioSheet.Episodes },
                onMarks = { sheet = if (sheet == AudioSheet.Rates) AudioSheet.None else AudioSheet.Rates },
                onToggleNight = { themeIndex = if (theme.night) 0 else ReaderTheme.ALL.lastIndex },
                onSettings = { sheet = if (sheet == AudioSheet.Settings) AudioSheet.None else AudioSheet.Settings },
                marksLabel = "语速" + rateLabel(rate),
                marksIcon = R.drawable.ic_speed,
            )
        }

        if (sheet == AudioSheet.Episodes) {
            EpisodesDrawer(
                names = names,
                have = have,
                current = episode,
                theme = theme,
                onPick = { goEpisode(it) },
                onClose = { sheet = AudioSheet.None },
            )
        }

        if (sheet == AudioSheet.Rates) {
            PanelShell(theme) {
                Row(
                    Modifier.fillMaxWidth().padding(top = 14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("语速", fontSize = 15.sp, fontWeight = FontWeight.Medium, color = theme.inkColor, modifier = Modifier.weight(1f))
                    Text("关闭", fontSize = 13.sp, color = theme.faintColor, modifier = Modifier.tap { sheet = AudioSheet.None })
                }
                Text(
                    "一档 = 原速。选一档立刻套到当前这一段上，换集不换（存在「我的 → 阅读偏好」同一格）。",
                    fontSize = 12.sp,
                    color = theme.faintColor,
                    modifier = Modifier.padding(top = 6.dp),
                )
                Row(
                    Modifier.fillMaxWidth().padding(top = 18.dp, bottom = 22.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    RATES.forEach { v ->
                        val on = v == rate
                        Box(
                            Modifier
                                .weight(1f)
                                .height(40.dp)
                                .clip(RoundedCornerShape(10.dp))
                                .background(if (on) theme.accentColor else theme.pageColor)
                                .tap { pickRate(v) },
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(rateLabel(v), fontSize = 13.sp, color = if (on) Color.White else theme.inkColor)
                        }
                    }
                }
            }
        }

        if (sheet == AudioSheet.Settings) {
            // 和漫画那一屏点「设置」开的同一格（同一份实现，不各写一遍）
            BrightnessBackgroundPanel(
                theme = theme,
                brightness = brightness,
                onBrightness = { brightness = it; prefs.brightness = it },
                onFollowSystem = { brightness = -1f; prefs.brightness = -1f },
                onPickTheme = { themeIndex = ReaderTheme.ALL.indexOf(it) },
            )
        }
    }
}

/** 三颗键里小的两颗：一颗图标居中在一个 44dp 的格子里，格管命中区，图标管看得见。 */
@Composable
private fun TransportKey(icon: Int, desc: String, tint: Color, onClick: () -> Unit) {
    Box(Modifier.size(44.dp).tap(onClick), contentAlignment = Alignment.Center) {
        Icon(painterResource(icon), contentDescription = desc, tint = tint, modifier = Modifier.size(26.dp))
    }
}

/** 中间那张封面。没下到封面的那本摆一块底色而不是空白：空着看上去像"这本书坏了"。 */
@Composable
private fun AudioCover(coverFile: File, theme: ReaderTheme) {
    val bmp by produceState<Bitmap?>(null, coverFile.absolutePath) {
        value = withContext(Dispatchers.IO) {
            if (coverFile.isFile) BitmapFactory.decodeFile(coverFile.absolutePath) else null
        }
    }
    val b = bmp
    if (b == null || b.isRecycled) {
        Box(
            Modifier.size(width = 180.dp, height = 240.dp).clip(RoundedCornerShape(12.dp)).background(theme.dividerColor),
            contentAlignment = Alignment.Center,
        ) {
            Text("有声书", fontSize = 15.sp, color = theme.faintColor)
        }
        return
    }
    Image(
        bitmap = b.asImageBitmap(),
        contentDescription = null,
        modifier = Modifier.size(width = 180.dp, height = 240.dp).clip(RoundedCornerShape(12.dp)),
        contentScale = ContentScale.Crop,
    )
}

/**
 * 目录：**从左边推满高度的抽屉**，300dp 宽、右缘 16dp 圆角、底色 `theme.barColor` ——
 * 和小说/漫画那一屏的同一份形状。当前那一行只把字染成强调色，不铺底（漫画那边他点名的口径）。
 */
@Composable
private fun BoxScope.EpisodesDrawer(
    names: List<String>,
    have: Map<Int, AudioBook.Episode>,
    current: Int,
    theme: ReaderTheme,
    onPick: (Int) -> Unit,
    onClose: () -> Unit,
) {
    Column(
        Modifier
            .fillMaxHeight()
            .width(300.dp)
            .align(Alignment.CenterStart)
            .clip(RoundedCornerShape(topEnd = 16.dp, bottomEnd = 16.dp))
            .background(theme.barColor)
            .consumesPanelTaps(),
    ) {
        PanelTitle("目录 ${names.size} 集", theme, onClose)
        val state = rememberLazyListState(initialFirstVisibleItemIndex = current.coerceIn(0, (names.size - 1).coerceAtLeast(0)))
        LazyColumn(
            state = state,
            modifier = Modifier.fillMaxWidth().weight(1f),
            contentPadding = PaddingValues(bottom = 12.dp),
        ) {
            items(names.size) { i -> EpisodeRow(i, names[i], have[i], i == current, theme, onPick) }
        }
    }
}

/** 一行：集名 + 右边段数。没下的那几行名字灰着、点了给一句为什么。 */
@Composable
private fun EpisodeRow(
    index: Int,
    name: String,
    ep: AudioBook.Episode?,
    current: Boolean,
    theme: ReaderTheme,
    onPick: (Int) -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .height(44.dp)
            .tap { onPick(index) }
            .padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            name.ifBlank { "第 ${index + 1} 集" },
            fontSize = 14.sp,
            color = when {
                current -> theme.accentColor
                ep == null -> theme.faintColor
                else -> theme.inkColor
            },
            maxLines = 1,
            modifier = Modifier.weight(1f),
        )
        if (ep != null) Text(ep.parts.toString(), fontSize = 12.sp, color = theme.faintColor)
    }
}

/** 毫秒 → "分:秒"。超过一小时的集也是这个样式（75:03），播客那一族都这么写。 */
private fun mmss(ms: Int): String {
    val s = (ms.coerceAtLeast(0)) / 1000
    return (s / 60).toString() + ":" + (s % 60).toString().padStart(2, '0')
}

/** 0.75f → "0.75x"，1.0f → "1.0x"。 */
private fun rateLabel(rate: Float): String = if (rate % 1f == 0f) rate.toInt().toString() + ".0x" else rate.toString() + "x"
