package com.bookreader.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import java.io.File
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.bookreader.R
import com.bookreader.core.model.BookMeta
import com.bookreader.core.ingest.PptxDeck
import com.bookreader.core.ingest.PptxDoc
import com.bookreader.data.BookLibrary
import com.bookreader.data.PdfDoc
import com.bookreader.data.ReaderPrefs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import com.bookreader.booksource.BookHit
import com.bookreader.booksource.HttpFetcher
import com.bookreader.booksource.SearchEngine
import com.bookreader.data.BookSourceStore

/**
 * 一套外壳底色（书架/精选/书签/我的）。夜间的墨色和强调色取自 `ReaderTheme.Night` 同一批值，
 * 两处不会各说一套。
 */
class Palette(
    val page: Color,
    val card: Color,
    val ink: Color,
    val faint: Color,
    val accent: Color,
    val dot: Color,
    val danger: Color,
    val hairline: Color,
    val chip: Color,
) {
    val pageArgb: Int get() = page.toArgb()
}

/**
 * 外壳用的颜色。阅读页另有一套（ReaderTheme 六档），因为那边要跟着正文纸色走。
 *
 * `night` 是可观察状态、所有颜色都是它的 getter —— 组合里读一次 `Chrome.page` 就订阅了它，
 * 夜间开关一拨，四页加状态栏/导航条一起换，不用每个调用点传参。
 */
object Chrome {
    /**
     * 外壳的夜间开关。「我的」页那颗写它，回到壳子上时从 `prefs.shellNight` 同步（`MainActivity`）。
     *
     * 某一屏阅读器开着的时候也会把它写成自己那档纸色的明暗（pdf / ppt 整屏用的是这套色板），
     * 退出那一屏就按 `shellNight` 还回来 —— **阅读模式里改的东西不该留在外壳上**。
     */
    var night by mutableStateOf(false)

    /**
     * 全局字号的倍率（「我的 → 字体管理」）。和 [night] 同一个道理：SharedPreferences 不是可观察状态，
     * 所以改完写这里一份，`TipHost` 读它来换 density —— 外壳四页跟着变，阅读正文不吃这一档
     * （ReaderScreen 排版用的是系统 fontScale，他点的「各算一档」）。
     */
    var uiScale by mutableFloatStateOf(1f)

    /** 全局字体文件名，同上；空 = 系统字体。改完由字体管理那一屏写进来，`TipHost` 按它重解一次字体。 */
    var fontFile by mutableStateOf("")

    /**
     * 系统栏那条动画的时长，**从平台自己的动画回调里学**（K40 / MIUI 实测：藏 1519ms 走 93 帧、
     * 还 311ms 走 19 帧；三个动画倍率都是 1.0，所以这不是被放大出来的）。
     * 阅读页那两条菜单的淡入淡出跟着这两个数走 —— 他点的口径是"过渡时间和菜单栏一样"，
     * 而平台那条 1.5 秒我们改不动，所以动的是我们这边。
     */
    var barHideMs by mutableIntStateOf(1500)
    var barShowMs by mutableIntStateOf(275)
    /** "藏系统栏"这一枪的动画有没有接到应用手里（`BarSync`）。接到了就不必再去拟合厂商那条时长 */
    var barsSynced by mutableStateOf(false)

    private val day = Palette(
        page = Color(0xFFF6F7F9), card = Color(0xFFFFFFFF), ink = Color(0xFF22262E),
        faint = Color(0xFF8B93A1), accent = Color(0xFFE30000), dot = Color(0xFFE30000),
        danger = Color(0xFFB3261E), hairline = Color(0xFFE3E7ED),
        chip = Color(0xFFF4F5F7),
    )
    private val dark = Palette(
        page = Color(0xFF121316), card = Color(0xFF1B1C20), ink = Color(0xFFB9BFC9),
        faint = Color(0xFF6A7079), accent = Color(0xFFFF6B6B), dot = Color(0xFFFF6B6B),
        danger = Color(0xFFCF6679), hairline = Color(0xFF2A2D33),
        chip = Color(0xFF232529),
    )
    private val cur: Palette get() = if (night) dark else day

    val page: Color get() = cur.page
    val card: Color get() = cur.card
    val ink: Color get() = cur.ink
    val faint: Color get() = cur.faint
    val accent: Color get() = cur.accent
    val dot: Color get() = cur.dot
    val danger: Color get() = cur.danger
    val hairline: Color get() = cur.hairline
    val chip: Color get() = cur.chip
    val pageArgb: Int get() = cur.pageArgb
}

/**
 * 底部抽屉/`ModalBottomSheet` 顶部那一对圆角：一个常量管到底，别出现"抽屉 16dp、偏好设置 28dp"
 * 那种同一种东西两个主人（Material 的默认就是 28dp）。
 * 10dp = 这台机 27.5px。阅读页里那四处弹层（快捷/更多设置/字体/书签）仍是 16dp —— 那是他认可过的另一档。
 */
val SHEET_RADIUS = 10.dp

