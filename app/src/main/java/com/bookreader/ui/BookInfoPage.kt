package com.bookreader.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.bookreader.R
import com.bookreader.booksource.HttpFetcher
import com.bookreader.booksource.SearchEngine
import com.bookreader.data.BookLibrary
import com.bookreader.data.BookProvenance
import com.bookreader.data.DownloadRecord
import com.bookreader.data.Downloads
import com.bookreader.data.StoredSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 「本书信息」那一页要摆的东西。两个方向凑出来：还没下过的（搜索结果/精选）和已经下过的（书架）。
 *
 * 为什么不带协程参数进去：这一页可能从三个入口打开，而"简介和大纲要不要现抓"取决于
 * 手上那份数据全不全 —— 判这件事的应该是页面自己，不是每个入口各写一遍。
 */
data class BookInfo(
    val title: String,
    val author: String = "",
    val category: String = "",
    val latestChapter: String = "",
    val wordCount: String = "",
    val intro: String = "",
    val outline: List<String> = emptyList(),
    val bookUrl: String = "",
    val sourceId: String = "",
    /** 已经进书架的那一本的 id；空 = 还没下过。 */
    val bookId: String = "",
) {
    /** 目录都读不全就别去现抓：本地导入的那一本根本没有书页。 */
    val hasSource get() = bookUrl.isNotEmpty() && sourceId.isNotEmpty()
}

/**
 * 一本书的信息页，版式照参考图3：顶栏一颗 ‹ → 封面 + 书名/作者/类目 → 一排数据 → 标签 chip →
 * 简介（长到一屏放不下才给「展开」）→ 一行「目录 ›」（进参考图4 那一屏）→ 底部动作条。
 *
 * 形态和「任务」「管理」「分类」同一族：整屏、底栏让位（这一页挂在壳的最外层）。
 *
 * 简介/大纲缺了才现抓一次（搜索结果和精选那一行只带书名作者，书页没读过）。抓不到就在页面上写明，
 * 不摆一段假简介糊人。图3 顶栏右边那颗分享、以及"月票/出圈指数"那一排，我们没有这个能力，
 * 不照抄过来当死控件。
 */
