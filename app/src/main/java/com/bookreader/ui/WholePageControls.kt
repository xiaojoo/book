package com.bookreader.ui

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.ActivityInfo
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.bookreader.R
import com.bookreader.core.ingest.PptxHit
import com.bookreader.data.ReaderPrefs
import kotlin.math.roundToInt

/** 播放速度那三档：每页停多少秒。开关不在这里，在「自动播放」那一行的开关上。 */
val AUTO_STEPS = listOf(12 to "慢 12s", 8 to "中 8s", 4 to "快 4s")

/** 抽屉底下那一层现在开的是哪个面板（None = 只开抽屉本身）。 */
enum class WholeSheet { None, Marks, Search, Thumbs }

/** pptx 才有的那三行放映。pdf 没有"放"这件事，整块不摆。 */
class WholePagePlays(
    val playing: Boolean,
    val fromCurrent: () -> Unit,
    val fromFirst: () -> Unit,
    val onToggle: () -> Unit,
    val autoSeconds: Int,
    val onAutoSeconds: (Int) -> Unit,
)

/**
 * pdf / pptx 的底部抽屉 —— 它就是这一类阅读页的底栏：点屏幕中间放上下两条栏时，下面那条就是这个抽屉。
 * 照他给的那张参考图重排：分组带把功能切成五段（进度 / 查找 / 版面工具 / 显示 / 放映），
 * 每段一条灰带子隔开，和参考图同一种节奏。
 *
 * 摆不下的那几颗（pdf 的「查找」、两族的「目录」）**置灰着并写明为什么**，不藏起来：
 * 藏了别人以为这一族本来就没有这个功能。
 *
 * 外壳和阅读页的弹层同一份规矩：只有顶上一对圆角、空白处自己吃掉点击、键盘抬起来时它跟着上去。
 * 它把自己量到的高度报给外层去留正文的底部空白（写死一个数就会要么压字要么留一大片空）。
 * 内容比上限高时它自己内部滚（横过来一定超）。
 */