enum class MainTab(val label: String, val iconRes: Int) {
    Shelf("书架", R.drawable.ic_tab_shelf),
    // 搜书单独占一格：藏在书架那颗 ⋯ 里等于没做（他点过这条）。精选那格原样留着
    Search("搜书", R.drawable.ic_search),
    Featured("精选", R.drawable.ic_tab_featured),
    Marks("书签", R.drawable.ic_tab_marks),
    Me("我的", R.drawable.ic_tab_me),
}

@Composable
fun AppScaffold(
    tab: MainTab,
    onTab: (MainTab) -> Unit,
    barVisible: Boolean = true,
    hidden: Set<MainTab> = emptySet(),
    content: @Composable () -> Unit,
) {
    // 系统导航条也铺界面底色：它是底栏往下那条，留着卡片白就等于同一条栏有两个主人
    ApplySystemBars(darkIcons = !Chrome.night)
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Chrome.page)
            // 键盘那一格要排掉：safeDrawing 含 ime，键盘一开整层（连着底栏）被顶上去。
            // 他要的是底栏不动、键盘盖在上面；需要自己躲键盘的那几处（搜书的结果列表）
            // 在自己的内衬里加 ime 高度，不靠壳来抬。
            .windowInsetsPadding(WindowInsets.systemBars.union(WindowInsets.displayCutout)),
    ) {
        Box(modifier = Modifier.weight(1f).fillMaxWidth()) { content() }
        if (barVisible) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Chrome.page)
                    .drawBehind {
                        // 1px 分界：这台机 1dp 会渲成 3px
                        drawLine(Chrome.hairline, Offset.Zero, Offset(size.width, 0f), 1f)
                    }
                    .height(52.dp),
                horizontalArrangement = Arrangement.SpaceEvenly,
            ) {
                // 「菜单管理」里藏掉的那几格不进底栏（SpaceEvenly + weight(1f) 会自动把剩下的几格摊满）
                MainTab.values().filterNot { it in hidden }.forEach { item ->
                    val on = item == tab
                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxSize()
                            .tap { onTab(item) },
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center,
                    ) {
                        Icon(
                            painter = painterResource(item.iconRes),
                            contentDescription = item.label,
                            tint = if (on) Chrome.accent else Chrome.faint,
                            modifier = Modifier.size(22.dp),
                        )
                        Text(
                            item.label,
                            fontSize = 11.sp,
                            color = if (on) Chrome.accent else Chrome.faint,
                            modifier = Modifier.padding(top = 2.dp),
                        )
                    }
                }
            }
        }
    }
}

/**
 * 顶部标题那一行的高度，三页共用一个数。
 *
 * 为什么是定高而不是 `vertical = 12dp`：书架那一行右边那颗 ⋯ 的命中框是 44dp（=121px），
 * 行高会被它撑到 121px，标题（60px）在行里上下居中就被推下去 (121-60)/2 = 30.5px ——
 * 同一个 12dp 内衬，两页的标题墨差出 31px。定高 46dp（=126.5px）之后 44dp 那颗装得下、
 * 不再决定行高，三页的标题都落在行顶往下 (126.5-60)/2 = 33.25px ≈ 12dp 处。
 */
val ScreenHeaderHeight = 46.dp

@Composable
fun ScreenHeader(title: String, trailing: String? = null) {
    Row(
        modifier = Modifier.fillMaxWidth().height(ScreenHeaderHeight).padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(title, fontSize = 20.sp, fontWeight = FontWeight.Medium, color = Chrome.ink)
        Spacer(Modifier.weight(1f))
        trailing?.let { Text(it, fontSize = 13.sp, color = Chrome.faint) }
    }
}

/**
 * 整屏页左上角那颗 ‹ 返回：命中框 44dp 见方（手指点得准），**墨的左边缘贴页面 16dp 那条内容线**。
 *
 * 为什么要单独一颗：原先四页各写一份 `Icon(size = 44.dp)`，那是把整个视口当尺寸，墨有 103px 高，
 * 比参考图那颗（41px）大两倍半；而图标居中在 44dp 框里时墨又落在 25dp 处，比下面每一行的 16dp
 * 缩进多 9dp，一眼能看出这颗比内容歪。
 *
 * 尺寸 18dp 是按参考图定的：18dp 框出来的墨是 24x42px，参考那颗是 24x41px（宽 Δ0、高 Δ1px）。
 * 左移的 4.2dp 不是从截图凑的，是从图标自己算的：`ic_back.xml` 的墨在 24 单位的视口里从 x=5.6 起，
 * 占 5.6/24 = 23.3%，18dp 框就是左边天然空 18 x 5.6/24 = 4.2dp —— 把这 4.2dp 补掉，墨正好落在 16dp。
 * 只有图标偏移，44dp 的命中框还在 16dp 处，不跟着左移。
 */
@Composable
fun BackSlot(desc: String, onBack: () -> Unit) {
    Box(
        Modifier.size(width = 44.dp, height = 44.dp).tap(onBack),
        contentAlignment = Alignment.CenterStart,
    ) {
        Icon(
            painterResource(R.drawable.ic_back), desc, tint = Chrome.ink,
            modifier = Modifier.size(18.dp).offset(x = (-4.2).dp),
        )
    }
}

