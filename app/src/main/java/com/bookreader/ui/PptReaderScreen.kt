package com.bookreader.ui

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredHeight
import androidx.compose.foundation.layout.requiredWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
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
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.bookreader.R
import com.bookreader.core.engine.ReaderGestures
import com.bookreader.core.engine.TapZone
import com.bookreader.core.ingest.PptxDeck
import com.bookreader.core.ingest.PptxDoc
import com.bookreader.core.ingest.PptxPara
import com.bookreader.core.ingest.PptxPicture
import com.bookreader.core.ingest.PptxShape
import com.bookreader.core.ingest.PptxSlide
import com.bookreader.core.ingest.PptxTable
import com.bookreader.core.ingest.PptxText
import com.bookreader.data.BookLibrary
import com.bookreader.data.ReaderPrefs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.zip.ZipFile
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** 1pt = 12700 EMU：PowerPoint 的坐标和字号都活在 EMU 里，缩放全靠这个换算。 */
private const val EMU_PER_PT = 12700f

/** 段落行高 = 字号 × 这个系数。行距写在版式/母版里，我们按主流查看器的默认值给。 */
private const val LINE_SPACING = 1.22f

/** 墨色：pptx 里没写颜色的 run 走这个，不跟阅读页那六档纸色走（版面是作者定的）。 */
private val PPT_INK = Color(0xFF22262E)

/**
 * pptx：一屏一张版面。形状按 EMU 坐标整体缩放到屏宽，文字按 run 换字重/斜体/字号/颜色画 ——
 * 和 pdf 那一类一样"保留原版面、没有版式可调"，所以顶栏右侧明写「原版面」。
 *
 * 文字超出形状框时整块等比缩小字号（PowerPoint 的"缩小文字以适应"），不裁：
 * 宁可字小一点，也不能让用户看到一张缺了字的页。
 */
@Composable
fun PptReaderScreen(
    bookId: String,
    title: String,
    library: BookLibrary,
    prefs: ReaderPrefs,
    onBack: () -> Unit,
) {
    val density = LocalDensity.current
    val screen = LocalConfiguration.current
    val view = LocalView.current
    ApplySystemBars(Chrome.pageArgb, Chrome.pageArgb, darkIcons = !Chrome.night)
    BackHandler(onBack = onBack)
    SideEffect { view.keepScreenOn = prefs.keepScreenOn }

    val widthPx = (screen.screenWidthDp * density.density).roundToInt()
    var deck by remember { mutableStateOf<PptxDeck?>(null) }
    var failure by remember { mutableStateOf<String?>(null) }
    val images = remember { PptxImages() }
    LaunchedEffect(bookId) {
        val file = library.deckFile(bookId)
        if (!file.isFile) {
            failure = "找不到这本 pptx 的原件（重新导入一次试试）"
            return@LaunchedEffect
        }
        runCatching { withContext(Dispatchers.IO) { PptxDoc().load(file) } }
            .onSuccess { d ->
                if (d == null || d.slides.isEmpty()) {
                    failure = "打不开这本 pptx：一页版面都没读出来"
                } else {
                    images.open(file)
                    deck = d
                }
            }
            .onFailure { failure = "打不开这本 pptx：${it.message ?: it.javaClass.simpleName}" }
    }
    DisposableEffect(bookId) { onDispose { images.close() } }

    val d = deck
    if (d == null) {
        Box(Modifier.fillMaxSize().background(Chrome.page), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    failure ?: "正在读取 pptx…",
                    fontSize = 14.sp,
                    color = if (failure == null) Chrome.faint else Chrome.danger,
                )
                if (failure != null) {
                    TextButton(onClick = onBack, modifier = Modifier.padding(top = 6.dp)) {
                        Text("返回书架", fontSize = 14.sp, color = Chrome.accent)
                    }
                }
            }
        }
    } else {
        PptBody(d, bookId, title, prefs, images, widthPx, onBack)
    }
}

