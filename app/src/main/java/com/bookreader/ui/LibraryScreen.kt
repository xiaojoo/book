package com.bookreader.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.bookreader.R
import com.bookreader.core.model.BookMeta
import com.bookreader.data.BookLibrary
import com.bookreader.data.ReaderPrefs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 那颗 ⋯ 的图标宽。顶栏和每一行**共用这一个数**（他点过：两处都要同一档），
 * 所以调它一次就把两颗一起带走，不会出现顶栏小、行里大。
 * 命中框仍是 44dp —— 要小的是看得见的三个点，不是按得中的地方。
 */
private val MORE_ICON = 18.dp

/**
 * 书架。网格尺寸是量出来的（参考截图 1080 宽）：左右 64px=23dp、封面 232x318=85x116dp、列间距 128px=46dp。
 * Fixed(3) + 46dp 间距 + 23dp 内边距正好把每格算成 85dp，所以这里写的是间距而不是格子宽。
 */
@Composable
fun LibraryScreen(
    library: BookLibrary,
    prefs: ReaderPrefs,
    onOpen: (BookMeta) -> Unit,
    notice: String? = null,
) {
    var books by remember { mutableStateOf(library.list().shelfOrder(prefs)) }
    var busy by remember { mutableStateOf<Pair<String, Float>?>(null) }
    var note by remember(notice) { mutableStateOf(notice) }
    // 三个点和长按开的是同一层（四个动作）；「删除书籍」那一步再单独弹一次框
    var actionsFor by remember { mutableStateOf<BookMeta?>(null) }
    var renameFor by remember { mutableStateOf<BookMeta?>(null) }
    var confirmDeleteFor by remember { mutableStateOf<BookMeta?>(null) }
    var menu by remember { mutableStateOf(false) }
    var shelfList by remember { mutableStateOf(prefs.shelfList) }
    val scope = rememberCoroutineScope()
    // 置顶会把那一行插到当前视口的上面，不滚回顶就是"点下去没反应"（真机撞到的）
    val listState = androidx.compose.foundation.lazy.rememberLazyListState()
    val gridState = androidx.compose.foundation.lazy.grid.rememberLazyGridState()

    fun reload() { books = library.list().shelfOrder(prefs) }

    fun startImport(uri: android.net.Uri) {
        busy = "正在导入" to 0f
        scope.launch {
            val outcome = runCatching {
                withContext(Dispatchers.IO) {
                    library.import(uri) { progress -> scope.launch { busy = "正在导入" to progress.toFloat() } }
                }
            }
            outcome.onSuccess { meta ->
                reload()
                note = if (BookMeta.WHOLE_PAGE.contains(meta.kind)) {
                    "已入库：${meta.title}，${meta.pages} 页"
                } else {
                    "已入库：${meta.title}，${meta.chapterCount} 章 / ${formatChars(meta.totalChars)}"
                }
            }.onFailure { error ->
                note = "导入失败：${error.message ?: error.javaClass.simpleName}"
            }
            busy = null
        }
    }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) startImport(uri)
    }
    // octet-stream 也在列里：MIUI 的文件源给 epub/mobi 报的就是它，只列精确 mime 会在选择器里看不见
    fun pick() = picker.launch(
        arrayOf(
            "text/*",
            "application/epub+zip",
            "application/pdf",
            "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
            "application/x-mobipocket-ebook",
            "application/vnd.amazon.ebook",
            "application/vnd.openxmlformats-officedocument.presentationml.presentation",
            "application/octet-stream",
        ),
    )

    Box(Modifier.fillMaxSize().background(Chrome.page)) {
        Column(Modifier.fillMaxSize()) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(start = 23.dp, end = 12.dp, top = 12.dp, bottom = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("书架", fontSize = 19.sp, fontWeight = FontWeight.Medium, color = Chrome.ink)
                Text("  ${books.size} 本", fontSize = 13.sp, color = Chrome.faint)
                Spacer(Modifier.weight(1f))
                // 「导入书籍」那颗文字按钮换成三个点：这一行的右边现在只管两件事（这一屏怎么排 + 从哪拿书），
                // 都收进弹框，不再各占一颗按钮
                Box(modifier = Modifier.size(44.dp).tap { menu = true }, contentAlignment = Alignment.Center) {
                    Icon(
                        painterResource(R.drawable.ic_more),
                        contentDescription = "书架选项",
                        // 和行里那三颗点同一档灰色（他点的：两处都要灰）、同一档大小（18dp，见下面那行）
                        tint = Chrome.faint,
                        modifier = Modifier.size(MORE_ICON),
                    )
                }
            }

            busy?.let { (label, progress) ->
                Column(Modifier.fillMaxWidth().padding(horizontal = 23.dp)) {
                    Text("$label ${(progress * 100).toInt()}%", fontSize = 13.sp, color = Chrome.faint)
                    LinearProgressIndicator(
                        progress = { progress },
                        modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                        color = Chrome.accent,
                        trackColor = Chrome.hairline,
                    )
                }
            }

            note?.let {
                Text(
                    it,
                    fontSize = 13.sp,
                    color = Chrome.faint,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 23.dp, vertical = 4.dp),
                )
            }

            // 两种排法共用同一份 books / 同一个开书和长按删的手势，只有行怎么摆不同
            if (shelfList) {
                LazyColumn(
                    contentPadding = PaddingValues(start = 12.dp, end = 12.dp, top = 0.dp, bottom = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                    modifier = Modifier.fillMaxSize(),
                    state = listState,
                ) {
                    items(books, key = { it.id }) { meta ->
                        BookRow(
                            meta = meta,
                            position = prefs.loadPosition(meta.id),
                            onOpen = { onOpen(meta) },
                            onLongPress = { actionsFor = meta },
                            onMore = { actionsFor = meta },
                        )
                    }
                }
            } else {
                LazyVerticalGrid(
                    columns = GridCells.Fixed(3),
                    contentPadding = PaddingValues(start = 23.dp, end = 23.dp, top = 10.dp, bottom = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(46.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                    modifier = Modifier.fillMaxSize(),
                    state = gridState,
                ) {
                    items(books, key = { it.id }) { meta ->
                        BookCard(
                            meta = meta,
                            position = prefs.loadPosition(meta.id),
                            onOpen = { onOpen(meta) },
                            onLongPress = { actionsFor = meta },
                        )
                    }
                    item { AddBookCard(onClick = { pick() }) }
                }
            }
        }

        if (menu) {
            // 铺满一整屏的透明接盘：点弹框外面就收，这一层不吃掉的话那一枪会落到书架卡片上、把书翻开
            Box(Modifier.fillMaxSize().tap { menu = false })
            Column(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    // 58dp = 顶栏 12 + 那颗 44 的框 + 2dp 缝；右缘和 ⋯ 的框对齐（同一个 12dp 内衬）
                    .padding(top = 58.dp, end = 12.dp)
                    .width(200.dp)
                    .clip(RoundedCornerShape(SHEET_RADIUS))
                    .background(Chrome.card),
            ) {
                ShelfMenuRow(
                    if (shelfList) R.drawable.ic_layout_grid else R.drawable.ic_layout_list,
                    if (shelfList) "切换至图墙模式" else "切换至列表模式",
                ) {
                    shelfList = !shelfList
                    prefs.shelfList = shelfList
                    menu = false
                }
                ShelfMenuRow(R.drawable.ic_import_local, "本地导入") {
                    menu = false
                    pick()
                }
            }
        }
    }

    actionsFor?.let { meta ->
        BookActionSheet(
            meta = meta,
            pinned = prefs.isPinned(meta.id),
            onDismiss = { actionsFor = null },
            onRemove = {
                prefs.setRemoved(meta.id, true)
                reload()
                actionsFor = null
                note = "已从书架移除《${meta.title}》：本机那份正文、书签、阅读位置都还在，再导入同一个文件会重新出现"
            },
            onRename = {
                actionsFor = null
                renameFor = meta
            },
            onPin = {
                prefs.setPinned(meta.id, !prefs.isPinned(meta.id))
                reload()
                actionsFor = null
                // 置顶把这一行插到当前视口上面，不滚回顶就是"点下去没反应"（真机撞到的：
                // 列表停在 delta 那一行，被置顶的那本在它上面，屏幕上看不到任何变化）
                scope.launch {
                    listState.scrollToItem(0)
                    gridState.scrollToItem(0)
                }
            },
            onDelete = {
                actionsFor = null
                confirmDeleteFor = meta
            },
        )
    }

    renameFor?.let { meta ->
        RenameBookSheet(
            meta = meta,
            onDismiss = { renameFor = null },
            onSave = { title ->
                library.rename(meta.id, title)
                reload()
                renameFor = null
                note = "书名已改成《$title》"
            },
        )
    }

    confirmDeleteFor?.let { meta ->
        AlertDialog(
            onDismissRequest = { confirmDeleteFor = null },
            // 他点名要的就是这一句，所以不加第二行说明
            title = { Text("确认将《${meta.title}》删除？") },
            confirmButton = {
                TextButton(onClick = {
                    library.delete(meta.id)
                    // 书都删了还把按书存的那串键留在 prefs 里 = 永远读不到的死字节，一并摘掉
                    prefs.clearBook(meta.id)
                    reload()
                    confirmDeleteFor = null
                    note = "已删除《${meta.title}》"
                }) { Text("删除", color = Chrome.danger) }
            },
            dismissButton = { TextButton(onClick = { confirmDeleteFor = null }) { Text("取消") } },
        )
    }
}

