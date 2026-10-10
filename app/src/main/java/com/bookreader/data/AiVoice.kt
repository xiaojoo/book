package com.bookreader.data

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaPlayer
import android.util.Log
import com.bookreader.core.ai.AiProtocol
import com.bookreader.core.ai.AiSpeech
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/**
 * 语音合成：把一段文字变成能播的 mp3 字节。两家形状不同，都在这一个函数里分派。
 *
 * **必须在 IO 线程上调用**（和 [AiClient] 一样，自己不切线程）。日志里不打秘钥、不打完整地址，
 * 只打 host / 状态码 / 字节数 —— 有的中转把 token 写在 path 里。
 */
object AiVoiceClient {

    private const val TAG = "AiVoice"

    /**
     * 拉一次"这个账号能用的音色"。**只能拉到 OpenAI 兼容那家的整表、和阿里你自己复刻过的音色** ——
     * 系统音色（`longanhuan_v3.1` 那一批）三家都没有列表接口，所以「可选音色」那格手抄的入口必须留着。
     * 必须在 IO 线程上调用。
     */
    fun listVoices(baseUrl: String, key: String, kind: AiSpeech.Kind): List<String> {
        val endpoint = AiSpeech.voicesEndpoint(baseUrl, kind)
            ?: throw AiError("这一家没有查音色的接口，只能自己抄进「可选音色」那一格")
        if (key.isBlank()) throw AiError("语音秘钥没填，或换机后失效要重填")
        val body = AiSpeech.voicesBody(kind)?.toByteArray()
        val conn = try {
            URL(endpoint).openConnection()
        } catch (e: IOException) {
            throw AiError("这个语音地址解析不了（${e.javaClass.simpleName}）")
        } as? HttpURLConnection ?: throw AiError("这个协议不是 http/https")
        return try {
            conn.requestMethod = "POST"
            conn.connectTimeout = 15_000
            conn.readTimeout = 30_000
            conn.setRequestProperty("Accept", "application/json")
            conn.setRequestProperty("Authorization", "Bearer $key")
            if (body != null) {
                conn.doOutput = true
                conn.setRequestProperty("Content-Type", "application/json; charset=utf-8")
                conn.setFixedLengthStreamingMode(body.size)
                conn.outputStream.use { it.write(body) }
            }
            val code = conn.responseCode
            val text = (if (code in 200..299) conn.inputStream else conn.errorStream)
                ?.use { it.readBytes().toString(Charsets.UTF_8) } ?: ""
            if (code !in 200..299) {
                Log.w(TAG, "voices host=${URL(endpoint).host} kind=$kind code=$code msg=${text.take(160).replace('\n', ' ')}")
                throw AiError(
                    if (code == 404) "这一家不认 ${URL(endpoint).path}（404）：还是自己抄进「可选音色」那一格"
                    else AiProtocol.errorText(code, text),
                )
            }
            val found = AiSpeech.parseVoices(text)
            if (found.isEmpty()) throw AiError("接口回了 $code，但里面没有音色名 —— 还是自己抄那一格")
            found
        } catch (e: AiError) {
            throw e
        } catch (e: IOException) {
            Log.w(TAG, "voices failed: ${e.javaClass.simpleName}")
            throw AiError("拉音色失败：${e.javaClass.simpleName}")
        } finally {
            conn.disconnect()
        }
    }