private class MarkRow(
    val bookId: String,
    val bookTitle: String,
    val chapterTitle: String,
    val offset: Long,
    val full: String,
    /** 整页那一族（pdf / pptx）的书签：只有页码（1 基）。null = 正文那一族的字符书签。 */
    val page: Int? = null,
)

/**
 * 书签页给页码书签配图那一件事的临时渲染器。
 *
 * 整页那一族没有文字摘录可印 —— 那一页长什么样只能把原件渲出来，所以这一页会为"有书签的那几本"
 * 各开一次原件（不是全部书：一本 1000 页的 pdf 光数版面比例就要几百毫秒，没书签就不该开）。
 * 离开这一页就关掉：PdfRenderer 和 zip 都是要还的文件句柄。
 */
private class MarkArt(
    val pdf: PdfDoc? = null,
    val deck: PptxDeck? = null,
    val images: PptxImages? = null,
) {
    /** 那一页的宽/高比例。书签指的页号越界时按第一页算，不让它去碰数组外面。 */
    fun ratio(page: Int): Float = when {
        pdf != null -> {
            val s = pdf.pageSizes[(page - 1).coerceIn(0, pdf.pageCount - 1)]
            s.widthPx.toFloat() / s.heightPx
        }
        deck != null -> deck.widthEmu.toFloat() / deck.heightEmu
        else -> 0.75f
    }

    suspend fun bitmap(page: Int, widthPx: Int): android.graphics.Bitmap? =
        pdf?.thumbnail((page - 1).coerceIn(0, pdf.pageCount - 1), widthPx)

    fun close() {
        pdf?.close()
        images?.close()
    }
}

/** 书签页那一格小图：pdf 拿位图，pptx 直接按版面画（和阅读页「缩略图」同一份画法，两处不会长得不一样）。 */
@Composable
private fun MarkThumb(art: MarkArt?, page: Int, widthPx: Int) {
    Box(Modifier.fillMaxSize().background(Color.White)) {
        val a = art ?: return@Box
        if (a.pdf != null) {
            var bmp by remember(a, page) { mutableStateOf<android.graphics.Bitmap?>(null) }
            LaunchedEffect(a, page) { bmp = a.bitmap(page, widthPx * 2) }
            val b = bmp ?: return@Box
            Image(
                bitmap = b.asImageBitmap(),
                contentDescription = "第 $page 页",
                contentScale = androidx.compose.ui.layout.ContentScale.FillWidth,
                modifier = Modifier.fillMaxSize(),
            )
        } else if (a.deck != null && a.images != null) {
            val i = (page - 1).coerceIn(0, a.deck.slides.lastIndex)
            SlideCanvas(a.deck, a.deck.slides[i], widthPx, a.images)
        }
    }
}

