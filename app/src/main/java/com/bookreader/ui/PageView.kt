package com.bookreader.ui

import android.graphics.Typeface
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.nativeCanvas
import com.bookreader.core.model.Page
import com.bookreader.core.model.PageStyle

/**
 * 一页正文。每行一次 drawText，整页就是 lines.size 次调用，不给每行建 Compose 节点。
 * 页文本是一次 slice 出来的连续串，所以行下标只要减掉页起始偏移。
 *
 * 两端对齐的字距在 [drawLines] 里换算成 letterSpacing，翻页和滚动两条路共用同一份画法。
 */
@Composable
fun PageView(
    page: Page?,
    pageText: String,
    pageStart: Long,
    style: PageStyle,
    theme: ReaderTheme,
    modifier: Modifier = Modifier,
    typeface: Typeface? = null,
    selection: LongRange? = null,
    selectionColor: Int = 0,
    marks: List<LongRange> = emptyList(),
    markColor: Int = 0,
    styles: com.bookreader.core.model.StyleIndex = com.bookreader.core.model.StyleIndex.EMPTY,
    // 「皮肤管理」上传了背景图时正文这一层要透（图压在下面），所以纸色不能写死在这一层
    paper: androidx.compose.ui.graphics.Color = theme.pageColor,
) {
    val textSizePx = style.fontSizePx
    val paint = readerPaint(theme, textSizePx, typeface)
    Canvas(modifier.background(paper)) {
        if (page == null || pageText.isEmpty()) return@Canvas
        drawLines(
            canvas = drawContext.canvas.nativeCanvas,
            lines = page.lines,
            text = pageText,
            pageStart = pageStart,
            baseY = 0f,
            paint = paint,
            ascent = -paint.fontMetrics.ascent,
            textSizePx = textSizePx,
            highlight = selection,
            highlightColor = selectionColor,
            lineHeightPx = style.lineHeightPx,
            marks = marks,
            markColor = markColor,
            styles = styles,
            quoteColor = quoteArgb(theme),
        )
    }
}