    fun synthesize(baseUrl: String, key: String, kind: AiSpeech.Kind, model: String, voice: String, text: String): ByteArray {
        val endpoint = runCatching { AiSpeech.endpoint(baseUrl, kind) }
            .getOrElse { throw AiError(it.message ?: "语音地址不能用") }
        if (model.isBlank()) throw AiError("语音模型名没填")
        if (voice.isBlank()) throw AiError("音色没填（OpenAI 那档如 alloy / nova，MiniMax 如 male-qn-qingse）")
        if (key.isBlank()) throw AiError("语音秘钥没填，或换机后失效要重填")
        if (text.isBlank()) throw AiError("这一段没有正文可念")

        val body = AiSpeech.body(kind, model, voice, text).toByteArray()
        val conn = try {
            URL(endpoint).openConnection()
        } catch (e: IOException) {
            throw AiError("这个语音地址解析不了（${e.javaClass.simpleName}）")
        } as? HttpURLConnection ?: throw AiError("这个协议不是 http/https")

        return try {
            conn.requestMethod = "POST"
            conn.connectTimeout = 15_000
            // 合成比对话慢得多：一句几十个字也可能要几秒，60s 是上限不是常态
            conn.readTimeout = 90_000
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "application/json; charset=utf-8")
            conn.setRequestProperty("Authorization", "Bearer $key")
            conn.setFixedLengthStreamingMode(body.size)
            conn.outputStream.use { it.write(body) }

            val code = conn.responseCode
            val type = conn.contentType ?: ""
            if (code !in 200..299) {
                val err = conn.errorStream?.use { it.readBytes().toString(Charsets.UTF_8) } ?: ""
                // 服务端给的原因必须落进日志：只在屏幕上闪一下的话，别人替你看不了这一次失败
                Log.w(
                    TAG,
                    "host=${URL(endpoint).host} path=${URL(endpoint).path} kind=$kind code=$code " +
                        "svc=${AiProtocol.jsonStringAfter(err, "code") ?: "-"} msg=${
                        (AiProtocol.jsonStringAfter(err, "message") ?: AiProtocol.jsonStringAfter(err, "msg") ?: err)
                            .take(200).replace('\n', ' ')
                    }",
                )
                // 404 几乎只有一种成因：服务商那一档选错了，于是请求了另一家的路径。
                // 把路径写进回话，这一枪就不用靠猜
                throw AiError(
                    if (code == 404) "语音服务回 404：这一档请求的是 ${URL(endpoint).path}，" +
                        "服务商选错就会这样（阿里的地址要选「阿里 Qwen-TTS」）"
                    else AiProtocol.errorText(code, err),
                )
            }
            when (kind) {
                // OpenAI 兼容：成功时回包本身就是音频字节
                AiSpeech.Kind.OpenAi -> {
                    val bytes = conn.inputStream.use { it.readBytes() }
                    Log.d(TAG, "host=${URL(endpoint).host} kind=OpenAi code=$code bytes=${bytes.size} type=$type")
                    if (bytes.isEmpty()) throw AiError("语音服务回了 0 字节")
                    if (type.contains("json", true)) {
                        // 有些网关出错也回 200 + JSON：不检这一条的话表现是"点了没声"
                        throw AiError(AiProtocol.errorText(code, bytes.toString(Charsets.UTF_8)))
                    }
                    bytes
                }
                // MiniMax：200 也可能带业务错误，而且音频是 hex
                AiSpeech.Kind.MiniMax -> {
                    val raw = conn.inputStream.use { it.readBytes().toString(Charsets.UTF_8) }
                    AiSpeech.minimaxError(raw)?.let { throw AiError(it) }
                    val hex = AiSpeech.minimaxAudio(raw) ?: throw AiError("回包里没有音频（data.audio 是空的）")
                    val bytes = AiSpeech.hexToBytes(hex) ?: throw AiError("音频 hex 不完整，播出来会只响一半")
                    Log.d(TAG, "host=${URL(endpoint).host} kind=MiniMax code=$code bytes=${bytes.size}")
                    bytes
                }
                // 阿里非实时口：回的是临时下载地址，要再 GET 一次
                AiSpeech.Kind.QwenHttp -> {
                    val raw = conn.inputStream.use { it.readBytes().toString(Charsets.UTF_8) }
                    val url = AiSpeech.qwenAudioUrl(raw)
                        ?: throw AiError(AiProtocol.errorText(code, raw).let { if (it == "HTTP $code") "回包里没有下载地址（output.audio.url）" else it })
                    download(url)
                }
            }
        } catch (e: IOException) {
            Log.w(TAG, "request failed: ${e.javaClass.simpleName}")
            throw AiError("连不上这个语音地址（${e.javaClass.simpleName}）")
        } finally {
            conn.disconnect()
        }
    }

    /**
     * 取那个临时下载地址。**不带任何请求头**：那是阿里云签名过的 OSS 地址，
     * 把 `Authorization: Bearer <他的秘钥>` 跟着发过去等于把秘钥送到另一个主机上。
     * 日志里也不打这个 URL —— 它自带签名，谁拿到都能下。
     */
    private fun download(url: String): ByteArray {
        val conn = try {
            URL(url).openConnection()
        } catch (e: IOException) {
            throw AiError("音频下载地址取不到（${e.javaClass.simpleName}）")
        } as? HttpURLConnection ?: throw AiError("音频下载地址不是 http/https")
        return try {
            conn.requestMethod = "GET"
            conn.connectTimeout = 15_000
            conn.readTimeout = 60_000
            val code = conn.responseCode
            val bytes = (if (code in 200..299) conn.inputStream else conn.errorStream)?.use { it.readBytes() }
                ?: throw AiError("音频下载地址回了 0 字节")
            Log.d(TAG, "audio host=${URL(url).host} code=$code bytes=${bytes.size}")
            if (code !in 200..299) throw AiError("音频下载失败：HTTP $code")
            if (bytes.isEmpty()) throw AiError("音频下载失败：0 字节")
            bytes
        } catch (e: IOException) {
            Log.w(TAG, "audio download failed: ${e.javaClass.simpleName}")
            throw AiError("音频下载中断（${e.javaClass.simpleName}）")
        } finally {
            conn.disconnect()
        }
    }
}