@Composable
private fun PptBody(
    deck: PptxDeck,
    bookId: String,
    title: String,
    prefs: ReaderPrefs,
    images: PptxImages,
    widthPx: Int,
    onBack: () -> Unit,
) {
    var chrome by remember { mutableStateOf(true) }
    val pageCount = deck.slides.size
    val start = prefs.loadWholePage(bookId).coerceIn(0, pageCount - 1)
    val pager = rememberPagerState(start) { pageCount }
    val scope = rememberCoroutineScope()
    // 缩放是双指手势的临时状态，不落库；播放和速度也一样，关掉这一屏就回到「没在放」
    var zoom by remember { mutableStateOf(1f) }
    var autoSeconds by remember { mutableStateOf(8) }
    var playing by remember { mutableStateOf(false) }

    LaunchedEffect(pager) {
        snapshotFlow { pager.currentPage }.collect { prefs.saveWholePage(bookId, it) }
    }

    fun go(index: Int) = scope.launch { pager.animateScrollToPage(index.coerceIn(0, pageCount - 1)) }

    // 播放 = 像 PC 那样一页一页往前放：每档秒数翻一页，走到最后一页自己停下、
    // 那颗按钮也跟着回到「播放」，不留一颗亮着却什么都不做的开关
    LaunchedEffect(playing, autoSeconds, pager.currentPage) {
        if (!playing) return@LaunchedEffect
        delay(autoSeconds * 1000L)
        if (pager.currentPage < pageCount - 1) go(pager.currentPage + 1) else playing = false
    }

    // 双指张合没有别的回显，捏合停下来打一行日志，方便对着真机核
    LaunchedEffect(zoom) {
        delay(400L)
        android.util.Log.d("WholeZoom", "ppt zoom=${(zoom * 100).roundToInt()}%")
    }

    /** 和阅读页正文同一套三区（分界数学在 core 里，有判据）。 */
    fun surfaceTap(widthPx: Int, x: Float) {
        if (chrome) {
            chrome = false
            return
        }
        when (ReaderGestures.zoneOf(widthPx, x)) {
            TapZone.Prev -> if (prefs.bothSidesNext) go(pager.currentPage + 1) else go(pager.currentPage - 1)
            TapZone.Next -> go(pager.currentPage + 1)
            TapZone.ToggleBars -> chrome = true
        }
    }

    var viewport by remember { mutableStateOf(IntSize.Zero) }
    Box(Modifier.fillMaxSize().safeDrawingPadding().background(Chrome.page)) {
        HorizontalPager(
            state = pager,
            // 放大到比屏宽之后，横向那一把gesture要给页面自己拖着看，不能再被 pager 抢去翻页
            userScrollEnabled = zoom <= 1.001f,
            pageContent = { page -> SlideView(deck, deck.slides[page], widthPx, images, zoom) },
            modifier = Modifier
                .fillMaxSize()
                .onSizeChanged { viewport = it }
                // 点击区挂在页面上而不是外层：挂外层的话，点"第 N / M 页"会连它一起收掉
                .pointerInput(Unit) { detectTapGestures { o -> surfaceTap(viewport.width, o.x) } }
                // 双指张合 = 整页放大；单指事件不消费，翻页滑动和点击照旧
                .pinchZoom { f -> zoom = (zoom * f).coerceIn(ZOOM_MIN, ZOOM_MAX) },
        )

        if (chrome) {
            Column(Modifier.fillMaxSize()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(Chrome.card)
                        .padding(start = 6.dp, end = 16.dp, top = 8.dp, bottom = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(Modifier.size(38.dp).tap(onBack), contentAlignment = Alignment.Center) {
                        Icon(painterResource(R.drawable.ic_back), "返回", tint = Chrome.ink, modifier = Modifier.size(20.dp))
                    }
                    Text(title, fontSize = 15.sp, color = Chrome.ink, maxLines = 1, modifier = Modifier.padding(start = 2.dp))
                    Spacer(Modifier.weight(1f))
                    Text("原版面", fontSize = 12.sp, color = Chrome.faint)
                }
                Spacer(Modifier.weight(1f))
                // 底栏就是这个抽屉本身：点屏幕中间把它放出来，不用再点页码弹一层
                WholePageDrawer(
                    current = pager.currentPage,
                    pageCount = pageCount,
                    onJump = { n -> go(n - 1) },
                    onDismiss = { chrome = false },
                    playing = playing,
                    onPlayToggle = { playing = !playing },
                    autoSeconds = autoSeconds,
                    onAutoSeconds = {
                        autoSeconds = it
                        playing = true
                    },
                    onHeight = { },
                )
            }
        }
    }
}

// ---- 版面：先量后画 ------------------------------------------------------------------

/** 一页上的一块，量完之后给画。三种块都从这一个密封类分出去，`when` 才不会漏分支。 */
private sealed class Plan {
    abstract val shape: PptxShape

    /** 一块文字：`lines` 是量好的每段排版结果（含"整块缩小以适应"后的结果）。 */
    class Text(override val shape: PptxText, val lines: List<LinePlan>) : Plan()

    class Picture(override val shape: PptxPicture) : Plan()

    class Table(
        override val shape: PptxTable,
        val cells: List<LinePlan>,
        val colX: List<Float>,
        val rowY: List<Float>,
    ) : Plan()
}

private class LinePlan(val layout: TextLayoutResult, val x: Float, val y: Float)