/** 置顶的排前面，其余保持书架原来的顺序（按目录名列出来 = 新的在前）。`sortedByDescending` 是稳定排序，不会打乱没置顶那一段。 */
private fun List<BookMeta>.shelfOrder(prefs: ReaderPrefs): List<BookMeta> =
    filterNot { prefs.isRemoved(it.id) }.sortedByDescending { prefs.isPinned(it.id) }

/**
 * 一本书的四个动作。两个入口开同一层：长按这一行、或点这一行右边那颗 ⋯。
 * 「移除书架」和「删除书籍」是两件事 —— 前者只从列表里摘掉、本机那份一个字不动；
 * 后者才真删，删之前再单独弹一次框（那一步回不去）。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun BookActionSheet(
    meta: BookMeta,
    pinned: Boolean,
    onDismiss: () -> Unit,
    onRemove: () -> Unit,
    onRename: () -> Unit,
    onPin: () -> Unit,
    onDelete: () -> Unit,
) {
    BookSheet(
        onDismiss = onDismiss,
        // 这一层里有两个动作是回不去的，所以最上面先说清"这是哪一本"；书名长就换行，不裁
        header = { Text(meta.title, fontSize = 16.sp, color = Chrome.ink, lineHeight = 22.sp) },
    ) {
        BookActionRow("移除书架", onClick = onRemove)
        BookActionRow("重命名", onClick = onRename)
        // 点完这一颗书名那一行右边的标记要跟着变，所以标题跟着状态走，不写死"置顶"
        BookActionRow(if (pinned) "取消置顶" else "置顶", onClick = onPin)
        BookActionRow("删除书籍", danger = true, onClick = onDelete)
        Spacer(Modifier.height(6.dp))
    }
}

/** 一行一个动作：52dp 高、整行都是命中区；「删除书籍」那颗红字。 */
@Composable
private fun BookActionRow(label: String, danger: Boolean = false, onClick: () -> Unit) {
    Box(
        modifier = Modifier.fillMaxWidth().height(52.dp).tap(onClick),
        contentAlignment = Alignment.CenterStart,
    ) {
        Text(label, fontSize = 15.sp, color = if (danger) Chrome.danger else Chrome.ink)
    }
}