/** 书签：跨书的标记列表。点一条 = 把那本书的位置设成它再打开。 */
@Composable
fun MarksScreen(
    library: BookLibrary,
    prefs: ReaderPrefs,
    onOpen: (String) -> Unit,
) {
    // 删一条就 +1：prefs 不是可观察状态，不重算这一列的话删完那行还留在屏幕上
    var tick by remember { mutableIntStateOf(0) }
    val rows = remember(tick) {
        val out = ArrayList<MarkRow>()
        library.list().forEach { meta ->
            val marks = prefs.bookmarks(meta.id)
            val pages = prefs.pageBookmarks(meta.id)
            if (marks.isEmpty() && pages.isEmpty()) return@forEach
            // 页码书签一条都不碰排版引擎：pdf/pptx 的原件里没有"第几章第几个字"这件事，
            // 拿它去 open() 只会白读一遍文件
            pages.forEach { out.add(MarkRow(meta.id, meta.title, "", 0L, "", it)) }
            if (marks.isEmpty()) return@forEach
            // 一本只开一次：章节名和预览都从这一次的随机读里拿
            runCatching {
                val book = library.open(meta.id, maxChunks = 2)
                marks.forEach { pos ->
                    val raw = book.cache.slice(pos.absoluteOffset, 800)
                    out.add(
                        MarkRow(
                            bookId = meta.id,
                            bookTitle = meta.title,
                            chapterTitle = book.chapters[pos.chapterIndex.coerceIn(0, book.chapters.size - 1)].title,
                            offset = pos.absoluteOffset,
                            // 整段原样交给界面去截：折叠时由省略号收尾，展开时全显示
                            full = raw.substringBefore('\n').ifEmpty { raw.take(30) },
                        )
                    )
                }
                book.close()
            }
        }
        out
    }

    // 一本一堆，tab 上那些书名就是这一列：顺序跟着 library.list()（= 书架那一列的顺序）
    val groups = remember(rows) {
        rows.distinctBy { it.bookId }.map { head -> head to rows.filter { it.bookId == head.bookId } }
    }
    var pick by remember { mutableIntStateOf(0) }
    // 删到只剩更少书的时候，选中的那一格要跟着收住：不然 groups[pick] 会越界，整页崩在删完最后一条上
    val at = pick.coerceIn(0, (groups.size - 1).coerceAtLeast(0))

    // 页码书签那一格要配图，就得为"有页码书签的那几本"各开一次原件（后台开，开好一本亮一本）
    val needArt = remember(rows) { rows.filter { it.page != null }.map { it.bookId }.distinct() }
    val art = remember { mutableStateMapOf<String, MarkArt>() }
    // 开过了但没开出来的那几本也要记一笔：不然那一格永远白着，看着像还在转
    var artDone by remember { mutableStateOf(emptySet<String>()) }
    LaunchedEffect(needArt) {
        needArt.forEach { id ->
            if (art.containsKey(id) || id in artDone) return@forEach
            val meta = library.list().firstOrNull { it.id == id } ?: return@forEach
            val built = withContext(Dispatchers.IO) {
                runCatching {
                    when (meta.kind) {
                        BookMeta.PDF -> library.pdfFile(id).takeIf { it.isFile }?.let { MarkArt(pdf = PdfDoc.open(it, 220)) }
                        BookMeta.PPTX -> library.deckFile(id).takeIf { it.isFile }?.let { f ->
                            PptxDoc().load(f)?.let { d -> MarkArt(deck = d, images = PptxImages().apply { open(f) }) }
                        }
                        else -> null
                    }
                }.getOrNull()
            }
            if (built != null) art[id] = built
            artDone = artDone + id
        }
    }
    DisposableEffect(Unit) {
        onDispose {
            art.values.forEach { it.close() }
            art.clear()
        }
    }

    Column(Modifier.fillMaxSize().background(Chrome.page)) {
        if (rows.isEmpty()) {
            Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
                Text("还没有书签", fontSize = 16.sp, color = Chrome.ink)
                Text("读书时点底部「书签」；pdf / pptx 在抽屉里点「插入书签」", fontSize = 13.sp, color = Chrome.faint)
            }
        } else {
            // 顶行不再单独印书名（他点的就是这一格"这个不要了，tab 从这里开始"）：
            // 左边是那排可横滑的 tab，右边留住条数，右内衬仍是 16dp = 原来标题那一档，位置一个字没挪
            Row(
                modifier = Modifier.fillMaxWidth().padding(start = 12.dp, end = 16.dp, top = 12.dp, bottom = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Row(
                    modifier = Modifier.weight(1f).horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    groups.forEachIndexed { i, (head, _) ->
                        WholeChip(
                            head.bookTitle,
                            modifier = Modifier.height(32.dp),
                            selected = i == at,
                            // 10dp = 下面那叠书签卡片的圆角，同一页里两套圆角就是没对齐
                            radius = 10.dp,
                        ) { pick = i }
                    }
                }
                // 条数：底色 / 圆角 / 字色都和未选中那颗 tab 同一档（它就是这一排里的一颗），
                // 左边留的空气 = 颗与颗之间那 8dp。
                // 盒宽钉死 50dp：内衬 12dp×2 之外留给字的是 26dp=71.5px，「6 条」50px、「12 条」64px
                // 都装得下并居中；上一版给的是上限 40dp，字要 42.3dp 才被夹掉半个「条」、空位全跑到右边。
                Box(
                    modifier = Modifier
                        .padding(start = 8.dp)
                        .width(50.dp)
                        .height(32.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(Chrome.chip)
                        .padding(horizontal = 12.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        "${groups[at].second.size} 条",
                        fontSize = 13.sp,
                        maxLines = 1,
                        textAlign = TextAlign.Center,
                        color = Chrome.ink,
                    )
                }
            }
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(start = 12.dp, end = 12.dp, top = 0.dp, bottom = 16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                items(groups[at].second, key = { "${it.bookId}-${it.page ?: it.offset}" }) { row ->
                    val page = row.page
                    if (page != null) {
                        // 整页那一族的一条书签就是一页号：没有正文摘录可印，所以那一格放**那一页本身**
                        // —— 和阅读页抽屉里「缩略图」同一份画法（pdf 取位图、pptx 按版面画），两处不会长得不一样
                        val a = art[row.bookId]
                        val ratio = (a?.ratio(page) ?: 0.75f).coerceIn(0.3f, 3f)
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(10.dp))
                                .background(Chrome.card)
                                .tap {
                                    prefs.saveWholePage(row.bookId, page - 1)
                                    onOpen(row.bookId)
                                }
                                .padding(start = 8.dp, end = 6.dp, top = 8.dp, bottom = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            var cellW by remember(row) { mutableIntStateOf(0) }
                            Box(
                                modifier = Modifier
                                    .width(48.dp)
                                    .aspectRatio(ratio)
                                    .onSizeChanged { cellW = it.width }
                                    .clip(RoundedCornerShape(6.dp))
                                    .border(1.dp, Chrome.hairline, RoundedCornerShape(6.dp)),
                            ) {
                                MarkThumb(a, page, cellW)
                                if (a == null && row.bookId in artDone) {
                                    // 开不出原件（原件被移走、或这一族我们渲不了）也要说一句，别留一格白让人以为还在转
                                    Text(
                                        "无预览",
                                        fontSize = 10.sp,
                                        color = Chrome.faint,
                                        modifier = Modifier.align(Alignment.Center),
                                    )
                                }
                            }
                            Text(
                                "第 $page 页",
                                fontSize = 15.sp,
                                color = Chrome.ink,
                                modifier = Modifier.weight(1f).padding(start = 10.dp),
                            )
                            Box(
                                modifier = Modifier.size(40.dp).tap {
                                    prefs.removePageBookmark(row.bookId, page - 1)
                                    tick++
                                },
                                contentAlignment = Alignment.Center,
                            ) {
                                Icon(
                                    painterResource(R.drawable.ic_close),
                                    "删除",
                                    tint = Chrome.faint,
                                    modifier = Modifier.size(17.dp),
                                )
                            }
                        }
                        return@items
                    }
                    var open by remember(row) { mutableStateOf(false) }
                    // 折起来确实被截掉一段才允许展开；两行放得下的，那颗箭头置灰
                    var overTwo by remember(row) { mutableStateOf(false) }
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(10.dp))
                            .background(Chrome.card)
                            .tap {
                                prefs.bookmarks(row.bookId).firstOrNull { it.absoluteOffset == row.offset }
                                    ?.let { prefs.savePosition(row.bookId, it) }
                                onOpen(row.bookId)
                            }
                            .padding(start = 16.dp, end = 6.dp, top = 8.dp, bottom = 8.dp),
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            // 卡片第一行从书名换成章节名：书名已经在标题和 tab 上，同一件事不说第二遍
                            Text(
                                row.chapterTitle,
                                fontSize = 15.sp,
                                color = Chrome.ink,
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
                                    // 置灰不藏起来：藏了别人以为这里本来就没有控件
                                    tint = if (overTwo) Chrome.faint else Chrome.faint.copy(alpha = 0.35f),
                                    modifier = Modifier
                                        .size(18.dp)
                                        .graphicsLayer { rotationZ = if (open) 180f else 0f },
                                )
                            }
                            // 删除：这一页原来一条都删不掉（正文书签和页码书签都没有这颗），
                            // 只能回阅读页里删 —— 现在两处都能删，口径和抽屉里那一层一样
                            Box(
                                modifier = Modifier.size(40.dp).tap {
                                    prefs.bookmarks(row.bookId)
                                        .firstOrNull { it.absoluteOffset == row.offset }
                                        ?.let { prefs.removeBookmark(row.bookId, it) }
                                    tick++
                                },
                                contentAlignment = Alignment.Center,
                            ) {
                                Icon(
                                    painterResource(R.drawable.ic_close),
                                    "删除",
                                    tint = Chrome.faint,
                                    modifier = Modifier.size(17.dp),
                                )
                            }
                        }
                        Text(
                            // 章节名提到第一行去了，这一行只剩字数：一行里印两遍章节名是同一件事说两遍
                            "第 ${row.offset} 字",
                            fontSize = 12.sp,
                            color = Chrome.faint,
                            modifier = Modifier.padding(top = 2.dp),
                        )
                        Text(
                            row.full,
                            fontSize = 13.sp,
                            color = Chrome.ink,
                            lineHeight = 19.sp,
                            maxLines = if (open) Int.MAX_VALUE else 2,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.padding(top = 4.dp),
                            onTextLayout = { r ->
                                // 判据同阅读页那侧：看有没有行被省略号截掉
                                if (!open) {
                                    overTwo = r.getLineEnd(r.lineCount - 1, visibleEnd = true) < row.full.length
                                }
                            },
                        )
                    }
                }
            }
        }
    }
}

