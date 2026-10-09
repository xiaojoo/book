package com.bookreader.ui

import android.app.Activity
import android.os.Build
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.platform.LocalView

/**
 * 系统栏必须跟着版面走：默认主题会把状态栏画成平台灰 #757575，和我们的底色差 130/255，
 * 那不是我调的色，是没人管的默认值。
 */
@Composable
fun ApplySystemBars(pageColor: Int, barColor: Int, darkIcons: Boolean) {
    val view = LocalView.current
    SideEffect {
        val window = (view.context as? Activity)?.window ?: return@SideEffect
        window.statusBarColor = pageColor
        window.navigationBarColor = pageColor
        // 不关掉这两个"对比度强制"，系统会在栏上盖一层 scrim，换主题就看不出变化
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            window.isStatusBarContrastEnforced = false
            window.isNavigationBarContrastEnforced = false
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            window.insetsController?.let {
                it.setSystemBarsAppearance(
                    if (darkIcons) android.view.WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS or
                        android.view.WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS else 0,
                    android.view.WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS or
                        android.view.WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS,
                )
            }
        }
    }
}
