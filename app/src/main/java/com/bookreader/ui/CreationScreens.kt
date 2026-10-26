package com.bookreader.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.bookreader.R
import com.bookreader.core.creation.Card
import com.bookreader.core.creation.CardKind
import com.bookreader.core.creation.Chapter
import com.bookreader.core.creation.CreationAi
import com.bookreader.core.creation.CreationFormat
import com.bookreader.core.creation.Novel
import com.bookreader.data.AiClient
import com.bookreader.data.AiError
import com.bookreader.data.CreationStore
import com.bookreader.data.NovelMeta
import com.bookreader.data.ReaderPrefs
import com.bookreader.data.SecretStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date

/**
 * 小说创作：「我的」进来 → 篇列表 → 某一篇的章列表 → 某一章的正文。
 *
 * 三屏都是**整屏页**（照「昵称」「AI 设置」那一族：顶栏 `‹ 标题 …… 右侧那颗`，底栏整条让位），
 * 不是弹层 —— 写东西的屏被半屏弹层盖住一半，等于没法写。
 * 卡片是**底部抽屉**（只露前几条）+「更多」跳一整屏看全部：他要的是"随手看一眼"和"逐条查"分开。
 */

/**
 * 创作那一族四屏的路由，编码成**一条字符串**存进 `rememberSaveable`：
 * 四颗布尔各存一份就得自己维持互斥（开这一屏要关那三颗），一条状态天然只有一种。
 *
 * 篇 id 是自己生成的 36 进制串（见 `CreationStore.newId`），里面不会有冒号，所以这样切是安全的。
 */
object CreationRoute {
    const val LIST = "list"
    private const val BOOK = "book:"
    private const val CHAPTER = "ch:"
    private const val CARDS = "cards:"

    fun book(id: String) = BOOK + id
    fun chapter(id: String, index: Int) = CHAPTER + id + ":" + index

    /** 卡片那一屏要记住是从哪一屏进来的（-1 = 章列表那屏），返回才回得去原地而不是退回篇列表。 */
    fun cards(id: String, from: Int) = CARDS + id + ":" + from

    fun isBook(raw: String) = raw.startsWith(BOOK)
    fun isChapter(raw: String) = raw.startsWith(CHAPTER)
    fun isCards(raw: String) = raw.startsWith(CARDS)

    fun idOf(raw: String): String = raw.substringAfter(":").substringBefore(":")
    fun indexOf(raw: String): Int = raw.substringAfterLast(':').toIntOrNull() ?: -1
}

/** 顶栏：48dp 一条，左边一颗 40dp 的 ‹，标题 19sp 中等，右边一颗文字按钮。三屏共用这一份几何。 */
@Composable
private fun CreationBar(title: String, onBack: () -> Unit, trailing: String? = null, onTrailing: () -> Unit = {}) {
    Row(
        Modifier.fillMaxWidth().height(48.dp).padding(start = 8.dp, end = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(40.dp).tap(onBack), contentAlignment = Alignment.Center) {
            Icon(painterResource(R.drawable.ic_back), "返回", tint = Chrome.ink, modifier = Modifier.size(20.dp))
        }
        Text(
            title, fontSize = 19.sp, fontWeight = FontWeight.Medium, color = Chrome.ink,
            maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f).padding(start = 4.dp),
        )
        if (trailing != null) {
            Text(
                trailing, fontSize = 17.sp, fontWeight = FontWeight.Medium, color = Chrome.accent,
                modifier = Modifier.tap(onTrailing).padding(vertical = 8.dp, horizontal = 6.dp),
            )
        }
    }
    // 0.5dp：1dp 在 2.75 倍上取整成 3 行，这条要的是 1 个物理像素（和「昵称」那一屏同一条线）
    Box(Modifier.fillMaxWidth().height(0.5.dp).background(Chrome.hairline))
}