@Composable
fun WholePageDrawer(
    current: Int,
    pageCount: Int,
    onJump: (Int) -> Unit,
    markCount: Int,
    onAddMark: () -> Unit,
    onShowMarks: () -> Unit,
    onThumbnails: () -> Unit,
    onSearch: (() -> Unit)?,
    searchNote: String?,
    tocNote: String,
    note: String?,
    plays: WholePagePlays?,
    night: Boolean,
    onNight: (Boolean) -> Unit,
    rotate: Boolean,
    onRotate: (Boolean) -> Unit,
    /**
     * 「撑满状态栏、导航栏」。五个阅读器各一档（各存各的 `ReaderScope` 那一份）——
     * pdf / pptx 没有版式可调，这一格是它们唯一"版面往外扩"的开关。
     */
    edge: Boolean,
    onEdge: (Boolean) -> Unit,
    onHeight: (Int) -> Unit,
) {
    // 拖滑块只是"候选"，按「跳转」才真的过去 —— 参考图那颗按钮就是干这个的
    var typed by remember { mutableStateOf("") }
    var slid by remember(current) { mutableIntStateOf(current) }
    val typedPage = typed.toIntOrNull()?.minus(1)
    val target = if (typed.isEmpty()) slid else typedPage
    val armed = target != null && target != current && target in 0 until pageCount

    SheetShell(onHeight = onHeight, scroll = true) {
        Spacer(Modifier.height(14.dp))
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 19.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("阅读进度", fontSize = 13.sp, color = Chrome.faint)
            Spacer(Modifier.weight(1f))
            Text("第 ${current + 1} / $pageCount 页", fontSize = 12.sp, color = Chrome.faint)
        }
        Spacer(Modifier.height(9.dp))
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 19.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .weight(1f)
                    .height(52.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .border(1.dp, Chrome.hairline, RoundedCornerShape(10.dp))
                    .padding(horizontal = 10.dp),
                contentAlignment = Alignment.Center,
            ) {
                WholeSlider(
                    frac = if (pageCount <= 1) 0f else slid.toFloat() / (pageCount - 1),
                    onFrac = { f ->
                        typed = ""
                        slid = (f * (pageCount - 1)).roundToInt().coerceIn(0, pageCount - 1)
                    },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            Spacer(Modifier.width(8.dp))
            NumberField(typed) { typed = it }
            Spacer(Modifier.width(8.dp))
            JumpBox(armed) {
                target?.let {
                    typed = ""
                    onJump(it)
                }
            }
        }
        // 一行状态位：要跳的那一页 > 刚做完的事 > 没说事儿时的提示。同一件事不说第二遍，所以不给它两行
        val (status, statusColor) = when {
            armed -> "跳到第 ${target + 1} 页" to Chrome.accent
            note != null -> note to Chrome.accent
            else -> "拖滑块或直接输页码，按「跳转」过去" to Chrome.faint
        }
        Text(
            status,
            fontSize = 11.sp,
            color = statusColor,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 19.dp, vertical = 5.dp),
        )

        SheetBand()
        SheetRow(R.drawable.ic_search, "查找文档内容", onSearch != null, searchNote) { onSearch?.invoke() }
        SheetBand()
        Row(Modifier.fillMaxWidth().padding(top = 6.dp, bottom = 6.dp)) {
            SheetGridItem(R.drawable.ic_layout_grid, "缩略图", true) { onThumbnails() }
            SheetGridItem(R.drawable.ic_mark_add, "插入书签", true) { onAddMark() }
            SheetGridItem(R.drawable.ic_mark_list, "所有书签", markCount > 0) { onShowMarks() }
            // 目录这一颗两族都是灰的：灰着摆出来 + 下面一行写为什么，比"这一行根本没有目录"诚实
            SheetGridItem(R.drawable.ic_reader_toc, "目录", false) {}
        }
        // 那一排里灰着的那几颗，理由写在它们脚底下：只看界面的人不会知道"没有"和"这一族本来就没有"的差别
        val gridNote = if (markCount == 0) {
            "「所有书签」灰着是因为还没有书签；目录：$tocNote"
        } else {
            "目录：$tocNote"
        }
        Text(
            gridNote,
            fontSize = 11.sp,
            color = Chrome.faint,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 19.dp).padding(bottom = 6.dp),
        )
        SheetBand()
        SheetSwitch(
            R.drawable.ic_reader_night,
            "夜间模式",
            "只换外壳和上下两条栏；原版面那一页本身不跟着变黑",
            night,
            onNight,
        )
        SheetHairline()
        SheetSwitch(R.drawable.ic_rotate, "旋转屏幕", "横过来看这一页", rotate, onRotate)
        SheetHairline()
        SheetSwitch(
            R.drawable.ic_reader_fullscreen,
            "撑满状态栏、导航栏",
            "版面铺到屏幕边，上下两条栏浮在版面上",
            edge,
            onEdge,
        )

        if (plays != null) {
            SheetBand()
            SheetRow(R.drawable.ic_play_page, "从当前页") { plays.fromCurrent() }
            SheetHairline()
            SheetRow(R.drawable.ic_play_first, "从首页") { plays.fromFirst() }
            SheetBand()
            SheetSwitch(
                R.drawable.ic_play_circle,
                "自动播放",
                if (plays.playing) "正在每 ${plays.autoSeconds} 秒翻一页，点一下停" else "点一下就从当前这一页开始放",
                plays.playing,
                { plays.onToggle() },
            )
            Row(
                Modifier.fillMaxWidth().padding(start = 19.dp, end = 19.dp, top = 2.dp, bottom = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("速度", fontSize = 12.sp, color = Chrome.faint, modifier = Modifier.width(34.dp))
                Spacer(Modifier.width(6.dp))
                AUTO_STEPS.forEachIndexed { i, (secs, label) ->
                    WholeChip(
                        label,
                        selected = secs == plays.autoSeconds,
                        modifier = Modifier.weight(1f).height(32.dp),
                        onClick = { plays.onAutoSeconds(secs) },
                    )
                    if (i < AUTO_STEPS.lastIndex) Spacer(Modifier.width(9.dp))
                }
            }
        }
        Spacer(Modifier.height(14.dp))
    }
}

