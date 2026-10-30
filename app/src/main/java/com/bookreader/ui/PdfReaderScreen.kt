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
    // 抽屉里再往下开的一层（书签 / 缩略图）。None = 只开抽屉本身
    var sheet by remember { mutableStateOf(WholeSheet.None) }
    // 按完一颗之后回的那句话（「第 5 页已存进书签」）：点了没反应和没做是一颗功能
    var note by remember { mutableStateOf<String?>(null) }
    val start = prefs.loadWholePage(bookId).coerceIn(0, doc.pageCount - 1)
    val listState = rememberLazyListState(start)
    var current by remember { mutableIntStateOf(start) }
    // 抽屉自己报上来的高度，正文的底部留白跟着它走（写死一个数要么压字、要么空一大片）
    var barHPx by remember { mutableIntStateOf(0) }
    val density = LocalDensity.current
    // 缩放是这一屏的双指手势状态，不落库：抽屉撤了就没有"复位"那颗按钮，存着一个 2.5 倍
    // 下次打开反而看不出为什么
    var zoom by remember { mutableFloatStateOf(1f) }
    val (night, onNight) = rememberNightToggle(prefs)
    val (rotate, onRotate) = rememberScreenRotate()
    // 页码书签：prefs 不是可观察状态，按完自己 +1 一下让这一列重算
    var marksTick by remember { mutableIntStateOf(0) }
    val marks = remember(marksTick) { prefs.pageBookmarks(bookId) }
    // 撑满状态栏、导航栏：五个阅读器各自一份（这一屏存 ReaderScope.PDF 那一格）
    var edge by remember { mutableStateOf(prefs.edgeToEdge) }
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
    // 键是**像素宽**不是倍数：转屏时屏宽变了，只盯倍数的话那一页还是拿竖屏像素放大出来的糊图
    val renderW = (widthPx * (zoom * 10f).roundToInt().coerceIn(10, 20) / 10f).roundToInt()
    LaunchedEffect(renderW) { doc.setRenderWidth(renderW) }

    fun go(index: Int) = scope.launch {
        listState.animateScrollToItem(index.coerceIn(0, doc.pageCount - 1))
    }

    // 音量键翻页：那颗开关在「我的 → 阅读偏好」里，小说/pdf/pptx/漫画四族共用同一格 ——
    // 之前只有小说那一屏认它。上下栏（这一族的底栏就是那条抽屉）或抽屉里再开的一层压着时交回系统音量
    VolumePageTurn(
        enabled = prefs.volumePaging,
        blockedBy = {
            when {
                sheet != WholeSheet.None -> "抽屉$sheet"
                chrome -> "上下栏"
                else -> null
            }
        },
        onTurn = { up -> go(if (up) current - 1 else current + 1) },
    )

    // 双指张合没有别的回显，捏合停下来打一行日志，方便对着真机核
    LaunchedEffect(zoom) {
        delay(400L)
        android.util.Log.d("WholeZoom", "pdf zoom=${(zoom * 100).roundToInt()}%")
    }

    /** 和阅读页正文同一套三区（`ReaderGestures.zoneOf`，1/3 处分界在 core 里有判据）：先收弹层，再谈翻页。 */
    fun surfaceTap(widthPx: Int, x: Float) {
        if (chrome) {
            chrome = false
            sheet = WholeSheet.None
            note = null
            return
        }
        when (ReaderGestures.zoneOf(widthPx, x)) {
            TapZone.Prev -> if (prefs.bothSidesNext) go(current + 1) else go(current - 1)
            TapZone.Next -> go(current + 1)
            TapZone.ToggleBars -> chrome = true
        }
    }

    // 返回键一层一层退：抽屉里的子面板 → 上下两条栏 → 退出这本书。
    // 后组合进来的那颗优先，所以这两颗都在顶栏那颗「返回」的下头
    BackHandler(enabled = sheet != WholeSheet.None) { sheet = WholeSheet.None }
    BackHandler(enabled = sheet == WholeSheet.None && chrome) {
        chrome = false
        note = null
    }

    // 藏系统栏要等这一屏自己的上下栏和抽屉都收干净（和小说/漫画同一句口径）
    ApplySystemBars(
        darkIcons = !Chrome.night,
        immersive = edge && !chrome && sheet == WholeSheet.None,
    )

    // 底色铺满整块窗口（系统栏背后也是它）；版面那一格再按「撑满」自己决定让不让系统栏
    Box(Modifier.fillMaxSize().background(Chrome.page)) {
        var viewport by remember { mutableStateOf(IntSize.Zero) }
        // 版面：撑满开着就顶到 y=0 / 屏幕底，不撑满就让开安全区（下面那条"第 N/M 页"才不被导航条盖住）
        Box(Modifier.fillMaxSize().fixedBarsPadding(edge)) {
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
                // 下面那条现在是抽屉，高度按它自己量上来的算。
                // **撑满那一档这两格都给 0** —— 版面顶到边才是"撑满"，两条栏浮在版面上
                contentPadding = PaddingValues(
                    top = if (edge) 0.dp else PAGE_TOP_PAD.dp,
                    bottom = if (edge) 0.dp
                    else if (barHPx > 0) with(density) { barHPx.toDp() } + 6.dp else PAGE_BOTTOM_PAD.dp,
                ),
                verticalArrangement = Arrangement.spacedBy(7.dp),
            ) {
                items(doc.pageCount, key = { it }) { i -> PdfPageView(doc, i, zoom, renderW) }
            }
        }

        // 栏和整屏覆盖那一层单独让安全区：撑满开着时版面顶到边，而上下两条栏和抽屉还是正常上下排列
        Box(Modifier.fillMaxSize().fixedBarsPadding()) {
            if (sheet == WholeSheet.Thumbs) {
                // 整屏覆盖：它有自己的顶栏和出口，不留一条"只能靠返回键出去"的路
                WholePageThumbs(
                    current = current,
                    pageCount = doc.pageCount,
                    ratio = { i -> doc.pageSizes[i].widthPx.toFloat() / doc.pageSizes[i].heightPx },
                    onClose = { sheet = WholeSheet.None },
                    onJump = { go(it) },
                ) { i, cellW -> PdfThumb(doc, i, cellW) }
            } else if (chrome) {
                Column(Modifier.fillMaxSize()) {
                    Row(
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
                    if (sheet == WholeSheet.Marks) {
                        WholePageMarksPanel(
                            pages = marks,
                            pageCount = doc.pageCount,
                            current = current,
                            note = note,
                            ratio = { i -> doc.pageSizes[i].widthPx.toFloat() / doc.pageSizes[i].heightPx },
                            onJump = { go(it) },
                            onDelete = {
                                prefs.removePageBookmark(bookId, it)
                                marksTick++
                                // 删完最后一条就不留话：那一层自己会收掉，收掉之后还顶一句提示就是噪声
                                note = if (prefs.pageBookmarks(bookId).isEmpty()) null else "第 ${it + 1} 页的书签已删掉"
                            },
                            onBack = { sheet = WholeSheet.None },
                            onEmpty = {
                                sheet = WholeSheet.None
                                chrome = false
                            },
                            onHeight = { barHPx = it },
                        ) { i, cellW -> PdfThumb(doc, i, cellW) }
                    } else {
                        WholePageDrawer(
                            current = current,
                            pageCount = doc.pageCount,
                            onJump = { go(it) },
                            markCount = marks.size,
                            onAddMark = {
                                note = if (marks.contains(current + 1)) {
                                    "第 ${current + 1} 页已经在书签里了"
                                } else {
                                    prefs.addPageBookmark(bookId, current)
                                    marksTick++
                                    "第 ${current + 1} 页已存进书签"
                                }
                            },
                            onShowMarks = { sheet = WholeSheet.Marks },
                            onThumbnails = { sheet = WholeSheet.Thumbs },
                            // pdf 这一族查不了：PdfRenderer 只把页渲成位图，一个字都不给
                            onSearch = null,
                            searchNote = "PDF 取不到文字层",
                            tocNote = "系统渲染器只给整页位图，读不到里面的章节",
                            note = note,
                            plays = null,
                            night = night,
                            onNight = onNight,
                            rotate = rotate,
                            onRotate = onRotate,
                            // 撑满：这一档和版式一样是这一屏自己的（存 ReaderScope.PDF 那一格）
                            edge = edge,
                            onEdge = { edge = it; prefs.edgeToEdge = it },
                            onHeight = { barHPx = it },
                        )
                    }
                }
            }
        }

        // 上下两条栏亮着的时候，状态栏 / 导航条那两格用实心页底盖住（撑满那一档版面铺到 y=0）。
        // 色子给的是外壳那套 `Chrome.page`（#F6F7F9），不是纸白 #FFFFFF —— 拿纸白盖上去和工具栏差 6/8/10，
        // 白天一眼能看出分界。形状、高度、时机和小说/漫画/听书共用同一份 `BarPaper`
        BarPaper(Chrome.page, if (chrome) 1f else 0f)
    }
}

/** 缩略图格里的那一张：按格子的实际像素渲，渲完交给 Image 铺满那一格。 */
@Composable
private fun PdfThumb(doc: PdfDoc, index: Int, widthPx: Int) {
    var shown by remember(index, widthPx) { mutableStateOf<android.graphics.Bitmap?>(null) }
    LaunchedEffect(index, widthPx, doc) { shown = doc.thumbnail(index, widthPx) }
    val bmp = shown
    if (bmp != null) {
        Image(
            bitmap = bmp.asImageBitmap(),
            contentDescription = "第 ${index + 1} 页",
            contentScale = androidx.compose.ui.layout.ContentScale.FillWidth,
            modifier = Modifier.fillMaxSize(),
        )
    }
}

/** 上一/下一页那两颗已经换成"点屏幕左右 1/3"，这里不再留一颗没人按的按钮。 */

@Composable
private fun PdfPageView(doc: PdfDoc, index: Int, zoom: Float, renderW: Int) {
    var shown by remember(index) { mutableStateOf<android.graphics.Bitmap?>(null) }
    val size = doc.pageSizes[index]
    val screenDp = with(LocalDensity.current) { contentSizePx().width.toDp() }
    LaunchedEffect(index, doc, renderW) { shown = doc.page(index) }
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
