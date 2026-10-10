package com.bookreader

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.KeyEvent
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.IndicationNodeFactory
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.interaction.InteractionSource
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import com.bookreader.data.BookLibrary
import com.bookreader.data.ReaderPrefs
import com.bookreader.core.model.BookMeta
import com.bookreader.ui.AppScaffold
import com.bookreader.ui.AiSettingsScreen
import com.bookreader.ui.FeaturedScreen
import com.bookreader.ui.LibraryScreen
import com.bookreader.ui.Chrome
import com.bookreader.ui.PdfReaderScreen
import com.bookreader.ui.PptReaderScreen
import com.bookreader.ui.ReaderTheme
import com.bookreader.ui.MainTab
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
        val library = BookLibrary(this)
        val prefs = ReaderPrefs(this)
        setContent {
            // 点击反馈全项目都从这颗 Indication 来：换成什么都不画的，涟漪、按住变深、悬停高亮一起没了
            CompositionLocalProvider(LocalIndication provides NoIndication) {
                Root(library, prefs, incoming = intent?.viewUri())
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
private fun Root(library: BookLibrary, prefs: ReaderPrefs, incoming: Uri?) {
    var openBook by rememberSaveable { mutableStateOf<String?>(null) }
    var notice by rememberSaveable { mutableStateOf<String?>(null) }
    var tab by rememberSaveable { mutableStateOf(MainTab.Shelf) }
    // AI 设置是另开的一屏，不是弹窗（他点名要的形态）；和阅读页一样盖在壳上面
    var aiSettings by rememberSaveable { mutableStateOf(false) }

    LaunchedEffect(incoming) {
        val uri = incoming ?: return@LaunchedEffect
        val title = library.displayName(uri).substringBeforeLast('.')
        library.list().firstOrNull { it.title == title }?.let {
            openBook = it.id
            return@LaunchedEffect
        }
        val outcome = runCatching { withContext(Dispatchers.IO) { library.import(uri) {} } }
        outcome.onSuccess { openBook = it.id }
            .onFailure { notice = "打不开这个文件：${it.message ?: it.javaClass.simpleName}" }
        Log.d("ReaderPerf", "view-import ok=${outcome.isSuccess} title=$title")
    }

    // 外壳的夜间跟着阅读页那六档：启动时、换 tab 时、从阅读页回来时各同步一次
    LaunchedEffect(tab, openBook) { Chrome.night = ReaderTheme.of(prefs.themeIndex).night }

    val id = openBook
    if (aiSettings) {
        AiSettingsScreen(prefs = prefs, onBack = { aiSettings = false })
    } else if (id == null) {
        AppScaffold(tab = tab, onTab = { tab = it }) {
            when (tab) {
                MainTab.Shelf -> LibraryScreen(
                    library = library,
                    prefs = prefs,
                    onOpen = { openBook = it.id },
                    notice = notice,
                )

                MainTab.Featured -> FeaturedScreen()
                MainTab.Marks -> MarksScreen(library, prefs, onOpen = { openBook = it })
                MainTab.Me -> MeScreen(library, prefs, onOpenAi = { aiSettings = true })
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
            ReaderScreen(bookId = id, library = library, prefs = prefs, onBack = { openBook = null })
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
