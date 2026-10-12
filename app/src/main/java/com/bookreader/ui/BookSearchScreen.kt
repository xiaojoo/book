package com.bookreader.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import android.content.Context
import androidx.activity.compose.BackHandler
import androidx.compose.ui.unit.sp
import com.bookreader.R
import com.bookreader.booksource.BookHit
import com.bookreader.booksource.HttpFetcher
import com.bookreader.booksource.PageRequest
import com.bookreader.booksource.Rule
import com.bookreader.data.BookSourceStore
import com.bookreader.data.DownloadRecord
import com.bookreader.data.Downloads
import com.bookreader.data.StoredSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/**
 * 这一格里的三页。一次只开一页，所以三页共用一个页头。
 *
 * 「任务」「管理」是**全屏独立页**：开进去之后底栏整条让位（`AppScaffold(barVisible=false)`），
 * 所以这个状态提在 MainActivity 里，不在这一格内部 —— 底栏在壳上，壳要看得见才知道该不该画。
 */
enum class SearchPage { Search, Tasks, Manage }

/** 「先拉开头」那一颗拉几章。10 章够看出这本书要不要，又不至于把整本抓完。 */
private const val HEAD_CHAPTERS = 10

/** 搜索结果那一行 → 「本书信息」那一页。简介/大纲这一行没有，进去再补抓一次。 */
private fun infoOf(hit: BookHit) = BookInfo(
    title = hit.bookName,
    author = hit.author,
    category = hit.category,
    latestChapter = hit.latestChapter,
    wordCount = hit.wordCount,
    intro = hit.intro,
    bookUrl = hit.bookUrl,
    sourceId = hit.sourceId,
)

/**
 * 搜书：选一条书籍源 → 输书名 → 出结果 → 点「下载」抓完整本进书架。
 *
 * 入口是底栏第五格（不是藏在书架那颗 ⋯ 里）：按你自己说过的规矩，藏在弹层里等于没做。
 * 精选那一格没动。
 *
 * 结果不缓存：源站的搜索结果页本身一直在变，留着上一轮那张列表只会让人点到一本已经不存在的书上。
 */
