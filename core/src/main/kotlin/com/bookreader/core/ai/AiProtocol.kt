package com.bookreader.core.ai

/**
 * AI 那一侧不需要 Android、也不需要联网的三件活：地址归一、提示词、把回包里的文字抠出来。
 * 放 `:core` 是因为这三条都能在 JVM 上正反各判一次 —— 真机上只验"网络通不通"这一件，
 * 地址拼错、回包读错这类毛病在没有网络的测试里就该抓住。
 */
object AiProtocol {

    /**
     * 他手上那种接口，地址能写成四五种样子：`https://x/v1`、`https://x/v1/`、`https://x`、
     * 甚至直接填到 `https://x/v1/chat/completions`。全部归一成后者，且**不重复拼**。
     * @throws IllegalArgumentException 空着或没写协议 —— 界面直接把这句话显示出来
     */
    fun chatEndpoint(base: String): String {
        var b = base.trim()
        require(b.isNotEmpty()) { "API 地址没填" }
        require(b.startsWith("http://") || b.startsWith("https://")) { "地址要以 http:// 或 https:// 开头" }
        while (b.endsWith("/")) b = b.dropLast(1)
        if (b.endsWith("/chat/completions")) return b
        return "$b/chat/completions"
    }

    /** 明文链路要在界面上说出来，不是悄悄发出去（秘钥会跟着这条链路走） */
    fun isCleartext(base: String): Boolean = base.trim().startsWith("http://")

    /** 固定三行的格式，是为了回包能拆成三档显示；不这么要求就会收到一整段散文 */
    const val DICT_SYSTEM =
        "你是一个中文词典。用户给出一个词或一段话，只回三行，不要编号、不要 markdown、不要多余解释：\n" +
            "拼音：<句中每个汉字的带调拼音；多音字按用户给的上下文取音；非汉字原样保留>\n" +
            "释义：<现代汉语里的意思，一到两句>\n" +
            "译文：<把整段翻成英文；用户给的本来就是外文时，翻成中文>"

    /** 上下文单独给：多音字（银行/行走）和"一/不"变调都只有上下文能定，只给一个词必然靠猜 */
    fun dictUser(query: String, context: String? = null): String {
        val q = query.trim()
        val c = context?.trim()?.takeIf { it.isNotEmpty() } ?: return "【要查】$q"
        return "【上下文】$c\n【要查】$q"
    }

    /**
     * 手写一个够用的 JSON 字符串读取器：`:core` 是纯 JVM，Android 那个 `org.json` 在这里没有，
     * 而我不想为一个取值再拉一个依赖进来。只认"某个键后面紧跟的字符串值"这一种形状。
     * @return 解码后的字符串；这个键不存在、或值不是字符串时返回 null
     */
    fun jsonStringAfter(body: String, key: String, from: Int = 0): String? {
        val k = body.indexOf("\"$key\"", from)
        if (k < 0) return null
        val colon = body.indexOf(':', k + key.length + 2)
        if (colon < 0) return null
        var i = colon + 1
        while (i < body.length && body[i].isWhitespace()) i++
        if (i >= body.length || body[i] != '"') return null
        val sb = StringBuilder()
        var j = i + 1
        while (j < body.length) {
            val c = body[j]
            if (c == '\\') {
                val e = body.getOrNull(j + 1) ?: return null
                when (e) {
                    'n' -> sb.append('\n')
                    't' -> sb.append('\t')
                    'r' -> sb.append('\r')
                    'b' -> sb.append('\b')
                    // Kotlin 没有 \f 这个转义，按码点写
                    'f' -> sb.append(12.toChar())
                    '"' -> sb.append('"')
                    '\\' -> sb.append('\\')
                    '/' -> sb.append('/')
                    'u' -> {
                        val hex = body.substring(j + 2, j + 6)
                        val cp = hex.toIntOrNull(16) ?: return null
                        sb.append(cp.toChar())
                        j += 4
                    }
                    else -> return null
                }
                j += 2
                continue
            }
            if (c == '"') return sb.toString()
            sb.append(c)
            j++
        }
        return null
    }

    /**
     * 发出去的请求体。转义和上面那个读取器**共用一套规则**，所以"小说里的引号/反斜杠/换行会不会把包弄坏"
     * 这一条能在 JVM 上正反判一次（见 `chatBodySurvivesTheCharactersANovelContains`）。
     */
    fun jsonString(value: String): String = buildString {
        append('"')
        for (c in value) {
            when (c) {
                '"' -> append("\\\"")
                '\\' -> append("\\\\")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                else -> if (c < ' ') {
                    append("\\u").append(c.code.toString(16).padStart(4, '0'))
                } else {
                    append(c)
                }
            }
        }
        append('"')
    }

    fun chatBody(model: String, system: String, user: String): String =
        "{\"model\":${jsonString(model)},\"temperature\":0,\"stream\":false," +
            "\"messages\":[{\"role\":\"system\",\"content\":${jsonString(system)}}," +
            "{\"role\":\"user\",\"content\":${jsonString(user)}}]}"

    /**
     * 取 `choices[0].message.content`。从 `"choices"` 之后开始找，是因为回包里
     * `messages` 常常被原样带回来 —— 里面就有我们发出去的那句 `"content"`，从头找会读到自己的提示词。
     */
    fun completionText(body: String): String? =
        jsonStringAfter(body, "content", body.indexOf("\"choices\"").coerceAtLeast(0))?.trim()?.takeIf { it.isNotEmpty() }

    /** 出错时那一串人话：优先用服务端给的 `error.message`，没有就报状态码 */
    fun errorText(status: Int, body: String): String =
        jsonStringAfter(body, "message")?.trim()?.takeIf { it.isNotEmpty() } ?: "HTTP $status"
}