@Composable
fun BookInfoPage(
    info: BookInfo,
    sources: List<StoredSource>,
    library: BookLibrary,
    onBack: () -> Unit,
    onOpen: (String) -> Unit,
    /** 点目录里某一章：从那一章开始读。只有已经下进书架的那一本给得起这个动作。 */
    onReadChapter: (Int) -> Unit,
) {
    val ctx = LocalContext.current
    var shown by remember { mutableStateOf(info) }
    var loading by remember { mutableStateOf(false) }
    var note by remember { mutableStateOf("") }
    var reload by remember { mutableIntStateOf(0) }
    var openIntro by remember { mutableStateOf(false) }
    var expanded by remember { mutableStateOf(false) }
    var rec by remember { mutableStateOf<DownloadRecord?>(null) }
    // 和搜书那一格同一条规矩：不维护第二份"内存里的进度"（那份东西进程一死就开始骗人），
    // 有任务在跑才 1 秒读一次磁盘上的下载表，没跑就只在动作之后读一次 —— 空转的轮询等于界面在自己刷自己。
    LaunchedEffect(shown.bookUrl, reload) {
        readRec(ctx, shown.bookUrl)?.let { rec = it }
        while (rec?.running == true) {
            delay(1_000L)
            readRec(ctx, shown.bookUrl)?.let { rec = it }
        }
    }
    val srcName = sources.firstOrNull { it.rule?.id == shown.sourceId }?.rule?.name.orEmpty()
    // 从搜索结果/精选那一行进来的这一份不带 bookId（那两个入口手上只有站点给的字段）。
    // 到书架上按"哪条源的哪个书页"把它找回来：找得回来这一页才写得对 —— 那颗按钮该是「补齐全本」
    // 而不是「下载全书」，而「下载全书」再点一次过去只会多出第二本一样的书。
    LaunchedEffect(shown.sourceId, shown.bookUrl, reload) {
        if (shown.bookId.isNotEmpty() || !shown.hasSource) return@LaunchedEffect
        val id = withContext(Dispatchers.IO) {
            library.downloaded()[BookProvenance.key(shown.sourceId, shown.bookUrl)]
        }
        if (id != null) shown = shown.copy(bookId = id)
    }

    // 每章字数只有"已经下过"的那一本有：那是引擎自己索引出来的字符数（`Chapter.charCount`）。
    // 没下过的书要看字数只能去读每一章，而两家站的目录页整页搜「N字」命中 0 次 —— 所以那一格宁可空着，
    // 不编一个数上去。开索引是读文件，走 IO，不在组合线程上。
    var counted by remember { mutableStateOf<List<Pair<String, String>>>(emptyList()) }
    LaunchedEffect(shown.bookId) {
        counted = if (shown.bookId.isEmpty()) emptyList() else withContext(Dispatchers.IO) {
            runCatching {
                library.open(shown.bookId).chapters.chapters.map { it.title to "${it.charCount}字" }
            }.getOrDefault(emptyList())
        }
    }
    val chapters = counted.ifEmpty { shown.outline.map { it to "" } }
    // 只有"这一本已经下进书架"才认得引擎的章序号：没下过的那一份章名是站点目录上的顺序，本地一个字都没有，
    // 点了没东西可读 —— 所以那一行不挂点击，也不摆一颗点了没反应的行
    val canRead = counted.isNotEmpty()

    BackHandler(onBack = onBack)
    // 只有"既没简介也没大纲、又确实有条源能去读"时才补一次；书架上那本本地文件里都有，不重复打站子
    LaunchedEffect(shown.bookUrl, shown.sourceId) {
        if (shown.intro.isNotEmpty() || shown.outline.isNotEmpty() || !shown.hasSource) return@LaunchedEffect
        loading = true
        val rule = sources.firstOrNull { it.rule?.id == shown.sourceId }?.rule
        if (rule == null) {
            note = "这条源（${shown.sourceId}）已经不在了，简介和大纲没处读"
        } else {
            val h = runCatching {
                withContext(Dispatchers.IO) { SearchEngine(rule, HttpFetcher()).head(shown.bookUrl) }
            }.getOrNull()
            if (h == null) note = "书页读不回来（站点限流、改版，或者要跑 JS）" else {
                shown = shown.copy(intro = h.intro, outline = h.chapters.map { it.first })
            }
        }
        loading = false
    }

    // 先铺满整块窗口再让开安全区（和「AI 设置」「pptx」同一句）：这一页是从精选/书架直接开的整屏页，
    // 不在 AppScaffold 里，没人替它让开状态栏 —— 不让的话页头墨落在 y=44，压在状态栏那条上（真机量到）
    Column(Modifier.fillMaxSize().background(Chrome.page).safeDrawingPadding()) {
        // 图3 的顶栏只有一颗 ‹：这一页的标题就是书名，写在下面那一格里，顶栏再写一遍就是重复
        Row(
            Modifier
                .fillMaxWidth()
                .height(48.dp)
                .padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            BackSlot("返回", onBack)
        }
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
            BookCover(shown.title, shown.bookId, Modifier.size(width = 78.dp, height = 116.dp))
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(shown.title, fontSize = 19.sp, fontWeight = FontWeight.Medium, color = Chrome.ink, maxLines = 2)
                if (shown.author.isNotEmpty()) {
                    Text(shown.author, fontSize = 13.sp, color = Chrome.faint, modifier = Modifier.padding(top = 6.dp))
                }
                if (shown.latestChapter.isNotEmpty()) {
                    Text("最新：" + shown.latestChapter, fontSize = 13.sp, color = Chrome.faint, maxLines = 2, modifier = Modifier.padding(top = 6.dp))
                }
            }
        }
        MetaBar(shown, srcName, chapters.size)
        TagRow(shown, srcName)
        IntroBlock(shown, loading, openIntro) { openIntro = !openIntro }
        CatalogRow(chapters.size, rowActions(shown, rec, sources, ctx, onOpen, { note = it }, { reload++ }))
        rec?.takeIf { it.running }?.let {
            // 正在下的时候只报进度：不给一颗点了没反应的图标
            Line("正在下：${it.done} / ${it.total} 章", Chrome.faint, modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp))
        }
        if (note.isNotEmpty()) {
            Text(
                note, fontSize = 12.sp, color = Chrome.danger, lineHeight = 17.sp,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
            )
        }
        // 章名这一格自己滚，顶上那些不动（他点名的"只做这个章节的滚动条"）。
        // 默认 10 章，底下那一行渐变盖住最后半行、中间写「展开全部 N 章 ⌄」= 全部展开；
        // 展开之后这一行先没了（这时没"更多"可给，留着只是挡章名），等这一格滚到底再换成
        // 「折叠 ⌃」，点它回到 10 章
        val listState = rememberLazyListState()
        val scope = rememberCoroutineScope()
        // 那一颗「去底部」连点会叠出好几条滚动循环，互相抢同一段距离：新的一条起来之前先把上一条收掉
        var flow by remember { mutableStateOf<Job?>(null) }
        // 判「到底」要同时问两条：最后一章在不在屏幕上、还能不能再往前滚。只问后一条会在刚展开、
        // 还没过布局那一帧里读到 false（列表以为自己已经不动了），「折叠」那颗就闪一下。
        val atBottom by remember {
            derivedStateOf {
                val li = listState.layoutInfo
                li.visibleItemsInfo.lastOrNull()?.index == li.totalItemsCount - 1 && !listState.canScrollForward
            }
        }
        val more = chapters.size > CARD_CHAPTERS
        Box(Modifier.fillMaxWidth().weight(1f)) {
            LazyColumn(
                Modifier.fillMaxSize(),
                state = listState,
                // 底下那一行有自己的高度，从滚动区末尾的内衬里扣掉：它盖在这一格的最后 56dp 上，
                // 不许压住任何一章的章名
                contentPadding = PaddingValues(bottom = BAR_H.dp),
            ) {
                items(if (expanded) chapters.size else chapters.size.coerceAtMost(CARD_CHAPTERS)) { i ->
                    val (name, words) = chapters[i]
                    ChapterLine(name, words, Modifier.padding(horizontal = 16.dp), onClick = if (canRead) { { onReadChapter(i) } } else null)
                }
            }
            if (more && (!expanded || atBottom)) {
                val collapsed = expanded
                Row(
                    Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .height(BAR_H.dp)
                        .background(Brush.verticalGradient(listOf(Color.Transparent, Chrome.page)))
                        .tap {
                            if (collapsed) {
                                // 先滚回顶上再收：反过来（先收章数再滚）会让这一格在只剩 10 章的时候
                                // 还带着原来几百章的偏移，被夹到底那一片里露出来的是中间十几章
                                scope.launch {
                                    listState.scrollToItem(0)
                                    expanded = false
                                }
                            } else {
                                expanded = true
                            }
                        },
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    // 字报名字（读屏也只读这一句），箭头只画方向，别再重复一遍同样的话
                    Text(if (collapsed) "折叠" else "展开全部 ${chapters.size} 章", fontSize = 13.sp, color = Chrome.faint)
                    Spacer(Modifier.width(4.dp))
                    Icon(
                        painterResource(if (collapsed) R.drawable.ic_chevron_up else R.drawable.ic_chevron_down),
                        null,
                        tint = Chrome.faint,
                        modifier = Modifier.size(16.dp),
                    )
                }
            }
            // 展开之后才有这颗：一千多章靠手滑到底滑不动。没展开（只有 10 章）就整颗没了，不摆一颗用不上的
            if (expanded) {
                Box(
                    Modifier
                        .align(Alignment.BottomEnd)
                        .padding(end = 16.dp, bottom = (BAR_H + 12).dp)
                        .size(40.dp)
                        .shadow(4.dp, CircleShape)
                        .clip(CircleShape)
                        .background(Chrome.card)
                        .tap { flow?.cancel(); flow = scope.launch { listState.flowToEnd() } },
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        painterResource(R.drawable.ic_chevron_down), "去底部",
                        tint = Chrome.ink,
                        // 上下补 0.85dp：这张图的墨在 24 视口里 y 占 6.98..14.76，中心 10.87，
                        // 比视口中心高 1.13 个单位 —— 18dp 的格子里就是 0.85dp = 2.3 设备px，
                        // 摆在圆里看着偏上。数从 pathData 算的，换图重要重算。
                        modifier = Modifier.size(18.dp).offset(y = CHEVRON_INK_LIFT.dp),
                    )
                }
            }
        }
    }
}

