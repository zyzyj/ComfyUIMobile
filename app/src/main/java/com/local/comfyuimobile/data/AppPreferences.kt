package com.local.comfyuimobile.data

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.local.comfyuimobile.model.ServerProfile
import com.local.comfyuimobile.model.CacheOutputRule
import com.local.comfyuimobile.model.AiStudioAccount
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import org.json.JSONArray
import org.json.JSONObject

private val Context.dataStore by preferencesDataStore(name = "comfy_mobile")

/**
 * 终端快捷命令的出厂默认值。
 *
 * 只在用户从未设置过时用（偏好里没有这个 key 时）。一旦用户增删过，就以他自己的列表为准
 * ——包括删到空，那时不会再被默认值填回来。
 */
val DEFAULT_CONSOLE_QUICK_COMMANDS = listOf(
    "comfyui",
    "nvidia-smi",
    "ls -lh ~/models/loras",
    "tail -n 50 /tmp/comfyui.log",
)

/** 终端快捷命令条数上限（避免偏好无限膨胀 + 界面刷不完）。 */
const val MAX_QUICK_COMMANDS = 30

/**
 * 已提交任务记录的条数上限（v0.3.7）。
 *
 * 比原来的 200 宽松：一条记录占 ~60 字节，1000 条约 60KB——偏好文件完全吃得消，
 * 而保留得太少会让 `list_my_jobs` 在批量出图后查不到早期任务。
 */
const val MAX_SUBMITTED_JOBS = 1000

/**
 * GPU 会话的开始记录（v0.3.7，P0-3）。
 *
 * 与项目、档位绑在一起是因为它们**天然是一体的**：换项目/换档位就该重新记。
 * 只存时刻的话，切到一个之前跑过的项目会把旧时刻当成"现在还在跑"。
 */
data class GpuSessionStart(
    val startedAt: Long,
    val projectId: String,
    val scheduleName: String,
) {
    companion object {
        /** 编码：`时刻|项目id|档位`。 */
        fun encode(startedAt: Long, projectId: String, scheduleName: String): String =
            "$startedAt|$projectId|$scheduleName"

        /** 解析；格式不对/为空返回 null（不编造）。 */
        fun parse(raw: String): GpuSessionStart? {
            if (raw.isBlank()) return null
            val parts = raw.split('|')
            if (parts.size < 3) return null
            val startedAt = parts[0].toLongOrNull()?.takeIf { it > 0 } ?: return null
            return GpuSessionStart(
                startedAt = startedAt,
                projectId = parts[1],
                scheduleName = parts.drop(2).joinToString("|"),
            )
        }
    }
}

