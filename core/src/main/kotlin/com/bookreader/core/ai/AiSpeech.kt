package com.bookreader.core.ai

/**
 * 语音合成那一档的纯字符串活：地址、请求体、把 MiniMax 的 hex 音频解回字节。
 *
 * 只做两家，是有意的：
 * - **OpenAI 兼容 `/audio/speech`** —— 回裸音频字节。大量中转站和自建网关把各家都统一成了这一种形状，
 *   所以"填个地址就能用"这一档覆盖面最广。
 * - **MiniMax `/v1/t2a_v2`** —— 同样 Bearer，但音频是 **hex 字符串**塞在 `data.audio` 里，
 *   而且 HTTP 状态码 200 也可能带业务错误（真正的结果在 `base_resp.status_code`）。
 * - 豆包/火山那一档**没做**：它走 `wss://…/api/v1/tts/ws_binary`，鉴权头是 `Bearer; {token}`，
 *   回包是自定义二进制分帧 + gzip，不是"填个地址"能收下的；官方文档页是 JS 渲染的，抓不到可核对的规格，
 *   照着二手博客写等于猜。要用它就走一个把它包成 `/audio/speech` 的中转。
 */
object AiSpeech {

    enum class Kind { OpenAi, MiniMax, QwenHttp }

    /** 界面上的名字就按这几个字面，别写"其他/自定义"那种看不出接的是什么的标签 */
    fun label(kind: Kind): String = when (kind) {
        Kind.OpenAi -> "OpenAI 兼容"
        Kind.MiniMax -> "MiniMax"
        Kind.QwenHttp -> "阿里 Qwen-TTS"
    }

    /**
     * 地址归一，规则和 [AiProtocol.chatEndpoint] 一样：允许带或不带尾斜杠、允许他已经填到底。
     * MiniMax 的文档给的 host 有 `api.minimax.cn` / `api.minimaxi.com` 好几种，阿里的专属空间域名每个业务空间
     * 也不一样（`{WorkspaceId}.cn-beijing.maas.aliyuncs.com`），所以**只补路径、不碰主机名**。
     * @throws IllegalArgumentException 空着或没写协议
     */
    fun endpoint(base: String, kind: Kind): String {
        var b = base.trim()
        require(b.startsWith("http://") || b.startsWith("https://")) { "语音地址要以 http:// 或 https:// 开头" }
        while (b.endsWith("/")) b = b.dropLast(1)
        return when (kind) {
            Kind.OpenAi -> if (b.endsWith("/audio/speech")) b else "$b/audio/speech"
            Kind.MiniMax -> if (b.endsWith("/t2a_v2") || b.endsWith("/t2a")) b else "$b/v1/t2a_v2"
            // 阿里这一档走「非实时语音合成」的 HTTP 口。同一个模型家族还有
            // `wss://…/api-ws/v1/inference` 那条流式口 —— 不走它：Android 没有内置 WebSocket 客户端，
            // 为它引一个依赖不值，而且非实时口是同步返回、不需要轮询任务。
            Kind.QwenHttp -> if (b.endsWith("/SpeechSynthesizer")) b else "$b/api/v1/services/audio/tts/SpeechSynthesizer"
        }
    }

    /**
     * 三家的请求体形状各不相同，而且**同名字段在不同的层**：OpenAI 的 `input` 是一个字符串字段，
     * 阿里的 `input` 是一个对象、正文在它里面的 `text`。写错的表现是 400，界面上看不出来是哪家。
     */
    fun body(kind: Kind, model: String, voice: String, text: String): String = when (kind) {
        Kind.OpenAi ->
            "{\"model\":${AiProtocol.jsonString(model)},\"input\":${AiProtocol.jsonString(text)}," +
                "\"voice\":${AiProtocol.jsonString(voice)},\"response_format\":\"mp3\"}"

        Kind.MiniMax ->
            "{\"model\":${AiProtocol.jsonString(model)},\"text\":${AiProtocol.jsonString(text)}," +
                "\"stream\":false," +
                "\"voice_setting\":{\"voice_id\":${AiProtocol.jsonString(voice)},\"speed\":1.0,\"vol\":1.0,\"pitch\":0}," +
                "\"audio_setting\":{\"sample_rate\":32000,\"bitrate\":128000,\"format\":\"mp3\",\"channel\":1}}"

        Kind.QwenHttp ->
            "{\"model\":${AiProtocol.jsonString(model)}," +
                "\"input\":{\"text\":${AiProtocol.jsonString(text)}," +
                // 这两个值是照官方 curl 示例来的：示例给的是 wav + 24000。
                // 只给 format=mp3、不给 sample_rate 时，引擎回 InvalidParameter / Engine error [411]
                // （真机 14:29:42 量过，音色和模型都用的官方表里的合法值，所以不是版本不匹配）
                "\"voice\":${AiProtocol.jsonString(voice)},\"format\":\"wav\",\"sample_rate\":24000}}"
    }

