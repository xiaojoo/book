package com.bookreader.data

import android.content.Context
import androidx.core.content.edit
import com.bookreader.booksource.Rule
import com.bookreader.booksource.RuleLoader
import java.io.File
import java.security.MessageDigest

/**
 * 一条存在本机的书源：文件 + 解析结果，或者解析失败的那句话。
 *
 * 解析失败不抛出去是有原因的：一个 40 条源的目录里坏一条，界面应该把那一条标灰并写出为什么坏，
 * 而不是整页打不开。
 */
class StoredSource(val file: File, val rule: Rule?, val error: String?) {

    /** 开/关记在 prefs 里用的是这个 id（文件名），不是规则里的 id：两条源写同一个 id 时它俩还得能分开。 */
    val id: String get() = file.nameWithoutExtension
    val title: String get() = rule?.name ?: id
    val usable: Boolean get() = rule != null
}

/**
 * 书籍源 = `filesDir/booksource/<名字>.json`，一个文件一条源：导入就是拷进来、卸载就是删掉，
 * 和"下载的书存在自己那格里"同一套。prefs 里只留"哪几条开着"。
 *
 * 为什么不塞进 SharedPreferences：源文件是人手写、别人分享的，得能原样导进导出；
 * 把 JSON 存进一个 prefs 字符串里，坏了连是哪条都不知道。
 */
class BookSourceStore(private val context: Context) {

    private val root = File(context.filesDir, "booksource").apply { mkdirs() }
    private val dlRoot = File(context.cacheDir, "dl")
    private val p = context.getSharedPreferences("reader", Context.MODE_PRIVATE)

    /**
     * 随包的默认源：第一次用到时从 jar 资源里拷进 filesDir，之后就是普通文件 —— 能关、能删。
     *
     * 每个名字只种一次（`seeded.<名字>` 记在 prefs 里）：删掉一条默认源之后不该被"自愈"回来，
     * 那样那颗删除就是假的。要恢复得走「导入」重新拿一次。
     */
    fun seed() {
        BUILTIN.forEach { name ->
            if (p.getBoolean("seeded.$name", false)) return@forEach
            p.edit { putBoolean("seeded.$name", true) }
            if (File(root, "$name.json").isFile) return@forEach
            restore(name)
        }
    }

    /**
     * 把随包那几条从 APK 里再拷一次出来，同名那条按包里那份覆盖。返回真拷出来几条。
     *
     * 为什么要有这颗：`seeded.<名字>` 让删掉的默认源不会自愈（那才是真的删除），代价是
     * **包里改了规则也到不了已经种过一次的手机** —— 没有这个入口，随包源一旦装上一次就永远停在第一版。
     * 覆盖而不是"只补缺"是有意的：一个人拿旧版的人才会点这颗，他要的就是换掉。
     */
    fun restoreBuiltin(): Int = BUILTIN.count { restore(it) }

    private fun restore(name: String): Boolean {
        val to = File(root, "$name.json")
        val bytes = runCatching {
            BookSourceStore::class.java.getResourceAsStream("/booksource/$name.json")?.use { it.readBytes() }
        }.getOrNull() ?: return false
        // 写一半比不写更糟：那是一份截断的规则，解析报错只说"这一条坏了"，不说为什么
        if (runCatching { to.outputStream().use { it.write(bytes) } }.isFailure) to.delete()
        return to.isFile
    }

    /**
     * 那份模板的原文，给「填一份模板进上面那格」那颗用。
     *
     * 为什么不叫他自己在手机上从零敲：键名十几个、还嵌套，手打一定会打错一个字符然后看到"不认识的键"。
     * 包里躺着这一份，粘进框里改几个值就能装。
     */
    fun templateText(): String? = runCatching {
        BookSourceStore::class.java.getResourceAsStream("/booksource/template.json")?.use {
            it.readBytes().toString(Charsets.UTF_8)
        }
    }.getOrNull()

    fun list(): List<StoredSource> =
        (root.listFiles { f: File -> f.isFile && f.name.endsWith(".json") } ?: emptyArray())
            .map { parse(it) }
            .sortedWith(compareByDescending<StoredSource> { it.usable }.thenBy { it.title })