/**
 * 抽屉 / 书签 / 查找三块面板共用的外壳：顶上一对圆角、卡片底、吃掉落在空白处的点击、
 * 键盘抬起来跟着上去，并把量到的高度报给外层。
 *
 * `scroll` 给 false 时这一列自己**不滚** —— 里面要放 LazyColumn 的那些（查找结果）必须走这条，
 * 竖向滚动容器套竖向滚动容器会直接崩。
 */
@Composable
private fun SheetShell(
    onHeight: (Int) -> Unit,
    scroll: Boolean = true,
    content: @Composable ColumnScope.() -> Unit,
) {
    // 上限按窗口实际高度的 46% 取：写死 dp 的话横过来（这台机竖屏 873dp、横屏 393dp）就顶出屏了
    val capDp = with(LocalDensity.current) { (contentSizePx().height * 0.46f).toDp() }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .imePadding()
            .heightIn(max = capDp)
            .clip(RoundedCornerShape(topStart = SHEET_RADIUS, topEnd = SHEET_RADIUS))
            .background(Chrome.card)
            .consumesPanelTaps()
            .onSizeChanged { onHeight(it.height) }
            .then(if (scroll) Modifier.verticalScroll(rememberScrollState()) else Modifier),
        content = content,
    )
}

/** 分组带：参考图里那一条。抽屉是卡片白，带子用页面底色，和顶栏/底栏之间那条缝同源。 */
@Composable
private fun SheetBand() {
    Box(Modifier.fillMaxWidth().height(9.dp).background(Chrome.page))
}

/** 组内那一根发丝线：1dp 在这台机取整成 3 行，要的是 1 个物理像素。 */
@Composable
private fun SheetHairline() {
    Box(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 19.dp)
            .height(with(LocalDensity.current) { 1.toDp() })
            .background(Chrome.hairline),
    )
}

/** 图标 + 一行字那种整行条目。灰的时候整行按不动，理由跟在字后面写出来。 */
@Composable
private fun SheetRow(iconRes: Int, label: String, enabled: Boolean = true, note: String? = null, onClick: () -> Unit) {
    val ink = if (enabled) Chrome.ink else Chrome.faint.copy(alpha = 0.5f)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .tap(enabled, onClick)
            .padding(horizontal = 19.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(painterResource(iconRes), null, tint = ink, modifier = Modifier.size(21.dp))
        Text(label, fontSize = 14.sp, color = ink, modifier = Modifier.padding(start = 12.dp))
        if (note != null) {
            Text(note, fontSize = 11.sp, color = Chrome.faint, modifier = Modifier.padding(start = 8.dp))
        }
    }
}

/** 上面一行图标、下面一行字那种（缩略图 / 插入书签 / 所有书签 那一排）。 */
@Composable
private fun RowScope.SheetGridItem(iconRes: Int, label: String, enabled: Boolean, onClick: () -> Unit) {
    val ink = if (enabled) Chrome.ink else Chrome.faint.copy(alpha = 0.5f)
    Column(
        modifier = Modifier.weight(1f).heightIn(min = 62.dp).tap(enabled, onClick),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(painterResource(iconRes), null, tint = ink, modifier = Modifier.size(22.dp))
        Text(label, fontSize = 12.sp, color = ink, modifier = Modifier.padding(top = 4.dp))
    }
}

/** 图标 + 标题 + 一行说明 + 右边那颗开关：整行按下去也算按开关。 */
@Composable
private fun SheetSwitch(
    iconRes: Int,
    label: String,
    sub: String,
    on: Boolean,
    onToggle: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 54.dp)
            .tap { onToggle(!on) }
            .padding(horizontal = 19.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(painterResource(iconRes), null, tint = Chrome.ink, modifier = Modifier.size(21.dp))
        Column(Modifier.weight(1f).padding(start = 12.dp)) {
            Text(label, fontSize = 14.sp, color = Chrome.ink)
            Text(sub, fontSize = 11.sp, color = Chrome.faint, modifier = Modifier.padding(top = 2.dp))
        }
        RedSwitch(on, onToggle)
    }
}

