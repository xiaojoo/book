package com.bookreader.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
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
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.pulltorefresh.PullToRefreshDefaults
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.Dp
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
import kotlin.math.PI
import kotlin.math.roundToInt
import kotlin.math.sin

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
@OptIn(ExperimentalMaterial3Api::class)
fun FeaturedHomeScreen(
    sources: BookSourceStore,
    page: String,
    onPage: (String) -> Unit,
    onInfo: (BookInfo) -> Unit,
    onSearch: () -> Unit,
) {
    // 随包那几条先进这一屏就和对账，再列源：包里换了规则而手机上那份文件还是旧的时，榜单和封面
    // 都停在旧那一版（2026-10-26 华为上"精选只有漫画有封面"量的就是这个）。不等他先点开「搜书」。
    var hotSources by remember { mutableStateOf<List<Rule>>(emptyList()) }
    var books by remember { mutableStateOf(FeaturedCache.books) }
    var bad by remember { mutableStateOf(FeaturedCache.failed) }
    var stamp by remember { mutableLongStateOf(FeaturedCache.at) }
    var loading by remember { mutableStateOf(books.isEmpty()) }
    // 键只能是"要重抓"那颗计数。写成 LaunchedEffect(loading) 会在全部源都抓失败时
    // 永远 fresh()=false → 键一变就重跑，变成不停打这两家站
    var refresh by remember { mutableIntStateOf(0) }
    LaunchedEffect(refresh) {
        val rules = withContext(Dispatchers.IO) {
            sources.seed()
            sources.list().filter { it.usable && it.rule?.hot != null }.mapNotNull { it.rule }
        }
        hotSources = rules
        // 只有"刚进这一屏那一次"（refresh==0）才允许吃 5 分钟缓存 —— 切 tab 回来不该再打一次站。
        // 他主动的那两下（点刷新那一行、屏幕下拉）必须真去抓：下拉完发现"数据还是五分钟前那份"
        // 等于下拉了个寂寞。
        if (refresh == 0 && FeaturedCache.fresh()) {
            books = FeaturedCache.books; bad = FeaturedCache.failed; stamp = FeaturedCache.at; loading = false
            return@LaunchedEffect
        }
        loading = true
        val found = ArrayList<BookHit>()
        val failed = ArrayList<String>()
        for (r in rules) {
            runCatching {
                withContext(Dispatchers.IO) { SearchEngine(r, HttpFetcher()).hot() }
            }.onSuccess { found += it.kindAs(r) }.onFailure { failed += r.name }
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
            CategoryBrowse(books, hotSources, isRefreshing = loading, onRefresh = { refresh++ }, onBack = { onPage("") }) { onInfo(infoOf(it)) }
        } else {
            CategoryBooks(page, groups[page].orEmpty(), hotSources, onBack = { onPage("") }) { onInfo(infoOf(it)) }
        }
        return
    }

    // 「全部」之外只列真有书的那几类：点了空的类看到空白一页，像是坏了
    val tabs = listOf("") + FEATURE_CATS.filter { groups.containsKey(it) }
    val shown = if (cat.isEmpty()) groups.toList() else listOf(cat to groups[cat].orEmpty())

    // 搜索行和类目 tab 钉在顶上，滚动的那一格从它们下面开始（他点名的"固定不动"）。
    // 底色留在最外层：里面那一格滚的时候不许把顶栏透出页面底色之外的颜色。
    // 下拉刷新只挂在会滚的那一格上 —— 顶上那两行不参与，拉它不会出那颗圈。
    val ptr = rememberPullToRefreshState()
    val firstLoad = books.isEmpty() && loading
    Column(Modifier.fillMaxSize().background(Chrome.page)) {
        // 第一次进这一屏：搜索条和那排"菜单"也一起进骨架 —— 只把列表换成骨架、顶上两排还是空的，
        // 看上去像坏了一半
        if (!firstLoad) {
            SearchRow(onSearch = onSearch, onBrowse = { onPage(PAGE_BROWSE) })
            CatTabs(tabs, cat) { cat = it }
        }
        PullToRefreshBox(
            isRefreshing = loading,
            onRefresh = { refresh++ },
            state = ptr,
            modifier = Modifier.weight(1f).fillMaxWidth(),
            // M3 那颗圈撤了：刷新的表现换成滚动区顶上多出来的那一行三个点。
            // 拉的时候那一行跟着下来、松手留在顶上直到真抓完 —— 它说的是"在等"，不是"快好了"
            indicator = {},
        ) {
            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
                if (loading && books.isNotEmpty()) LoadingDots()
                if (books.isNotEmpty()) Banner(books.take(BANNER_BOOKS)) { onInfo(infoOf(it)) }
                // 第一次那一屏整块换成骨架，公告行也在骨架里（一根灰条）：
                // 留着真公告会在骨架顶上多出一行字，同一件事说两遍
                if (!firstLoad) Notice(books.size, hotSources.size, bad, stamp, loading) { refresh++ }

                if (hotSources.isEmpty() && !firstLoad) {
                    Text(
                        "还没有哪条源写了「hot」那一格：在「管理书籍源」里把源的 JSON 补上 hot（首页的分类块怎么选），这一页才有东西可列。",
                        fontSize = 13.sp,
                        color = Chrome.faint,
                        lineHeight = 19.sp,
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
                    )
                }
                // 「正在读…」那一行撤了：顶上那三个点就是它，两种指示同时说是重复
                if (firstLoad) {
                    // search = true：顶上那两排（搜索条 + 类目「菜单」）真身已经让位，骨架得把它们也画上，
                    // 不然上半屏是一片空灰
                    FeaturedSkeleton()
                } else {
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
                }
                Spacer(Modifier.height(16.dp))
            }
        }
    }
}