/** 改名：底部抽屉里一格输入（要输入的浮层不做居中弹窗），空着或没改时那颗「保存」置灰。 */
@Composable
private fun RenameBookSheet(
    meta: BookMeta,
    onDismiss: () -> Unit,
    onSave: (String) -> Unit,
) {
    var tmp by remember(meta.id) { mutableStateOf(meta.title) }
    BookSheet(
        onDismiss = onDismiss,
        header = { Text("重命名", fontSize = 16.sp, color = Chrome.ink) },
    ) {
        OutlinedTextField(
            value = tmp,
            onValueChange = { tmp = it.take(40) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
        )
        Row(
            modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // 「取消」按他点的撤了：这一层点抽屉外面、或按系统返回都能回得去，不是没有出口
            Spacer(Modifier.weight(1f))
            SheetAction(
                "保存",
                Chrome.accent,
                enabled = tmp.isNotBlank() && tmp.trim() != meta.title,
                onClick = { onSave(tmp.trim()) },
            )
        }
        Spacer(Modifier.height(14.dp))
    }
}

/** 两本书动作那层的同一份外壳：底部推上来、`Chrome.card`、顶上一对圆角、没有拖拽把手。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun BookSheet(onDismiss: () -> Unit, header: @Composable () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = Chrome.card,
        shape = RoundedCornerShape(topStart = SHEET_RADIUS, topEnd = SHEET_RADIUS),
        dragHandle = null,
    ) {
        Column(Modifier.padding(horizontal = 19.dp, vertical = 19.dp)) {
            header()
            Spacer(Modifier.height(10.dp))
            content()
        }
    }
}

/** ⋯ 弹框里的一行：图标 + 文字，52dp 高。整个宽度都是命中区，不是只有那两个字。 */
@Composable
private fun ShelfMenuRow(iconRes: Int, label: String, onClick: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().height(52.dp).tap(onClick).padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            painterResource(iconRes),
            contentDescription = null,
            tint = Chrome.ink,
            modifier = Modifier.size(20.dp),
        )
        Spacer(Modifier.size(12.dp))
        Text(label, fontSize = 15.sp, color = Chrome.ink)
    }
}