    /** 开着且解析得动的源 —— 搜索页上能选的就是这几条。 */
    fun usable(): List<Rule> = list().filter { it.usable && enabled(it.id) }.mapNotNull { it.rule }

    /**
     * 装一条源：这段文本可以是从网址抓回来的，也可以是用户直接粘进来的。
     *
     * 存的就是他给的那份原文（不重新序列化）：字段顺序、注释性空格都保持他看到的样子，
     * 下次「删掉这条源」导出去还是同一份。
     *
     * @return null = 装上了；否则是给用户看的原话。不抛：装不上要在这层抽屉里当场读回来，
     *   而不是让人对着一段 JSON 猜是哪一格写错了。
     */
    fun install(text: String, where: String): String? {
        val rule = try {
            RuleLoader.load(text, where)
        } catch (e: Exception) {
            return "这不是一条能用的书源：" + (e.message ?: e.javaClass.simpleName)
        }
        val slug = rule.id.filter { !ILLEGAL.contains(it) }.take(60).ifBlank { "source" }
        val to = File(root, "$slug.json")
        val dup = list().firstOrNull { it.file != to && it.rule?.id == rule.id }
        if (dup != null) {
            // 两条源共用一个 id 的话，抓下来的章会存进同一个格子：A 书的第 9 章会被当成 B 书的第 9 章续上
            return "已经有一条 id 为 \"" + rule.id + "\" 的源了（" + dup.title + "），改个 id 再装"
        }
        val wrote = runCatching { to.outputStream().use { it.write(text.toByteArray(Charsets.UTF_8)) } }
        if (wrote.isFailure) {
            to.delete()
            return "存不下去：" + (wrote.exceptionOrNull()?.message ?: "")
        }
        // 重装的这条要立刻能搜：上一次被关掉的记录跟着 id 走，不清掉的话界面会摆着一颗灰色的新源
        p.edit { remove("srcOff.$slug") }
        return null
    }

    /** 删一条源，连它那份下载缓存一起删：留着就是永远读不到的垃圾字节。 */
    fun remove(id: String): Boolean {
        val ok = File(root, "$id.json").delete()
        File(dlRoot, id).deleteRecursively()
        p.edit { remove("srcOff.$id") }
        return ok
    }

    fun enabled(id: String): Boolean = !p.getBoolean("srcOff.$id", false)

    fun setEnabled(id: String, on: Boolean) {
        p.edit { if (on) remove("srcOff.$id") else putBoolean("srcOff.$id", true) }
    }

    /** 某本书的断点缓存目录：按 源 id + 书页地址 分格，删源时能整片带走。 */
    fun cacheDir(sourceId: String, bookUrl: String): File = File(File(dlRoot, sourceId), sha1(bookUrl))

    private fun parse(f: File): StoredSource = try {
        StoredSource(f, RuleLoader.load(f.readText(), f.nameWithoutExtension), null)
    } catch (e: Exception) {
        StoredSource(f, null, e.message ?: e.javaClass.simpleName)
    }

    private companion object {
        /**
         * 随包那几条默认源的名字。写死而不是列目录：APK 里的资源目录没法可靠枚举，
         * 加一条源就同时改这个列表和 `booksource/src/main/resources/booksource/`。
         *
         * 进来的条件是三格都从**真站取回的页面**判过：搜索出行、目录按正文顺序、正文抽得出字数。
         * 燃文小说网因此被摘掉 —— 它搜得到、目录也齐，但正文是 `<script>document.writeln(qsbs.bb('BASE64'))</script>
         * 拼出来的，剥掉 script 之后 `#content` 只剩 726 字广告（2026-10-11 实测），下完是一本垃圾书。
         * 这条判据留在 `RealSiteFixturesTest` 里，谁想再把它加回来会先撞上。
         */
        val BUILTIN = listOf("mengshu", "aixiashu")

        /** Windows 那套保留字符一并挡掉：这台机上文件名会跟着书目录被 adb/压缩来回倒腾。 */
        val ILLEGAL = charArrayOf('/', '\\', ':', '*', '?', '"', '<', '>', '|')

        fun sha1(s: String): String =
            MessageDigest.getInstance("SHA-1").digest(s.toByteArray(Charsets.UTF_8))
                .joinToString("") { "%02x".format(it) }
    }
}