@Composable
private fun CreationNote(text: String?) {
    if (text == null) return
    Text(text, fontSize = 12.sp, color = Chrome.faint, lineHeight = 17.sp, modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp))
}

// ---------------------------------------------------------------- 篇列表

@Composable
fun CreationListScreen(store: CreationStore, onBack: () -> Unit, onOpen: (String) -> Unit) {
    ApplySystemBars(darkIcons = !Chrome.night)
    BackHandler(onBack = onBack)
    var tick by remember { mutableIntStateOf(0) }
    val items = remember(tick) { store.list() }
    var confirm by remember { mutableStateOf<NovelMeta?>(null) }
    var note by remember { mutableStateOf<String?>(null) }

    Column(Modifier.fillMaxSize().background(Chrome.page).safeDrawingPadding()) {
        CreationBar("小说创作", onBack, "新建") {
            // 新建之后直接进那一篇：停在列表上让人再点一次"未命名"是多余的一枪
            val id = store.create("未命名稿")
            if (id == null) note = "建不出来：这台机的应用目录写不进去" else onOpen(id)
        }
        CreationNote(note)
        if (items.isEmpty()) {
            Text(
                "还没有稿子。按右上角「新建」开一篇，进去「新建一章」就可以写了。",
                fontSize = 13.sp, color = Chrome.faint, lineHeight = 20.sp,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 18.dp),
            )
        }
        LazyColumn(Modifier.fillMaxWidth().weight(1f), contentPadding = androidx.compose.foundation.layout.PaddingValues(bottom = 16.dp)) {
            items(items, key = { it.id }) { m ->
                Row(
                    Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(horizontal = 16.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    // 整行（不含右边那颗 ✕）都是"进去写"的命中区
                    Column(Modifier.weight(1f).tap { onOpen(m.id) }) {
                        Text(m.title, fontSize = 15.sp, color = Chrome.ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(
                            subLine(m), fontSize = 12.sp, color = Chrome.faint,
                            maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 2.dp),
                        )
                    }
                    Box(Modifier.size(40.dp).tap { confirm = m }, contentAlignment = Alignment.CenterEnd) {
                        Icon(painterResource(R.drawable.ic_close), "删掉这一篇", tint = Chrome.faint, modifier = Modifier.size(16.dp))
                    }
                }
                Hairline()
            }
        }
    }
    confirm?.let { m ->
        ConfirmDialog(
            title = "删掉「${m.title}」？",
            body = "这一篇的 ${m.chapterCount} 章正文和 ${m.cardCount} 张卡片一起删掉，找不回来。",
            confirm = "删除",
            onConfirm = { store.delete(m.id); confirm = null; tick++ },
            onDismiss = { confirm = null },
        )
    }
}

private fun subLine(m: NovelMeta): String {
    val when2 = SimpleDateFormat("MM-dd HH:mm").format(Date(m.updatedAt))
    return "${m.chapterCount} 章 · ${formatChars(m.chars.toLong())}" +
        (if (m.cardCount == 0) "" else " · ${m.cardCount} 张卡") + " · $when2"
}

// ---------------------------------------------------------------- 某一篇：章列表

