package com.bookreader.ui

import android.content.Context
import android.graphics.BitmapFactory
import android.graphics.Typeface
import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.bookreader.R
import com.bookreader.data.ReaderPrefs
import java.io.File

/**
 * 「我的」页那张卡开的三屏：**皮肤管理 / 字体管理 / 菜单管理**。
 * 外壳照「AI 翻译」「AI 听书」那一族（`‹ 标题` 一整屏、底栏让位），但**没有「保存」**：
 * 这几页每一下都当场落盘并立刻生效，摆一颗保存反而让人怀疑按了没有。
 */

/** 「我的」页那张卡开的三屏是哪一屏（挂在壳的最外层，和 AI 设置同一族）。 */
enum class Customize { Skin, Font, Menu }

/** `filesDir/theme/`：字体和背景图各拷一份进来。SAF 那条 uri 的读权限会被系统收回，原文也在别的 app 私有目录里。 */
fun themeDir(ctx: Context): File = File(ctx.filesDir, "theme").apply { mkdirs() }

/** 存的是文件名不是绝对路径：装包换路径时那串旧路径会指到一个不存在的地方。 */
fun themeFile(ctx: Context, name: String): File? =
    name.takeIf { it.isNotBlank() }?.let { File(themeDir(ctx), it) }?.takeIf { it.isFile }

private fun importToTheme(ctx: Context, uri: Uri, into: String): Result<File> = runCatching {
    val out = File(themeDir(ctx), into)
    ctx.contentResolver.openInputStream(uri)?.use { input -> out.outputStream().use { input.copyTo(it) } }
        ?: error("读不到那个文件")
    out
}

private fun suffixOf(ctx: Context, uri: Uri): String {
    val name = runCatching {
        ctx.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        }
    }.getOrNull().orEmpty()
    val s = name.substringAfterLast('.', "").lowercase()
    return if (s in setOf("png", "webp", "gif", "bmp")) s else "jpg"
}

/** 按目标像素宽缩着解一张图：手机拍的一张图 4000x3000，整张进内存就是 48MB，预览和正文都要不起。
 *  皮肤管理那一屏的预览和阅读正文用的是同一份解码，两边看到的就是同一张图。 */
@Composable
internal fun scaledImage(file: File?, widthPx: Int): ImageBitmap? {
    val path = file?.absolutePath
    val bmp by produceState<ImageBitmap?>(null, path, widthPx) {
        value = if (path == null || widthPx <= 0) null else kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            runCatching {
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeFile(path, bounds)
                var sample = 1
                while (bounds.outWidth / sample > widthPx * 2) sample *= 2
                BitmapFactory.decodeFile(path, BitmapFactory.Options().apply { inSampleSize = sample })?.asImageBitmap()
            }.getOrNull()
        }
    }
    return bmp
}

@Composable
private fun CustomizePage(title: String, onBack: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    BackHandler(onBack = onBack)
    Box(Modifier.fillMaxSize().background(Chrome.page).safeDrawingPadding()) {
        Column(Modifier.fillMaxSize()) {
            Row(
                Modifier.fillMaxWidth().height(48.dp).padding(start = 8.dp, end = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(Modifier.size(40.dp).tap(onBack), contentAlignment = Alignment.Center) {
                    Icon(painterResource(R.drawable.ic_back), "返回", tint = Chrome.ink, modifier = Modifier.size(20.dp))
                }
                Text(title, fontSize = 19.sp, fontWeight = FontWeight.Medium, color = Chrome.ink)
            }
            Column(
                Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 12.dp),
            ) { content() }
        }
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(text, fontSize = 13.sp, color = Chrome.faint, modifier = Modifier.padding(start = 4.dp, top = 18.dp, bottom = 8.dp))
}

@Composable
private fun NoteLine(text: String, color: Color = Chrome.faint) {
    Text(text, fontSize = 12.sp, color = color, modifier = Modifier.fillMaxWidth().padding(top = 8.dp, start = 4.dp, end = 4.dp))
}

@Composable
private fun SwitchRow(title: String, sub: String, on: Boolean, onToggle: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().height(64.dp).tap { onToggle(!on) }.padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, fontSize = 15.sp, color = Chrome.ink)
            Text(sub, fontSize = 12.sp, color = Chrome.faint, modifier = Modifier.padding(top = 2.dp))
        }
        RedSwitch(on = on, onToggle = onToggle)
    }
}

