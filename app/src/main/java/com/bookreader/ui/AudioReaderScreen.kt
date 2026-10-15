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
import androidx.compose.foundation.lazy.items
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
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameMillis
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
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
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
    /** 书架上那一行"曹雪芹 · 120 章"的同一格：录音开头念回目时**连作者一起念**，跟读的零点要用它。 */
    author: String,
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
    /**
     * 这一集的正文。只有"音频和正文在同一页上"的那一族（古文岛）才有 —— 播客那一族读回来是空串，
     * 空串就不开跟读那一格（不是报错，那一族本来没正文可跟）。
     */
    var text by remember { mutableStateOf("") }
    /**
     * 跟读开没开。**有正文那一集默认开着**（他给的那张截图上，中间那一格就是正文占满）。
     * 每一集换了正文会重新开一次，手动关过这一本就不再自作主张（`followChosen` 记那一笔）。
     */
    var follow by remember { mutableStateOf(false) }
    var followChosen by remember(bookId) { mutableStateOf(false) }
    /** 自动跟随：人一滚就停下（不然他翻到别处看，一秒又被拽回来），滚回当前那一段那一颗再续上。 */
    var pinned by remember { mutableStateOf(true) }

    val have = remember(episodes) { episodes.associateBy { it.index } }
    val names = if (outline.size >= episodes.size) outline else episodes.map { it.title }
    val total = maxOf(names.size, episodes.size)

    /**
     * 跟读能不能开：**这一集有正文、而且这一集只有一段音频**。
     *
     * 后一半不是保守，是算法：时刻是把字数摊在 `durationMs` 上，而那是**当前那一段**的时长，
     * 拿它摊一整集的字数会把第二段该念的那些句全算进第一段里 —— 开着一张错表比不开更糟。
     * （古文岛那一族一回就是一段，所以这一条对它不起作用。）
     */
    val canFollow = text.isNotBlank() && parts.size == 1
    /**
     * 跟读表用的文本 = **集名 + 作者 + 正文**，不是正文单独。
     *
     * 录音开头先把回目连作者念一遍，才念正文。量过（古文岛·红楼梦第 1/3/118 回、三国第 1 回）：
     * 第一处 ≥0.4 秒的停顿起点 **6.14 / 3.76 / 7.00 / 6.18 秒**，按「(回目＋作者) 字数 × 0.28 秒/字」
     * 推是 6.16 / 6.16 / 7.00 / 6.16 秒 —— 三回对到 0.1 秒内（第三回那一处落在回目中间）。
     * 第 118 回 25 字 → 7.00 秒正对上 7.00 秒，是"作者也被念出来"最硬的一条（只算回目会推成 6.2 秒）。
     *
     * 这一格值多少，拿真停顿逐句比过：表上**只摆回目**（19 格 = 5.93 秒）时正文第一句真开口在
     * **6.95 秒**，表早 1.02 秒，整回带号中位 **+0.49 秒**；把作者补进去（22 格 = 6.86 秒）之后
     * 开头只差 **0.09 秒**，整回带号中位 +0.32 秒、|误差| 中位 1.03 → **0.92 秒**。
     * 再往上把书名也记进去（25 格 = 7.80 秒）会把头**过冲**成晚 0.85 秒 —— 那是"文字比声音慢"的形状，不做。
     *
     * 站点交回来的正文里**没有**这一句（`div.contson` 从正文第一个字就开始），不摆进表就等于整张表
     * 从第一秒起比声音早一句（句均 21 字 ≈ 6.5 秒）—— 他报的"声音还在上一句，文字变红已经在下一句"。
     */
    val announce = listOf(names.getOrNull(episode)?.trim().orEmpty(), author.trim()).joinToString(" ").trim()
    val followText = if (announce.isEmpty()) text else "$announce\n$text"
    val map = remember(followText, durationMs) { FollowAlign.align(followText, durationMs.toLong()) }
    val line = if (canFollow && follow) map.sentenceAt(positionMs.toLong()) else -1

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
        text = withContext(Dispatchers.IO) { book.text(episode) }
        pinned = true
        if (text.isNotBlank() && parts.size == 1 && !followChosen) follow = true
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

    // 「我的 → 皮肤管理」上传的那张背景图，这一屏也要铺（2026-10-15 他点名"这个没有实现上传的图片
    // 背景显示啊"）。位图按整块窗口的宽解、压的那层纸色仍是皮肤管理那一格的滑块 —— 和阅读屏、
    // 听书页共用同一份 `PaperBackground`，三屏换纸色 / 换浓淡一起换。
    val bgBmp = scaledImage(themeFile(context, prefs.bgImageFile), contentSizePx().width)
    // **先铺满整块窗口，再让内容让开安全区**：图要伸到状态栏和导航条底下，正文和那两条栏要让开。
    // 顺序反了图就只铺到安全区里，栏背后露的是窗口默认色（`ApplySystemBars` 上面那条注释写的就是这个坑）
    Box(Modifier.fillMaxSize().background(theme.pageColor)) {
        PaperBackground(theme, bgBmp, prefs.bgDim)
        // 上下那两条栏亮着的时候，状态栏 / 导航条那两条盖成实心纸 —— 和阅读屏同一条口径、同一份实现
        BarPaper(theme, if (bars) 1f else 0f)
        Box(
            Modifier
                .fillMaxSize()
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
                    // 这一集有正文才给这一颗（没正文那一族点了是颗死控件）。名字写的是**按下去会看到什么**
                    if (canFollow) {
                        Text(
                            if (follow) "封面" else "正文",
                            fontSize = 12.sp,
                            color = theme.faintColor,
                            modifier = Modifier
                                .padding(start = 12.dp)
                                .tap { follow = !follow; followChosen = true; pinned = true },
                        )
                    }
                }

                // 中间那一格：有正文又开着跟读 → 正文占满（当前那句染成强调色）；否则还是封面 + 集名
                if (canFollow && follow) {
                    FollowPane(
                        map = map,
                        line = line,
                        theme = theme,
                        fontSizeSp = prefs.fontSizeSp,
                        lineHeight = prefs.lineHeightMultiplier,
                        pinned = pinned,
                        onUnpin = { pinned = false },
                        onBackToCurrent = { pinned = true },
                        note = note,
                        modifier = Modifier.fillMaxWidth().weight(1f),
                    )
                } else Column(
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
                        // 铺了图的那一屏：轨道和滑块一起让图透出来（0.45 那一档的来由和真图算出的
                        // 读数写在 `BAR_ALPHA_ON_IMAGE` 上头）；纯色纸仍走原来那两笔实心
                        barAlpha = if (bgBmp != null) BAR_ALPHA_ON_IMAGE else 1f,
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
}

/**
 * 跟读那一格：整集正文摆满，当前那一句染成强调色，**只在当前这一句滚出屏幕时**才把它拉回来。
 *
 * 两处都是量出来的形态，不是随手定的：
 *  - **不挂点击**。这一格上的字只是给人读的，点屏幕正中在这屏一贯是"收放上下那两条栏"；
 *    给它挂一颗跳播之后，实测一次中心点击把播放从 7:45 拽回 7:35（那一段的开头）—— 他点名的"点屏幕中心翻页"。
 *    要跳有底下那条进度条，不用在正文上再埋一颗看不见的按钮。
 *  - **跟随按句、但只在看不见时才滚**。一屏装得下二十来句，跟着每一句滚等于每隔几秒整屏跳一下；
 *    而"跟着段滚"在古文岛这种**一段 700 多字**的正文上更糟：滚到段首之后红字还在屏幕外面。
 *    所以判据换成"当前这一句还在不在屏上"，在就不动。
 */
@Composable
private fun FollowPane(
    map: FollowMap,
    line: Int,
    theme: ReaderTheme,
    fontSizeSp: Float,
    lineHeight: Float,
    pinned: Boolean,
    onUnpin: () -> Unit,
    onBackToCurrent: () -> Unit,
    note: String?,
    modifier: Modifier = Modifier,
) {
    val state = rememberLazyListState()
    // 自己那一条滚动动画不算"人滚开了"：`isScrollInProgress` 对我们、对他都亮
    var auto by remember { mutableStateOf(false) }
    // 键里必须有 `pinned`：那颗「回到当前」只改这一个值。原来只挂 `line`，
    // 于是按下去除了药丸自己消失之外屏幕上什么都不动（他报的"没有什么效果"）
    LaunchedEffect(line, pinned) {
        if (!pinned || line < 0) return@LaunchedEffect
        val p = map.paragraphOf(line)
        val f = map.fractionInParagraph(line)
        auto = true
        try {
            // 最多三轮：第一轮把这一整段滚进屏幕（顺便才量得到它多高），第二轮才把红字摆到上三分之一，
            // 第三轮确认。原来只滚一次，段不在屏幕上时传的是 0 = 段首 —— 古文岛一段能有 700 多字，
            // 红字照样在屏幕外面，那是"没有什么效果"的第二半
            for (round in 0 until 3) {
                val info = state.layoutInfo
                val view = info.viewportEndOffset - info.viewportStartOffset
                val item = info.visibleItemsInfo.firstOrNull { it.index == p }
                if (item != null) {
                    // 红字还在屏幕上（上留 40、下留 120 的余量）就一下都不滚
                    val y = item.offset + (item.size * f).toInt()
                    if (y in (info.viewportStartOffset + 40)..(info.viewportEndOffset - 120)) break
                }
                state.animateScrollToItem(p, followScrollOffset(item?.size ?: 0, f, view))
                withFrameMillis { }   // 让下一轮读到滚完之后的布局
            }
        } finally {
            auto = false   // 中途被取消（换句了）也要把这个标志放下来，否则跟随再也停不下来
        }
    }
    LaunchedEffect(state) {
        snapshotFlow { state.isScrollInProgress }.collect { if (it && !auto) onUnpin() }
    }
    Box(modifier) {
        LazyColumn(
            state = state,
            // 缝要开在**视口**上，不是开在 contentPadding 上：后者只在滚到顶 / 底那一头生效，
            // 中途滚动时文字照样贴着栏切一刀（他第二张截图圈的就是这个）。
            // 视口往里缩 20dp（和左右那条缝同一条数），上下各留一条真空隙；边缘那半行再淡出 8dp
            // —— 淡出和听书页那个两行窗口共用一份 `edgeFade`
            modifier = Modifier.fillMaxSize().padding(vertical = 20.dp).edgeFade(8.dp),
            contentPadding = PaddingValues(horizontal = 20.dp),
        ) {
            items(map.paragraphs.size) { pi ->
                val lines = map.paragraphs[pi]
                // 整段还是一段话（不是一句一行），只有当前那一句的字换成强调色
                val local = if (line >= 0 && map.paragraphOf(line) == pi) line - map.paragraphOffsets[pi] else -1
                val span = buildAnnotatedString {
                    lines.forEachIndexed { si, l ->
                        withStyle(SpanStyle(color = if (si == local) theme.accentColor else theme.inkColor)) {
                            append(l.text)
                        }
                    }
                }
                Text(
                    span,
                    fontSize = fontSizeSp.sp,
                    lineHeight = (fontSizeSp * lineHeight).sp,
                    modifier = Modifier.fillMaxWidth().padding(bottom = 14.dp),
                )
            }
        }
        // 人自己滚开以后自动跟随停了：给一颗回得去的，别让"跟着读"变成一条断头路
        if (!pinned && line >= 0) {
            Box(
                Modifier
                    .align(Alignment.BottomEnd)
                    .padding(end = 20.dp, bottom = 8.dp)
                    .clip(RoundedCornerShape(14.dp))
                    .background(theme.accentColor)
                    .tap(onBackToCurrent),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    "回到当前",
                    fontSize = 12.sp,
                    color = Color.White,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 7.dp),
                )
            }
        }
        note?.let {
            Text(
                it,
                fontSize = 12.sp,
                color = theme.accentColor,
                textAlign = TextAlign.Center,
                modifier = Modifier.align(Alignment.BottomCenter).padding(horizontal = 24.dp, vertical = 8.dp),
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