@Composable
fun CreationBookScreen(
    id: String,
    store: CreationStore,
    onBack: () -> Unit,
    onOpenChapter: (Int) -> Unit,
    onOpenCards: () -> Unit,
) {
    ApplySystemBars(darkIcons = !Chrome.night)
    var novel by remember(id) { mutableStateOf(store.load(id)) }
    var renaming by remember { mutableStateOf(false) }
    var cardsOpen by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf<Int?>(null) }
    var note by remember { mutableStateOf<String?>(null) }
    val n = novel
    BackHandler(onBack = onBack)

    if (n == null) {
        // 目录被别处删掉了（清理缓存、adb）：这一屏不能停在一片空白上
        Column(Modifier.fillMaxSize().background(Chrome.page).safeDrawingPadding()) {
            CreationBar("小说创作", onBack)
            Text("这一篇已经不在了。", fontSize = 13.sp, color = Chrome.faint, modifier = Modifier.padding(16.dp))
        }
        return
    }

    Column(Modifier.fillMaxSize().background(Chrome.page).safeDrawingPadding()) {
        CreationBar(n.title, onBack, "改名") { renaming = true }
        CreationNote(note)
        LazyColumn(Modifier.fillMaxWidth().weight(1f)) {
            items(n.chapters, key = { it.index }) { c ->
                Row(
                    Modifier.fillMaxWidth().heightIn(min = 52.dp).padding(horizontal = 16.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f).tap { onOpenChapter(c.index) }) {
                        Text(chapterLabel(c), fontSize = 15.sp, color = Chrome.ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(
                            formatChars(c.chars.toLong()), fontSize = 12.sp, color = Chrome.faint,
                            modifier = Modifier.padding(top = 2.dp),
                        )
                    }
                    Box(Modifier.size(40.dp).tap { confirmDelete = c.index }, contentAlignment = Alignment.CenterEnd) {
                        Icon(painterResource(R.drawable.ic_close), "删掉这一章", tint = Chrome.faint, modifier = Modifier.size(16.dp))
                    }
                }
                Hairline()
            }
            item {
                Box(
                    Modifier.fillMaxWidth().height(50.dp).padding(horizontal = 16.dp)
                        .tap {
                            val next = store.nextChapterIndex(n)
                            // 章名**留空**：行标签本来就是"第 N 章"，再预填一次会显示成"第 1 章 第 1 章"，
                            // 而且那五个字是占位不是内容 —— 他要改的话改的是空着的那格
                            store.save(n.copy(chapters = n.chapters + Chapter(next, "", ""), updatedAt = System.currentTimeMillis()))
                            novel = store.load(id)
                        },
                    contentAlignment = Alignment.Center,
                ) { Text("＋ 新建一章", fontSize = 15.sp, color = Chrome.accent) }
            }
            item {
                Row(
                    Modifier.fillMaxWidth().height(50.dp).padding(horizontal = 16.dp).tap { cardsOpen = true },
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("卡片", fontSize = 15.sp, color = Chrome.ink)
                    Spacer(Modifier.weight(1f))
                    Text("${n.cards.size} 张", fontSize = 13.sp, color = Chrome.faint, modifier = Modifier.padding(end = 4.dp))
                    Icon(painterResource(R.drawable.ic_chevron_right), null, tint = Chrome.faint, modifier = Modifier.size(16.dp))
                }
            }
        }
    }

    if (cardsOpen) CardsSheet(n.cards, onDismiss = { cardsOpen = false }, onMore = { cardsOpen = false; onOpenCards() })
    if (renaming) {
        NameSheet(n.title, onDismiss = { renaming = false }, onSubmit = { t ->
            store.rename(id, t)
            novel = store.load(id)
            renaming = false
        })
    }
    confirmDelete?.let { idx ->
        ConfirmDialog(
            title = "删掉这一章？",
            body = "这一章的正文一起删掉。卡片里由这一章抽出来的那些不动（要清得重新提取一次）。",
            confirm = "删除",
            onConfirm = {
                store.save(n.copy(chapters = n.chapters.filterNot { it.index == idx }, updatedAt = System.currentTimeMillis()))
                novel = store.load(id)
                confirmDelete = null
            },
            onDismiss = { confirmDelete = null },
        )
    }
}

private fun chapterLabel(c: Chapter): String =
    if (c.title.isBlank()) "第 ${c.index} 章" else "第 ${c.index} 章 ${c.title}"

// ---------------------------------------------------------------- 某一章：正文