data class StoredSettings(
    val profiles: List<ServerProfile> = emptyList(),
    val activeServerUrl: String = "",
    val promptHistory: List<String> = emptyList(),
    val submittedJobs: Set<String> = emptySet(),
    /**
     * 与 [submittedJobs] **同一份数据**的结构化视图（v0.3.7）。
     *
     * 不是第二份存储：两者从同一个偏好键解出。分开只为让现有界面代码
     * （用 `Set<String>`）零改动，同时 MCP 侧能拿到「提交时刻 / 是否已取图」。
     */
    val submittedJobRecords: List<SubmittedJobRecord> = emptyList(),
    val autoSaveResults: Boolean = true,
    /**
     * 是否保存/恢复本地未保存草稿。
     *
     * v0.2.43：默认**开启**。以前默认关，于是参数页改完不保存，切走再回来、
     * 或 App 被杀掉后修改全部丢失（用户反馈「修改过的内容不会自动保存」）；
     * 而 README 一直声称会保存，两者对不上。草稿只存用户改过的字段（增量），
     * 开销很小，默认开才符合预期。用户可在设置里关掉。
     */
    val localDraftsEnabled: Boolean = true,
    /** 每日自动签到 + 领算力（默认开：不自动就断签）。 */
    val autoDailyTasks: Boolean = true,
    /**
     * 控制台终端快捷命令。从未设置过时（偏好里没这个 key）用出厂默认值；
     * 用户删到空也存成"已设置"的空列表，不会被默认值又填回来。
     */
    val consoleQuickCommands: List<String> = emptyList(),
    /** 控制台终端配色主题 id（见客户端 TERMINAL_THEMES）；空则用默认主题。 */
    val consoleThemeId: String = "",
    val lastUpdateCheck: Long = 0L,
    val recentWorkflows: List<String> = emptyList(),
    val cacheOutputRules: List<CacheOutputRule> = emptyList(),
    val cacheClearedAt: Long = 0L,
    val favoriteResultKeys: Set<String> = emptySet(),
    val saveFolderUri: String = "",
    val quickEnabledParamsByWorkflow: Map<String, List<String>> = emptyMap(),
    val quickFieldValuesByWorkflow: Map<String, Map<String, String>> = emptyMap(),
    val quickBatchCountByWorkflow: Map<String, Int> = emptyMap(),
    val quickSeedModeByWorkflow: Map<String, String> = emptyMap(),
    val quickWorkflowPath: String = "",
    /**
     * 内嵌 MCP server 开关与凭证（v0.2.85）。
     *
     * 默认关闭；token 持久化以便 App 重启后 AiCode 侧的配置不用改。
     */
    val mcpServerEnabled: Boolean = false,
    val mcpServerToken: String = "",
    /**
     * 是否启用 Bearer 鉴权（v0.2.97，F1）。
     *
     * 默认 **false = 免鉴权**（用户拍板）：本地服务绑 127.0.0.1，且 AiCode 的图形
     * 配置界面只能填 URL、没地方填 token。想开的用户可以打开。
     */
    val mcpServerRequireAuth: Boolean = false,
    /**
     * MCP 端口（v0.2.88）：用户改过一次就一直用。0 表示从未设置，用 [com.local.comfyuimobile.mcp.McpServerManager.DEFAULT_PORT]。
     * 端口持久化是为了不让 AiCode 那三行配置悄悄失效——端口一变就要重新复制配置。
     */
    val mcpServerPort: Int = 0,
    /**
     * 上次启动 GPU 用的档位名（scheduleName）；空表示没记录过（v0.2.98）。
     *
     * 真机实测：AI 说"已记住默认档位"，但那只是**它的会话记忆**——App 侧没有任何
     * 持久化，换个会话（或 AI 重启）就得重新问/重新猜。存下来后 start_gpu
     * 不传 schedule 时直接用它。
     */
    val lastGpuSchedule: String = "",
    /** GPU 会话开始记录（v0.3.7，P0-3）；null = 未知。 */
    val gpuSessionStart: GpuSessionStart? = null,
    // v0.1.90：AI Studio 平台账号（Cookie 即凭证）。
    val aiStudioAccounts: List<AiStudioAccount> = emptyList(),
    val aiStudioActiveId: String = "",
)

class AppPreferences(private val context: Context) {
    private object Keys {
        val profiles = stringPreferencesKey("profiles")
        val activeServerUrl = stringPreferencesKey("active_server_url")
        val promptHistory = stringPreferencesKey("prompt_history")
        val submittedJobs = stringPreferencesKey("submitted_jobs")
        val autoSaveResults = booleanPreferencesKey("auto_save_results")
        val localDraftsEnabled = booleanPreferencesKey("local_drafts_enabled")
        val autoDailyTasks = booleanPreferencesKey("auto_daily_tasks")
        val consoleQuickCommands = stringPreferencesKey("console_quick_commands")
        val consoleThemeId = stringPreferencesKey("console_theme_id")
        val lastUpdateCheck = longPreferencesKey("last_update_check")
        val recentWorkflow = stringPreferencesKey("recent_workflow")
        val recentWorkflows = stringPreferencesKey("recent_workflows")
        val cacheOutputRules = stringPreferencesKey("cache_output_rules")
        val cacheClearedAt = longPreferencesKey("cache_cleared_at")
        val favoriteResultKeys = stringPreferencesKey("favorite_result_keys")
        val saveFolderUri = stringPreferencesKey("save_folder_uri")
        val quickEnabledParams = stringPreferencesKey("quick_enabled_params")
        val quickFieldValues = stringPreferencesKey("quick_field_values")
        val quickBatchSettings = stringPreferencesKey("quick_batch_settings")
        val quickWorkflowPath = stringPreferencesKey("quick_workflow_path")
        val mcpServerEnabled = booleanPreferencesKey("mcp_server_enabled")
        val mcpServerEnabledSeq = longPreferencesKey("mcp_server_enabled_seq")
        val mcpServerToken = stringPreferencesKey("mcp_server_token")
        val mcpServerRequireAuth = booleanPreferencesKey("mcp_server_require_auth")
        val mcpServerPort = intPreferencesKey("mcp_server_port")
        val lastGpuSchedule = stringPreferencesKey("last_gpu_schedule")
        /**
         * GPU 会话开始时刻（v0.3.7，P0-3）。
         *
         * 格式 `启动毫秒|项目id|档位名`。空 = 未知。
         *
         * 为什么不拆三个 key：它们**天然是一体的**（换项目/换档位就该重新记），
         * 拆开后很容易出现"只更新了其中两个"这种平行路径。
         */
        val gpuSessionStart = stringPreferencesKey("gpu_session_start")
        val aiStudioAccounts = stringPreferencesKey("ai_studio_accounts")
        val aiStudioActiveId = stringPreferencesKey("ai_studio_active_id")
    }