@Composable
fun BookSearchScreen(
    sources: BookSourceStore,
    page: SearchPage,
    onPage: (SearchPage) -> Unit,
    onOpen: (String) -> Unit,
    onInfo: (BookInfo) -> Unit,
) {
    ApplySystemBars(darkIcons = !Chrome.night)
    // 全屏那两页把底栏让掉了，唯一的出口就剩页头那颗 ✕ 和系统返回键 —— 两个都要能回搜书
    if (page != SearchPage.Search) BackHandler { onPage(SearchPage.Search) }
    val keyboard = LocalSoftwareKeyboardController.current
    val scope = rememberCoroutineScope()
    var query by remember { mutableStateOf("") }
    var picked by remember { mutableStateOf<String?>(null) }
    var hits by remember { mutableStateOf<List<BookHit>?>(null) }
    var searching by remember { mutableStateOf(false) }
    var note by remember { mutableStateOf<String?>(null) }
    // 这一格里三页：搜索 / 任务 / 管理书籍源。都是**换页**，不是抽屉也不是盖住壳的一层 ——
    // 底栏那一排不参与，一次也只可能开一页（返回键因此永远只有一个去处）。
    var tick by remember { mutableIntStateOf(0) }
    val context = LocalContext.current
    var records by remember { mutableStateOf<List<DownloadRecord>>(emptyList()) }
    var reload by remember { mutableIntStateOf(0) }
    val anyRunning = records.any { it.running }
    // 不去维护第二份"内存里的进度"：那份东西进程一死就开始骗人。有任务在跑才 1 秒读一次，
    // 没有就只在动作之后读一次 —— 空转的轮询等于界面在自己刷自己。
    LaunchedEffect(reload, anyRunning) {
        records = withContext(Dispatchers.IO) { Downloads.reconcile(context) }
        while (records.any { it.running }) {
            delay(1_000L)
            records = withContext(Dispatchers.IO) { Downloads.reconcile(context) }
        }
    }
    // 默认源在第一次进这一格时种进去（后台）：不在 Activity 里做，不点开搜书就不碰磁盘
    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) { sources.seed() }
        tick++
    }
    val all = remember(tick) { sources.list() }
    val usable = all.filter { it.usable }
    val current: Rule? = usable.firstOrNull { it.id == picked && sources.enabled(it.id) }?.rule
        ?: usable.firstOrNull { sources.enabled(it.id) }?.rule

    fun submit() {
        val q = query.trim()
        val r = current
        when {
            r == null -> note = if (usable.isEmpty()) {
                "还没有可用的书籍源：点上面那排右边的「管理」导一条（一条源就是一个 JSON 文件）"
            } else {
                "所有源都关着，到「管理书籍源」里开一条"
            }
            q.isEmpty() -> { hits = null; note = "先输入要搜的书名" }
            searching -> {}
            else -> {
                keyboard?.hide()
                searching = true
                note = null
                hits = null
                scope.launch {
                    runCatching { Downloads.search(r, q) }.fold(
                        onSuccess = { found ->
                            searching = false
                            hits = found
                            note = if (found.isEmpty()) "「$q」在这条源上没搜到（源站可能改了版式，或者它要跑 JS）" else null
                        },
                        onFailure = {
                            searching = false
                            note = "搜不动：" + (it.message ?: it.javaClass.simpleName)
                        },
                    )
                }
            }
        }
    }

    val tip = remember { mutableStateOf<Tip?>(null) }
    var root by remember { mutableStateOf(Rect.Zero) }
    // 气泡画在这一格最外层，不画进 Popup：这台机上 Popup 那扇窗口的坐标原点不是应用窗口，
    // 量到整块偏 (-46, +267) px；外层这格的位置是自己 onGloballyPositioned 拿回来的，错不了。
    CompositionLocalProvider(LocalTip provides tip) {
        Box(Modifier.fillMaxSize().background(Chrome.page)) {
            Column(
                Modifier
                    .fillMaxSize()
                    .onGloballyPositioned { root = it.boundsInWindow() },
            ) {
                // 全屏那两页（任务/管理）不开这一行：底栏已经让位了，顶上再留一条「搜书」+源 chips
                // 就还是"在搜书里面"，正是他说"怎么能看到搜书的 tab"要消掉的东西。
                if (page == SearchPage.Search) {
                    ScreenHeader("搜书", trailing = usable.size.toString() + " / " + all.size + " 条源可用")
                    // 源那一排：选中哪条就用哪条搜。坏的那条也摆着（灰的），点它给一句为什么坏 ——
                    // 藏起来的话人会以为是搜索坏了
                    // 右内衬跟下面那行输入框同一档（4dp），两颗按钮的右边缘才在同一条线上；左边那 12dp 不动
                    Row(
                        Modifier.fillMaxWidth().padding(start = 12.dp, end = 4.dp).heightIn(min = 34.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Row(
                            Modifier.weight(1f).horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            all.forEach { s ->
                                WholeChip(
                                    s.title,
                                    modifier = Modifier.height(32.dp),
                                    selected = s.usable && s.id == current?.id,
                                    enabled = s.usable,
                                    radius = 10.dp,
                                ) {
                                    if (s.usable) {
                                        picked = s.id
                                        hits = null
                                        note = if (!sources.enabled(s.id)) "这条源在「管理书籍源」里被关着，开了才搜得动" else null
                                    } else {
                                        note = "这条源读不开：" + s.error
                                    }
                                }
                            }
                        }
                        Spacer(Modifier.width(8.dp))
                        val n = records.count { it.running || it.state == DownloadRecord.STATE_FAILED }
                        WholeChip(
                            if (n == 0) "任务" else "任务 $n",
                            modifier = Modifier.widthIn(min = 56.dp).height(32.dp),
                            radius = 10.dp,
                            selected = page == SearchPage.Tasks,
                        ) { onPage(if (page == SearchPage.Tasks) SearchPage.Search else SearchPage.Tasks) }
                        Spacer(Modifier.width(8.dp))
                        WholeChip(
                            "管理",
                            modifier = Modifier.widthIn(min = 56.dp).height(32.dp),
                            radius = 10.dp,
                            selected = page == SearchPage.Manage,
                        ) { onPage(if (page == SearchPage.Manage) SearchPage.Search else SearchPage.Manage) }
                    }
                }
                if (page == SearchPage.Tasks) {
                    PageHeader("下载任务") { onPage(SearchPage.Search); tick++ }
                    TasksPanel(Modifier.weight(1f), records, sources, context, onOpen, onNote = { note = it }, onAction = { reload++ })
                } else if (page == SearchPage.Manage) {
                    PageHeader("管理书籍源") { onPage(SearchPage.Search); tick++ }
                    SourceManagerPage(Modifier.weight(1f), sources) { tick++ }
                } else {
                // 输入行照全书搜索那一页的形状来：44dp 高、清除和搜索都在同一行内居中，底下一条 1px 中分线
                Row(
                    Modifier.fillMaxWidth().height(44.dp).padding(start = 16.dp, end = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    BasicTextField(
                        value = query,
                        onValueChange = { query = it; hits = null },
                        singleLine = true,
                        textStyle = TextStyle(fontSize = 17.sp, color = Chrome.ink),
                        cursorBrush = SolidColor(Chrome.accent),
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                        keyboardActions = KeyboardActions(onSearch = { submit() }),
                        modifier = Modifier.weight(1f),
                        decorationBox = { inner ->
                            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.CenterStart) {
                                if (query.isEmpty()) Text("输入书名，按回车搜", fontSize = 17.sp, color = Chrome.faint)
                                inner()
                            }
                        },
                    )
                    if (query.isNotEmpty()) {
                        Box(
                            Modifier.fillMaxHeight().widthIn(min = 48.dp).tap { query = ""; hits = null }.padding(horizontal = 10.dp),
                            contentAlignment = Alignment.Center,
                        ) { Text("清除", fontSize = 14.sp, color = Chrome.faint) }
                    }
                    Box(
                        Modifier.fillMaxHeight().widthIn(min = 56.dp).tap(enabled = !searching) { submit() }.padding(horizontal = 12.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            if (searching) "搜索中" else "搜索",
                            fontSize = 14.sp,
                            color = if (searching) Chrome.faint else Chrome.accent,
                        )
                    }
                }
                SearchHairline()
                note?.let {
                    Text(
                        it,
                        fontSize = 12.sp,
                        color = Chrome.faint,
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
                    )
                }
                val imePx = with(LocalDensity.current) { WindowInsets.ime.getBottom(this).toDp() }
                LazyColumn(
                    modifier = Modifier.fillMaxWidth().weight(1f),
                    contentPadding = PaddingValues(top = 4.dp, bottom = 16.dp + imePx),
                ) {
                    items(hits.orEmpty(), key = { it.sourceId + it.bookUrl }) { h ->
                        SearchResultRow(
                            h,
                            current,
                            records.firstOrNull { it.bookUrl == h.bookUrl },
                            onOpen = onOpen,
                            onNote = { note = it },
                            onStart = { update ->
                                val r = current
                                if (r == null) note = "先在上面选一条能用的源" else {
                                    Downloads.start(context, r, h, update)
                                    reload++
                                }
                            },
                            onHead = {
                                val r = current
                                if (r == null) note = "先在上面选一条能用的源" else {
                                    Downloads.start(context, r, h, update = false, head = HEAD_CHAPTERS)
                                    reload++
                                }
                            },
                            onPause = { Downloads.pause(context, h.bookUrl); reload++ },
                            onInfo = onInfo,
                        )
                    }
                }
                }
            }
            Box(Modifier.fillMaxSize()) { tip.value?.let { TipBubble(it, root) } }
        }
    }
}