/** 页码那个小输入框：自绘，不用 Material 那套外框 + 浮动标签。 */
@Composable
private fun NumberField(value: String, onChange: (String) -> Unit) {
    Box(
        modifier = Modifier
            .width(66.dp)
            .height(52.dp)
            .clip(RoundedCornerShape(10.dp))
            .border(1.dp, Chrome.hairline, RoundedCornerShape(10.dp))
            .padding(horizontal = 8.dp),
        contentAlignment = Alignment.Center,
    ) {
        if (value.isEmpty()) {
            Text("页码", fontSize = 13.sp, color = Chrome.faint)
        }
        BasicTextField(
            value = value,
            onValueChange = { onChange(it.filter(Character::isDigit).take(6)) },
            singleLine = true,
            textStyle = LocalTextStyle.current.copy(fontSize = 14.sp, color = Chrome.ink, textAlign = TextAlign.Center),
            cursorBrush = SolidColor(Chrome.accent),
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

/** 「跳转」那颗：没得跳的时候照样摆着，只是描边和字退回灰档（置灰不消失）。 */
@Composable
private fun JumpBox(enabled: Boolean, onClick: () -> Unit) {
    val shape = RoundedCornerShape(10.dp)
    Box(
        modifier = Modifier
            .width(60.dp)
            .height(52.dp)
            .clip(shape)
            .background(if (enabled) Chrome.accent.copy(alpha = 0.10f) else Color.Transparent)
            .border(1.dp, if (enabled) Chrome.accent else Chrome.hairline, shape)
            .tap(enabled, onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text("跳转", fontSize = 14.sp, color = if (enabled) Chrome.accent else Chrome.faint)
    }
}

/**
 * 自绘进度滑块：Material 那颗滑块带一圈按压光晕和自己的内衬，和这一屏"原版面、没有 Material 味"
 * 对不上，所以描一条轨道 + 一个实心圆点。
 *
 * 只吃横向那把手势（按下即消费），外层 pdf 列表的竖向滚动和翻页点击都不受影响。
 */
@Composable
fun WholeSlider(frac: Float, onFrac: (Float) -> Unit, modifier: Modifier = Modifier) {
    Canvas(
        modifier
            .pointerInput(Unit) {
                val pad = 8.dp.toPx()
                val usable = (size.width - pad * 2).coerceAtLeast(1f)
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    down.consume()
                    onFrac(((down.position.x - pad) / usable).coerceIn(0f, 1f))
                    while (true) {
                        val e = awaitPointerEvent()
                        val c = e.changes.firstOrNull { it.pressed } ?: break
                        c.consume()
                        onFrac(((c.position.x - pad) / usable).coerceIn(0f, 1f))
                    }
                }
            },
    ) {
        val r = 7.dp.toPx()
        val cy = size.height / 2f
        val usable = (size.width - r * 2f).coerceAtLeast(1f)
        val x = r + usable * frac.coerceIn(0f, 1f)
        val h = 3.dp.toPx()
        val corner = CornerRadius(h / 2f)
        drawRoundRect(Chrome.hairline, Offset(r, cy - h / 2f), Size(usable, h), corner)
        drawRoundRect(Chrome.accent, Offset(r, cy - h / 2f), Size((x - r).coerceAtLeast(0f), h), corner)
        drawCircle(Chrome.accent, r, Offset(x, cy))
    }
}

/** 面板顶行那三块共用的表头：‹ 返回 + 标题 + 右边那颗（条数或关闭）。 */
@Composable
private fun SheetHeader(title: String, trailing: String?, onBack: () -> Unit, onClose: (() -> Unit)? = null) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .padding(start = 6.dp, end = 12.dp, top = 8.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(38.dp).tap(onBack), contentAlignment = Alignment.Center) {
            Icon(painterResource(R.drawable.ic_back), "返回", tint = Chrome.ink, modifier = Modifier.size(20.dp))
        }
        Text(title, fontSize = 15.sp, fontWeight = FontWeight.Medium, color = Chrome.ink, modifier = Modifier.padding(start = 2.dp))
        Spacer(Modifier.weight(1f))
        if (trailing != null) Text(trailing, fontSize = 12.sp, color = Chrome.faint)
        if (onClose != null) {
            Box(Modifier.size(38.dp).tap(onClose), contentAlignment = Alignment.Center) {
                Icon(painterResource(R.drawable.ic_close), "关闭", tint = Chrome.faint, modifier = Modifier.size(20.dp))
            }
        }
    }
}

/**
 * 「所有书签」这一层：和「缩略图」同一份格子（白底 + 描边 + 右下角页码角标，正在看那一格描边换成强调色），
 * 只是每格右上角多一颗删除。一格书签就是一页，把那一页画出来比印「第 N 页」三个字认得快。
 *
 * 书签一般就几条，所以不套 LazyGrid —— 外层那列自己滚（滚动套滚动会崩）。
 */
@Composable
fun WholePageMarksPanel(
    pages: List<Int>,
    pageCount: Int,
    current: Int,
    note: String?,
    ratio: (Int) -> Float,
    onJump: (Int) -> Unit,
    onDelete: (Int) -> Unit,
    onBack: () -> Unit,
    onEmpty: () -> Unit,
    onHeight: (Int) -> Unit,
    thumb: @Composable (Int, Int) -> Unit,
) {
    // 删到一条不剩就把这一层连着底栏一起收掉，回到那一页本身，不留一句「都删完了」：
    // 空位子摆在那儿就是答案。‹ 那颗仍然是回抽屉，两个出口不是一件事
    LaunchedEffect(pages.isEmpty()) {
        if (pages.isEmpty()) onEmpty()
    }
    SheetShell(onHeight = onHeight) {
        SheetHeader("所有书签", "${pages.size} 条", onBack)
        if (note != null) {
            Text(
                note,
                fontSize = 11.sp,
                color = Chrome.accent,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 19.dp).padding(bottom = 6.dp),
            )
        }
        pages.chunked(2).forEach { pair ->
            Row(
                Modifier.fillMaxWidth().padding(start = 19.dp, end = 19.dp, bottom = 12.dp),
                verticalAlignment = Alignment.Top,
            ) {
                pair.forEach { page ->
                    // 原件变短过（换文件、书签是从别处带来的）：越界的页号夹回最后一页，不让它去碰数组外面
                    val index = (page - 1).coerceIn(0, (pageCount - 1).coerceAtLeast(0))
                    PageCell(
                        page = index,
                        selected = index == current,
                        ratio = ratio(index),
                        modifier = Modifier.weight(1f),
                        onClick = {
                            onJump(index)
                            onBack()
                        },
                        overlay = {
                            Box(
                                modifier = Modifier
                                    .align(Alignment.TopEnd)
                                    .padding(4.dp)
                                    .size(30.dp)
                                    .clip(CircleShape)
                                    .background(Color(0xB3000000))
                                    .tap { onDelete(index) },
                                contentAlignment = Alignment.Center,
                            ) {
                                Icon(
                                    painterResource(R.drawable.ic_close),
                                    "删掉第 $page 页",
                                    tint = Color.White,
                                    modifier = Modifier.size(15.dp),
                                )
                            }
                        },
                        thumb = thumb,
                    )
                    if (pair.size == 2) Spacer(Modifier.width(12.dp))
                }
                // 落单的那一行右边补一格同样宽的空白：不然左边那格会被 weight 拉成整行宽
                if (pair.size == 1) Spacer(Modifier.weight(1f))
            }
        }
        Spacer(Modifier.height(2.dp))
    }
}

