package com.bookreader.ui

import android.content.Context
import android.speech.tts.TextToSpeech
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.bookreader.R
import java.util.Locale
import java.util.concurrent.atomic.AtomicReference

/** 长按选段后那一行里的五颗动作。书签那颗会随"这一段有没有书签"换成删除。 */
enum class SelectionAction { Comment, Listen, Dictionary, Correct, Bookmark, Delete }

/**
 * 长按选中一段后浮出来的动作栏：图标在上、文字在下，一行五颗（他给的那张参考图就是这个排法）。
 * 底色跟正文同色，只靠一条发丝线浮起来 —— 和顶/底栏同一套规则。
 */
@Composable
fun SelectionBar(
    theme: ReaderTheme,
    bookmarked: Boolean,
    onAction: (SelectionAction) -> Unit,
    modifier: Modifier = Modifier,
) {
    val items = listOf(
        Triple(R.drawable.ic_sel_comment, "评论", SelectionAction.Comment),
        Triple(R.drawable.ic_sel_audio, "听书", SelectionAction.Listen),
        Triple(R.drawable.ic_sel_dict, "词典", SelectionAction.Dictionary),
        Triple(R.drawable.ic_sel_fix, "纠错", SelectionAction.Correct),
        // 已经加过书签的那一段，第五颗换成"删除"：书签的入口和出口在同一个位置
        if (bookmarked) Triple(R.drawable.ic_sel_del, "删除", SelectionAction.Delete)
        else Triple(R.drawable.ic_sel_mark, "书签", SelectionAction.Bookmark),
    )
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            // 不画边框：靠底色浮起来（bar 那一档就是面板用的那颗，六档各有对应色）
            .background(theme.barColor)
            .height(58.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceEvenly,
    ) {
        items.forEachIndexed { index, (icon, label, action) ->
            if (index > 0) Box(Modifier.width(1.dp).height(26.dp).background(theme.dividerColor))
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(3.dp),
                modifier = Modifier
                    .weight(1f)
                    .clickable { onAction(action) },
            ) {
                Image(
                    painter = painterResource(icon),
                    contentDescription = label,
                    modifier = Modifier.size(21.dp),
                    // 自绘 vector 才控制得住描边和尺寸（平台图标会跟着系统主题变）
                    colorFilter = androidx.compose.ui.graphics.ColorFilter.tint(theme.inkColor),
                )
                Text(
                    label,
                    fontSize = 11.sp,
                    color = theme.inkColor,
                    textAlign = TextAlign.Center,
                )
            }
        }
    }
}

/**
 * 动作栏指向段落的那颗三角：8dp 高、底 14dp 宽，**钉在动作栏的水平正中**。
 * 和动作栏同底色、不描边，接在一起看上去是同一块。
 */
@Composable
internal fun SelectionArrow(barColor: Color, down: Boolean) {
    val density = LocalDensity.current
    Canvas(Modifier.fillMaxWidth().height(8.dp)) {
        val half = with(density) { 7.dp.toPx() }
        val x = size.width / 2f
        val path = Path().apply {
            if (down) {
                moveTo(x - half, 0f)
                lineTo(x + half, 0f)
                lineTo(x, size.height)
            } else {
                moveTo(x, 0f)
                lineTo(x + half, size.height)
                lineTo(x - half, size.height)
            }
            close()
        }
        drawPath(path, barColor)
    }
}

/**
 * 系统 TTS 的一层薄壳：初始化是异步的，所以第一句可能来得比 speak 晚，先存着。
 * 这台机没装中文语音引擎时不报错，只是不出声 —— 调用方要给用户一句回话。
 */
class ParagraphVoice(context: Context) {

    /** 0 = 还在初始化，1 = 可以用，-1 = 这台机没有可用的引擎。 */
    var state = 0
        private set

    private val pending = AtomicReference<String?>(null)
    private var engine: TextToSpeech? = null

    /** @return 真的开口了没有。false = 还没就绪或这台机根本没有语音引擎。 */
    fun speak(text: String): Boolean {
        if (text.isBlank() || state != 1) return false
        engine?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "paragraph")
        return true
    }

    fun stop() {
        pending.set(null)
        engine?.stop()
    }

    fun close() {
        engine?.stop()
        engine?.shutdown()
        engine = null
    }

    init {
        engine = TextToSpeech(context.applicationContext) { status ->
            state = if (status == TextToSpeech.SUCCESS) 1 else -1
            if (state == 1) {
                engine?.let { e ->
                    e.language = Locale.SIMPLIFIED_CHINESE
                    pending.getAndSet(null)?.let { speak(it) }
                }
            }
        }
    }
}
