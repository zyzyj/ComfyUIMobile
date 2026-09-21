package com.local.comfyuimobile.network

import com.local.comfyuimobile.model.AiStudioAccount
import com.local.comfyuimobile.model.AiStudioProject
import com.local.comfyuimobile.model.AiStudioSchedule
import org.json.JSONArray
import org.json.JSONObject

/**
 * 百度 AI Studio 平台接口的协议层。
 *
 * v0.1.90：路径与参数取自平台网页前端的真实请求（前端 bundle 里
 * `request.post("/studio/xxx").type("form").send({...})` 的形态），不是官方公开 API。
 *
 * 这里刻意**不引用 OkHttp，也不引用任何 Android API**——全部是纯 Kotlin + org.json，
 * 因此能直接在本地 JVM 上跑单元测试。发请求本身在 [AiStudioClient] 里。
 *
 * 平台响应统一是 `{errorCode, errorMsg, result}` 三层壳；但也有个别接口直接返回
 * result。解析一律先剥壳再判断，且**任何字段缺失都只降级不抛异常**——平台改版时
 * 宁可少显示一个字段，也不该让整个面板崩掉。
 */
object AiStudioProtocol {

    const val BASE_URL = "https://aistudio.baidu.com"

    // ===== 账号 / 签到 =====
    const val PATH_PROFILE = "/studio/user/index/profile"
    const val PATH_SIGN_IN = "/studio/user/signin"

    // ===== 积分 / 算力 =====
    /** 社区积分：另一套接口（不带 /studio 前缀），签到就是签这个。 */
    const val PATH_POINT_SIGN = "/point/sign"
    const val PATH_POINT_INFO = "/point/user/info"
    /** 算力卡与资源配额。 */
    const val PATH_RESOURCE_SUMMARY = "/studio/resource/user/summary"
    const val PATH_RESOURCE_QUOTA = "/studio/resource/quota"
    /** A币余额。 */
    const val PATH_COIN_RESIDUE = "/studio/trade/coin/residue"
    /** SDK Token 余额。 */
    const val PATH_TOKEN_BALANCE = "/studio/trade/token/my/balance"
    /** 领取每日资源（算力）。 */
    const val PATH_RESOURCE_RECEIVE = "/studio/user/center/resource/receive"

    // ===== 项目 =====
    const val PATH_PROJECT_LIST = "/studio/project/self/list"
    /** 备用项目列表入口。平台在不同页面用了两套，主入口读不到时再试这个。 */
    const val PATH_PROJECT_LIST_ALT = "/studio/user/project/cherry/list"
    const val PATH_PROJECT_DETAIL = "/studio/project/detail"
    const val PATH_PROJECT_STATUS = "/studio/project/status"
    const val PATH_PROJECT_ADD = "/studio/project/add"

    // ===== 启动 / 停止环境 =====
    const val PATH_NOTEBOOK_ENTER = "/studio/project/notebook/enter"
    const val PATH_NOTEBOOK_CONFIG = "/studio/notebook/config"
    const val PATH_CLUSTER_ALL_LIST = "/studio/project/cluster/allList"
    const val PATH_PROJECT_RUNNING = "/studio/project/running"
    const val PATH_PROJECT_STOP = "/studio/project/stop"
    const val PATH_REQUIRE_GRAPHIC = "/studio/user/start/notebook/require/graphic"

    /** startMode：0 = Notebook。其余形态（脚本任务等）本项目暂不支持。 */
    const val START_MODE_NOTEBOOK = 0

    /** 默认调度名。免费环境用这个；GPU 档位由 scheduleName 参数指定。 */
    const val DEFAULT_SCHEDULE = "normalSchedule"

