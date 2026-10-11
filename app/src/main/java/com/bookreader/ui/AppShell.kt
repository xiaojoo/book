package com.bookreader.ui

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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
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
    /** 夜间开关。"我的"页那颗开关写它，启动和从阅读页回来时从 `prefs.themeIndex` 同步。 */
    var night by mutableStateOf(false)

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
                MainTab.values().forEach { item ->
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

private class FeaturedBook(
    val title: String,
    val blurb: String,
    val meta: String,
    val tags: List<String>,
    val coverRes: Int,
)

/**
 * 精选：静态。文案和封面都是从那张参考截图里裁/抄来的样例，只给原型看，上线前必须换成真书源。
 * 版式是量出来的（截图 827 宽 → 设备 1080 宽，系数 1.306）：封面 128x171=61x82dp、
 * 图文间距 25=12dp、行距 204=97dp。列表按书签那一档做成卡片（同一套外壳：12dp 外缩进 +
 * 10dp 圆角 + 10dp 卡缝），所以封面左缘的 19dp 拆成"卡外 12 + 卡内 7"，墨迹位置不变。
 */
private val FEATURED = listOf(
    FeaturedBook(
        "省委钦点秘书长，我官路通天",
        "直系领导自杀，省纪委介入，让王晨迅速成为省委办公厅的众矢之的，内忧外困下，才发现领导早已帮他",
        "官场 · 38.1万字",
        listOf("现实题材", "智商在线"),
        R.drawable.cover_feat1,
    ),
    FeaturedBook(
        "婚内五年后重逢，霍总深情失控跪地求和",
        "温然嫁给霍驰野六年，分居五年。\n五年后，他怀抱白月光高调回国。",
        "总裁豪门 · 36.3万字",
        listOf("虐恋", "白月光"),
        R.drawable.cover_feat2,
    ),
    FeaturedBook(
        "分居三年，高冷商总跪地求不离",
        "三年前，因为那个丢失的孩子，温楹给了商灌池一刀，斩断了青梅竹马的情分。",
        "总裁豪门 · 34.5万字",
        listOf("甜宠", "现代言情"),
        R.drawable.cover_feat3,
    ),
    FeaturedBook(
        "一百岁老头子，谁让你装嫩修仙了",
        "“一百岁了，还在炼气期，这老废物怎么还不去死？”\n面对这样的嘲讽，林默不仅没有生气，反而笑得更蔫",
        "幻想修真 · 30.3万字",
        listOf("暗黑", "仙侠"),
        R.drawable.cover_feat4,
    ),
    FeaturedBook(
        "北疆最狂纨绔：从赐婚进京开始",
        "｛ 历史爽文，但非无脑爽文，喜欢无脑爽慎入 ｝\n商界传奇林烨，庆功宴上醉倒，一睁眼却成了大夏北",
        "架空历史 · 35.2万字",
        listOf("赘婿", "快节奏"),
        R.drawable.cover_feat5,
    ),
    FeaturedBook(
        "娇软美人出嫁后",
        "【肤白貌美娇娇女x忠犬直男糙汉，体型差+家长里短】",
        "古代情缘 · 31.5万字",
        listOf("轻松", "糙汉"),
        R.drawable.cover_feat6,
    ),
)

@Composable
fun FeaturedScreen() {
    var note by remember { mutableStateOf<String?>(null) }
    Column(Modifier.fillMaxSize().background(Chrome.page)) {
        ScreenHeader("精选")
        note?.let {
            Text(
                it,
                fontSize = 12.sp,
                color = Chrome.accent,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 19.dp, vertical = 2.dp),
            )
        }
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 12.dp, end = 12.dp, top = 2.dp, bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            items(FEATURED, key = { it.title }) { book ->
                FeaturedRow(book = book, onClick = {
                    note = "《${book.title}》是参考截图里的静态样例：书源未接入，加不了书架。"
                })
            }
        }
    }
}

