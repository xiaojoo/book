package com.bookreader.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.bookreader.R
import com.bookreader.core.ai.AiProtocol
import com.bookreader.core.ai.AiSpeech
import com.bookreader.data.AiClient
import com.bookreader.data.AiError
import com.bookreader.data.AiVoiceCache
import com.bookreader.data.AiVoiceClient
import com.bookreader.data.AiVoicePlayer
import com.bookreader.data.ReaderPrefs
import com.bookreader.data.SecretStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * AI 服务设置：**另开一屏填，不用弹窗**（他点名的形态就是他那张「昵称」页 ——
 * 顶栏 `‹ 标题 …… 保存`，正文是输入区，底下几行灰点规则）。
 *
 * 这一屏只管三件事：往哪发（地址）、发哪个模型、凭据（秘钥）。真正用它的只有阅读页长按
 * 动作栏里那颗「词典」；听书走系统语音引擎，不经过这里，也不发任何文字到网上。
 */
@Composable
fun AiSettingsScreen(prefs: ReaderPrefs, onBack: () -> Unit) {
    var base by remember { mutableStateOf(prefs.aiBaseUrl) }
    var model by remember { mutableStateOf(prefs.aiModel) }
    // 已存过秘钥后这一格是空的：留空 = 不改，我们从不把秘钥回显到屏幕上
    var key by remember { mutableStateOf("") }
    var showKey by remember { mutableStateOf(false) }
    var hasKey by remember { mutableStateOf(prefs.aiKeyCipher.isNotEmpty()) }
    var note by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    // 试听自己那颗按钮的状态：原来它和"试一下"共用 busy，于是一按一个、两颗同时变灰变字，
    // 看着就像"点一个把两件事都执行了"
    var vBusy by remember { mutableStateOf(false) }
    // 语音那一档：和词典分开填，因为它们通常不是一家，秘钥也不同
    var vKind by remember { mutableStateOf(runCatching { AiSpeech.Kind.valueOf(prefs.aiVoiceKind) }.getOrDefault(AiSpeech.Kind.OpenAi)) }
    var vBase by remember { mutableStateOf(prefs.aiVoiceBaseUrl) }
    var vModel by remember { mutableStateOf(prefs.aiVoiceModel) }
    var vVoice by remember { mutableStateOf(prefs.aiVoiceId) }
    // 按服务商各存一列，所以切服务商要重读这一格：不重读的话，把 A 家那列原样存进 B 家键里
    var vChoices by remember(vKind) { mutableStateOf(prefs.aiVoiceChoices(vKind.name)) }
    val voiceList = AiSpeech.voices(vChoices)
    var voiceOpen by remember { mutableStateOf(false) }
    var pulling by remember { mutableStateOf(false) }
    // 三颗按钮各自的"成了"状态：成了变绿，请求中变灰，失败才在底下写字
    // 绿 = "这一套参数答过"。key 挂在各栏上：改过地址/模型/音色就自己退回红色，不留一个过期的对勾
    var dictOk by remember(base, model, hasKey) { mutableStateOf(false) }
    var voiceOk by remember(vBase, vModel, vVoice) { mutableStateOf(false) }
    var pullOk by remember(vBase, vKind) { mutableStateOf(false) }
    var pullNote by remember { mutableStateOf<String?>(null) }
    var vKey by remember { mutableStateOf("") }
    var vHasKey by remember { mutableStateOf(prefs.aiVoiceKeyCipher.isNotEmpty()) }
    var showVKey by remember { mutableStateOf(false) }
    var vPlaying by remember { mutableStateOf(false) }
    val context = LocalContext.current
    val player = remember { AiVoicePlayer() }
    DisposableEffect(player) { onDispose { player.close() } }
    val scope = rememberCoroutineScope()
    val keyboard = LocalSoftwareKeyboardController.current

    ApplySystemBars(darkIcons = !Chrome.night)
    BackHandler(onBack = onBack)

    /** @return 有没有存下去。地址填错时不写 prefs，也不让"试一下"带着错地址去发 */
    fun save(): Boolean {
        if (base.isNotBlank()) {
            runCatching { AiProtocol.chatEndpoint(base) }.onFailure {
                note = it.message
                return false
            }
        }
        if (vBase.isNotBlank()) {
            runCatching { AiSpeech.endpoint(vBase, vKind) }.onFailure {
                note = it.message
                return false
            }
        }
        if (key.isNotBlank()) {
            val blob = SecretStore.encrypt(key.trim())
            if (blob == null) {
                note = "这台机的 Keystore 用不了，秘钥存不下"
                return false
            }
            prefs.aiKeyCipher = blob
            hasKey = true
            key = ""
        }
        if (vKey.isNotBlank()) {
            val blob = SecretStore.encrypt(vKey.trim())
            if (blob == null) {
                note = "这台机的 Keystore 用不了，语音秘钥存不下"
                return false
            }
            prefs.aiVoiceKeyCipher = blob
            vHasKey = true
            vKey = ""
        }
        prefs.aiBaseUrl = base
        prefs.aiModel = model
        prefs.aiVoiceKind = vKind.name
        prefs.aiVoiceBaseUrl = vBase
        prefs.aiVoiceModel = vModel
        prefs.aiVoiceId = vVoice
        prefs.setAiVoiceChoices(vKind.name, vChoices)
        note = "已保存"
        return true
    }

    fun test() {
        keyboard?.hide()
        if (!save()) return
        if (!hasKey) {
            note = "已保存，但还没有秘钥 —— 填了才能试"
            return
        }
        busy = true
        dictOk = false
        // 请求中不再往下面写一行字：按钮变灰、字变"连接中"已经说了同一件事
        note = null
        scope.launch {
            val plain = withContext(Dispatchers.IO) { SecretStore.decrypt(prefs.aiKeyCipher) }
            val outcome = withContext(Dispatchers.IO) {
                runCatching {
                    AiClient.chat(prefs.aiBaseUrl, plain ?: "", prefs.aiModel, "只回两个字：通了", "ping")
                }
            }
            busy = false
            // 成功就不留字（他要的）；失败必须留 —— 那行是唯一能看见服务端说了什么的地方
            outcome.fold(
                onSuccess = { dictOk = true; note = null },
                onFailure = {
                    dictOk = false
                    note = if (it is AiError) it.message ?: "失败" else "失败：${it.javaClass.simpleName}"
                },
            )
        }
    }

    fun stopVoice() {
        player.stop()
        vPlaying = false
        note = null
    }

    /** 拉一次音色表填进「可选音色」。拉不到就把原因写在同一行，手抄那条路永远留着。 */
    fun pullVoices() {
        keyboard?.hide()
        pulling = true
        pullOk = false
        pullNote = null
        scope.launch {
            val plain = withContext(Dispatchers.IO) { SecretStore.decrypt(prefs.aiVoiceKeyCipher) }
            val outcome = withContext(Dispatchers.IO) {
                runCatching { AiVoiceClient.listVoices(vBase, plain ?: "", vKind) }
            }
            pulling = false
            outcome.fold(
                onSuccess = { found ->
                    vChoices = (AiSpeech.voices(vChoices) + found).distinct().joinToString(", ")
                    prefs.setAiVoiceChoices(vKind.name, vChoices)
                    pullOk = true
                    // 拉到就不写字：那一列本身就填好了，结果写在脸上
                    pullNote = null
                },
                onFailure = {
                    pullOk = false
                    pullNote = if (it is AiError) it.message ?: "拉取失败" else "拉取失败：${it.javaClass.simpleName}"
                },
            )
        }
    }

    /** 合成一句固定的试听词 —— 不念书里的内容，免得试听本身花一次钱还查不到是什么 */
    fun tryVoice() {
        keyboard?.hide()
        if (!save()) return
        if (!vHasKey) {
            note = "已保存，但还没有语音秘钥 —— 填了才能试"
            return
        }
        vBusy = true
        voiceOk = false
        note = null
        scope.launch {
            val plain = withContext(Dispatchers.IO) { SecretStore.decrypt(prefs.aiVoiceKeyCipher) }
            val line = "这一句是用来试听音色的。"
            val outcome = withContext(Dispatchers.IO) {
                runCatching {
                    val bytes = AiVoiceClient.synthesize(
                        prefs.aiVoiceBaseUrl, plain ?: "", vKind, prefs.aiVoiceModel, prefs.aiVoiceId, line,
                    )
                    AiVoiceCache.fileFor(context, vKind, prefs.aiVoiceModel, prefs.aiVoiceId, line)
                        // 和听书页同一条：wav 头里的长度要写成真实字节数，不然时长报 745:39、进度条钉在 0
                        .apply { writeBytes(AiSpeech.wavSizesFixed(bytes)) }
                }
            }
            vBusy = false
            outcome.fold(
                onSuccess = { file ->
                    val started = player.play(file) { vPlaying = false }
                    vPlaying = started
                    voiceOk = started
                    note = if (started) null else "拿到了 ${file.length()} 字节音频，但本机播不出来"
                },
                onFailure = {
                    voiceOk = false
                    note = if (it is AiError) it.message ?: "失败" else "失败：${it.javaClass.simpleName}"
                },
            )
        }
    }

    // 底色先铺满整块窗口（含状态栏/导航栏背后），再让内容让开安全区 —— 顺序反了栏背后会露窗口背景
    Box(Modifier.fillMaxSize().background(Chrome.page).safeDrawingPadding()) {
        Column(Modifier.fillMaxSize()) {
            Row(
                modifier = Modifier.fillMaxWidth().height(48.dp).padding(start = 8.dp, end = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(modifier = Modifier.size(40.dp).tap(onBack), contentAlignment = Alignment.Center) {
                    Icon(
                        painter = painterResource(R.drawable.ic_back),
                        contentDescription = "返回",
                        tint = Chrome.ink,
                        modifier = Modifier.size(20.dp),
                    )
                }
                Text("AI 服务", fontSize = 19.sp, fontWeight = FontWeight.Medium, color = Chrome.ink)
                Spacer(Modifier.weight(1f))
                Text(
                    "保存",
                    fontSize = 17.sp,
                    fontWeight = FontWeight.Medium,
                    color = Chrome.ink,
                    modifier = Modifier.tap { keyboard?.hide(); save() }.padding(vertical = 8.dp, horizontal = 6.dp),
                )
            }

            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp),
            ) {
                Spacer(Modifier.height(10.dp))
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(Chrome.card),
                ) {
                    FieldRow("API 地址", "https://api.deepseek.com", base, { base = it }, KeyboardType.Uri, false, null, wrap = true)
                    Divider()
                    FieldRow("模型名", "gpt-4o-mini / qwen2.5:7b", model, { model = it }, KeyboardType.Text, false, null)
                    Divider()
                    FieldRow(
                        "秘钥",
                        if (hasKey) "已保存 · 留空表示不改" else "sk-…",
                        key,
                        { key = it },
                        KeyboardType.Password,
                        showKey,
                        { showKey = !showKey },
                    )
                }

                // 明文这一条只在真的填了 http 时出现：常驻的警告等于没有警告
                if (AiProtocol.isCleartext(base)) {
                    Text(
                        "这条地址是 http://，秘钥和你选中的文字都会明文走网络",
                        fontSize = 12.sp,
                        color = Chrome.danger,
                        modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                    )
                }
                Resolved("词典将请求", runCatching { AiProtocol.chatEndpoint(base) }.getOrElse { "地址还没填对：${it.message}" })

                Row(modifier = Modifier.fillMaxWidth().padding(top = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                    ActionPill(
                        bg = when {
                            busy -> BUSY_GREY
                            dictOk -> OK_GREEN
                            else -> Chrome.accent
                        },
                        fg = Chrome.card,
                        enabled = !busy,
                        onClick = { test() },
                        text = if (busy) "连接中" else "试一下",
                    )
                    Spacer(Modifier.size(10.dp))
                    Text("发一条最小对话请求，验词典那档通不通", fontSize = 12.sp, color = Chrome.faint, modifier = Modifier.weight(1f))
                }

                Spacer(Modifier.height(24.dp))
                Text("语音（听书）", fontSize = 13.sp, color = Chrome.faint, modifier = Modifier.padding(bottom = 8.dp))
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(Chrome.card),
                ) {
                    // 服务商三选一：三家的字段名和字段层都不同（input 是字符串 vs input 是对象），选错档等于发错形状
                    Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp)) {
                        AiSpeech.Kind.entries.forEachIndexed { i, k ->
                            WholeChip(
                                AiSpeech.label(k),
                                selected = vKind == k,
                                radius = ACTION_RADIUS,
                                modifier = Modifier.weight(1f).height(32.dp),
                            ) { vKind = k }
                            if (i < AiSpeech.Kind.entries.lastIndex) Spacer(Modifier.size(8.dp))
                        }
                    }
                    Divider()
                    FieldRow(
                        "语音地址",
                        when (vKind) {
                            AiSpeech.Kind.OpenAi -> "https://你的地址/v1"
                            AiSpeech.Kind.MiniMax -> "https://api.minimax.cn"
                            AiSpeech.Kind.QwenHttp -> "https://业务空间.cn-beijing.maas.aliyuncs.com"
                        },
                        vBase, { vBase = it }, KeyboardType.Uri, false, null, wrap = true,
                    )
                    Divider()
                    FieldRow(
                        "语音模型",
                        when (vKind) {
                            AiSpeech.Kind.OpenAi -> "tts-1 / gpt-4o-mini-tts"
                            AiSpeech.Kind.MiniMax -> "speech-02-hd"
                            AiSpeech.Kind.QwenHttp -> "qwen-audio-3.1-tts-flash"
                        },
                        vModel, { vModel = it }, KeyboardType.Text, false, null,
                    )
                    Divider()
                    // 音色这一格不再让手敲：敲错一个不在模型表里的名字，代价是一次 400。
                    // 点它开底部抽屉，从下面「可选音色」那一列里挑（当前值一定在列里，哪怕还没抄进去）
                    PickRow("音色", if (vVoice.isBlank()) "没选" else vVoice) { voiceOpen = true }
                    Divider()
                    // 音色名没有能查的列表接口，只能把他这个模型支持的那几个抄进来：
                    // 抄进来之后，听书页「更多 → 朗读音色」就是选，不是让他再敲一遍名字
                    FieldRow(
                        "可选音色",
                        when (vKind) {
                            AiSpeech.Kind.OpenAi -> "alloy, nova, onyx"
                            AiSpeech.Kind.MiniMax -> "male-qn-qingse, female-shaonv"
                            AiSpeech.Kind.QwenHttp -> "longanhuan_v3.1, longmiao_v3.1"
                        },
                        vChoices, { vChoices = it }, KeyboardType.Text, false, null, wrap = true,
                    )
                    Divider()
                    // 「拉一下」只在有两家能用：MiniMax 没有查音色的接口，那颗就置灰并说出为什么
                    // （置灰不消失 —— 藏起来别人会以为这里本来就没有这个能力）
                    Divider()
                    FieldRow(
                        "语音秘钥",
                        if (vHasKey) "已保存 · 留空表示不改" else "sk-…",
                        vKey,
                        { vKey = it },
                        KeyboardType.Password,
                        showVKey,
                        { showVKey = !showVKey },
                    )
                }

                // 这颗原来在卡片里面，于是它的左沿比另外两颗按钮低 14dp（= 39px）—— 三颗按钮
                // 一样宽也一样不齐。挪到页面这一层，和「试一下」「试听」同一条左线
                Row(
                    modifier = Modifier.fillMaxWidth().height(46.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    val canPull = runCatching { AiSpeech.voicesEndpoint(vBase, vKind) }.getOrNull() != null
                    ActionPill(
                        bg = when {
                            pulling -> BUSY_GREY
                            pullOk -> OK_GREEN
                            canPull -> Chrome.accent
                            else -> Chrome.chip
                        },
                        fg = if (canPull || pulling || pullOk) Chrome.card else Chrome.faint,
                        enabled = canPull && !pulling,
                        onClick = { pullVoices() },
                        text = if (pulling) "正在拉" else "拉音色列表",
                    )
                    Spacer(Modifier.size(10.dp))
                    Text(
                        pullNote
                            ?: if (vKind == AiSpeech.Kind.MiniMax) "MiniMax 没有查音色的接口，只能手抄「可选音色」那一格"
                            else "拉到的会填进上面那一格；手抄那条一直留着",
                        fontSize = 12.sp,
                        color = Chrome.faint,
                        modifier = Modifier.weight(1f),
                    )
                }

                if (AiProtocol.isCleartext(vBase)) {
                    Text(
                        "这条语音地址是 http://，正文和秘钥都会明文走网络",
                        fontSize = 12.sp,
                        color = Chrome.danger,
                        modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                    )
                }
                // 三家补的是三条不同路径，选错服务商的表现是 404 而不是"哪家说错了话" ——
                // 所以把补完的完整地址直接摊开给他看
                Resolved(
                    "听书将请求",
                    runCatching { AiSpeech.endpoint(vBase, vKind) }.getOrElse { "地址还没填对：${it.message}" },
                )

                Row(
                    modifier = Modifier.fillMaxWidth().padding(top = 14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    ActionPill(
                        bg = when {
                            vPlaying -> Chrome.chip
                            vBusy -> BUSY_GREY
                            voiceOk -> OK_GREEN
                            else -> Chrome.accent
                        },
                        fg = if (vPlaying) Chrome.ink else Chrome.card,
                        enabled = !vBusy,
                        onClick = { if (vPlaying) stopVoice() else tryVoice() },
                        text = when {
                            vPlaying -> "停止"
                            vBusy -> "合成中"
                            else -> "试听"
                        },
                    )
                    Spacer(Modifier.size(10.dp))
                    Text(
                        if (vPlaying) "正在放这一句" else "四项齐全听书才走 AI，缺一项就退回系统语音引擎",
                        fontSize = 12.sp,
                        color = Chrome.faint,
                        modifier = Modifier.weight(1f),
                    )
                }
                note?.let {
                    Text(it, fontSize = 13.sp, color = Chrome.accent, modifier = Modifier.fillMaxWidth().padding(top = 10.dp))
                }

                Spacer(Modifier.height(20.dp))
                Column {
                    Rule("「词典」和「听书」只在阅读页长按后用得到；两家可以完全不同，秘钥各存一份。")
                Rule("听书只念你选中的那一段。同一段存本机，再点不重复请求、不重复计费。")
                Rule("秘钥经本机 Keystore 加密后落盘；换机或从云备份恢复后要重填。")
                Rule("地址填 https://x 或 https://x/v1 都行，各家路径我们自己补。")
                Rule("音色名各家不通用：抄进「可选音色」或用「拉音色列表」拉，听书页上直接挑。")
                }
                Spacer(Modifier.height(28.dp))
            }
            }

        // 音色做成"从下面那一列里挑"：手敲一个不在模型表里的名字，代价是一次 400
        if (voiceOpen) {
            Sheet(Chrome.card, Chrome.ink, Chrome.faint, title = "音色", onDismiss = { voiceOpen = false }) {
                // 他自己抄的 + 当前这个 + 内置常见表：第一屏就不是一排空的
                val names = AiSpeech.voiceOptions(voiceList + listOf(vVoice), AiSpeech.commonVoices(vKind, vModel))
                    .filter { it.isNotBlank() }
                names.forEach { v ->
                    Row(
                        modifier = Modifier.fillMaxWidth().height(52.dp).tap { vVoice = v; voiceOpen = false },
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(v, fontSize = 15.sp, color = Chrome.ink)
                        Spacer(Modifier.weight(1f))
                        if (v == vVoice) Text("·", fontSize = 18.sp, color = Chrome.accent)
                    }
                }
                if (names.isEmpty()) {
                    Text(
                        "还没有可选音色：先在下面「可选音色」那一格把名字抄进来",
                        fontSize = 13.sp,
                        color = Chrome.faint,
                        modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp),
                    )
                }
                Spacer(Modifier.height(10.dp))
            }
        }
    }
}

