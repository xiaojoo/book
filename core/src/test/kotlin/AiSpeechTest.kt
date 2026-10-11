package com.bookreader.core

import com.bookreader.core.ai.AiProtocol
import com.bookreader.core.ai.AiSpeech
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/** 语音合成那两家的请求形状和回包读法。全离线判，不需要真 key。 */
class AiSpeechTest {

    @Test
    fun endpointAppendsOnlyThePathEachVendorWants() {
        assertEquals("https://x/v1/audio/speech", AiSpeech.endpoint("https://x/v1", AiSpeech.Kind.OpenAi))
        assertEquals("https://x/v1/audio/speech", AiSpeech.endpoint("  https://x/v1/ ", AiSpeech.Kind.OpenAi))
        assertEquals("https://x/v1/audio/speech", AiSpeech.endpoint("https://x/v1/audio/speech", AiSpeech.Kind.OpenAi))
        // MiniMax 的主机名有好几种（api.minimax.cn / api.minimaxi.com），所以只补路径、不动 host
        assertEquals("https://api.minimax.cn/v1/t2a_v2", AiSpeech.endpoint("https://api.minimax.cn", AiSpeech.Kind.MiniMax))
        assertEquals("https://x/v1/t2a_v2", AiSpeech.endpoint("https://x/v1/t2a_v2", AiSpeech.Kind.MiniMax))
        assertEquals("https://x/v1/t2a", AiSpeech.endpoint("https://x/v1/t2a", AiSpeech.Kind.MiniMax))
        // 阿里的专属空间域名每个业务空间不同，同样只补路径
        assertEquals(
            "https://llm-abc.cn-beijing.maas.aliyuncs.com/api/v1/services/audio/tts/SpeechSynthesizer",
            AiSpeech.endpoint("https://llm-abc.cn-beijing.maas.aliyuncs.com/", AiSpeech.Kind.QwenHttp),
        )
        assertEquals(
            "https://x/api/v1/services/audio/tts/SpeechSynthesizer",
            AiSpeech.endpoint("https://x/api/v1/services/audio/tts/SpeechSynthesizer", AiSpeech.Kind.QwenHttp),
        )
        val bad = runCatching { AiSpeech.endpoint("api.minimax.cn", AiSpeech.Kind.MiniMax) }.exceptionOrNull()
        assertEquals("语音地址要以 http:// 或 https:// 开头", bad!!.message)
    }

    @Test
    fun bodiesUseDifferentNamesAndNeitherLeaksTheOtherShape() {
        val openai = AiSpeech.body(AiSpeech.Kind.OpenAi, "tts-1", "alloy", "第一段")
        val minimax = AiSpeech.body(AiSpeech.Kind.MiniMax, "speech-02-hd", "male-qn-qingse", "第一段")
        // OpenAI 的字段叫 input、MiniMax 的叫 text —— 写错的表现是 400，界面上看不出来是哪家
        assertTrue(openai.contains("\"input\""))
        assertFalse("OpenAI 那档不该发 text", openai.contains("\"text\""))
        assertTrue(minimax.contains("\"text\""))
        assertFalse("MiniMax 那档没有 input 这个键", minimax.contains("\"input\""))
        assertTrue(openai.contains("\"response_format\":\"mp3\""))
        assertTrue(minimax.contains("\"voice_id\""))
        assertTrue(minimax.contains("\"format\":\"mp3\""))
        assertTrue(minimax.contains("\"stream\":false"))
        // 阿里的 input 是**对象**，正文在 input.text 里 —— 按 OpenAI 那套把 input 当字符串发过去就是 400
        val qwen = AiSpeech.body(AiSpeech.Kind.QwenHttp, "qwen-audio-3.1-tts-flash", "longanhuan_v3.6", "第一段")
        assertFalse("阿里的 input 不能是字符串", qwen.contains("\"input\":\""))
        assertTrue(qwen.contains("\"input\":{\"text\":"))
        assertTrue(qwen.contains("\"voice\":"))
        assertTrue(qwen.contains("\"format\":\"wav\""))
        // 官方 curl 示例给的就是 wav + 24000；只发 mp3 不给 sample_rate 时引擎回 411（真机量过）
        assertTrue(qwen.contains("\"sample_rate\":24000"))
        assertFalse("阿里这档没有 response_format 这个键", qwen.contains("response_format"))
    }

