package com.bookreader

import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.view.KeyEvent
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.IndicationNodeFactory
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.interaction.InteractionSource
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import com.bookreader.data.BookLibrary
import com.bookreader.data.BookSourceStore
import com.bookreader.data.ReaderPrefs
import com.bookreader.core.model.BookMeta
import com.bookreader.ui.AppScaffold
import com.bookreader.ui.BookInfo
import com.bookreader.ui.BookInfoPage
import com.bookreader.ui.NicknameScreen
import com.bookreader.ui.AiSettingsScreen
import com.bookreader.ui.BookSearchScreen
import com.bookreader.ui.FeaturedHomeScreen
import com.bookreader.ui.LibraryScreen
import com.bookreader.ui.Chrome
import com.bookreader.ui.PdfReaderScreen
import com.bookreader.ui.PptReaderScreen
import com.bookreader.ui.ReaderTheme
import com.bookreader.ui.MainTab
import com.bookreader.ui.SearchPage
import com.bookreader.ui.TipHost
import com.bookreader.ui.MarksScreen
import com.bookreader.ui.MeScreen
import com.bookreader.ui.ReaderScreen
import com.bookreader.ui.VolumeKeyHost
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity(), VolumeKeyHost {
    /** 阅读页开着「音量键翻页」时挂上来，离开就置空。返回 false = 这一枪交回系统调音量。 */
    override var volumeHook: ((Boolean) -> Boolean)? = null

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        val hook = volumeHook
        if (hook != null && (keyCode == KeyEvent.KEYCODE_VOLUME_UP || keyCode == KeyEvent.KEYCODE_VOLUME_DOWN)) {
            if (hook(keyCode == KeyEvent.KEYCODE_VOLUME_UP)) return true
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // 官方推荐的 edge-to-edge：两条系统栏透明，底色由各屏自己铺到栏后面（见 ApplySystemBars 的注释）。
        // 两条都给全透明 —— 导航栏那一档默认带一层 scrim，会把我们铺好的页面底色盖灰
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.auto(Color.TRANSPARENT, Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.auto(Color.TRANSPARENT, Color.TRANSPARENT),
        )
        val library = BookLibrary(this)
        val prefs = ReaderPrefs(this)
        // 书籍源是文件、下载任务是进程内的（断点在磁盘上）：各建一次，随 Activity 活一辈子。
        // 不放进 remember —— onCreate 里不是组合作用域，而它们本来也不该随重组重建（下载任务表会整张丢）
        val sources = BookSourceStore(this)
        setContent {
            // 点击反馈全项目都从这颗 Indication 来：换成什么都不画的，涟漪、按住变深、悬停高亮一起没了
            CompositionLocalProvider(LocalIndication provides NoIndication) {
                // 一层气泡铺在全应用最外面：哪一屏的图标按住要报名字，都挂在这一层上画
                TipHost { Root(library, prefs, sources, incoming = intent?.viewUri()) }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
    }
}

/** 文件管理器"用本应用打开"带过来的 uri，读权限由系统授予。 */
private fun Intent.viewUri(): Uri? = if (action == Intent.ACTION_VIEW) data else null

@Composable
private fun Root(
    library: BookLibrary,
    prefs: ReaderPrefs,
    sources: BookSourceStore,
    incoming: Uri?,
) {
    var openBook by rememberSaveable { mutableStateOf<String?>(null) }
    var notice by rememberSaveable { mutableStateOf<String?>(null) }
    var tab by rememberSaveable { mutableStateOf(MainTab.Shelf) }
    // 「任务」「管理」是搜书底下的全屏独立页：状态提在这里，壳才看得见该不该画底栏
    var searchPage by rememberSaveable { mutableStateOf(SearchPage.Search) }
    // 精选搜索框右边那颗「分类」、每张卡右上那颗「更多」开的都是一整屏（和搜书的任务/管理同一族），
    // 底栏要让位。存的是"哪一页"：空 = 精选首页，「#分类」= 分类页，其余就是类目名
    var featuredPage by rememberSaveable { mutableStateOf("") }
    // AI 设置是另开的一屏，不是弹窗（他点名要的形态）；和阅读页一样盖在壳上面
    var aiSettings by rememberSaveable { mutableStateOf(false) }
    // 昵称也改成单独一屏（原先是「我的」页上弹一个 AlertDialog，他点名要换掉）；和 AI 设置同一族
    var editName by rememberSaveable { mutableStateOf(false) }
    // 「本书信息」也是盖在壳上的一整屏（和 AI 设置同一族）。不存盘：它是从某一本书点进去的，
    // 转个屏就退回列表比停在半页信息上更符合直觉，而 rememberSaveable 装这个类还得写 Parcelable
    var info by remember { mutableStateOf<BookInfo?>(null) }
    // 从目录里某一章进来的话，那一章的序号放这里（-1 = 按阅读进度接着读）。不存盘：转个屏就退回列表，
    // 和上面那颗 info 同一族
    var openChapter by remember { mutableIntStateOf(-1) }
    /** 开一本书的唯一入口：顺手把"从第几章"归零，免得上一本的章序号漏到这一本上。 */
    val openAt: (String, Int) -> Unit = { id, chapter -> openChapter = chapter; openBook = id }

    LaunchedEffect(incoming) {
        val uri = incoming ?: return@LaunchedEffect
        val title = library.displayName(uri).substringBeforeLast('.')
        library.list().firstOrNull { it.title == title }?.let {
            // 之前在书架上"移除"过的那本，再导入一次就该回到架上（他点名的语义：移除只动列表、
            // 数据留着）；不清这个标记的话它会一直藏在列表外，只是被打开而已
            if (prefs.isRemoved(it.id)) prefs.setRemoved(it.id, false)
            openAt(it.id, -1)
            return@LaunchedEffect
        }
        val outcome = runCatching { withContext(Dispatchers.IO) { library.import(uri) {} } }
        outcome.onSuccess { openAt(it.id, -1) }
            .onFailure { notice = "打不开这个文件：${it.message ?: it.javaClass.simpleName}" }
        Log.d("ReaderPerf", "view-import ok=${outcome.isSuccess} title=$title")
    }

    // 外壳的夜间跟着阅读页那六档：启动时、换 tab 时、从阅读页回来时各同步一次
    LaunchedEffect(tab, openBook) { Chrome.night = ReaderTheme.of(prefs.themeIndex).night }

    val id = openBook
    val bookInfo = info
    if (bookInfo != null) {
        BookInfoPage(
            info = bookInfo,
            sources = sources.list(),
            library = library,
            onBack = { info = null },
            onOpen = { info = null; openAt(it, -1) },
            onReadChapter = { i -> info = null; openAt(bookInfo.bookId, i) },
        )
    } else if (editName) {
        NicknameScreen(prefs, onBack = { editName = false })
    } else if (aiSettings) {
        AiSettingsScreen(prefs = prefs, onBack = { aiSettings = false })
    } else if (id == null) {
        AppScaffold(tab = tab, onTab = { tab = it; featuredPage = "" }, barVisible = searchPage == SearchPage.Search && !(tab == MainTab.Featured && featuredPage.isNotEmpty())) {
            when (tab) {
                MainTab.Shelf -> LibraryScreen(
                    library = library,
                    prefs = prefs,
                    onOpen = { openAt(it.id, -1) },
                    onInfo = { info = it },
                    notice = notice,
                )

                MainTab.Search -> BookSearchScreen(
                    sources = sources,
                    page = searchPage,
                    onPage = { searchPage = it },
                    onOpen = { openAt(it, -1) },
                    onInfo = { info = it },
                )
                MainTab.Featured -> FeaturedHomeScreen(
                    sources = sources,
                    page = featuredPage,
                    onPage = { featuredPage = it },
                    onInfo = { info = it },
                    onSearch = { tab = MainTab.Search; searchPage = SearchPage.Search },
                )
                MainTab.Marks -> MarksScreen(library, prefs, onOpen = { openAt(it, -1) })
                MainTab.Me -> MeScreen(library, prefs, onOpenAi = { aiSettings = true }, onEditName = { editName = true })
            }
        }
    } else {
        // 整页渲染那一类（pdf / pptx）不进排版引擎，开的是另一条渲染路 —— 路由看的是 kind 而不是"能不能 open"
        val meta = remember(id) { library.list().firstOrNull { it.id == id } }
        if (meta == null) {
            LaunchedEffect(id) { openBook = null }
        } else if (meta.kind == BookMeta.PDF) {
            PdfReaderScreen(bookId = id, title = meta.title, library = library, prefs = prefs, onBack = { openBook = null })
        } else if (meta.kind == BookMeta.PPTX) {
            PptReaderScreen(bookId = id, title = meta.title, library = library, prefs = prefs, onBack = { openBook = null })
        } else {
            ReaderScreen(bookId = id, library = library, prefs = prefs, startChapter = openChapter, onBack = { openBook = null })
        }
    }
}
/** 什么都不画的点击反馈：Material 的 Button 和 clickable 都读这颗，一处换掉全项目生效。 */
private object NoIndication : IndicationNodeFactory {
    override fun create(interactionSource: InteractionSource): Modifier.Node = object : Modifier.Node() {}

    // Indication 按值比较：不重写的话每次组合都会被当成换了一颗，节点会被反复重建
    override fun equals(other: Any?): Boolean = other === this

    override fun hashCode(): Int = System.identityHashCode(this)
}
