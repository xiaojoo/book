package com.bookreader.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.bookreader.R
import com.bookreader.data.DeviceFonts
import com.bookreader.data.ReaderPrefs

import kotlin.math.roundToInt

/** 顶栏那颗返回箭头（参考截图里阅读页左上就是它，不写"书架"两个字）。 */
@Composable
fun BackChevron(theme: ReaderTheme, flush: Boolean = false, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .size(width = 40.dp, height = 40.dp)
            .tap(onClick),
        // flush = 把墨迹贴到框的左沿：面板里那颗要和下面内容的左边缘对齐（框还是 40dp，命中区不缩）
        contentAlignment = if (flush) Alignment.CenterStart else Alignment.Center,
    ) {
        Icon(
            painter = painterResource(R.drawable.ic_back),
            contentDescription = "返回",
            tint = theme.inkColor,
            modifier = Modifier.size(20.dp),
        )
    }
}

enum class TurnMode { Simple, Slide, Scroll, Cover }

private val LINE_LABELS = listOf("松散", "较宽", "适中", "紧凑", "密集")
private val MARGIN_LABELS = listOf("较窄", "适中", "较宽")
private val AUTO_LABELS = listOf("慢", "中", "快")
private val TURN_LABELS = listOf("平移", "仿真", "上下", "简洁")

private fun chipBg(theme: ReaderTheme) = if (theme.night) Color(0xFF222222) else Color(0xFFEDEEF0)
private fun trackBg(theme: ReaderTheme) = if (theme.night) Color(0xFF2A2B2E) else Color(0xFFE2E5EA)

/** 底栏四颗：目录 / 书签 / 白天(切日间) / 设置。图标在上、文字在下。 */
@Composable
fun ReaderBottomBar(
    theme: ReaderTheme,
    modifier: Modifier = Modifier,
    onToc: () -> Unit,
    onMarks: () -> Unit,
    onToggleNight: () -> Unit,
    onSettings: () -> Unit,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(theme.pageColor)
            .drawBehind {
                drawLine(theme.dividerColor, Offset.Zero, Offset(size.width, 0f), 1f)
            }
            .height(58.dp),
    ) {
        BarItem("目录", R.drawable.ic_reader_toc, theme, onToc)
        BarItem("书签", R.drawable.ic_reader_marks, theme, onMarks)
        BarItem(
            if (theme.night) "白天" else "夜间",
            if (theme.night) R.drawable.ic_reader_day else R.drawable.ic_reader_night,
            theme,
            onToggleNight,
        )
        BarItem("设置", R.drawable.ic_reader_settings, theme, onSettings)
    }
}

@Composable
private fun RowScope.BarItem(label: String, iconRes: Int, theme: ReaderTheme, onClick: () -> Unit) {
    Column(
        modifier = Modifier.weight(1f).fillMaxHeight().tap(onClick),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(
            painter = painterResource(iconRes),
            contentDescription = label,
            tint = theme.faintColor,
            modifier = Modifier.size(21.dp),
        )
        Text(label, fontSize = 11.sp, color = theme.faintColor, modifier = Modifier.padding(top = 3.dp))
    }
}

/** 弹层要吃掉落在自己空白处的点击，否则那一枪会穿到正文上，关面板的同时把页也翻了。 */
@Composable
fun Modifier.consumesPanelTaps(): Modifier = pointerInput(Unit) { detectTapGestures { } }

@Composable
private fun BoxScope.PanelShell(
    theme: ReaderTheme,
    capFraction: Float = 0.92f,
    content: @Composable ColumnScope.() -> Unit,
) {
    // 面板按内容撑高（内容短就不留空档），只给一个"最多占这么高"的上限；
    // 上限之内再靠子项的 weight(fill=false) 让列表能滚
    BoxWithConstraints(
        modifier = Modifier
            .align(Alignment.BottomCenter)
            .fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = maxHeight * capFraction)
                .clip(RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp))
                .background(theme.barColor)
                .consumesPanelTaps()
                .padding(horizontal = 19.dp),
            content = content,
        )
    }
}

/**
 * 带 ‹ 返回图标的弹层表头：图标用顶栏同一只 vector（不是「‹」这个字符，字符的墨迹中心
 * 和图标对不齐），标题字号跟快捷面板「字体」那一行同为 13sp，整行按中心线对齐。
 */
@Composable
private fun PanelHeader(title: String, theme: ReaderTheme, onBack: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().height(46.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        BackChevron(theme, flush = true, onClick = onBack)
        Spacer(Modifier.weight(1f))
        Text(title, fontSize = 13.sp, fontWeight = FontWeight.Medium, color = theme.inkColor)
        Spacer(Modifier.weight(1f))
        Spacer(Modifier.width(40.dp))
    }
}

