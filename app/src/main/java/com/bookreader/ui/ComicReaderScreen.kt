package com.bookreader.ui

import android.app.Activity
import android.graphics.Bitmap
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.bookreader.core.engine.ReaderGestures
import com.bookreader.core.engine.TapZone
import com.bookreader.data.BookLibrary
import com.bookreader.data.BookProvenance
import com.bookreader.data.ChapterFetch
import com.bookreader.data.ComicBook
import com.bookreader.data.ComicImages
import com.bookreader.data.ReaderPrefs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.abs
import java.io.File

/** 底栏开的是哪一层。一次只开一层（和小说那一屏同一条规矩）。 */
private enum class Sheet { None, Chapters, Marks, Settings }

/**
 * 读一本漫画。一屏一张，**点哪翻哪页和小说同一套**：`ReaderGestures.zoneOf` 那三个区，
 * 左右 1/3 是上一页/下一页（走到本章头一张再点左就退到上一话的最后一张），中间那区收放栏。
 * 底栏就是小说那一排四颗 **目录 / 书签 / 夜间 / 设置**，设置里只留亮度 + 背景两样。
 *
 * 目录是**从左边推满高度的抽屉**（和小说那一屏同一份形状、同一批色），每行行首一颗
 * 该章第一页的小缩略图。
 *
 * 为什么不套 `ReaderScreen`：那条路的一切都围着"一串字 → 排成页"（行网格、字号、行距、字符偏移、
 * 听书取句），漫画一个字都没有；它和 pdf/pptx 才是一族 —— 整页渲染，共用同一批控件。
 */
