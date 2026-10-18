package com.bookreader.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Icon
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.sp
import androidx.activity.compose.BackHandler
import com.bookreader.R
import com.bookreader.core.ai.AiSpeech
import com.bookreader.core.model.Chapter
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
    ApplySystemBars(darkIcons = !theme.night, bgArgb = theme.page)
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
    ApplySystemBars(darkIcons = !theme.night, bgArgb = theme.page)
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

/**
 * 一次批量缓存的进度。界面上那一行读的就是这几个数 —— 分两类才说得清花钱没有：
 * `done` 是跑完几段，`requests` 是**真发了几次请求**（命中缓存的那一段一个请求都不发）。
 */
class VoiceBatch {
    var running by mutableStateOf(false)
    var done by mutableIntStateOf(0)
    var requests by mutableIntStateOf(0)
    var chars by mutableLongStateOf(0L)
    var error by mutableStateOf<String?>(null)

    /** 停下只置这个标志：正在跑的那一次请求会跑完（钱已经花了），后面的不再发。 */
    @Volatile
    var stop = false
}

/**
 * 「缓存范围」那四档（选项卡）：从正在听这一章之后数几章，-1 = 全部章节。
 * "要不要自动跑"不在这一排里 —— 那是旁边那颗开关的事，两件事分开才不会选了范围就自己掏钱。
 */
val CACHE_SCOPES = listOf(5 to "五章", 15 to "十五章", 50 to "五十章", -1 to "全部章节")

/** 这一档到底缓存哪几章（纯函数在 `:core` 的 `AiSpeech.chapterWindow`，那条有判据）。 */
fun cacheRange(scope: Int, current: Int, total: Int): List<Int> =
    if (scope == 0) emptyList() else AiSpeech.chapterWindow(current, total, if (scope < 0) null else scope)

/**
 * 目录：整本的章都在这一页。右边那颗「缓存」= 把这一章的每一段按**播放时同一套键**合成落盘，
 * 所以之后真听到那一段是命中缓存、不再掏一次钱。列表本身一个字节都不发 —— 合成按字符计费。
 * 右上角那三颗点是「缓存范围」的设置（不自动 / 最近五章 / 十五章 / 五十章 / 全部章节）。
 */