    /**
     * 阿里这档**不把音频放在回包里**：非流式回的是一个临时下载地址 `output.audio.url`（24 小时有效），
     * 而 `output.audio.data` 在非流式下恒为空串。所以要再 GET 一次那个地址才拿得到字节。
     * 从 `"output"` 之后开始找，避免读到回包里别的 `url` 字段。
     */
    fun qwenAudioUrl(body: String): String? =
        AiProtocol.jsonStringAfter(body, "url", body.indexOf("\"output\"").coerceAtLeast(0))?.trim()?.takeIf {
            it.startsWith("http://") || it.startsWith("https://")
        }

    /** MiniMax 的音频 hex 串（`data.audio`）；没有或不是字符串时 null */
    fun minimaxAudio(body: String): String? =
        AiProtocol.jsonStringAfter(body, "audio", body.indexOf("\"data\"").coerceAtLeast(0))?.trim()?.takeIf { it.isNotEmpty() }

    /**
     * MiniMax 就算 HTTP 200 也可能带业务错误 —— 真正的结果在 `base_resp.status_code`，非 0 就是失败。
     * @return 人话；成功（status_code 为 0 或这个字段不在）时 null
     */
    fun minimaxError(body: String): String? {
        val resp = body.indexOf("\"base_resp\"")
        if (resp < 0) return null
        val code = AiProtocol.jsonStringAfter(body, "status_msg", resp)
        val num = Regex("\"status_code\"\\s*:\\s*(-?\\d+)").find(body.substring(resp))?.groupValues?.get(1)?.toIntOrNull()
            ?: return null
        if (num == 0) return null
        return code?.takeIf { it.isNotBlank() } ?: "语音服务返回状态码 $num"
    }

    /**
     * 把设置页「可选音色」那一格解析成列表：中英文逗号、分号、空白（含换行）都当分隔符，
     * 去重且保持他抄写的顺序 —— 顺序就是他在听书页上看到的顺序，别偷偷按字母排。
     */
    fun voices(raw: String): List<String> =
        raw.split(',', '，', ';', '；', ' ', '\n', '\r', '\t', '　')
            .map { it.trim() }.filter { it.isNotEmpty() }.distinct()

    /**
     * 「听书纠错」那张白名单：每行一条 `银行=银航`。发给语音接口之前把左边换成右边，
     * **屏幕上读的正文一个字都不动**。
     *
     * 为什么只能是"换成另一个字"：这三家接口都只吃纯文本，没有 SSML、没有音素口，
     * 拿不到"给某个字指定读音"的入口。所以能落地的形式只有换成一个**只会读这个音**的字。
     */
    fun applyFixes(text: String, table: String): String {
        var out = text
        parseFixes(table).forEach { (from, to) -> out = out.replace(from, to) }
        return out
    }

    /** 解析那张表：坏行（没等号、任一边空、左右一样）直接跳过不猜；**长的先换**，免得单字那条抢在多字前面。 */
    fun parseFixes(table: String): List<Pair<String, String>> =
        table.split('\n', '，', ';', '；').mapNotNull { raw ->
            val at = raw.indexOf('=')
            if (at <= 0) return@mapNotNull null
            val from = raw.substring(0, at).trim()
            val to = raw.substring(at + 1).trim()
            if (from.isEmpty() || to.isEmpty() || from == to) null else from to to
        }.sortedByDescending { it.first.length }.distinctBy { it.first }