/** 一次只可能开一页，所以这三页共用一个页头：标题 + 右上角那颗 ✕（唯一的出口要看得见）。 */
@Composable
internal fun PageHeader(title: String, onClose: () -> Unit) {
    // 左右都 16dp：标题那一格的左边缘和页头那颗 ✕ 的右边缘在同一条线上（以前右边是 8dp +
    // 一颗居中的图标，墨落在 21.5dp 处，跟左边差 5.5dp，一眼能看出歪）
    Row(
        Modifier.fillMaxWidth().height(52.dp).padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(title, fontSize = 19.sp, fontWeight = FontWeight.Medium, color = Chrome.ink, modifier = Modifier.weight(1f))
        // 页头这颗不挂气泡：它的名字就写在同一行左边（「下载任务」「管理书籍源」），再说一遍就是重复
        IconAction(R.drawable.ic_close, "关闭这一页", Chrome.faint, box = 44.dp, tipOn = false) { onClose() }
    }
    SearchHairline()
}

/**
 * 一条搜索结果：左边书名/作者/最新章，右边那一排按这一本在表上的状态换图标
 * （下载 → ⏸ → 📖 + ↻ / ▶）。图标和任务行那排是同一颗 `IconAction`。
 *
 * 这一行不再持有协程作用域：以前 `start()` 收的是 `rememberCoroutineScope()`，
 * 于是"这本书能不能下完"取决于"这一行还在不在屏幕上"。
 */
