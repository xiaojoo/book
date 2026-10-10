package com.bookreader.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.sp
import androidx.activity.compose.BackHandler
import com.bookreader.R
import com.bookreader.data.AiVoiceCache
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 听书页「更多」里那两页。都做成整屏（和 AI 服务那页同一套外壳）：抽屉里再叠一层弹层，
 * "点哪算哪"就说不清了。
 */

/** 缓存历史：这一本听过的段都在本机，点一条直接播，不再发合成请求。 */
@Composable
fun ListenHistoryPage(
    theme: ReaderTheme,
    entries: List<AiVoiceCache.Entry>,
    preview: (Long, Int) -> String,
    totalBytes: Long,
    onBack: () -> Unit,
    onReplay: (AiVoiceCache.Entry) -> Unit,
) {
    ApplySystemBars(theme.pageColor.toArgb(), theme.pageColor.toArgb(), !theme.night)
    BackHandler(onBack = onBack)
    val stamp = remember { SimpleDateFormat("MM-dd HH:mm", Locale.CHINA) }
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(theme.pageColor)
            .padding(horizontal = 16.dp)
            // 这一屏要吃掉落在空白处的点击：阅读页的翻页热区在它下面，不吞掉会顺手翻走一页
            .pointerInput(Unit) { detectTapGestures { } },
    ) {
        Row(Modifier.fillMaxWidth().height(48.dp), verticalAlignment = Alignment.CenterVertically) {
            // 墨迹贴到框的左沿：这颗要和下面内容的左边缘同一条线（框还是 40dp，命中区不缩）
            Box(modifier = Modifier.size(40.dp).tap(onBack), contentAlignment = Alignment.CenterStart) {
                Icon(
                    painter = painterResource(R.drawable.ic_back),
                    contentDescription = "返回",
                    tint = theme.inkColor,
                    modifier = Modifier.size(20.dp),
                )
            }
            Text("缓存历史 ${entries.size} 段", fontSize = 19.sp, fontWeight = FontWeight.Medium, color = theme.inkColor)
            Spacer(Modifier.weight(1f))
            Text(charsOf(totalBytes), fontSize = 13.sp, color = theme.faintColor)
        }
        Text(
            "听过的段存在本机，点一条直接播，不再请求接口。",
            fontSize = 12.sp,
            color = theme.faintColor,
            lineHeight = 17.sp,
            modifier = Modifier.fillMaxWidth().padding(bottom = 10.dp),
        )
        if (entries.isEmpty()) {
            Text("这一本还没有听过任何一段。", fontSize = 14.sp, color = theme.faintColor)
        } else {
            LazyColumn(modifier = Modifier.fillMaxWidth()) {
                items(entries, key = { it.name }) { e ->
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(bottom = 8.dp)
                            .clip(RoundedCornerShape(10.dp))
                            .background(theme.barColor)
                            .tap { onReplay(e) }
                            .padding(horizontal = 14.dp, vertical = 10.dp),
                    ) {
                        Text(
                            preview(e.offset, e.chars.coerceAtMost(34)),
                            fontSize = 15.sp,
                            color = theme.inkColor,
                            lineHeight = 22.sp,
                            maxLines = 2,
                        )
                        Text(
                            "${e.chars} 字 · ${charsOf(e.bytes)} · ${stamp.format(Date(e.at))}",
                            fontSize = 11.sp,
                            color = theme.faintColor,
                            modifier = Modifier.padding(top = 4.dp),
                        )
                    }
                }
            }
        }
    }
}

/**
 * 听书纠错：多音字白名单，每行一条 `银行=银航`。
 *
 * 为什么只能写成"换成另一个字"：这三家语音接口都只吃纯文本，没有 SSML、没有音素口，
 * 拿不到"给这个字指定读音"的入口。所以这里改的是**发给接口的那串字**，屏幕上读的正文一个字都不动。
 */
@Composable
fun ListenFixesPage(
    theme: ReaderTheme,
    table: String,
    hits: Int,
    onBack: () -> Unit,
    onSave: (String) -> Unit,
) {
    ApplySystemBars(theme.pageColor.toArgb(), theme.pageColor.toArgb(), !theme.night)
    BackHandler(onBack = onBack)
    var text by remember { mutableStateOf(table) }
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(theme.pageColor)
            .padding(horizontal = 16.dp)
            // 这一屏要吃掉落在空白处的点击：阅读页的翻页热区在它下面，不吞掉会顺手翻走一页
            .pointerInput(Unit) { detectTapGestures { } },
    ) {
        Row(Modifier.fillMaxWidth().height(48.dp), verticalAlignment = Alignment.CenterVertically) {
            // 墨迹贴到框的左沿：这颗要和下面内容的左边缘同一条线（框还是 40dp，命中区不缩）
            Box(modifier = Modifier.size(40.dp).tap(onBack), contentAlignment = Alignment.CenterStart) {
                Icon(
                    painter = painterResource(R.drawable.ic_back),
                    contentDescription = "返回",
                    tint = theme.inkColor,
                    modifier = Modifier.size(20.dp),
                )
            }
            Text("听书纠错", fontSize = 19.sp, fontWeight = FontWeight.Medium, color = theme.inkColor)
            Spacer(Modifier.weight(1f))
            Text(
                "保存",
                fontSize = 17.sp,
                fontWeight = FontWeight.Medium,
                color = theme.inkColor,
                modifier = Modifier.tap { onSave(text); onBack() }.padding(vertical = 8.dp, horizontal = 6.dp),
            )
        }
        Text(
            "每行一条 左边=右边：念到左边时，发给接口的是右边那串。屏幕上的正文不变。",
            fontSize = 12.sp,
            color = theme.faintColor,
            lineHeight = 17.sp,
            modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
        )
        Text(
            "例：银行=银航　重庆=虫庆（右边挑只会读这个音的字）。",
            fontSize = 12.sp,
            color = theme.faintColor,
            lineHeight = 17.sp,
            modifier = Modifier.fillMaxWidth().padding(bottom = 10.dp),
        )
        BasicTextField(
            value = text,
            onValueChange = { text = it },
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .clip(RoundedCornerShape(10.dp))
                .background(theme.barColor)
                .padding(14.dp),
            textStyle = TextStyle(color = theme.inkColor, fontSize = 15.sp, lineHeight = 26.sp),
            cursorBrush = SolidColor(theme.accentColor),
        )
        Text(
            if (hits > 0) "已存 $hits 段；改了表，那些段重听会按新读法合成一次。"
            else "改完点右上角「保存」。",
            fontSize = 11.sp,
            color = theme.faintColor,
            lineHeight = 16.sp,
            modifier = Modifier.fillMaxWidth().padding(top = 10.dp, bottom = 24.dp),
        )
    }
}

/** 字节数说成人话：这一页只到 MB 这一档，别写 1,267,244 这种要数位的数。 */
private fun charsOf(bytes: Long): String = when {
    bytes >= 1_000_000L -> "%.1fMB".format(bytes / 1_000_000.0)
    bytes >= 1_000L -> "%dKB".format(bytes / 1000)
    else -> "$bytes B"
}