// ---------------------------------------------------------------- 皮肤管理

/**
 * 皮肤管理：上传一张图当**小说正文那一屏**的阅读背景，图上压一层当前那档纸色（浓淡可调）。
 *
 * 只铺小说那一屏是他点的口径：pdf/pptx 要保留原件版面、漫画整屏都是图，铺底只会把东西糊掉。
 * 压纸色那一层是必须的 —— 一张花图直接压在文字底下就是看不清，而"看不清"要用户自己去猜原因。
 */
@Composable
fun SkinScreen(prefs: ReaderPrefs, onBack: () -> Unit) {
    val ctx = LocalContext.current
    var name by remember { mutableStateOf(prefs.bgImageFile) }
    var dim by remember { mutableStateOf(prefs.bgDim) }
    var note by remember { mutableStateOf<String?>(null) }
    val theme = ReaderTheme.of(prefs.themeIndex)
    val file = themeFile(ctx, name)
    val density = LocalDensity.current
    val previewPx = remember(density) { with(density) { 320.dp.toPx() }.toInt() }
    val bmp = scaledImage(file, previewPx)
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        importToTheme(ctx, uri, "bg.${suffixOf(ctx, uri)}").onSuccess { f ->
            themeDir(ctx).listFiles()?.filter { it.name.startsWith("bg.") && it != f }?.forEach { it.delete() }
            name = f.name
            prefs.bgImageFile = f.name
            note = "已换上"
        }.onFailure { note = "没能读那张图：${it.message}" }
    }

    CustomizePage("皮肤管理", onBack) {
        Spacer(Modifier.height(10.dp))
        Box(
            Modifier.fillMaxWidth().height(180.dp).clip(RoundedCornerShape(12.dp)).background(theme.pageColor),
        ) {
            if (bmp != null) {
                Box(Modifier.fillMaxSize()) {
                    Image(
                        bitmap = bmp,
                        contentDescription = "背景图预览",
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize(),
                    )
                    // 和正文同一层：同一档纸色、同一个浓淡，这一格看到什么翻开书就是什么
                    Box(Modifier.fillMaxSize().background(theme.pageColor.copy(alpha = dim)))
                    Text(
                        "海上生明月，天涯共此时。",
                        fontSize = 16.sp,
                        color = theme.inkColor,
                        modifier = Modifier.align(Alignment.Center).padding(horizontal = 12.dp),
                    )
                }
            } else {
                Text(
                    "还没选背景图：现在正文是纯色纸（${theme.label}）",
                    fontSize = 13.sp,
                    color = theme.faintColor,
                    modifier = Modifier.align(Alignment.Center),
                )
            }
        }
        if (file != null) {
            SectionLabel("压淡多少")
            Column(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(Chrome.card)
                    .padding(horizontal = 14.dp, vertical = 12.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("原图", fontSize = 13.sp, color = Chrome.faint)
                    Spacer(Modifier.weight(1f))
                    Text("${(dim * 100).toInt()}% 纸色", fontSize = 13.sp, color = Chrome.ink)
                }
                SlideBar(
                    value = dim,
                    enabled = true,
                    theme = theme,
                    onChange = { v -> dim = v; prefs.bgDim = v },
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                )
                NoteLine("0% = 图原样铺（字会看不清），100% = 完全压成纸色。这一层用的就是当前那档「${theme.label}」，换纸色这里跟着换。")
            }
        }
        SectionLabel("背景图")
        Card {
            Line("选一张图", sub = "只铺在小说正文那一屏", trailing = if (name.isBlank()) "没选" else name, on = { picker.launch(arrayOf("image/*")) })
            if (file != null) {
                Hairline()
                Line("不用背景图", color = Chrome.danger, on = {
                    themeDir(ctx).listFiles()?.filter { it.name.startsWith("bg.") }?.forEach { it.delete() }
                    prefs.bgImageFile = ""
                    name = ""
                    note = "已回到纯色纸"
                })
            }
        }
        note?.let { NoteLine(it, Chrome.accent) }
        Spacer(Modifier.height(8.dp))
        NoteLine("图会拷一份进本机（不留原文件的地址）：图库里删掉它，阅读背景也不受影响。")
        Spacer(Modifier.height(28.dp))
    }
}