    @Test
    fun bodiesSurviveNovelTextBothWays() {
        val tricky = "他说\"走\"，C:\\新\\卷\n二行\t制表 中文 😀"
        val keys = mapOf(
            AiSpeech.Kind.OpenAi to "input",
            AiSpeech.Kind.MiniMax to "text",
            AiSpeech.Kind.QwenHttp to "text",
        )
        for ((kind, key) in keys) {
            val body = AiSpeech.body(kind, "m", "v", tricky)
            assertEquals("$kind 的正文没能原样读回", tricky, AiProtocol.jsonStringAfter(body, key))
        }
    }

    @Test
    fun qwenAudioUrlReadsTheDownloadLinkNotSomethingElse() {
        // 非流式：音频不在包里，只有一个临时下载地址；data 恒为空串
        val body = """{"output":{"audio":{"id":"audio1","data":"","url":"https://oss.example.com/a.mp3?sig=1",""" +
            """"expires_at":1790000000},"finish_reason":"stop"},"usage":{"characters":12},"request_id":"r1"}"""
        assertEquals("https://oss.example.com/a.mp3?sig=1", AiSpeech.qwenAudioUrl(body))
        assertNull("回包没有 output 就没有地址", AiSpeech.qwenAudioUrl("""{"code":"InvalidParameter","message":"voice not found"}"""))
        // data 是空串时不能被当成"音频在这里"——这一条保证我们不会去解一个空字符串
        assertNull(AiSpeech.qwenAudioUrl("""{"output":{"audio":{"data":"","url":""}}}"""))
        // 值不是 http(s) 地址（有的实现放的是相对路径）时宁可报没有，也不去拼一个猜出来的地址
        assertNull(AiSpeech.qwenAudioUrl("""{"output":{"audio":{"url":"/tmp/a.mp3"}}}"""))
    }

    @Test
    fun minimaxAudioSkipsTheEchoedAudioFieldBeforeData() {
        // 回包里 input.audio_setting 那一族字段名里就带 "audio"，从头找会拿到"设置"而不是音频本体
        val body = """{"model":"speech-02-hd","input":{"audio_setting":{"audio":"WRONG"}},""" +
            """"data":{"status":1,"audio":"494433","subtitle_file":""},"base_resp":{"status_code":0}}"""
        val got = AiSpeech.minimaxAudio(body)
        assertEquals("494433", got)
        assertArrayEquals(byteArrayOf(0x49, 0x44, 0x33), AiSpeech.hexToBytes(got!!))
        assertNull("没有 data 就没有音频", AiSpeech.minimaxAudio("""{"base_resp":{"status_code":1002}}"""))
    }

    @Test
    fun minimaxReportsBusinessErrorsEvenOnHttp200() {
        assertNull(AiSpeech.minimaxError("""{"data":{"audio":"494433"},"base_resp":{"status_code":0,"status_msg":"success"}}"""))
        assertEquals("rate limit reached", AiSpeech.minimaxError("""{"base_resp":{"status_code":1002,"status_msg":"rate limit reached"}}"""))
        assertEquals("语音服务返回状态码 1004", AiSpeech.minimaxError("""{"base_resp":{"status_code":1004}}"""))
        // 有的网关不包 base_resp：这种情况不能凭空造一个错误出来
        assertNull(AiSpeech.minimaxError("""{"data":{"audio":"494433"}}"""))
    }

    @Test
    fun hexDecoderRefusesAnythingThatWouldPlayHalfAClip() {
        assertArrayEquals(byteArrayOf(0x49, 0x44, 0x33), AiSpeech.hexToBytes("494433"))
        assertArrayEquals(byteArrayOf(0x49, 0x44), AiSpeech.hexToBytes("49 44\n"))   // 容忍换行的网关
        assertArrayEquals(byteArrayOf(0x00, -1), AiSpeech.hexToBytes("00ff"))
        assertNull("奇数长度", AiSpeech.hexToBytes("49443"))
        assertNull("非 hex 字符", AiSpeech.hexToBytes("49zz"))
        assertNull("空串", AiSpeech.hexToBytes("   "))
    }

    @Test
    fun voiceListKeepsHisOrderAndAcceptsEverySeparator() {
        // 中英文逗号、分号、全/半角空格、换行都算分隔符；抄重了只留第一次出现的那个位置
        assertEquals(
            listOf("longanhuan_v3.1", "longmiao_v3.1", "Chelsie"),
            AiSpeech.voices("longanhuan_v3.1， longmiao_v3.1;Chelsie\nlonganhuan_v3.1"),
        )
        assertEquals(emptyList<String>(), AiSpeech.voices("  ，； \n "))
        assertEquals(listOf("alloy"), AiSpeech.voices("　alloy　"))
    }

