package com.local.comfyuimobile.network

import com.local.comfyuimobile.data.AppLogger
import com.local.comfyuimobile.model.AiStudioAccount
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/**
 * bdToken 自动刷新（v0.2.81）。
 *
 * 背景：`x-studio-token` 头里的 bdToken 由平台**登录时注入页面**，App 过去只在登录
 * 那一刻抓一次、此后永不更新。但该令牌有寿命——过期后所有校验它的接口（算力卡、
 * 可用档位、领算力、启动环境…）会集体返回 **HTTP 200 + 业务码 403**，而不校验它的
 * 接口（项目列表、积分）照常成功。用户看到的就是「只有部分功能读不到」，极易被误判成
 * 平台限制。
 *
 * 解法：令牌 403 时重新 GET 一次平台页面（`window.aiStudio.bdToken` 就明文注入在
 * HTML 里），抠出新值重试。实测签发新值**不会**让旧值立即失效，因此刷新不影响网页端
 * 或其它设备正在进行的会话。
 */
class AiStudioTokenRefresher {

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .build()

    /**
     * 单飞：算力卡 / 档位 / 项目状态是并发刷的，同时 403 会各自去抓一次页面，瞬间发出
     * 十几个请求。这里用同一把锁串行化，后来者若发现令牌已被别人换过（不再是当初失败的
     * 那个），就直接复用结果，不再打网络。
     */
    private val mutex = Mutex()

    /** 本刷新器最近一次取到的令牌及其时间戳，用于单飞复用。 */
    @Volatile private var observedToken: String = ""
    @Volatile private var observedTokenAt: Long = 0L
    @Volatile private var observedAccountId: String = ""

    /**
     * 刷新结果。
     *
     * 用密封类而不是 nullable / 字符串，是为了让调用方**必须**区分几种失败否则编译不过
     * ——这正是 v0.2.37 的教训：把「登录失效」和「页面结构变了」混成一句“没有权限”，
     * 用户只会反复重登白折腾。
     */
    sealed interface Result {
        /** 拿到可用的新令牌。 */
        data class Refreshed(val account: AiStudioAccount) : Result

        /** 页面明确显示未登录——Cookie 也失效了，需要用户重新登录。 */
        data object LoggedOut : Result

        /** 页面拿到了但抠不出令牌：平台改版了。 */
        data class ExtractionFailed(val message: String) : Result

        /** 网络/服务器故障，稍后重试即可。 */
        data class Unreachable(val message: String) : Result
    }

    /**
     * 若 [failedToken] 已不是当前观察到的令牌（说明刷新窗口内别人已换过），直接复用；
     * 否则抓一次新令牌。
     *
     * @param failedToken 触发刷新的那次请求所用的令牌；为空表示请求根本没带令牌。
     */
    suspend fun refresh(account: AiStudioAccount, failedToken: String): Result = mutex.withLock {
        val cached = observedToken
        // 单飞复用必须同时匹配账号：切账号后旧账号的缓存令牌对不上新账号，
        // 直接复用会把旧账号的令牌盖到新账号上。
        if (cached.isNotBlank() && cached != failedToken && observedAccountId == account.id) {
            return@withLock Result.Refreshed(
                account.copy(bdToken = cached, bdTokenFetchedAt = observedTokenAt),
            )
        }
        withContext(Dispatchers.IO) {
            val html = try {
                val request = Request.Builder()
                    .url(AiStudioProtocol.BASE_URL + TOKEN_SOURCE_PATH)
                    .header("Cookie", account.cookie)
                    .header("Accept", "text/html")
                    .get()
                    .build()
                client.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) {
                        return@withContext Result.Unreachable("HTTP ${response.code}")
                    }
                    response.body?.string().orEmpty()
                }
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                return@withContext Result.Unreachable(error.message.orEmpty())
            }
            parsePage(account, html)
        }
    }

    /** 解析页面并落定结果；抽出来便于单测（不依赖网络）。 */
    fun parsePage(account: AiStudioAccount, html: String): Result {
        if (html.isBlank()) return Result.Unreachable("页面为空")
        val token = AiStudioProtocol.extractBdToken(html)
        if (token.isBlank()) {
            // 页面拿到了但没令牌：先看是不是登录态没了（服务端把 userInfo 写成 "{}"）。
            return if (AiStudioProtocol.pageLooksLoggedOut(html)) {
                Result.LoggedOut
            } else {
                Result.ExtractionFailed("页面中未找到平台令牌，平台页面结构可能已变化")
            }
        }
        val now = System.currentTimeMillis()
        observedToken = token
        observedTokenAt = now
        observedAccountId = account.id
        AppLogger.info("AI Studio 令牌已刷新（${account.displayName()}）")
        return Result.Refreshed(account.copy(bdToken = token, bdTokenFetchedAt = now))
    }

    companion object {
        /** 任意登录页都会注入 bdToken，取最轻的概览页。 */
        private const val TOKEN_SOURCE_PATH = "/overview"

        /**
         * 令牌多久算陈旧（v0.2.82）。
         *
         * 实测边界：2026-10-02 21:29 算力卡还能读（57 小时），2026-10-03 02:22 起失效
         * ——TTL 约 4~5 天。取 72 小时留一天余量：既不会过于频繁地多打页面请求，
         * 又能保证用户在真失效前已经换成新的。
         */
        const val TOKEN_MAX_AGE_MILLIS = 72L * 60L * 60L * 1000L

        /**
         * 该账号的令牌是否陈旧到需要预防性刷新。
         *
         * ` fetchedAt == 0` 视为未知（老账号未记录时间）——**不算陈旧**，
         * 避免升级后一上来就对所有老账号狂刷一次页面。它们照样有 403→刷新 兜底。
         */
        fun isTokenStale(fetchedAt: Long, now: Long): Boolean =
            fetchedAt > 0L && now - fetchedAt >= TOKEN_MAX_AGE_MILLIS
    }
}