// ---------------------------------------------------------------- 字体管理

/**
 * 全局字体：把 `theme/` 下那支 ttf/otf 解成 [FontFamily]。没选文件、或解不开 = null，
 * 调用方（MaterialTheme 的 typography）就用系统字体。**不是 composable**：换文件之后要按一个
 * tick 重算，而 SharedPreferences 不是可观察状态，读它拿不到重组。
 */
fun loadAppFont(ctx: Context, name: String): FontFamily? =
    themeFile(ctx, name)?.let { f ->
        runCatching { Typeface.Builder(f.absolutePath).build() }.getOrNull()?.let { FontFamily(it) }
    }

private val SCALE_PRESETS = listOf(0.85f to "小", 1f to "标准", 1.15f to "大", 1.3f to "特大")

/**
 * 字体管理：全局字体文件 + 全局字号。
 *
 * **字号只管外壳**（他点的"各算一档"）：那一档走 `AppScaffold` 那层的 density 倍率，
 * 阅读屏挂在那一层之外，所以翻开书那一页的字号仍然只在阅读页的设置面板里调。
 */
@Composable
fun FontScreen(prefs: ReaderPrefs, onChanged: () -> Unit, onBack: () -> Unit) {
    val ctx = LocalContext.current
    var fontName by remember { mutableStateOf(prefs.uiFontFile) }
    var scale by remember { mutableStateOf(prefs.uiFontScale) }
    var note by remember { mutableStateOf<String?>(null) }
    val family = remember(fontName) { loadAppFont(ctx, fontName) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        val ext = suffixOf(ctx, uri).let { if (it in setOf("ttf", "otf", "ttc")) it else "ttf" }
        importToTheme(ctx, uri, "font.$ext").onSuccess { f ->
            // 只有解得开成字体的才留下：选错一个 pdf 进来，界面会静默回到系统字体，人不知道发生了什么
            val ok = runCatching { Typeface.Builder(f.absolutePath).build() }.getOrNull() != null
            if (!ok) {
                f.delete()
                note = "那个文件不是一支字体，没换"
            } else {
                themeDir(ctx).listFiles()?.filter { it.name.startsWith("font.") && it != f }?.forEach { it.delete() }
                fontName = f.name
                prefs.uiFontFile = f.name
                Chrome.fontFile = f.name
                onChanged()
                note = "已换上"
            }
        }.onFailure { note = "没能读那个文件：${it.message}" }
    }

    CustomizePage("字体管理", onBack) {
        Spacer(Modifier.height(10.dp))
        SectionLabel("全局字号")
        Card {
            Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 12.dp)) {
                SCALE_PRESETS.forEach { (k, label) ->
                    WholeChip(
                        label,
                        selected = scale == k,
                        radius = 10.dp,
                        modifier = Modifier.weight(1f).height(32.dp),
                    ) { scale = k; prefs.uiFontScale = k; Chrome.uiScale = k; onChanged() }
                    if (label != SCALE_PRESETS.last().second) Spacer(Modifier.size(8.dp))
                }
            }
        }
        NoteLine("跟着变的是外壳那些字（书架 / 搜书 / 精选 / 书签 / 我的）。翻开书那一页的正文不变 —— 正文的字号仍在阅读页的设置面板里调，两档各算各的。")
        SectionLabel("全局字体")
        Card {
            Line("选字体文件", sub = "ttf / otf，拷一份进本机", trailing = if (fontName.isBlank()) "系统字体" else fontName,
                on = { picker.launch(arrayOf("*/*")) })
            if (fontName.isNotBlank()) {
                Hairline()
                Line("恢复系统字体", color = Chrome.danger, on = {
                    themeDir(ctx).listFiles()?.filter { it.name.startsWith("font.") }?.forEach { it.delete() }
                    prefs.uiFontFile = ""
                    fontName = ""
                    Chrome.fontFile = ""
                    onChanged()
                    note = "已恢复系统字体"
                })
            }
        }
        note?.let { NoteLine(it, Chrome.accent) }
        SectionLabel("预览")
        Column(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(Chrome.card)
                .padding(horizontal = 14.dp, vertical = 12.dp),
        ) {
            Text("海上生明月，天涯共此时", fontSize = 17.sp, color = Chrome.ink, fontFamily = family ?: FontFamily.Default)
            Text("第 3 章 · 0123456789 · ABCdef", fontSize = 13.sp, color = Chrome.faint,
                modifier = Modifier.padding(top = 6.dp), fontFamily = family ?: FontFamily.Default)
        }
        Spacer(Modifier.height(8.dp))
        NoteLine("字体文件解不开就不会换（选错文件会说一句「那个文件不是一支字体」），不会出现界面一半新字一半旧字。")
        Spacer(Modifier.height(28.dp))
    }
}

