package com.bookreader.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.bookreader.R
import com.bookreader.booksource.BookHit
import com.bookreader.booksource.HttpFetcher
import com.bookreader.booksource.SearchEngine
import com.bookreader.booksource.Rule
import com.bookreader.data.BookSourceStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

/**
 * 精选整屏，版式照他给的参考图（起点那一屏）自上而下七层：
 * 搜索行 → 类目 tab → 推荐 banner 轮播 → 图标宫格 → 公告条 → 分组书单。
 *
 * 参考图里有几样我们没有，处理方式统一是"版式照搬、格子里放真数据"，不造假数字：
 *  - 搜索框里那句"猜你想搜"要的是历史搜索词 → 写"搜书名"，点它切到搜书那一页；
 *  - 频道 tab（男生/女生/听书/出版/漫画）是起点自己的性别频道 → 换成**我们真抓得到的类目**，
 *    点它就是筛下面的书单，这一排因此不是装饰；
 *  - 图标宫格六格每一格都要求有真去处（没去处的格子就是死控件），所以放的是
 *    分类/刷新/源/任务/书架/书签这六个本来就存在的页面；
 *  - 每一行右侧那两个标签位、以及第二行"简介"位：两家站的首页清单里只有书名 + 作者
 *    （实测 `li` 就是 `<a>书名</a>/<a>作者</a>`），所以简介位先放作者，
 *    [BookHit.intro] 一旦有值就自动接管那一格，不用改版式。
 */