/** 「目录」行右边的一颗图标动作：图标 + 名字（按住给气泡）+ 干什么。 */
private class RowAction(val icon: Int, val label: String, val onClick: () -> Unit)

/**
 * 「目录」这一行：左「目录」，右「全 N 章」+ 两颗图标。整行不再可点（点它没有去处 —— 目录不另开一屏了，
 * 章名就摊在这行下面），右边那颗 `›` 也跟着撤了。
 *
 * 右边那一簇的三处间隙一律 16dp，最后一颗的**墨**压在内容线上（= 上面那张卡片的右边线，也就是屏边往里 16dp）。
 * 原先量到的是 29.8 / 16.1 / 19.7dp 三档不等：按 44dp 的格子排，间隙其实是格子的余量，而两颗图的墨宽不一样
 * （ic_play_first 14.3dp、ic_download 9.9dp），于是"字→图标"被格子撑到 29.8dp、"图标→屏边"又缩进 19.7dp。
 *
 * 这一格改成**按墨排**：每颗的占位就是它自己的墨宽，44dp 的命中框居中溢出去（两颗叠 16dp，各自还剩 77px
 * 的独占区，和原先同一档）。**前提是那颗墨在自己的命中框里也居中** —— `IconAction` 默认是贴格子右边缘
 * （CenterEnd，别处两页对齐靠它），第一版没换它，溢出的那 14.85dp 全算到了墨的右边，间隙一处没动（他退回的
 * 那张图就是这个：70px 对 39px）。所以这里传 `align = Center`。
 */