/**
 * 一格页面预览：白底 + 描边 + 右下角页码角标。
 * 「缩略图」那一屏和「所有书签」那一层共用这一份，两处不会长得不一样。
 */
@Composable
private fun PageCell(
    page: Int,
    selected: Boolean,
    ratio: Float,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
    overlay: (@Composable BoxScope.() -> Unit)? = null,
    thumb: @Composable (Int, Int) -> Unit,
) {
    var cellW by remember { mutableIntStateOf(0) }
    val shape = RoundedCornerShape(6.dp)
    Box(
        modifier = modifier
            .aspectRatio(ratio.coerceIn(0.2f, 5f))
            .onSizeChanged { cellW = it.width }
            .clip(shape)
            .background(Color.White)
            .border(if (selected) 2.dp else 1.dp, if (selected) Chrome.accent else Chrome.hairline, shape)
            .tap(onClick),
    ) {
        if (cellW > 0) thumb(page, cellW)
        Box(
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .background(if (selected) Chrome.accent else Color(0x99000000))
                .padding(horizontal = 8.dp, vertical = 2.dp),
        ) {
            Text("${page + 1}", fontSize = 12.sp, color = Color.White)
        }
        overlay?.invoke(this)
    }
}

/**
 * 「查找文档内容」这一层（pptx 才有）：顶上是输入框，底下一串命中。
 *
 * 结果列表自己滚（外层那列不滚）—— 竖向滚动容器套竖向滚动容器会直接崩，所以这一层走 `scroll = false`。
 */