@Composable
fun FeaturedHomeScreen(
    sources: BookSourceStore,
    page: String,
    onPage: (String) -> Unit,
    onInfo: (BookInfo) -> Unit,
    onSearch: () -> Unit,
) {
    val hotSources = remember(sources) { sources.list().filter { it.usable && it.rule?.hot != null } }
    var books by remember { mutableStateOf(FeaturedCache.books) }
    var bad by remember { mutableStateOf(FeaturedCache.failed) }
    var stamp by remember { mutableLongStateOf(FeaturedCache.at) }
    var loading by remember { mutableStateOf(books.isEmpty()) }
    // 键只能是"要重抓"那颗计数。写成 LaunchedEffect(loading) 会在全部源都抓失败时
    // 永远 fresh()=false → 键一变就重跑，变成不停打这两家站
    var refresh by remember { mutableIntStateOf(0) }
    LaunchedEffect(refresh) {
        if (FeaturedCache.fresh()) {
            books = FeaturedCache.books; bad = FeaturedCache.failed; stamp = FeaturedCache.at; loading = false
            return@LaunchedEffect
        }
        loading = true
        val found = ArrayList<BookHit>()
        val failed = ArrayList<String>()
        for (s in hotSources) {
            val r = s.rule ?: continue
            runCatching {
                withContext(Dispatchers.IO) { SearchEngine(r, HttpFetcher()).hot() }
            }.onSuccess { found += it.comicAs(r).perCategory(HOT_PER_CAT) }.onFailure { failed += r.name }
        }
        FeaturedCache.books = found
        FeaturedCache.failed = failed
        FeaturedCache.at = System.currentTimeMillis()
        books = found
        bad = failed
        stamp = FeaturedCache.at
        loading = false
    }

    var cat by rememberSaveable { mutableStateOf("") }
    val groups = remember(books) { books.groupBy { featureCat(it.category) } }

    // 「更多」和「分类」开的都是一整屏（不是同页展开、也不是浮层）：底栏要让位，见 MainActivity 的 barVisible
    if (page.isNotEmpty()) {
        BackHandler { onPage("") }
        if (page == PAGE_BROWSE) {
            CategoryBrowse(books, onBack = { onPage("") }) { onInfo(infoOf(it)) }
        } else {
            CategoryBooks(page, groups[page].orEmpty(), onBack = { onPage("") }) { onInfo(infoOf(it)) }
        }
        return
    }

    // 「全部」之外只列真有书的那几类：点了空的类看到空白一页，像是坏了
    val tabs = listOf("") + FEATURE_CATS.filter { groups.containsKey(it) }
    val shown = if (cat.isEmpty()) groups.toList() else listOf(cat to groups[cat].orEmpty())

    // 搜索行和类目 tab 钉在顶上，滚动的那一格从它们下面开始（他点名的"固定不动"）。
    // 底色留在最外层：里面那一格滚的时候不许把顶栏透出页面底色之外的颜色
    Column(Modifier.fillMaxSize().background(Chrome.page)) {
        SearchRow(onSearch = onSearch, onBrowse = { onPage(PAGE_BROWSE) })
        CatTabs(tabs, cat) { cat = it }
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
            if (books.isNotEmpty()) Banner(books.take(BANNER_BOOKS)) { onInfo(infoOf(it)) }
            Notice(books.size, hotSources.size, bad, stamp, loading) { refresh++ }

            if (hotSources.isEmpty()) {
                Text(
                    "还没有哪条源写了「hot」那一格：在「管理书籍源」里把源的 JSON 补上 hot（首页的分类块怎么选），这一页才有东西可列。",
                    fontSize = 13.sp,
                    color = Chrome.faint,
                    lineHeight = 19.sp,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
                )
            }
            if (loading && books.isEmpty()) {
                Text("正在读…", fontSize = 13.sp, color = Chrome.faint, modifier = Modifier.padding(16.dp))
            }
            shown.forEach { (name, list) ->
                if (list.isNotEmpty()) {
                    // 一个类一张卡：参考图那张面板量到 x=30..1049、圆角 9~10dp、标题离卡边 34px=12dp。
                    // 左右内衬仍走我们全页统一的 16dp（参考是 11dp，书架那轮他点名要过 16dp 这一档）；
                    // 卡与卡之间参考是 22px=8dp 一道缝，所以这里每格只让 4dp
                    Column(
                        Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 4.dp)
                            .clip(RoundedCornerShape(10.dp))
                            .background(Chrome.card)
                            .padding(12.dp),
                    ) {
                        // 「更多」不在这张卡里展开，开一整屏那一类的书单（他点名的形态）
                        SectionHead(name, list.size) { onPage(name) }
                        list.take(PER_SECTION).forEach { b ->
                            HotRow(b) { onInfo(infoOf(b)) }
                        }
                    }
                }
            }
            Spacer(Modifier.height(16.dp))
        }
    }
}

private fun infoOf(b: BookHit) = BookInfo(
    title = b.bookName, author = b.author, category = b.category,
    latestChapter = b.latestChapter, wordCount = b.wordCount, intro = b.intro,
    bookUrl = b.bookUrl, sourceId = b.sourceId,
)

/**
 * 站点自己的类目名归到这一张表上：按"类目的名字里含不含这个词"命中，命中不了算「其他」。
 *
 * 为什么是子串而不是等值：两家站的同一类叫法不一样（梦书「玄幻奇幻」/爱下书「玄幻小说」，
 * 梦书「修真仙侠」/爱下书「修真小说」），等值就得给每条源抄一份表。
 * 表里的词都取自这两家站真实出现过的块名（2026-10-12 从首页抓的清单量的）。
 */
internal fun featureCat(category: String): String =
    FEATURE_CATS.firstOrNull { category.contains(it) } ?: CAT_OTHER

/**
 * 固定的一张中文类目表。顺序就是那一排 tab 的顺序。
 *
 * 「漫画」在最后：小说那六类是他逐条点过的顺序，不动它，新增的一颗只往后加。
 * 这一类和别的可能撞字（"科幻"里含"幻"、站点自己写的"热血魔幻"之类），
 * 而它靠的不是站点给的类名 —— 漫画源抓回来的书在 [FeaturedHomeScreen] 里就被改成这一类了。
 */
internal val FEATURE_CATS = listOf("玄幻", "修真", "都市", "穿越", "网游", "科幻", CAT_COMIC)

