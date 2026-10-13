package com.bookreader.core.model

import kotlin.math.roundToInt

data class BookMeta(
    val id: String,
    val title: String,
    val author: String,
    val totalChars: Long,
    val chunkChars: Int,
    val chunkCount: Int,
    val chapterCount: Int,
    val contentBytes: Long,
    val sourceBytes: Long,
    val sourceCharset: String,
    /** txt = 抽过正文、进排版引擎的书；pdf / pptx = 原件整页渲染，没有 chunk/章节索引。 */
    val kind: String = TXT,
    /** 只有整页渲染那一类有：版面页数（pdf 的页、pptx 的幻灯片数）。排版引擎的书恒为 0（它数的是章）。 */
    val pages: Int = 0,
) {
    companion object {
        const val TXT = "txt"
        const val PDF = "pdf"
        const val PPTX = "pptx"

        /** 走"一屏一页、保留原版面"那条路的种类 —— 它们都没有字号/行距那套版式控制。 */
        val WHOLE_PAGE = setOf(PDF, PPTX)

        /**
         * 漫画：一章 = 一排图。它和 pdf/pptx 一样不进排版引擎，但它**有章** ——
         * 所以 `chapterCount` 对它是"目录有几章"（真的），`pages` 对它是"一共多少张图"。
         */
        const val COMIC = "comic"

        /** 清单里没有 chunk/章索引那一套的几类：读清单时对那几个键给默认值，别把一本好书判成损坏。 */
        val NO_TEXT_INDEX = setOf(PDF, PPTX, COMIC)
    }
}

/** 章节只是"绝对字符偏移上的一段"，正文不在这里。 */
data class Chapter(val index: Int, val title: String, val startOffset: Long, val endOffset: Long) {
    val charCount: Long get() = endOffset - startOffset
}

/**
 * 阅读位置。absoluteOffset 是唯一权威：字体、行距、横竖屏、边距全变了也能找回来。
 * 页码是 (absoluteOffset + 当前 PageStyle) 的派生值，所以不进这个结构。
 */
data class BookPosition(
    val chapterIndex: Int,
    val absoluteOffset: Long,
    val paragraphId: Int,
    val charOffset: Int,
)

data class PageStyle(
    val fontSizePx: Float,
    val lineHeightMultiplier: Float,
    val contentWidthPx: Float,
    val contentHeightPx: Float,
    val paragraphIndentPx: Float,
    val letterSpacingPx: Float = 0f,
    /**
     * 顶缝：正文第一行离状态栏底边多远。算在 [contentHeightPx] 之内（不是画的时候往下推），
     * 所以少的是这一页的行数。
     */
    val topInsetPx: Float = 0f,
    /** 底缝：和顶缝同一档，正文最后一行离屏幕内容区底边多远。 */
    val bottomInsetPx: Float = 0f,
    /** 换了字体必须让它变，否则 id 相同、分页器不会重建测量器。 */
    val fontTag: String = "",
) {
    /**
     * 行网格取整像素。滚动量只能存整数（ScrollState.value 是 Int），浮点行高在"往前补一段"
     * 那一刻被 round 掉半格，整块文字就比按页那一档低半个像素
     * —— 量过：17sp 下简洁切上下，26 行里 14 行的墨迹顶边差 1px，看着像整屏重排了一遍。
     * 排版和绘制两头都用这一个数，才没有第二条网格可对不齐。
     */
    val lineHeightPx: Float = (fontSizePx * lineHeightMultiplier).roundToInt().toFloat()

    val id: String = buildString {
        append(fontTag).append('|')
        append(fontSizePx.toInt()).append('-')
        append((lineHeightMultiplier * 100).toInt()).append('-')
        append(contentWidthPx.toInt()).append('-')
        append(contentHeightPx.toInt()).append('-')
        append(paragraphIndentPx.toInt()).append('-')
        append((letterSpacingPx * 100).toInt()).append('-')
        append(topInsetPx.toInt()).append('/')
        append(bottomInsetPx.toInt())
    }
}

data class TextLine(
    val start: Long,
    val end: Long,
    val indentPx: Float,
    val topPx: Float,
    val widthPx: Float,
    /** 两端对齐要补的字距（每字后加多少 px）。0 = 不拉伸（段末行、单字行）。 */
    val trackingPx: Float = 0f,
    /** 章节标题行的字号倍数（1 = 正文）。标题顶格、加粗、按这个倍数放大。 */
    val titleScale: Float = 1f,
    /** 等宽字体那一档：md 的代码块/行内码。排版要按等宽量，画要换字体。 */
    val mono: Boolean = false,
    /** 引用块：画的时候整段变灰并在左缘补一条竖线。 */
    val quote: Boolean = false,
) {
    val chars: Int get() = (end - start).toInt()
}

/** 一屏正文。endOffset 是下一页的起始位置，所以相邻两页首尾相接、不重叠。 */
class Page(
    val startOffset: Long,
    val endOffset: Long,
    val lines: List<TextLine>,
    val chapterIndex: Int,
) {
    override fun toString(): String = "Page(start=$startOffset,end=$endOffset,lines=${lines.size},ch=$chapterIndex)"
}
