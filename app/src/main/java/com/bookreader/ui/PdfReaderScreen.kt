package com.bookreader.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.bookreader.R
import com.bookreader.core.engine.ReaderGestures
import com.bookreader.core.engine.TapZone
import com.bookreader.data.BookLibrary
import com.bookreader.data.PdfDoc
import com.bookreader.data.ReaderPrefs
import kotlinx.coroutines.delay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

/** pdf 的上下留白：等于两条工具栏各自的高度，永远预留（收起工具栏也不挪页）。 */
private const val PAGE_TOP_PAD = 55f
private const val PAGE_BOTTOM_PAD = 47f

/**
 * pdf：整页渲染、保留原件版面。这一页里没有字号、行距、边距、纸色六档 ——
 * 那些都属于排版引擎，而 pdf 的版面是作者定好的，"保留原格式"和"可调版式"互斥，
 * 主流阅读器也是这么分的。
 *
 * 形态：连续滚动 + 页码 + 跳页。当前页取自列表第一个可见项，不是估出来的。
 */
@Composable
fun PdfReaderScreen(
    bookId: String,
    title: String,
    library: BookLibrary,
    prefs: ReaderPrefs,
    onBack: () -> Unit,
) {
    val density = LocalDensity.current
    val view = LocalView.current
    ApplySystemBars(darkIcons = !Chrome.night)
    BackHandler(onBack = onBack)
    SideEffect { view.keepScreenOn = prefs.keepScreenOn }

    // 页宽 = 正文宽：pdf 是定版面的东西，缩到比屏宽还小只是把字糊掉
    val widthPx = contentSizePx().width

    var doc by remember { mutableStateOf<PdfDoc?>(null) }
    var failure by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(bookId) {
        val file = library.pdfFile(bookId)
        if (!file.isFile) {
            failure = "找不到这本 pdf 的原件（重新导入一次试试）"
            return@LaunchedEffect
        }
        runCatching { withContext(Dispatchers.IO) { PdfDoc.open(file, widthPx) } }
            .onSuccess { doc = it }
            .onFailure { failure = "打不开这本 pdf：${it.message ?: it.javaClass.simpleName}" }
    }
    // 退出时关掉 renderer 和 fd。位图不主动 recycle —— 可能正被 Compose 画着，回收会崩画布
    DisposableEffect(bookId) {
        onDispose { doc?.close(); doc = null }
    }

    val d = doc
    if (d == null) {
        Box(Modifier.fillMaxSize().background(Chrome.page), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    failure ?: "正在读取 pdf…",
                    fontSize = 14.sp,
                    color = if (failure == null) Chrome.faint else Chrome.danger,
                    textAlign = TextAlign.Center,
                )
                if (failure != null) {
                    TextButton(onClick = onBack, modifier = Modifier.padding(top = 6.dp)) {
                        Text("返回书架", fontSize = 14.sp, color = Chrome.accent)
                    }
                }
            }
        }
    } else {
        PdfBody(d, bookId, title, prefs, widthPx, onBack)
    }
}