    /**
     * 平台统一响应壳：`{errorCode, errorMsg, result}`。
     * 成功（errorCode == 0）时返回 result；否则抛带人话的异常。
     */
    fun unwrap(raw: String, action: String): JSONObject {
        val root = runCatching { JSONObject(raw) }.getOrElse {
            throw AiStudioException("$action 失败：服务器返回的不是 JSON（${raw.take(120)}）")
        }
        // 没有 errorCode 字段的，视为 result 直接就是正文。
        if (!root.has("errorCode")) return root
        val code = root.optInt("errorCode", 0)
        if (code != 0) {
            val msg = root.optString("errorMsg").ifBlank { describeErrorCode(code) }
            throw AiStudioException("$action 失败：$msg（错误码 $code）")
        }
        return root.optJSONObject("result") ?: root
    }

    /** 把平台常见错误码翻译成人能看懂的话。未知码原样带出去，便于真机反馈。 */
    fun describeErrorCode(code: Int): String = when (code) {
        0 -> "成功"
        403 -> "没有权限（登录态可能已失效）"
        8307 -> "需要先完成图形验证码校验"
        10001 -> "平台内部错误"
        10003 -> "参数不完整"
        10004 -> "参数无效"
        10007 -> "账号被冻结"
        10008 -> "权限不足"
        else -> "平台返回错误"
    }

    /** 登录态判断：资料接口拿不到用户 id 即视为未登录。 */
    fun parseUid(profile: JSONObject): String {
        val direct = profile.optString("id")
        if (direct.isNotBlank()) return direct
        return profile.optJSONObject("user")?.optString("id").orEmpty()
    }

    fun parseNickname(profile: JSONObject): String {
        val candidates = listOf("nickname", "userName", "name", "username")
        candidates.forEach { key ->
            val value = profile.optString(key)
            if (value.isNotBlank()) return value
        }
        val nested = profile.optJSONObject("user") ?: return ""
        candidates.forEach { key ->
            val value = nested.optString(key)
            if (value.isNotBlank()) return value
        }
        return ""
    }

    /**
     * 解析「我的项目」列表。
     *
     * 平台分页返回的壳有几种可能：`result.list`、`result.records`、或 result 直接是数组。
     * 逐个兜底，任一命中即用；全都不是就返回空列表（不抛异常，界面显示"暂无项目"）。
     */
    fun parseProjects(result: JSONObject): List<AiStudioProject> {
        val array = firstArray(
            result,
            listOf("list", "records", "items", "data", "projectList", "rows"),
        ) ?: return emptyList()
        return buildList {
            repeat(array.length()) { index ->
                val item = array.optJSONObject(index) ?: return@repeat
                val id = firstString(item, listOf("projectId", "id", "project_id"))
                if (id.isBlank()) return@repeat
                add(
                    AiStudioProject(
                        projectId = id,
                        name = firstString(item, listOf("projectName", "name", "title")),
                        description = firstString(item, listOf("projectAbs", "projectDesc", "description", "abs")),
                        statusRaw = item.optInt("status", 0),
                        running = parseRunning(item),
                        updatedAt = firstLong(item, listOf("updateTime", "updatedAt", "mtime", "update_time")),
                        isNotebook = parseIsNotebook(item),
                    ),
                )
            }
        }
    }

    /**
     * 判断项目是否在运行。
     *
     * 平台的字段名与取值都不稳定：可能是布尔 `running`/`isRunning`，也可能是
     * 数字 `status`（1 常表示运行中），还有 `runStatus` 字符串。任一命中即可，
     * 全都没有就按"未运行"处理——大不了让用户点一下启动，比误判成运行中而
     * 什么都不做要好。
     */
    fun parseRunning(item: JSONObject): Boolean {
        listOf("running", "isRunning", "is_running").forEach { key ->
            if (item.has(key)) {
                val value = item.opt(key)
                if (value is Boolean) return value
                if (value is Number) return value.toInt() == 1
                if (value is String) return value == "1" || value.equals("true", true)
            }
        }
        val status = item.optString("runStatus")
        if (status.isNotBlank()) {
            val lowered = status.lowercase()
            if (lowered.contains("run") || lowered.contains("running")) return true
            if (lowered.contains("stop") || lowered.contains("idle")) return false
        }
        // status 是数字时：1 = 运行中（平台惯用）。0/其他按未运行。
        val numeric = item.opt("status")
        if (numeric is Number) return numeric.toInt() == 1
        return false
    }