/**
 * 我的：照他给的那张参考图排版（头像+昵称+等级牌 → 分组卡片 → 红色开关 → 底部红色整行）。
 * 参考图里那几行没有后端的，点了给一句回话，不做成按了没反应的哑按钮。
 * 真接上的是夜间档、缓存大小、阅读偏好开关、昵称。导入和阅读配色不在这一页 ——
 * 前者在书架右上角，后者在阅读页的设置面板里；"清除全部书籍"按他的要求删了，
 * 现在只能一本书一本书地长按书架卡片删。
 */
@Composable
fun MeScreen(
    library: BookLibrary,
    prefs: ReaderPrefs,
    onOpenAi: (AiSection) -> Unit,
    onEditName: () -> Unit,
    onSkin: () -> Unit,
    onFont: () -> Unit,
    onMenu: () -> Unit,
) {
    val context = LocalContext.current
    var books by remember { mutableStateOf(library.list()) }
    // 外壳的夜间。它和每一屏阅读器那档纸色**是两个主人**：这颗开关只动外壳那四页 + 状态栏，
    // 不再顺手把小说/漫画/pdf 的纸色一起改掉（那些现在是各屏一份，见 ReaderPrefs.forReader）。
    var night by remember { mutableStateOf(prefs.shellNight) }
    var confirmClean by remember { mutableStateOf(false) }
    var note by remember { mutableStateOf<String?>(null) }
    var cacheBytes by remember { mutableLongStateOf(0L) }
    var prefsSheet by remember { mutableStateOf(false) }
    var nickname by remember { mutableStateOf(prefs.nickname) }
    val totalChars = books.sumOf { it.totalChars }
    val readChars = books.sumOf { prefs.loadPosition(it.id)?.absoluteOffset ?: 0L }

    fun measureCache() {
        cacheBytes = dirBytes(context.cacheDir) + dirBytes(context.externalCacheDir)
    }
    LaunchedEffect(Unit) { measureCache() }

    Column(Modifier.fillMaxSize().background(Chrome.page)) {
        // 头像那一行：平铺在页面底色上，不是卡片（参考图就是这样）。
        // 右边 26dp = 下面那一叠行的 12dp（整页内衬）+ 14dp（行内衬）：顶上那颗 `>` 的墨右
        // 要和「皮肤管理」那行的 `>` 墨右落在同一条线上（改前差 38px = 13.8dp）
        Row(
            Modifier.fillMaxWidth().padding(start = 16.dp, end = 26.dp, top = 14.dp, bottom = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(46.dp)
                    .clip(CircleShape)
                    .background(Chrome.accent.copy(alpha = 0.14f))
                    .tap { onEditName() },
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    nickname.take(1).ifEmpty { "书" },
                    fontSize = 19.sp,
                    fontWeight = FontWeight.Medium,
                    color = Chrome.accent,
                )
            }
            Column(Modifier.padding(start = 10.dp)) {
                Text(nickname, fontSize = 17.sp, fontWeight = FontWeight.Medium, color = Chrome.ink)
                Row(Modifier.padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        Modifier.background(Chrome.card, RoundedCornerShape(9.dp))
                            .padding(horizontal = 7.dp, vertical = 2.dp),
                    ) { Text("L1问道", fontSize = 11.sp, color = Chrome.faint) }
                    Text(
                        "已读 ${formatChars(readChars)} / 共 ${formatChars(totalChars)}",
                        fontSize = 11.sp,
                        color = Chrome.faint,
                        modifier = Modifier.padding(start = 7.dp),
                    )
                }
            }
            Spacer(Modifier.weight(1f))
            Icon(
                painter = painterResource(R.drawable.ic_chevron_right),
                contentDescription = "昵称",
                tint = Chrome.faint,
                modifier = Modifier.size(18.dp).tap { onEditName() },
            )
        }

        LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 12.dp, end = 12.dp, bottom = 18.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            item {
                Card {
                    Row(
                        Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text("夜间模式", fontSize = 15.sp, color = Chrome.ink)
                        Spacer(Modifier.weight(1f))
                        RedSwitch(
                            on = night,
                            onToggle = {
                                night = it
                                prefs.shellNight = it
                                // 当场换外壳这四页 + 状态栏，不然开关按下去没反应、看着是假的
                                Chrome.night = it
                                note = if (it) {
                                    "已切到夜间：只改外壳这四页；翻开书那一屏在它自己的设置里换"
                                } else {
                                    "已切回白天（外壳）；某一屏阅读器要是自己开着夜间，进去还是夜间"
                                }
                            },
                        )
                    }
                }
            }
            item {
                // 单独一张卡：这三行都是"改这台机上的样子"，和上面那颗开关、下面那两档 AI 不是一类事
                Card {
                    Line(
                        "皮肤管理",
                        sub = "上传一张图当阅读背景",
                        trailing = if (prefs.bgImageFile.isBlank()) "纯色纸" else "已用图",
                        on = onSkin,
                    )
                    Hairline()
                    Line(
                        "字体管理",
                        sub = "全局字体和全局字号",
                        trailing = if (prefs.uiFontFile.isBlank()) "系统字体" else "已换字体",
                        on = onFont,
                    )
                    Hairline()
                    Line("菜单管理", sub = "底栏哪几格要显示", on = onMenu)
                }
            }
            item {
                Card {
                    Line("偏好设置", trailing = prefsSummary(prefs), on = { prefsSheet = true })
                    Hairline()
                    // 两档各开一屏（他点名从「AI 服务」这一格拆开）：两家的地址和秘钥本来就各存一份
                    Line(
                        "AI 翻译",
                        sub = "长按选段 →「词典」的翻译和拼音走这一档",
                        trailing = if (prefs.aiBaseUrl.isBlank() && prefs.aiKeyCipher.isBlank()) "未配置" else "已配置",
                        on = { onOpenAi(AiSection.Translate) },
                    )
                    Hairline()
                    Line(
                        "AI 听书",
                        sub = "长按选段 → 念出来；地址 / 模型 / 音色在这里配",
                        trailing = if (prefs.aiVoiceBaseUrl.isBlank() && prefs.aiVoiceKeyCipher.isBlank()) "未配置" else "已配置",
                        on = { onOpenAi(AiSection.Speech) },
                    )
                }
            }
            item {
                Card {
                    Line(
                        "清理缓存",
                        trailing = "${"%.2f".format(cacheBytes / 1048576f)}MB",
                        on = { confirmClean = true },
                    )
                }
            }
            note?.let {
                item {
                    Text(it, fontSize = 12.sp, color = Chrome.faint, modifier = Modifier.padding(horizontal = 4.dp))
                }
            }
        }
    }

    if (prefsSheet) {
        PrefSheet(prefs, onDismiss = { prefsSheet = false })
    }
    if (confirmClean) {
        AlertDialog(
            onDismissRequest = { confirmClean = false },
            title = { Text("清理缓存 ${"%.2f".format(cacheBytes / 1048576f)}MB？") },
            text = { Text("只删 cache 目录里可再生的临时文件。书的正文副本、索引、位置、书签都在 files 目录，不动。") },
            confirmButton = {
                TextButton(onClick = {
                    val before = cacheBytes
                    deleteContents(context.cacheDir)
                    context.externalCacheDir?.let { deleteContents(it) }
                    context.cacheDir.deleteRecursively()
                    measureCache()
                    confirmClean = false
                    note = "释放 ${"%.2f".format((before - cacheBytes) / 1048576f)}MB，剩下 ${"%.2f".format(cacheBytes / 1048576f)}MB"
                }) { Text("清理", color = Chrome.danger) }
            },
            dismissButton = { TextButton(onClick = { confirmClean = false }) { Text("取消") } },
        )
    }
}