// ---------------------------------------------------------------- 菜单管理

/** 底栏藏掉哪几格。只存名字，读的时候认不回的就丢掉（改了枚举名不会崩）。 */
internal fun hiddenTabsOf(prefs: ReaderPrefs): Set<MainTab> =
    prefs.hiddenTabs.split(';').mapNotNull { s -> MainTab.entries.firstOrNull { it.name == s.trim() } }.toSet()

/**
 * 菜单管理：底栏那五格里，**搜书**和**精选**可以藏掉（这两格的内容都是从书源装回来的，
 * 不用的人一屏看着碍眼）。书架 / 书签 / 我的 不给藏 —— 藏了就没有回来的路。
 */
@Composable
fun MenuScreen(prefs: ReaderPrefs, onHiddenChanged: (Set<MainTab>) -> Unit, onBack: () -> Unit) {
    var hidden by remember { mutableStateOf(hiddenTabsOf(prefs)) }
    fun set(tab: MainTab, show: Boolean) {
        hidden = if (show) hidden - tab else hidden + tab
        prefs.hiddenTabs = hidden.joinToString(";") { it.name }
        onHiddenChanged(hidden)
    }
    CustomizePage("菜单管理", onBack) {
        Spacer(Modifier.height(10.dp))
        Card {
            SwitchRow("显示「搜书」", "装书源 / 任务 / 管理 / 先拉开头都在这格", MainTab.Search !in hidden) { set(MainTab.Search, it) }
            Hairline()
            SwitchRow("显示「精选」", "热门书单 / 分类页 / 类目卡", MainTab.Featured !in hidden) { set(MainTab.Featured, it) }
        }
        NoteLine("藏掉「搜书」= 装新书源、看下载任务、管理已装的源都没有入口了；要用的时候回这一格打开。")
        NoteLine("藏掉「精选」= 分类页和每张卡右上那颗「更多」进不去。")
        NoteLine("书架 / 书签 / 我的 这三格不给藏：藏了就没有回到这一页的路。")
        Spacer(Modifier.height(28.dp))
    }
}