    private fun parseIsNotebook(item: JSONObject): Boolean {
        val type = firstString(item, listOf("projectType", "type", "resourceType"))
        if (type.isBlank()) return true
        val lowered = type.lowercase()
        // 明确是脚本任务/图形化任务时不算 Notebook；其余（含空、notebook）都当 Notebook。
        if (lowered.contains("script") || lowered.contains("graphical")) return false
        return true
    }

    /**
     * 解析启动时可选的算力档位。
     *
     * 平台返回的每项字段名不定（scheduleName / name / gpuType / displayName），
     * 逐个兜底。scheduleName 是启动时真正要传的值，取不到就跳过这一项。
     */
    fun parseSchedules(result: JSONObject): List<AiStudioSchedule> {
        val array = firstArray(
            result,
            listOf("list", "records", "items", "scheduleList", "clusterList", "data"),
        ) ?: return emptyList()
        return buildList {
            repeat(array.length()) { index ->
                val item = array.optJSONObject(index) ?: return@repeat
                val scheduleName = firstString(item, listOf("scheduleName", "name", "schedule"))
                if (scheduleName.isBlank()) return@repeat
                add(
                    AiStudioSchedule(
                        scheduleName = scheduleName,
                        label = firstString(item, listOf("label", "displayName", "showName", "desc")),
                        gpuType = firstString(item, listOf("gpuType", "gpu", "resourceType", "cardType")),
                        available = parseScheduleAvailable(item),
                    ),
                )
            }
        }
    }

    private fun parseScheduleAvailable(item: JSONObject): Boolean {
        listOf("available", "enable", "enabled", "canUse").forEach { key ->
            if (item.has(key)) {
                val value = item.opt(key)
                if (value is Boolean) return value
                if (value is Number) return value.toInt() == 1
            }
        }
        // 没有可用性字段时不要禁用——宁可让用户点了收到服务器报错。
        return true
    }

    /** 启动环境的表单体。tk/ds 来自通行码流程，免费环境可为空。 */
    fun runProjectBody(
        projectId: String,
        scheduleName: String,
        tk: String = "",
        ds: String = "",
    ): Map<String, String> = buildMap {
        put("projectId", projectId)
        put("versionId", "0")
        put("scheduleName", scheduleName.ifBlank { DEFAULT_SCHEDULE })
        put("startMode", START_MODE_NOTEBOOK.toString())
        put("tk", tk)
        put("ds", ds)
    }

    /** 构造表单编码体（平台绝大多数 POST 走 `application/x-www-form-urlencoded`）。 */
    fun formEncode(fields: Map<String, String>): String = fields.entries.joinToString("&") {
        "${urlEncode(it.key)}=${urlEncode(it.value)}"
    }

    private fun urlEncode(value: String): String =
        java.net.URLEncoder.encode(value, "UTF-8")

    /** 从 Cookie 串里取某个键的值，取不到返回空串。 */
    fun cookieValue(cookie: String, name: String): String {
        cookie.split(';').forEach { segment ->
            val trimmed = segment.trim()
            val eq = trimmed.indexOf('=')
            if (eq <= 0) return@forEach
            if (trimmed.substring(0, eq).trim() == name) {
                return trimmed.substring(eq + 1).trim()
            }
        }
        return ""
    }

    /**
     * 平台登录态的判定：核心是 BDUSS。没有它就一定没登录。
     * 其余 Cookie（BAIDUID 等）匿名访问也会有，不能作为依据。
     */
    fun looksLoggedIn(cookie: String): Boolean = cookieValue(cookie, "BDUSS").isNotBlank()