    @Test
    fun wavWithAStreamSizedHeaderGetsItsRealLengthsBack() {
        val payload = ByteArray(100) { (it % 7).toByte() }
        // 阿里真机的头：RIFF 和 data 都写 0x7FFFFFFF（"我还不知道多长"）
        val bad = wav(0x7FFFFFFF, 0x7FFFFFF9, payload)
        val fixed = AiSpeech.wavSizesFixed(bad)
        assertTrue("该改的没改", fixed !== bad)
        assertEquals("总长不该变", bad.size, fixed.size)
        assertEquals("RIFF 长度 = 文件 - 8", 136, le32(fixed, 4))
        assertEquals("data 长度 = 真实音频字节", 100, le32(fixed, 40))
        assertTrue("正文一个字节都不许动", fixed.copyOfRange(44, 144).contentEquals(payload))

        // 本来就对的头：回同一个数组，调用方靠"是不是同一个对象"决定要不要回写文件
        val good = wav(136, 100, payload)
        assertSame("不该改的改了", good, AiSpeech.wavSizesFixed(good))
        // mp3 没有 RIFF：原样退回，别去改别人的容器
        val mp3 = ByteArray(200) { 0x24 }
        mp3[0] = 'I'.code.toByte(); mp3[1] = 'D'.code.toByte(); mp3[2] = '3'.code.toByte()
        assertSame(mp3, AiSpeech.wavSizesFixed(mp3))
    }

    @Test
    fun pronunciationTableRewritesLongestFirstAndSkipsBadLines() {
        val table = "银行=银航\n重庆=虫庆\n重=虫\n没有等号的坏行\n=只有右边\n左边=左边\n \n"
        assertEquals("银行 和 重庆", "银航 和 虫庆", AiSpeech.applyFixes("银行 和 重庆", table))
        // 长的先换：`重庆` 先被多字那条整块换掉，不会再被单字 `重=虫` 咬第二遍
        assertEquals("虫庆的银航", AiSpeech.applyFixes("重庆的银行", table))
        // 这一条才是"长的先换"的真判据：短的先换会把长的键拆散，两边结果不一样
        assertEquals("银幸", AiSpeech.applyFixes("银行", "行=航\n银行=银幸"))
        assertEquals("银航", AiSpeech.applyFixes("银行", "银行=银幸\n行=航\n银幸=银航"))
        assertEquals("整段没命中就原样", "一句普通的话。", AiSpeech.applyFixes("一句普通的话。", table))
        // 单字那条仍然管用到没有多字条覆盖的地方
        assertEquals("虫量", AiSpeech.applyFixes("重量", table))
        assertEquals("坏行不进表：只有三条", 3, AiSpeech.parseFixes(table).size)
    }

    @Test
    fun voiceListEndpointExistsForTwoVendorsAndParserIgnoresRequestIds() {
        assertEquals("https://x/v1/audio/voices", AiSpeech.voicesEndpoint("https://x/v1", AiSpeech.Kind.OpenAi))
        assertEquals(
            "https://ws.cn-beijing.maas.aliyuncs.com/api/v1/services/audio/tts/customization",
            AiSpeech.voicesEndpoint("https://ws.cn-beijing.maas.aliyuncs.com/", AiSpeech.Kind.QwenHttp),
        )
        // MiniMax 没有列表口：回 null，界面上说清，而不是摆一颗点了没反应的按钮
        assertNull(AiSpeech.voicesEndpoint("https://api.minimax.cn", AiSpeech.Kind.MiniMax))
        assertNull(AiSpeech.voicesBody(AiSpeech.Kind.OpenAi))
        assertTrue(AiSpeech.voicesBody(AiSpeech.Kind.QwenHttp)!!.contains("list_voice"))

        // OpenAI 那家：data[] 里的 id
        assertEquals(
            listOf("alloy", "nova"),
            AiSpeech.parseVoices("""{"data":[{"id":"alloy","created":1700},{"id":"nova","created":1701}]}"""),
        )
        // 阿里复刻口：request_id 是干扰项，不能混进列表
        assertEquals(
            listOf("qwen-audio-3.0-tts-flash-myvoice-abc"),
            AiSpeech.parseVoices(
                """{"request_id":"abc-123","output":{"voice_list":[""" +
                    """{"voice_id":"qwen-audio-3.0-tts-flash-myvoice-abc","status":"OK"}]},"usage":{"count":1}}""",
            ),
        )
        assertEquals("只有业务错误码时是空表", emptyList<String>(), AiSpeech.parseVoices("""{"base_resp":{"status_code":1004}}"""))
        // 内置常见表：只摆查得到出处的名字，MiniMax 没有可核对的表就给空
        assertEquals(listOf("alloy", "nova", "onyx", "shimmer", "fable", "echo"), AiSpeech.commonVoices(AiSpeech.Kind.OpenAi, "tts-1"))
        assertEquals(listOf("Cherry", "Serena", "Ethan", "Chelsie"), AiSpeech.commonVoices(AiSpeech.Kind.QwenHttp, "qwen3-tts-flash"))
        assertEquals(listOf("longanhuan_v3.1"), AiSpeech.commonVoices(AiSpeech.Kind.QwenHttp, "qwen-audio-3.1-tts-flash"))
        assertEquals(emptyList<String>(), AiSpeech.commonVoices(AiSpeech.Kind.MiniMax, "speech-02-hd"))
        assertEquals("alloy", AiSpeech.defaultVoice(AiSpeech.Kind.OpenAi, "tts-1"))
        // 他自己抄的排前面，内置的补后面；大小写不同算同一个，**保留他写的那个拼法**（音色名可能区分大小写）
        assertEquals(
            listOf("我的音色", "Alloy", "nova"),
            AiSpeech.voiceOptions(listOf("我的音色", "Alloy"), listOf("alloy", "nova")),
        )
    }

