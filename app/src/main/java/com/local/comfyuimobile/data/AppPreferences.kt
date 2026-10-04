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
import com.local.comfyuimobile.model.CommandAllowlist
import com.local.comfyuimobile.model.LlmConfig
import com.local.comfyuimobile.model.PromptPresets
import com.local.comfyuimobile.model.PromptPreset
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

data class StoredSettings(
    val profiles: List<ServerProfile> = emptyList(),
    val activeServerUrl: String = "",
    val promptHistory: List<String> = emptyList(),
    val submittedJobs: Set<String> = emptySet(),
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
    /** 用户自建的提示词预设（v0.2.54）。内置预设不入库，由代码内置。 */
    val customPresets: List<PromptPreset> = emptyList(),
    /** AI 助手的命令执行权限等级（v0.2.59）：1=每条都问 / 2=仅危险命令 / 3=不问。 */
    val commandPermissionLevel: Int = 2,
    /**
     * 用户预批准的命令模式（v0.2.83）：glob 匹配，命中直接执行、零提示。
     *
     * 空列表时用 [CommandAllowlist.DEFAULT_PATTERNS]——预置项不入库，
     * 用户一旦增删过就以他自己的为准（含删到空：那时表示"什么都不信任"）。
     */
    val trustedCommands: List<String> = emptyList(),
    // v0.1.88：AI 提示词助手所用的外部大模型配置。
    val llmConfig: LlmConfig = LlmConfig(),
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
        val customPresets = stringPreferencesKey("custom_prompt_presets")
        val commandPermissionLevel = intPreferencesKey("command_permission_level")
        val trustedCommands = stringPreferencesKey("trusted_commands")
        val llmConfig = stringPreferencesKey("llm_config")
        val aiStudioAccounts = stringPreferencesKey("ai_studio_accounts")
        val aiStudioActiveId = stringPreferencesKey("ai_studio_active_id")
    }

    val settings: Flow<StoredSettings> = context.dataStore.data.map { preferences ->
        StoredSettings(
            profiles = decodeProfiles(preferences[Keys.profiles].orEmpty()),
            activeServerUrl = preferences[Keys.activeServerUrl].orEmpty(),
            promptHistory = decodeStrings(preferences[Keys.promptHistory].orEmpty()).take(PromptHistory.MAX_SIZE),
            submittedJobs = decodeStrings(preferences[Keys.submittedJobs].orEmpty()).toSet(),
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
            customPresets = decodeCustomPresets(preferences[Keys.customPresets].orEmpty()),
            commandPermissionLevel = (preferences[Keys.commandPermissionLevel] ?: 2).coerceIn(1, 3),
            trustedCommands = preferences[Keys.trustedCommands]
                ?.let { decodeStrings(it) }
                ?: CommandAllowlist.DEFAULT_PATTERNS,
            llmConfig = decodeLlmConfig(preferences[Keys.llmConfig].orEmpty()),
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

    suspend fun saveSubmittedJobs(ids: Set<String>) {
        context.dataStore.edit { it[Keys.submittedJobs] = encodeStrings(ids.toList().takeLast(200)) }
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

    suspend fun setCommandPermissionLevel(level: Int) {
        context.dataStore.edit { it[Keys.commandPermissionLevel] = level.coerceIn(1, 3) }
    }

    /** 保存用户预批准的命令模式（v0.2.83）。传空列表即"什么都不信任"。 */
    suspend fun setTrustedCommands(patterns: List<String>) {
        val cleaned = patterns.map(String::trim).filter(String::isNotBlank).distinct()
        context.dataStore.edit { it[Keys.trustedCommands] = encodeStrings(cleaned) }
    }

    suspend fun saveCustomPresets(presets: List<PromptPreset>) {
        context.dataStore.edit { preferences ->
            preferences[Keys.customPresets] = JSONArray().apply {
                presets.forEach { preset ->
                    put(
                        JSONObject()
                            .put("id", preset.id)
                            .put("label", preset.label)
                            .put("hint", preset.hint)
                            .put("systemPrompt", preset.systemPrompt),
                    )
                }
            }.toString()
        }
    }

    private fun decodeCustomPresets(raw: String): List<PromptPreset> = runCatching {
        val array = JSONArray(raw.ifBlank { "[]" })
        buildList {
            repeat(array.length()) { index ->
                val item = array.optJSONObject(index) ?: return@repeat
                val id = item.optString("id")
                val prompt = item.optString("systemPrompt")
                // id 与正文缺一不可：没有正文的预设选中后 AI 会退回内置规则，容易让用户困惑。
                if (id.isBlank() || prompt.isBlank()) return@repeat
                add(
                    PromptPreset(
                        id = id,
                        label = item.optString("label").ifBlank { "自定义" },
                        hint = item.optString("hint"),
                        systemPrompt = prompt,
                        builtin = false,
                    ),
                )
            }
        }
    }.getOrDefault(emptyList())

    suspend fun saveLlmConfig(config: LlmConfig) {
        context.dataStore.edit { preferences ->
            preferences[Keys.llmConfig] = JSONObject()
                .put("baseUrl", config.baseUrl.trim())
                .put("apiKey", config.apiKey.trim())
                .put("model", config.model.trim())
                .put("preset", config.presetId)
                .put("temperature", config.temperature.toDouble())
                .toString()
        }
    }

    private fun decodeLlmConfig(raw: String): LlmConfig = runCatching {
        if (raw.isBlank()) return@runCatching LlmConfig()
        val item = JSONObject(raw)
        LlmConfig(
            baseUrl = item.optString("baseUrl"),
            apiKey = item.optString("apiKey"),
            model = item.optString("model"),
            presetId = item.optString("preset").ifBlank { PromptPresets.DEFAULT_PRESET_ID },
            temperature = LlmConfig.normalizeTemperature(item.optDouble("temperature", LlmConfig.DEFAULT_TEMPERATURE.toDouble()).toFloat()),
        )
    }.getOrDefault(LlmConfig())

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
    suspend fun saveAiStudioAccounts(accounts: List<AiStudioAccount>, activeId: String) {
        context.dataStore.edit { preferences ->
            preferences[Keys.aiStudioAccounts] = JSONArray().apply {
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
            preferences[Keys.aiStudioActiveId] = activeId
        }
    }

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