@Composable
fun WholePageSearchPanel(
    query: String,
    onQuery: (String) -> Unit,
    hits: List<PptxHit>,
    onJump: (Int) -> Unit,
    onBack: () -> Unit,
    onHeight: (Int) -> Unit,
) {
    SheetShell(onHeight = onHeight, scroll = false) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 48.dp)
                .padding(start = 6.dp, end = 19.dp, top = 8.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.size(38.dp).tap(onBack), contentAlignment = Alignment.Center) {
                Icon(painterResource(R.drawable.ic_back), "返回", tint = Chrome.ink, modifier = Modifier.size(20.dp))
            }
            Box(
                modifier = Modifier
                    .weight(1f)
                    .height(40.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(Chrome.chip)
                    .padding(horizontal = 12.dp),
                contentAlignment = Alignment.CenterStart,
            ) {
                if (query.isEmpty()) {
                    Text("输入要查找的内容", fontSize = 14.sp, color = Chrome.faint)
                }
                BasicTextField(
                    value = query,
                    onValueChange = onQuery,
                    singleLine = true,
                    textStyle = LocalTextStyle.current.copy(fontSize = 14.sp, color = Chrome.ink),
                    cursorBrush = SolidColor(Chrome.accent),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            if (query.isNotEmpty()) {
                Box(Modifier.size(38.dp).tap { onQuery("") }, contentAlignment = Alignment.Center) {
                    Icon(painterResource(R.drawable.ic_close), "清空", tint = Chrome.faint, modifier = Modifier.size(17.dp))
                }
            }
        }
        val q = query.trim()
        if (q.isEmpty()) {
            Text(
                "至少输入一个字才开始查",
                fontSize = 12.sp,
                color = Chrome.faint,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 19.dp, vertical = 12.dp),
            )
        } else if (hits.isEmpty()) {
            Text(
                "没有找到「$q」",
                fontSize = 12.sp,
                color = Chrome.faint,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 19.dp, vertical = 12.dp),
            )
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxWidth().weight(1f, fill = false),
                contentPadding = PaddingValues(bottom = 12.dp),
            ) {
                items(hits) { hit ->
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .tap {
                                onJump(hit.page)
                                onBack()
                            }
                            .padding(horizontal = 19.dp, vertical = 9.dp),
                    ) {
                        Text("第 ${hit.page + 1} 页", fontSize = 12.sp, color = Chrome.accent)
                        Text(
                            highlight(hit, q),
                            fontSize = 13.sp,
                            color = Chrome.ink,
                            lineHeight = 19.sp,
                            maxLines = 2,
                            modifier = Modifier.padding(top = 2.dp),
                        )
                    }
                    SheetHairline()
                }
            }
        }
    }
}