@Composable
private fun CatalogRow(total: Int, actions: List<RowAction>) {
    Row(
        Modifier
            .fillMaxWidth()
            .height(52.dp)
            .padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("目录", fontSize = 16.sp, fontWeight = FontWeight.Medium, color = Chrome.ink)
        Spacer(Modifier.weight(1f))
        if (total > 0) Text("全 $total 章", fontSize = 12.sp, color = Chrome.faint)
        actions.forEach { a ->
            Spacer(Modifier.width(ROW_GAP.dp))
            Box(Modifier.size(width = inkOf(a.icon), height = 44.dp), contentAlignment = Alignment.Center) {
                // requiredSize：这格的占位只有墨那么宽，用 size 会被父给的窄约束夹住，命中框就真的小了
                IconAction(
                    a.icon, a.label, Chrome.faint,
                    modifier = Modifier.requiredSize(44.dp), box = 44.dp,
                    align = Alignment.Center, onClick = a.onClick,
                )
            }
        }
    }
}

/** 「目录」行右边那一簇的间隙，也是卡片/正文那条 16dp 内衬的同一档。 */
private const val ROW_GAP = 16

/**
 * 一张图标的墨在它自己 17dp 的格子里占多宽：**从 pathData 的 x 范围算**（÷24 视口 ×17dp），不量像素 ——
 * 量的数会跟着字体缩放、截图缩放漂，而图标的墨范围是文件里写死的。
 * 换图标或改图里的路径，这一档要重算，否则那一簇的间隙又会不等。
 */
private fun inkOf(icon: Int): Dp = when (icon) {
    // 两张叠着的页 + 三角：x 2.15..22.35（含 1.7 描边）
    R.drawable.ic_play_first -> 14.3.dp
    // 一本合着的书：x 4..20
    R.drawable.ic_open -> 11.3.dp
    // 向下箭头 + 落点横线：x 5..19
    else -> 9.9.dp
}

/**
 * 那两颗图标给哪一对，看"这本在不在架上、拉了开头还是全本"，一次只给一对。
 * 正在下的时候回空表：那一行只报进度，不摆一颗点了没反应的图标。
 */
