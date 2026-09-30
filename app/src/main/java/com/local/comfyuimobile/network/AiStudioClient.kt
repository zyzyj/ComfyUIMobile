package com.local.comfyuimobile.network

import com.local.comfyuimobile.data.AppLogger
import com.local.comfyuimobile.model.AiStudioAccount
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
    suspend fun fetchPoints(account: AiStudioAccount): Int? = fetchPointsInfo(account).first

    /**
     * 拉资源与任务信息。
     *
     * @return computeCardMinutes 算力卡原始分钟数（null=未读到）；
     *         computeCard 展示值；weekQuota 本周各档剩余。
     */
    data class ResourceSnapshot(
        val computeCardMinutes: Double?,
        val computeCard: String?,
        val weekQuota: Map<String, Double>,
    )

    /** 拉算力卡与本周各档剩余（`/studio/resource/user/summary`）。 */
    suspend fun fetchResources(account: AiStudioAccount): ResourceSnapshot {
        val raw = runCatching {
            request(account, AiStudioProtocol.PATH_RESOURCE_SUMMARY, "POST_JSON", "{}", "读取算力")
        }.onFailure { error ->
            if (error is CancellationException) throw error
            AppLogger.warn("读取算力卡失败：${error.message.orEmpty()}")
        }.getOrNull()
        if (raw != null) logRaw("算力卡", raw.toString())
        return ResourceSnapshot(
            computeCardMinutes = raw?.let { AiStudioProtocol.parseComputeCardMinutes(it) },
            computeCard = raw?.let { AiStudioProtocol.parseComputeCard(it) },
            weekQuota = raw?.let { AiStudioProtocol.parseWeekQuotaMap(it) } ?: emptyMap(),
        )
    }

    /**
     * 领取每日资源（算力）。
     *
     * 重要：这个接口**不是**“每天点一下就能领 8 点算力”的入口。
     * 2026-09-30 用两个真实账号实测确认：
     *
     *  1. 真正的每日 8 点算力是**运行 Notebook 项目时平台自动发**的：
     *     启动前 resourceTotal=2938（49.0），启动后=3418（57.0），
     *     resourceIncome +480 分钟 = +8 点；同一天第二次启动不再发。
     *     —— 不发的时候，本接口无论项目是否在跑都回 errorCode 500 “无效操作”。
     *  2. 本接口的前置参数 `isInfoComplete=1` 是必需的：
     *     不带它（或传 0）会回“用户信息不完整”，而不是“无效操作”。
     *
     * 所以“无效操作”的真实含义是**今天已经没有可领的算力**（通常是还没运行项目，
     * 或今日已发过），不是接口坏了。这里把提示文案改成能指导下一步的说法。
     */
    suspend fun receiveResource(account: AiStudioAccount): String {
        return try {
            request(
                account,
                AiStudioProtocol.PATH_RESOURCE_RECEIVE + "?isInfoComplete=1",
                "POST_EMPTY",
                null,
                "领取算力",
            )
            "领取成功"
        } catch (error: AiStudioException) {
            val message = error.message.orEmpty()
            if (message.contains("无效操作") || message.contains("已领取")) {
                // 前置条件过了但没可领的额度：指导用户去做真正能触发发放的动作。
                "今日暂无可领算力：每日 8 点算力在「启动项目」时由平台自动发放，" +
                    "请先启动一个项目（免费 CPU 档也行），启动后会自动到账"
            } else if (message.contains("用户信息不完整")) {
                "账号资料未补全：请先在网页端补全个人资料，再回来领取"
            } else {
                throw error
            }
        }
    }

    suspend fun listProjects(account: AiStudioAccount, page: Int = 1, pageSize: Int = 30): AiStudioProtocol.ProjectPage {
        // 关键：/studio/project/self/list 前端用的是 `.type("json")`，
        // 分页参数名是 **p**（不是 pageNo）。发错会被平台 500。
        val body = JSONObject()
            .put("p", page)
            .put("pageSize", pageSize)
            .toString()
        val primary = request(account, AiStudioProtocol.PATH_PROJECT_LIST, "POST_JSON", body, "读取项目列表")
        logRaw("项目列表", primary.toString())
        val parsed = AiStudioProtocol.parseProjectPage(primary)
        if (parsed.projects.isNotEmpty()) return parsed
        // 主入口读不到时试备用端点（同样是 JSON）。
        val fallback = runCatching {
            request(account, AiStudioProtocol.PATH_PROJECT_LIST_ALT, "POST_JSON", body, "读取项目列表")
        }.onFailure { error ->
            if (error is CancellationException) throw error
            AppLogger.warn("备用项目列表接口也不可用: ${error.message.orEmpty()}")
        }.getOrNull() ?: return parsed
        logRaw("项目列表(备用)", fallback.toString())
        return AiStudioProtocol.parseProjectPage(fallback)
    }

    /** A币余额（`/studio/trade/coin/residue` → `coinNumShow`）。 */
    suspend fun fetchACoin(account: AiStudioAccount): String? {
        val raw = runCatching {
            request(account, AiStudioProtocol.PATH_COIN_RESIDUE, "GET", null, "读取A币")
        }.onFailure { error ->
            if (error is CancellationException) throw error
            AppLogger.warn("读取 A币失败：${error.message.orEmpty()}")
        }.getOrNull() ?: return null
        logRaw("A币", raw.toString())
        return AiStudioProtocol.parseACoin(raw)
    }

    /**
     * 读积分信息。除了余额，还带「今天是否已登录」。
     * 返回 Pair(积分, 今天已签到)；两者各自可能为 null。
     */
    suspend fun fetchPointsInfo(account: AiStudioAccount): Pair<Int?, Boolean?> {
        val raw = runCatching {
            request(account, AiStudioProtocol.PATH_POINT_INFO, "GET", null, "读取积分")
        }.onFailure { error ->
            if (error is CancellationException) throw error
            AppLogger.warn("读取积分失败：${error.message.orEmpty()}")
        }.getOrNull() ?: return null to null
        logRaw("积分", raw.toString())
        return AiStudioProtocol.parsePoints(raw) to AiStudioProtocol.parseSignInDone(raw)
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
     * 读项目正在使用的算力档位显示名（如 "V100 16GB"）。
     *
     * 项目列表接口**不返回** GPU 字段，只有 `/studio/project/detail` 的
     * `runningClusterInfo` 在项目真的运行时才带。所以单独拿一次详情；读不到
     * （未运行 / 接口异常）返回空串，不影响主流程。
     */
    suspend fun fetchRunningGpuLabel(account: AiStudioAccount, projectId: String): String {
        val result = runCatching {
            request(
                account,
                AiStudioProtocol.PATH_PROJECT_DETAIL,
                "POST",
                AiStudioProtocol.formEncode(mapOf("projectId" to projectId)),
                "读取项目详情",
            )
        }.onFailure { error ->
            if (error is CancellationException) throw error
            AppLogger.warn("读取项目运行档位失败：${error.message.orEmpty()}")
        }.getOrNull() ?: return ""
        return AiStudioProtocol.parseRunningGpuLabel(result)
    }

    /**
     * 启动项目环境。
     *
     * 启动前先问一次「是否需要人机校验」（[AiStudioProtocol.PATH_REQUIRE_GRAPHIC]，
     * POST 无 body）。注意：这个接口返回的是**是否需要校验的标记**，不是 tk/ds —— tk/ds
     * 是校验弹窗完成后才产生的一次性凭证。免费环境通常不需要校验，直接启动；
     * 真需要时平台会用错误码 8307 明确拒绝，届时提示用户去网页完成校验。
     */
    suspend fun startProject(
        account: AiStudioAccount,
        projectId: String,
        scheduleName: String,
    ): String {
        // 预检只用来判断“要不要先过人机校验”，结果本身不参与启动参数。网络抖动时
        // 它的 20 秒连接超时会把启动生生拖慢 20 秒（真机日志里就有一次 timeout 后
        // 才提交启动）。这里用 OkHttp 的 callTimeout 限时 3 秒，超时就按“免校验”
        // 继续——真需要校验时平台会在启动接口上用错误码 8307 明确拒绝。
        runCatching {
            request(
                account,
                AiStudioProtocol.PATH_REQUIRE_GRAPHIC,
                "POST_EMPTY",
                null,
                "检查启动校验",
                callTimeoutMillis = 3_000L,
            )
        }.onFailure { error ->
            if (error is CancellationException) throw error
            AppLogger.warn("启动校验预检失败（按免校验继续）: ${error.message.orEmpty()}")
        }
        val body = AiStudioProtocol.runProjectBody(projectId, scheduleName)
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

    // ===== 内部 =====

    private suspend fun request(
        account: AiStudioAccount,
        path: String,
        method: String,
        formBody: String?,
        action: String,
        callTimeoutMillis: Long? = null,
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
            // 个别调用（如启动前的人机校验预检）需要比默认更短的超时；
            // 只有传了值才单独建一个 client，避免影响其余接口。
            val call = if (callTimeoutMillis != null) {
                client.newBuilder().callTimeout(callTimeoutMillis, TimeUnit.MILLISECONDS).build()
                    .newCall(builder.build())
            } else {
                client.newCall(builder.build())
            }
            call.execute()
        } catch (error: Exception) {
            if (error is CancellationException) throw error
            throw AiStudioException("${action}失败：网络不可达（${error.message.orEmpty()}）")
        }
        response.use { resp ->
            val raw = resp.body?.string().orEmpty()
            lastRawResponse = raw.take(4000)
            when {
                // 注：不判 301/302——OkHttp 默认 followRedirects=true，重定向会被自动
                // 跟随，落到登录页 HTML 时由下面的 startsWith("<") 分支兜住。
                !resp.isSuccessful -> throw AiStudioException(
                    // v0.2.37：403 且本账号没拿到平台令牌（bdToken）时，平台只会回一个
                    // 笼统的“没有权限”，用户不知道该怎么修。这种账号是登录时页面还没注入
                    // window.aiStudio 就提交了（登录页已修），已存的旧账号重新登录即可。
                    if (resp.code == 403 && account.bdToken.isBlank()) {
                        "${action}失败：该账号缺少平台令牌（登录时未取到 bdToken），平台拒绝了这个操作。请到「账号」页重新登录一次（多账号请用「添加账号」重登后切换）。"
                    } else {
                        "${action}失败：HTTP ${resp.code}"
                    },
                )
                // 偶尔会返回登录页 HTML（百度网关的登录墙）
                raw.trimStart().startsWith("<") -> throw AiStudioException(
                    "${action}失败：登录已失效，请重新登录 AI Studio",
                )
            }
            AiStudioProtocol.unwrap(raw, action)
        }
    }
}
