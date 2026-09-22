package com.local.comfyuimobile.network

import com.local.comfyuimobile.model.AiStudioAccount
import com.local.comfyuimobile.model.AiStudioPointAction
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
    /** 积分任务列表（任务名/多少分/是否完成）。 */
    const val PATH_POINT_ACTION = "/point/user/action"
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
    /** 设为公开 / 删除项目（积分任务「发布项目」用到）。 */
    const val PATH_PROJECT_PUBLIC = "/studio/project/public"
    const val PATH_PROJECT_DELETE = "/studio/project/delete"
    /** 生成项目版本（公开前必须先有版本，否则平台回“当前项目没有版本”）。 */
    const val PATH_PROJECT_VERSION_ADD = "/studio/project/version/add"

    // ===== 启动 / 停止环境 =====
    const val PATH_NOTEBOOK_ENTER = "/studio/project/notebook/enter"
    const val PATH_NOTEBOOK_CONFIG = "/studio/notebook/config"
    const val PATH_CLUSTER_ALL_LIST = "/studio/project/cluster/allList"
    const val PATH_PROJECT_RUNNING = "/studio/project/running"
    /** 启动后查环境连接信息（返回 baseUrl / token / hubBaseUrl，用于 Jupyter 内核）。 */
    const val PATH_RUNNING_STATUS_CHECK = "/studio/project/running_status_check"
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
            // 中文之间不要空格：之前是「领取算力 失败：…」，读着别扭。
            throw AiStudioException("${action}失败：$msg（错误码 $code）")
        }
        // 注意：result 可能是**对象也可能是数组**（如 /point/user/action）。
        // 数组时 optJSONObject 返回 null，不能直接 fallback 成 root——那样数组就丢了。
        // 把数组包成 {"result": [...]} 返回，让调用方统一能取到。
        return root.optJSONObject("result")
            ?: root.optJSONArray("result")?.let { JSONObject().put("result", it) }
            ?: root
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
    data class ProjectPage(val projects: List<AiStudioProject>, val total: Int)

    /**
     * 解析「我的项目」列表。
     *
     * 真实响应：`{errorCode, result: {data: [...], allCount, page, pageSize}}`
     * —— 数组在 `result.data`，总数在 `result.allCount`（前端读的就是这两个）。
     */
    fun parseProjectPage(result: JSONObject): ProjectPage {
        val array = firstArray(
            result,
            listOf("data", "list", "records", "items", "projectList", "rows"),
        ) ?: return ProjectPage(emptyList(), 0)
        val total = listOf("allCount", "totalCount", "total", "count")
            .firstNotNullOfOrNull { key ->
                val value = result.opt(key)
                when (value) {
                    is Number -> value.toInt()
                    is String -> value.toIntOrNull()
                    else -> null
                }
            } ?: array.length()
        val projects = buildList {
            repeat(array.length()) { index ->
                val item = array.optJSONObject(index) ?: return@repeat
                val id = firstString(item, listOf("projectId", "id", "project_id"))
                if (id.isBlank()) return@repeat
                add(
                    AiStudioProject(
                        projectId = id,
                        name = firstString(item, listOf("projectName", "name", "title")),
                        description = firstString(item, listOf("projectAbs", "projectDesc", "description", "abs")),
                        statusRaw = item.optInt("projectState", item.optInt("status", 0)),
                        running = parseRunning(item),
                        updatedAt = firstLong(item, listOf("updateTime", "updateTimeStamp", "updatedAt", "mtime")),
                        isNotebook = parseIsNotebook(item),
                    ),
                )
            }
        }
        return ProjectPage(projects, total)
    }

    /** 只取项目列表（不需要总数时用）。 */
    fun parseProjects(result: JSONObject): List<AiStudioProject> = parseProjectPage(result).projects

    /**
     * 判断项目是否在运行。
     *
     * 平台的字段名与取值都不稳定：可能是布尔 `running`/`isRunning`，也可能是
     * 数字 `status`（1 常表示运行中），还有 `runStatus` 字符串。任一命中即可，
     * 全都没有就按"未运行"处理——大不了让用户点一下启动，比误判成运行中而
     * 什么都不做要好。
     */
    /**
     * 判断项目是否在运行。
     *
     * 平台真实字段：`running`（布尔，前端直接读它）。其余名字作为兜底。
     * 全都没线索时按「未运行」处理——大不了让用户点一下启动，比误判成
     * 运行中而什么都不做要好。
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
            if (lowered.contains("run")) return true
            if (lowered.contains("stop") || lowered.contains("idle")) return false
        }
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
     * 平台把可选环境放在 `result.scheduleList`，每项形如：
     * `{scheduleName, displayName, gpuType, costPerHour, available, weekQuotaType, ...}`
     * 其中 `costPerHour` 是该档每小时消耗的算力卡（分），`available` 1=可 2=不可 3=算力不足。
     */
    fun parseSchedules(result: JSONObject): List<AiStudioSchedule> {
        val array = firstArray(
            result,
            listOf("scheduleList", "list", "records", "items", "clusterList", "data"),
        ) ?: return emptyList()
        return buildList {
            repeat(array.length()) { index ->
                val item = array.optJSONObject(index) ?: return@repeat
                val scheduleName = firstString(item, listOf("scheduleName", "name", "schedule"))
                if (scheduleName.isBlank()) return@repeat
                val fallbackLabel = SCHEDULE_LABELS[scheduleName]
                add(
                    AiStudioSchedule(
                        scheduleName = scheduleName,
                        // 平台给的 displayName 优先，没有就用本地已知枚举名，最后查 scheduleName。
                        label = firstString(item, listOf("displayName", "label", "showName"))
                            .ifBlank { fallbackLabel.orEmpty() },
                        gpuType = firstString(item, listOf("gpuType", "gpu", "resourceType", "cardType")),
                        costPerHour = (item.opt("costPerHour") as? Number)?.toDouble(),
                        weekQuotaType = firstString(item, listOf("weekQuotaType")),
                        available = parseScheduleAvailable(item),
                    ),
                )
            }
        }
    }

    /**
     * 平台固定的环境枚举名 → 显示名。
     *
     * 取自前端枚举（不同 chunk 里都一致）。接口不给 displayName 时用它兜底。
     */
    private val SCHEDULE_LABELS = mapOf(
        "normalSchedule" to "基础版（CPU）",
        "resourceSugonDcuSchedule" to "异构算力 16GB（DCU）",
        "resourceCardVGpuSchedule" to "高级版 V100 16GB",
        "resourceCardSchedule" to "高级版 V100 32GB",
        "resourceCardA100Schedule" to "至尊版 A100 40GB",
        "resourceMultiCardsSchedule" to "V100 四卡",
        "resourceDevGpuSchedule" to "二次开发 V100 版",
    )

    /** available 字段：1=可、2=不可、3=算力不足。 */
    private fun parseScheduleAvailable(item: JSONObject): Boolean {
        listOf("available", "enable", "enabled", "canUse").forEach { key ->
            if (item.has(key)) {
                val value = item.opt(key)
                if (value is Boolean) return value
                if (value is Number) return value.toInt() == 1
                if (value is String) return value == "1" || value.equals("true", true)
            }
        }
        // 没有可用性字段时不要禁用——宁可让用户点了收到服务器报错。
        return true
    }

    /** 签到的每日任务列表。平台：`GET /point/user/action`。 */
    fun parsePointActions(result: JSONObject): List<AiStudioPointAction> {
        // 真机响应：result 直接是数组，每项
        // {pointActionDesc, rewardPoint, isFinished, isDisposableAction, jumpUrl}
        val array = result.optJSONArray("result")
            ?: firstArray(result, listOf("actionList", "data", "list", "actions", "items", "records"))
            ?: return emptyList()
        return buildList {
            repeat(array.length()) { index ->
                val item = array.optJSONObject(index) ?: return@repeat
                val name = firstString(
                    item,
                    listOf("pointActionDesc", "actionName", "name", "title", "desc"),
                )
                if (name.isBlank()) return@repeat
                val done = listOf("isFinished", "isFinish", "finished", "isDone", "status")
                    .firstNotNullOfOrNull { key ->
                        when (val value = item.opt(key)) {
                            is Boolean -> value
                            is Number -> value.toInt() == 1
                            is String -> value == "1" || value.equals("true", true)
                            else -> null
                        }
                    } ?: false
                add(
                    AiStudioPointAction(
                        name = name,
                        points = (item.opt("rewardPoint") as? Number)?.toInt()
                            ?: (item.opt("point") as? Number)?.toInt()
                            ?: (item.opt("points") as? Number)?.toInt(),
                        done = done,
                        jumpUrl = item.optString("jumpUrl"),
                    ),
                )
            }
        }
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

    /**
     * 建项目表单体。
     *
     * 平台必填字段（从前端 savedParams 核实）：projectName / projectAbs /
     * projectType / projectEnvironment / projectFramework。projectEnvironment=2、
     * projectType=0、projectFramework=44 是 Notebook 新建页的默认值。
     */
    fun createProjectBody(name: String, description: String = ""): Map<String, String> = mapOf(
        "projectName" to name,
        "projectAbs" to description,
        "projectType" to "0",
        "projectEnvironment" to "2",
        "projectFramework" to "44",
        "templateId" to "-1",
    )

    /** 构造表单编码体（平台绝大多数 POST 走 `application/x-www-form-urlencoded`）。 */
    fun formEncode(fields: Map<String, String>): String = fields.entries.joinToString("&") {
        "${urlEncode(it.key)}=${urlEncode(it.value)}"
    }

    private fun urlEncode(value: String): String =
        java.net.URLEncoder.encode(value, "UTF-8")

    /**
     * A币余额。平台真实字段：`GET /studio/trade/coin/residue` → `result.coinNumShow`。
     */
    fun parseACoin(result: JSONObject): String? {
        listOf("coinNumShow", "coinNum", "aCoin", "coin").forEach { key ->
            val value = result.opt(key)
            when (value) {
                is Number -> return trimNumber(value.toDouble())
                is String -> if (value.isNotBlank()) return value.trim()
            }
        }
        return null
    }

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
    /**
     * 从积分接口取剩余积分。
     *
     * 平台前端真实读法：`GET /point/user/info` → `result.totalPoint`。
     * 其余名字作为兼容兜底。拿不到返回 null（界面显「—」），与「真的是 0」区分。
     */
    fun parsePoints(result: JSONObject): Int? {
        val candidates = listOf(
            "totalPoint", "totalUserPoints", "point", "points",
            "available", "residue", "balance", "score", "value",
        )
        candidates.forEach { key ->
            val value = result.opt(key)
            if (value is Number) return value.toInt()
            if (value is String) value.trim().toIntOrNull()?.let { return it }
        }
        val nested = result.optJSONObject("data")
            ?: result.optJSONObject("user") ?: return null
        return parsePoints(nested)
    }

    /**
     * 今天是否已签到。平台真实字段：`isFinishSign`（`/point/user/info`）。
     * 取不到返回 null，由调用方决定是否用本机记录兜底。
     */
    fun parseSignInDone(result: JSONObject): Boolean? {
        listOf("isFinishSign", "signInStatus", "signed", "isSign").forEach { key ->
            if (result.has(key)) {
                val value = result.opt(key)
                when (value) {
                    is Boolean -> return value
                    is Number -> return value.toInt() == 1
                    is String -> return value == "1" || value.equals("true", true)
                }
            }
        }
        return null
    }

    /**
     * 把算力卡余额整理成一句人话（如 "32.5 点"）。
     *
     * 平台的返回形态差异很大：可能是数字、可能是带单位的字符串，也可能压根没有
     * 这个字段。拿不到就返回 null。
     */
    /**
     * 算力卡余额展示文案。
     *
     * 平台口径就是「多少算力卡」—— 用户看的是数字本身（如 3761 算力卡）。
     * 不再换算成小时：resourceTotal 虽是分钟，但不同显卡每小时扣的数量不同，
     * 换成小时会让人误以为“什么卡都能跑这么久”（已被真机反馈过）。
     */
    fun parseComputeCard(result: JSONObject): String? {
        val value = parseComputeCardMinutes(result) ?: return null
        return "${trimNumber(value)} 算力卡"
    }

    /** 算力卡余额原始值（分钟）。 */
    fun parseComputeCardMinutes(result: JSONObject): Double? =
        listOf("resourceTotal", "resourceFree", "resourceQuota")
            .firstNotNullOfOrNull { key -> (result.opt(key) as? Number)?.toDouble() }

    /**
     * 本周各配额类型（V100 / A100 / DCU / DEV）剩余分钟数。
     * 平台字段：`result.resourceWeekQuotaMap`。
     */
    fun parseWeekQuotaMap(result: JSONObject): Map<String, Double> {
        val map = result.optJSONObject("resourceWeekQuotaMap") ?: return emptyMap()
        return buildMap {
            map.keys().forEach { key ->
                val value = (map.opt(key) as? Number)?.toDouble() ?: return@forEach
                put(key, value)
            }
        }
    }

    /**
     * 去掉无意义的小数尾巴，并保留至多 1 位小数。
     * 32.0 → 32；62.683333 → 62.7。
     * （之前直接 toString() 会吐出 62.68333333333333 这种长小数，界面很脏。）
     */
    private fun trimNumber(value: Double): String {
        val rounded = Math.round(value * 10.0) / 10.0
        return if (rounded == rounded.toLong().toDouble()) rounded.toLong().toString() else rounded.toString()
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