private fun rowActions(
    info: BookInfo,
    rec: DownloadRecord?,
    sources: List<StoredSource>,
    ctx: android.content.Context,
    onOpen: (String) -> Unit,
    onNote: (String) -> Unit,
    onStarted: () -> Unit,
): List<RowAction> = when {
    rec?.running == true -> emptyList()
    info.bookId.isNotEmpty() -> listOf(
        RowAction(R.drawable.ic_open, "打开来读") { onOpen(info.bookId) },
        RowAction(R.drawable.ic_download, "补齐全本：只补目录里新增的章") {
            start(info, sources, ctx, onNote, head = 0); onStarted()
        },
    )
    // 一篇文献就是一支 PDF：没有"前十章"可拉，那颗在这条源上点了抓回的是同一支文件
    info.hasSource && sources.firstOrNull { it.rule?.id == info.sourceId }?.rule?.isPaper == true -> listOf(
        RowAction(R.drawable.ic_download, "下载 PDF") {
            start(info, sources, ctx, onNote, head = 0); onStarted()
        },
    )
    info.hasSource -> listOf(
        RowAction(R.drawable.ic_play_first, "预览十章：只拉前十章 + 封面 + 简介") {
            start(info, sources, ctx, onNote, head = 10); onStarted()
        },
        RowAction(R.drawable.ic_download, "下载全书") {
            start(info, sources, ctx, onNote, head = 0); onStarted()
        },
    )
    else -> emptyList()
}

/** 下载表在磁盘上，读它要走 IO。读不到（这本还没下过）回 null，调用方保留上一份、不清空。 */
private suspend fun readRec(ctx: android.content.Context, url: String): DownloadRecord? =
    withContext(Dispatchers.IO) { Downloads.history(ctx).find(url) }

/** 图3 那一排数据。只摆真拿得到的：章数 / 字数 / 分类 / 来源，每个数写明它是什么口径。 */
@Composable
private fun MetaBar(info: BookInfo, srcName: String, total: Int) {
    val cells = buildList {
        if (total > 0) add("$total 章" to "目录")
        if (info.wordCount.isNotEmpty()) add(info.wordCount to "字数")
        if (info.category.isNotEmpty()) add(info.category to "分类")
        if (srcName.isNotEmpty()) add(srcName to "来源")
    }
    if (cells.isEmpty()) return
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(Chrome.card)
            .padding(vertical = 10.dp),
    ) {
        cells.forEach { (value, label) ->
            Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                Text(value, fontSize = 15.sp, fontWeight = FontWeight.Medium, color = Chrome.ink, maxLines = 1)
                Text(label, fontSize = 11.sp, color = Chrome.faint, modifier = Modifier.padding(top = 4.dp))
            }
        }
    }
}

/** 图3 书名下面那一排标签 chip：我们的真标签就是类目和来源。 */
@Composable
private fun TagRow(info: BookInfo, srcName: String) {
    val tags = listOf(info.category, srcName).filter { it.isNotEmpty() }
    if (tags.isEmpty()) return
    Row(
        Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        tags.forEach {
            Box(
                Modifier
                    .clip(RoundedCornerShape(4.dp))
                    .background(Chrome.chip)
                    .padding(horizontal = 8.dp, vertical = 4.dp),
            ) { Text(it, fontSize = 11.sp, color = Chrome.faint) }
        }
    }
}

/**
 * 简介。参考图3 那一屏这一段**没有"简介"这个标题**（正文直接接在标签行下面）。
 *
 * 折叠不给按钮、不给图标：**正文自己就是那一颗**，点它展开、再点收回来。看得见的线索只有行末
 * 那个省略号（`overflow = Ellipsis`），这也是参考图原本的样子 —— 少一颗挂在行尾的圆角按钮，
 * 就少一次"它到底能不能点"的猜。
 *
 * 只有真的被截断了才允许点：判据问排版结果（`isLineEllipsized`），不问字数。原先写的是
 * "超过 64 字"，那个数是拍的：14sp 在这台机的正文宽（992px）里一行 25.7 个字，48 字的《剑来》
 * 只有两行，而 64 字（2.5 行）照样会亮出一颗永远用不上的展开；他那台机还开着 font_scale 0.81，
 * 字数和行数根本不是固定比例。展开之后要能收回来，所以 `open` 时也算可点。
 */
