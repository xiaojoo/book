package com.bookreader.core

import com.bookreader.core.ai.AiProtocol
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** AI 那层的纯字符串活：地址归一、提示词、回包取值。全部离线可判，不需要联网。 */
class AiProtocolTest {

    @Test
    fun endpointNormalizesTheFourWaysPeopleWriteIt() {
        assertEquals("https://x/v1/chat/completions", AiProtocol.chatEndpoint("https://x/v1"))
        assertEquals("https://x/v1/chat/completions", AiProtocol.chatEndpoint("  https://x/v1/  "))
        assertEquals("https://x/chat/completions", AiProtocol.chatEndpoint("https://x"))
        // 已经填到底的不能再拼一遍 —— 这条错了的表现是 404，看不出来是重复拼了
        assertEquals("https://x/v1/chat/completions", AiProtocol.chatEndpoint("https://x/v1/chat/completions"))
    }

    @Test
    fun endpointRejectsAnAddressItCannotUse() {
        val empty = runCatching { AiProtocol.chatEndpoint("  ") }.exceptionOrNull()
        assertTrue(empty is IllegalArgumentException)
        assertEquals("API 地址没填", empty!!.message)
        val noScheme = runCatching { AiProtocol.chatEndpoint("api.openai.com/v1") }.exceptionOrNull()
        assertEquals("地址要以 http:// 或 https:// 开头", noScheme!!.message)
    }

    @Test
    fun cleartextIsFlaggedSeparatelyFromWellFormedness() {
        assertTrue(AiProtocol.isCleartext("http://192.168.1.20:11434/v1"))
        assertFalse(AiProtocol.isCleartext("https://api.example.com/v1"))
        // 协议写错要被 chatEndpoint 拒掉，但 isCleartext 不能因此说它是"明文可发"
        assertFalse(AiProtocol.isCleartext("ftp://x"))
    }

    @Test
    fun dictPromptCarriesContextForPolyphonicChars() {
        assertEquals("【要查】银行", AiProtocol.dictUser("银行"))
        assertEquals("【上下文】他去银行取钱\n【要查】银行", AiProtocol.dictUser("银行", "他去银行取钱"))
        // 空白上下文等于没有，不要留一行空壳
        assertEquals("【要查】银行", AiProtocol.dictUser("银行", "   "))
        assertTrue(AiProtocol.DICT_SYSTEM.contains("拼音："))
    }

    @Test
    fun completionTextReadsTheAnswerNotTheEchoOfOurOwnPrompt() {
        // 真实回包常把 messages 原样带回来，里面就有我们发出去的那句 content。
        // 从头找 "content" 会读到自己的提示词 —— 这条判据专门钉这个
        val body = """
            {"model":"gpt-x","messages":[{"role":"system","content":"你是一个中文词典"},
             {"role":"user","content":"【要查】银行"}],
             "choices":[{"index":0,"message":{"role":"assistant","content":"拼音：yín háng\n释义：办理存汇款的金融机构"},
             "finish_reason":"stop"}],"usage":{"prompt_tokens":30}}
        """.trimIndent()
        val got = AiProtocol.completionText(body)
        assertEquals("拼音：yín háng\n释义：办理存汇款的金融机构", got)
        assertFalse("读到提示词了：取值没跳过 choices 之前那段", got!!.contains("中文词典"))
    }

    @Test
    fun completionTextUnescapesJsonStringEscapes() {
        // 反斜杠手拼：raw 字符串里 `\uXXXX` 会被 Kotlin 词法先吃掉，写不出"JSON 的 \u 转义"这个形状
        val bs = 92.toChar().toString()
        val body = """{"choices":[{"message":{"content":"A${bs}u6c49${bs}nB${bs}"引号${bs}"${bs}${bs}斜杠"}}]}"""
        assertEquals("A汉\nB\"引号\"\\斜杠", AiProtocol.completionText(body))
    }

    @Test
    fun completionTextGivesUpInsteadOfInventingAnAnswer() {
        assertNull("没有 choices 也没有 content", AiProtocol.completionText("""{"error":{"message":"bad key"}}"""))
        assertNull("content 不是字符串（有的网关回 null）", AiProtocol.completionText("""{"choices":[{"message":{"content":null}}]}"""))
        assertNull("全空白不算答案", AiProtocol.completionText("""{"choices":[{"message":{"content":"   "}}]}"""))
        assertNull("截断的包", AiProtocol.completionText("""{"choices":[{"message":{"content":"没写完"""))
    }

    @Test
    fun chatBodySurvivesTheCharactersANovelContains() {
        val tricky = "他说\"走\"，路径 C:\\新\\卷 一行\n二行\t制表　全角 以及 emoji 😀 和 \u0001 控制符"
        val body = AiProtocol.chatBody("gpt-x", AiProtocol.DICT_SYSTEM, tricky)
        // 发和收共用一套转义，就必须互逆：从 user 那条之后找 content，要能原样读回来
        val from = body.indexOf("\"role\":\"user\"")
        assertTrue("请求体里没有 user 那条", from > 0)
        assertEquals(tricky, AiProtocol.jsonStringAfter(body, "content", from))
        // 没转义的话，正文里一个裸引号就会把 JSON 打断 —— 这条判据保证我们发出去的还是合法形状
        assertFalse("正文里的引号没被转义", body.substring(from).contains("说\"走\""))
    }

    @Test
    fun errorTextPrefersTheServersOwnWords() {
        assertEquals("无效的 api key", AiProtocol.errorText(401, """{"error":{"message":"无效的 api key","type":"auth_error"}}"""))
        assertEquals("HTTP 502", AiProtocol.errorText(502, "<html>网关挂了</html>"))
    }
}
