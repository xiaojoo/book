package com.bookreader.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** 播放速度那三档：每页停多少秒。开/关不在这里，在「播放」那颗按钮上。 */
val AUTO_STEPS = listOf(12 to "慢 12s", 8 to "中 8s", 4 to "快 4s")

/**
 * pdf / pptx 的底部抽屉 —— 它就是这一类阅读页的底栏：点屏幕中间放上下两条栏时，下面那条就是这个抽屉，
 * 不再"点页码才弹一层"。照他给的那张版式：顶行左「取消」右「前往」（pptx 在「前往」左边多一颗「播放」），
 * 中间那对分段控件不做；下面是跳页输入框 + 当前页码；pptx 再多一行播放速度。
 *
 * 外壳和阅读页的弹层同一份规矩：只有顶上一对 16dp 圆角、空白处自己吃掉点击、键盘抬起来时它跟着上去。
 * 它把自己量到的高度报给外层去留正文的底部空白（写死一个数就会要么压字要么留一大片空）。
 */
@Composable
fun WholePageDrawer(
    current: Int,
    pageCount: Int,
    onJump: (Int) -> Unit,
    onDismiss: () -> Unit,
    playing: Boolean?,
    onPlayToggle: (() -> Unit)?,
    autoSeconds: Int?,
    onAutoSeconds: ((Int) -> Unit)?,
    onHeight: (Int) -> Unit,
) {
    var text by remember { mutableStateOf("") }
    val keyboard = LocalSoftwareKeyboardController.current
    val target = text.toIntOrNull()
    val ok = target != null && target in 1..pageCount
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .imePadding()
            .clip(RoundedCornerShape(topStart = SHEET_RADIUS, topEnd = SHEET_RADIUS))
            .background(Chrome.card)
            .consumesPanelTaps()
            .onSizeChanged { onHeight(it.height) }
            .padding(horizontal = 19.dp),
    ) {
        Spacer(Modifier.height(14.dp))
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(
                "取消",
                fontSize = 15.sp,
                color = Chrome.ink,
                modifier = Modifier.tap {
                    keyboard?.hide()
                    onDismiss()
                }.padding(vertical = 6.dp, horizontal = 2.dp),
            )
            Spacer(Modifier.weight(1f))
            if (playing != null && onPlayToggle != null) {
                // 「暂停」用选中态（红框红字），和阅读页那四颗翻页档同一个说法
                WholeChip(
                    if (playing) "暂停" else "播放",
                    selected = playing,
                    radius = ACTION_RADIUS,
                    modifier = Modifier.width(WHOLE_CHIP_W).height(34.dp),
                ) {
                    keyboard?.hide()
                    onPlayToggle()
                }
                Spacer(Modifier.width(9.dp))
            }
            GoButton(enabled = ok) {
                target?.let {
                    keyboard?.hide()
                    onJump(it)
                }
            }
        }
        Spacer(Modifier.height(12.dp))
        PageField(text, onValueChange = { text = it.filter(Character::isDigit).take(6) })
        Text(
            "第 ${current + 1} / $pageCount 页",
            fontSize = 12.sp,
            color = Chrome.faint,
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
        )
        if (autoSeconds != null && onAutoSeconds != null) {
            Spacer(Modifier.height(16.dp))
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("速度", fontSize = 13.sp, color = Chrome.faint, modifier = Modifier.width(38.dp))
                Spacer(Modifier.width(6.dp))
                AUTO_STEPS.forEachIndexed { i, (secs, label) ->
                    WholeChip(
                        label,
                        selected = secs == autoSeconds,
                        modifier = Modifier.weight(1f).height(34.dp),
                        onClick = { onAutoSeconds(secs) },
                    )
                    if (i < AUTO_STEPS.lastIndex) Spacer(Modifier.width(9.dp))
                }
            }
        }
        Spacer(Modifier.height(16.dp))
    }
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

/** 右上那颗实心按钮（参考图里的「创建」）：没输对页数就置灰着，不藏起来。 */
@Composable
private fun GoButton(enabled: Boolean, onClick: () -> Unit) {
    SheetAction("前往", Chrome.accent, enabled, onClick)
}

/** 跳页输入框：自绘，不用 Material 那套外框 + 浮动标签。 */
@Composable
private fun PageField(text: String, onValueChange: (String) -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(48.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(Chrome.chip)
            .padding(horizontal = 14.dp),
        contentAlignment = Alignment.CenterStart,
    ) {
        if (text.isEmpty()) {
            Text("输入要跳到的页数", fontSize = 14.sp, color = Chrome.faint)
        }
        BasicTextField(
            value = text,
            onValueChange = onValueChange,
            singleLine = true,
            textStyle = LocalTextStyle.current.copy(fontSize = 14.sp, color = Chrome.ink),
            cursorBrush = SolidColor(Chrome.accent),
            modifier = Modifier.fillMaxWidth(),
        )
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