    val settings: Flow<StoredSettings> = context.dataStore.data.map { preferences ->
        StoredSettings(
            profiles = decodeProfiles(preferences[Keys.profiles].orEmpty()),
            activeServerUrl = preferences[Keys.activeServerUrl].orEmpty(),
            promptHistory = decodeStrings(preferences[Keys.promptHistory].orEmpty()).take(PromptHistory.MAX_SIZE),
            submittedJobs = decodeStrings(preferences[Keys.submittedJobs].orEmpty()).toSet(),
            submittedJobRecords = McpSubmittedJobs.decode(preferences[Keys.submittedJobs].orEmpty()),
            autoSaveResults = preferences[Keys.autoSaveResults] ?: true,
            localDraftsEnabled = preferences[Keys.localDraftsEnabled] ?: true,
            autoDailyTasks = preferences[Keys.autoDailyTasks] ?: true,
            consoleQuickCommands = preferences[Keys.consoleQuickCommands]
                ?.let { decodeStrings(it) }
                ?: DEFAULT_CONSOLE_QUICK_COMMANDS,
            consoleThemeId = preferences[Keys.consoleThemeId].orEmpty(),
            lastUpdateCheck = preferences[Keys.lastUpdateCheck] ?: 0L,
            recentWorkflows = decodeStrings(preferences[Keys.recentWorkflows].orEmpty())
                .ifEmpty { listOfNotNull(preferences[Keys.recentWorkflow]?.takeIf(String::isNotBlank)) }
                .take(RecentWorkflows.MAX_SIZE),
            cacheOutputRules = decodeCacheOutputRules(preferences[Keys.cacheOutputRules].orEmpty()),
            cacheClearedAt = preferences[Keys.cacheClearedAt] ?: 0L,
            favoriteResultKeys = decodeStrings(preferences[Keys.favoriteResultKeys].orEmpty()).toSet(),
            saveFolderUri = preferences[Keys.saveFolderUri].orEmpty(),
            quickEnabledParamsByWorkflow = decodeQuickParams(preferences[Keys.quickEnabledParams].orEmpty()),
            quickFieldValuesByWorkflow = decodeQuickFieldValues(preferences[Keys.quickFieldValues].orEmpty()),
            quickBatchCountByWorkflow = decodeQuickBatchSettings(preferences[Keys.quickBatchSettings].orEmpty()).first,
            quickSeedModeByWorkflow = decodeQuickBatchSettings(preferences[Keys.quickBatchSettings].orEmpty()).second,
            quickWorkflowPath = preferences[Keys.quickWorkflowPath].orEmpty(),
            mcpServerEnabled = preferences[Keys.mcpServerEnabled] ?: false,
            mcpServerToken = preferences[Keys.mcpServerToken].orEmpty(),
            mcpServerRequireAuth = preferences[Keys.mcpServerRequireAuth] ?: false,
            mcpServerPort = preferences[Keys.mcpServerPort] ?: 0,
            lastGpuSchedule = preferences[Keys.lastGpuSchedule].orEmpty(),
            gpuSessionStart = GpuSessionStart.parse(preferences[Keys.gpuSessionStart].orEmpty()),
            aiStudioAccounts = decodeAiStudioAccounts(preferences[Keys.aiStudioAccounts].orEmpty()),
            aiStudioActiveId = preferences[Keys.aiStudioActiveId].orEmpty(),
        )
    }