@Composable
private fun SearchResultRow(
    hit: BookHit,
    rule: Rule?,
    task: DownloadRecord?,
    onOpen: (String) -> Unit,
    onNote: (String) -> Unit,
    onStart: (Boolean) -> Unit,
    onHead: () -> Unit,
    onPause: () -> Unit,
    onInfo: (BookInfo) -> Unit,
) {
    val sub = listOfNotNull(
        hit.author.takeIf { it.isNotEmpty() },
        hit.category.takeIf { it.isNotEmpty() },
        hit.wordCount.takeIf { it.isNotEmpty() },
    ).joinToString(" · ")
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            // 左边那一整块就是「看看这本是什么」的命中区：书名/作者/最新章本来就摆在一起，
            // 再单独画一颗「详情」就是把同一件事说两遍
            Column(Modifier.weight(1f).tap { onInfo(infoOf(hit)) }) {
                Text(hit.bookName, fontSize = 15.sp, color = Chrome.ink, maxLines = 1)
                Text(
                    (if (sub.isEmpty()) "" else sub + " · ") + hit.sourceName,
                    fontSize = 12.sp,
                    color = Chrome.faint,
                    modifier = Modifier.padding(top = 2.dp),
                )
                if (hit.latestChapter.isNotEmpty()) {
                    Text(
                        "最新：" + hit.latestChapter,
                        fontSize = 12.sp,
                        color = Chrome.faint,
                        maxLines = 1,
                        modifier = Modifier.padding(top = 2.dp),
                    )
                }
            }
            Spacer(Modifier.width(10.dp))
            when {
                task == null -> Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    // 两颗分开：先拉十章 = 快点能读，下载 = 一次到底。他要的是"全本那颗不动"
                    SheetAction("先拉开头", Chrome.accent, rule != null) { onHead() }
                    SheetAction("下载", Chrome.accent, rule != null) { onStart(false) }
                }
                task.running -> IconAction(R.drawable.ic_pause, "暂停这一本", Chrome.accent) { onPause() }
                task.state == DownloadRecord.STATE_FAILED -> IconAction(R.drawable.ic_play, "重试：从没抓完的那一章接着来", Chrome.danger) { onStart(false) }
                task.resumable -> IconAction(R.drawable.ic_play, "接着下", Chrome.accent) { onStart(false) }
                else -> {
                    IconAction(R.drawable.ic_open, "打开来读", Chrome.accent) {
                        if (task.bookId.isEmpty()) onNote("这条记录指向的那本已经不在书架上了") else onOpen(task.bookId)
                    }
                    IconAction(R.drawable.ic_refresh, "更新：只补目录里新增的章", Chrome.faint) { onStart(true) }
                }
            }
        }
        // 一句回话就摆在按下去的那一行底下：抓完了不说"缺两章"，人会以为整本是齐的
        if (task != null && !task.running) {
            val line = when {
                task.error.isNotEmpty() -> task.error
                task.state == DownloadRecord.STATE_CANCELLED || task.state == DownloadRecord.STATE_PAUSED ->
                    (if (task.state == DownloadRecord.STATE_PAUSED) "已暂停，" else "已取消，") +
                        "抓到 " + task.done + " / " + task.total + " 章在磁盘上 —— 点 ▶ 从没抓的那一章接着来"
                task.missing > 0 -> "已进书架，缺 " + task.missing + " 章 —— 再点一次「下载」只补那几章"
                else -> "已进书架，共 " + task.total + " 章 · " + task.chars + " 字"
            }
            if (line.isNotEmpty()) {
                Text(
                    line,
                    fontSize = 11.sp,
                    color = if (task.state == DownloadRecord.STATE_FAILED) Chrome.danger else Chrome.faint,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
        }
    }
    SearchHairline()
}