/** 漫画那一类不归站点说：一条 `kind=comic` 的源交回来的每一本都是漫画，类名由种类定。 */
internal const val CAT_COMIC = "漫画"

internal const val CAT_OTHER = "其他"

private const val BANNER_BOOKS = 3
private const val PER_SECTION = 3

/** 「分类」那一屏的页号。类目名本身就是 page 的取值，所以这颗要挑一个不会撞名的。 */
private const val PAGE_BROWSE = "#分类"

/**
 * 一条源、一个类目最多摆几本。
 *
 * 为什么不是"一条源取前 20 本"：两家站的首页是一块一块按类目排的（梦书六块、每块 12 本），
 * 整条取前 20 本只会落进头两个类目 —— 那一排类目 tab 就永远只有两颗，下面也只有两组书单。
 * 按类目各取几本，六个类目才真的都出现在这一屏上。
 * 最后那一块里"最近更新 / 最新入库"那几十本被站自己的 h2 算成和它同一个类目，
 * DOM 顺序排在真书后面，由这一格的上限挡掉。
 */
private const val HOT_PER_CAT = 6

/**
 * 漫画源的榜单：类名一律改成「漫画」。
 *
 * 不按站点给的标签归类的理由：它那一格写的是"热血 / 冒险 / 恋爱"，和小说那六类撞不进一张表；
 * 而"这是漫画"是这条源的**种类**给的实话（`kind=comic`），不是站点的分类词能改判的东西。
 */
internal fun List<BookHit>.comicAs(rule: Rule): List<BookHit> =
    if (!rule.isComic) this else map { it.copy(category = CAT_COMIC) }

internal fun List<BookHit>.perCategory(max: Int): List<BookHit> =
    groupBy { featureCat(it.category) }.values.flatMap { c -> c.take(max) }

/** 抓回来的那一份先揣在进程里：切个 tab 就重打两家站没道理。五分钟算过期。 */
private object FeaturedCache {
    var at = 0L
    var books: List<BookHit> = emptyList()
    var failed: List<String> = emptyList()
    fun fresh(): Boolean = System.currentTimeMillis() - at < 5 * 60 * 1000L && books.isNotEmpty()
}

/** 顶部那一行：左边搜索框（整条可点，切到搜书那一页），右边「分类」进参考图2 那一屏。 */
@Composable
private fun SearchRow(onSearch: () -> Unit, onBrowse: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .height(52.dp)
            .padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(
            Modifier
                .weight(1f)
                .height(34.dp)
                .clip(RoundedCornerShape(17.dp))
                // 加深一档：原先用 chip，和页面底色只差 2/2/255（白天的 #F4F5F7 落在 #F6F7F9 上），
                // 等于没有底色。换成 hairline 这一档：白天离底色 Δ19/16/12、夜间 Δ24/26/29，
                // 是一个能看出"这是一个输入槽"又没到卡片那么跳的台阶（沿用现成的深浅档，不新调颜色）
                .background(Chrome.hairline)
                .tap(onSearch)
                .padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(painterResource(R.drawable.ic_search), "搜书名", tint = Chrome.faint, modifier = Modifier.size(16.dp))
            Text("搜书名", fontSize = 14.sp, color = Chrome.faint, modifier = Modifier.padding(start = 6.dp))
        }
        Row(
            Modifier
                .width(60.dp)
                .height(34.dp)
                .tap(onBrowse),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.End,
        ) {
            Icon(painterResource(R.drawable.ic_layout_grid), "分类", tint = Chrome.ink, modifier = Modifier.size(17.dp))
            Text("分类", fontSize = 12.sp, color = Chrome.ink, modifier = Modifier.padding(start = 4.dp))
        }
    }
}