/** 命中那几个字涂成强调色：只说"第 N 页有"不说在哪一行，翻过去还得自己找。 */
@Composable
private fun highlight(hit: PptxHit, query: String): AnnotatedString {
    val accent = Chrome.accent
    return buildAnnotatedString {
        val from = hit.at.coerceIn(0, hit.line.length)
        val end = (from + query.length).coerceIn(from, hit.line.length)
        append(hit.line.substring(0, from))
        withStyle(SpanStyle(color = accent, fontWeight = FontWeight.Medium)) {
            append(hit.line.substring(from, end))
        }
        append(hit.line.substring(end))
    }
}

/**
 * 「缩略图」这一层：整屏，两列，一格一张版面 + 右下角页码角标。
 * 正在看的那一格描边和角标都换成强调色（参考图那格是橙色，我们这一族的强调色是红的）。
 */
@Composable
fun WholePageThumbs(
    current: Int,
    pageCount: Int,
    ratio: (Int) -> Float,
    onClose: () -> Unit,
    onJump: (Int) -> Unit,
    thumb: @Composable (Int, Int) -> Unit,
) {
    Column(Modifier.fillMaxSize().background(Chrome.page)) {
        SheetHeader("缩略图", null, onClose, onClose)
        LazyVerticalGrid(
            columns = GridCells.Fixed(2),
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            items(pageCount) { i ->
                PageCell(
                    page = i,
                    selected = i == current,
                    ratio = ratio(i),
                    modifier = Modifier.fillMaxWidth(),
                    onClick = {
                        onJump(i)
                        onClose()
                    },
                    thumb = thumb,
                )
            }
        }
    }
}

/**
 * 「旋转屏幕」那颗开关的落地。
 *
 * 状态存在**屏**里、不存在抽屉里：抽屉是浮层，收起来的时候整个不在组合中，那会儿转屏的话
 * 重建之后开关会回到「关」而屏幕还横着 —— 开关就撒谎了。
 * 配套：MainActivity 声明了 `configChanges`，转屏不重建 Activity，页码和缩放都原地留着。
 */
@Composable
fun rememberScreenRotate(): Pair<Boolean, (Boolean) -> Unit> {
    var on by rememberSaveable { mutableStateOf(false) }
    val activity = LocalContext.current.findActivity()
    SideEffect {
        // 关的那一档回到 UNSPECIFIED、不是 PORTRAIT：这一屏在开关没碰过之前本来就该跟着系统转，
        // 一上来就钉死竖屏是这颗开关反过来改了它的默认行为
        activity?.requestedOrientation = if (on) {
            ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        } else {
            ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        }
    }
    DisposableEffect(activity) {
        onDispose { activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED }
    }
    return on to { on = it }
}

/**
 * 「夜间模式」那颗开关的落地。**读的是传进来那一份设置**（pdf / pptx 各自一份，见 `ReaderPrefs.forReader`），
 * 所以在这两屏里点夜间不会把小说、漫画的纸色一起改掉 —— 它只改这一屏那一格 `theme.pdf` / `theme.ppt`。
 *
 * pdf / pptx 整屏用的是外壳那套色板（`Chrome.*`），所以这一屏的明暗要**当场映进 `Chrome.night`**，
 * 不然按下去只有状态栏的图标换了色、正文一动不动。退出这一屏时 `MainActivity` 按 `shellNight` 还回去。
 *
 * 开夜之前那一档纸色要记住：关掉夜间该还回他原来选的那档，不是一律拍回"纸白"。
 */
@Composable
fun rememberNightToggle(prefs: ReaderPrefs): Pair<Boolean, (Boolean) -> Unit> {
    var theme by rememberSaveable { mutableIntStateOf(prefs.themeIndex) }
    var dayBefore by rememberSaveable {
        mutableIntStateOf(if (ReaderTheme.of(theme).night) ReaderTheme.Paper.ordinal else theme)
    }
    val night = ReaderTheme.of(theme).night
    LaunchedEffect(night) { Chrome.night = night }
    return night to { on ->
        if (on) {
            dayBefore = theme
            theme = ReaderTheme.Night.ordinal
        } else {
            theme = dayBefore
        }
        prefs.themeIndex = theme
        Chrome.night = on
    }
}