/** 快捷面板：亮度 / 字体 / 翻页 / 5 个色块 / 底行。 */
@Composable
fun BoxScope.QuickSettingsPanel(
    theme: ReaderTheme,
    brightness: Float,
    onBrightness: (Float) -> Unit,
    onFollowSystem: () -> Unit,
    fontSizeSp: Float,
    onFontStep: (Int) -> Unit,
    fontLabel: String,
    onFonts: () -> Unit,
    turnMode: TurnMode,
    onTurnMode: (TurnMode) -> Unit,
    onPickTheme: (ReaderTheme) -> Unit,
    eyeCare: Boolean,
    onEyeCare: () -> Unit,
    onSearch: () -> Unit,
    onMore: () -> Unit,
    note: String?,
) {
    PanelShell(theme) {
        Spacer(Modifier.height(20.dp))
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            RowLabel("亮度", theme)
            Spacer(Modifier.width(6.dp))
            SlideBar(
                value = if (brightness < 0f) 0.5f else brightness,
                enabled = brightness >= 0f,
                theme = theme,
                onChange = onBrightness,
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.width(12.dp))
            Text("跟随系统", fontSize = 13.sp, color = theme.faintColor)
            Spacer(Modifier.width(7.dp))
            Box(
                modifier = Modifier
                    .size(22.dp)
                    .clip(CircleShape)
                    .background(if (brightness < 0f) theme.accentColor else chipBg(theme))
                    .tap(onFollowSystem),
                contentAlignment = Alignment.Center,
            ) {
                Text("✓", fontSize = 12.sp, color = if (brightness < 0f) Color.White else theme.faintColor)
            }
        }
        Spacer(Modifier.height(26.dp))
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            RowLabel("字体", theme)
            Spacer(Modifier.width(6.dp))
            PanelChip("A-", theme, Modifier.height(34.dp)) { onFontStep(-1) }
            Text(
                fontSizeSp.roundToInt().toString(),
                fontSize = 14.sp,
                color = theme.inkColor,
                textAlign = TextAlign.Center,
                modifier = Modifier.width(34.dp),
            )
            PanelChip("A+", theme, Modifier.height(34.dp)) { onFontStep(1) }
            Spacer(Modifier.weight(1f))
            PanelChip(fontLabel, theme, Modifier.height(34.dp), onClick = onFonts)
        }
        Spacer(Modifier.height(26.dp))
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            RowLabel("翻页", theme)
            Spacer(Modifier.width(6.dp))
            TURN_LABELS.forEachIndexed { index, label ->
                val mode = when (index) {
                    0 -> TurnMode.Slide
                    1 -> TurnMode.Cover
                    2 -> TurnMode.Scroll
                    else -> TurnMode.Simple
                }
                PanelChip(
                    label,
                    theme,
                    Modifier.weight(1f).height(34.dp),
                    selected = mode == turnMode,
                    onClick = { onTurnMode(mode) },
                )
                if (index < TURN_LABELS.lastIndex) Spacer(Modifier.width(9.dp))
            }
        }
        Spacer(Modifier.height(26.dp))
        Row(Modifier.fillMaxWidth()) {
            ReaderTheme.SWATCHES.forEachIndexed { index, option ->
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .height(36.dp)
                        .clip(RoundedCornerShape(18.dp))
                        .background(option.tileColor)
                        .then(
                            if (option == theme) {
                                Modifier.border(1.dp, theme.accentColor, RoundedCornerShape(18.dp))
                            } else {
                                Modifier
                            }
                        )
                        .tap { onPickTheme(option) },
                )
                if (index < ReaderTheme.SWATCHES.lastIndex) Spacer(Modifier.width(10.dp))
            }
        }
        // 顶部缝是 20dp 空档 = 实测 55px；底行 46dp 里字是居中的，自带 41px 上偏移，
        // 所以这里只补 5dp，两条缝的墨迹间距才对平（原来吃剩余高度时这条缝有 100px+）
        Spacer(Modifier.height(5.dp))
        note?.let {
            Text(it, fontSize = 12.sp, color = theme.accentColor, modifier = Modifier.padding(bottom = 6.dp))
        }
        Row(
            modifier = Modifier.fillMaxWidth().height(46.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            FootButton("护眼模式", theme, eyeCare, onEyeCare)
            FootDivider(theme)
            FootButton("搜索", theme, false, onSearch)
            FootDivider(theme)
            FootButton("更多设置", theme, false, onMore)
        }
    }
}