@Composable
private fun SlideView(
    deck: PptxDeck,
    slide: PptxSlide,
    widthPx: Int,
    images: PptxImages,
    zoom: Float,
) {
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current
    // 整页缩放就是把"一屏宽 = 版面宽"这条等式改成"一屏宽 × zoom"，EMU→px 的比例跟着走，
    // 形状、图片、表格、文字全在同一个比例上
    val pageW = (widthPx * zoom).roundToInt().coerceAtLeast(1)
    val scale = pageW / deck.widthEmu.toFloat()
    val heightPx = (deck.heightEmu * scale).roundToInt()
    val plans = remember(slide.index, pageW) { planSlide(slide, scale, measurer) }
    // 像素换算成 dp 必须过 density：直接把像素数当 dp 写进 size，在这台机上是 2.75 倍大，
    // 画布比屏宽出一截，整页版面就挤在左边一条里（真机上量过：表格那页挤成一列竖排字）
    val pageWidth = with(density) { pageW.toDp() }
    val pageHeight = with(density) { heightPx.toDp() }
    val hScroll = rememberScrollState()
    val vScroll = rememberScrollState()
    var viewport by remember { mutableStateOf(IntSize.Zero) }
    // 双指放大以**屏幕中心**为锚：滚动容器里那一块永远撑到"版面和视口里较大的那个"，版面比屏小的时候
    // 它居中、比屏大时它撑开，两种情况都用"滚到一半"把版面中心对到屏幕中心。
    // 之前是放大后 TopStart 贴左上角（他一捏就跑到顶上去了），而 Center 又会让超出视口的那半截滚不到。
    LaunchedEffect(zoom, viewport, pageW, heightPx) {
        if (viewport.width > 0 && viewport.height > 0) {
            hScroll.scrollTo(((pageW - viewport.width) / 2).coerceAtLeast(0))
            vScroll.scrollTo(((heightPx - viewport.height) / 2).coerceAtLeast(0))
        }
    }

    Box(
        Modifier
            .fillMaxSize()
            .onSizeChanged { viewport = it }
            .horizontalScroll(hScroll)
            .verticalScroll(vScroll),
    ) {
        Box(
            modifier = Modifier
                .requiredWidth(with(density) { max(pageW, viewport.width).toDp() })
                .requiredHeight(with(density) { max(heightPx, viewport.height).toDp() }),
            contentAlignment = Alignment.Center,
        ) {
            Canvas(Modifier.width(pageWidth).height(pageHeight).background(Color.White)) {
                for (plan in plans) {
                    when (plan) {
                        is Plan.Text -> drawTextPlan(plan)
                        is Plan.Picture -> images.get(plan.shape.entry)?.let { drawPicture(it, plan, scale) }
                        is Plan.Table -> drawTablePlan(plan, scale)
                    }
                }
            }
        }
    }
}

/**
 * 一页的所有块。文字块在这里量完（含"放不下就整块缩小"），画的时候不再量。
 * 放大整页走的是 [SlideView] 里的 `scale`（EMU→px 的比例），形状、图片、表格、文字全在同一个比例上；
 * 没有单独的"字号"旋钮 —— 定版面里单独放大文字会被形状框顶死（真机量过：选 155% 时生效值反而掉到 31%、
 * 版面几乎没动），那颗旋钮等于白按 —— 所以抽屉里没有字号，放大只走整页这一条路。
 */
private fun planSlide(slide: PptxSlide, scale: Float, measurer: TextMeasurer): List<Plan> {
    val out = slide.shapes.mapNotNull { shape ->
        val left = shape.x * scale
        val top = shape.y * scale
        val boxWidth = max(1f, shape.cx * scale)
        when (shape) {
            is PptxText -> {
                val boxHeight = shape.cy * scale
                var lines = layoutParas(shape.paras, boxWidth, left, top, scale, 1f, measurer)
                if (boxHeight > 0f) {
                    // 用的地方比框高：整块等比缩小重排一次。只缩一次就够 —— 行数和字号近似线性
                    val used = lines.lastOrNull()?.let { it.y + it.layout.size.height - top } ?: 0f
                    if (used > boxHeight) {
                        lines = layoutParas(
                            shape.paras, boxWidth, left, top, scale,
                            (boxHeight / used).coerceIn(0.2f, 1f), measurer,
                        )
                    }
                }
                Plan.Text(shape, lines)
            }
            is PptxPicture -> Plan.Picture(shape)
            is PptxTable -> {
                val cols = shape.colWidths.ifEmpty { List(shape.rows.firstOrNull()?.size ?: 1) { shape.cx } }
                val total = cols.sum().toFloat().coerceAtLeast(1f)
                val colX = ArrayList<Float>()
                colX += left
                for (c in cols) colX += colX.last() + c / total * boxWidth
                val rowH = if (shape.cy > 0) shape.cy * scale / shape.rows.size.coerceAtLeast(1) else 40f
                val rowY = ArrayList<Float>()
                rowY += top
                repeat(shape.rows.size) { rowY += rowY.last() + rowH }
                val cells = ArrayList<LinePlan>()
                shape.rows.forEachIndexed { r, row ->
                    row.forEachIndexed { c, paras ->
                        if (r < rowY.size - 1 && c < colX.size - 1) {
                            cells += layoutParas(paras, colX[c + 1] - colX[c] - 12f, colX[c] + 6f, rowY[r] + rowH * 0.18f, scale, 1f, measurer)
                        }
                    }
                }
                Plan.Table(shape, cells, colX, rowY)
            }
        }
    }
    return out
}

