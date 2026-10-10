package com.bookreader.data

import android.util.Log
import com.bookreader.core.ai.AiProtocol
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/** 一句人话就能显示在界面上的失败：地址没填 / 连不上 / 秘钥不对 / 回包没文字。 */
class AiError(message: String) : Exception(message)

/**
 * OpenAI 兼容的 `/chat/completions`，只取文字。整段一次请求，不做流式（词典一问一答，
 * 逐字吐在这里只会被误当成"卡住了"）。**必须在 IO 线程上调用**，自己不切线程。
 *
 * 日志里不出现秘钥、也不出现完整地址：有些中转站把 token 直接写在 path 里。
 */
object AiClient {

    private const val TAG = "AiClient"

    fun chat(baseUrl: String, key: String, model: String, system: String, user: String): String {
        val endpoint = runCatching { AiProtocol.chatEndpoint(baseUrl) }
            .getOrElse { throw AiError(it.message ?: "API 地址不能用") }
        if (model.isBlank()) throw AiError("模型名没填")
        if (key.isBlank()) throw AiError("秘钥没填，或换机后失效要重填")

        val body = AiProtocol.chatBody(model, system, user).toByteArray()
        val conn = try {
            URL(endpoint).openConnection()
        } catch (e: IOException) {
            throw AiError("这个地址解析不了（${e.javaClass.simpleName}）")
        } as? HttpURLConnection ?: throw AiError("这个协议不是 http/https")

        return try {
            conn.requestMethod = "POST"
            conn.connectTimeout = 15_000
            conn.readTimeout = 60_000
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "application/json; charset=utf-8")
            conn.setRequestProperty("Accept", "application/json")
            conn.setRequestProperty("Authorization", "Bearer $key")
            conn.setFixedLengthStreamingMode(body.size)
            conn.outputStream.use { it.write(body) }

            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val text = stream?.use { it.readBytes().toString(Charsets.UTF_8) } ?: ""
            if (code in 200..299) {
                Log.d(TAG, "host=${URL(endpoint).host} code=$code bytes=${text.length}")
            } else {
                // 和 AiVoice 同一条规矩：服务端给的原因要落进日志，不能只在屏幕上闪一下
                Log.w(
                    TAG,
                    "host=${URL(endpoint).host} code=$code msg=${
                        (AiProtocol.jsonStringAfter(text, "message") ?: text).take(200).replace('\n', ' ')
                    }",
                )
            }
            if (code !in 200..299) throw AiError(AiProtocol.errorText(code, text))
            AiProtocol.completionText(text) ?: throw AiError("回包里没有正文（这一档接口可能不出文字）")
        } catch (e: IOException) {
            Log.w(TAG, "request failed: ${e.javaClass.simpleName}")
            throw AiError("连不上这个地址（${e.javaClass.simpleName}）")
        } finally {
            conn.disconnect()
        }
    }
}