/**
 * 一行一个设置项：标签在左、输入在右，整行都是命中区。
 *
 * @param wrap 长值（地址）允许折行。**这一格不能截断**：一长串主机名被吃掉开头之后，
 *   剩下那段看着仍然像一条合法地址，谁也不能靠它判断自己填对没有 —— 阿里那个专属空间域名
 *   有 60 多个字符，单行在这台机上必然截。
 */
@Composable
private fun FieldRow(
    label: String,
    placeholder: String,
    value: String,
    onValueChange: (String) -> Unit,
    keyboardType: KeyboardType,
    reveal: Boolean,
    onToggleReveal: (() -> Unit)?,
    wrap: Boolean = false,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .then(if (wrap) Modifier.heightIn(min = 52.dp) else Modifier.height(52.dp))
            .padding(horizontal = 14.dp, vertical = if (wrap) 10.dp else 0.dp),
        verticalAlignment = if (wrap) Alignment.Top else Alignment.CenterVertically,
    ) {
        Text(label, fontSize = 15.sp, color = Chrome.ink)
        Spacer(Modifier.size(width = 14.dp, height = 1.dp))
        Box(
            Modifier.weight(1f),
            contentAlignment = if (wrap) Alignment.TopEnd else Alignment.CenterEnd,
        ) {
            if (value.isEmpty()) {
                Text(placeholder, fontSize = 13.sp, color = Chrome.faint, textAlign = TextAlign.End)
            }
            BasicTextField(
                value = value,
                onValueChange = { onValueChange(it.replace("\n", "")) },
                // 地址那一档允许软折行：Enter 仍然被上面那句吃掉，不会真的存进换行符
                singleLine = !wrap,
                textStyle = LocalTextStyle.current.copy(fontSize = 14.sp, color = Chrome.ink, textAlign = TextAlign.End),
                cursorBrush = SolidColor(Chrome.accent),
                visualTransformation = if (reveal || onToggleReveal == null) VisualTransformation.None else PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = keyboardType),
                modifier = Modifier.fillMaxWidth(),
            )
        }
        if (onToggleReveal != null) {
            Spacer(Modifier.size(10.dp))
            Text(
                if (reveal) "隐藏" else "显示",
                fontSize = 13.sp,
                color = Chrome.faint,
                modifier = Modifier.tap(onToggleReveal).padding(vertical = 8.dp, horizontal = 2.dp),
            )
        }
    }
}