@Composable
fun CreationChapterScreen(
    id: String,
    index: Int,
    store: CreationStore,
    prefs: ReaderPrefs,
    onBack: () -> Unit,
    onOpenCards: () -> Unit,
) {
    ApplySystemBars(darkIcons = !Chrome.night)
    val novel = remember(id) { store.load(id) }
    var text by remember(id, index) { mutableStateOf(novel?.chapter(index)?.text ?: "") }
    var title by remember(id, index) { mutableStateOf(novel?.chapter(index)?.title ?: "") }
    var busy by remember { mutableStateOf(false) }
    var note by remember { mutableStateOf<String?>(null) }
    var cardsOpen by remember { mutableStateOf(false) }
    var cards by remember(id) { mutableStateOf(novel?.cards ?: emptyList()) }
    val scope = rememberCoroutineScope()
    val keyboard = LocalSoftwareKeyboardController.current

    fun persist() {
        val n = store.load(id) ?: return
        val updated = n.chapters.map { if (it.index == index) it.copy(title = title.trim(), text = text) else it }
        store.save(n.copy(chapters = updated, updatedAt = System.currentTimeMillis()))
    }

    // 返回就存：写东西的人不该被要求"先按保存"，漏一次就是白写
    BackHandler {
        keyboard?.hide()
        persist()
        onBack()
    }
    if (novel == null || novel.chapter(index) == null) {
        Column(Modifier.fillMaxSize().background(Chrome.page).safeDrawingPadding()) {
            CreationBar("第 $index 章", onBack)
            Text("这一章已经不在了。", fontSize = 13.sp, color = Chrome.faint, modifier = Modifier.padding(16.dp))
        }
        return
    }

    Column(
        Modifier
            .fillMaxSize()
            .background(Chrome.page)
            // 系统栏那一格必须先扣掉：不扣的话顶栏压在状态栏上，右上角那颗「完成/改名」的墨落在状态栏里，
            // 看着就在按钮上、点到的却是系统栏（这一条是上屏才发现的）
            .safeDrawingPadding()
            // 键盘那一格自己扣：整屏就是输入框，被键盘盖住一半就没法写
            .imePadding()
    ) {
        CreationBar("第 $index 章", onBack, "完成") { keyboard?.hide(); persist(); onBack() }
        // 章名那一行：单行，改完当场进 text
        Box(Modifier.fillMaxWidth().height(44.dp).padding(horizontal = 16.dp), contentAlignment = Alignment.CenterStart) {
            BasicTextField(
                value = title,
                onValueChange = { title = it.take(40) },
                singleLine = true,
                textStyle = TextStyle(fontSize = 17.sp, color = Chrome.ink, fontWeight = FontWeight.Medium),
                cursorBrush = SolidColor(Chrome.accent),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
                modifier = Modifier.fillMaxWidth(),
                decorationBox = { inner ->
                    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.CenterStart) {
                        if (title.isEmpty()) Text("章名（可留空）", fontSize = 17.sp, color = Chrome.faint)
                        inner()
                    }
                },
            )
        }
        Box(Modifier.fillMaxWidth().height(0.5.dp).background(Chrome.hairline))
        Box(Modifier.fillMaxWidth().weight(1f).padding(horizontal = 16.dp, vertical = 10.dp)) {
            BasicTextField(
                value = text,
                onValueChange = { text = it },
                textStyle = TextStyle(fontSize = 16.sp, color = Chrome.ink, lineHeight = 27.sp),
                cursorBrush = SolidColor(Chrome.accent),
                modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
                decorationBox = { inner ->
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopStart) {
                        if (text.isEmpty()) Text("从这里开始写…", fontSize = 16.sp, color = Chrome.faint)
                        inner()
                    }
                },
            )
        }
        CreationNote(note)
        // 底栏那一条：字数常驻（实时），右边两颗动作
        Row(
            Modifier.fillMaxWidth().height(52.dp).padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(formatChars(Chapter(index, title, text).chars.toLong()), fontSize = 13.sp, color = Chrome.faint)
            Spacer(Modifier.weight(1f))
            Text(
                if (busy) "提取中…" else "提取",
                fontSize = 15.sp, color = if (busy) Chrome.faint else Chrome.accent,
                modifier = Modifier.tap(enabled = !busy) {
                    keyboard?.hide()
                    persist()
                    val configured = prefs.aiBaseUrl.isNotBlank() && prefs.aiKeyCipher.isNotBlank()
                    if (!configured) {
                        note = "还没配 AI 翻译：我的 → AI 翻译 里填 API 地址、模型名和秘钥。"
                    } else {
                        busy = true
                        note = null
                        scope.launch {
                            val plain = withContext(Dispatchers.IO) { SecretStore.decrypt(prefs.aiKeyCipher) }
                            val cut = CreationAi.truncated(text)
                            val outcome = withContext(Dispatchers.IO) {
                                runCatching {
                                    AiClient.chat(
                                        prefs.aiBaseUrl, plain ?: "", prefs.aiModel,
                                        CreationAi.SYSTEM, CreationAi.user(index, title, text),
                                    )
                                }
                            }
                            busy = false
                            outcome.fold(
                                onSuccess = { reply ->
                                    val p = CreationAi.parse(reply, index)
                                    cards = CreationFormat.merge(cards, p.cards)
                                    store.saveCards(id, cards)
                                    note = when {
                                        p.cards.isEmpty() && p.dropped.isEmpty() -> "这一章没有可提取的设定。"
                                        else -> "抽出 ${p.cards.size} 张卡" +
                                            (if (p.dropped.isNotEmpty()) "，有 ${p.dropped.size} 行没看懂" else "") +
                                            (if (cut) "（这一章太长，只发了前 ${CreationAi.MAX_CHARS} 字）" else "") + "。"
                                    }
                                },
                                onFailure = { note = if (it is AiError) it.message ?: "提取失败" else "提取失败：${it.javaClass.simpleName}" },
                            )
                        }
                    }
                }.padding(horizontal = 10.dp, vertical = 8.dp),
            )
            Text(
                "卡片 ${cards.size}", fontSize = 15.sp, color = Chrome.ink,
                modifier = Modifier.tap { cardsOpen = true }.padding(horizontal = 10.dp, vertical = 8.dp),
            )
        }
    }
    // 「更多」那一屏是另一屏：先把手上这些字落盘再走，不然跳一趟回来刚才写的没了
    if (cardsOpen) CardsSheet(cards, onDismiss = { cardsOpen = false }, onMore = { cardsOpen = false; persist(); onOpenCards() })
}

