package com.bookreader.booksource

/**
 * 按文本读一份夹具，并把行尾统一成 `\n`。
 *
 * 好几条判据是拿带 `\n` 的整段去 `replace` 改夹具的（换 crawl 那一段、删掉必填的一格）。
 * 仓库里存的是 LF，而 Windows 上 `git checkout` 会按 autocrlf 落成 CRLF —— 那种锚点在 CRLF 的副本上
 * **静默匹配不上**：replace 什么都没改，测的就成了没被改过的默认值（并发是 3 而不是 1、四章而不是两章）。
 * 在入口收一次行尾，判据就不取决于这台机怎么把文件检出来的。
 */
fun fixtureText(path: String): String =
    checkNotNull(RuleLoader::class.java.getResourceAsStream(path)) { "缺夹具 $path" }
        .use { it.readBytes().toString(Charsets.UTF_8) }
        .replace("\r\n", "\n")
