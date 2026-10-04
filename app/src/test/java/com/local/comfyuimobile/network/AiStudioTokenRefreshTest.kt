package com.local.comfyuimobile.network

import com.local.comfyuimobile.model.AiStudioAccount
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v0.2.81：bdToken 提取与归因的纯逻辑测试。
 *
 * 这几条钉住的是"平台页面结构变化时能立刻发现"以及"三种失败必须分开归因"——
 * 后者是 v0.2.37 的教训：把登录失效和结构变化混成一句"没有权限"，用户只会反复重登。
 */
class AiStudioTokenRefreshTest {

    // ===== 从页面 HTML 提取 bdToken =====

    @Test
    fun extractsBdTokenFromInjectedScript() {
        val html = """
            <script>
                window.aiStudio = {
                    siteId: 0,
                    userInfo: {"id":20222217,"userName":"\u7728\u773C\u775Beye"},
                    logoutUrl: null,
                    bdToken: "EF5E370E98F66077E24D8F63B5425AE712A490CB1218432399246B4999156AB2E3C0E0679A57601C80E7E89742DD5D75"
                };
            </script>
        """.trimIndent()
        assertEquals(
            "EF5E370E98F66077E24D8F63B5425AE712A490CB1218432399246B4999156AB2E3C0E0679A57601C80E7E89742DD5D75",
            AiStudioProtocol.extractBdToken(html),
        )
    }

    @Test
    fun extractsTokenWithFlexibleSpacing() {
        val token = "A".repeat(96)
        assertTrue(AiStudioProtocol.extractBdToken("bdToken:\"$token\"") == token)
        assertTrue(AiStudioProtocol.extractBdToken("bdToken : \"$token\"") == token)
    }

    @Test
    fun returnsEmptyWhenPageHasNoToken() {
        assertEquals("", AiStudioProtocol.extractBdToken("<html><body>login</body></html>"))
    }

    @Test
    fun ignoresTooShortLookalike() {
        // 页面里若出现 bdToken:"abc"（非 60 位以上十六进制）不应误取。
        assertEquals("", AiStudioProtocol.extractBdToken("bdToken: \"abc\""))
    }

    @Test
    fun pageWithoutTokenIsNotReportedAsHavingOne() {
        assertFalse(AiStudioProtocol.pageHasBdToken("<html></html>"))
        assertTrue(AiStudioProtocol.pageHasBdToken("bdToken: \"" + "F".repeat(64) + "\""))
    }

    // ===== 未登录页面识别 =====

    @Test
    fun detectsLoggedOutPage() {
        val loggedOut = """
            <script>
                window.aiStudio = {
                    siteId: 0,
                    pageTitle: "\u98DE\u6868AI Studio",
                    userInfo: "{}",
                    logoutUrl: null
                };
            </script>
        """.trimIndent()
        assertTrue(AiStudioProtocol.pageLooksLoggedOut(loggedOut))
    }

    @Test
    fun loggedInPageIsNotReportedAsLoggedOut() {
        val loggedIn = """
            window.aiStudio = {
                siteId: 0,
                userInfo: {"id":20222217,"userName":"eye"},
                bdToken: "${"E".repeat(96)}"
            };
        """.trimIndent()
        assertFalse(AiStudioProtocol.pageLooksLoggedOut(loggedIn))
    }

    // ===== 刷新器归因（不依赖网络） =====

    private val account = AiStudioAccount(
        id = "uid:1",
        nickname = "eye",
        uid = "1",
        cookie = "BDUSS=abc",
        bdToken = "OLD".repeat(32),
    )

    @Test
    fun refreshedResultCarriesNewTokenAndTimestamp() {
        val refresher = AiStudioTokenRefresher()
        val html = "bdToken: \"" + "A".repeat(96) + "\""
        val result = refresher.parsePage(account, html)
        assertTrue(result is AiStudioTokenRefresher.Result.Refreshed)
        val refreshed = result as AiStudioTokenRefresher.Result.Refreshed
        assertEquals("A".repeat(96), refreshed.account.bdToken)
        assertTrue(refreshed.account.bdTokenFetchedAt > 0L)
    }

    @Test
    fun loggedOutPageIsAttributedToLoggedOutNotExtractionFailure() {
        val refresher = AiStudioTokenRefresher()
        val result = refresher.parsePage(account, "window.aiStudio = { userInfo: \"{}\" };")
        assertTrue(result is AiStudioTokenRefresher.Result.LoggedOut)
    }

    @Test
    fun pageWithoutTokenNorLoggedOutMarkIsExtractionFailure() {
        val refresher = AiStudioTokenRefresher()
        val result = refresher.parsePage(account, "window.aiStudio = { userInfo: { id: 1 } };")
        assertTrue(result is AiStudioTokenRefresher.Result.ExtractionFailed)
    }

    @Test
    fun blankPageIsUnreachable() {
        val refresher = AiStudioTokenRefresher()
        assertTrue(refresher.parsePage(account, "") is AiStudioTokenRefresher.Result.Unreachable)
    }

    // ===== 预防性刷新的陈旧判定（v0.2.82）=====

    @Test
    fun tokenWithinMaxAgeIsNotStale() {
        val now = 1_000_000_000L
        val justFetched = now - 60_000L
        assertFalse(AiStudioTokenRefresher.isTokenStale(justFetched, now))
    }

    @Test
    fun tokenOlderThanSeventyTwoHoursIsStale() {
        val now = 1_000_000_000L
        val threeDaysAgo = now - AiStudioTokenRefresher.TOKEN_MAX_AGE_MILLIS - 1
        assertTrue(AiStudioTokenRefresher.isTokenStale(threeDaysAgo, now))
    }

    @Test
    fun unknownFetchTimeIsNotTreatedAsStale() {
        // 老账号升级上来 bdTokenFetchedAt=0，不应触发"一上来就狂刷"。
        assertFalse(AiStudioTokenRefresher.isTokenStale(0L, System.currentTimeMillis()))
    }
}