/**
 * 管理书籍源：装一条、开/关、删一条。**搜书这一格里的一个页**（抽屉、居中弹窗、盖住壳的整页三种都试过了，
 * 他最后点的是"和任务同一形式的页内换页"）。
 *
 * 装这一格从"调系统选文件"换成了输入框：**网址优先**，那页直接回一段 JSON 就装得动；
 * 网址不行（没有能直连的地址，或者那份只在群里贴成一段文字）才把整段 JSON 粘进来。
 * 装不上不写"导入失败"那种空话，把 RuleLoader 那句原样贴出来 —— 它说的是哪个键不对、为什么不对。
 */
@Composable
private fun SourceManagerPage(modifier: Modifier, sources: BookSourceStore, onChanged: () -> Unit) {
    var tick by remember { mutableIntStateOf(0) }
    val all = remember(tick) { sources.list() }
    var restored by remember { mutableIntStateOf(-1) }
    var input by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var err by remember { mutableStateOf<String?>(null) }
    var done by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val keyboard = LocalSoftwareKeyboardController.current

    /** 网址优先：以 http(s):// 开头就去把那页整段拿回来，否则把框里的东西当作粘进来的 JSON。 */
    fun install() {
        val t = input.trim()
        if (t.isEmpty() || busy) return
        busy = true
        err = null
        done = null
        keyboard?.hide()
        val url = t.startsWith("http://", true) || t.startsWith("https://", true)
        scope.launch {
            val why = withContext(Dispatchers.IO) {
                if (url) {
                    runCatching { HttpFetcher().fetch(PageRequest(t)).text() }
                        .fold(
                            { sources.install(it, "从那个网址拿到的那段") },
                            { "抓不到 " + t + "：" + (it.message ?: it.javaClass.simpleName) }
                        )
                } else {
                    sources.install(t, "粘进来的那段")
                }
            }
            busy = false
            if (why == null) {
                input = ""
                done = "装上了，现在 " + sources.list().size + " 条源"
                tick++
            } else {
                err = why
            }
        }
    }

    Column(modifier.fillMaxWidth().verticalScroll(rememberScrollState())) {
        Row(
            Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp).heightIn(min = 44.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            BasicTextField(
                value = input,
                onValueChange = { input = it; err = null; done = null },
                textStyle = TextStyle(fontSize = 13.sp, color = Chrome.ink),
                cursorBrush = SolidColor(Chrome.accent),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { install() }),
                modifier = Modifier.weight(1f).heightIn(max = 104.dp).padding(vertical = 8.dp),
                decorationBox = { inner ->
                    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.CenterStart) {
                        if (input.isEmpty()) Text("填一条源的网址（那页要直接回 JSON），或把整份 JSON 粘进来", fontSize = 13.sp, color = Chrome.faint)
                        inner()
                    }
                },
            )
            Box(
                Modifier.widthIn(min = 56.dp).height(44.dp)
                    .tap(enabled = !busy && input.isNotBlank()) { install() }.padding(horizontal = 12.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    if (busy) "装上…" else "装上",
                    fontSize = 14.sp,
                    color = if (busy || input.isBlank()) Chrome.faint else Chrome.accent,
                )
            }
        }
        SearchHairline()
        Row(
            Modifier.fillMaxWidth().height(44.dp).padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Box(
                Modifier.fillMaxHeight().widthIn(min = 44.dp).tap(enabled = !busy) {
                    sources.templateText()?.let { input = it; err = null; done = null }
                },
                contentAlignment = Alignment.CenterStart,
            ) { Text("填一份模板进上面那格", fontSize = 13.sp, color = Chrome.accent) }
            Box(
                Modifier.fillMaxHeight().widthIn(min = 44.dp).tap { restored = sources.restoreBuiltin(); tick++ },
                contentAlignment = Alignment.CenterEnd,
            ) { Text("恢复随包", fontSize = 13.sp, color = Chrome.accent) }
        }
        if (restored >= 0) {
            Text(
                if (restored == 0) "这个包里没带随包源" else "已把随包那 $restored 条换成包里那份",
                fontSize = 12.sp,
                color = Chrome.faint,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 2.dp),
            )
        }
        err?.let {
            Text(it, fontSize = 12.sp, color = Chrome.danger, lineHeight = 17.sp,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 2.dp))
        }
        done?.let {
            Text(it, fontSize = 12.sp, color = Chrome.faint,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 2.dp))
        }
        Text(
            "一条源 = 一个 JSON 文件：按「装上」就落进 filesDir/booksource/，下面列表里那颗 ✕ 就是卸载。" +
                "要跑 JavaScript 才出得来正文的源装不上（手机上不内嵌 JS 引擎），装的时候当场告诉你为什么。" +
                "随包那几条删了不会自己回来，「恢复随包」按包里那份换回来（你改过它就没）。",
            fontSize = 11.sp,
            color = Chrome.faint,
            lineHeight = 16.sp,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
        )
        SearchHairline()
        if (all.isEmpty()) {
            Text("还没有书籍源", fontSize = 13.sp, color = Chrome.faint, modifier = Modifier.padding(16.dp))
        }
        all.forEach { s ->
                Row(
                    Modifier.fillMaxWidth().heightIn(min = 50.dp).padding(horizontal = 16.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(s.title, fontSize = 15.sp, color = if (s.usable) Chrome.ink else Chrome.faint)
                        Text(
                            s.error ?: s.rule?.homeUrl.orEmpty(),
                            fontSize = 11.sp,
                            color = if (s.usable) Chrome.faint else Chrome.danger,
                            maxLines = 2,
                        )
                    }
                    if (s.usable) {
                        RedSwitch(sources.enabled(s.id)) {
                            sources.setEnabled(s.id, it)
                            tick++
                        }
                    }
                    Box(Modifier.size(44.dp).tap { sources.remove(s.id); tick++ }, contentAlignment = Alignment.CenterEnd) {
                        Icon(painterResource(R.drawable.ic_close), "删掉这条源", tint = Chrome.faint, modifier = Modifier.size(17.dp))
                    }
                }
                SearchHairline()
            }
    }
}

