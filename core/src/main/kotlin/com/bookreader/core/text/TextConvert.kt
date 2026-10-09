package com.bookreader.core.text

/**
 * 显示层整段替换（繁→简）唯一的硬约束：**长度不许变**。
 *
 * 页上的每一行都是按源文的绝对字符偏移切的（[com.bookreader.core.model.TextLine.start/end]），
 * 换完字数一旦对不上，行就会切到隔壁行的字上去。所以长度变了就整段退回原文 ——
 * 宁可这一页不转，也不能把行切错。
 */
fun safeConvert(source: String, convert: (String) -> String): String {
    if (source.isEmpty()) return source
    val out = runCatching { convert(source) }.getOrNull() ?: return source
    return if (out.length == source.length) out else source
}