    /**
     * 「拉一下」用的地址。**三家都没有"系统音色"的列表口** —— `longanhuan_v3.1` 那一批只在文档表里；
     * 阿里那个 `list_voice` 回的是**你自己复刻过**的音色。能拉的只有两家：
     *  - OpenAI 兼容：`GET {base}/audio/voices`
     *  - 阿里：`POST {base}/api/v1/services/audio/tts/customization`（`model=voice-enrollment`）
     *  - MiniMax：官方没有这个口（阿里云文档里也明说"MiniMax 不支持通过本接口查询音色列表"）
     *
     * @return null = 这一家拉不了。界面上要说出为什么，而不是摆一颗点了没反应的按钮。
     */
    fun voicesEndpoint(base: String, kind: Kind): String? {
        if (kind == Kind.MiniMax) return null
        var b = base.trim()
        require(b.startsWith("http://") || b.startsWith("https://")) { "语音地址要以 http:// 或 https:// 开头" }
        while (b.endsWith("/")) b = b.dropLast(1)
        return when (kind) {
            Kind.OpenAi -> if (b.endsWith("/audio/voices")) b else "$b/audio/voices"
            Kind.QwenHttp -> if (b.endsWith("/customization")) b else "$b/api/v1/services/audio/tts/customization"
            Kind.MiniMax -> null
        }
    }

    /** 拉列表的请求体：OpenAI 那家是 GET（没有体），阿里要报 `voice-enrollment` + `list_voice`。 */
    fun voicesBody(kind: Kind): String? = when (kind) {
        Kind.OpenAi, Kind.MiniMax -> null
        Kind.QwenHttp ->
            """{"model":"voice-enrollment","input":{"action":"list_voice","page_index":0,"page_size":100}}"""
    }

    /**
     * 从回包里把音色名捞出来。两家形状不一样，所以不整棵解析 JSON，只认两个键：
     * `"voice_id":"…"`（阿里复刻口）和 `"id":"…"`（OpenAI 的 `data[]`）。
     * 判 `"id"` 时带上前面那个引号 —— 不然 `request_id` 会被当成一个音色混进列表。
     */
    fun parseVoices(body: String): List<String> {
        val keys = listOf("\"voice_id\":\"", "\"id\":\"")
        val out = ArrayList<String>()
        var i = 0
        while (i < body.length) {
            val hit = keys.firstOrNull { body.startsWith(it, i) }
            if (hit == null) {
                i++
            } else {
                val from = i + hit.length
                val to = body.indexOf('"', from)
                if (to < 0) return out
                val v = body.substring(from, to)
                if (v.isNotEmpty() && v !in out) out.add(v)
                i = to + 1
            }
        }
        return out
    }

    /**
     * 内置的"常见音色"，界面上先替他填上，省得第一屏是一排空的。
     *
     * **只放我查得到出处的名字**（2026-10-10 对过官方文档）：OpenAI 那六个是 `tts-1` 的标准音色；
     * 阿里这一档要分模型 —— `qwen3-tts-flash` 用的是 `Cherry/Serena/Ethan/Chelsie` 那一批，
     * 而 `qwen-audio-*-tts` / CosyVoice 用的是 `long*_v3.1` 那一批，**官方没有公开的完整列表**，
     * 所以我只能填确认过的 `longanhuan_v3.1`（他真机用过、通）。其余让他自己「拉一下」或抄进来。
     * MiniMax 同理：没有能核对的表，就不摆一串我猜的名字。
     */
    fun commonVoices(kind: Kind, model: String): List<String> = when (kind) {
        Kind.OpenAi -> listOf("alloy", "nova", "onyx", "shimmer", "fable", "echo")
        Kind.QwenHttp -> if (model.contains("qwen3-tts")) listOf("Cherry", "Serena", "Ethan", "Chelsie")
            else listOf("longanhuan_v3.1")
        Kind.MiniMax -> emptyList()
    }

    /** 这一档的默认音色 = 内置表里第一个；选了个不认的名字时退回它再试一次。 */
    fun defaultVoice(kind: Kind, model: String): String? = commonVoices(kind, model).firstOrNull()