@Composable
fun ComicReaderScreen(
    bookId: String,
    title: String,
    library: BookLibrary,
    prefs: ReaderPrefs,
    /** 「本书信息」里点某一章进来：从那一章起。-1 = 回到上次读的位置。 */
    startChapter: Int = -1,
    onBack: () -> Unit,
) {
    // 和小说那一屏同一个拿法：LocalContext 直接 cast 成 Activity 是 lint 报的错（Context 不一定是 Activity），
    // 而窗口亮度这件事只有 Activity 的 window 改得了
    val activity = LocalView.current.context as? Activity
    val ctx = LocalContext.current
    var themeIndex by remember { mutableIntStateOf(prefs.themeIndex) }
    val theme = remember(themeIndex) { ReaderTheme.of(themeIndex) }
    var brightness by remember { mutableFloatStateOf(prefs.brightness) }
    BackHandler(onBack = onBack)
    // 亮度 -1 = 跟随系统，那时要把窗口属性还原成默认（和小说那一屏同一条写法）
    LaunchedEffect(brightness, activity) {
        val window = activity?.window ?: return@LaunchedEffect
        window.attributes = window.attributes.apply {
            screenBrightness = if (brightness < 0f) -1f else brightness.coerceIn(0.02f, 1f)
        }
    }
    // 换背景/夜间要连着外壳一起换：那六档色块和外壳的夜间是同一个 themeIndex
    LaunchedEffect(themeIndex) { prefs.themeIndex = themeIndex; Chrome.night = theme.night }

    // `ComicBook` 把章表 lazy 读一次就缓存住：补完一章要换一个新对象才读得到那一行新 append 的
    val bookTick = remember { mutableIntStateOf(0) }
    val book = remember(bookId, bookTick.intValue) { ComicBook(library.dir(bookId)) }
    var chapters by remember { mutableStateOf<List<ComicBook.Chapter>>(emptyList()) }
    // 整本的章名来自下载时存下的那份目录：架上那行写"全书 1068 话"，抽屉就该能看见 1068 行。
    // 只列已下载的话，「先拉开头」那本看上去就只有十章 —— 像被抓坏了
    var outline by remember { mutableStateOf<List<String>>(emptyList()) }
    LaunchedEffect(bookId, bookTick.intValue) {
        chapters = withContext(Dispatchers.IO) { book.chapters() }
        outline = withContext(Dispatchers.IO) { BookProvenance(library.dir(bookId)).outline() }
    }

    val start = remember(bookId, startChapter) {
        // 从「本书信息」点某一章进来时从那一章起；否则回到上次读的位置
        if (startChapter >= 0) startChapter to 0 else prefs.loadComicPos(bookId) }
    var chapter by remember { mutableIntStateOf(start.first.coerceAtLeast(0)) }
    var page by remember { mutableIntStateOf(start.second.coerceAtLeast(0)) }
    var pages by remember { mutableStateOf<List<File>>(emptyList()) }
    var sheet by remember { mutableStateOf(Sheet.None) }
    /** 上下两条栏在不在。中间那一区只管它，亮度那一层只有按「设置」才开（他点名的口径）。 */
    var bars by remember { mutableStateOf(true) }
    var note by remember { mutableStateOf<String?>(null) }
    var marksTick by remember { mutableIntStateOf(0) }
    // 正在补的那一话（同一瞬间只允许一枪）和刚补完的那一话（只有那一行的勾弹一下）
    var getting by remember { mutableStateOf<Int?>(null) }
    // 目录抽屉的排法（正序/倒序）：和小说那一屏各存一份（"几千话只想看最新的"通常是漫画这一档的需求）
    var chapterDesc by remember { mutableStateOf(prefs.chapterDesc) }
    // 怎么翻：false = 左右翻（一张一屏），true = 上下连着滚（条漫）。存按屏这一档，理由同上面那颗
    var vertical by remember { mutableStateOf(prefs.comicVertical) }
    // 撑满状态栏和导航栏：这一档自己存一份（漫画撑满不该把小说也撑满）
    var edge by remember { mutableStateOf(prefs.edgeToEdge) }
    var justGot by remember { mutableStateOf(-1) }
    val scope = rememberCoroutineScope()
    val marks = remember(marksTick) { prefs.comicBookmarks(bookId) }
    val have = remember(chapters) { chapters.associateBy { it.index } }
    val names = if (outline.size >= chapters.size) outline else chapters.map { it.title }
    val total = maxOf(names.size, chapters.size)

    // bookTick 也在键里：重下当前这一话之后要把图重新读一遍，不然屏上还是旧那几张
    LaunchedEffect(bookId, chapter, bookTick.intValue) {
        pages = withContext(Dispatchers.IO) { book.pages(chapter) }
        if (page >= pages.size) page = 0
    }
    LaunchedEffect(chapter, page) { prefs.saveComicPos(bookId, chapter, page) }

    val contentWidth = contentSizePx().width
    val images = remember(contentWidth) { ComicImages(contentWidth) }
    val thumbs = remember { ComicImages(THUMB_PX) }

    /** `lastPage = true` 是从右边缘退回去时用的：进那一话的最后一张。 */
    fun goChapter(to: Int, lastPage: Boolean = false, atPage: Int = 0) {
        if (to < 0 || to >= total) return
        // have 是这一次组合里那份表：刚补完的那一章已经落盘，但内存这一份要等下一次重组才追上，
        // 所以那一章放行（justGot 读的是状态本身，不是闭包里那份旧值）
        if (have[to] == null && to != justGot) {
            note = "第 ${to + 1} 话还没下到本机 —— 在目录里点那一行就补它"
            return
        }
        page = if (lastPage) (have[to]?.pages ?: 1).minus(1).coerceAtLeast(0) else atPage
        chapter = to
        sheet = Sheet.None
        note = null
    }

    /**
     * 补这一话：抓完就地翻进去读。
     *
     * 走的是整本下载同一个 downloader、同一个仓（下载目录就是书的目录，所以补一章不碰别的行），
     * 但不起 WorkManager 那一枪 —— 一本书一个任务是给整本准备的，这一枪就在这一屏里跑完。
     */
    fun fetch(to: Int) {
        if (getting != null) return
        getting = to
        note = null
        scope.launch {
            val pages = runCatching { withContext(Dispatchers.IO) { ChapterFetch.comic(ctx, library, bookId, to) } }
            getting = null
            pages.fold(
                onSuccess = {
                    bookTick.intValue++
                    justGot = to
                    goChapter(to)
                    // 不另说一句"补好了"：那一行自己变成勾、并且当场翻进去读 —— 那就是回执，
                    // 而这一屏正文上那行 note 只在收起栏时才露出来，写了也看不见
                },
                onFailure = { note = it.message ?: "这一话没抓到" },
            )
        }
    }

    /** 重下这一话：同一章的图重新取一遍（图床换图、上次只下到半截都靠它）。翻着的页不动。 */
    fun redo(to: Int) {
        if (getting != null) return
        getting = to
        note = null
        scope.launch {
            val n = runCatching {
                withContext(Dispatchers.IO) { ChapterFetch.comic(ctx, library, bookId, to, force = true) }
            }
            getting = null
            n.fold(
                onSuccess = { bookTick.intValue++; justGot = to },
                onFailure = { note = it.message ?: "这一话没抓到" },
            )
        }
    }

    /** 行点击的规矩在 [onRowTap] 那一颗里：已下的直接翻进去，没下的先补再翻，正在下的那一下不重发。 */
    fun pick(to: Int) {
        when (onRowTap(have[to] != null, getting == to)) {
            RowTap.Open -> goChapter(to)
            RowTap.FetchThenOpen -> fetch(to)
            RowTap.Wait -> Unit
        }
    }

    /** 翻一张：走到本章头一张再往回就退到上一话的最后一张，末页再往前就进下一话。点两侧和音量键走的是同一格。 */
    fun step(next: Boolean) {
        if (next) { if (page < pages.lastIndex) page += 1 else goChapter(chapter + 1) }
        else if (page > 0) page -= 1 else goChapter(chapter - 1, lastPage = true)
    }

    // 音量键：和小说同一颗开关（「我的 → 阅读偏好」里那格），只在屏幕上没压着东西时翻页
    VolumePageTurn(
        enabled = prefs.volumePaging,
        blockedBy = {
            when {
                sheet != Sheet.None -> "抽屉$sheet"
                bars -> "上下栏"
                else -> null
            }
        },
        onTurn = { up -> step(!up) },
    )

    // 撑满真正生效的那一刻：这一屏自己的两条栏和抽屉都收干净了才顶到边 ——
    // 栏还开着就把内衬归零，「从姑获鸟开始 / 16 / 57」那一条会和状态栏叠、「目录 书签 夜间 设置」会和导航条叠
    val immersive = edge && !bars && sheet == Sheet.None
    ApplySystemBars(darkIcons = !theme.night, immersive = immersive, bgArgb = theme.page)

    Box(
        Modifier
            .fillMaxSize()
            // **先铺满底色、再让开安全区**（和 `PdfReaderScreen` 顶上那条同一个写法，那一条早就写对了）。
            // 这两句原来反着：底色只铺进安全区里侧，edge-to-edge 之后系统那两条栏是透明的，栏背后露的
            // 是窗口默认灰 —— 在「设置 → 背景」换一档纸色，正文跟着换、状态栏和导航条那两条不跟着换。
            // 让开安全区本身不能省：底栏压在导航条上的话，"目录/书签/夜间/设置" 就和 ‹ □ ☰ 挤在同一行里。
            .background(theme.pageColor)
            // 撑满开着就**永远**顶到边，和菜单在不在无关：菜单收放只是浮层出现/消失，
            // 内容一格都不动（上一版把这两件事绑在一起，菜单一收一放正文就跳 84/130px）。
            // 内衬落在下面正文那一格（这一层要留着铺满整块窗口，`BarPaper` 才盖得到那两格）
            .pointerInput(chapter, pages.size) {
                detectTapGestures { off ->
                    // 抽屉开着的时候，点到它外面任何一侧都是**先收抽屉**，不是翻页
                    // （原来只有中间那一区收，点右边那 1/3 会去翻一页、抽屉还留着 —— 和小说那一屏的口径不一样）
                    // 抽屉自己那一格吃点击（consumesPanelTaps），所以点在抽屉里的空白处不会走到这里
                    if (sheet != Sheet.None) {
                        sheet = Sheet.None
                        return@detectTapGestures
                    }
                    when (ReaderGestures.zoneOf(size.width, off.x)) {
                        TapZone.Prev -> step(false)
                        TapZone.Next -> step(true)
                        TapZone.ToggleBars -> bars = !bars
                    }
                }
            }
            // 手指**甩**到本章头一张 / 末一张时也要能跨话：原先只有点两侧和音量键会跨，
            // 滑到头就顶住了（小说那一屏的左右滑是连着跨章的，"翻页和小说一样"缺的是这一条）。
            // 挂在 Initial 那一趟、全程不 consume：中间那些滑动照旧归 pager 自己滚，
            // 我们只记下"起手时停在哪一张"和总位移 —— 起手在末页才是"甩不动的那一下"。
            .pointerInput(chapter, pages.size) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                    val from = page
                    val last = pages.lastIndex
                    var dx = 0f
                    var dy = 0f
                    while (true) {
                        val e = awaitPointerEvent(PointerEventPass.Initial)
                        val c = e.changes.firstOrNull() ?: break
                        dx = c.position.x - down.position.x
                        dy = c.position.y - down.position.y
                        if (!c.pressed) break
                    }
                    val need = size.width / 3
                    if (sheet == Sheet.None && abs(dx) >= need && abs(dx) > abs(dy) * 2) {
                        if (dx < 0 && from == last) step(true)
                        else if (dx > 0 && from == 0) step(false)
                    }
                }
            },
    ) {
            // 正文那一格：撑满开着就顶到边（图铺到 y=0），不撑满就让开系统栏
            Box(Modifier.fillMaxSize().fixedBarsPadding(edge)) {
                if (pages.isEmpty()) {
                    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            if (chapters.isEmpty()) "这一本还没下到图" else "这一话的图不在本机",
                            fontSize = 14.sp,
                            color = theme.faintColor,
                            textAlign = TextAlign.Center,
                        )
                        Text(
                            "到「本书信息」点「下载全书」补齐 —— 漫画的图必须先在磁盘上：站点的图片地址是带签名的，过一会儿就失效",
                            fontSize = 12.sp,
                            color = theme.faintColor,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.padding(top = 8.dp, start = 24.dp, end = 24.dp),
                        )
                    }
                } else {
                    // key(chapter, vertical) 让换章和换翻法都重建那一台：不然它拿上一话的页数去夹新的页号，
                    // 进来先闪一张上一话的末页
                    key(chapter, vertical) {
                        if (vertical) {
                            // 条漫那一档：一列图连着往下滚，图与图之间不留缝（留了就像每页被切开）
                            val list = rememberLazyListState(page.coerceIn(0, (pages.size - 1).coerceAtLeast(0)))
                            LaunchedEffect(list) { snapshotFlow { list.firstVisibleItemIndex }.collect { page = it } }
                            // 反方向也要接上：点两侧和音量键是"状态要画面走"，少了这一条只有顶栏数字在动
                            LaunchedEffect(page) {
                                if (!list.isScrollInProgress && list.firstVisibleItemIndex != page) {
                                    list.scrollToItem(page.coerceIn(0, (pages.size - 1).coerceAtLeast(0)))
                                }
                            }
                            LazyColumn(state = list, modifier = Modifier.fillMaxSize()) {
                                items(pages.size) { i -> PageImage(pages[i], images, i + 1, theme, fillHeight = false) }
                            }
                        } else {
                            val pager = rememberPagerState(page.coerceIn(0, (pages.size - 1).coerceAtLeast(0))) { pages.size }
                            LaunchedEffect(pager) { snapshotFlow { pager.currentPage }.collect { page = it } }
                            // 反方向也要接上：滑动是 pager 报页号给状态，而**点两侧和音量键是状态要 pager 走**。
                            // 少了这一条，按下去只有日志里那句"翻页"和顶栏数字在动，画面那张还是旧的
                            // （真机量出来的：音量− 两下，日志两条"翻页"，正文节点还是"第 7 页"）
                            LaunchedEffect(page) {
                                if (pager.currentPage != page) pager.animateScrollToPage(page.coerceIn(0, (pages.size - 1).coerceAtLeast(0)))
                            }
                            HorizontalPager(state = pager, modifier = Modifier.fillMaxSize()) { i ->
                                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                    PageImage(pages[i], images, i + 1, theme)
                                }
                            }
                        }
                    }
                }
            }

        // 菜单那一层单独让安全区：撑满开着时正文/图顶到边，而这两条栏和抽屉还是正常上下排列
        // （不让的话「16 / 57」会压进状态栏、底栏会压进导航条 —— 他截图圈的就是那两处）
        Box(Modifier.fillMaxSize().fixedBarsPadding()) {
        // 栏收着的时候那句回话也要看得见：不然"这一话没下到"这种挡下来的动作就是**点了没反应**
        if (note != null && !bars && sheet == Sheet.None) {
            Text(
                note.orEmpty(),
                fontSize = 12.sp,
                color = theme.faintColor,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(horizontal = 24.dp, vertical = 28.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(theme.barColor)
                    .padding(horizontal = 12.dp, vertical = 8.dp),
            )
        }

        if (bars) TopLine(title, names.getOrNull(chapter) ?: "第 ${chapter + 1} 话", page + 1, pages.size, theme, onBack)

        if (bars && sheet != Sheet.Chapters) {
            ReaderBottomBar(
                theme = theme,
                modifier = Modifier.align(Alignment.BottomCenter),
                onToc = { sheet = Sheet.Chapters },
                onMarks = { sheet = if (sheet == Sheet.Marks) Sheet.None else Sheet.Marks },
                onToggleNight = { themeIndex = if (theme.night) 0 else ReaderTheme.ALL.lastIndex },
                onSettings = { sheet = if (sheet == Sheet.Settings) Sheet.None else Sheet.Settings },
            )
        }

        when (sheet) {
            Sheet.Chapters -> ChaptersDrawer(
                names, have, chapter, note, book, thumbs, theme, getting, justGot,
                desc = chapterDesc,
                onToggleSort = { chapterDesc = !chapterDesc; prefs.chapterDesc = chapterDesc },
                onRedo = { redo(it) },
                onPick = { pick(it) },
                onClose = { sheet = Sheet.None },
            )

            Sheet.Marks -> MarksDrawer(
                marks, names, theme, note,
                onAdd = {
                    prefs.addComicBookmark(bookId, chapter, page)
                    marksTick++
                    note = "已记下第 ${chapter + 1} 话 第 ${page + 1} 页"
                },
                onJump = { (c, p) -> goChapter(c, atPage = p) },
                onDelete = { (c, p) ->
                    prefs.removeComicBookmark(bookId, c, p)
                    marksTick++
                },
                onClose = { sheet = Sheet.None },
            )

            Sheet.Settings -> SettingsDrawer(
                theme,
                vertical = vertical,
                // 换翻法要把页号回到这一话的第一张：左右那一档是"一张一屏"，上下那一档是"滚到那一张的顶"，
                // 停在第 30 张进来只会看见一列图的中间，像翻坏了
                onVertical = {
                    if (vertical != it) {
                        vertical = it
                        prefs.comicVertical = it
                        page = 0
                    }
                },
                edge = edge,
                onEdge = { edge = !edge; prefs.edgeToEdge = edge },
                brightness,
                onBrightness = { brightness = it; prefs.brightness = it },
                onFollowSystem = { brightness = -1f; prefs.brightness = -1f },
                onPickTheme = { themeIndex = ReaderTheme.ALL.indexOf(it) },
            )

            Sheet.None -> Unit
        }
        }

        // 菜单亮着的时候，状态栏 / 导航条那两格用实心纸色盖住（下面的图在撑满那一档是铺到 y=0 的）：
        // 和小说/听书共用一份 `BarPaper` —— 他点名的"导航栏也要和 app 工具栏背景色一样"。
        // 画在正文那一格之后才盖得住；抽屉自己让过系统栏，和这两格不重叠
        BarPaper(theme, if (bars) 1f else 0f)
    }
}

