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
import com.bookreader.booksource.Rule
import com.bookreader.booksource.SearchEngine
import com.bookreader.core.model.BookMeta
import com.bookreader.data.AudioBook
import com.bookreader.data.BookLibrary
import com.bookreader.data.BookProvenance
import com.bookreader.data.ChapterFetch
import com.bookreader.data.ComicBook
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
    /** 列表页（搜书结果 / 精选榜单）那张封面的地址：这本书还没进书架时靠它画同一颗封面。 */
    val coverUrl: String = "",
) {
    /** 目录都读不全就别去现抓：本地导入的那一本根本没有书页。 */
    val hasSource get() = bookUrl.isNotEmpty() && sourceId.isNotEmpty()
}

/**
 * 目录那一格的行：永远按站点那份整大纲摆，本地已下的那一截只用来填字数。
 *
 * 为什么不按本地那份摆：一本 3066 章的小说「先拉开头」只有 10 章，按本地摆就是这一屏只剩 10 行、
 * 底下 3056 章像被删了 —— 而他要的是"列表还是全部，只是其余的没下"。
 * 字数**按章名对位**，不按下标：下载时空正文的那几章是跳过的，引擎的第 i 章不一定是大纲的第 i 章，
 * 按下标填会在末尾把 A 章的名字配上 B 章的字数（见 [alignOutline]）。
 * 大纲空着（本地导入的那一本没有书页）就整份退回本地清单。
 * 盘上比大纲多出来的那些章**不摆成第二行** —— 理由写在 [outlineRows] 上面。
 */
