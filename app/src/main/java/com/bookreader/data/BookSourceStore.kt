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
 * 随包那一条源在手机上的一份对账结果。只用三个数就能判：手上那份的哈希、包里那份的哈希、
 * 上次往手机上写的时候记下的"当时包里那版"的哈希 —— 不碰文件不碰网，所以抽成 [decideBuiltin] 单独测。
 */
internal enum class BuiltinSync {
    /** 手机上没这个文件，而 `seeded.` 记着它种过 ⇒ 他删掉的：不该自己回来。 */
    SKIP,

    /** 手机上没这个文件，也没种过：种下去。 */
    SEED,

    /** 手上这份和账上那版一字不差 ⇒ 是我们写下去的、他没动过，而包里已经换版 ⇒ 跟着包走。 */
    UPDATE,

    /** 手上这份就是包里那一版：不用写，把账记齐（老装法的机器靠这一档第一次上账）。 */
    CURRENT,

    /** 三个哈希对不上 ⇒ 要么他手改过一条同名的，要么是这套账落地之前装的老机器：都不许自动覆盖。 */
    KEEP,
}

/**
 * 三个哈希的对账。`fileHash` 为 null = 手机上没这个文件。
 *
 * 只有"账上记着的那一版"才敢自动覆盖 —— 那份 bytes 是我们自己写下去的，改没改一比就知道。
 * 这套账是后加的，老机器上没记过账，那一档走 [KEEP]：不猜。**他按一次「恢复随包」就把账上齐了**
 * （按完文件就是包里那一版，落进 [CURRENT]），从下一个版本起这一条自己跟着包走。
 */