/** 顶栏：‹ 书名 + 章名 / 右边页码。栏开着时一直在 —— 它是这一族唯一说"读到第几话第几页"的地方。
 *  这里**不再**自己套 `systemBarsPadding`：整屏那一格已经让开系统栏了，再套一层就是给状态栏留两条缝。 */
@Composable
private fun TopLine(title: String, chapter: String, page: Int, pages: Int, theme: ReaderTheme, onBack: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().height(52.dp).padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        BackSlot("返回书架", onBack)
        Column(Modifier.weight(1f).padding(start = 2.dp)) {
            Text(title, fontSize = 15.sp, fontWeight = FontWeight.Medium, color = theme.inkColor, maxLines = 1)
            Text(chapter, fontSize = 12.sp, color = theme.faintColor, maxLines = 1)
        }
        Text("$page / $pages", fontSize = 13.sp, color = theme.faintColor)
    }
}

/**
 * 目录：**从左边推满高度的抽屉**，300dp 宽、右缘 16dp 圆角、底色 `theme.barColor` ——
 * 和小说那一屏的 `ChaptersPanel` 同一份形状（露在外面的只有右边缘）。
 */
@Composable
private fun BoxScope.ChaptersDrawer(
    names: List<String>,
    have: Map<Int, ComicBook.Chapter>,
    current: Int,
    note: String?,
    book: ComicBook,
    thumbs: ComicImages,
    theme: ReaderTheme,
    getting: Int?,
    justGot: Int,
    desc: Boolean,
    onToggleSort: () -> Unit,
    onRedo: (Int) -> Unit,
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
        PanelTitle("目录 ${names.size} 话", theme, onClose, sortDesc = desc, onToggleSort = onToggleSort)
        note?.let {
            Text(it, fontSize = 12.sp, color = theme.accentColor, modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp))
        }
        // 倒序就是把号倒过来摆：点第几话还是那几话，不因为换了排法错位
        val cur = current.coerceIn(0, (names.size - 1).coerceAtLeast(0))
        val order = chapterOrder(names.size, desc)
        val at = chapterSlot(names.size, cur, desc)
        val state = rememberLazyListState(initialFirstVisibleItemIndex = at.coerceIn(0, (names.size - 1).coerceAtLeast(0)))
        // 换排法的时候把当前话留在原来那一屏位上
        LaunchedEffect(desc) { if (names.isNotEmpty()) state.scrollToItem(at.coerceIn(0, names.lastIndex)) }
        LazyColumn(
            state = state,
            modifier = Modifier.fillMaxWidth().weight(1f),
            contentPadding = PaddingValues(bottom = 12.dp),
        ) {
            items(order.size) { slot ->
                val i = order[slot]
                ChapterRow(
                    i, names[i], have[i], i == current, book, thumbs, theme,
                    getting == i, justGot == i, onPick, onRedo,
                )
            }
        }
    }
}