internal fun chapterRows(outline: List<String>, counted: List<Pair<String, String>>): List<Pair<String, String>> {
    if (outline.isEmpty()) return counted
    val at = alignOutline(outline, counted.map { it.first })
    val words = HashMap<Int, String>()
    at.forEachIndexed { i, p -> if (p >= 0) words[p] = counted[i].second }
    // 行数只认大纲：盘上多出来的那些章（正文里被标题规则误判出来的）不摆成第二行，
    // 那一行的章名会和上面那一行一模一样（真机《夜无疆》：882 条的目录被摆成 917 行）
    return outline.mapIndexed { j, t -> t to (words[j] ?: "") }
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
    /**
     * 点目录里某一章：从那一章开始读。只有已经下进书架的那一本给得起这个动作。
     *
     * 书 id 由这一页交回去：从精选/分类进来的那一份 `BookInfo` 手上没有 id，是这一页按
     * "哪条源的哪个书页" 从书架上找回来的（上面那格 LaunchedEffect）。壳子那份 `bookInfo`
     * 还是空的 —— 拿它去开就是开一本不存在的书，界面表现为"点完退回原地"（真机撞见过）。
     */
    onReadChapter: (String, Int) -> Unit,
) {
    val ctx = LocalContext.current
    var shown by remember { mutableStateOf(info) }
    var loading by remember { mutableStateOf(false) }
    var note by remember { mutableStateOf("") }
    var reload by remember { mutableIntStateOf(0) }
    var openIntro by remember { mutableStateOf(false) }
    var expanded by remember { mutableStateOf(false) }
    var rec by remember { mutableStateOf<DownloadRecord?>(null) }
    // 哪一章是「点了先下、下完就地翻开」那一枪叫起来的：整本那条 worker 是异步的，
    // 这一格就是它跑完该跳去的地方（-1 = 没人在等它）。就地补一章那一档是同步的，不经这一格。
    var pendingOpen by remember { mutableIntStateOf(-1) }
    var getting by remember { mutableStateOf<Int?>(null) }
    // 和搜书那一格同一条规矩：不维护第二份"内存里的进度"（那份东西进程一死就开始骗人），
    // 有任务在跑才 1 秒读一次磁盘上的下载表，没跑就只在动作之后读一次 —— 空转的轮询等于界面在自己刷自己。
    LaunchedEffect(shown.bookUrl, reload, pendingOpen) {
        readRec(ctx, shown.bookUrl)?.let { rec = it }
        var wasRunning = false
        while (rec?.running == true) {
            wasRunning = true
            delay(1_000L)
            readRec(ctx, shown.bookUrl)?.let { rec = it }
        }
        if (!wasRunning) return@LaunchedEffect
        // 任务刚跑完要再重读一遍这一页：书 id、每章字数、那颗 ↻ 三样都是按"架上那本"算的，
        // 不重读就停在任务开始前的那一份上（真机：补完 8 章回到这一页，那一屏还满屏 ↓）
        getting = null
        reload++
        val to = pendingOpen
        pendingOpen = -1
        // 只在下成功之后翻进去：那一枪失败了就停在原地，把失败那句实话留在页面上。
        // id 问下载表那一行，不问 `shown.bookId`：那一格是另一个 effect 按书架找回来的，
        // 它这一轮还没跑到（真机点第五章：书下完了、人还站在这一页，因为那时它是空的）
        if (to >= 0 && rec?.state == DownloadRecord.STATE_DONE) {
            val id = rec?.bookId.orEmpty().ifEmpty { shown.bookId }
            if (id.isNotEmpty()) onReadChapter(id, to)
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
    LaunchedEffect(shown.bookId, reload) {
        counted = if (shown.bookId.isEmpty()) emptyList() else withContext(Dispatchers.IO) {
            runCatching {
                library.open(shown.bookId).chapters.chapters.map { it.title to "${it.charCount}字" }
            }.getOrDefault(emptyList())
        }
    }
    // 列表永远按站点那份整目录摆：下了几章就只剩几行是假象（真机：点第五章补了 5 章，
    // 这一屏就只剩 5 行，其余三千章像被删了）。本地那份只用来填字数那一格。
    val chapters = chapterRows(shown.outline, counted)
    // 只有"这一本已经下进书架"才认得引擎的章序号：没下过的那一份章名是站点目录上的顺序，本地一个字都没有，
    // 点了没东西可读 —— 所以那一行不挂点击，也不摆一颗点了没反应的行
    val canRead = counted.isNotEmpty()
    val rule = sources.firstOrNull { it.rule?.id == shown.sourceId }?.rule
    val getKind = if (shown.hasSource) getKindOf(rule?.kind) else GetKind.None
    // 那颗「补这一章」：漫画/有声就地补那一章（架上还没有这一本就先开一本空壳，见 [ChapterFetch.openShell]）；
    // 小说补的是从头到那一章那一截（见 [GetKind.Prefix]）。
    // 整本下载正在跑的时候图标**照样摆着**：撤掉它们会让同一串行在任务起止之间变两次形状
    // （真机：点第五章，那一屏十行右边的箭头全没了，看着像列表被换了一份）。
    // 点下去该拒的那一枪由 [busyByJob] 拒，并当场说一句为什么 —— 不是不响应，是这一枪现在打不得。
    val canGet = getKind != GetKind.None
    // 哪几章已经在机上：读的就是阅读页那一屏同一份清单（章号 = 站点目录里的号，两边同一个主人）
    var have by remember { mutableStateOf<Set<Int>>(emptySet()) }
    var gotTick by remember { mutableIntStateOf(0) }
    var justGot by remember { mutableStateOf(-1) }
    val getScope = rememberCoroutineScope()
    LaunchedEffect(shown.bookId, canGet, gotTick) {
        have = if (!canGet || shown.bookId.isEmpty()) emptySet() else withContext(Dispatchers.IO) {
            val d = library.dir(shown.bookId)
            runCatching {
                if (rule?.kind == BookMeta.AUDIO) AudioBook(d).episodes().map { it.index }.toSet()
                else ComicBook(d).chapters().map { it.index }.toSet()
            }.getOrDefault(emptySet())
        }
    }

    /**
     * 整本那条 worker 正在跑 —— 这时候再点一章 = 往同一个队列名里塞第二枪，
     * `KEEP` 会把第二枪直接吃掉，界面上就是"点了没反应"。所以拒掉，并说一句为什么。
     */
    fun busyByJob(): Boolean {
        if (rec?.running != true) return false
        note = "整本正在下：等它跑完再点这一章"
        return true
    }

    /**
     * 小说那一档：起「从头补到这一章」那一枪（就是上面那颗「预览十章」同一条路，只是拉到第 to+1 章）。
     *
     * 不自己开一条"只抓一章"的路：那一章进不了架上那串字（要整本重灌重索引），
     * 引擎也不认一份不在 `chapter.idx` 里的章。补完这一页会自己重读：那一行换成 ↻，点它就读。
     */
    fun fetchPrefix(to: Int) {
        start(shown, sources, ctx, { note = it }, head = to + 1)
        // 那一枪是异步的（整本那条 worker）：这一行先挂上三个点，跑完由上面那个轮询收掉，
        // 并按 pendingOpen 就地翻进那一章 —— 他要的是"没下的点击就直接下载，然后阅读"
        getting = to
        pendingOpen = to
        reload++
    }

    /** 补完就地从那一章读起来 —— 和阅读页抽屉里那一行点下去是同一条规矩（[onRowTap]）。 */
    fun fetchOne(to: Int) {
        if (getting != null) return
        getting = to
        note = ""
        getScope.launch {
            val r = rule ?: run { getting = null; note = "那条源已经不在了，这一章没处下"; return@launch }
            val audio = r.kind == Rule.AUDIO
            var id = shown.bookId
            if (id.isEmpty()) {
                // 架上还没有这一本：先开一本空壳（清单 + 来源 + 整份大纲），不然那一章没地方落
                id = withContext(Dispatchers.IO) {
                    ChapterFetch.openShell(ctx, library, r, shown.bookUrl, shown.title, shown.author, shown.outline)
                }
                shown = shown.copy(bookId = id)
            }
            val got = runCatching {
                withContext(Dispatchers.IO) {
                    if (audio) ChapterFetch.audio(ctx, library, id, to) else ChapterFetch.comic(ctx, library, id, to)
                }
            }
            getting = null
            got.fold(
                onSuccess = { gotTick++; justGot = to; onReadChapter(id, to) },
                onFailure = { note = it.message ?: "这一章没抓到" },
            )
        }
    }

    /**
     * 这一章在不在本机：漫画/有声问那份章表（章号本来就是大纲号）；
     * 小说问"引擎里有没有一章对得上大纲这个位置" —— 空正文被跳过的那几章会让两个号错开，
     * 所以这里按章名对位，不按"排到第几章"那个前缀算。
     */
    val localAt = remember(shown.outline, counted, getKind) {
        if (getKind == GetKind.Prefix) alignOutline(shown.outline, counted.map { it.first })
            .filter { it >= 0 }.toSet()
        else emptySet()
    }

    fun hasAt(to: Int): Boolean = if (getKind == GetKind.Prefix) to in localAt else to in have

    /** 重下这一章：漫画/有声就地重取那一章的文件；小说那一节是「抹掉断点缓存 + 补齐全本」那一枪。 */
    fun redo(to: Int) {
        if (getting != null) return
        if (busyByJob()) return
        val id = shown.bookId
        getting = to
        note = ""
        if (getKind == GetKind.Prefix) {
            getScope.launch {
                val ok = runCatching {
                    withContext(Dispatchers.IO) {
                        // head = to+1：要的是"到这一章为止"，不是整本。盘上比它多时由 routeOf 抬回去，
                        // 所以重下中间某一节不会把后面的裁掉
                        ChapterFetch.novel(ctx, library, id, to, shown.title, head = to + 1)
                    }
                }
                ok.fold(
                    // 起了枪就别收圈：那一枪是异步的，由上面那个轮询在任务停下时收（顺带重读这一页）
                    onSuccess = { if (!it) { getting = null; note = ChapterFetch.goneSays(to) } },
                    onFailure = { getting = null; note = it.message ?: "这一章没抓到" },
                )
            }
            return
        }
        val audio = rule?.kind == Rule.AUDIO
        getScope.launch {
            val n = runCatching {
                withContext(Dispatchers.IO) {
                    if (audio) ChapterFetch.audio(ctx, library, id, to, force = true)
                    else ChapterFetch.comic(ctx, library, id, to, force = true)
                }
            }
            getting = null
            n.fold(onSuccess = { gotTick++ }, onFailure = { note = it.message ?: "这一章没抓到" })
        }
    }

    fun pickRow(to: Int) {
        if (!canGet) {
            if (canRead) onReadChapter(shown.bookId, to)
            return
        }
        when (onRowTap(hasAt(to), getting == to)) {
            RowTap.Open -> onReadChapter(shown.bookId, to)
            // 已经下过的那一章照开（整本在下也读得到）；要新起一枪的这一档得先看队列忙不忙
            RowTap.FetchThenOpen -> if (!busyByJob()) {
                if (getKind == GetKind.Prefix) fetchPrefix(to) else fetchOne(to)
            }
            RowTap.Wait -> Unit
        }
    }

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
            BookCover(shown.title, shown.bookId, Modifier.size(width = 78.dp, height = 116.dp), remote = shown.coverUrl)
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
                    ChapterLine(
                        name, words, Modifier.padding(horizontal = 16.dp),
                        // 状态那颗只在【补这一章】时转圈；重下的圈归左边那颗（两圈同转看不出谁在跑）
                        face = if (canGet) faceOf(hasAt(i), getting == i) else null,
                        pop = justGot == i,
                        // 那颗自己：没下过=补这一章，下过=重下这一章（三档都有重下，见 ChapterFetch.novel）
                        onGlyph = { if (hasAt(i)) redo(i) else pickRow(i) },
                        onClick = { pickRow(i) },
                    )
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
    // 一本有声书的"头"是一集不是十章：一集几十 MB，预览十章等于几个 GB
    info.hasSource && sources.firstOrNull { it.rule?.id == info.sourceId }?.rule?.isAudio == true -> listOf(
        RowAction(R.drawable.ic_play_first, "先听一集：只拉第一集 + 封面 + 简介") {
            start(info, sources, ctx, onNote, head = 1); onStarted()
        },
        RowAction(R.drawable.ic_download, "下载全书") {
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
    face: GetFace? = null,
    pop: Boolean = false,
    onGlyph: () -> Unit = {},
    onClick: (() -> Unit)? = null,
) {
    var m = modifier
    if (onClick != null) m = m.tap(onClick)
    // 字数挪到章名同一行的右边：原先它另起一行，量下来**有字数的行 167px、没字数的行 122px**
    // —— 同一屏里"下过的"和"没下过的"两种行差一截（他点名的就是这一条）。
    // 一行到底之后两种行同一个高、同一份内衬（还是上下各 14dp），信息一样不少
    Row(m.fillMaxWidth().padding(vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(title, fontSize = 15.sp, lineHeight = 20.sp, color = Chrome.ink, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
        if (words.isNotEmpty()) {
            Text(words, fontSize = 12.sp, color = Chrome.faint, modifier = Modifier.padding(start = 10.dp))
        }
        // 那颗：没下过是箭头（补这一章），下过是转圈箭头（重下这一章）；没有那颗时一行不占位。
        // 往右挪一点让墨的右边缘和表头最右那颗对上（表头那颗离屏边 43px）：箭头和转圈箭头的墨
        // 在各自画布里留的白不一样（ic_download 描到 x=19/24、ic_redownload 描到 x=21/24），
        // 所以两档各给一个数 —— 量的，对平后都是 43px
        face?.let {
            Box(Modifier.offset(x = if (it == GetFace.Absent) 3.5.dp else 2.dp)) {
                ChapterGet(it, Chrome.dot, Chrome.faint, pop, onGlyph)
            }
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