    /**
     * 把「他抄的那一列」和内置常见表并起来：他自己的排前面（那是他账号下真能用的），
     * 内置的补在后面当候选。去重按不区分大小写 —— 有的中转把 `Alloy` 和 `alloy` 当两个写。
     */
    fun voiceOptions(chosen: List<String>, common: List<String>): List<String> {
        val out = ArrayList(chosen)
        common.forEach { c -> if (out.none { it.equals(c, ignoreCase = true) }) out.add(c) }
        return out
    }

    /**
     * 「缓存范围」那一档要缓存哪几章：从**正在听的这一章之后**数 N 章（他点的原话是
     * "按读到的章节后面加五章进行缓存"），所以当前这一章不在里面 —— 它正一段一段播着、一段一段进缓存。
     *
     * `count = null` 是「全部章节」这一档：整本从头到尾（不是"之后"，全部就是全部）。
     * 越界自己收：读到第 3 章、总共 5 章、要 50 章 ⇒ 只回第 4、5 章，不吐负数也不吐到 53。
     */
    fun chapterWindow(current: Int, total: Int, count: Int?): List<Int> {
        if (total <= 0) return emptyList()
        return if (count == null) (0 until total).toList()
        else ((current + 1).coerceAtMost(total) until (current + 1 + count).coerceAtMost(total)).toList()
    }

    /**
     * 这一串值不值得发给合成接口：**至少要有一个念得出来的字**。
     *
     * 真机 11:54:45 缓存第一章报 400 `InvalidParameter / Please ensure input text is valid.` ——
     * 那一格是 markdown 表格的分隔行 `|----|----|`，整段只有竖线和连字符，一个音都发不出来。
     * 本地按同一套切段复量：第一章 18 段，前 12 段的偏移 0,3,19,42,87,95,103,111,119,124,129,141
     * 与设备上 index.tsv 记的逐一对上，第 13 段（off=151）就是这一行。
     *
     * `isNotBlank()` 挡不住它：竖线、破折号、星号都不是空白。字母和数字算"能念"，
     * 标点、符号、空白都不算。
     */
    fun speakable(text: String): Boolean = text.any { Character.isLetterOrDigit(it) }

    /**
     * 修 wav 头里那两个长度字段。
     *
     * **为什么要修**：阿里这一档回的 wav 把 `RIFF` 长度和 `data` 长度都写成 `0x7FFFFFFF`
     * （"流式，我还不知道多长"）。`MediaPlayer` 照这个数算时长：真长 16.6 秒、798,764 字节的语音
     * 被报成 **745:39**，于是进度条钉在 0.03%、听书页的正文一步都不动（他报的"读到最后文字没变化"）。
     * 这里按文件真实字节数把两个字段改回来；不是 wav、或者本来就对，就**原样返回同一个数组**。
     */
    fun wavSizesFixed(bytes: ByteArray): ByteArray {
        if (bytes.size < 12) return bytes
        if (!matches(bytes, 0, "RIFF") || !matches(bytes, 8, "WAVE")) return bytes
        var at = 12
        var dataAt = -1
        while (at + 8 <= bytes.size) {
            val size = le(bytes, at + 4)
            if (matches(bytes, at, "data")) {
                dataAt = at
                break
            }
            if (size <= 0) return bytes
            at += 8 + size + (size % 2)   // 块按偶数字节对齐，奇数长度后面有一个填充字节
        }
        if (dataAt < 0) return bytes
        val dataLen = bytes.size - (dataAt + 8)
        if (dataLen < 0) return bytes
        if (le(bytes, 4) == bytes.size - 8 && le(bytes, dataAt + 4) == dataLen) return bytes
        val out = bytes.copyOf()
        putLe(out, 4, out.size - 8)
        putLe(out, dataAt + 4, dataLen)
        return out
    }

