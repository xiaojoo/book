package com.bookreader.booksource

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 登录那一枪的表单串。这一串差一个字符，屏幕上就只有"登录失败"四个字 ——
 * 而站点那句「登錄失敗」既可能是号错、也可能是我们把明文当哈希发了出去，人在手机上分不出来。
 * 所以形状本身要能在纯 JVM 上判红：字段名、编码、还有 `hash` 那一档到底有没有生效。
 */
class LoginFormTest {

    private val lg = LoginRule("https://x.test/m/user/login", "email", "password")

    @Test
    fun theTwoFieldNamesComeStraightFromTheRule() {
        assertEquals("email=a%40b.co&password=pw", loginForm(lg, "a@b.co", "pw"))
    }

    /** 站点自己的 JS 写的是 `md5(post['password'])`：这一档没生效，就等于永远登不进去。 */
    @Test
    fun md5HashSendsTheDigestNotWhatThePersonTyped() {
        val hashed = lg.copy(hash = LoginRule.MD5)
        // 9dd4e461268c8034f5c8564e155c67a6 就是 md5("x")，和浏览器那一份同一个数
        assertEquals("email=a%40b.co&password=9dd4e461268c8034f5c8564e155c67a6", loginForm(hashed, "a@b.co", "x"))
        assertTrue("原样那串不许出现在串里", !loginForm(hashed, "a@b.co", "x").contains("password=x&"))
    }

    /** 没写 `hash` 的源照旧发明文 —— 这一档不能默认打开，别的站的密码就是密码。 */
    @Test
    fun aRuleWithoutTheHashGridStillSendsThePasswordAsTyped() {
        assertEquals("u=1&pw=x", loginForm(LoginRule("https://x.test/l", "u", "pw"), "1", "x"))
    }

    @Test
    fun spacesAndAmpersandsSurviveTheEncoding() {
        assertEquals("email=a+b%26c&password=p%3Aw", loginForm(lg, "a b&c", "p:w"))
    }
}