    suspend fun saveServer(profile: ServerProfile) {
        context.dataStore.edit { preferences ->
            val current = decodeProfiles(preferences[Keys.profiles].orEmpty())
            val merged = listOf(profile) + current.filterNot { it.baseUrl == profile.baseUrl }
            preferences[Keys.profiles] = encodeProfiles(merged.take(12))
            preferences[Keys.activeServerUrl] = profile.baseUrl
        }
    }

    suspend fun removeServer(baseUrl: String) {
        context.dataStore.edit { preferences ->
            val remaining = decodeProfiles(preferences[Keys.profiles].orEmpty()).filterNot { it.baseUrl == baseUrl }
            preferences[Keys.profiles] = encodeProfiles(remaining)
            if (preferences[Keys.activeServerUrl] == baseUrl) preferences.remove(Keys.activeServerUrl)
        }
    }

    suspend fun savePromptHistory(history: List<String>) {
        context.dataStore.edit { it[Keys.promptHistory] = encodeStrings(history.take(PromptHistory.MAX_SIZE)) }
    }

    /**
     * 整体覆盖已提交任务集合（清理/重建场景用；新增单个 id 请用 [addSubmittedJob]）。
     *
     * v0.3.7：覆盖时**继承已有记录**的提交时刻与取图标记。界面提交后走的就是这条
     * 路径（`submittedJobIds + newId`）——若在这里无条件重建记录，MCP 刚记下的
     * 提交时刻会被抹成 0，`list_my_jobs` 就答不出「什么时候提交的」。
     */
    suspend fun saveSubmittedJobs(ids: Set<String>) {
        context.dataStore.edit { preferences ->
            val merged = McpSubmittedJobs.mergeForOverwrite(
                McpSubmittedJobs.decode(preferences[Keys.submittedJobs].orEmpty()),
                ids,
            )
            preferences[Keys.submittedJobs] = McpSubmittedJobs.encode(merged.takeLast(MAX_SUBMITTED_JOBS))
        }
    }

    /**
     * 登记一个已提交的任务 id（v0.3.5，P1-2；v0.3.7 加提交时刻）。
     *
     * **读写必须原子**：先 `settings.first()` 读、再 `saveSubmittedJobs` 写两步之间，
     * 若另有一次提交，后写的集合会覆盖先写的 → **任务 id 丢失** → 该任务不进界面
     * 跟踪（进度、通知、结果页都少一张）。所以把读和写放进同一个 `edit {}` 事务里。
     *
     * @return true 表示这次真的新增了（false = 已存在，重复登记）。
     */
    suspend fun addSubmittedJob(promptId: String, submittedAt: Long = System.currentTimeMillis()): Boolean {
        if (promptId.isBlank()) return false
        var added = false
        context.dataStore.edit { preferences ->
            val current = McpSubmittedJobs.decode(preferences[Keys.submittedJobs].orEmpty())
            if (current.any { it.jobId == promptId }) return@edit
            val next = current + SubmittedJobRecord(jobId = promptId, submittedAt = submittedAt)
            preferences[Keys.submittedJobs] = McpSubmittedJobs.encode(next.takeLast(MAX_SUBMITTED_JOBS))
            added = true
        }
        return added
    }

    /**
     * 把某个已提交任务标为「已取图」（v0.3.7）。
     *
     * `list_my_jobs` 靠它回答「哪些图还没取」——那是 AI 最需要知道的：没取的
     * 图看得到状态、已取的就不必再查。只改标记，不动时刻。
     */
    suspend fun markSubmittedJobFetched(promptId: String) {
        if (promptId.isBlank()) return
        context.dataStore.edit { preferences ->
            val current = McpSubmittedJobs.decode(preferences[Keys.submittedJobs].orEmpty())
            if (current.none { it.jobId == promptId && !it.fetched }) return@edit
            val next = current.map { if (it.jobId == promptId) it.copy(fetched = true) else it }
            preferences[Keys.submittedJobs] = McpSubmittedJobs.encode(next)
        }
    }