/**
 * 昵称：**单独一屏，不是弹框**（他点名的形态，照他给的图 —— 顶栏 `‹ 昵称 …… 保存`，
 * 整页正文就是那一行字，光标跟在字后面闪）。顶栏那一格和「AI 服务」同一份尺寸节奏：
 * 48dp 高、左边一颗 40dp 的 ‹、标题 19sp 中等、右边一颗 17sp 的「保存」。
 *
 * 只写这台机上的 prefs，没有账号体系。空着保存 = 不改（原先弹框就是这条规矩，留着）；
 * 最多 12 个字也和原先一样。底栏整条让位：这一屏挂在壳的最外层，和「AI 服务」同一路。
 */
@Composable
fun NicknameScreen(prefs: ReaderPrefs, onBack: () -> Unit) {
    // 光标要跟在字的最后（他那张图就是这样）：只给字符串的话 BasicTextField 的游标停在 0，
    // 进这一页看见的就是光标压在第一个字前面
    var field by remember { mutableStateOf(TextFieldValue(prefs.nickname, TextRange(prefs.nickname.length))) }
    val keyboard = LocalSoftwareKeyboardController.current
    val focus = remember { FocusRequester() }
    BackHandler(onBack = onBack)
    // 进这一页光标就落在字后面、键盘自己弹起来，不用先点一下（图里就是这个状态）
    LaunchedEffect(Unit) { focus.requestFocus() }

    fun save() {
        val v = field.text.trim()
        if (v.isNotEmpty()) prefs.nickname = v
        keyboard?.hide()
        onBack()
    }
    Column(Modifier.fillMaxSize().background(Chrome.page).safeDrawingPadding()) {
        Row(
            Modifier.fillMaxWidth().height(48.dp).padding(start = 8.dp, end = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.size(40.dp).tap(onBack), contentAlignment = Alignment.Center) {
                Icon(painterResource(R.drawable.ic_back), "返回", tint = Chrome.ink, modifier = Modifier.size(20.dp))
            }
            Text("昵称", fontSize = 19.sp, fontWeight = FontWeight.Medium, color = Chrome.ink)
            Spacer(Modifier.weight(1f))
            Text(
                "保存", fontSize = 17.sp, fontWeight = FontWeight.Medium, color = Chrome.ink,
                modifier = Modifier.tap { save() }.padding(vertical = 8.dp, horizontal = 6.dp),
            )
        }
        // 输入区照「搜书」那一格同一份几何（他这次贴的就是那一行）：44dp 的一条带、字在带里垂直居中，
        // 细线就压在带的下沿 —— 于是字的上下两空相等。线用 0.5dp：1dp 在这台机落到 3 设备px，
        // 0.5dp 取整成 1px，和分类页那条分界同一档。
        // 顶上不留额外内衬（他点名不要）：44dp 那条带直接接在导航带下沿，字在带里居中 ——
        // 字顶离导航带下沿那 46px 是"居中"本身要的空气，不是额外加的
        Column(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp)) {
            Box(Modifier.fillMaxWidth().height(44.dp), contentAlignment = Alignment.CenterStart) {
                BasicTextField(
                    value = field,
                    onValueChange = {
                        // 长度上限还是原先那 12 个字；截了之后游标要跟着收回来，不然它会指到字符串外面
                        val t = it.text.take(12)
                        field = TextFieldValue(t, TextRange(it.selection.start.coerceAtMost(t.length), it.selection.end.coerceAtMost(t.length)))
                    },
                    singleLine = true,
                    textStyle = LocalTextStyle.current.copy(fontSize = 17.sp, color = Chrome.ink),
                    cursorBrush = SolidColor(Chrome.accent),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { save() }),
                    modifier = Modifier.fillMaxWidth().focusRequester(focus),
                )
            }
            Box(Modifier.fillMaxWidth().height(0.5.dp).background(Chrome.hairline))
        }
    }
}

