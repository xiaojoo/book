package com.bookreader.ui

import android.app.Activity
import android.os.Build
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.core.graphics.Insets
import androidx.compose.ui.unit.IntSize
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat

/**
 * 系统栏必须跟着版面走：默认主题会把状态栏画成平台灰 #757575，和我们的底色差 130/255，
 * 那不是我调的色，是没人管的默认值。
 *
 * **官方推荐那套**（`Window.statusBarColor` / `navigationBarColor` 在 targetSdk 35 起已经是**空操作**，
 * 而且都标了 deprecated）：窗口 edge-to-edge（在 `MainActivity` 里 `enableEdgeToEdge()`），
 * 系统栏本身透明，**底色由每一屏自己铺到栏后面** —— 就是各屏根那句
 * `.background(页面底色).safeDrawingPadding()`：先铺满整块窗口，再让内容让开安全区。
 * 顺序不能反，反了底色就只铺到安全区里，栏背后露的是窗口背景。
 *
 * 所以这里只剩两件事，都不涉及颜色：
 *  1. **图标明暗**走 `WindowInsetsControllerCompat`。原来那段是 `if (SDK_INT >= R)` 才调
 *     `setSystemBarsAppearance`，于是 API 26–29 这一档根本没设过图标（minSdk 就是 26）；
 *     compat 这一个调用从 23 起都有效，版本分叉一起去掉。
 *  2. 关掉导航栏那层**对比度 scrim**：不关的话系统会在我们铺好的底色上再盖一层灰，
 *     换主题看不出变化。`isStatusBarContrastEnforced` 那个同名开关已废弃、且状态栏这一侧
 *     透明样式下本来就不加 scrim，所以不再调它。
 *
 * `darkIcons` 沿用调用方的叫法，含义是平台那个 flag 的名字："栏背景是浅的"（= 图标要画成深色）。
 */
@Composable
fun ApplySystemBars(darkIcons: Boolean, immersive: Boolean = false) {
    val view = LocalView.current
    // 只在**真的换了档**那一刻动系统栏：SideEffect 每次重组都会跑，无条件 hide()/show()
    // 本身就是叠在一起的动画请求（他看到的"闪烁"有一半是这个）
    var applied by remember { mutableStateOf<Boolean?>(null) }
    // 我们刚发的是藏还是还 —— 只有这一对才认领动画时长：键盘那种 IME 动画也走这个回调，
    // 不区分的话菜单会被键盘的时长带跑
    var pending by remember { mutableStateOf<Boolean?>(null) }
    DisposableEffect(view) {
        androidx.core.view.ViewCompat.setWindowInsetsAnimationCallback(
            view,
            object : androidx.core.view.WindowInsetsAnimationCompat.Callback(
                androidx.core.view.WindowInsetsAnimationCompat.Callback.DISPATCH_MODE_STOP
            ) {
                override fun onPrepare(animation: androidx.core.view.WindowInsetsAnimationCompat) {
                    val hiding = pending
                    if (hiding != null) {
                        val ms = animation.durationMillis.coerceIn(0L, 3000L).toInt()
                        if (hiding) Chrome.barHideMs = ms else Chrome.barShowMs = ms
                    }
                }

                override fun onEnd(animation: androidx.core.view.WindowInsetsAnimationCompat) {
                    pending = null
                }

                // 这一条只是必须实现（compat 里它是抽象的）：**不**拿它给的数去跟 ——
                // 这台机上藏那一向 `interpolatedFraction` 整段是 0.0、insets 一路停在 84/130，
                // 到最后一条回调才跳到 0，跟着它走等于憋满整段再啪一下（数字见 ReaderScreen 那段）
                override fun onProgress(
                    insets: androidx.core.view.WindowInsetsCompat,
                    runningAnimations: MutableList<androidx.core.view.WindowInsetsAnimationCompat>,
                ) = insets
            },
        )
        onDispose { androidx.core.view.ViewCompat.setWindowInsetsAnimationCallback(view, null) }
    }
    SideEffect {
        val window = (view.context as? Activity)?.window ?: return@SideEffect
        WindowInsetsControllerCompat(window, view).apply {
            isAppearanceLightStatusBars = darkIcons
            isAppearanceLightNavigationBars = darkIcons
            // 藏/还系统栏和上面两行必须走同一个主人：另起一处 hide() 会被这里的重组顶回去
            if (applied != immersive) {
                applied = immersive
                pending = immersive
                systemBarsBehavior = if (immersive) {
                    WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                } else {
                    WindowInsetsControllerCompat.BEHAVIOR_DEFAULT
                }
                if (immersive) hide(WindowInsetsCompat.Type.systemBars())
                else show(WindowInsetsCompat.Type.systemBars())
            }
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            window.isNavigationBarContrastEnforced = false
        }
    }
}

