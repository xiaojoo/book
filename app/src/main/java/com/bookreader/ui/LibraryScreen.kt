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
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
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
    var books by remember { mutableStateOf(library.list()) }
    var busy by remember { mutableStateOf<Pair<String, Float>?>(null) }
    var note by remember(notice) { mutableStateOf(notice) }
    var pendingDelete by remember { mutableStateOf<BookMeta?>(null) }
    val scope = rememberCoroutineScope()

    fun startImport(uri: android.net.Uri) {
        busy = "正在导入" to 0f
        scope.launch {
            val outcome = runCatching {
                withContext(Dispatchers.IO) {
                    library.import(uri) { progress -> scope.launch { busy = "正在导入" to progress.toFloat() } }
                }
            }
            outcome.onSuccess { meta ->
                books = library.list()
                note = "已入库：${meta.title}，${meta.chapterCount} 章 / ${formatChars(meta.totalChars)}"
            }.onFailure { error ->
                note = "导入失败：${error.message ?: error.javaClass.simpleName}"
            }
            busy = null
        }
    }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) startImport(uri)
    }
    fun pick() = picker.launch(arrayOf("text/*", "application/octet-stream"))

    Column(Modifier.fillMaxSize().background(Chrome.page)) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(start = 23.dp, end = 12.dp, top = 12.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("书架", fontSize = 19.sp, fontWeight = FontWeight.Medium, color = Chrome.ink)
            Text("  ${books.size} 本", fontSize = 13.sp, color = Chrome.faint)
            Spacer(Modifier.weight(1f))
            TextButton(onClick = { pick() }, modifier = Modifier.height(44.dp)) {
                Text("导入 TXT", fontSize = 15.sp, color = Chrome.accent)
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

        LazyVerticalGrid(
            columns = GridCells.Fixed(3),
            contentPadding = PaddingValues(start = 23.dp, end = 23.dp, top = 10.dp, bottom = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(46.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
            modifier = Modifier.fillMaxSize(),
        ) {
            items(books, key = { it.id }) { meta ->
                BookCard(
                    meta = meta,
                    position = prefs.loadPosition(meta.id),
                    onOpen = { onOpen(meta) },
                    onLongPress = { pendingDelete = meta },
                )
            }
            item { AddBookCard(onClick = { pick() }) }
        }
    }

    pendingDelete?.let { meta ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text("删除《${meta.title}》？") },
            text = { Text("会删掉已入库的正文索引和这本书的阅读位置、书签。原文件不动。") },
            confirmButton = {
                TextButton(onClick = {
                    library.delete(meta.id)
                    books = library.list()
                    pendingDelete = null
                    note = "已删除《${meta.title}》"
                }) { Text("删除", color = Chrome.danger) }
            },
            dismissButton = { TextButton(onClick = { pendingDelete = null }) { Text("取消") } },
        )
    }
}

/** 参考截图里有的书有封面；没封面的书用书名前两字占位，不放假图冒充。 */
private fun coverOf(title: String): Int? = when {
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
    // 参考截图那一行写的是"12章未读"，我们手上有章号，就用同一个口径，不写"读到 0%"这种看不出东西的百分比
    val unread = position?.let { (meta.chapterCount - it.chapterIndex - 1).coerceAtLeast(0) }
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
                if (unread != null) "${unread}章未读" else "${meta.chapterCount} 章",
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
