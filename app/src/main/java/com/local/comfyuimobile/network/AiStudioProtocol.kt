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

    // ===== 启动 / 停止环境 =====
    const val PATH_NOTEBOOK_ENTER = "/studio/project/notebook/enter"
    const val PATH_NOTEBOOK_CONFIG = "/studio/notebook/config"
    /** 环境连接信息（baseUrl/token/hubBaseUrl）——前端 loadNotebookConfig 真正用的就是它。 */
    const val PATH_ENV_BASEINFO = "/studio/project/envs/baseinfo"
    const val PATH_CLUSTER_ALL_LIST = "/studio/project/cluster/allList"
    const val PATH_PROJECT_RUNNING = "/studio/project/running"
    /** 启动后查环境连接信息（返回 baseUrl / token，用于 Jupyter 终端）。 */
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
     * 把响应里的凭据值脱敏后再拿去落日志。
     *
     * 平台的 `envs/baseinfo` / `running_status_check` 响应里带 Jupyter `token`——
     * 它可以直接访问云端环境，等同于密码。而诊断日志默认开启，用户排障时又会
     * 把日志发出来，所以落日志前必须先把这些字段的主文替换掉。
     *
     * 只保留字段名与长度（长度本身对排障够用了，能看出"有没有拿到"），
     * 不做通用加密——日志是给人读的。
     */
    fun redactSecrets(raw: String): String {
        var text = raw
        REDACT_KEYS.forEach { key ->
            text = Regex("\"$key\"\\s*:\\s*\"([^\"]*)\"").replace(text) { match ->
                val value = match.groupValues[1]
                if (value.isEmpty()) "\"$key\":\"\"" else "\"$key\":\"<已省略 len=${value.length}>\""
            }
        }
        return text
    }

    private val REDACT_KEYS = listOf("token", "bdToken", "cookie")

    /**
     * 按名称合并多串 Cookie，**后面的覆盖前面的**（同名的取靠后那份）。
     *
     * 用途：账号 Cookie（BDUSS 等） + 连终端时网关下发的项目级 Cookie
     * （`ide-proxy`、`user-{uid}-{pid}`）。同名时项目级那份更新、更准，必须优先。
     * 输出保持插入顺序，方便日志排查。
     */
    fun mergeCookies(vararg parts: String): String {
        val order = LinkedHashMap<String, String>()
        parts.forEach { part ->
            part.split(';').forEach { segment ->
                val trimmed = segment.trim()
                if (trimmed.isEmpty()) return@forEach
                val eq = trimmed.indexOf('=')
                if (eq <= 0) return@forEach
                val name = trimmed.substring(0, eq).trim()
                if (name.isEmpty()) return@forEach
                order[name] = trimmed.substring(eq + 1).trim()
            }
        }
        return order.entries.joinToString("; ") { "${it.key}=${it.value}" }
    }

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
     * 算力卡余额展示值。
     *
     * **与官网完全一致**：官网用户卡就是 `(resourceTotal/60).toFixed(1)`
     * （平台源码 1629.js：`i({ num: +(r.resourceTotal/60).toFixed(1), linkText: "算力卡" })`）。
     * 所以这里只做同一道除法，不额外换算、不加单位、不做四舍五入以外的处理。
     * 用户若看不懂这个数字的含义，点卡片看说明。
     */
    fun parseComputeCard(result: JSONObject): String? {
        val minutes = parseComputeCardMinutes(result) ?: return null
        return trimNumber(minutes / 60.0)
    }

    /** 算力卡余额原始值（分钟）。 */
    fun parseComputeCardMinutes(result: JSONObject): Double? =
        listOf("resourceTotal", "resourceFree", "resourceQuota")
            .firstNotNullOfOrNull { key ->
                // 平台字段类型不稳：既可能给数字，也可能给字符串（本项目其它
                // 处已按这个前提做了 double 兼容），两种都要认。
                when (val value = result.opt(key)) {
                    is Number -> value.toDouble()
                    is String -> value.trim().toDoubleOrNull()
                    else -> null
                }
            }

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

    // ===== Jupyter 终端（terminado）WebSocket 帧 =====
    //
    // 协议是 **JSON 数组**，不是对象：发送 ["stdin", "命令"]，
    // 接收 ["stdout", "输出"]，尺寸 ["set_size", rows, cols]。
    // 平台终端就是 Jupyter 的 terminado，源码 websocket.py 里用 command[0] 取类型。
    // 以前发的是 {"type":"stdin",...}，服务器当成畸形消息直接掉线。

    /** 构造向终端发送命令的帧：`["stdin", "命令\r"]`。 */
    fun terminalStdinFrame(command: String): String =
        JSONArray().put("stdin").put(command + "\r").toString()

    /** 构造原始 stdin 帧（不补回车）：`["stdin", "..."]`。用于 Ctrl+C 等控制字符。 */
    fun terminalRawStdinFrame(raw: String): String =
        JSONArray().put("stdin").put(raw).toString()

    /** 构造终端尺寸帧：`["set_size", rows, cols]`（注意 rows 在前）。 */
    fun terminalResizeFrame(rows: Int, cols: Int): String =
        JSONArray().put("set_size").put(rows).put(cols).toString()

    /**
     * 从终端帧中提取 stdout 输出。
     *
     * 只处理 `["stdout", "..."]`；`["setup", {}]`、`["disconnect", n]` 等返回 null。
     * 帧不是数组（或非法 JSON）也返回 null——宁可少显示一行，也不能让解析异常把连接搞断。
     */
    fun parseTerminalOutput(frame: String): String? {
        val array = runCatching { JSONArray(frame) }.getOrNull() ?: return null
        if (array.optString(0) != "stdout") return null
        return array.optString(1).takeIf { it.isNotEmpty() }
    }

    /**
     * 剥离终端输出里的 ANSI 转义序列。
     *
     * Jupyter 终端（xterm 协议）会给输出带上大量控制序列：设标题的 OSC
     * （`ESC]0;host:~`）、括号粘贴模式的 CSI（`ESC[?2004h`）、颜色码（`ESC[32m`）等。
     * 这些是给终端模拟器看的，直接当普通文本渲染出来就是一串乱码（用户反馈
     * 「输入 ls 输出一堆看不懂的东西」）。这里把它们去掉，只留人看得懂的正文。
     */
    fun stripAnsi(text: String): String = ANSI_PATTERN.replace(text, "")

    /**
     * 清洗终端输出：**保留 ANSI 颜色码**，只剔掉其它控制序列。
     *
     * 与 [stripAnsi] 的差别：以前把颜色码一并剥掉，于是 `ls --color` 的着色、
     * 彩色提示符全变成一片白，一屏文字又密又平（用户反馈「终端文字很杂乱」）。
     * 这里只去「不该显示给人看」的部分——OSC（设标题）、CSI（括号粘贴模式、清行
     * 等），把 SGR（`ESC[…m`，颜色/加粗）留给界面渲染。
     */
    fun sanitizeTerminalOutput(text: String): String {
        val colored = ANSI_PATTERN.replace(text) { match ->
            val seq = match.value
            if (seq.startsWith("\u001B[") && seq.endsWith("m")) seq else ""
        }
        // ESC 要留着（它是颜色序列的开头），其余不可见控制字符丢掉。
        return buildString(colored.length) {
            colored.forEach { ch ->
                if (ch == '\n' || ch == '\r' || ch == '\t' || ch == '\u001B' || ch.code >= 32) append(ch)
            }
        }
    }

    private val ANSI_PATTERN = Regex(
        // CSI：ESC [ 参数 中间字节 结束字节
        "\u001B\\[[0-9;?]*[ -/]*[@-~]" +
            // OSC：ESC ] ... 由 BEL 或 ST(ESC \) 结束
            "|\u001B\\][^\u0007\u001B]*(?:\u0007|\u001B\\\\)" +
            // 其他双字符转义（如 ESC(B、ESC=）
            "|\u001B[@-Z\\\\-_]",
    )

    /** 去掉剩余不可见控制字符（除换行、回车外），避免界面出现方块/乱码。 */
    fun stripControlChars(text: String): String = buildString(text.length) {
        text.forEach { ch ->
            if (ch == '\n' || ch == '\r' || ch == '\t' || ch.code >= 32) append(ch)
        }
    }
}

/** AI Studio 接口调用失败。message 一律是可以直接展示给用户的中文。 */
class AiStudioException(message: String) : IllegalStateException(message)