/** 类目那一排：选中项加粗 + 底下一条 12x3dp 的红条（参考图里那条短横线）。 */
@Composable
private fun CatTabs(tabs: List<String>, sel: String, onPick: (String) -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .height(44.dp)
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(28.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        tabs.forEach { t ->
            val name = if (t.isEmpty()) "全部" else t
            val on = t == sel
            Column(
                Modifier.tap { onPick(t) },
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    name,
                    fontSize = 16.sp,
                    fontWeight = if (on) FontWeight.Medium else FontWeight.Normal,
                    color = if (on) Chrome.ink else Chrome.faint,
                )
                Box(
                    Modifier
                        .padding(top = 3.dp)
                        .size(width = 12.dp, height = 3.dp)
                        .background(if (on) Chrome.dot else Chrome.page, RoundedCornerShape(2.dp)),
                )
            }
        }
    }
}

/** 推荐位轮播：横向整屏滑，一格一本（参考图那块大图我们没图可放，用封面 + 书名撑同一格位）。 */
@Composable
private fun Banner(books: List<BookHit>, onOpen: (BookHit) -> Unit) {
    val scroll = rememberScrollState()
    val density = LocalDensity.current
    BoxWithConstraints(Modifier.fillMaxWidth().padding(top = 4.dp)) {
        val card = maxWidth - 32.dp
        // 卡片之间那 12dp 也要算进"一页"的位移，不然滚到第二张时圆点还指着第一张
        val step = with(density) { (card + 12.dp).toPx() }
        // BoxWithConstraints 给的是一格 Box：圆点那行不套进 Column 就会叠在卡片上面
        // （真机量到三个点画在 banner 里、压在「热门推荐」那一行上）
        Column {
            Row(Modifier.fillMaxWidth().horizontalScroll(scroll).padding(horizontal = 16.dp)) {
                books.forEachIndexed { i, b ->
                    if (i > 0) Spacer(Modifier.width(12.dp))
                    Row(
                        Modifier
                            .width(card)
                            .height(82.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .background(Chrome.card)
                            .tap { onOpen(b) }
                            .padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        BookCover(b.bookName, "", Modifier.size(width = 52.dp, height = 68.dp))
                        Column(Modifier.padding(start = 10.dp)) {
                            Text("热门推荐", fontSize = 11.sp, color = Chrome.dot)
                            Text(b.bookName, fontSize = 17.sp, fontWeight = FontWeight.Medium, color = Chrome.ink, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 4.dp))
                            val who = listOf(b.author, b.category).filter { it.isNotEmpty() }.joinToString(" · ")
                            if (who.isNotEmpty()) Text(who, fontSize = 12.sp, color = Chrome.faint, modifier = Modifier.padding(top = 4.dp))
                        }
                        Spacer(Modifier.weight(1f))
                        Icon(painterResource(R.drawable.ic_chevron_right), "看这本书", tint = Chrome.faint, modifier = Modifier.size(16.dp))
                    }
                }
            }
            // 圆点：取"当前离哪一页最近"，四舍五入而不是取整 —— 滚过半张就该换点。
            // 走 derivedStateOf 而不是直接读 scroll.value：后者每滚一帧就重组一次整屏（lint 的
            // FrequentlyChangingValue 报的就是这个），派生出来的页码只在真的换页那一帧才变
            val page by remember(scroll, step, books) {
                derivedStateOf { (scroll.value / step).roundToInt().coerceIn(0, books.lastIndex) }
            }
            Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.Center) {
                books.forEachIndexed { i, _ ->
                    Box(
                        Modifier
                            .padding(horizontal = 3.dp)
                            .size(if (i == page) 7.dp else 5.dp)
                            .background(if (i == page) Chrome.dot else Chrome.hairline, CircleShape),
                    )
                }
            }
        }
    }
}