private fun infoOf(b: BookHit) = BookInfo(
    title = b.bookName, author = b.author, category = b.category,
    latestChapter = b.latestChapter, wordCount = b.wordCount, intro = b.intro,
    bookUrl = b.bookUrl, sourceId = b.sourceId, coverUrl = b.coverUrl,
)

/**
 * 三个点：一行高、居中，三颗按错开的相位亮下去。下拉刷新和滚到底翻页共用这一颗。
 *
 * 换掉 M3 那颗转圈的理由不是审美：转圈暗示"知道还要多久、正在路上"，而这两处我们真的不知道 ——
 * 站点那一页没回来就是没回来。三个点说的是"在等"，不是"快好了"。
 *
 * 相位由一个总时钟算出来（不是三颗各起一条动画）：三颗永远同拍，也不会随重组各自漂相位。
 */
@Composable
internal fun LoadingDots(dot: Color = Chrome.dot, rowHeight: Dp = 44.dp) {
    val clock = rememberInfiniteTransition(label = "loading-dots")
    val phase by clock.animateFloat(
        initialValue = 0f, targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(1_000, easing = LinearEasing)),
        label = "phase",
    )
    Row(
        Modifier.fillMaxWidth().height(rowHeight),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        repeat(3) { i ->
            val a = 0.28f + 0.72f * (0.5f + 0.5f * sin(2.0 * PI * (phase - i / 3.0)).toFloat())
            Box(Modifier.padding(horizontal = 4.dp).size(7.dp).clip(CircleShape).background(dot.copy(alpha = a)))
        }
    }
}


/**
 * 精选/分类的骨架屏：形状就是真那一屏从上到下的样子 —— 搜索条、类目那一排"菜单"、轮播图（连底下那三颗点）、
 * 公告行、然后两组"标题条 + 三行"。差的是内容，位置一个都不差。
 *
 * 为什么不是转圈：转圈只说"在等"，骨架说的是"这里将出现什么"，而这两家站一次要七八秒 ——
 * 那几秒里最不该看到的是一片空白。
 *
 * 条的底色用 `Chrome.hairline`，不用 `chip`：chip 白天落在页面底色上只差 2/255（`SearchRow` 里那条
 * 注释记的就是同一件事），第一版真机截图量下来只有白卡里那几根看得见、页面底色上的全隐形。
 * 呼吸走整条明暗，不走移动的高光：高光要么和滚动打架，要么在低刷屏上看着像卡帧。
 *
 * [rows] 给分类页用：那一屏顶部只有"共 N 本"一行，所以只摆一组卡。
 * [grid] 也是分类页：那一格书墙是两列的方格，不是精选那种整行卡 —— 形状不一样就得换一套条。
 */
