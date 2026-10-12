package com.bookreader.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.sp
import com.bookreader.booksource.HttpFetcher
import com.bookreader.booksource.PageRequest
import com.bookreader.booksource.SearchEngine
import com.bookreader.data.BookLibrary
import com.bookreader.data.BookProvenance
import com.bookreader.data.DownloadRecord
import com.bookreader.data.Downloads
import com.bookreader.data.StoredSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 「本书信息」那一页要摆的东西。两个方向凑出来：还没下过的（搜索结果/精选）和已经下过的（书架）。
 *
 * 为什么不带协程参数进去：这一页可能从三个入口打开，而"简介和大纲要不要现抓"取决于
 * 手上那份数据全不全 —— 判这件事的应该是页面自己，不是每个入口各写一遍。
 */
data class BookInfo(
    val title: String,
    val author: String = "",
    val category: String = "",
    val latestChapter: String = "",
    val wordCount: String = "",
    val intro: String = "",
    val outline: List<String> = emptyList(),
    val bookUrl: String = "",
    val sourceId: String = "",
    /** 已经进书架的那一本的 id；空 = 还没下过。 */
    val bookId: String = "",
) {
    /** 目录都读不全就别去现抓：本地导入的那一本根本没有书页。 */
    val hasSource get() = bookUrl.isNotEmpty() && sourceId.isNotEmpty()
}

/**
 * 一本书的信息页：封面 + 书名/作者/类目 + 简介全文 + 大纲（章名列表）+ 底部那一颗动作。
 *
 * 形态和「任务」「管理」同一族：整屏、页头一颗 ✕、底栏让位（这一页挂在壳的最外层，
 * 不画在 `AppScaffold` 里面，所以底栏自然不在）。
 *
 * 简介/大纲缺了才现抓一次（搜索结果那一行只带书名作者，书页没读过）。抓不到就在页面上写明，
 * 不摆一段假简介糊人。
 */
@Composable
fun BookInfoPage(
    info: BookInfo,
    sources: List<StoredSource>,
    onBack: () -> Unit,
    onOpen: (String) -> Unit,
) {
    val ctx = LocalContext.current
    var shown by remember { mutableStateOf(info) }
    var loading by remember { mutableStateOf(false) }
    var note by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf("") }
    var reload by remember { mutableStateOf(0) }
    val rec = remember(shown.bookUrl, reload, busy) { Downloads.history(ctx).find(shown.bookUrl) }

    BackHandler(onBack = onBack)
    // 只有"既没简介也没大纲、又确实有条源能去读"时才补一次；书架上那本本地文件里都有，不重复打站子
    LaunchedEffect(shown.bookUrl, shown.sourceId) {
        if (shown.intro.isNotEmpty() || shown.outline.isNotEmpty() || !shown.hasSource) return@LaunchedEffect
        loading = true
        val rule = sources.firstOrNull { it.rule?.id == shown.sourceId }?.rule
        if (rule == null) {
            note = "这条源（${shown.sourceId}）已经不在了，简介和大纲没处读"
        } else {
            val h = runCatching {
                withContext(Dispatchers.IO) { SearchEngine(rule, HttpFetcher()).head(shown.bookUrl) }
            }.getOrNull()
            if (h == null) note = "书页读不回来（站点限流、改版，或者要跑 JS）" else {
                shown = shown.copy(intro = h.intro, outline = h.chapters.map { it.first })
            }
        }
        loading = false
    }

    Column(Modifier.fillMaxSize().background(Chrome.page)) {
        PageHeader("本书信息", onClose = onBack)
        Column(Modifier.fillMaxWidth().weight(1f).verticalScroll(rememberScrollState())) {
            Row(Modifier.fillMaxWidth().padding(16.dp)) {
                BookCover(shown.title, shown.bookId, Modifier.size(width = 82.dp, height = 110.dp))
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(shown.title, fontSize = 19.sp, fontWeight = FontWeight.Medium, color = Chrome.ink)
                    val sub = listOfNotNull(
                        shown.author.takeIf { it.isNotEmpty() },
                        shown.category.takeIf { it.isNotEmpty() },
                        shown.wordCount.takeIf { it.isNotEmpty() },
                    ).joinToString(" · ")
                    if (sub.isNotEmpty()) Text(sub, fontSize = 12.sp, color = Chrome.faint, modifier = Modifier.padding(top = 4.dp))
                    if (shown.latestChapter.isNotEmpty()) {
                        Text("最新：" + shown.latestChapter, fontSize = 12.sp, color = Chrome.faint,
                            maxLines = 2, modifier = Modifier.padding(top = 4.dp))
                    }
                }
            }
            InfoHairline()
            Section("简介") {
                when {
                    loading -> Line("正在读…", Chrome.faint)
                    shown.intro.isNotEmpty() -> Paragraph(shown.intro)
                    else -> Line("这条源没写简介在哪一格，页面上也没有", Chrome.faint)
                }
            }
            Section("大纲") {
                if (shown.outline.isEmpty()) Line("读不到目录，就没有大纲可列", Chrome.faint)
                shown.outline.forEachIndexed { i, n -> Line("${i + 1}. $n", Chrome.ink, 13.sp) }
            }
        }
        if (note.isNotEmpty()) {
            Text(note, fontSize = 12.sp, color = Chrome.danger, lineHeight = 17.sp,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp))
        }
        if (busy.isNotEmpty()) Line(busy, Chrome.faint, modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp))
        InfoActions(shown, rec, sources, ctx, onBack, onOpen) { note = it; busy = "" }
    }
}