@Composable
private fun Divider() {
    // 和「我的」页那些分隔线同一规则：1 个物理像素，不是 1dp（2.75 倍密度上会取整成 3 行）
    val d = LocalDensity.current
    Box(Modifier.fillMaxWidth().padding(start = 14.dp).height(with(d) { 1.toDp() }).background(Chrome.hairline))
}

/** 把"我们最终会往哪发"摊开：地址只填到主机名时，补完的路径看不见的表现就是选错档只会收到 404 */
@Composable
private fun Resolved(label: String, url: String) {
    Text(
        "$label $url",
        fontSize = 11.sp,
        color = Chrome.faint,
        lineHeight = 16.sp,
        modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
    )
}

@Composable
private fun Rule(text: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Box(
            modifier = Modifier
                .size(4.dp)
                .padding(top = 8.dp)
                .background(Chrome.faint, androidx.compose.foundation.shape.CircleShape),
        )
        Spacer(Modifier.size(width = 8.dp, height = 1.dp))
        Text(text, fontSize = 13.sp, color = Chrome.faint, lineHeight = 19.sp, modifier = Modifier.weight(1f))
    }
}

/** 成了 = 绿，请求中 = 灰。这两档不在 `Chrome` 里：外壳那四页没有这两个语义，只有这一页要。 */
private val OK_GREEN = Color(0xFF2FA36B)
private val BUSY_GREY = Color(0xFF9AA0A6)

/**
 * 这一页三颗动作按钮共用一份尺寸：**96x34dp、14sp**，宽度不跟着字数走 ——
 * 他要的是三颗一样宽，那就只留一个主人，别在三个调用点各写一遍数字（写三遍迟早飘）。
 */
@Composable
private fun ActionPill(bg: Color, fg: Color, enabled: Boolean, onClick: () -> Unit, text: String) {
    Box(
        modifier = Modifier
            .size(width = 96.dp, height = 34.dp)
            .clip(RoundedCornerShape(ACTION_RADIUS))
            .background(bg)
            .tap(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { Text(text, fontSize = 14.sp, color = fg) }
}

/** 只读的一行：值贴在右边，点整行开底部抽屉去挑。内衬和 FieldRow 同一套，两列标签才对得齐。 */
@Composable
private fun PickRow(label: String, value: String, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 52.dp)
            .tap(onClick)
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Text(label, fontSize = 15.sp, color = Chrome.ink)
        Spacer(Modifier.size(width = 14.dp, height = 1.dp))
        Box(Modifier.weight(1f), contentAlignment = Alignment.TopEnd) {
            Text(value, fontSize = 15.sp, color = Chrome.ink, textAlign = TextAlign.End)
        }
    }
}