/** 一行：行首那颗该章首页的小缩略图 + 章名 + 右边张数。没下的那几行名字灰着、点了给一句为什么。 */
@Composable
private fun ChapterRow(
    index: Int,
    name: String,
    ch: ComicBook.Chapter?,
    current: Boolean,
    book: ComicBook,
    thumbs: ComicImages,
    theme: ReaderTheme,
    busy: Boolean,
    pop: Boolean,
    onPick: (Int) -> Unit,
    onRedo: (Int) -> Unit,
) {
    val first by produceState<File?>(null, index, ch) {
        value = if (ch == null) null else withContext(Dispatchers.IO) { book.thumbnail(index) }
    }
    val bmp by produceState<Bitmap?>(null, first?.absolutePath) {
        value = first?.let { withContext(Dispatchers.IO) { thumbs.page(it) } }
    }
    Row(
        Modifier
            .fillMaxWidth()
            .height(44.dp)
            .tap { onPick(index) }
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val b = bmp
        if (b != null && !b.isRecycled) {
            Image(
                bitmap = b.asImageBitmap(),
                contentDescription = null,
                modifier = Modifier.size(width = 20.dp, height = 28.dp).clip(RoundedCornerShape(2.dp)),
                contentScale = ContentScale.Crop,
            )
        } else {
            // 没下的那一行留一块底色而不是空白：行首空着会让整行看上去比邻居短一截
            Box(Modifier.size(width = 20.dp, height = 28.dp).background(theme.dividerColor))
        }
        Spacer(Modifier.width(10.dp))
        Text(
            name.ifBlank { "第 ${index + 1} 话" },
            fontSize = 14.sp,
            // 当前那一行**只把字染成强调色**，不铺底：铺一块 theme.pageColor 在白底抽屉上
            // 就是一条白带（他点名的就是这个），而"是哪一行"用字色说一遍就够了
            color = when {
                current -> theme.accentColor
                ch == null -> theme.faintColor
                else -> theme.inkColor
            },
            maxLines = 1,
            modifier = Modifier.weight(1f),
        )
        // 张数只在有的那一行出现，那颗自己永远占同一个位置（不因为有没有下过而左右挪）
        if (ch != null) Text(ch.pages.toString(), fontSize = 12.sp, color = theme.faintColor)
        // 一颗两用：没下过是箭头（点它补这一话），下过是转圈箭头（点它重下这一话）。
        // 原先"勾 + ↻"两个记号说的是同一件事，勾撤了
        ChapterGet(faceOf(ch != null, busy), theme.accentColor, theme.faintColor, pop) {
            if (ch == null) onPick(index) else onRedo(index)
        }
    }
}