    suspend fun setAutoSaveResults(enabled: Boolean) {
        context.dataStore.edit { it[Keys.autoSaveResults] = enabled }
    }

    suspend fun setLocalDraftsEnabled(enabled: Boolean) {
        context.dataStore.edit { it[Keys.localDraftsEnabled] = enabled }
    }

    suspend fun setAutoDailyTasks(enabled: Boolean) {
        context.dataStore.edit { it[Keys.autoDailyTasks] = enabled }
    }

    suspend fun saveConsoleQuickCommands(commands: List<String>) {
        context.dataStore.edit {
            it[Keys.consoleQuickCommands] = encodeStrings(
                commands.map(String::trim).filter(String::isNotBlank).distinct().take(MAX_QUICK_COMMANDS),
            )
        }
    }

    suspend fun setConsoleThemeId(id: String) {
        context.dataStore.edit { it[Keys.consoleThemeId] = id }
    }

    suspend fun setLastUpdateCheck(timestamp: Long) {
        context.dataStore.edit { it[Keys.lastUpdateCheck] = timestamp }
    }

    suspend fun setRecentWorkflow(path: String, replacedPath: String? = null) {
        context.dataStore.edit { preferences ->
            val current = decodeStrings(preferences[Keys.recentWorkflows].orEmpty())
                .ifEmpty { listOfNotNull(preferences[Keys.recentWorkflow]?.takeIf(String::isNotBlank)) }
            val updated = RecentWorkflows.add(current, path, replacedPath)
            preferences[Keys.recentWorkflow] = path
            preferences[Keys.recentWorkflows] = encodeStrings(updated)
        }
    }

    suspend fun removeRecentWorkflow(path: String) {
        context.dataStore.edit { preferences ->
            val updated = RecentWorkflows.remove(
                decodeStrings(preferences[Keys.recentWorkflows].orEmpty()),
                path,
            )
            preferences[Keys.recentWorkflows] = encodeStrings(updated)
            if (preferences[Keys.recentWorkflow] == path) {
                updated.firstOrNull()?.let { preferences[Keys.recentWorkflow] = it }
                    ?: preferences.remove(Keys.recentWorkflow)
            }
        }
    }

    suspend fun saveCacheOutputRules(rules: List<CacheOutputRule>) {
        context.dataStore.edit { preferences ->
            preferences[Keys.cacheOutputRules] = JSONArray().apply {
                rules.forEach { rule ->
                    put(
                        JSONObject()
                            .put("serverUrl", rule.serverUrl)
                            .put("workflowPath", rule.workflowPath)
                            .put("workflowName", rule.workflowName)
                            .put("nodeId", rule.nodeId)
                            .put("nodeTitle", rule.nodeTitle)
                            .put("nodeType", rule.nodeType)
                            .put("enabled", rule.enabled),
                    )
                }
            }.toString()
        }
    }

    suspend fun setCacheClearedAt(timestamp: Long) {
        context.dataStore.edit { it[Keys.cacheClearedAt] = timestamp }
    }

    suspend fun saveFavoriteResultKeys(keys: Set<String>) {
        context.dataStore.edit { it[Keys.favoriteResultKeys] = encodeStrings(keys.take(1_000)) }
    }

    suspend fun setSaveFolderUri(uri: String) {
        context.dataStore.edit { it[Keys.saveFolderUri] = uri }
    }

    suspend fun saveQuickEnabledParams(workflowPath: String, keys: List<String>) {
        context.dataStore.edit { preferences ->
            val current = decodeQuickParams(preferences[Keys.quickEnabledParams].orEmpty())
            val updated = current + (workflowPath to keys.filter(String::isNotBlank).distinct())
            preferences[Keys.quickEnabledParams] = encodeQuickParams(updated)
        }
    }

    suspend fun saveQuickWorkflowPath(workflowPath: String) {
        context.dataStore.edit { it[Keys.quickWorkflowPath] = workflowPath }
    }