/** Compose 给的 LocalContext 不一定是 Activity（中间可能套 ContextWrapper），一层层往外找。 */
private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

/** 弹层里那颗实心动作按钮的公共形状：「前往」和「删除」共用，圆角不一致他一眼看得出来。 */
val ACTION_RADIUS = 8.dp

/** 实心动作按钮（参考图里那颗「创建」）：不满足条件时置灰着，不藏起来。 */
@Composable
fun SheetAction(text: String, color: Color, enabled: Boolean = true, onClick: () -> Unit) {
    val shape = RoundedCornerShape(ACTION_RADIUS)
    Box(
        modifier = Modifier
            .width(WHOLE_CHIP_W)
            .height(34.dp)
            .clip(shape)
            .background(if (enabled) color else color.copy(alpha = 0.35f))
            .tap(enabled, onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, fontSize = 14.sp, color = Color.White.copy(alpha = if (enabled) 1f else 0.6f))
    }
}

/** 底栏按钮的宽度：左边垫一条同样宽的空白，页码才还在屏幕正中（原来它是两颗等宽按钮夹着的）。 */
val WHOLE_CHIP_W = 72.dp

/** 双指张合的上下限：1 倍 = 版面正好铺满屏宽。 */
const val ZOOM_MIN = 1f
const val ZOOM_MAX = 3f

/**
 * 双指张合缩放。
 *
 * 只有真的两根手指按下来之后才消费事件：单指的拖动和点击必须原样留给外层的列表滚动、
 * 翻页器滑动和"点屏幕左右 1/3 翻页"，否则这颗检测器会把它们全吃掉（那是最容易装成功的写法）。
 */
@Composable
fun Modifier.pinchZoom(onScale: (Float) -> Unit): Modifier = pointerInput(Unit) {
    awaitEachGesture {
        awaitFirstDown(requireUnconsumed = false)
        var prev = 0f
        while (true) {
            // 这版 Compose 只有 PointerEventPass、没有 PointerEventWaitMode，
            // 所以 awaitPointerEvent() 本来就是每个事件都返回，捏合的中间帧拿得到
            val e = awaitPointerEvent()
            val down = e.changes.filter { it.pressed }
            if (down.size >= 2) {
                val d = (down[0].position - down[1].position).getDistance()
                if (prev > 0f && d > 0f) onScale(d / prev)
                prev = d
                e.changes.forEach { it.consume() }
            } else if (down.isEmpty()) {
                break
            } else {
                prev = 0f
            }
        }
    }
}

/** 选中 = 红框红字（和阅读页翻页那四颗同一条规则）。圆角默认 17dp，顶行那颗播放要跟「前往」同角。 */
@Composable
fun WholeChip(
    text: String,
    modifier: Modifier = Modifier,
    selected: Boolean = false,
    enabled: Boolean = true,
    radius: androidx.compose.ui.unit.Dp = 17.dp,
    onClick: (() -> Unit)? = null,
) {
    val shape = RoundedCornerShape(radius)
    // 必须是 Box 套 Text：裸 Text 给了固定高度时文字贴着上边排，和同一行里的页码对不上中心线
    // （真机量过：中心 2199.5 vs 2205.5，差 6px）
    Box(
        modifier = modifier
            .clip(shape)
            .background(
                when {
                    selected -> Color.Transparent
                    enabled -> Chrome.chip
                    else -> Chrome.chip.copy(alpha = 0.4f)
                },
            )
            .then(if (selected) Modifier.border(1.dp, Chrome.accent, shape) else Modifier)
            .then(if (onClick != null) Modifier.tap(enabled, onClick) else Modifier)
            .padding(horizontal = 12.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text,
            fontSize = 13.sp,
            color = when {
                selected -> Chrome.accent
                enabled -> Chrome.ink
                else -> Chrome.faint.copy(alpha = 0.5f)
            },
            textAlign = TextAlign.Center,
        )
    }
}
