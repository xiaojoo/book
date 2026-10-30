package com.bookreader.data

import android.content.Context
import androidx.core.content.edit
import com.bookreader.booksource.Fetcher
import com.bookreader.booksource.HttpFetcher
import com.bookreader.booksource.PageRequest
import com.bookreader.booksource.Rule
import com.bookreader.booksource.loginForm

/**
 * 「要先登录才有内容」那一族源的账号那一格（199mh：取址口在未登录时回 `{"code":4001002,"message":"請先登錄"}`，
 * 而 HTTP 状态还是 200）。
 *
 * 登录由应用来打，会话留在交给抓取的那一个 fetcher 的 cookie 罐里 —— 不放进 :booksource 那边：
 * 规则文件要能原样导给别人看，里面不该躺着一个人的密码；而 AndroidKeyStore 那个模块也碰不到。
 * 所以规则里只写「登录口在哪、那两个字段叫什么」（`login` 那一段），值存在这台机上：
 * 邮箱明文存（它是账号标识，不是秘钥），密码走 [SecretStore] 加密。
 */
object SourceAuth {

    /** 这一条源要不要账号。界面上那颗「账号」就按这一格摆出来。 */
    fun needs(rule: Rule): Boolean = rule.login != null

    /** 存着的账号（邮箱, 密码）。没存过就是两个空串。 */
    fun account(ctx: Context, rule: Rule): Pair<String, String> {
        val p = prefs(ctx)
        val blob = p.getString("srcPassCipher.${rule.id}", null)
        return p.getString("srcUser.${rule.id}", "").orEmpty() to
            (blob?.let { runCatching { SecretStore.decrypt(it) }.getOrNull() }.orEmpty())
    }

    /**
     * 存下账号。返回 null = 存好了；否则是给人看的那句话。
     *
     * Keystore 用不了的时候**不能退回去存明文**：那一格界面会显示"已保存"，而盘上躺的是人的密码。
     */
    fun save(ctx: Context, rule: Rule, email: String, password: String): String? {
        if (rule.login == null) return null
        val pass = password.trim()
        val blob = if (pass.isEmpty()) null else runCatching { SecretStore.encrypt(pass) }.getOrNull()
        if (pass.isNotEmpty() && blob == null) return "这台机的 Keystore 用不了，密码存不下去（邮箱已经存了）"
        prefs(ctx).edit {
            putString("srcUser.${rule.id}", email.trim())
            if (blob == null) remove("srcPassCipher.${rule.id}") else putString("srcPassCipher.${rule.id}", blob)
        }
        return null
    }

    /**
     * 建一个 fetcher，要登录的先把那一枪打掉。
     *
     * 第二格是**给人看的那句话**：要账号而没登录上时，下载那一头该拿这句话当场停下，
     * 而不是把一本书的几百条取址全打过去、每一枪都回一句「請先登錄」。
     */
    fun fetcher(ctx: Context, rule: Rule): Pair<Fetcher, String?> {
        // 一本图片包一卷就是几十 MB，读的超时按整本下载那一档放宽（抓图那一条 12 秒是够的，这里不够）
        val f = HttpFetcher(readTimeoutMs = 120_000)
        val lg = rule.login ?: return f to null
        val (email, pass) = account(ctx, rule)
        if (email.isEmpty() || pass.isEmpty()) {
            return f to "「${rule.name}」要先登录：在「管理书籍源」那一行的「账号」里填邮箱和密码"
        }
        // 串按规则里那一档过一道再发（199mh 要 md5，见 [LoginRule.hash]）—— 明文发过去永远登不进
        val body = loginForm(lg, email, pass)
        val reply = runCatching { f.fetch(PageRequest(lg.url, method = "POST", body = body)) }
            .fold({ it }, {
                // 站点那句人话（"登錄失敗"）比 `IOException` 这个类名有用，有消息就别报类名
                val said = it.message.orEmpty().trim()
                return f to "「${rule.name}」登录没打成：" + if (said.isEmpty()) it.javaClass.simpleName else said
            })
        if (!f.hasCookie(lg.url)) {
            return f to "「${rule.name}」没拿到会话，站点回的是：" + reply.text(rule.charset).replace('\n', ' ').trim().take(120)
        }
        return f to null
    }

    private fun prefs(ctx: Context) = ctx.getSharedPreferences("reader", Context.MODE_PRIVATE)
}