@Composable
private fun FeaturedRow(book: FeaturedBook, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            // 卡片外壳和书签那一档同一份：10dp 圆角 + 卡片底色；左右各 7dp 内衬，加上卡外的
            // 12dp 正好还是量出来的封面左缘 19dp，改成卡片不该把内容挪位
            .clip(RoundedCornerShape(10.dp))
            .background(Chrome.card)
            .tap(onClick)
            .padding(start = 7.dp, end = 7.dp, top = 7.dp, bottom = 7.dp),
    ) {
        Image(
            painter = painterResource(book.coverRes),
            contentDescription = book.title,
            contentScale = androidx.compose.ui.layout.ContentScale.Crop,
            modifier = Modifier
                .size(width = 61.dp, height = 82.dp)
                .clip(RoundedCornerShape(4.dp)),
        )
        Column(Modifier.padding(start = 12.dp).fillMaxWidth()) {
            Text(book.title, fontSize = 16.sp, color = Chrome.ink, lineHeight = 22.sp)
            Text(
                book.blurb,
                fontSize = 13.sp,
                color = Chrome.faint,
                lineHeight = 18.sp,
                maxLines = 2,
                modifier = Modifier.padding(top = 4.dp),
            )
            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(book.meta, fontSize = 13.sp, color = Chrome.faint)
                Spacer(Modifier.weight(1f))
                book.tags.forEach { tag ->
                    Box(
                        modifier = Modifier
                            .padding(start = 6.dp)
                            .background(Chrome.chip, RoundedCornerShape(4.dp))
                            .padding(horizontal = 8.dp, vertical = 3.dp),
                    ) { Text(tag, fontSize = 11.sp, color = Chrome.faint) }
                }
            }
        }
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
    onOpenAi: () -> Unit,
) {
    val context = LocalContext.current
    var books by remember { mutableStateOf(library.list()) }
    var themeIndex by remember { mutableIntStateOf(prefs.themeIndex) }
    // 开夜之前的那一档纸色：关掉夜间要还回原来那档，不是一律拍回"纸白"
    var dayBefore by remember { mutableIntStateOf(ReaderTheme.of(prefs.themeIndex).let { if (it.night) 0 else prefs.themeIndex }) }
    var confirmClean by remember { mutableStateOf(false) }
    var note by remember { mutableStateOf<String?>(null) }
    var cacheBytes by remember { mutableLongStateOf(0L) }
    var prefsSheet by remember { mutableStateOf(false) }
    var editName by remember { mutableStateOf(false) }
    var nickname by remember { mutableStateOf(prefs.nickname) }
    val totalChars = books.sumOf { it.totalChars }
    val readChars = books.sumOf { prefs.loadPosition(it.id)?.absoluteOffset ?: 0L }

    fun measureCache() {
        cacheBytes = dirBytes(context.cacheDir) + dirBytes(context.externalCacheDir)
    }
    LaunchedEffect(Unit) { measureCache() }

    Column(Modifier.fillMaxSize().background(Chrome.page)) {
        // 头像那一行：平铺在页面底色上，不是卡片（参考图就是这样）
        Row(
            Modifier.fillMaxWidth().padding(start = 16.dp, end = 12.dp, top = 14.dp, bottom = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(46.dp)
                    .clip(CircleShape)
                    .background(Chrome.accent.copy(alpha = 0.14f))
                    .tap { editName = true },
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
                modifier = Modifier.size(18.dp).tap { editName = true },
            )
        }

        LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 12.dp, end = 12.dp, bottom = 18.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            item {
                Card {
                    Line("消息通知", on = { note = "消息要接书源服务端才有地方存" })
                    Hairline()
                    Line("自动订阅", on = { note = "自动订阅要有书源账号，本机只读本地文件" })
                }
            }
            item {
                Card {
                    Row(
                        Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text("夜间模式", fontSize = 15.sp, color = Chrome.ink)
                        Spacer(Modifier.weight(1f))
                        RedSwitch(
                            on = themeIndex == ReaderTheme.ALL.lastIndex,
                            onToggle = {
                                themeIndex = if (it) {
                                    dayBefore = themeIndex
                                    ReaderTheme.ALL.lastIndex
                                } else {
                                    dayBefore
                                }
                                prefs.themeIndex = themeIndex
                                // 外壳（这四页 + 状态栏）跟着一起换，不然开关只改阅读页、看着是假的
                                Chrome.night = it
                                note = if (it) "已切到夜间：外壳和阅读页一起变" else "已切回${ReaderTheme.of(dayBefore).label}"
                            },
                        )
                    }
                }
            }
            item {
                Card {
                    Line("隐私设置", on = {
                        note = if (prefs.aiBaseUrl.isBlank()) {
                            "本机不上传任何数据：书、位置、书签都只存在这台手机上"
                        } else {
                            "书、位置、书签仍然只在本机；只有 AI 词典会把你要查的那一段发到 " +
                                prefs.aiBaseUrl + "，其它功能一个字节都不发"
                        }
                    })
                    Hairline()
                    Line("偏好设置", trailing = prefsSummary(prefs), on = { prefsSheet = true })
                    Hairline()
                    // AI 服务这一档必须在这一页看得见：藏在阅读页里等于没做（他点过这条）
                    Line(
                        "AI 服务",
                        sub = "长按「词典」的翻译和拼音；「听书」的语音也在这里配",
                        trailing = if (prefs.aiBaseUrl.isBlank() && prefs.aiKeyCipher.isBlank()) "未配置" else "已配置",
                        on = onOpenAi,
                    )
                    Hairline()
                    Line("其他设置", on = { note = "其他项要接书源服务端，本机没有" })
                }
            }
            item {
                Card {
                    Line("账号设置", on = { note = "没有账号体系：昵称只是本机显示用" })
                    Hairline()
                    Line("未成年人模式", on = { note = "要接实名和时长统计，本机没有" })
                    Hairline()
                    Line(
                        "清理缓存",
                        trailing = "${"%.2f".format(cacheBytes / 1048576f)}MB",
                        on = { confirmClean = true },
                    )
                }
            }
            item {
                Card(pad = 0.dp) {
                    Box(
                        Modifier.fillMaxWidth().height(50.dp).tap {
                            note = "没有账号可退出：这台机上的书和位置不会被任何服务器记录"
                        },
                        contentAlignment = Alignment.Center,
                    ) {
                        Text("退出登录", fontSize = 15.sp, color = Chrome.danger)
                    }
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
    if (editName) {
        var tmp by remember { mutableStateOf(nickname) }
        AlertDialog(
            onDismissRequest = { editName = false },
            title = { Text("昵称") },
            text = {
                OutlinedTextField(
                    value = tmp,
                    onValueChange = { tmp = it.take(12) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    nickname = tmp.ifBlank { nickname }
                    prefs.nickname = nickname
                    editName = false
                }) { Text("保存", color = Chrome.accent) }
            },
            dismissButton = { TextButton(onClick = { editName = false }) { Text("取消") } },
        )
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

@Composable
private fun Card(pad: Dp = 0.dp, content: @Composable () -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(Chrome.card)
            .padding(horizontal = pad),
    ) { content() }
}

@Composable
private fun Hairline() {
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
private fun Line(
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
            SwitchLine("点屏幕两侧都往后翻", both) { both = it; prefs.bothSidesNext = it }
            SwitchLine("段首空两格", indent) { indent = it; prefs.paragraphIndent = it }
        }
    }
}

@Composable
private fun SwitchLine(title: String, on: Boolean, onToggle: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 50.dp).padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(title, fontSize = 15.sp, color = Chrome.ink, modifier = Modifier.weight(1f))
        RedSwitch(on, onToggle)
    }
}