@Composable
private fun PdfBody(
    doc: PdfDoc,
    bookId: String,
    title: String,
    prefs: ReaderPrefs,
    widthPx: Int,
    onBack: () -> Unit,
) {
    var chrome by remember { mutableStateOf(true) }
    val start = prefs.loadWholePage(bookId).coerceIn(0, doc.pageCount - 1)
    val listState = rememberLazyListState(start)
    var current by remember { mutableIntStateOf(start) }
    // 抽屉自己报上来的高度，正文的底部留白跟着它走（写死一个数要么压字、要么空一大片）
    var barHPx by remember { mutableIntStateOf(0) }
    val density = LocalDensity.current
    // 缩放是这一屏的双指手势状态，不落库：抽屉撤了就没有"复位"那颗按钮，存着一个 2.5 倍
    // 下次打开反而看不出为什么
    var zoom by remember { mutableFloatStateOf(1f) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(listState) {
        snapshotFlow { listState.firstVisibleItemIndex }.collect {
            current = it
            prefs.saveWholePage(bookId, it)
        }
    }
    // 预取紧邻的两页：滚到空白格才渲，看到的就是一页白闪
    LaunchedEffect(current) {
        for (i in listOf(current + 1, current + 2, current - 1)) doc.page(i)
    }

    // 渲染宽度按 10% 一档取整再重开位图：拖滑轨每一帧都换分辨率的话，手指还没抬起来已经渲了几十轮。
    // 只渲到 2 倍为止 —— 一张 3 倍的 A4 位图是 3240x4583 = 59MB，会把按 maxMemory/4 的那格缓存
    // 挤得只装得下一页；2 倍时正文已经到 40px 上下，再往上就是摊大已有像素，不是多要细节。
    val renderBucket = (zoom * 10f).roundToInt().coerceIn(10, 20)
    LaunchedEffect(renderBucket) { doc.setRenderWidth((widthPx * renderBucket / 10f).roundToInt()) }

    fun go(index: Int) = scope.launch {
        listState.animateScrollToItem(index.coerceIn(0, doc.pageCount - 1))
    }

    // 双指张合没有别的回显，捏合停下来打一行日志，方便对着真机核
    LaunchedEffect(zoom) {
        delay(400L)
        android.util.Log.d("WholeZoom", "pdf zoom=${(zoom * 100).roundToInt()}%")
    }

    /** 和阅读页正文同一套三区（`ReaderGestures.zoneOf`，1/3 处分界在 core 里有判据）：先收弹层，再谈翻页。 */
    fun surfaceTap(widthPx: Int, x: Float) {
        if (chrome) {
            chrome = false
            return
        }
        when (ReaderGestures.zoneOf(widthPx, x)) {
            TapZone.Prev -> if (prefs.bothSidesNext) go(current + 1) else go(current - 1)
            TapZone.Next -> go(current + 1)
            TapZone.ToggleBars -> chrome = true
        }
    }

    Box(
        Modifier
            .fillMaxSize()
            // 顺序是"先铺满底色、再让开安全区"：edge-to-edge 之后系统栏是透明的，
            // 栏背后那条就是这一句铺的 Chrome.page（反过来写底色只铺到安全区里，栏背后会露窗口背景）。
            // 让开安全区本身不能省：底下那条"第 N / M 页"会被系统导航条盖住，看着就像控件凭空没了
            .background(Chrome.page)
            .safeDrawingPadding(),
    ) {
        var viewport by remember { mutableStateOf(IntSize.Zero) }
        LazyColumn(
            state = listState,
            // 点击区挂在这一列上，不挂外层：挂外层的话，点工具栏里那颗"第 N / M 页"会连它一起收掉。
            // 只挂 detectTapGestures 不影响竖向滚动 —— Compose 的点击不消费滚动。
            modifier = Modifier
                .fillMaxSize()
                .onSizeChanged { viewport = it }
                .pointerInput(Unit) {
                    detectTapGestures { o -> surfaceTap(viewport.width, o.x) }
                }
                // 双指张合 = 整页放大（不是抽屉里的滑轨）：单指事件不消费，竖向滚动照旧
                .pinchZoom { f -> zoom = (zoom * f).coerceIn(ZOOM_MIN, ZOOM_MAX) },
            // 上下两条带子永远按各自高度预留（不是画在上面盖住）：pdf 的一页是一张图，
            // 被工具栏压住就是真的少了那一截版面，不像正文那样"字还在下面滚着"。
            // 下面那条现在是抽屉，高度按它自己量上来的算
            contentPadding = PaddingValues(
                top = PAGE_TOP_PAD.dp,
                bottom = if (barHPx > 0) with(density) { barHPx.toDp() } + 6.dp else PAGE_BOTTOM_PAD.dp,
            ),
            verticalArrangement = Arrangement.spacedBy(7.dp),
        ) {
            items(doc.pageCount, key = { it }) { i -> PdfPageView(doc, i, zoom, renderBucket) }
        }

        if (chrome) {
            Column(Modifier.fillMaxSize()) {                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(Chrome.card)
                        .padding(start = 6.dp, end = 16.dp, top = 8.dp, bottom = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(Modifier.size(38.dp).tap(onBack), contentAlignment = Alignment.Center) {
                        Icon(
                            painterResource(R.drawable.ic_back),
                            "返回",
                            tint = Chrome.ink,
                            modifier = Modifier.size(20.dp),
                        )
                    }
                    Text(
                        title,
                        fontSize = 15.sp,
                        color = Chrome.ink,
                        maxLines = 1,
                        modifier = Modifier.padding(start = 2.dp),
                    )
                    Spacer(Modifier.weight(1f))
                    // 这一档没有版式可调是格式本身的性质，写在角上比藏进菜单里诚实
                    Text("原版面", fontSize = 12.sp, color = Chrome.faint)
                }
                Spacer(Modifier.weight(1f))
                // 底栏就是这个抽屉本身：点屏幕中间把它放出来，不用再点页码弹一层
                WholePageDrawer(
                    current = current,
                    pageCount = doc.pageCount,
                    onJump = { n -> go(n - 1) },
                    onDismiss = { chrome = false },
                    playing = null,
                    onPlayToggle = null,
                    autoSeconds = null,
                    onAutoSeconds = null,
                    onHeight = { barHPx = it },
                )
            }
        }
    }
}

/** 上一/下一页那两颗已经换成"点屏幕左右 1/3"，这里不再留一颗没人按的按钮。 */

@Composable
private fun PdfPageView(doc: PdfDoc, index: Int, zoom: Float, renderBucket: Int) {
    var shown by remember(index) { mutableStateOf<android.graphics.Bitmap?>(null) }
    val size = doc.pageSizes[index]
    val screenDp = with(LocalDensity.current) { contentSizePx().width.toDp() }
    LaunchedEffect(index, doc, renderBucket) { shown = doc.page(index) }
    // 缩放 >1 时这一页比屏宽，横向拖着看局部；竖向仍然走外面那个列表（两个方向不抢同一把手势）
    Box(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())) {
        Box(
            Modifier
                .width(screenDp * zoom)
                .aspectRatio(size.widthPx.toFloat() / size.heightPx.toFloat())
                // 还没渲出来的那一格也刷白：和 pdf 页面同色，滚动时不会闪出灰块
                .background(Color.White),
            contentAlignment = Alignment.Center,
        ) {
            val bmp = shown
            if (bmp != null) {
                Image(
                    bitmap = bmp.asImageBitmap(),
                    contentDescription = "第 ${index + 1} 页",
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
    }
}