/** 底部那一排：按"这本在不在架上、拉了开头还是全本"给一颗，不给第二颗。 */
@Composable
private fun InfoActions(
    info: BookInfo,
    rec: DownloadRecord?,
    sources: List<StoredSource>,
    ctx: android.content.Context,
    onBack: () -> Unit,
    onOpen: (String) -> Unit,
    onNote: (String) -> Unit,
) {
    val running = rec?.running == true
    val done = rec?.state == DownloadRecord.STATE_DONE
    Row(
        Modifier.fillMaxWidth().height(52.dp).padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (info.bookId.isNotEmpty()) {
            FilledAction("打开来读") { onOpen(info.bookId) }
        }
        if (info.hasSource && (running || done || info.bookId.isEmpty())) {
            when {
                // 正在下的时候只报进度，不给一颗点了没反应的假按钮
                running -> Line("正在下：${rec.done} / ${rec.total} 章", Chrome.faint)
                info.bookId.isEmpty() -> {
                    FilledAction("先拉开头十章") {
                        start(info, sources, ctx, onNote, head = 10)
                    }
                    PlainAction("直接下全本") { start(info, sources, ctx, onNote, head = 0) }
                }
                else -> PlainAction("补齐全本") { start(info, sources, ctx, onNote, head = 0) }
            }
        }
        if (info.bookId.isEmpty() && !info.hasSource) {
            Line("这本不在书架上，也没有来源可下", Chrome.faint)
        }
    }
}

private fun start(
    info: BookInfo,
    sources: List<StoredSource>,
    ctx: android.content.Context,
    onNote: (String) -> Unit,
    head: Int,
) {
    val rule = sources.firstOrNull { it.rule?.id == info.sourceId }?.rule
    if (rule == null) {
        onNote("这条源（${info.sourceId}）已经不在了，抓不动")
        return
    }
    Downloads.start(
        ctx, rule,
        com.bookreader.booksource.BookHit(
            sourceId = rule.id, sourceName = rule.name, bookName = info.title,
            author = info.author, bookUrl = info.bookUrl,
        ),
        update = false,
        head = head,
    )
}

@Composable
private fun Section(title: String, content: @Composable () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp)) {
        Text(title, fontSize = 13.sp, color = Chrome.faint, modifier = Modifier.padding(bottom = 6.dp))
        content()
    }
    InfoHairline()
}

@Composable
private fun Paragraph(text: String) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        text.split('\n').filter { it.isNotBlank() }.forEach {
            Text(it, fontSize = 14.sp, lineHeight = 21.sp, color = Chrome.ink)
        }
    }
}

@Composable
private fun Line(text: String, color: androidx.compose.ui.graphics.Color, size: androidx.compose.ui.unit.TextUnit = 14.sp, modifier: Modifier = Modifier) {
    Text(text, fontSize = size, lineHeight = (size.value + 6f).sp, color = color, modifier = modifier)
}

/** 底部那颗实心动作：和搜书那排的「装上」同一档（红底白字、44dp 高、10dp 圆角）。 */
@Composable
private fun FilledAction(label: String, onClick: () -> Unit) {
    Box(
        Modifier.height(40.dp).tap(onClick).background(Chrome.accent, RoundedCornerShape(10.dp)),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, fontSize = 14.sp, color = Color.White, modifier = Modifier.padding(horizontal = 16.dp))
    }
}

@Composable
private fun PlainAction(label: String, onClick: () -> Unit) {
    Text(label, fontSize = 14.sp, color = Chrome.accent, modifier = Modifier.padding(horizontal = 4.dp))
}

@Composable
private fun InfoHairline() {
    Box(Modifier.fillMaxWidth().height(1.dp).background(Chrome.hairline))
}