/**
 * 列表模式的一行。卡片外壳、圆角和封面比例跟「精选」那一行同一档（4dp 封面圆角、10dp 卡片圆角、
 * 封面长宽比 1:1.34），但封面本身缩到 **48x64dp = 精选那颗的 0.79 倍**（线性）：这一行的主角是右边三行字，
 * 82dp 的封面把行高顶到 106dp，三行字只吃 45dp，剩下全是图的重量。
 * 12dp 内衬没动 —— 他只点了图片，没点行高。
 */
@Composable
private fun BookRow(
    meta: BookMeta,
    position: com.bookreader.core.model.BookPosition?,
    onOpen: () -> Unit,
    onLongPress: () -> Unit,
    onMore: () -> Unit,
) {
    val whole = BookMeta.WHOLE_PAGE.contains(meta.kind)
    val unread = if (whole) null else position?.let { (meta.chapterCount - it.chapterIndex - 1).coerceAtLeast(0) }
    val cover = coverOf(meta.title)
    Row(
        modifier = Modifier
                .fillMaxWidth()
            // 不再套卡片：行直接铺在页面底色上（他给的参考图就是没有卡片那一层）
            .combinedClickable(onClick = onOpen, onLongClick = onLongPress)
            // 上下收到 8dp、行缝 10→4dp（他要"间隙小一点"）；左仍 12dp（封面那条左线才不动），
            // 右边不给内衬：让行末那颗 ⋯ 的框和顶栏那颗落在同一条线上（两边留一样宽的间隙）
            .padding(start = 12.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (cover != null) {
            Image(
                painter = painterResource(cover),
                contentDescription = meta.title,
                contentScale = ContentScale.Crop,
                modifier = Modifier.size(width = 48.dp, height = 64.dp).clip(RoundedCornerShape(4.dp)),
            )
        } else {
            // 行底现在是 Chrome.page，占位封面退一档用 Chrome.chip：还是同一套 token，不再造新颜色
            Box(
                modifier = Modifier
                    .size(width = 48.dp, height = 64.dp)
                    .background(Chrome.chip, RoundedCornerShape(4.dp)),
                contentAlignment = Alignment.Center,
            ) {
                Text(meta.title.take(2), fontSize = 15.sp, fontWeight = FontWeight.Medium, color = Chrome.ink)
            }
        }
        Spacer(Modifier.size(12.dp))
        Column(Modifier.fillMaxWidth()) {
            // 作者这一档：抽不出作者的件（txt / 本地文档全是）不能把分隔点单独晾在行首
            val who = meta.author.trim()
            val stat = when {
                whole -> "${meta.pages} 页"
                unread != null -> "${unread}章未读"
                else -> "${meta.chapterCount} 章"
            }
            // 书名和那颗 ⋯ 同一行：⋯ 和长按开的是同一个抽屉（两个入口、一份动作），
            // 44dp 命中框 + 18dp 图标 = 顶栏那三颗点同一份，所以两颗的墨右在同一条线上。
            // 18dp 是他这轮"小一点"落下来的数：×0.82（22→18），这台机 2.75 倍下三个点的墨宽
            // 45.4px → 37.1px、单个点 ⌀10.1px → 8.25px。命中框不动（44dp）—— 要小的是看得见的点，
            // 不是按得中的地方
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    meta.title,
                    fontSize = 16.sp,
                    color = Chrome.ink,
                    lineHeight = 22.sp,
                    modifier = Modifier.weight(1f),
                )
                Box(modifier = Modifier.size(44.dp).tap(onMore), contentAlignment = Alignment.Center) {
                    Icon(
                        painterResource(R.drawable.ic_more),
                        contentDescription = "《${meta.title}》的选项",
                        // 灰的：和顶栏那三颗点同一档，行里这颗不比书名抢眼
                        tint = Chrome.faint,
                        modifier = Modifier.size(MORE_ICON),
                    )
                }
            }
            Row(
                modifier = Modifier.padding(top = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(if (who.isEmpty()) stat else "$who · $stat", fontSize = 13.sp, color = Chrome.faint)
                // 原来这里是一颗 7dp 红点（他点的去掉了）：同一行文字已经写着"N章未读"，
                // 红点是同一件事说两遍。连它前面那 5dp 的占位一起删 —— 留着一个空 Spacer 就是死控件
            }
            Text(
                if (whole) "${meta.kind.uppercase()} 原件整页" else "${meta.chapterCount} 章 · ${formatChars(meta.totalChars)}",
                fontSize = 13.sp,
                color = Chrome.faint,
                modifier = Modifier.padding(top = 2.dp),
            )
        }
    }
}