    private fun wav(riff: Int, data: Int, payload: ByteArray): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        out.write("RIFF".toByteArray()); out.write(le(riff)); out.write("WAVE".toByteArray())
        out.write("fmt ".toByteArray()); out.write(le(16)); out.write(ByteArray(16))
        out.write("data".toByteArray()); out.write(le(data)); out.write(payload)
        return out.toByteArray()
    }

    /**
     * 「缓存范围」那一档到底缓存哪几章。判据钉的是"从正在听这一章**之后**数 N 章"：
     * 当前章不在里面（它正一段一段播着、一段一段进缓存），越界要自己收住，`null` 才是整本。
     */
    @Test
    fun chapterWindowStartsAfterTheChapterYouAreHearingAndClamps() {
        assertEquals(listOf(3, 4, 5, 6, 7), AiSpeech.chapterWindow(2, 10, 5))
        // 读到第 8 章、总共 10 章、要 50 章 ⇒ 只剩第 9 章，不吐到 53、也不吐负数
        assertEquals(listOf(9), AiSpeech.chapterWindow(8, 10, 50))
        // 最后一章之后没有章了
        assertEquals(emptyList<Int>(), AiSpeech.chapterWindow(9, 10, 5))
        // 「全部章节」= 整本，含当前章
        assertEquals((0 until 10).toList(), AiSpeech.chapterWindow(3, 10, null))
        assertEquals(emptyList<Int>(), AiSpeech.chapterWindow(0, 0, 5))
    }

    /**
     * 用真机那本书第一章切出来的 18 段当样本：被阿里 400 拒掉的那一段（`|----|----|`）
     * 判 false，念得出字的（含只有数字的那一行）判 true。
     */
    @Test
    fun onlyTextWithPronounceableCharactersGetsSent() {
        listOf("剑来", "引用：天雨虽宽，不润无根之草。", "| 名 | 值 |", "| 剑 | 来 |", "1. 有序第一",
            "fun main() {", "    println(\"泥瓶巷\")").forEach {
            assertTrue("$it 里全是竖线、连字符以外还有字，该发", AiSpeech.speakable(it))
        }
        listOf("|----|----|", "— — — — — —", "}", "", "      ", "（）", "***").forEach {
            assertFalse("$it 一个音都发不出来，发过去就是 400", AiSpeech.speakable(it))
        }
    }

    private fun le(v: Int) = byteArrayOf(
        (v and 0xFF).toByte(), ((v shr 8) and 0xFF).toByte(),
        ((v shr 16) and 0xFF).toByte(), ((v shr 24) and 0xFF).toByte(),
    )

    private fun le32(b: ByteArray, at: Int) =
        (b[at].toInt() and 0xFF) or ((b[at + 1].toInt() and 0xFF) shl 8) or
            ((b[at + 2].toInt() and 0xFF) shl 16) or ((b[at + 3].toInt() and 0xFF) shl 24)
}
