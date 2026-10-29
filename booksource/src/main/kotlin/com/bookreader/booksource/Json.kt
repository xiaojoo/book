package com.bookreader.booksource

/** 书源文件写坏了要说清是哪里：这一族配置是人手编辑的，静默读错等于搜出乱七八糟的书。 */
class RuleException(message: String) : IllegalArgumentException(message)

/**
 * 够用的 JSON 读取器：对象、数组、字符串、数字、布尔、null，仅此。
 * 控制字符转义里只支持 n / r / t（书源文件用不到 b 和 f，支持了就得多验两条）。
 *
 * 为什么不引序列化库：这一层只读"我们自己定义形状"的书源文件，几十个字段而已；
 * :core 里那套 AI 协议同样是手写读 JSON（AiProtocol.jsonStringAfter），
 * 多一个依赖就要多解释一次"读个配置为什么要装框架"。
 *
 * 刻意严格：括号不配对、结尾有多余内容、同一个键写两遍，全部抛错。
 * "能少读一点就少读一点"在这里是害人的 —— 字段名打错一个字母，那条源看着是配好了，
 * 搜出来却是空的，而界面只会说"没结果"。
 */
object Json {

    fun parse(text: String): Any? {
        val c = Cursor(text)
        c.ws()
        val v = c.value()
        c.ws()
        if (!c.done()) throw RuleException("JSON 第 " + c.line + " 行：结尾之后还有多余内容")
        return v
    }

    /** 取字段用的便捷读法：不是对象就报错。 */
    @Suppress("UNCHECKED_CAST")
    fun obj(v: Any?): Map<String, Any?> = v as? Map<String, Any?>
        ?: throw RuleException("这里要的是一个对象，实际读到的是 " + (v?.javaClass?.simpleName ?: "null"))

    @Suppress("UNCHECKED_CAST")
    fun arr(v: Any?): List<Any?> = v as? List<Any?>
        ?: throw RuleException("这里要的是一个数组，实际读到的是 " + (v?.javaClass?.simpleName ?: "null"))

    /**
     * 点号路径取一格：`result.0.url` —— 中间那段数字是数组下标。取不到回**空串**。
     *
     * 给 `chapter.file.path` 那一格用（取址口回的那种小 JSON）。不是通用 JSON 查询语言：
     * 通配、过滤器、负下标都没有，写不出来就在规则里换一条路径，别在这里加语法。
     */
    @Suppress("UNCHECKED_CAST")
    fun path(text: String, dotted: String): String {
        var v: Any? = runCatching { parse(text) }.getOrNull()
        for (seg in dotted.split('.').filter { it.isNotEmpty() }) {
            val cur = v
            v = when (cur) {
                is Map<*, *> -> (cur as? Map<String, Any?>)?.get(seg)
                is List<*> -> seg.toIntOrNull()?.let { (cur as? List<Any?>)?.getOrNull(it) }
                else -> null
            } ?: return ""
        }
        return when (v) {
            is String -> v
            is Number -> v.toString()
            else -> ""
        }
    }

    private class Cursor(private val s: String) {
        private var i = 0
        var line = 1
            private set

        fun done(): Boolean = i >= s.length

        private fun cur(): Char = s[i]

        private fun at(c: Char): Boolean = !done() && cur() == c

        fun ws() {
            while (!done()) {
                val c = cur()
                if (c == '\n') {
                    line++
                    i++
                } else if (c == ' ' || c == '\t' || c == '\r') {
                    i++
                } else {
                    return
                }
            }
        }

        private fun expect(c: Char) {
            if (!at(c)) throw RuleException("第 " + line + " 行：想要 " + q(c) + "，实际是 " + what())
            i++
        }

        private fun q(c: Char) = "'" + c + "'"

        private fun what(): String = if (done()) "文件结束" else q(cur())

        fun value(): Any? {
            if (done()) throw RuleException("第 " + line + " 行：值还没读到就结束了")
            return when (cur()) {
                '{' -> objectValue()
                '[' -> arrayValue()
                '"' -> stringValue()
                't' -> literal("true", true)
                'f' -> literal("false", false)
                'n' -> literal("null", null)
                else -> numberValue()
            }
        }

        private fun literal(w: String, v: Any?): Any? {
            if (!s.startsWith(w, i)) throw RuleException("第 " + line + " 行：这里只可能是 true/false/null")
            i += w.length
            return v
        }

        private fun objectValue(): Map<String, Any?> {
            expect('{')
            val out = LinkedHashMap<String, Any?>()
            ws()
            if (at('}')) {
                i++
                return out
            }
            while (true) {
                ws()
                val k = stringValue()
                ws()
                expect(':')
                ws()
                if (out.containsKey(k)) throw RuleException("第 " + line + " 行：键 " + q2(k) + " 写了两遍")
                out[k] = value()
                ws()
                if (done()) throw RuleException("第 " + line + " 行：对象没闭合")
                if (at(',')) {
                    i++
                } else if (at('}')) {
                    i++
                    return out
                } else {
                    throw RuleException("第 " + line + " 行：键值对之间要 ',' 或 '}'，实际是 " + what())
                }
            }
        }

        private fun arrayValue(): List<Any?> {
            expect('[')
            val out = ArrayList<Any?>()
            ws()
            if (at(']')) {
                i++
                return out
            }
            while (true) {
                ws()
                out.add(value())
                ws()
                if (done()) throw RuleException("第 " + line + " 行：数组没闭合")
                if (at(',')) {
                    i++
                } else if (at(']')) {
                    i++
                    return out
                } else {
                    throw RuleException("第 " + line + " 行：数组元素之间要 ',' 或 ']'，实际是 " + what())
                }
            }
        }

        private fun numberValue(): Any {
            val from = i
            if (at('-')) i++
            while (!done() && cur().isDigit()) i++
            var integral = true
            if (!done() && cur() == '.') {
                integral = false
                i++
                while (!done() && cur().isDigit()) i++
            }
            if (!done() && (cur() == 'e' || cur() == 'E')) {
                integral = false
                i++
                if (!done() && (cur() == '+' || cur() == '-')) i++
                while (!done() && cur().isDigit()) i++
            }
            val t = s.substring(from, i)
            if (t.isEmpty() || t == "-") throw RuleException("第 " + line + " 行：这里要的是一个值")
            if (integral) return t.toLongOrNull() ?: throw RuleException("第 " + line + " 行：整数写歪了 " + q2(t))
            return t.toDoubleOrNull() ?: throw RuleException("第 " + line + " 行：小数写歪了 " + q2(t))
        }

        private fun q2(v: String) = "\"" + v + "\""

        private fun stringValue(): String {
            expect('"')
            val b = StringBuilder()
            while (true) {
                if (done()) throw RuleException("第 " + line + " 行：字符串没闭合")
                val c = cur()
                i++
                if (c == '"') return b.toString()
                if (c != '\\') {
                    b.append(c)
                    continue
                }
                if (done()) throw RuleException("第 " + line + " 行：转义写到一半")
                val e = cur()
                i++
                when (e) {
                    '"' -> b.append('"')
                    '\\' -> b.append('\\')
                    '/' -> b.append('/')
                    'n' -> b.append('\n')
                    'r' -> b.append('\r')
                    't' -> b.append('\t')
                    'u' -> {
                        if (i + 4 > s.length) throw RuleException("第 " + line + " 行：\\u 后面不足 4 位")
                        val hex = s.substring(i, i + 4)
                        i += 4
                        b.append(hex.toInt(16).toChar())
                    }
                    else -> throw RuleException("第 " + line + " 行：不认的转义 \\" + e)
                }
            }
        }
    }
}