/** 一段一段往下排；返回每一块的排版结果和它在屏幕上的落点。 */
private fun layoutParas(
    paras: List<PptxPara>,
    boxWidth: Float,
    left: Float,
    top: Float,
    scale: Float,
    fit: Float,
    measurer: TextMeasurer,
): List<LinePlan> {
    val out = ArrayList<LinePlan>()
    var y = top
    for (para in paras) {
        val maxPt = para.runs.maxOf { it.sizePt }
        val indent = para.level * maxPt * EMU_PER_PT * scale * fit * 0.9f
        val text = buildAnnotated(para, scale, fit, para.level > 0)
        val style = TextStyle(
            fontSize = (maxPt * EMU_PER_PT * scale * fit).sp,
            lineHeight = (maxPt * EMU_PER_PT * scale * fit * LINE_SPACING).sp,
            textAlign = when (para.align) {
                "ctr" -> TextAlign.Center
                "just", "dist" -> TextAlign.Justify
                "r" -> TextAlign.Right
                else -> TextAlign.Left
            },
        )
        val width = max(1, (boxWidth - indent).roundToInt())
        // 必须带宽度约束去量：不然 layout 的宽就是文字的自然宽，"居中"没有可居中的框（真机上标题就贴左了）
        val layout = measurer.measure(text, style, constraints = Constraints(minWidth = width, maxWidth = width), maxLines = 200)
        out += LinePlan(layout, left + indent, y)
        y += layout.size.height.toFloat() + maxPt * EMU_PER_PT * scale * fit * 0.28f
    }
    return out
}

/** run 级样式：字重、斜体、字号、颜色一个一个带上，整段一个样就等于没保留格式。 */
private fun buildAnnotated(para: PptxPara, scale: Float, fit: Float, bullet: Boolean) = buildAnnotatedString {
    if (bullet) append("• ")
    for (run in para.runs) {
        withStyle(
            SpanStyle(
                fontSize = (run.sizePt * EMU_PER_PT * scale * fit).sp,
                fontWeight = if (run.bold) FontWeight.Bold else FontWeight.Normal,
                fontStyle = if (run.italic) FontStyle.Italic else FontStyle.Normal,
                color = if (run.colorArgb == 0) PPT_INK else Color(run.colorArgb),
            ),
        ) { append(run.text) }
    }
}

private fun DrawScope.drawTextPlan(plan: Plan.Text) {
    for (line in plan.lines) drawText(line.layout, topLeft = Offset(line.x, line.y))
}

private fun DrawScope.drawPicture(bitmap: ImageBitmap, plan: Plan.Picture, scale: Float) {
    val w = max(1, (plan.shape.cx * scale).roundToInt())
    val h = max(1, (plan.shape.cy * scale).roundToInt())
    drawImage(
        bitmap,
        dstSize = IntSize(w, h),
        dstOffset = IntOffset((plan.shape.x * scale).roundToInt(), (plan.shape.y * scale).roundToInt()),
    )
}

private fun DrawScope.drawTablePlan(plan: Plan.Table, scale: Float) {
    val line = Color(0xFFC9CED6)
    for (x in plan.colX) drawLine(line, Offset(x, plan.rowY.first()), Offset(x, plan.rowY.last()), 1f)
    for (y in plan.rowY) drawLine(line, Offset(plan.colX.first(), y), Offset(plan.colX.last(), y), 1f)
    for (cell in plan.cells) drawText(cell.layout, topLeft = Offset(cell.x, cell.y))
}

/** 图片按条目名缓存解码结果：翻页来回滑时不该每次都重新解 png。 */
private class PptxImages {
    private var zip: ZipFile? = null
    private val cache = object : LruCache<String, Bitmap>(48 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount
    }

    fun open(file: File) {
        runCatching { zip?.close() }
        zip = runCatching { ZipFile(file) }.getOrNull()
    }

    fun close() {
        cache.evictAll()
        runCatching { zip?.close() }
        zip = null
    }

    fun get(entry: String): ImageBitmap? {
        cache.get(entry)?.let { return it.asImageBitmap() }
        val z = zip ?: return null
        return runCatching {
            val e = z.getEntry(entry) ?: return null
            val bmp = BitmapFactory.decodeStream(z.getInputStream(e)) ?: return null
            cache.put(entry, bmp)
            bmp.asImageBitmap()
        }.getOrNull()
    }
}