    suspend fun saveQuickFieldValues(workflowPath: String, values: Map<String, String>) {
        context.dataStore.edit { preferences ->
            val current = decodeQuickFieldValues(preferences[Keys.quickFieldValues].orEmpty()).toMutableMap()
            current[workflowPath] = values.filterKeys(String::isNotBlank)
            preferences[Keys.quickFieldValues] = encodeQuickFieldValues(current)
        }
    }

    suspend fun saveQuickBatchSettings(workflowPath: String, batchCount: Int, seedMode: String) {
        context.dataStore.edit { preferences ->
            val root = JSONObject(preferences[Keys.quickBatchSettings].orEmpty().ifBlank { "{}" })
            root.put(workflowPath, JSONObject().put("batchCount", batchCount).put("seedMode", seedMode))
            preferences[Keys.quickBatchSettings] = root.toString()
        }
    }

    /**
     * 写 MCP 开关（v0.3.5，P0-3）。
     *
     * @param seq 写入序号，**必须单调递增**。跨进程（服务有自己的 AppPreferences
     *   实例）时靠它丢弃过期写入：迟到的旧写入不能覆盖更新的值——否则上一次
     *   停止的余波会把用户这次的开启盖掉（「打开就关上」）。
     *
     * 为什么进程内的内存保护不够：内存态只在同一进程有效，服务与界面是
     * **同一个进程**但各有各的实例；更重要的是进程重启后内存态就没了。
     */
    suspend fun setMcpServerEnabled(enabled: Boolean, seq: Long) {
        context.dataStore.edit { preferences ->
            val recorded = preferences[Keys.mcpServerEnabledSeq] ?: 0L
            if (!McpSwitchGuard.shouldApply(seq, recorded)) {
                AppLogger.warn("丢弃过期的 MCP 开关写入：seq=$seq < 已记录 $recorded")
                return@edit
            }
            preferences[Keys.mcpServerEnabledSeq] = seq
            preferences[Keys.mcpServerEnabled] = enabled
        }
    }

    suspend fun setMcpServerToken(token: String) {
        context.dataStore.edit { it[Keys.mcpServerToken] = token }
    }

    /** 持久化 MCP 端口（0 表示回到默认）。 */
    suspend fun setMcpServerPort(port: Int) {
        context.dataStore.edit { it[Keys.mcpServerPort] = port }
    }

    /** 记下上次启动 GPU 用的档位（v0.2.98）。 */
    suspend fun setLastGpuSchedule(scheduleName: String) {
        context.dataStore.edit { it[Keys.lastGpuSchedule] = scheduleName }
    }

    /**
     * 记下一次 GPU 会话的开始（v0.3.7，P0-3）。
     *
     * 只在 `startGpu` **成功返回后**调用——那是唯一能确定"真的开始计费"的时刻。
     * 拿 `updatedAt` 之类的字段推算是不行的（它不是起始时间）。
     */
    suspend fun setGpuSessionStart(projectId: String, scheduleName: String, startedAt: Long) {
        val encoded = GpuSessionStart.encode(startedAt, projectId, scheduleName)
        context.dataStore.edit { it[Keys.gpuSessionStart] = encoded }
    }

    /** 清除本次会话记录（停止 GPU 时）。 */
    suspend fun clearGpuSessionStart() {
        context.dataStore.edit { it[Keys.gpuSessionStart] = "" }
    }

    /** 持久化"是否启用 Bearer 鉴权"（v0.2.97，F1）。 */
    suspend fun setMcpServerRequireAuth(enabled: Boolean) {
        context.dataStore.edit { it[Keys.mcpServerRequireAuth] = enabled }
    }