/**
 * 行尾那一排图标动作：17dp 墨、没有底色（底色是药丸的，药丸已经撤了）、按住给一颗自绘气泡报它叫什么。
 *
 * 墨**贴格子的右边缘**而不是居中：居中时墨的右边缘落在格子右边往里 (格子-墨)/2 处，
 * 页头那颗 44dp、行里这几颗 40dp，两颗 ✕ 就差了 16px 对不齐，而且整列比左边的 16dp 缩进去一截。
 * 贴右之后墨的右边缘 = 格子右边缘 = 行内衬 16dp 那条线，和标题的左 16dp 同一条。
 */
@Composable
private fun IconAction(
    icon: Int,
    desc: String,
    tint: Color,
    enabled: Boolean = true,
    box: Dp = 40.dp,
    tipOn: Boolean = true,
    onClick: () -> Unit,
) {
    val src = remember { MutableInteractionSource() }
    val tip = LocalTip.current
    var at by remember { mutableStateOf(Rect.Zero) }
    val pressed = src.collectIsPressedAsState().value
    // 这一颗的身份证：气泡归"把它挂上去的那一颗"收，凭身份、不凭位置
    val owner = remember { Any() }
    Box(
        Modifier
            .size(box)
            .onGloballyPositioned { at = it.boundsInWindow() }
            // 和别处同一颗规则：按下不画涟漪、不叠高亮（tap 那颗不给 interactionSource，气泡拿不到"按住了"）
            .clickable(interactionSource = src, indication = null, enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.CenterEnd,
    ) {
        Icon(painterResource(icon), desc, tint = tint, modifier = Modifier.size(17.dp))
    }
    // 按住 = 把自己挂到那一格的气泡层上；抬手 = 只收回自己那条
    LaunchedEffect(pressed, at, tipOn) {
        if (pressed && tipOn && at != Rect.Zero) tip.value = Tip(at, desc, box, owner)
        else if (tip.value?.owner === owner) tip.value = null
    }
    // 真正治"手移开也不消失"的是这一条：按 ✕ 那一颗会把这一页整个换掉，这颗直接离开组合，
    // 上面那个 else 分支根本没机会跑；离开组合时不清，气泡就永久留在屏幕上了（他报的就是这个）
    DisposableEffect(owner) {
        onDispose { if (tip.value?.owner === owner) tip.value = null }
    }
}

/** 一颗按住的图标交给气泡层的全部信息：它在窗口里的位置、要写的字、那一格多宽（三角要指着正中）。 */
private class Tip(val at: Rect, val text: String, val box: Dp, val owner: Any)

private val LocalTip = compositionLocalOf<MutableState<Tip?>> { error("这一格没铺气泡层") }

/**
 * 气泡压在图标上方还是下方，以及那一格的顶内衬（px，**保证不为负**）。
 *
 * 单独抽出来当纯函数算，是因为它喂的是 Compose 的 `padding`：内衬一旦为负，Compose 不夹取而是
 * 直接抛 `IllegalArgumentException: Padding must be non-negative` 把进程打死。全屏那两页的页头
 * 离容器顶只剩 34dp，气泡要往上占 36dp → 按住 ✕ 那一瞬间就崩过一次（他报的是"点 ✕ 程序直接退出"）。
 *
 * @param risePx 整块气泡的高 = 药丸 26 + 三角 6 + 与图标的间隙 4
 */
internal fun tipSlot(atTop: Float, atBottom: Float, rootTop: Float, risePx: Float, gapPx: Float): Pair<Boolean, Float> {
    val above = atTop - rootTop >= risePx
    return above to if (above) atTop - rootTop - risePx else atBottom - rootTop + gapPx
}

/**
 * 按住图标时浮出来的那颗气泡，整颗自己画：系统那颗 Tooltip 要按住约 500ms 才出、样式也归它管，
 * Material3 的 `TooltipBox` 同样是别人家的浮层 —— 他要的是自己写。
 *
 * 画在这一格最外层那圈不接事件的满屏 Box 里（`LazyColumn` 会把条目裁在自己范围内，画在行里
 * 一抬到行上方就没了；`Popup` 那扇窗口的坐标原点又不在应用窗口上，量到整块偏 (-46, +267)px）。
 * 所以位置 = 图标在窗口里的位置 - 这一格在窗口里的位置，两个数都是 `boundsInWindow()` 自己拿的。
 *
 * 气泡右边缘贴图标格的右边缘（图标都在行尾，往左长才不会出屏），三角指着那一格的水平正中 →
 * 离右边缘 = 格子宽的一半。上方放不下就整块翻到图标下面、三角跟着翻向，见 [tipSlot]。
 * 底色 `Chrome.ink`、字 `Chrome.page`，夜间不用另写一套。
 */
@Composable
private fun TipBubble(t: Tip, root: Rect) {
    val density = LocalDensity.current
    val rise = with(density) { 36.dp.toPx() }
    val (above, topPx) = tipSlot(t.at.top, t.at.bottom, root.top, rise, with(density) { 4.dp.toPx() })
    Box(Modifier.fillMaxSize()) {
        Column(
            Modifier
                .align(Alignment.TopEnd)
                .padding(
                    end = with(density) { (root.right - t.at.right).toDp() },
                    top = with(density) { topPx.toDp() },
                ),
        ) {
            // 靠图标那一侧的 6dp 是给三角占的位：三角画在药丸外（drawBehind 排在 clip 前面，才不被它裁掉），
            // 但高度要算进气泡里，不然它落在这一格的测量范围外
            Box(Modifier.padding(if (above) PaddingValues(bottom = 6.dp) else PaddingValues(top = 6.dp))) {
                Box(
                    Modifier
                        .drawBehind {
                            val half = 6.dp.toPx()
                            val cx = (size.width - (t.box / 2f).toPx()).coerceIn(half + 4.dp.toPx(), size.width - 4.dp.toPx())
                            val base = if (above) size.height else 0f
                            val tip = if (above) size.height + 6.dp.toPx() else -6.dp.toPx()
                            drawPath(
                                Path().apply {
                                    moveTo(cx - half, base)
                                    lineTo(cx + half, base)
                                    lineTo(cx, tip)
                                    close()
                                },
                                Chrome.ink,
                            )
                        }
                        .clip(RoundedCornerShape(6.dp))
                        .background(Chrome.ink)
                        .padding(horizontal = 9.dp)
                        .height(26.dp),
                    contentAlignment = Alignment.Center,
                ) { Text(t.text, fontSize = 12.sp, color = Chrome.page, maxLines = 1) }
            }
        }
    }
}


/**
 * 任务：worker 在写的那张 `history.tsv` 原样列出来（新的在上面）。
 *
 * 这里不另存一份进度：表就是唯一事实，进程被杀、通知被关、界面没打开，数都还在同一处。
 * 一行能做的事按状态给：在跑 → 取消；失败/已取消 → 续下（章级缓存在磁盘上，已抓的一章不再要）；
 * 已入库 → 打开 + 更新（只补目录里新增的那几章）；任何一条 → 删记录（连它的章缓存一起删）。
 */
@Composable
private fun TasksPanel(
    modifier: Modifier = Modifier,
    records: List<DownloadRecord>,
    sources: BookSourceStore,
    context: Context,
    onOpen: (String) -> Unit,
    onNote: (String) -> Unit,
    onAction: () -> Unit,
) {
    Column(modifier.fillMaxWidth()) {
    if (records.isEmpty()) {
        Text("还没有下载记录", fontSize = 13.sp, color = Chrome.faint, modifier = Modifier.padding(16.dp))
        return@Column
    }
    LazyColumn(Modifier.fillMaxWidth().weight(1f), contentPadding = PaddingValues(bottom = 16.dp)) {
        items(records, key = { it.bookUrl }) { r ->
            Row(
                Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(horizontal = 16.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(r.bookName, fontSize = 15.sp, color = Chrome.ink, maxLines = 1)
                    Text(
                        r.state + " · " + r.sourceName + " · " +
                            if (r.total > 0) r.done.toString() + " / " + r.total + " 章" else "读目录…" +
                            if (r.missing > 0) "（缺 " + r.missing + " 章）" else "",
                        fontSize = 12.sp,
                        color = Chrome.faint,
                        modifier = Modifier.padding(top = 2.dp),
                    )
                    if (r.error.isNotEmpty()) {
                        Text(r.error, fontSize = 11.sp, color = Chrome.danger, maxLines = 2, modifier = Modifier.padding(top = 2.dp))
                    }
                }
                Spacer(Modifier.width(10.dp))
                Row(horizontalArrangement = Arrangement.End) {
                    when {
                        r.running -> IconAction(R.drawable.ic_pause, "暂停这一本", Chrome.accent) {
                            Downloads.pause(context, r.bookUrl); onAction()
                        }
                        r.state == DownloadRecord.STATE_DONE -> {
                            IconAction(R.drawable.ic_open, "打开来读", Chrome.accent) {
                                if (r.bookId.isEmpty()) onNote("这本进书架的那次没记下 id，去书架上找它") else onOpen(r.bookId)
                            }
                            IconAction(R.drawable.ic_refresh, "更新：只补目录里新增的章", Chrome.faint) {
                                restart(r, sources, context, onNote); onAction()
                            }
                        }
                        else -> IconAction(R.drawable.ic_play, "接着下", Chrome.accent) {
                            restart(r, sources, context, onNote); onAction()
                        }
                    }
                    IconAction(R.drawable.ic_close, "删掉这条记录", Chrome.faint) {
                        Downloads.forget(context, sources, r); onAction()
                    }
                }
            }
            SearchHairline()
        }
    }
    }
}

/** 续下 / 更新都要原来的那条源还在：源被删掉就说实话，不拿一份猜出来的规则去抓。 */
private fun restart(r: DownloadRecord, sources: BookSourceStore, context: Context, onNote: (String) -> Unit) {
    val rule = sources.list().firstOrNull { it.rule?.id == r.ruleId }?.rule
    if (rule == null) {
        onNote("这条源（" + r.sourceName + "）已经不在了，剩下的章没法接着抓")
        return
    }
    Downloads.start(
        context, rule,
        BookHit(sourceId = rule.id, sourceName = rule.name, bookName = r.bookName, author = r.author, bookUrl = r.bookUrl),
        update = r.state == DownloadRecord.STATE_DONE,
    )
}

/** 1 个物理像素那条线：1dp 在这台机（2.75 倍）会铺成 3 行。 */
@Composable
private fun SearchHairline() {
    Box(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
            .height(with(LocalDensity.current) { 1.toDp() })
            .background(Chrome.hairline),
    )
}