private const val INTRO_LINES = 4

@Composable
private fun IntroBlock(info: BookInfo, loading: Boolean, open: Boolean, onToggle: () -> Unit) {
    var clipped by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp)) {
        when {
            loading -> Text("正在读…", fontSize = 14.sp, color = Chrome.faint)
            info.intro.isEmpty() -> Text("这条源没写简介在哪一格，页面上也没有", fontSize = 13.sp, color = Chrome.faint)
            else -> Paragraph(info.intro, INTRO_LINES, open, { clipped = it }, if (clipped || open) onToggle else null)
        }
    }
}

/** 默认露几章。和「预览十章」同一档，两数一致才对得上。 */
private const val CARD_CHAPTERS = 10

/** 底下那一行「展开全部 / 折叠」的高度（dp）：它盖住的这一条同时也是滚动区末尾的内衬，所以章名永不藏在它下面。 */
private const val BAR_H = 56

/** 「去底部」的动画档：见 `flowToEnd`。每毫秒 120px，总时长夹在 260ms 到 1.4s 之间。 */
private const val FLOW_PX_PER_MS = 120f
private const val FLOW_MIN_MS = 260f
private const val FLOW_MAX_MS = 1400f

/**
 * 每帧最多走多少像素。真机量过这条曲线（1195 章那一本，逐帧打点）：一步 233px 以内还能 16.7ms 一帧，
 * 600-1900px 就掉到 32.8ms（30 帧），5500px 要 65.7ms，13,888px 要 100ms，最后那一步 52,786px 要 **836ms**
 —— 也就是他说的"跳转会有一点时间"：一次给到底的那一大步，界面要冻将近一秒。
 * 所以这里给每一步设上限，走完为止，时长自己按距离摊开（1195 章 ≈ 2 秒），不再有大台阶。
 */
private const val FLOW_CAP = 2400f

/** ic_chevron_down 的墨在 24 视口里偏上 1.13 个单位（y 占 6.98..14.76），18dp 的格子里折 0.85dp。 */
private const val CHEVRON_INK_LIFT = 0.85f

/**
 * 一行章名，副行是字数。整屏目录那一页用它（本书信息那一页只留「目录」一行，不再预览章名）。
 *
 * 副行只有"已下过的那一本"才有数（引擎索引出来的 `charCount`）；没有数就整行少一行，不写"字数未知"占位。
 * 上下内衬 14dp 是照参考图定的：它那两行一格的行周期量到 184px = 67dp，我们这一格 15sp 行盒 + 5dp +
 * 12sp 副行 + 上下各 14dp = 190px。
 *
 * `onClick` 给了整行才可点（不画底色、不加下划线 —— 它要看起来还是章名）。
 */
@Composable
private fun ChapterLine(
    title: String,
    words: String,
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
) {
    var m = modifier
    if (onClick != null) m = m.tap(onClick)
    Column(m.fillMaxWidth().padding(vertical = 14.dp)) {
        Text(title, fontSize = 15.sp, lineHeight = 20.sp, color = Chrome.ink, maxLines = 1)
        if (words.isNotEmpty()) {
            Text(words, fontSize = 12.sp, color = Chrome.faint, modifier = Modifier.padding(top = 5.dp))
        }
    }
}

/**
 * 流到底：一帧一步推到末尾，两头慢中间快（三次缓入缓出），每一步都有上限（`FLOW_CAP`）。
 *
 * 为什么要自己写这一圈：一次给到底（`dispatchRawDelta(1_000_000f)`）那一大步真机要 **836ms**，
 * 界面冻将近一秒 —— 他说的"点击后跳转会有一点时间"就是这一下。逐帧打点量到的曲线写在 `FLOW_CAP` 上面。
 * 为什么不用 `animateScrollToItem`：它每帧最多吃 200px、最多跑 500ms，一千多章跑到半路就硬跳完。
 *
 * `ms` 只是缓动表自己的节拍（1195 章 = 1.2 秒），实际墙钟时长由步长上限摊开：真机量到 70 帧 / 2.59 秒
 * 走完 145,915px，中间那一段每帧 2400px、15~30 帧/秒。要再快就得允许更大的台阶，那样又会看见卡顿。
 * 每帧问列表"这一步你吃了多少"，吃多少算多少，所以章高估错了也只会提前收住；
 * 最后再夹一次真正的末尾（末尾留着 56dp 内衬，不夹这一下「折叠」那一行不会出来）。
 */