private fun dirBytes(dir: File?): Long {
    if (dir == null || !dir.exists()) return 0L
    var total = 0L
    dir.walkTopDown().forEach { if (it.isFile) total += it.length() }
    return total
}

private fun deleteContents(dir: File) {
    if (!dir.exists()) return
    dir.listFiles()?.forEach { it.deleteRecursively() }
}

private fun prefsSummary(prefs: ReaderPrefs): String =
    listOf(
        if (prefs.volumePaging) "音量键翻页" else "",
        if (prefs.keepScreenOn) "屏幕常亮" else "",
        if (prefs.fullScreen) "全屏模式" else "",
        if (prefs.bothSidesNext) "两侧下一页" else "",
        if (prefs.paragraphIndent) "段首缩进" else "",
    ).filter { it.isNotEmpty() }.joinToString(" · ")

/**
 * 参考图里那颗红色开关：轨道 42x24dp，圆点 20dp，开了整条变红。
 * 「我的」页和 pdf/pptx 抽屉里那两颗（夜间模式 / 旋转屏幕）共用这一份 —— 同一种东西两个形状就是没对齐。
 */
@Composable
internal fun RedSwitch(on: Boolean, onToggle: (Boolean) -> Unit) {
    Box(
        modifier = Modifier
            .size(width = 42.dp, height = 24.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(if (on) Chrome.accent else Chrome.hairline)
            .tap { onToggle(!on) },
    ) {
        Box(
            Modifier
                .size(20.dp)
                .align(Alignment.CenterStart)
                .offset(x = if (on) 20.dp else 2.dp)
                .clip(CircleShape)
                .background(Color.White),
        )
    }
}

// 这三颗皮肤页（皮肤/字体/菜单管理）也要用：同一张卡、同一条发丝线、同一行形状，不各写一份
@Composable
internal fun Card(pad: Dp = 0.dp, content: @Composable () -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(Chrome.card)
            .padding(horizontal = pad),
    ) { content() }
}