    /**
     * 保存 AI Studio 账号。
     *
     * 凭据（cookie / bdToken）**明文**写入 DataStore，未做加密——这是有意选择：
     * Android 官方的 security-crypto 已被 Google 弃用，而自建 Keystore 加密对
     * 本场景（个人自用客户端，Cookie 本就等同设备登录态）收益有限、迁移风险不低。
     * 真正挡住的是外泄而非本地读取：`AndroidManifest` 已设 allowBackup=false，
     * 且 data_extraction_rules 把 datastore 整个目录排除在云备份与新机迁移之外
     * （与 ComfyUI 服务器 Cookie 同一套处理），所以设备 root / 手动导出仍是唯一
     * 能读到它的路径——那是用户自己掌控的范围。
     */
    /**
     * 只更新一个账号（v0.2.97）：MCP 侧刷新令牌后回写用。
     *
     * 不复用 [saveAiStudioAccounts] 是因为那条要求调用方手里有**完整**账号列表，
     * 而服务侧只能读到偏要快照的副本——用它会把快照写回去，覆盖掉 App 在期间
     * 改过的其他账号。这里在 edit 事务内重新读出当前列表再改。
     */
    suspend fun updateAiStudioAccount(updated: AiStudioAccount) {
        context.dataStore.edit { preferences ->
            val current = decodeAiStudioAccounts(preferences[Keys.aiStudioAccounts].orEmpty())
            if (current.none { it.id == updated.id }) return@edit
            preferences[Keys.aiStudioAccounts] = encodeAiStudioAccounts(
                current.map { if (it.id == updated.id) updated else it },
            )
        }
    }

    suspend fun saveAiStudioAccounts(accounts: List<AiStudioAccount>, activeId: String) {
        context.dataStore.edit { preferences ->
            preferences[Keys.aiStudioAccounts] = encodeAiStudioAccounts(accounts)
            preferences[Keys.aiStudioActiveId] = activeId
        }
    }

    private fun encodeAiStudioAccounts(accounts: List<AiStudioAccount>): String =
        JSONArray().apply {
            accounts.forEach { account ->
                put(
                    JSONObject()
                        .put("id", account.id)
                        .put("nickname", account.nickname)
                        .put("uid", account.uid)
                        .put("cookie", account.cookie)
                        .put("bdToken", account.bdToken)
                        .put("bdTokenFetchedAt", account.bdTokenFetchedAt)
                        .put("lastUsedAt", account.lastUsedAt)
                        .put("lastSignInAt", account.lastSignInAt),
                )
            }
        }.toString()

    /**
     * 逐条解析，坏数据只跳过那一条。
     *
     * 与 [decodeProfiles] 同样的理由：一条记录缺字段不应该让全部账号凭空消失
     * （那等于让用户把所有账号重新登录一遍）。
     */
    private fun decodeAiStudioAccounts(raw: String): List<AiStudioAccount> = runCatching {
        val array = JSONArray(raw.ifBlank { "[]" })
        buildList {
            repeat(array.length()) { index ->
                val item = array.optJSONObject(index) ?: return@repeat
                val id = item.optString("id")
                val cookie = item.optString("cookie")
                if (id.isBlank() || cookie.isBlank()) return@repeat
                add(
                    AiStudioAccount(
                        id = id,
                        nickname = item.optString("nickname"),
                        uid = item.optString("uid"),
                        cookie = cookie,
                        bdToken = item.optString("bdToken"),
                        bdTokenFetchedAt = item.optLong("bdTokenFetchedAt"),
                        lastUsedAt = item.optLong("lastUsedAt"),
                        lastSignInAt = item.optLong("lastSignInAt"),
                    ),
                )
            }
        }
    }.getOrDefault(emptyList())

    private fun decodeQuickParams(raw: String): Map<String, List<String>> = runCatching {
        val array = JSONArray(raw.ifBlank { "[]" })
        buildMap {
            repeat(array.length()) { index ->
                val item = array.getJSONObject(index)
                val path = item.optString("path")
                if (path.isNotBlank()) {
                    val keys = item.optJSONArray("keys") ?: JSONArray()
                    put(path, List(keys.length()) { keys.optString(it) }.filter(String::isNotBlank))
                }
            }
        }
    }.getOrDefault(emptyMap())

    private fun encodeQuickParams(map: Map<String, List<String>>): String = JSONArray().apply {
        map.forEach { (path, keys) ->
            put(JSONObject().put("path", path).put("keys", JSONArray(keys.take(200))))
        }
    }.toString()