/**
 * 项目里所有可点的东西都走这颗：indication 显式给 null，按下不画涟漪、不叠高亮。
 * MainActivity 里换掉的那颗 LocalIndication 管不住这些调用点（量过：按住 A+ 时芯片中心
 * 从 242 掉到 177，还是从触点扩散开的一个圆），所以一处一处断。
 */
@Composable
internal fun Modifier.tap(onClick: () -> Unit): Modifier = clickable(
    interactionSource = remember { MutableInteractionSource() },
    indication = null,
    onClick = onClick,
)

/**
 * 带"按不动"的那一档。单独开一个重载而不是给上面那个加默认参数：
 * Kotlin 的尾随 lambda 永远绑最后一个形参，加了 `enabled` 默认参之后满项目的 `.tap { }` 都会往 `enabled` 上绑。
 */
@Composable
internal fun Modifier.tap(enabled: Boolean, onClick: () -> Unit): Modifier = clickable(
    interactionSource = remember { MutableInteractionSource() },
    indication = null,
    enabled = enabled,
    onClick = onClick,
)

@Composable
private fun RowLabel(text: String, theme: ReaderTheme) {
    Text(text, fontSize = 13.sp, color = theme.faintColor, modifier = Modifier.width(38.dp))
}

@Composable
private fun SlideBar(
    value: Float,
    enabled: Boolean,
    theme: ReaderTheme,
    onChange: (Float) -> Unit,
    modifier: Modifier = Modifier,
) {
    var widthPx by remember { mutableStateOf(1f) }
    fun ratio(x: Float) = (x / widthPx).coerceIn(0.04f, 1f)
    Box(
        modifier = modifier
            // 轨高 = 滑块直径：原来轨 30dp、滑块 24dp，两头各冒出 3dp，看着就是背景比圆高
            .height(24.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(trackBg(theme))
            .onSizeChanged { widthPx = it.width.toFloat().coerceAtLeast(1f) }
            .pointerInput(Unit) {
                detectTapGestures { offset -> onChange(ratio(offset.x)) }
            }
            .pointerInput(Unit) {
                detectDragGestures { change, drag ->
                    change.consume()
                    onChange(ratio(value * widthPx + drag.x))
                }
            },
    ) {
        Box(
            modifier = Modifier
                .align(Alignment.CenterStart)
                .padding(start = (2 + value.coerceIn(0f, 1f) * 246).dp)
                .size(24.dp)
                .clip(CircleShape)
                .background(if (enabled) Color(0xFF8A8D91) else theme.faintColor.copy(alpha = 0.4f)),
        )
    }
}

@Composable
private fun PanelChip(
    text: String,
    theme: ReaderTheme,
    modifier: Modifier = Modifier,
    selected: Boolean = false,
    onClick: (() -> Unit)? = null,
) {
    val shape = RoundedCornerShape(17.dp)
    Box(
        modifier = modifier
            .clip(shape)
            .background(if (selected) Color.Transparent else chipBg(theme))
            .then(if (selected) Modifier.border(1.dp, theme.accentColor, shape) else Modifier)
            .then(if (onClick != null) Modifier.tap(onClick) else Modifier),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text,
            fontSize = 13.sp,
            maxLines = 1,
            color = if (selected) theme.accentColor else theme.inkColor,
            modifier = Modifier.padding(horizontal = 10.dp),
        )
    }
}

@Composable
private fun RowScope.FootButton(text: String, theme: ReaderTheme, on: Boolean, onClick: () -> Unit) {
    Box(Modifier.weight(1f).fillMaxHeight().tap(onClick), contentAlignment = Alignment.Center) {
        Text(text, fontSize = 13.sp, color = if (on) theme.accentColor else theme.inkColor)
    }
}

@Composable
private fun FootDivider(theme: ReaderTheme) {
    Box(Modifier.width(1.dp).height(16.dp).background(theme.faintColor.copy(alpha = 0.35f)))
}