internal fun decideBuiltin(fileHash: String?, recorded: String?, packaged: String, everSeeded: Boolean): BuiltinSync = when {
    fileHash == null -> if (everSeeded) BuiltinSync.SKIP else BuiltinSync.SEED
    fileHash == packaged -> BuiltinSync.CURRENT
    recorded != null && fileHash == recorded -> BuiltinSync.UPDATE
    else -> BuiltinSync.KEEP
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
     * 随包的默认源：手机上没有的种下去，**种过而包里换了版、且他没改过的跟着包换**。
     *
     * 换不换只按 [decideBuiltin] 那三个哈希对账。`seeded.<名字>` 那条老规矩留着 —— 删掉的默认源
     * 不该被"自愈"回来，那样那颗删除就是假的；要拿回来走「恢复随包」或「导入」。
     */
    fun seed() {
        // 先从包里摘掉的名单清一遍。`seeded.` 只管"不再种"，**不管把已经种下的那份删掉** ——
        // 所以燃文那一条在装过它的手机上一直留着还开着：点它会一路 100% 抓回一本"本站推荐"的书名列表
        // （它的正文是 `document.writeln(qsbs.bb(BASE64))` 拼的，规则抽不到，而进度不看内容）。
        // 清一次就记 `retired.<名字>`：以后他自己导入一份同名的，不会被下次启动再误删。
        RETIRED.filterNot { p.getBoolean("retired.$it", false) }.forEach { name ->
            p.edit { putBoolean("retired.$name", true) }
            remove(name)
        }
        BUILTIN.forEach { name ->
            val packaged = bundledBytes(name) ?: return@forEach
            val ph = sha256(packaged)
            val file = File(root, "$name.json")
            val fh = if (file.isFile) runCatching { sha256(file.readBytes()) }.getOrNull() else null
            val recorded = p.getString("builtin.$name", null)
            when (decideBuiltin(fh, recorded, ph, p.getBoolean("seeded.$name", false))) {
                BuiltinSync.SKIP, BuiltinSync.KEEP -> {}
                BuiltinSync.CURRENT -> p.edit { putString("builtin.$name", ph) }
                BuiltinSync.SEED, BuiltinSync.UPDATE -> if (writeBundled(name, packaged)) {
                    p.edit {
                        putBoolean("seeded.$name", true)
                        putString("builtin.$name", ph)
                    }
                }
            }
        }
    }

    /**
     * 把随包那几条从 APK 里再拷一次出来，同名那条按包里那份覆盖。返回真拷出来几条。
     *
     * 自动换版讲条件（`KEEP` 那一档不碰），这颗不讲：他要的就是连自己改过的一起换掉。
     * 按完把账记齐，之后包里再换版就轮到 `seed()` 自己跟着走。
     */
    fun restoreBuiltin(): Int = BUILTIN.count { name ->
        val bytes = bundledBytes(name) ?: return@count false
        if (!writeBundled(name, bytes)) return@count false
        p.edit {
            putBoolean("seeded.$name", true)
            putString("builtin.$name", sha256(bytes))
        }
        true
    }

    /** 包里那一条源的原文；没随包（或被摘掉）就回 null。 */
    private fun bundledBytes(name: String): ByteArray? = runCatching {
        BookSourceStore::class.java.getResourceAsStream("/booksource/$name.json")?.use { it.readBytes() }
    }.getOrNull()

    private fun writeBundled(name: String, bytes: ByteArray): Boolean {
        val to = File(root, "$name.json")
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
         *
         * `shuhaige` = 书海阁小说网：第一台**一章切好几页**的源（剑来 第52章 = 十页），所以它是
         * `chapter.nextPage` 的第一家真用户；那一条的判据也在 `RealSiteFixturesTest` 里。
         */
        val BUILTIN = listOf("mengshu", "aixiashu", "shuhaige", "tuku", "guazi", "arxiv", "openalex", "podcast-rss", "gushiwen-audio")

        /**
         * `gushiwen-audio` 是第一条"**同一页同时给正文和朗读音频**"的源（古文岛）：
         * 章回小说和史部，一回一条目录、一回一支整回 mp3（红楼梦第一回实测 36,357,164 字节、ID3 开头、
         * 无签名无 cookie）。它**没有 `search` 那一段** —— 站内搜索 302 到登录页，所以入口走「精选 → 有声」
         * 那一档（`hot` 指向分类页 `Default.aspx?type=小说家类`，实测 10 本）。
         * 音频地址埋在 `href="javascript:PlayBookv(…,'https://….mp3',…)"` 的参数串里，
         * 用 `chapter.audio` 的 `@href@url` 那一档抠出来（不跑 JS，正则取第一个 http 地址）。
         *
         * 它也是**唯一有"文字跟着读"那一屏**的一条：`chapter.content=div.contson` 把同一页那整回正文
         * 一起抓下来（实测 67 段、非空白 7768 格），和音频落在同一集的目录里（`text.txt`）。
         * 播客那一条没有这一格 —— 站点不给正文，那一屏就还是封面。
         */

        /**
         * `podcast-rss` 是有声那一族的第一条：搜索走 Apple 的 iTunes 播客索引（公开、免登录），
         * `feedUrl` 就是书页，书页那份 RSS 的每个 `<item>` 是一集、`<enclosure url>` 就是那集的文件
         * （所以 `chapter.audio` 写的是 `@self`，一集一次集页请求都不发）。
         * 进来的判据和小说/漫画同一档：2026-10-14 在**PC 的 `HttpFetcher` 和手机各自的网络上打过同一支文件**，
         * 两边都是 200 / 3,787,944 字节 / `ftyp`+`M4A ` 开头，逐位相同。
         * 这一族能通的道理是协议性的 —— RSS 是给第三方播放器直取的，挡裸请求就等于挡自己的订阅者。
         *
         * CRI（国际在线）也在 iTunes 索引里，但**没进来**：它那 19 条 feed 的首集 17 条回 423、
         * 剩下 2 条给的是 `video/mp4` 的视频播客，不是有声书（同一天的实测，数字在主 README）。
         */

        /**
         * 后四条不是小说源：`tuku`/`guazi` 是漫画（一章 = 一排图），`arxiv`/`openalex` 是文献（一篇 = 一支 PDF）。
         * 进来的条件和小说那几条一样 —— 三格都从真站取回的页面判过（判据在 `:booksource` 的
         * `ComicPaperFixturesTest`），差别只在"第三格"对漫画是**图片地址能不能抽全**、
         * 对文献是**那个地址直下是不是 %PDF 开头**。DOAJ 没进来：它的 link 数组 102 条里只有 15 条看着像
         * 直接可下的 PDF，实下 12 支里 7 支真是 PDF（2026-10-13 实测），十个下载里要失败三个 —— 那不像源，像坏网。
         */

        /**
         * 从 `BUILTIN` 摘掉的，按文件名点名。摘掉只管新装，不管已经种过一次的手机（`seeded.` 故意不自愈），
         * 所以要在这里挂一笔账，等 `seed()` 去清。
         *
         * `ranwen` = 燃文小说网：正文是 `document.writeln(qsbs.bb(BASE64))`，规则抽出来只剩"本站推荐"那串书名，
         * 而下载进度照样走到 100%（它数的是章数，不看内容）。判据留在 `:booksource` 的
         * `RealSiteFixturesTest.ranwenBodyIsBase64InsideScriptsSoTheRuleCannotReachIt`。
         */
        val RETIRED = listOf("ranwen")

        /** Windows 那套保留字符一并挡掉：这台机上文件名会跟着书目录被 adb/压缩来回倒腾。 */
        val ILLEGAL = charArrayOf('/', '\\', ':', '*', '?', '"', '<', '>', '|')

        fun sha1(s: String): String =
            MessageDigest.getInstance("SHA-1").digest(s.toByteArray(Charsets.UTF_8))
                .joinToString("") { "%02x".format(it) }

        /** 随包源的对账用它：文件内容的哈希，不是地址的哈希。 */
        fun sha256(bytes: ByteArray): String =
            MessageDigest.getInstance("SHA-256").digest(bytes)
                .joinToString("") { "%02x".format(it) }
    }
}