@Composable
fun ListenTocPage(
    theme: ReaderTheme,
    chapters: List<Chapter>,
    current: Int,
    cached: Set<Int>,
    batch: VoiceBatch,
    scope: Int,
    auto: Boolean,
    onScope: (Int) -> Unit,
    onAuto: (Boolean) -> Unit,
    onBack: () -> Unit,
    onPick: (Int) -> Unit,
    onCacheChapter: (Int) -> Unit,
    onCacheRange: (List<Int>) -> Unit,
    onBatchStop: () -> Unit,
) {
    var settings by remember { mutableStateOf(false) }
    ApplySystemBars(darkIcons = !theme.night, bgArgb = theme.page)
    BackHandler(onBack = if (settings) { { settings = false } } else onBack)
    Box(Modifier.fillMaxSize().background(theme.pageColor)) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 16.dp)
                // 吃掉落在空白处的点击：阅读页的翻页热区在它下面，不吞掉会顺手翻走一页
                .pointerInput(Unit) { detectTapGestures { } },
        ) {
            Row(Modifier.fillMaxWidth().height(48.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(modifier = Modifier.size(40.dp).tap(onBack), contentAlignment = Alignment.CenterStart) {
                    Icon(
                        painter = painterResource(R.drawable.ic_back),
                        contentDescription = "返回",
                        tint = theme.inkColor,
                        modifier = Modifier.size(20.dp),
                    )
                }
                Text("目录 ${chapters.size} 章", fontSize = 19.sp, fontWeight = FontWeight.Medium, color = theme.inkColor)
                Spacer(Modifier.weight(1f))
                Box(modifier = Modifier.size(40.dp).tap { settings = true }, contentAlignment = Alignment.CenterEnd) {
                    Icon(
                        painter = painterResource(R.drawable.ic_more),
                        contentDescription = "缓存范围",
                        tint = theme.faintColor,
                        modifier = Modifier.size(20.dp),
                    )
                }
            }
            // 跑批的时候这一行说清"跑到哪了、真发了几次请求"；停下那颗只在跑的时候点得动
            if (batch.running || batch.error != null) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        // 不给分母：一批要跑多少段得先把整章走一遍才知道，而这一行只该报量到的数
                        // （原来写的 `${batch.done}/${batch.total}` 里 total 从没被赋过值，界面上是「已存 12/0 段」）
                        batch.error
                            ?: "正在缓存：已跑 ${batch.done} 段 · 真发 ${batch.requests} 次请求 · ${formatChars(batch.chars)} 字",
                        fontSize = 12.sp,
                        color = if (batch.error != null) theme.accentColor else theme.faintColor,
                        lineHeight = 17.sp,
                        modifier = Modifier.weight(1f),
                    )
                    if (batch.running) {
                        Text(
                            "停下",
                            fontSize = 13.sp,
                            color = theme.accentColor,
                            modifier = Modifier.tap(onBatchStop).padding(vertical = 6.dp, horizontal = 6.dp),
                        )
                    }
                }
            }
            LazyColumn(modifier = Modifier.fillMaxWidth().weight(1f), contentPadding = PaddingValues(bottom = 16.dp)) {
                items(chapters, key = { it.index }) { ch ->
                    val hit = ch.index in cached
                    Row(
                        // 行不再另加左右内衬：这一列的右线就是这一页内容的右线（16dp），
                        // 和上面那三颗点的右沿同一条
                        Modifier
                            .fillMaxWidth()
                            .height(48.dp)
                            .tap { onPick(ch.index) },
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            ch.title,
                            fontSize = 14.sp,
                            color = if (ch.index == current) theme.accentColor else theme.inkColor,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f),
                        )
                        Text(formatChars(ch.charCount), fontSize = 11.sp, color = theme.faintColor)
                        // 原来这颗定宽 52dp + 右对齐：两个字（缓存）左边白让出 26dp，三个字（已缓存）只让 13dp，
                        // 每一行的间隙还不一样宽。现在让它裹内容，间隙就是这一颗固定的缝
                        Spacer(Modifier.width(6.dp))
                        // 缓存过的这一行它就是个状态，不是一颗点不动的按钮：所以不给它 tap
                        Text(
                            if (hit) "已缓存" else "缓存",
                            fontSize = 13.sp,
                            color = if (hit) theme.faintColor else theme.accentColor,
                            modifier = if (hit) Modifier else Modifier.tap { onCacheChapter(ch.index) },
                        )
                    }
                }
            }
        }
        if (settings) {
            val picked = cacheRange(scope, current, chapters.size)
            val chars = picked.sumOf { chapters[it].charCount }
            // 这一层没有标题、也没有右上角那颗 ✕（他点的）：出口还是两条 —— 点抽屉外面、系统返回，
            // 而这一页自己的 BackHandler 会先收这一层再收目录
            Sheet(theme.barColor, theme.inkColor, theme.faintColor, title = null, onDismiss = { settings = false }) {
                // 名字在左、选项在右（和听书页「更多」里那排音色同一形状）。
                // 没有标题行了，顶内衬自己给：14dp（= 那层的横向内衬）他还嫌贴，再放一档到 20dp
                Row(
                    Modifier.fillMaxWidth().padding(top = 20.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("缓存范围", fontSize = 15.sp, color = theme.inkColor)
                    Spacer(Modifier.width(8.dp))
                    Row(
                        Modifier.weight(1f).horizontalScroll(rememberScrollState()),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        CACHE_SCOPES.forEachIndexed { i, (v, label) ->
                            WholeChip(
                                label,
                                modifier = Modifier.height(32.dp),
                                selected = v == scope,
                                radius = 10.dp,
                            ) { onScope(v) }
                            if (i < CACHE_SCOPES.lastIndex) Spacer(Modifier.width(8.dp))
                        }
                    }
                }
                TocSwitchRow(theme, "自动缓存", checked = auto, enabled = true, onChange = onAuto)
                // 「立即缓存」也做成胶囊：它照的是"这一批在不在跑"，跑完自己回到关。
                // 开着的时候按下去就是停下（正在发的那一次会跑完 —— 钱已经花了，后面的不再发）
                TocSwitchRow(
                    theme, "立即缓存这些章",
                    checked = batch.running,
                    enabled = scope != 0 && picked.isNotEmpty(),
                    onChange = { v -> if (v) onCacheRange(picked) else onBatchStop() },
                )
                // 说明统一挪到最下面：两行开关中间夹一段字，读起来像是上一颗的注脚
                Text(
                    (if (scope == 0) "先点上面一颗选范围。"
                     else "这一档要缓存 ${picked.size} 章 / 约 ${formatChars(chars)} 字 —— 合成按字符计费。") +
                        "自动缓存：开着，换到新的一章就自己往后发；关着，要拨「立即缓存这些章」。",
                    fontSize = 12.sp,
                    color = theme.faintColor,
                    lineHeight = 17.sp,
                    modifier = Modifier.fillMaxWidth().padding(top = 2.dp, bottom = 10.dp),
                )
            }
        }
    }
}

/** 抽屉里的一行开关：左边名字、右边胶囊，52dp 高（和「更多」那层的开关行同一档节奏）。 */
@Composable
private fun TocSwitchRow(
    theme: ReaderTheme,
    label: String,
    checked: Boolean,
    enabled: Boolean,
    onChange: (Boolean) -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().height(52.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, fontSize = 15.sp, color = theme.inkColor, modifier = Modifier.weight(1f))
        Switch(
            checked = checked,
            onCheckedChange = onChange,
            enabled = enabled,
            // 点不动的时候由组件自己置灰（这一版 Material3 没有 disabledTrackColor 这两个键）
            colors = SwitchDefaults.colors(
                checkedTrackColor = theme.accentColor,
                checkedThumbColor = theme.barColor,
                uncheckedTrackColor = theme.pageColor,
                uncheckedBorderColor = theme.dividerColor,
                uncheckedThumbColor = theme.faintColor,
            ),
        )
    }
}