/** 一条真话当公告：几条源在跑、抓到多少本、上次什么时候抓的。点它=立刻重抓一次。 */
@Composable
private fun Notice(books: Int, sources: Int, bad: List<String>, at: Long, loading: Boolean, onRefresh: () -> Unit) {
    val time = if (at == 0L) "" else SimpleDateFormat("HH:mm", Locale.CHINA).format(Date(at))
    val line = when {
        loading -> "正在从 $sources 条源读热门…"
        bad.isNotEmpty() -> "${bad.joinToString("、")} 这次没读到 · 其余 $books 本"
        else -> "$sources 条源 · 抓到 $books 本" + if (time.isEmpty()) "" else " · 上次 $time"
    }
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp)
            .height(34.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(Chrome.chip)
            .tap(onRefresh)
            .padding(horizontal = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .clip(RoundedCornerShape(4.dp))
                .background(Chrome.dot)
                .padding(horizontal = 5.dp, vertical = 1.dp),
        ) { Text("刷新", fontSize = 10.sp, color = Color.White) }
        Text(line, fontSize = 12.sp, color = Chrome.faint, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(start = 8.dp))
        Spacer(Modifier.weight(1f))
        // 这一行整行点它就是重抓，所以行尾不该是一颗「还能进去」的 >（它不翻页）。换成刷新那颗。
        // 右边也要和上面卡片右端那颗 > 站在同一条竖线上：卡片那颗的墨右边缘真机量到 x=989；
        // 这一颗的格子右边落在药丸内衬 10dp 处 = x=1008.5，而 ic_refresh 的墨在 14dp 格子里只空
        // 4/24 × 14dp = 6.4px，墨右边缘会到 x=1002 —— 比上面低 13px。往回收 4.8dp 正好压在同一条线。
        Icon(
            painterResource(R.drawable.ic_refresh), "重新抓一次",
            tint = Chrome.faint, modifier = Modifier.padding(end = 4.8.dp).size(14.dp),
        )
    }
}