@Composable
internal fun Hairline() {
    // 1dp 在 2.75 倍密度上取整成 3 行；这条线要的是 1 个物理像素
    val d = LocalDensity.current
    Box(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 14.dp)
            .height(with(d) { 1.toDp() })
            .background(Chrome.hairline),
    )
}

@Composable
internal fun Line(
    title: String,
    sub: String? = null,
    trailing: String? = null,
    color: Color = Chrome.ink,
    on: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 50.dp).tap(on).padding(horizontal = 14.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, fontSize = 15.sp, color = color)
            if (sub != null) {
                Text(sub, fontSize = 12.sp, color = Chrome.faint, modifier = Modifier.padding(top = 2.dp))
            }
        }
        if (trailing != null) {
            Text(trailing, fontSize = 13.sp, color = Chrome.faint, modifier = Modifier.padding(end = 4.dp))
        }
        Icon(
            painter = painterResource(R.drawable.ic_chevron_right),
            contentDescription = null,
            tint = Chrome.faint,
            modifier = Modifier.size(16.dp),
        )
    }
}

/** 偏好设置：这台机上真生效的开关，一次列全。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PrefSheet(prefs: ReaderPrefs, onDismiss: () -> Unit) {
    var volume by remember { mutableStateOf(prefs.volumePaging) }
    var keep by remember { mutableStateOf(prefs.keepScreenOn) }
    var full by remember { mutableStateOf(prefs.fullScreen) }
    var both by remember { mutableStateOf(prefs.bothSidesNext) }
    var indent by remember { mutableStateOf(prefs.paragraphIndent) }
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = Chrome.card,
        shape = RoundedCornerShape(topStart = SHEET_RADIUS, topEnd = SHEET_RADIUS),
        // 不要顶上那颗拖拽小把手：这两层都不靠拖（点外面、点「取消」都回得去），
        // 摆一颗把手是暗示一个不存在的动作
        dragHandle = null,
    ) {
        Column(Modifier.padding(bottom = 20.dp)) {
            // 去掉把手之后标题就贴到圆角那条边上了：顶内衬给到和左边同一个数（16dp）
            Text("偏好设置", fontSize = 16.sp, color = Chrome.ink, modifier = Modifier.padding(start = 16.dp, top = 16.dp, bottom = 4.dp))
            SwitchLine("音量键翻页", volume) { volume = it; prefs.volumePaging = it }
            SwitchLine("阅读时屏幕常亮", keep) { keep = it; prefs.keepScreenOn = it }
            SwitchLine("全屏模式", full) { full = it; prefs.fullScreen = it }
            SwitchLine("点屏幕两侧都往后翻", both) { both = it; prefs.bothSidesNext = it }
            SwitchLine("段首空两格", indent) { indent = it; prefs.paragraphIndent = it }
        }
    }
}

@Composable
private fun SwitchLine(title: String, on: Boolean, onToggle: (Boolean) -> Unit) {
    Row(
        // 整行都是命中区：原来只有右边那颗开关点得动，点标题那一片什么都不会发生
        // （真机撞过：脚本按标题中心点下去，prefs 一位没变）
        Modifier.fillMaxWidth().heightIn(min = 50.dp).tap { onToggle(!on) }.padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(title, fontSize = 15.sp, color = Chrome.ink, modifier = Modifier.weight(1f))
        RedSwitch(on, onToggle)
    }
}