/**
 * 同一段文字的音频落进 `cacheDir/voice/`，文件名是"服务商+模型+音色+正文"的 SHA-1。
 * 命中就直接播，不再花一次钱 —— 这一档按字符计费，重复听同一段不该重复请求。
 * 这些文件在「我的 → 清理缓存」里数得到（那一行量的是整个 cache 目录）。
 */
object AiVoiceCache {

    fun dir(context: Context): File = File(context.cacheDir, "voice").apply { mkdirs() }

    fun fileFor(context: Context, kind: AiSpeech.Kind, model: String, voice: String, text: String): File {
        val key = "${kind.name}\n$model\n$voice\n$text"
        val digest = MessageDigest.getInstance("SHA-1").digest(key.toByteArray())
        val name = digest.joinToString("") { "%02x".format(it) }.take(28)
        // 扩展名不写 mp3：阿里那档回的是 wav，`MediaPlayer` 靠嗅探不靠后缀，写死了是个假话
        return File(dir(context), "$name.audio")
    }

    /**
     * 就地修一个已经存下来的 wav 的长度字段。阿里那档把头里的长度写成 `0x7FFFFFFF`，
     * `MediaPlayer` 照它算时长会把 16.6 秒报成 **745:39** —— 进度条和听书页的正文就都钉在 0 不动。
     * 本来就对的话一个字节都不回写。
     */
    fun heal(file: File) {
        val raw = file.readBytes()
        val fixed = AiSpeech.wavSizesFixed(raw)
        if (fixed !== raw) file.writeBytes(fixed)
    }

    /**
     * 每合成成功一段就在 `index.tsv` 上记一笔（哪本书、正文哪个偏移、多少字、多大、什么时候）。
     * 缓存文件的名字是 SHA-1，反过来读不出它是哪一段 —— 没有这一行流水，「缓存历史」就只能列一串哈希。
     */
    fun remember(context: Context, file: File, bookId: String, offset: Long, chars: Int) {
        val idx = File(dir(context), "index.tsv")
        runCatching {
            // 记到 200KB（约 4000 条）就把前面一半丢掉：这一页只用来回看，不值得无限长
            if (idx.length() > 200_000L) {
                idx.readLines().takeLast(2000).let { lines -> idx.writeText(lines.joinToString("\n") + "\n") }
            }
            idx.appendText("${file.name}\t$bookId\t$offset\t$chars\t${file.length()}\t${System.currentTimeMillis()}\n")
        }
    }

    /** 一条缓存流水。`name` 就是 `.audio` 的文件名，能直接算出路径再播，不再发请求。 */
    data class Entry(val name: String, val bookId: String, val offset: Long, val chars: Int, val bytes: Long, val at: Long)

    /** 最近听的在前；同一段只留最后一次。文件没写过就是空表。 */
    fun history(context: Context): List<Entry> = runCatching {
        File(dir(context), "index.tsv").readLines().mapNotNull { raw ->
            val c = raw.split('\t')
            if (c.size < 6) return@mapNotNull null
            val off = c[2].toLongOrNull() ?: return@mapNotNull null
            val t = c[5].toLongOrNull() ?: return@mapNotNull null
            Entry(c[0], c[1], off, c[3].toIntOrNull() ?: 0, c[4].toLongOrNull() ?: 0, t)
        }.reversed().distinctBy { it.name }
    }.getOrDefault(emptyList())

    /** 那一页要按名字取回文件；顺手判一下还在不在（「清理缓存」会整目录端掉）。 */
    fun fileNamed(context: Context, name: String): File? =
        File(dir(context), name).takeIf { it.isFile && it.length() > 0L }
}

/** 和 [com.bookreader.ui.ParagraphVoice] 同一档生命周期：阅读页持有它，离开时 close。 */
class AiVoicePlayer {

    private var player: MediaPlayer? = null

    var playing = false
        private set

    /** 混播：开着就不抢音频焦点，别的音乐/播客继续响。每一段新建播放器时套上去。 */
    var shareFocus = false

    /** 混播要用的那个系统服务：阅读页创建播放器时塞进来（这个类自己不碰 Context）。 */
    var audioManager: AudioManager? = null

