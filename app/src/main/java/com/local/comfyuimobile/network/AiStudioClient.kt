package com.local.comfyuimobile.network

import com.local.comfyuimobile.data.AppLogger
import com.local.comfyuimobile.model.AiStudioAccount
import com.local.comfyuimobile.model.AiStudioProject
import com.local.comfyuimobile.model.AiStudioSchedule
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * 百度 AI Studio 平台接口客户端。
 *
 * v0.1.90：报文构造与解析在 [AiStudioProtocol]（纯逻辑、可单测），这里只负责发请求。
 *
 * 认证要点（都来自前端真实请求）：
 *  - `Cookie: <整串>`（核心 BDUSS）
 *  - `x-studio-token: <bdToken>`（登录后页面注入）
 *  - `X-XSRFToken: <cookie 里的 _xsrf>`
 *  - `x-requested-with: XMLHttpRequest`（缺这个会被 302 甩到登录页）
 *
 * 安全要点：这个 client **绝不能**复用 ComfyClient 里那个——它挂着无差别附加
 * ComfyUI 反代登录 Cookie 的拦截器，拿它请求 aistudio 等于把 ComfyUI 服务器的
 * 登录态送给百度。这里也不共用连接池。
 */
class AiStudioClient {

    private val formMedia = "application/x-www-form-urlencoded; charset=utf-8".toMediaType()
    private val jsonMedia = "application/json; charset=utf-8".toMediaType()

    private val client = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .build()

    /** 记下最近一次接口的原始响应，供真机把结构发回来校准解析。 */
    @Volatile var lastRawResponse: String = ""
        private set

    /** 供诊断日志用的简短脱敏摘要。 */
    private fun logRaw(action: String, raw: String) {
        AppLogger.info("AI Studio[$action] 响应 ${raw.length} 字：${raw.take(300)}")
    }

    // ===== 对外接口 =====

    /** 拉用户资料，用于确认登录态并拿到 UID / 昵称。 */
    suspend fun fetchProfile(account: AiStudioAccount): JSONObject =
        request(account, AiStudioProtocol.PATH_PROFILE, "GET", null, "读取账号信息")

    /** 签到。 */
    suspend fun signIn(account: AiStudioAccount): JSONObject {
        // 积分签到：POST /point/sign，无 body（用 JSON 空对象，避免平台对
        // form 体报 500）。拿不到时退回 /studio/user/signin。
        return runCatching {
            request(account, AiStudioProtocol.PATH_POINT_SIGN, "POST_JSON", "{}", "签到")
        }.getOrElse { error ->
            if (error is CancellationException) throw error
            AppLogger.warn("积分签到失败，改试 /studio/user/signin：${error.message.orEmpty()}")
            request(account, AiStudioProtocol.PATH_SIGN_IN, "GET", null, "签到")
        }
    }

    /** 拉积分余额。 */
    suspend fun fetchPoints(account: AiStudioAccount): Int? {
        val raw = runCatching {
            request(account, AiStudioProtocol.PATH_POINT_INFO, "GET", null, "读取积分")
        }.onFailure { error ->
            if (error is CancellationException) throw error
            AppLogger.warn("读取积分失败：${error.message.orEmpty()}")
        }.getOrNull() ?: return null
        logRaw("积分", raw.toString())
        return AiStudioProtocol.parsePoints(raw)
    }

    /** 拉算力卡余额。 */
    suspend fun fetchComputeCard(account: AiStudioAccount): String? {
        // 该接口前端未指定 type，按 JSON 发；form 体会被拒。
        val raw = runCatching {
            request(account, AiStudioProtocol.PATH_RESOURCE_SUMMARY, "POST_JSON", "{}", "读取算力")
        }.onFailure { error ->
            if (error is CancellationException) throw error
            AppLogger.warn("读取算力卡失败：${error.message.orEmpty()}")
        }.getOrNull() ?: return null
        logRaw("算力卡", raw.toString())
        return AiStudioProtocol.parseComputeCard(raw)
    }

    /**
     * 领每日资源（算力）。
     *
     * 真实调用是 `POST /studio/user/center/resource/receive?isInfoComplete=1`
     * 且**不带 body**。之前当成普通表单 POST 发，平台回「用户信息不完整 500」——
     * `isInfoComplete=1` 正是绕过那份“完善信息”校验的开关。
     */
    suspend fun receiveResource(account: AiStudioAccount): String {
        request(
            account,
            AiStudioProtocol.PATH_RESOURCE_RECEIVE + "?isInfoComplete=1",
            "POST_EMPTY",
            null,
            "领取算力",
        )
        return "已提交领取请求"
    }

    suspend fun listProjects(account: AiStudioAccount, page: Int = 1, pageSize: Int = 30): List<AiStudioProject> {
        // 关键：/studio/project/self/list 前端用的是 `.type("json")`，
        // 发表单体会被平台 500。这里必须发 JSON。
        val body = JSONObject()
            .put("pageNo", page)
            .put("pageSize", pageSize)
            .toString()
        val primary = request(account, AiStudioProtocol.PATH_PROJECT_LIST, "POST_JSON", body, "读取项目列表")
        logRaw("项目列表", primary.toString())
        val projects = AiStudioProtocol.parseProjects(primary)
        if (projects.isNotEmpty()) return projects
        // 主入口读不到时试备用端点（同样是 JSON）。
        val fallback = runCatching {
            request(account, AiStudioProtocol.PATH_PROJECT_LIST_ALT, "POST_JSON", body, "读取项目列表")
        }.onFailure { error ->
            if (error is CancellationException) throw error
            AppLogger.warn("备用项目列表接口也不可用: ${error.message.orEmpty()}")
        }.getOrNull() ?: return emptyList()
        logRaw("项目列表(备用)", fallback.toString())
        return AiStudioProtocol.parseProjects(fallback)
    }