/**
 * 让开系统栏那一条，但用的是**"藏了栏也不变"**的那一份 insets（`insetsIgnoringVisibility`）。
 *
 * `safeDrawingPadding()` 读的是当前 insets —— 栏一藏就变 0，正文会整块往上/下挪、分页跟着重排
 * （同一章从 6 页变 5 页），那就是他报的"闪烁"。全屏那一态要的是"背景铺满、正文一个字都不动"，
 * 所以正文那一层用这里，藏与不藏给出**同一个数**。
 */
@Composable
internal fun Modifier.fixedBarsPadding(): Modifier {
    val view = LocalView.current
    val d = LocalDensity.current
    val w = view.rootWindowInsets ?: return this.safeDrawingPadding()
    val i = WindowInsetsCompat.toWindowInsetsCompat(w).getInsetsIgnoringVisibility(
        WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
    )
    return this.padding(
        start = with(d) { i.left.toDp() },
        top = with(d) { i.top.toDp() },
        end = with(d) { i.right.toDp() },
        bottom = with(d) { i.bottom.toDp() },
    )
}

/** 系统栏那两条的高度（同一份"藏了也不变"的值）：盖条要按它画，切来切去才不抖。 */
@Composable
internal fun systemBarHeights(): PaddingValues {
    val view = LocalView.current
    val d = LocalDensity.current
    val w = view.rootWindowInsets ?: return PaddingValues()
    val i = WindowInsetsCompat.toWindowInsetsCompat(w).getInsetsIgnoringVisibility(
        WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
    )
    return PaddingValues(
        start = with(d) { i.left.toDp() },
        top = with(d) { i.top.toDp() },
        end = with(d) { i.right.toDp() },
        bottom = with(d) { i.bottom.toDp() },
    )
}

/**
 * 正文可用尺寸（像素）—— 官方推荐的来源：**窗口自己的容器大小**让开系统栏，
 * 不再读 `Configuration.screenWidthDp / screenHeightDp`。
 *
 * **为什么不能继续读 Configuration**：那两个数在多窗口 / 折叠屏 / 分屏切换时会滞后，
 * 而 edge-to-edge 之后"可用高度"本来就该由窗口自己的 insets 算 —— 目标 SDK 35+ 上系统栏那两条
 * 已经归应用自己管了。真读错的表现是：分页按比实际高的高度排字，最后一行钻到导航条底下。
 *
 * 阅读页里这只是**首屏那一次的种子**：正文框第一次布局后 `onSizeChanged` 报回来的 `viewport`
 * 会接管（`PageStyle.id` 里含 `contentWidthPx/contentHeightPx`，所以尺寸一变 `applyStyle` 就跑）。
 * pdf / pptx 没有排版器，这一个数就是渲染宽度本身。
 */
@Composable
fun contentSizePx(): IntSize {
    val container = LocalWindowInfo.current.containerSize
    val view = LocalView.current
    // 跟着容器大小重算：转屏 / 分屏改尺寸时系统栏那一条也会跟着变
    val bars = remember(container) {
        val w = view.rootWindowInsets
        if (w == null) Insets.NONE
        else WindowInsetsCompat.toWindowInsetsCompat(w).getInsets(WindowInsetsCompat.Type.systemBars())
    }
    return IntSize(
        (container.width - bars.left - bars.right).coerceAtLeast(1),
        (container.height - bars.top - bars.bottom).coerceAtLeast(1),
    )
}