    /**
     * 从积分接口里取「剩余/可用积分」。
     *
     * 字段名不定（points / point / available / residue 都可能），逐个兜底；
     * 全都取不到就返回 null，让界面显示「—」而不是 0。
     */
    fun parsePoints(result: JSONObject): Int? {
        val direct = result.opt("points")
        if (direct is Number) return direct.toInt()
        listOf("point", "available", "residue", "balance", "value").forEach { key ->
            val value = result.opt(key)
            if (value is Number) return value.toInt()
            if (value is String) value.trim().toIntOrNull()?.let { return it }
        }
        val nested = result.optJSONObject("data")
            ?: result.optJSONObject("user") ?: return null
        return parsePoints(nested)
    }

    /**
     * 把算力卡余额整理成一句人话（如 "32.5 点"）。
     *
     * 平台的返回形态差异很大：可能是数字、可能是带单位的字符串，也可能压根没有
     * 这个字段。拿不到就返回 null。
     */
    fun parseComputeCard(result: JSONObject): String? {
        listOf("computeCard", "resourceCard", "card", "quota", "remain", "residue").forEach { key ->
            val value = result.opt(key)
            when (value) {
                is Number -> return "${value.toDouble()} 点"
                is String -> if (value.isNotBlank()) return value.trim()
            }
        }
        // 也有把算力拆成「总/已用/剩余」三个数字的情况。
        val total = firstDouble(result, listOf("total", "totalQuota", "amount"))
        val remain = firstDouble(result, listOf("remain", "remaining", "left", "usable"))
        if (remain != null) return "$remain 点"
        if (total != null) return "$total 点"
        return null
    }

    private fun firstDouble(root: JSONObject, keys: List<String>): Double? {
        keys.forEach { key ->
            val value = root.opt(key)
            if (value is Number) return value.toDouble()
        }
        return null
    }

    /**
     * 把 Cookie 收敛成平台域名下可用的一份。
     *
     * 不做过滤：百度系的 passToken/STOKEN/BDUSS/_xsrf 等都可能参与鉴权，
     * 丢掉任何一个都会导致"明明登录了却一直跳登录页"。整串带上是唯一稳妥的做法。
     */
    fun normalizeCookie(raw: String): String = raw
        .split(';')
        .map(String::trim)
        .filter { it.isNotBlank() && it.contains('=') }
        .joinToString("; ")

    /** 账号身份的唯一键：优先 UID，其次昵称，最后按 Cookie 内容散列。 */
    fun accountKey(uid: String, nickname: String, cookie: String): String = when {
        uid.isNotBlank() -> "uid:$uid"
        nickname.isNotBlank() -> "name:$nickname"
        else -> "cookie:${cookie.hashCode()}"
    }

    fun newAccount(
        cookie: String,
        bdToken: String,
        uid: String,
        nickname: String,
        now: Long,
    ): AiStudioAccount = AiStudioAccount(
        id = accountKey(uid, nickname, cookie),
        nickname = nickname,
        uid = uid,
        cookie = normalizeCookie(cookie),
        bdToken = bdToken,
        lastUsedAt = now,
    )

    // ===== 内部小工具 =====

    private fun firstArray(root: JSONObject, keys: List<String>): JSONArray? {
        keys.forEach { key ->
            root.optJSONArray(key)?.let { return it }
            // 有些接口把数组放在嵌套对象里
            root.optJSONObject(key)?.let { nested ->
                keys.forEach { inner ->
                    nested.optJSONArray(inner)?.let { return it }
                }
            }
        }
        return null
    }

    private fun firstString(root: JSONObject, keys: List<String>): String {
        keys.forEach { key ->
            val value = root.optString(key)
            if (value.isNotBlank() && value != "null") return value
        }
        return ""
    }

    private fun firstLong(root: JSONObject, keys: List<String>): Long {
        keys.forEach { key ->
            val value = root.optLong(key, 0L)
            if (value != 0L) return value
        }
        return 0L
    }
}

/** AI Studio 接口调用失败。message 一律是可以直接展示给用户的中文。 */
class AiStudioException(message: String) : IllegalStateException(message)