private suspend fun LazyListState.flowToEnd() {
    val li = layoutInfo
    val vis = li.visibleItemsInfo
    if (vis.isEmpty()) return
    val last = vis.last()
    val avg = vis.sumOf { it.size } / vis.size.toFloat()
    val viewH = li.viewportEndOffset - li.viewportStartOffset
    val total = ((li.totalItemsCount - 1 - last.index) * avg + last.offset + last.size + li.afterContentPadding - viewH)
        .coerceAtLeast(0f)
    val ms = (total / FLOW_PX_PER_MS).coerceIn(FLOW_MIN_MS, FLOW_MAX_MS)
    val t0 = withFrameNanos { it }
    var done = 0f
    while (done < total) {
        val now = withFrameNanos { it }
        val f = ((now - t0) / 1_000_000f / ms).coerceAtMost(1f)
        val e = if (f < .5f) 4 * f * f * f else 1 - (2 - 2 * f) * (2 - 2 * f) * (2 - 2 * f) / 2
        val want = total * e
        if (want > done) {
            val asked = (want - done).coerceAtMost(FLOW_CAP)
            val got = dispatchRawDelta(asked)
            done += got
            // 要了多少没给满 = 已经到底（每章等高是估的，估多了也在这里收住）
            if (got < asked - 1f) break
        }
        if (now - t0 > 6_000_000_000L) break
    }
    dispatchRawDelta(1_000_000f)
}

private fun start(
    info: BookInfo,
    sources: List<StoredSource>,
    ctx: android.content.Context,
    onNote: (String) -> Unit,
    head: Int,
) {
    val rule = sources.firstOrNull { it.rule?.id == info.sourceId }?.rule
    if (rule == null) {
        onNote("这条源（${info.sourceId}）已经不在了，抓不动")
        return
    }
    Downloads.start(
        ctx, rule,
        com.bookreader.booksource.BookHit(
            sourceId = rule.id, sourceName = rule.name, bookName = info.title,
            author = info.author, bookUrl = info.bookUrl,
        ),
        update = false,
        head = head,
    )
}

/**
 * 一段正文。多段合成一个 Text（段与段之间留一个真空行），这样"被截断"只有一处可问：
 * 分好几个 Text 各夹四行的话，光看第一个就说不清整段到底有没有被切掉。
 * `onTap` 给了就把整段正文变成那一颗（不画底色、不加下划线 —— 它要看起来还是正文）。
 */
@Composable
private fun Paragraph(
    text: String,
    maxLines: Int = 0,
    open: Boolean = false,
    onClipped: (Boolean) -> Unit = {},
    onTap: (() -> Unit)? = null,
) {
    val body = text.split('\n').filter { it.isNotBlank() }.joinToString("\n\n")
    var m: Modifier = Modifier
    if (onTap != null) m = m.tap(onTap)
    Text(
        body,
        fontSize = 14.sp,
        lineHeight = 21.sp,
        color = Chrome.ink,
        maxLines = if (open || maxLines == 0) Int.MAX_VALUE else maxLines,
        overflow = TextOverflow.Ellipsis,
        onTextLayout = { r -> onClipped(r.isLineEllipsized((r.lineCount - 1).coerceAtLeast(0))) },
        modifier = m,
    )
}

@Composable
private fun Line(text: String, color: Color, modifier: Modifier = Modifier, size: androidx.compose.ui.unit.TextUnit = 14.sp) {
    Text(text, fontSize = size, lineHeight = (size.value + 6f).sp, color = color, modifier = modifier)
}