    private var focusRequest: AudioFocusRequest? = null

    /** 申请"不参与焦点策略"的播放：别的媒体继续响，两边混着放。 */
    private fun askNoFocus(attrs: AudioAttributes) {
        val am = audioManager ?: return
        val req = runCatching {
            AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_NONE).setAudioAttributes(attrs).build()
        }.getOrNull() ?: return
        focusRequest = req
        runCatching { am.requestAudioFocus(req) }
            .onFailure { Log.w("AiVoice", "no-focus request failed: ${it.javaClass.simpleName}") }
    }

    /** @return 真的开口了没有。失败时把 MediaPlayer 的错误码回成一句日志，界面上由调用方给话。 */
    fun play(file: File, onEnd: () -> Unit = {}): Boolean {
        stop()
        if (!file.exists() || file.length() == 0L) return false
        return runCatching {
            MediaPlayer().apply {
                val attrs = AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
                setAudioAttributes(attrs)
                // 「允许其他应用一起播」走的是焦点这一条：公开的 `AudioAttributes.FLAG_SHARE_AUDIO_FOCUS`
                // 在 android.jar 里根本没有（@hide，编不过），能用的只有 AUDIOFOCUS_NONE 这次申请
                if (shareFocus) askNoFocus(attrs)
                setDataSource(file.absolutePath)
                setOnPreparedListener {
                    // 新实例默认 1.0x：每一段都要把界面上选着的速率套上
                    runCatching { it.playbackParams = it.playbackParams.setSpeed(rate.coerceIn(0.5f, 2.0f)) }
                    it.start()
                }
                setOnCompletionListener { stop(); onEnd() }
                setOnErrorListener { _, what, extra ->
                    Log.w("AiVoice", "play failed what=$what extra=$extra")
                    stop()
                    true
                }
                prepareAsync()
            }
        }.onSuccess { player = it; playing = true }.isSuccess
    }

    /** 进度条要的两个数。`prepareAsync` 还没回来时 duration 是 0，界面按 0 处理成"还没就绪"。 */
    val positionMs: Int get() = runCatching { player?.currentPosition ?: 0 }.getOrDefault(0)

    val durationMs: Int get() = runCatching { player?.duration ?: 0 }.getOrDefault(0)

    /** @return seek 成没成（播放器还没准备好时不假装成功） */
    fun seekTo(ms: Int): Boolean {
        val p = player ?: return false
        val dur = runCatching { p.duration }.getOrDefault(0)
        return runCatching { p.seekTo(ms.coerceIn(0, dur.coerceAtLeast(0))) }.isSuccess
    }

    /**
     * 语速存在播放器身上（不是界面上）：换段、暂停后继续，都要拿同一个值。
     * 0.5~2.0，超出按边界取。
     */
    var rate = 1.0f

    /**
     * 把 [rate] 套到当前这个播放器上。
     *
     * **暂停中直接塞非 0 速率会让 `MediaPlayer` 自己开播**（平台行为，不是我们的 bug），
     * 所以暂停的时候先套 0f —— 真速率等 `toggle()` 继续播放那一刻再套。
     * @return false = 这台机的解码器不支持变速
     */
    fun applyRate(): Boolean {
        val p = player ?: return true
        val want = if (runCatching { p.isPlaying }.getOrDefault(false)) rate.coerceIn(0.5f, 2.0f) else 0f
        return runCatching { p.playbackParams = p.playbackParams.setSpeed(want) }
            .onFailure { Log.w("AiVoice", "applyRate failed: ${it.javaClass.simpleName}") }.isSuccess
    }

    /** 暂停 / 继续。@return 现在是不是在放。 */
    fun toggle(): Boolean {
        val p = player ?: return false
        return runCatching {
            if (p.isPlaying) {
                p.pause()
                // 停下来之后把速率归 0，免得下一次 setSpeed 把它顶得自己走起来
                p.playbackParams = p.playbackParams.setSpeed(0f)
                playing = false
                false
            } else {
                p.playbackParams = p.playbackParams.setSpeed(rate.coerceIn(0.5f, 2.0f))
                p.start()
                playing = true
                true
            }
        }.getOrDefault(playing)
    }

    fun stop() {
        focusRequest?.let { r -> runCatching { audioManager?.abandonAudioFocusRequest(r) } }
        focusRequest = null
        player?.let {
            runCatching { if (it.isPlaying) it.stop() }
            runCatching { it.release() }
        }
        player = null
        playing = false
    }

    fun close() = stop()
}