@Composable
private fun FeaturedSkeleton(rows: Int = 2, search: Boolean = true, grid: Boolean = false) {
    val breath = rememberInfiniteTransition(label = "skeleton")
    // 地板 0.6：再淡下去 hairline 混进页面底色，呼吸就变成"没画"
    val a by breath.animateFloat(
        0.6f, 1f,
        infiniteRepeatable(tween(900, easing = LinearEasing), RepeatMode.Reverse),
        label = "alpha",
    )
    val ghost = Chrome.hairline.copy(alpha = a)
    @Composable
    fun bar(w: Dp, h: Dp, r: Int = 3, top: Dp = 4.dp) = Box(
        Modifier.padding(top = top).size(width = w, height = h)
            .clip(RoundedCornerShape(r.dp)).background(ghost),
    )
    Column(Modifier.fillMaxWidth()) {
        if (search) {
            // 搜索条：整行 52dp、槽 34dp 高 / 圆角 17dp、右边那 60dp 是真的「分类」那一格。
            // 尺寸照 SearchRow 抄 —— 骨架换成真身那一帧不许跳
            Row(
                Modifier.fillMaxWidth().height(52.dp).padding(horizontal = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(Modifier.weight(1f).height(34.dp).clip(RoundedCornerShape(17.dp)).background(ghost))
                // 「分类」那颗也在骨架里：17dp 的格子图标 + 4dp + 两个字（12sp 两个汉字 ≈ 24dp），
                // 靠右贴齐那 60dp —— 真身就是这两样，只空着右边会让这一格看着"还没做完"
                Row(
                    Modifier.width(60.dp),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    bar(17.dp, 17.dp, 2, 0.dp)
                    Box(Modifier.padding(start = 4.dp)) { bar(24.dp, 12.dp, 3, 0.dp) }
                }
            }
            // 顶上那排"菜单"：整行 44dp、八根 30×16、间隔 28dp。
            // 八根不是六根：真那一排是「全部」+ 八类（FEATURE_CATS 八条），本来就滚得出屏，
            // 骨架只画到屏内等于把这排能滚这件事说没了。
            // wrapContentWidth(unbounded) + 从左边起：这一排比屏宽。直接写宽度会被父约束夹回来，
            // 而 requiredSize 那档默认居中会把整排往左挪 —— 第一版真机量到左边那根被切掉 81px、
            // 右边还剩两根在屏外，正好反了。也不用 horizontalScroll：骨架不该能被拖着走
            Box(Modifier.fillMaxWidth().height(44.dp).clipToBounds()) {
                Row(
                    Modifier.wrapContentWidth(align = Alignment.Start, unbounded = true).height(44.dp).padding(start = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(28.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    repeat(8) { bar(30.dp, 16.dp, 3, 0.dp) }
                }
            }
        }
        if (grid) {
            // 分类页右栏的形状：两列方格，每格一张 44×56 封面 + 两行字。
            // 横宽用 weight(1f) 而不是写死 dp —— 那一格是 GridCells.Fixed(2)，跟着它分才不错位。
            // 行距 68dp = 封面 56 + 真那一格上下内衬各 4 + 网格自己那道 4dp 间隔
            Column(Modifier.fillMaxSize().clipToBounds().padding(horizontal = 8.dp)) {
                repeat(rows * 4) {
                    Row(Modifier.fillMaxWidth().padding(top = 12.dp)) {
                        repeat(2) {
                            Row(Modifier.weight(1f).padding(horizontal = 4.dp)) {
                                bar(44.dp, 56.dp, 4, 0.dp)
                                Column(Modifier.padding(start = 8.dp)) { bar(78.dp, 14.dp); bar(50.dp, 10.dp) }
                            }
                        }
                    }
                }
            }
        } else {
            Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
                // 轮播图：一张 82dp 的卡 + 底下三颗点（点位和真的那三颗同一档间距）
                Box(
                    Modifier.fillMaxWidth().padding(top = 4.dp).height(82.dp).clip(RoundedCornerShape(8.dp)).background(Chrome.card).padding(12.dp),
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        bar(52.dp, 68.dp, 4, 0.dp)
                        Column(Modifier.padding(start = 10.dp)) { bar(56.dp, 12.dp); bar(150.dp, 16.dp); bar(96.dp, 12.dp) }
                    }
                }
                Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.Center) {
                    repeat(3) { Box(Modifier.padding(horizontal = 3.dp).size(6.dp).clip(CircleShape).background(ghost)) }
                }
                // 公告那一行（真身：圆点下面空 23dp 才到那行 12sp 的字）
                bar(240.dp, 14.dp, 3, 20.dp)
                repeat(rows) {
                    Box(
                        Modifier.fillMaxWidth().padding(top = 8.dp).clip(RoundedCornerShape(10.dp)).background(Chrome.card).padding(12.dp),
                    ) {
                        Column {
                            // 标题那一格真身有 44dp（16sp 的字 + 右边那颗 22dp 的「更多」pill + 上下内衬），
                            // 条只画 18dp、剩下的算进行高 —— 不然每张卡比真的矮 40dp，换真身时整屏往上跳
                            Box(Modifier.fillMaxWidth().height(44.dp), contentAlignment = Alignment.CenterStart) {
                                bar(88.dp, 18.dp, 3, 0.dp)
                            }
                            // 行距 90dp = 封面 76 + 真那一行的上下内衬各 7（2026-10-29 量到封面顶间距 246px）
                            repeat(3) {
                                Row(Modifier.fillMaxWidth().padding(top = 14.dp), verticalAlignment = Alignment.Top) {
                                    bar(62.dp, 76.dp, 4, 0.dp)
                                    Column(Modifier.padding(start = 10.dp)) { bar(180.dp, 16.dp); bar(120.dp, 12.dp); bar(64.dp, 12.dp) }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

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
internal val FEATURE_CATS = listOf("玄幻", "修真", "都市", "穿越", "网游", "科幻", CAT_COMIC, CAT_AUDIO)

/** 漫画那一类不归站点说：一条 `kind=comic` 的源交回来的每一本都是漫画，类名由种类定。 */
internal const val CAT_COMIC = "漫画"

/** 同上一条：`kind=audio` 的源交回来的每一本都是有声书，类名由种类定。 */
internal const val CAT_AUDIO = "有声"

internal const val CAT_OTHER = "其他"

private const val BANNER_BOOKS = 3
private const val PER_SECTION = 3

/** 「分类」那一屏的页号。类目名本身就是 page 的取值，所以这颗要挑一个不会撞名的。 */
private const val PAGE_BROWSE = "#分类"

/**
 * 漫画源和有声源的榜单：类名一律改成「漫画」/「有声」。
 *
 * 不按站点给的标签归类的理由：漫画那一格写的是"热血 / 冒险 / 恋爱"，有声那一格写的是
 * "都市 / 推理"，和小说那六类撞不进一张表；而"这是漫画/有声书"是这条源的**种类**给的实话
 * （`kind=...`），不是站点的分类词能改判的东西。
 */
internal fun List<BookHit>.kindAs(rule: Rule): List<BookHit> = when {
    rule.isComic -> map { it.copy(category = CAT_COMIC) }
    rule.isAudio -> map { it.copy(category = CAT_AUDIO) }
    else -> this
}

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
                        BookCover(b.bookName, "", Modifier.size(width = 52.dp, height = 68.dp), remote = b.coverUrl)
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
                    // 选中那颗以前是 7dp、其余 5dp：圆点一排里有一颗大一号，眼睛先看见那颗"胖的"，
                    // 反而读成"这里能点"。现在五颗同尺寸（6dp = 原来那两个数的中点，两边各挪 1dp），
                    // 谁在当前页只由颜色说
                    Box(
                        Modifier
                            .padding(horizontal = 3.dp)
                            .size(6.dp)
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
        // 行尾那颗刷新图标按他的要求撤了：这一行整行点下去就是重抓（`.tap(onRefresh)`），
        // 左边那颗红药丸也已经写着「刷新」—— 同一件事在一行里说三遍，那颗是重复入口，不是入口。
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
        BookCover(b.bookName, "", Modifier.size(width = 62.dp, height = 76.dp), remote = b.coverUrl)
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
/**
 * 一个类目的「加载更多」：滚到接近底部，就向**写了 `hot.page` 的那几条源**要下一页，追加、去重。
 *
 * 「更多」那一整屏和「分类」页右边那格书墙共用这一格 —— 两处各自实现过一份的话，
 * 以后改一处漏一处，表现就是"同一类书在一屏能翻、在另一屏翻不动"。
 * 滚动状态也从这里交出去：`LazyColumn` 和 `LazyVerticalGrid` 认的是同一个 `LazyListState`。
 */
@Composable
private fun rememberCategoryPager(
    name: String,
    pool: List<BookHit>,
    rules: List<Rule>,
    source: Rule?,
    catUrl: String,
    atBottom: () -> Boolean,
): CategoryPager {
    // 谁去要下一页：选了具体类目就只那一家站（而且它的 `cats.page` 拼得出第 2 页）；
    // 只选了站点就用那家站的 `hot.page`；什么都没选就是全场几家能翻的站
    val pagers = remember(rules, source, catUrl) {
        when {
            // 选了类目：这一家站有 cats 就该去拿第 1 页（第 2 页拼不拼得出来由 cats.page 说话，
            // 拼不出来就是拿完第 1 页后 fresh 为空 → 判成"到底了"，不会空转）
            catUrl.isNotBlank() -> listOfNotNull(source?.takeIf { it.cats != null })
            source != null -> listOfNotNull(source.takeIf { it.hot?.page?.isNotBlank() == true })
            else -> rules.filter { it.hot?.page?.isNotBlank() == true }
        }
    }
    // 这几样都按「类目 + 站点 + 具体哪一类」记：只按 name 记的话，从「全部」切到「瓜子·恋爱」
    // 会把上一份追加进来的那几十本混在新选择里，屏幕上就是"我明明选了恋爱，怎么还有别的站的书"
    val key = name + (source?.id.orEmpty()) + catUrl
    // 选了具体类目：那一类的第 1 页也得去类目页拿 —— 原来第 1 页永远用「该站榜单里这一类」那份，
    // 点了类目标签屏上还是原来那几十本，表现就是"点了没筛选"。页号从 0 起 = 连第 1 页都还没拿
    val fromCat = catUrl.isNotBlank()
    var extra by remember(key) { mutableStateOf<List<BookHit>>(emptyList()) }
    var page by remember(key) { mutableIntStateOf(if (fromCat) 0 else 1) }
    // 初值不能是 true：下面那个 collect 的守卫是"正在加载就跳过"，一进来就 true 等于把自己堵死，
    // 第 1 页永远不发 —— 屏上就是"点了类目标签，结果一本都没有"。第 1 页由 page<1 那一下立刻触发
    var loading by remember(key) { mutableStateOf(false) }
    // 有一页拿回来全是见过的 → 这一类到底了，不再打第二枪
    var dry by remember(key) { mutableStateOf(false) }
    val books = remember(pool, extra, fromCat) { (if (fromCat) emptyList() else pool) + extra }
        .distinctBy { it.sourceId + it.bookUrl }

    // 键里不放 loading：那一格是在这个协程里翻的，放进键会把自己取消掉
    LaunchedEffect(key, dry, pagers) {
        if (pagers.isEmpty() || dry) return@LaunchedEffect
        // 带上页号一起发：只看布尔的话，第 1 页到手后"到底了"仍然是 true —— 值没变，
        // snapshotFlow 就不发第二拍，第 2 页永远不发（真机现场：瓜子·恋爱 滚 8 下还是 36 本）
        snapshotFlow { (atBottom() || page < 1) to page }.collect { (bottom, _) ->
            if (!bottom || loading) return@collect
            loading = true
            val next = page + 1
            val got = withContext(Dispatchers.IO) {
                pagers.flatMap { r ->
                    runCatching {
                        val e = SearchEngine(r, HttpFetcher())
                        // 类目页交回来的就是那一类的书，类名是站点自己写的（"幽默"、"詩類"），
                        // 不能再拿我们那八档去筛一遍 —— 一筛就全空
                        if (catUrl.isNotBlank()) e.catBooks(catUrl, next).kindAs(r)
                        else e.hot(next).kindAs(r).filter { featureCat(it.category) == name }
                    }.getOrDefault(emptyList())
                }
            }
            val fresh = got.distinctBy { it.sourceId + it.bookUrl }
                .filter { g -> books.none { it.sourceId == g.sourceId && it.bookUrl == g.bookUrl } }
            if (fresh.isEmpty()) dry = true else { extra += fresh; page = next }
            loading = false
        }
    }
    return CategoryPager(books, loading, dry, pagers.isNotEmpty())
}

/**
 * 两排选择的共用状态：第一排=这一类里有书的几家站，第二排=选中那家站自己的真实类目。
 *
 * 「更多」那一整屏和「分类」页右边那格都用它 —— 各写一份的话，以后改一处漏一处，
 * 表现就是"同一类书在一屏能挑站点、在另一屏不能"。
 */
@Composable
private fun rememberCatScope(name: String, all: List<BookHit>, rules: List<Rule>): CatScope {
    val inCat = remember(rules, all) { rules.filter { r -> r.hot != null && all.any { it.sourceId == r.id } } }
    var src by remember(name) { mutableStateOf("") }
    var cat by remember(name) { mutableStateOf("") }
    var cats by remember(name, src) { mutableStateOf<List<Pair<String, String>>>(emptyList()) }
    var wide by remember(name) { mutableStateOf(false) }
    val chosen = inCat.firstOrNull { it.name == src }
    LaunchedEffect(name, src) {
        cat = ""
        val r = inCat.firstOrNull { it.name == src }
        cats = if (r?.cats == null) emptyList() else withContext(Dispatchers.IO) {
            runCatching { SearchEngine(r, HttpFetcher()).cats() }.getOrDefault(emptyList())
        }
    }
    // 只选了站点没选类目：第一页就是那家站自己那份（"全部"= 该站这一类全部）
    val pool = remember(all, chosen) { if (chosen == null) all else all.filter { it.sourceId == chosen.id } }
    return CatScope(inCat, chosen, src, { src = it }, cat, { cat = it }, cats,
        cats.firstOrNull { it.first == cat }?.second.orEmpty(), pool, wide) { wide = it }
}

/** 两排此刻的结果：可选的站点、选中的站点与类目、该站的类目表、按站点筛过的那一份第一页。 */
private class CatScope(
    val inCat: List<Rule>,
    val chosen: Rule?,
    val src: String,
    val pickSrc: (String) -> Unit,
    val cat: String,
    val pickCat: (String) -> Unit,
    val cats: List<Pair<String, String>>,
    val catUrl: String,
    val pool: List<BookHit>,
    val wide: Boolean,
    val pickWide: (Boolean) -> Unit,
)

/**
 * 分类页用的那一版两排：字、行高、起始位置都照左栏那一格（13sp、48dp 一行、第一排顶上去 11dp）。
 * 「更多」那一整屏用的还是 [CatScopes]（那一屏顶部本来就是 16sp 的标题档，跟着它走）。
 */
@Composable
private fun CatScopesFlat(sc: CatScope) {
    if (sc.inCat.size > 1) {
        val names = sc.inCat.map { it.name.substringBefore("（").trim() }
        FlatTabs(listOf("") + names, sc.src.substringBefore("（").trim(), 0.dp) { t ->
            sc.pickSrc(sc.inCat.firstOrNull { it.name.substringBefore("（").trim() == t }?.name ?: "")
        }
    }
    if (sc.cats.isNotEmpty()) {
        val all = sc.cats.map { it.first }
        val shown = if (all.size > CAT_TABS - 1) all.take(CAT_TABS - 1) + MORE else all
        if (sc.wide) CatChips(listOf("") + all, sc.cat) { sc.pickCat(it); sc.pickWide(false) }
        else FlatTabs(listOf("") + shown, if (sc.cat.isEmpty() || shown.contains(sc.cat)) sc.cat else MORE, 0.dp) { t ->
            if (t == MORE) sc.pickWide(true) else sc.pickCat(t)
        }
    }
}

/** 一排扁平的筛选标签：和左栏那些格子同一档字、同一行高，选中只换颜色（不加下划线、不放大）。 */
@Composable
private fun FlatTabs(names: List<String>, sel: String, top: Dp, onPick: (String) -> Unit) {
    Row(
        Modifier.fillMaxWidth().offset(y = top).height(48.dp)
            .horizontalScroll(rememberScrollState()).padding(horizontal = 14.dp),
        horizontalArrangement = Arrangement.spacedBy(20.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        names.forEach { n ->
            val on = n == sel
            Text(
                if (n.isEmpty()) "全部" else n,
                fontSize = 13.sp,
                fontWeight = if (on) FontWeight.Medium else FontWeight.Normal,
                color = if (on) Chrome.dot else Chrome.faint,
                maxLines = 1,
                modifier = Modifier.tap { onPick(n) },
            )
        }
    }
}

/** 那两排本身。第二排只在选了某一家站、而那家站确实摘得出类目表时出现。 */
@Composable
private fun CatScopes(sc: CatScope) {
    // 站点名是给"管理书籍源"那一屏看的，规则名里常带括号说明（199mh 漫画（要账号）），
    // 摆在 tab 上一颗就占掉半屏 —— 那一排只留括号前面的部分
    if (sc.inCat.size > 1) {
        val names = sc.inCat.map { it.name.substringBefore("（").trim() }
        val picked = sc.src.substringBefore("（").trim()
        CatTabs(listOf("") + names, picked) { t -> sc.pickSrc(sc.inCat.firstOrNull { it.name.substringBefore("（").trim() == t }?.name ?: "") }
    }
    if (sc.cats.isNotEmpty()) {
        val all = sc.cats.map { it.first }
        if (sc.wide) {
            CatChips(listOf("") + all, sc.cat) { sc.pickCat(it); sc.pickWide(false) }
        } else if (all.size <= CAT_TABS) {
            CatTabs(listOf("") + all, sc.cat) { sc.pickCat(it) }
        } else {
            // 类目多到一排摆不完（199mh 一次给 42 颗）：末尾那颗不给"超长的那一颗"，给「更多」
            val shown = all.take(CAT_TABS - 1)
            CatTabs(listOf("") + shown + MORE, if (sc.cat.isEmpty()) "" else if (shown.contains(sc.cat)) sc.cat else MORE) { t ->
                if (t == MORE) sc.pickWide(true) else sc.pickCat(t)
            }
        }
    }
}

private const val CAT_TABS = 8
private const val MORE = "更多"

/** 「更多」点开之后：类目全部铺开成几行（不是横向滚一条），选完自动收回去。 */
@Composable
private fun CatChips(names: List<String>, sel: String, onPick: (String) -> Unit) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)) {
        names.chunked(5).forEach { row ->
            Row(Modifier.fillMaxWidth().padding(vertical = 3.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                row.forEach { n ->
                    val label = if (n.isEmpty()) "全部" else n
                    val on = n == sel
                    Box(
                        Modifier.weight(1f).height(34.dp).clip(RoundedCornerShape(6.dp))
                            .background(if (on) Chrome.dot.copy(alpha = 0.14f) else Chrome.chip)
                            .tap { onPick(n) },
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(label, fontSize = 13.sp, color = if (on) Chrome.dot else Chrome.ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        }
    }
}

/** 一个类目此刻的样子：有几本、在不在要下一页、翻完没有、这一类翻得动吗。 */
private data class CategoryPager(
    val books: List<BookHit>,
    val loading: Boolean,
    val dry: Boolean,
    val canPage: Boolean,
)

/**
 * 列表底下那一行：正在要下一页就是三个点；翻完了 / 这一类翻不动，换成一句话说清 ——
 * 留一颗点了没反应的空白，等于告诉他"再划划看"而那里什么都没有。
 */
@Composable
private fun PagerFoot(p: CategoryPager) {
    when {
        p.loading -> LoadingDots()
        !p.canPage -> FootLine("这一类的站点只给首页那一份榜单，没有下一页可翻（源文件里没写 hot.page 那一格）。")
        p.dry -> FootLine("到这里就翻完了，一共 ${p.books.size} 本。")
        else -> Spacer(Modifier.height(8.dp))
    }
}

@Composable
private fun FootLine(text: String) {
    Text(
        text, fontSize = 12.sp, color = Chrome.faint, lineHeight = 17.sp,
        modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp),
    )
}

@Composable
private fun CategoryBooks(
    name: String,
    first: List<BookHit>,
    rules: List<Rule>,
    onBack: () -> Unit,
    onOpen: (BookHit) -> Unit,
) {
    val listState = rememberLazyListState()
    val sc = rememberCatScope(name, first, rules)
    val catUrl = sc.catUrl
    val pager = rememberCategoryPager(name, sc.pool, rules, sc.chosen, sc.catUrl) {
        val info = listState.layoutInfo
        val last = info.visibleItemsInfo.lastOrNull()?.index ?: -1
        info.totalItemsCount > 0 && last >= info.totalItemsCount - 4
    }

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
        CatScopes(sc)
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxWidth().weight(1f),
            contentPadding = PaddingValues(start = 16.dp, top = 4.dp, end = 16.dp, bottom = 16.dp),
        ) {
            items(pager.books, key = { it.sourceId + it.bookUrl }) { b ->
                Row(
                    Modifier
                        .fillMaxWidth()
                        .tap { onOpen(b) }
                        .padding(vertical = 7.dp),
                ) {
                    BookCover(b.bookName, "", Modifier.size(width = 66.dp, height = 82.dp), remote = b.coverUrl)
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
            item(key = "#底") { PagerFoot(pager) }
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
@OptIn(ExperimentalMaterial3Api::class)
private fun CategoryBrowse(
    books: List<BookHit>,
    rules: List<Rule>,
    isRefreshing: Boolean,
    onRefresh: () -> Unit,
    onBack: () -> Unit,
    onOpen: (BookHit) -> Unit,
) {
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
        // 下拉刷新挂整行：左边那栏类目、右边那格书墙都算"屏幕下拉"（那颗圈跟在滚起来的那一侧上面）
        val ptr = rememberPullToRefreshState()
        PullToRefreshBox(
            isRefreshing = isRefreshing,
            onRefresh = onRefresh,
            state = ptr,
            modifier = Modifier.fillMaxWidth().weight(1f),
            indicator = {},
        ) {
            Row(Modifier.fillMaxSize()) {
                // 左栏宽度不写死：整栏由最宽那一格撑出来（原先钉 76dp，两字的类目名只占 18.5dp，
                // 于是"字→分界线"空到 42.5dp、而"屏边→字"只有 14dp，左右一眼就不等价）。
                // 现在两边都是 14dp。每格自己包内容，不能写 fillMaxWidth —— 在 Row 里那会吃掉整行剩余宽度。
                // 整栏上移 11dp：右边那行的顶内衬同一步子从 17dp 收到 6dp，两列一起上去，
                // 上一轮量到的"两行框顶都是 y=274"这个对齐不动（他这次要的是顶部间隙小一点，不是重新错开）。
                // 用 offset 而不是负 padding —— 负 padding 会让这一栏比 Row 给的空间高出 11dp，底部那一格会被挤出去。
                Column(Modifier.fillMaxHeight().offset(y = (-11).dp).verticalScroll(rememberScrollState())) {
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
                    // 和「更多」那一屏同一格翻页：右边这格书墙滚到底也去要下一页，底部同样三个点
                    val sc = rememberCatScope(sel, groups[sel].orEmpty(), rules)
                    val gridState = rememberLazyGridState()
                    // 切类目时别停在原来那个滚动偏移上：那一格是上一类的第 30 行，进来会是一片空
                    LaunchedEffect(sel) { gridState.scrollToItem(0) }
                    val pager = rememberCategoryPager(sel, sc.pool, rules, sc.chosen, sc.catUrl) {
                        val info = gridState.layoutInfo
                        val last = info.visibleItemsInfo.lastOrNull()?.index ?: -1
                        info.totalItemsCount > 0 && last >= info.totalItemsCount - 6
                    }
                    val list = pager.books
                    // 和精选同一颗：下拉时这一栏顶上多出来的一行三个点 —— 左栏类目不动，
                    // 反馈挂在滚得动的那一侧
                    if (isRefreshing) LoadingDots()
                    // 顶内衬 17dp → 6dp，和左栏那 11dp offset 是同一步子。上一轮把这里从 12dp 抬到 17dp
                    // 是为了和左栏第一格顶部对齐；这次他只要顶部间隙小一点，所以两列一起上移、对齐不动：
                    // 量到两行框顶都从 y=274 落到 y=244（互相 Δ0），墨迹顶都从 y=279 落到 y=249。
                    // 整块抬 11dp（左栏那一列就是这么起的），里面每一格都是 48dp 一行：
                    // 筛选排、筛选排、共 N 本 —— 三行同一档节奏，行距不再各走各的（真机量过 161 对 132 那一版）
                    Column(Modifier.fillMaxWidth().offset(y = (-11).dp)) {
                        CatScopesFlat(sc)
                        Box(Modifier.fillMaxWidth().height(48.dp), contentAlignment = Alignment.CenterStart) {
                            Text("共 ${list.size} 本", fontSize = 12.sp, color = Chrome.faint, modifier = Modifier.padding(start = 14.dp))
                        }
                    }
                    // 一本都还没有又在读：这一格摆成两列方格的骨架。读的是两件事 ——
                    // 外层下拉在转，或这一类自己正在要第一页（切到一个缓存里没有的类目就是这一条，
                    // 那一秒不然这格是一片空墙）。已经有书了不许闪骨架，那看着像把列表清了
                    if (list.isEmpty() && (isRefreshing || pager.loading)) {
                        FeaturedSkeleton(rows = 2, search = false, grid = true)
                    } else {
                        LazyVerticalGrid(
                            state = gridState,
                            columns = GridCells.Fixed(2),
                            modifier = Modifier.fillMaxSize(),
                            contentPadding = PaddingValues(start = 8.dp, end = 8.dp, bottom = 12.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            items(list, key = { it.sourceId + it.bookUrl }) { b ->
                                Row(Modifier.padding(4.dp).tap { onOpen(b) }) {
                                    BookCover(b.bookName, "", Modifier.size(width = 44.dp, height = 56.dp), remote = b.coverUrl)
                                    Column(Modifier.padding(start = 8.dp)) {
                                        Text(b.bookName, fontSize = 14.sp, color = Chrome.ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                        if (b.author.isNotEmpty()) Text(b.author, fontSize = 11.sp, color = Chrome.faint, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 4.dp))
                                    }
                                }
                            }
                            // 底下那一行要横跨两列，不然它只占一个格子、三个点会画在半宽处
                            item(key = "#底", span = { GridItemSpan(maxLineSpan) }) { PagerFoot(pager) }
                        }
                    }
                }
            }
        }
    }
}