/** 书签：这一本的「第几话第几页」一串，顶上一颗「记当前页」。 */
@Composable
private fun BoxScope.MarksDrawer(
    marks: List<Pair<Int, Int>>,
    names: List<String>,
    theme: ReaderTheme,
    note: String?,
    onAdd: () -> Unit,
    onJump: (Pair<Int, Int>) -> Unit,
    onDelete: (Pair<Int, Int>) -> Unit,
    onClose: () -> Unit,
) {
    PanelShell(theme) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("书签 ${marks.size} 条", fontSize = 15.sp, fontWeight = FontWeight.Medium, color = theme.inkColor, modifier = Modifier.weight(1f))
            Text("记当前页", fontSize = 13.sp, color = theme.accentColor, modifier = Modifier.tap(onAdd).padding(horizontal = 12.dp, vertical = 8.dp))
            Text("关闭", fontSize = 13.sp, color = theme.faintColor, modifier = Modifier.tap(onClose).padding(start = 8.dp, end = 4.dp, top = 8.dp, bottom = 8.dp))
        }
        note?.let { Text(it, fontSize = 12.sp, color = theme.accentColor, modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) }
        if (marks.isEmpty()) {
            Text("还没有书签：翻到要看的那一页，按上面「记当前页」", fontSize = 13.sp, color = theme.faintColor, modifier = Modifier.padding(16.dp))
        }
        LazyColumn(Modifier.fillMaxWidth().weight(1f, fill = false)) {
            items(marks, key = { it }) { m ->
                Row(
                    Modifier.fillMaxWidth().height(44.dp).tap { onJump(m) }.padding(horizontal = 16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        (names.getOrNull(m.first)?.takeIf { it.isNotBlank() } ?: "第 ${m.first + 1} 话") + " 第 ${m.second + 1} 页",
                        fontSize = 14.sp,
                        color = theme.inkColor,
                        maxLines = 1,
                        modifier = Modifier.weight(1f),
                    )
                    Text("删", fontSize = 13.sp, color = theme.accentColor, modifier = Modifier.size(44.dp).tap { onDelete(m) })
                }
            }
        }
    }
}