    /**
     * 把 PCM wav **开头和结尾的静音剪掉**，精确剪到第一个 / 最后一个有声帧（不留垫 —— 留了就不幂等，
     * 而这一格在每次播放前都会被走一遍）。
     *
     * **为什么要剪**：TTS 回的语音两头带静音。从他机器上那两段真缓存量的（同一把 ffmpeg 判据
     * `-35dB / d=0.05`）：8.16 秒那段开头空 **0.095 秒**、结尾空 0.244 秒；10.4 秒那段 0.085 / 0.196 秒。
     * 我们这一格的判据（峰值的 1/16 ≈ 峰值下 24dB，底噪再低也不低于满量程 0.5%）在两段上是
     * 90 / 84 毫秒 —— 和 ffmpeg 对到 10 毫秒以内，因为那一段头是**数字零**。
     * 整段剪完 8160.75→7817.67 毫秒、10400.75→10094.46 毫秒。
     *
     * 但要把话说清楚：**这不到 0.1 秒的头空解释不了他报的现象**。听书页"念到第几句"是按
     * `已过时长 ÷ 总时长` 折算的，0.09 秒折进 8 秒里只有 1%，不到半个字。真正咬人的是
     * 段**内部**那些停顿（这两段的内部停顿合起来占 21%~26% 的时长），那部分见
     * [com.bookreader.core.layout.sentenceIndexByChars] 的口径讨论。
     * 剪这一格还是值得做：时长从此是"说话的时长"，播放条、进度、正文折算三条一起对上，
     * 而且剪在**存盘之前**（和 [wavSizesFixed] 同一道口子），界面上不用补任何魔数。
     *
     * 只认 `fmt ` 里 audioFormat=1（PCM）且位宽 8/16/24/32 的 wav；mp3 / m4a / 浮点 / 压过的一律
     * **原样交回去同一个数组**，不猜。判"有没有声"用相对量：峰值的 1/16，且不低于满量程的 0.5%
     * —— 底噪高的录音也不会被剪穿。整段都没过线（全静音）时原样返回，不交一个空文件出去。
     */
    fun wavSilenceTrimmed(bytes: ByteArray): ByteArray {
        if (bytes.size < 44) return bytes
        if (!matches(bytes, 0, "RIFF") || !matches(bytes, 8, "WAVE")) return bytes
        var at = 12
        var fmtAt = -1
        var dataAt = -1
        var dataLen = 0
        while (at + 8 <= bytes.size) {
            val size = le(bytes, at + 4)
            when {
                matches(bytes, at, "fmt ") -> fmtAt = at
                matches(bytes, at, "data") -> {
                    dataAt = at
                    dataLen = minOf(size.coerceAtLeast(0), bytes.size - (at + 8))
                }
                else -> if (size <= 0) return bytes
            }
            if (dataAt >= 0) break
            if (size <= 0) return bytes
            at += 8 + size + (size % 2)
        }
        if (fmtAt < 0 || dataAt < 0 || dataLen <= 0) return bytes
        val f = fmtAt + 8
        if (u16(bytes, f) != 1) return bytes                       // 不是 PCM：不动
        val channels = u16(bytes, f + 2).coerceAtLeast(1)
        val rate = le(bytes, f + 4).coerceAtLeast(1)
        val bits = u16(bytes, f + 14)
        val bytesPer = bits / 8
        if (bytesPer != 1 && bytesPer != 2 && bytesPer != 3 && bytesPer != 4) return bytes
        val frame = channels * bytesPer
        val frames = dataLen / frame
        if (frames <= 0) return bytes
        val base = dataAt + 8
        val amp = LongArray(frames)
        var peak = 0L
        for (i in 0 until frames) {
            var m = 0L
            for (c in 0 until channels) {
                val v = sample(bytes, base + i * frame + c * bytesPer, bytesPer)
                val a = if (v < 0) -v else v
                if (a > m) m = a
            }
            amp[i] = m
            if (m > peak) peak = m
        }
        val thr = maxOf(peak / 16, 32768L / 200)
        var first = -1
        var last = -1
        for (i in 0 until frames) {
            if (amp[i] >= thr) {
                if (first < 0) first = i
                last = i
            }
        }
        if (first < 0) return bytes                                 // 整段静音：原样交回去
        // 不预留"垫"：留了就**不幂等** —— heal 每次播放前都会跑一遍，留 30ms 垫等于每播一次
        // 再削掉 60ms。精确剪到第一个 / 最后一个过线帧，第二次进来 from=0、to=末帧，原样返回。
        // 门限本身够保守（峰值的 1/16 ≈ 峰值下 24dB），TTS 那段开头又是数字零，削不到字头。
        val from = first
        val to = last
        if (from == 0 && to == frames - 1) return bytes             // 本来就没有首尾静音
        val keep = (to - from + 1) * frame
        val out = ByteArray(base + keep)
        bytes.copyInto(out, 0, 0, base)
        bytes.copyInto(out, base, base + from * frame, base + (to + 1) * frame)
        return wavSizesFixed(out)
    }