    private fun decodeQuickFieldValues(raw: String): Map<String, Map<String, String>> = runCatching {
        val root = JSONObject(raw.ifBlank { "{}" })
        buildMap {
            root.keys().forEach { path ->
                val item = root.optJSONObject(path) ?: return@forEach
                val values = buildMap {
                    item.keys().forEach { key -> put(key, item.optString(key)) }
                }
                put(path, values)
            }
        }
    }.getOrDefault(emptyMap())

    private fun encodeQuickFieldValues(map: Map<String, Map<String, String>>): String =
        JSONObject().apply {
            map.forEach { (path, values) -> put(path, JSONObject(values)) }
        }.toString()

    private fun decodeQuickBatchSettings(raw: String): Pair<Map<String, Int>, Map<String, String>> = runCatching {
        val root = JSONObject(raw.ifBlank { "{}" })
        val counts = mutableMapOf<String, Int>()
        val modes = mutableMapOf<String, String>()
        root.keys().forEach { path ->
            val item = root.optJSONObject(path) ?: return@forEach
            counts[path] = item.optInt("batchCount", 1).coerceIn(1, 16)
            modes[path] = item.optString("seedMode", "RANDOM")
        }
        counts to modes
    }.getOrDefault(emptyMap<String, Int>() to emptyMap())

    private fun decodeProfiles(raw: String): List<ServerProfile> = runCatching {
        val array = JSONArray(raw.ifBlank { "[]" })
        buildList {
            repeat(array.length()) { index ->
                val item = array.optJSONObject(index) ?: return@repeat
                // v0.1.87：以前这里用 getString（同函数里其他字段都是 optString），
                // 任意一条记录缺 baseUrl 就抛异常，runCatching 的 getOrDefault 会把
                // **全部**已保存服务器地址一次性清空。改成逐条跳过坏数据：
                // 丢一条比丢全部好得多。
                val baseUrl = item.optString("baseUrl")
                if (baseUrl.isBlank()) return@repeat
                add(
                    ServerProfile(
                        id = item.optString("id"),
                        name = item.optString("name"),
                        baseUrl = baseUrl,
                        lastSeen = item.optLong("lastSeen"),
                        comfyVersion = item.optString("comfyVersion"),
                        cookie = item.optString("cookie"),
                    ),
                )
            }
        }
    }.getOrDefault(emptyList())

    private fun encodeProfiles(profiles: List<ServerProfile>): String = JSONArray().apply {
        profiles.forEach { profile ->
            put(
                JSONObject()
                    .put("id", profile.id)
                    .put("name", profile.name)
                    .put("baseUrl", profile.baseUrl)
                    .put("lastSeen", profile.lastSeen)
                    .put("comfyVersion", profile.comfyVersion)
                    .put("cookie", profile.cookie),
            )
        }
    }.toString()

    private fun decodeStrings(raw: String): List<String> = runCatching {
        val array = JSONArray(raw.ifBlank { "[]" })
        List(array.length()) { array.optString(it) }.filter { it.isNotBlank() }
    }.getOrDefault(emptyList())

    private fun encodeStrings(values: Collection<String>): String = JSONArray(values).toString()

    private fun decodeCacheOutputRules(raw: String): List<CacheOutputRule> = runCatching {
        val array = JSONArray(raw.ifBlank { "[]" })
        List(array.length()) { index ->
            array.getJSONObject(index).let { item ->
                CacheOutputRule(
                    serverUrl = item.optString("serverUrl"),
                    workflowPath = item.optString("workflowPath"),
                    workflowName = item.optString("workflowName"),
                    nodeId = item.optString("nodeId"),
                    nodeTitle = item.optString("nodeTitle"),
                    nodeType = item.optString("nodeType"),
                    enabled = item.optBoolean("enabled", true),
                )
            }
        }
            .filter { it.serverUrl.isNotBlank() && it.nodeType.isNotBlank() }
            .groupBy { "${it.serverUrl}/${it.nodeType}" }
            .values
            .map { matching ->
                matching.first().copy(
                    workflowPath = "",
                    workflowName = "",
                    nodeId = "",
                    enabled = matching.any { it.enabled },
                )
            }
    }.getOrDefault(emptyList())
}