// ---------------------------------------------------------------- 卡片：底部抽屉 + 整屏页

/** 抽屉里每类只露前几条：他要的是"随手看一眼"，逐条查走「更多」那一屏。 */
private const val SHEET_ROWS = 3

/**
 * 底部抽屉：每类只露前 [SHEET_ROWS] 条。
 * [onMore] 是「更多」那一颗 —— 由上面那两屏交给壳去开整屏卡片页（抽屉自己不该藏路由）。
 */
@Composable
private fun CardsSheet(cards: List<Card>, onDismiss: () -> Unit, onMore: () -> Unit) {
    // 这一层挂在各屏那圈安全区内衬**外面**（它是盖满整屏的浮层），所以自己得把系统栏那一格扣掉：
    // 不扣的话「更多」那颗正好压在导航条上（上屏量到的就是这个）
    Box(Modifier.fillMaxSize().safeDrawingPadding()) {
        Sheet(Chrome.card, Chrome.ink, Chrome.faint, "卡片 ${cards.size} 张", onDismiss) {
            if (cards.isEmpty()) {
                Text(
                    "还没有卡片。在某一章里按「提取」，把这一章的时间线、人物、技能抽出来。",
                    fontSize = 13.sp, color = Chrome.faint, lineHeight = 20.sp,
                    modifier = Modifier.fillMaxWidth().padding(vertical = 14.dp),
                )
            }
            CardKind.entries.forEach { kind ->
                val rows = cards.filter { it.kind == kind }
                if (rows.isNotEmpty()) {
                    Text(
                        "${kind.label} 共 ${rows.size} 条", fontSize = 12.sp, color = Chrome.faint,
                        modifier = Modifier.fillMaxWidth().padding(top = 12.dp, bottom = 4.dp),
                    )
                    rows.take(SHEET_ROWS).forEach { CardRow(it) }
                }
            }
            if (cards.isNotEmpty()) {
                Box(
                    Modifier.fillMaxWidth().height(46.dp).tap(onMore),
                    contentAlignment = Alignment.Center,
                ) { Text("更多", fontSize = 15.sp, color = Chrome.accent) }
            }
            Spacer(Modifier.height(8.dp))
        }
    }
}