    /**
     * 存盘前对一段合成音频做的两件事：**先剪首尾静音，再把流式 wav 的两个长度字段补成真实值**。
     *
     * 顺序不能反：剪完字节数变了，长度字段要按剪之后的重算。四个写入点（缓存、heal、试听、批量缓存）
     * 都走这一格，免得有一处漏剪、那一处存的音频在听书页上就慢半拍。
     */
    fun storedAudio(bytes: ByteArray): ByteArray = wavSizesFixed(wavSilenceTrimmed(bytes))

    private fun u16(b: ByteArray, at: Int): Int =
        if (at + 2 > b.size) 0 else (b[at].toInt() and 0xFF) or ((b[at + 1].toInt() and 0xFF) shl 8)

    /** 一个整数 PCM 采样，归一到 16 位的量纲（8 位是无符号、24/32 位往下截）。 */
    private fun sample(b: ByteArray, at: Int, size: Int): Long = when (size) {
        1 -> if (at in b.indices) ((b[at].toInt() and 0xFF) - 128) * 256L else 0L
        2 -> if (at + 1 < b.size) {
            // 只读两个字节，且要按**有符号**解释：写成 le() 会连着读四个字节，把下一帧的高半截
            // 当成这一帧的符号位 —— 负样本（-9000）读成 3 亿的巨值，峰值跟着虚高，门限就废了。
            val v = (b[at].toInt() and 0xFF) or ((b[at + 1].toInt() and 0xFF) shl 8)
            (if (v and 0x8000 != 0) v - 0x10000 else v).toLong()
        } else 0L
        3 -> if (at + 2 < b.size) {
            val v = (b[at].toInt() and 0xFF) or ((b[at + 1].toInt() and 0xFF) shl 8) or
                ((b[at + 2].toInt() and 0xFF) shl 16)
            (if (v and 0x800000 != 0) v - 0x1000000 else v) / 256L
        } else 0L
        4 -> if (at + 3 < b.size) le(b, at).toLong() / 65536L else 0L
        else -> 0L
    }

    private fun matches(b: ByteArray, at: Int, tag: String): Boolean {
        if (at + 4 > b.size) return false
        for (i in 0 until 4) if (b[at + i].toInt() != tag[i].code) return false
        return true
    }

    private fun le(b: ByteArray, at: Int): Int =
        if (at + 4 > b.size) -1 else
            (b[at].toInt() and 0xFF) or ((b[at + 1].toInt() and 0xFF) shl 8) or
                ((b[at + 2].toInt() and 0xFF) shl 16) or ((b[at + 3].toInt() and 0xFF) shl 24)

    private fun putLe(b: ByteArray, at: Int, v: Int) {
        b[at] = (v and 0xFF).toByte()
        b[at + 1] = ((v shr 8) and 0xFF).toByte()
        b[at + 2] = ((v shr 16) and 0xFF).toByte()
        b[at + 3] = ((v shr 24) and 0xFF).toByte()
    }

    /**
     * hex → 字节。宽松到只吃空白：有的网关会在 hex 里换行。
     * 长度为奇数、或出现非 hex 字符时返回 null —— 半截音频播出来是"念到一半没声"，比直接报错更难查。
     */
    fun hexToBytes(hex: String): ByteArray? {
        val s = buildString { for (c in hex) if (!c.isWhitespace()) append(c) }
        if (s.isEmpty() || s.length % 2 != 0) return null
        val out = ByteArray(s.length / 2)
        for (i in out.indices) {
            val hi = Character.digit(s[i * 2], 16)
            val lo = Character.digit(s[i * 2 + 1], 16)
            if (hi < 0 || lo < 0) return null
            out[i] = ((hi shl 4) or lo).toByte()
        }
        return out
    }
}