/**
 * 设置：翻页那一行（左右 / 上下）+ 小说那两行 —— 亮度那一整行（滑轨 + 跟随系统那颗）和背景那排色块。
 * 面板本体在 `ReaderControls.BrightnessBackgroundPanel`（有声书那一屏点的是同一格，不各写一份），
 * 「翻页」那一行用它的 `header` 槽挂上去，有声那一屏不传 = 一个字都不变。
 */
@Composable
private fun BoxScope.SettingsDrawer(
    theme: ReaderTheme,
    vertical: Boolean,
    onVertical: (Boolean) -> Unit,
    edge: Boolean,
    onEdge: () -> Unit,
    brightness: Float,
    onBrightness: (Float) -> Unit,
    onFollowSystem: () -> Unit,
    onPickTheme: (ReaderTheme) -> Unit,
) = BrightnessBackgroundPanel(
    theme, brightness, onBrightness, onFollowSystem, onPickTheme,
    header = {
        // 顶上多留一截：这一行原来几乎是贴着面板上沿画的（面板顶到「全屏」那行字 35px = 13dp），
        // 他要"这个选项的顶部间隙大一点" —— 和有声音那一屏同一个数（14dp）
        Spacer(Modifier.height(14.dp))
        EdgeToEdgeRow(theme, edge, onEdge)
        Spacer(Modifier.height(20.dp))
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            RowLabel("翻页", theme)
            Spacer(Modifier.width(6.dp))
            PanelChip("左右", theme, Modifier.weight(1f).height(34.dp), selected = !vertical) { onVertical(false) }
            Spacer(Modifier.width(9.dp))
            PanelChip("上下", theme, Modifier.weight(1f).height(34.dp), selected = vertical) { onVertical(true) }
        }
    },
)