    /** 拿启动时可选的算力档位。 */
    suspend fun listSchedules(account: AiStudioAccount, projectId: String): List<AiStudioSchedule> {
        val result = request(
            account,
            AiStudioProtocol.PATH_CLUSTER_ALL_LIST,
            "POST",
            AiStudioProtocol.formEncode(
                mapOf(
                    "projectId" to projectId,
                    "startMode" to AiStudioProtocol.START_MODE_NOTEBOOK.toString(),
                ),
            ),
            "读取可用算力",
        )
        return AiStudioProtocol.parseSchedules(result)
    }

    /**
     * 启动项目环境。
     *
     * 先取通行码（[AiStudioProtocol.PATH_REQUIRE_GRAPHIC]）；免费环境通常返回空，
     * 拿不到也不阻断——真需要校验时平台会用错误码 8307 明确拒绝，界面再提示用户。
     */
    suspend fun startProject(
        account: AiStudioAccount,
        projectId: String,
        scheduleName: String,
    ): String {
        var tk = ""
        var ds = ""
        runCatching {
            val graphic = request(
                account,
                AiStudioProtocol.PATH_REQUIRE_GRAPHIC,
                "POST",
                AiStudioProtocol.formEncode(mapOf("projectId" to projectId)),
                "获取启动校验",
            )
            tk = graphic.optString("tk")
            ds = graphic.optString("ds")
        }.onFailure { error ->
            if (error is CancellationException) throw error
            AppLogger.warn("获取启动通行码失败（按免校验继续）: ${error.message.orEmpty()}")
        }
        val body = AiStudioProtocol.runProjectBody(projectId, scheduleName, tk, ds)
        request(
            account,
            AiStudioProtocol.PATH_PROJECT_RUNNING,
            "POST",
            AiStudioProtocol.formEncode(body),
            "启动环境",
        )
        return "已提交启动请求"
    }

    suspend fun stopProject(account: AiStudioAccount, projectId: String): String {
        request(
            account,
            AiStudioProtocol.PATH_PROJECT_STOP,
            "POST",
            AiStudioProtocol.formEncode(
                mapOf(
                    "projectId" to projectId,
                    "startMode" to AiStudioProtocol.START_MODE_NOTEBOOK.toString(),
                ),
            ),
            "停止环境",
        )
        return "已提交停止请求"
    }

    suspend fun projectStatus(account: AiStudioAccount, projectId: String): JSONObject =
        request(
            account,
            AiStudioProtocol.PATH_PROJECT_STATUS,
            "POST",
            AiStudioProtocol.formEncode(mapOf("projectId" to projectId)),
            "查询项目状态",
        )

    // ===== 内部 =====

    private suspend fun request(
        account: AiStudioAccount,
        path: String,
        method: String,
        formBody: String?,
        action: String,
    ): JSONObject = withContext(Dispatchers.IO) {
        val url = AiStudioProtocol.BASE_URL + path
        val builder = Request.Builder()
            .url(url)
            .header("Cookie", account.cookie)
            .header("x-requested-with", "XMLHttpRequest")
            .header("Referer", AiStudioProtocol.BASE_URL + "/")
            .header("Accept", "application/json, text/plain, */*")
        if (account.bdToken.isNotBlank()) builder.header("x-studio-token", account.bdToken)
        val xsrf = AiStudioProtocol.cookieValue(account.cookie, "_xsrf")
        if (xsrf.isNotBlank()) builder.header("X-XSRFToken", xsrf)

        // 方法语义：
        //   GET         — 无体
        //   POST        — application/x-www-form-urlencoded
        //   POST_JSON   — application/json（平台部分接口如 /studio/project/self/list 必须）
        //   POST_EMPTY  — 完全没有请求体（领资源接口用，带体反而报 500）
        when (method) {
            "GET" -> builder.get()
            "POST" -> builder.post((formBody ?: "").toRequestBody(formMedia))
            "POST_JSON" -> builder.post((formBody ?: "{}").toRequestBody(jsonMedia))
            "POST_EMPTY" -> builder.post(ByteArray(0).toRequestBody(null))
        }

        val response: Response = try {
            client.newCall(builder.build()).execute()
        } catch (error: Exception) {
            if (error is CancellationException) throw error
            throw AiStudioException("$action 失败：网络不可达（${error.message.orEmpty()}）")
        }
        response.use { resp ->
            val raw = resp.body?.string().orEmpty()
            lastRawResponse = raw.take(4000)
            when {
                resp.code == 302 || resp.code == 301 -> throw AiStudioException(
                    "$action 失败：登录已失效，请重新登录 AI Studio",
                )
                !resp.isSuccessful -> throw AiStudioException(
                    "$action 失败：HTTP ${resp.code}",
                )
                // 偶尔会返回登录页 HTML（百度网关的登录墙）
                raw.trimStart().startsWith("<") -> throw AiStudioException(
                    "$action 失败：登录已失效，请重新登录 AI Studio",
                )
            }
            AiStudioProtocol.unwrap(raw, action)
        }
    }
}