/** 参考截图里有的书有封面；没封面的书用书名前两字占位，不放假图冒充。听书浮窗也读这一颗。 */
internal fun coverOf(title: String): Int? = when {
    title.contains("剑来") || title.contains("jianlai", ignoreCase = true) -> R.drawable.cover_jianlai
    else -> null
}

@Composable
private fun BookCard(
    meta: BookMeta,
    position: com.bookreader.core.model.BookPosition?,
    onOpen: () -> Unit,
    onLongPress: () -> Unit,
) {
    // pdf 没有"章"这个口径，也没有排版位置可算未读
    val whole = BookMeta.WHOLE_PAGE.contains(meta.kind)
    // 参考截图那一行写的是"12章未读"，我们手上有章号，就用同一个口径，不写"读到 0%"这种看不出东西的百分比
    val unread = if (whole) null else position?.let { (meta.chapterCount - it.chapterIndex - 1).coerceAtLeast(0) }
    val cover = coverOf(meta.title)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(onClick = onOpen, onLongClick = onLongPress),
    ) {
        if (cover != null) {
            Image(
                painter = painterResource(cover),
                contentDescription = meta.title,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(116.dp)
                    .clip(RoundedCornerShape(4.dp)),
            )
        } else {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(116.dp)
                    .background(Chrome.card, RoundedCornerShape(4.dp)),
                contentAlignment = Alignment.Center,
            ) {
                Text(meta.title.take(2), fontSize = 24.sp, fontWeight = FontWeight.Medium, color = Chrome.ink)
            }
        }
        // 书名不截断：截断等于这个控件没做完
        Text(
            meta.title,
            fontSize = 15.sp,
            color = Chrome.ink,
            modifier = Modifier.padding(top = 7.dp),
            lineHeight = 20.sp,
        )
        Row(
            modifier = Modifier.fillMaxWidth().padding(top = 3.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                when {
                    whole -> "${meta.pages} 页"
                    unread != null -> "${unread}章未读"
                    else -> "${meta.chapterCount} 章"
                },
                fontSize = 12.sp,
                color = Chrome.faint,
            )
            if (unread != null && unread > 0) {
                // 未读点：参考截图量到 #5A0000（在变暗遮罩下），反算成 #E30000
                Spacer(Modifier.size(5.dp))
                Box(Modifier.size(7.dp).background(Chrome.dot, CircleShape))
            }
        }
    }
}

@Composable
private fun AddBookCard(onClick: () -> Unit) {
    val line = Chrome.hairline
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .height(116.dp)
            .drawBehind {
                drawRect(
                    color = line,
                    // 1dp 在这台机是 3 行；虚线框要 1 个物理像素
                style = Stroke(width = 1f, pathEffect = PathEffect.dashPathEffect(floatArrayOf(8f, 8f), 0f)),
                )
            }
            .tap(onClick),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            modifier = Modifier
                .size(30.dp)
                .background(Chrome.page, CircleShape),
            contentAlignment = Alignment.Center,
        ) { Text("+", fontSize = 20.sp, color = Chrome.faint) }
        Text("添加作品", fontSize = 12.sp, color = Chrome.faint, modifier = Modifier.padding(top = 6.dp))
    }
}

fun formatChars(chars: Long): String = when {
    chars >= 10_000 -> "${chars / 10_000} 万字"
    else -> "$chars 字"
}