/**
 * 一张页。解码在 IO 线程：一张 980x1403 的页在主线程解要 20ms 上下，一屏两张就是掉帧。
 *
 * 解不出来那一格写的是「这页没下来」而不是画一块白：白块看着像"图坏了"，
 * 而实际原因（这一页没下全、或者站点那串签名地址过期了）只有文字说得出。
 */
@Composable
private fun PageImage(file: File, images: ComicImages, pageNo: Int, theme: ReaderTheme, fillHeight: Boolean = true) {
    val bmp by produceState<Bitmap?>(null, file.absolutePath) {
        value = withContext(Dispatchers.IO) { images.page(file) }
    }
    val b = bmp
    if (b == null || b.isRecycled) {
        Box(Modifier.fillMaxWidth().height(200.dp), contentAlignment = Alignment.Center) {
            Text("这页没下来", fontSize = 12.sp, color = theme.faintColor)
        }
        return
    }
    Image(
        bitmap = b.asImageBitmap(),
        contentDescription = "第 $pageNo 页",
        // 左右那一档一张占满一屏（图小就居中留边）。上下那一档只钉宽度、高度跟着图的长宽比走：
        // 条漫一张能有三四屏高，给它 fillMaxSize 会被压成一屏的缩略图，字就看不见了
        modifier = if (fillHeight) Modifier.fillMaxSize() else Modifier.fillMaxWidth(),
        contentScale = if (fillHeight) ContentScale.Fit else ContentScale.FillWidth,
    )
}

/** 目录行首那颗小缩略图的解码宽度（像素）：20dp 在 2.75 密度下 55px，解码按 2 的幂取样到 110 那档。 */
private const val THUMB_PX = 110