@Composable
private fun SectionHead(name: String, count: Int, onMore: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(top = 2.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(name, fontSize = 16.sp, fontWeight = FontWeight.Medium, color = Chrome.ink)
        Spacer(Modifier.weight(1f))
        if (count > PER_SECTION) {
            // 参考图那颗「更多」是描边 pill（量的时候只有上下两条边沿不是底色，中间和卡面同色），不是实底
            Row(
                Modifier
                    .clip(RoundedCornerShape(11.dp))
                    .border(1.dp, Chrome.hairline, RoundedCornerShape(11.dp))
                    .tap(onMore)
                    // 参考那颗 pill 量到 137x60px（50x22dp）：10sp 字的行盒只有 37px，剩下的靠内衬补
                    .padding(start = 11.dp, end = 7.dp, top = 4.dp, bottom = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("更多", fontSize = 10.sp, color = Chrome.faint)
                Icon(
                    painterResource(R.drawable.ic_chevron_right), "看这一类全部",
                    tint = Chrome.faint, modifier = Modifier.size(12.dp),
                )
            }
        }
    }
}

/** 书单里的一行：封面 + 书名 + 那一格简介位 + 底行（源名 + 类目 chip），摆在类目卡里。 */
@Composable
private fun HotRow(b: BookHit, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .tap(onClick)
            .padding(vertical = 7.dp),
    ) {
        BookCover(b.bookName, "", Modifier.size(width = 62.dp, height = 76.dp))
        Column(Modifier.padding(start = 10.dp).fillMaxWidth()) {
            Text(b.bookName, fontSize = 16.sp, fontWeight = FontWeight.Medium, color = Chrome.ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
            // 站点首页清单里没有简介，这一格先放作者；BookHit.intro 有值就自动换成简介，版式不动。
            // 12sp = 参考图那一行的字面 em 量到 33.6px（33.6/2.75=12.2sp），行距 18sp 也是量的（两行墨顶相距 51px）
            val blurb = b.intro.ifEmpty { b.author }
            if (blurb.isNotEmpty()) {
                Text(blurb, fontSize = 12.sp, color = Chrome.faint, lineHeight = 18.sp, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 6.dp))
            }
            Row(
                Modifier.fillMaxWidth().padding(top = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(b.sourceName, fontSize = 12.sp, color = Chrome.faint, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Spacer(Modifier.weight(1f))
                if (b.category.isNotEmpty()) Chip(b.category)
            }
        }
    }
}

/**
 * 行尾那个类目标签。参考图那一排标签是描边的（chip 底和卡面只差一档，我们实底时白卡上只有 Δ11，
 * 白天几乎看不出边界）→ 和「更多」同一做法：1dp hairline 描边，日夜两套都看得见。
 */
/**
 * 一整屏只列一个类目的书（点卡片右上那颗「更多」进来），版式照他给的"主编力荐"那一屏。
 *
 * 那屏的数（1080 宽、同一把尺）：顶栏标题字面 em=45.7px=16.6sp 且真的居中（墨 451..629，屏心 540）；
 * 封面 181x225px = 66x82dp；行周期 265px = 96dp（行间净空 41px = 15dp）；
 * 行书名 em=40px=14.5sp、简介 em=33px=12sp 两行、底行 em=31px=11.3sp、标签 em=25.6px=9.3sp。
 *
 * 参考图每行右上角还有一颗"加入书架"，这一屏不放：我们没有"轻轻一下就把整本收进书架"这个动作
 * —— 下全本是几百个请求的重操作，挂在一颗不带确认的图标上就是误触；点开这一行就有得选。
 */
@Composable
private fun CategoryBooks(name: String, books: List<BookHit>, onBack: () -> Unit, onOpen: (BookHit) -> Unit) {
    Column(Modifier.fillMaxSize().background(Chrome.page)) {
        Row(
            Modifier
                .fillMaxWidth()
                .height(52.dp)
                .padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            BackSlot("返回精选", onBack)
            Text(name, fontSize = 17.sp, fontWeight = FontWeight.Medium, color = Chrome.ink, modifier = Modifier.weight(1f), textAlign = TextAlign.Center)
            // 标题要真居中：右边补一颗和 ‹ 同宽的配重，不然整块被那颗挤偏 22dp
            Spacer(Modifier.width(44.dp))
        }
        LazyColumn(
            Modifier.fillMaxWidth().weight(1f),
            contentPadding = PaddingValues(start = 16.dp, top = 4.dp, end = 16.dp, bottom = 16.dp),
        ) {
            items(books, key = { it.sourceId + it.bookUrl }) { b ->
                Row(
                    Modifier
                        .fillMaxWidth()
                        .tap { onOpen(b) }
                        .padding(vertical = 7.dp),
                ) {
                    BookCover(b.bookName, "", Modifier.size(width = 66.dp, height = 82.dp))
                    Column(Modifier.padding(start = 6.dp).fillMaxWidth()) {
                        Text(b.bookName, fontSize = 15.sp, fontWeight = FontWeight.Medium, color = Chrome.ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        val blurb = b.intro.ifEmpty { b.author }
                        if (blurb.isNotEmpty()) {
                            Text(blurb, fontSize = 12.sp, color = Chrome.faint, lineHeight = 17.sp, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 6.dp))
                        }
                        Row(
                            Modifier.fillMaxWidth().padding(top = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(b.sourceName, fontSize = 11.sp, color = Chrome.faint, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Spacer(Modifier.weight(1f))
                            if (b.category.isNotEmpty()) Chip(b.category, 10.sp)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun Chip(text: String, size: TextUnit = 11.sp) {
    Box(
        Modifier
            .padding(start = 6.dp)
            .clip(RoundedCornerShape(4.dp))
            .background(Chrome.chip)
            .border(1.dp, Chrome.hairline, RoundedCornerShape(4.dp))
            .padding(horizontal = 7.dp, vertical = 3.dp),
    ) { Text(text, fontSize = size, color = Chrome.faint) }
}

/**
 * 参考图2 那一屏：左栏类目、右栏两列格子。
 *
 * 图2 右边那一格是"子类 + 藏书数"，我们这一层没有子类可列，就把该类目下真抓到的书摆成两列
 * （封面 + 书名 + 作者），点它直接进本书信息 —— 左上角那颗「全部作品」才回精选的分组书单。
 */
@Composable
private fun CategoryBrowse(books: List<BookHit>, onBack: () -> Unit, onOpen: (BookHit) -> Unit) {
    val groups = remember(books) { books.groupBy { featureCat(it.category) } }
    val names = groups.keys.toList()
    var sel by rememberSaveable { mutableStateOf(names.firstOrNull().orEmpty()) }
    Column(Modifier.fillMaxSize().background(Chrome.page)) {
        // 导航栏那一格：标题居中 = 墨的中心压在屏幕中线上，不靠两边各摆一颗撑出来（两边一颗 44dp、
        // 一颗三个字，撑出来的"居中"会偏 121px）。所以整格换成 Box，三样各贴各的边。
        Box(Modifier.fillMaxWidth().height(52.dp)) {
            Box(Modifier.align(Alignment.CenterStart).padding(start = 16.dp)) { BackSlot("返回精选", onBack) }
            Text("分类", fontSize = 19.sp, fontWeight = FontWeight.Medium, color = Chrome.ink, modifier = Modifier.align(Alignment.Center))
            // 图2 右上角那颗是"不按类目看全部"，在我们这就是回精选那一屏的「全部」分组
            Text("全部作品", fontSize = 11.sp, color = Chrome.dot, modifier = Modifier.align(Alignment.CenterEnd).padding(end = 16.dp).tap(onBack))
        }
        Row(Modifier.fillMaxWidth().weight(1f)) {
            // 左栏宽度不写死：整栏由最宽那一格撑出来（原先钉 76dp，两字的类目名只占 18.5dp，
            // 于是"字→分界线"空到 42.5dp、而"屏边→字"只有 14dp，左右一眼就不等价）。
            // 现在两边都是 14dp。每格自己包内容，不能写 fillMaxWidth —— 在 Row 里那会吃掉整行剩余宽度。
            Column(Modifier.fillMaxHeight().verticalScroll(rememberScrollState())) {
                names.forEach { n ->
                    val on = n == sel
                    Box(
                        Modifier
                            .height(48.dp)
                            .tap { sel = n },
                        contentAlignment = Alignment.CenterStart,
                    ) {
                        if (on) Box(Modifier.size(width = 2.dp, height = 16.dp).background(Chrome.dot))
                        Text(n, fontSize = 13.sp, color = if (on) Chrome.dot else Chrome.faint, modifier = Modifier.padding(start = 14.dp, end = 14.dp))
                    }
                }
            }
            // 0.5dp 是这台机能落到的最细的一条：1dp 量到 3 设备px，0.5dp 取整成 1px
            Box(Modifier.width(0.5.dp).fillMaxHeight().background(Chrome.hairline))
            Column(Modifier.weight(1f)) {
                val list = groups[sel].orEmpty()
                Text("共 ${list.size} 本", fontSize = 12.sp, color = Chrome.faint, modifier = Modifier.padding(start = 12.dp, top = 12.dp, bottom = 4.dp))
                LazyVerticalGrid(
                    columns = GridCells.Fixed(2),
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(start = 8.dp, end = 8.dp, bottom = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    items(list, key = { it.sourceId + it.bookUrl }) { b ->
                        Row(Modifier.padding(4.dp).tap { onOpen(b) }) {
                            BookCover(b.bookName, "", Modifier.size(width = 44.dp, height = 56.dp))
                            Column(Modifier.padding(start = 8.dp)) {
                                Text(b.bookName, fontSize = 14.sp, color = Chrome.ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                if (b.author.isNotEmpty()) Text(b.author, fontSize = 11.sp, color = Chrome.faint, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 4.dp))
                            }
                        }
                    }
                }
            }
        }
    }
}