/** 更多设置：参考截图那张「阅读设置」，每一颗都是真开关、真动作。 */
@Composable
fun BoxScope.MoreSettingsPanel(
    theme: ReaderTheme,
    linePreset: Int,
    onLinePreset: (Int) -> Unit,
    marginPreset: Int,
    onMarginPreset: (Int) -> Unit,
    indent: Boolean,
    onIndent: (Boolean) -> Unit,
    keepOn: Boolean,
    onKeepOn: (Boolean) -> Unit,
    volumePaging: Boolean,
    onVolumePaging: (Boolean) -> Unit,
    bothSidesNext: Boolean,
    onBothSidesNext: (Boolean) -> Unit,
    restMinutes: Int,
    onRestMinutes: (Int) -> Unit,
    autoRead: Boolean,
    onAutoRead: (Boolean) -> Unit,
    autoSpeedPreset: Int,
    onAutoSpeedPreset: (Int) -> Unit,
    traditional: Boolean,
    onTraditional: (Boolean) -> Unit,
    traditionalAvailable: Boolean,
    note: String?,
    onBack: () -> Unit,
) {
    PanelShell(theme) {
        PanelHeader("阅读设置", theme, onBack)
        LazyColumn(Modifier.fillMaxWidth().weight(1f, fill = false), contentPadding = PaddingValues(bottom = 12.dp)) {
            item { Text("行间距", fontSize = 13.sp, color = theme.faintColor, modifier = Modifier.padding(top = 10.dp)) }
            item {
                Row(Modifier.fillMaxWidth().padding(top = 8.dp)) {
                    LINE_LABELS.forEachIndexed { index, label ->
                        PanelChip(
                            label,
                            theme,
                            Modifier.weight(1f).height(34.dp),
                            selected = index == linePreset,
                            onClick = { onLinePreset(index) },
                        )
                        if (index < LINE_LABELS.lastIndex) Spacer(Modifier.width(8.dp))
                    }
                }
            }
            item { Text("边距", fontSize = 13.sp, color = theme.faintColor, modifier = Modifier.padding(top = 18.dp)) }
            item {
                Row(Modifier.fillMaxWidth().padding(top = 8.dp)) {
                    MARGIN_LABELS.forEachIndexed { index, label ->
                        PanelChip(
                            label,
                            theme,
                            Modifier.weight(1f).height(34.dp),
                            selected = index == marginPreset,
                            onClick = { onMarginPreset(index) },
                        )
                        if (index < MARGIN_LABELS.lastIndex) Spacer(Modifier.width(10.dp))
                    }
                }
            }
            item { SwitchRow(theme, "段首缩进", indent, onIndent) }
            item { SwitchRow(theme, "音量键翻页", volumePaging, onVolumePaging) }
            item { SwitchRow(theme, "屏幕常亮", keepOn, onKeepOn) }
            item { SwitchRow(theme, "点击两侧均翻到下一页", bothSidesNext, onBothSidesNext) }
            item {
                Row(
                    modifier = Modifier.fillMaxWidth().height(50.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("休息提醒", fontSize = 14.sp, color = theme.inkColor)
                    Spacer(Modifier.weight(1f))
                    val label = if (restMinutes == 0) "不提醒" else "$restMinutes 分钟"
                    Text(
                        "$label ›",
                        fontSize = 13.sp,
                        color = theme.faintColor,
                        modifier = Modifier
                            .tap {
                                onRestMinutes(
                                    when (restMinutes) {
                                        0 -> 15
                                        15 -> 30
                                        30 -> 60
                                        else -> 0
                                    }
                                )
                            }
                            .padding(12.dp),
                    )
                }
            }
            item { SwitchRow(theme, "自动阅读", autoRead, onAutoRead) }
            item {
                Row(Modifier.fillMaxWidth().padding(top = 2.dp)) {
                    AUTO_LABELS.forEachIndexed { index, label ->
                        PanelChip(
                            // 单位写在芯片上：一颗"速度"档在上下和按页两种模式下都是同一个口径（行/分钟）
                            "$label · ${ReaderPrefs.AUTO_LINES_PER_MIN[index]} 行/分",
                            theme,
                            Modifier.weight(1f).height(34.dp),
                            selected = index == autoSpeedPreset,
                            onClick = { onAutoSpeedPreset(index) },
                        )
                        if (index < AUTO_LABELS.lastIndex) Spacer(Modifier.width(8.dp))
                    }
                }
            }
            item {
                SwitchRow(
                    theme, "繁体转简体", traditional, onTraditional,
                    enabled = traditionalAvailable,
                    hint = if (traditionalAvailable) null else "需 Android 10+",
                )
            }
            note?.let {
                item { Text(it, fontSize = 12.sp, color = theme.accentColor, modifier = Modifier.padding(top = 8.dp)) }
            }
        }
    }
}

@Composable
private fun SwitchRow(
    theme: ReaderTheme,
    label: String,
    checked: Boolean,
    onChange: (Boolean) -> Unit,
    enabled: Boolean = true,
    hint: String? = null,
) {
    Row(
        modifier = Modifier.fillMaxWidth().height(52.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, fontSize = 14.sp, color = if (enabled) theme.inkColor else theme.faintColor)
        hint?.let {
            Text("　$it", fontSize = 12.sp, color = theme.faintColor)
        }
        Spacer(Modifier.weight(1f))
        // 平台 Switch 传 null 回调就是置灰：开关不许消失，只许灰着
        Switch(checked = checked, onCheckedChange = onChange.takeIf { enabled })
    }
}

/** 字体表：这台机 /system/fonts 里能真加载出来的才列出来，并标出认不认识中文。 */
@Composable
fun BoxScope.FontPickerPanel(
    theme: ReaderTheme,
    selectedPath: String?,
    onPick: (DeviceFonts.Font) -> Unit,
    onBack: () -> Unit,
) {
    var cjkOnly by remember { mutableStateOf(true) }
    val fonts = remember(cjkOnly) { DeviceFonts.list(cjkOnly) }
    PanelShell(theme) {
        PanelHeader("字体", theme, onBack)
        Text(
            "共 ${fonts.size} 款，来自 /system/fonts；预览行就是用该字体画的",
            fontSize = 12.sp,
            color = theme.faintColor,
            modifier = Modifier.padding(bottom = 6.dp),
        )
        LazyColumn(Modifier.fillMaxWidth().weight(1f, fill = false), contentPadding = PaddingValues(bottom = 8.dp)) {
            items(fonts, key = { it.label + "|" + (it.path ?: "") }) { font ->
                val on = font.path == selectedPath
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(58.dp)
                        .tap { onPick(font) },
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    val face = remember(font.path) { DeviceFonts.typefaceOf(font.path) ?: android.graphics.Typeface.DEFAULT }
                    Column(Modifier.weight(1f)) {
                        Text(font.label, fontSize = 14.sp, color = if (on) theme.accentColor else theme.inkColor)
                        Text(
                            "永字八法 Aa123",
                            fontSize = 16.sp,
                            color = theme.inkColor,
                            fontFamily = androidx.compose.ui.text.font.FontFamily(face),
                        )
                    }
                    if (on) Text("✓", fontSize = 15.sp, color = theme.accentColor)
                }
            }
        }
        Row(
            modifier = Modifier.fillMaxWidth().height(48.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("只列支持中文的", fontSize = 13.sp, color = theme.inkColor)
            Spacer(Modifier.weight(1f))
            Switch(checked = cjkOnly, onCheckedChange = { cjkOnly = it })
        }
    }
}

/**
 * 底部抽屉：整幅宽、只有顶部两颗 16dp 圆角，和阅读页那四处弹层同一份外壳
 * （手机端要挑东西的浮层一律走这个，居中弹窗算没做）。先铺一层透明接盘，点抽屉外任何一处就收。
 *
 * 配色由调用方给：阅读页那几屏是 `ReaderTheme`，外壳那四页是 `Chrome`，两套 token 不能在这里挑一边。
 */
@Composable
internal fun BoxScope.Sheet(
    background: Color,
    ink: Color,
    faint: Color,
    title: String?,
    onDismiss: () -> Unit,
    content: @Composable ColumnScope.() -> Unit,
) {
    Box(Modifier.fillMaxSize().tap(onDismiss))
    Column(
        modifier = Modifier
            .align(Alignment.BottomCenter)
            .fillMaxWidth()
            .clip(RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp))
            .background(background)
            // 抽屉自己吃掉落在空白处的点击：不然那一枪漏到接盘上，整层会被自己收掉
            .pointerInput(Unit) { detectTapGestures { } }
            .padding(horizontal = 14.dp),
    ) {
        if (title != null) Row(Modifier.fillMaxWidth().height(52.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(title, fontSize = 15.sp, fontWeight = FontWeight.Medium, color = ink)
            Spacer(Modifier.weight(1f))
            Box(modifier = Modifier.size(40.dp).tap(onDismiss), contentAlignment = Alignment.Center) {
                Icon(
                    painter = painterResource(R.drawable.ic_close),
                    contentDescription = "关闭",
                    tint = faint,
                    modifier = Modifier.size(16.dp),
                )
            }
        }
        // 内容按现在这五行量下来是 ~353dp，所以帽子给到 420dp：他要的是"高一点、别在里面滚"，
        // 420 装得下又不顶到状态栏。真超了（他自己抄了十几条音色）才在里面滚
        Column(
            modifier = Modifier.fillMaxWidth().heightIn(max = 420.dp).verticalScroll(rememberScrollState()),
        ) { content() }
    }
}
