package com.local.comfyuimobile.network

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 用百度 AI Studio 真实返回的页面做测试数据。
 * 这些 HTML 片段来自用户日志里 /userdata 的 404 响应。
 */
class PlatformResponseGuardTest {

    private val aistudioErrorPage = """
        <!doctype html>
        <html lang="zh-cmn-Hans-CN">
        <head>
            <meta charset="UTF-8">
            <title>飞桨 AI Studio</title>
            <link rel="stylesheet" href="//aistudio-fe-online.cdn.bcebos.com/aistudio/dist/css/error.css">
        </head>
        <body>
        <div id="main" class="main"></div>
        </body>
        </html>
    """.trimIndent()

    private val loginPage = """
        <!DOCTYPE html>
        <html>
        <head><title>百度飞桨</title></head>
        <body>
        <form action="https://passport.baidu.com/v2/api/" method="post">
          <input type="password" name="password" />
        </form>
        </body>
        </html>
    """.trimIndent()

    @Test
    fun classifiesHtmlJsonEmptyAndRedirect() {
        assertEquals(PlatformResponseGuard.BodyKind.HTML, PlatformResponseGuard.classify(aistudioErrorPage))
        assertEquals(PlatformResponseGuard.BodyKind.HTML, PlatformResponseGuard.classify("  <html><body>x</body></html>"))
        assertEquals(PlatformResponseGuard.BodyKind.JSON, PlatformResponseGuard.classify("{\"a\":1}"))
        assertEquals(PlatformResponseGuard.BodyKind.JSON, PlatformResponseGuard.classify("[1,2,3]"))
        assertEquals(PlatformResponseGuard.BodyKind.EMPTY, PlatformResponseGuard.classify("   "))
        assertEquals(PlatformResponseGuard.BodyKind.TEXT, PlatformResponseGuard.classify("not json at all"))
        assertEquals(
            PlatformResponseGuard.BodyKind.REDIRECT,
            PlatformResponseGuard.classify("<script>location.replace('/login')</script>"),
        )
    }

    @Test
    fun detectsLoginPages() {
        assertTrue(PlatformResponseGuard.looksLikeLoginPage(loginPage))
        assertFalse("普通错误页不应被当成登录页", PlatformResponseGuard.looksLikeLoginPage(aistudioErrorPage))
    }

    @Test
    fun describesUnsupportedPageInOneLine() {
        val message = PlatformResponseGuard.describe(404, aistudioErrorPage)
        assertTrue(message.contains("不支持"))
        assertTrue(message.contains("404"))
        assertFalse("不应把 HTML 灌进错误提示", message.contains("<!doctype"))
        assertTrue("提示必须是一行", message.lines().size == 1)
        assertTrue("提示要短小", message.length <= 200)
    }

    @Test
    fun describesLoginPageInsteadOfRawJson() {
        val message = PlatformResponseGuard.describe(200, loginPage)
        assertTrue("应提示需要登录", message.contains("登录"))
        assertFalse(message.contains("<form"))
    }

    @Test
    fun keepsJsonErrorsReadable() {
        val body = """{"error":{"message":"Prompt has no outputs"}}"""
        val message = PlatformResponseGuard.describe(400, body)
        assertTrue(message.contains("Prompt has no outputs"))
    }

    @Test
    fun guardRejectsHtmlEvenWhenHttpIs200() {
        // 这是 AI Studio 最坑的情况：200 + 登录页，以前会被当成合法 JSON
        val error = runCatching { PlatformResponseGuard.guard(200, loginPage) }.exceptionOrNull()
        assertTrue(error is PlatformResponseException)
        assertTrue((error as PlatformResponseException).unsupported)
    }

    @Test
    fun guardAcceptsNormalJson() {
        PlatformResponseGuard.guard(200, """{"system":{}}""")
        // 没抛异常即通过
    }

    @Test
    fun guardRejectsNonOkStatus() {
        val error = runCatching { PlatformResponseGuard.guard(500, "boom") }.exceptionOrNull()
        assertTrue(error is PlatformResponseException)
        assertEquals(500, (error as PlatformResponseException).httpCode)
    }

    @Test
    fun marksHtmlResponsesSoCallerCanRetry() {
        // html 标记用于区分"冷启动抖动"（重试能好）和"服务器明确报错"（不该重试）。
        // AI Studio 实例刚起来时会吐一页 HTML，重试第二次就正常了。
        val error = runCatching { PlatformResponseGuard.guard(200, loginPage) }.exceptionOrNull()
        assertTrue((error as PlatformResponseException).html)
    }

    @Test
    fun doesNotMarkPlainJsonErrorsAsHtml() {
        val body = """{"error":{"message":"Prompt has no outputs"}}"""
        val error = runCatching { PlatformResponseGuard.guard(400, body) }.exceptionOrNull()
        assertFalse((error as PlatformResponseException).html)
    }

    @Test
    fun emptyBodyIsNotHtml() {
        val error = runCatching { PlatformResponseGuard.guard(502, "") }.exceptionOrNull()
        assertFalse((error as PlatformResponseException).html)
    }

    @Test
    fun loginPageIsNotRetriable() {
        // v0.1.69：Cookie 不会因为多试两次就自己出现。日志里 16:04:38 起连着 8 次
        // "需要登录或登录已失效"，每次都白等三轮退避——登录页必须第一次就报出来。
        val error = runCatching { PlatformResponseGuard.guard(200, loginPage) }.exceptionOrNull()
            as PlatformResponseException
        assertTrue(error.loginPage)
        assertFalse(error.retriable)
    }

    @Test
    fun coldStartHtmlIsStillRetriable() {
        // 冷启动抖动页（非登录页）：重试能好，不能把重试关掉。
        val body = "<html><body>service temporarily unavailable</body></html>"
        val error = runCatching { PlatformResponseGuard.guard(200, body) }.exceptionOrNull()
            as PlatformResponseException
        assertFalse(error.loginPage)
        assertTrue(error.retriable)
    }

    @Test
    fun server5xxIsRetriableButPlain4xxIsNot() {
        val e500 = runCatching { PlatformResponseGuard.guard(500, "boom") }.exceptionOrNull()
            as PlatformResponseException
        assertTrue(e500.retriable)
        val e400 = runCatching { PlatformResponseGuard.guard(400, """{"error":{"message":"bad"}}""") }
            .exceptionOrNull() as PlatformResponseException
        assertFalse(e400.retriable)
    }

    @Test
    fun unsupportedHtmlStillRetriesBecauseColdStartLooksIdentical() {
        // unsupported 的用途是能力门控（ServerCapabilities），不是重试开关：
        // 所有 HTML 都会标 unsupported，而 AI Studio 冷启动吐的也是 HTML，
        // 重试层面区分不了"不支持"和"抖动"，所以非登录页 HTML 照旧可重试。
        val error = runCatching { PlatformResponseGuard.guard(404, aistudioErrorPage) }.exceptionOrNull()
            as PlatformResponseException
        assertTrue(error.unsupported)
        assertTrue(error.retriable)
    }

    @Test
    fun plainJson4xxIsUnsupportedFalseAndNotRetriable() {
        val e400 = runCatching { PlatformResponseGuard.guard(400, """{"error":{"message":"bad"}}""") }
            .exceptionOrNull() as PlatformResponseException
        assertFalse(e400.unsupported)
        assertFalse(e400.retriable)
    }
}