@Composable
private fun CardRow(c: Card) {
    Column(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
        Text(c.name, fontSize = 15.sp, color = Chrome.ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
        if (c.note.isNotEmpty()) {
            Text(c.note, fontSize = 13.sp, color = Chrome.faint, lineHeight = 19.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
        Text("第 ${c.chapter} 章更新", fontSize = 11.sp, color = Chrome.faint, modifier = Modifier.padding(top = 2.dp))
    }
}

/** 「更多」那一屏：整篇的卡片全列，按三类分组。 */
@Composable
fun CreationCardsScreen(id: String, store: CreationStore, onBack: () -> Unit) {
    ApplySystemBars(darkIcons = !Chrome.night)
    BackHandler(onBack = onBack)
    val cards = remember(id) { store.load(id)?.cards ?: emptyList() }
    Column(Modifier.fillMaxSize().background(Chrome.page).safeDrawingPadding()) {
        CreationBar("卡片 ${cards.size} 张", onBack)
        if (cards.isEmpty()) {
            Text("还没有卡片。", fontSize = 13.sp, color = Chrome.faint, modifier = Modifier.padding(16.dp))
        } else {
            Column(Modifier.fillMaxWidth().weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp)) {
                CardKind.entries.forEach { kind ->
                    val rows = cards.filter { it.kind == kind }
                    if (rows.isEmpty()) return@forEach
                    Text(
                        "${kind.label} ${rows.size} 条", fontSize = 13.sp, fontWeight = FontWeight.Medium, color = Chrome.ink,
                        modifier = Modifier.fillMaxWidth().padding(top = 16.dp, bottom = 2.dp),
                    )
                    rows.forEach { CardRow(it); Hairline() }
                }
                Spacer(Modifier.height(20.dp))
            }
        }
    }
}

/** 改名：底部抽屉里一行字 + 保存（和书架上"改名"同一形态，不另开居中弹窗）。 */
@Composable
private fun NameSheet(current: String, onDismiss: () -> Unit, onSubmit: (String) -> Unit) {
    var v by remember { mutableStateOf(current) }
    val keyboard = LocalSoftwareKeyboardController.current
    Box(Modifier.fillMaxSize().safeDrawingPadding().imePadding()) {
        Sheet(Chrome.card, Chrome.ink, Chrome.faint, "改名", onDismiss) {
            Box(Modifier.fillMaxWidth().height(44.dp), contentAlignment = Alignment.CenterStart) {
                BasicTextField(
                    value = v,
                    onValueChange = { v = it.take(24) },
                    singleLine = true,
                    textStyle = TextStyle(fontSize = 17.sp, color = Chrome.ink),
                    cursorBrush = SolidColor(Chrome.accent),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    keyboardActions = androidx.compose.foundation.text.KeyboardActions(onDone = { keyboard?.hide(); onSubmit(v.trim()) }),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            Box(
                Modifier.fillMaxWidth().height(46.dp)
                    .tap { keyboard?.hide(); if (v.trim().isNotEmpty()) onSubmit(v.trim()) },
                contentAlignment = Alignment.Center,
            ) { Text("保存", fontSize = 15.sp, color = Chrome.accent) }
            Spacer(Modifier.height(8.dp))
        }
    }
}
