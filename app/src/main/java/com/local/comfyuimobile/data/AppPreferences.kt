package com.local.comfyuimobile.data

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.local.comfyuimobile.model.ServerProfile
import com.local.comfyuimobile.model.CacheOutputRule
import com.local.comfyuimobile.model.AiStudioAccount
import com.local.comfyuimobile.model.LlmConfig
import com.local.comfyuimobile.model.LlmPreset
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import org.json.JSONArray
import org.json.JSONObject

private val Context.dataStore by preferencesDataStore(name = "comfy_mobile")

data class StoredSettings(
    val profiles: List<ServerProfile> = emptyList(),
    val activeServerUrl: String = "",
    val promptHistory: List<String> = emptyList(),
    val submittedJobs: Set<String> = emptySet(),
    val autoSaveResults: Boolean = true,
    val localDraftsEnabled: Boolean = false,
    val lastUpdateCheck: Long = 0L,
    val recentWorkflows: List<String> = emptyList(),
    val cacheOutputRules: List<CacheOutputRule> = emptyList(),
    val cacheClearedAt: Long = 0L,
    val favoriteResultKeys: Set<String> = emptySet(),
    val saveFolderUri: String = "",
    val quickEnabledParamsByWorkflow: Map<String, List<String>> = emptyMap(),
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
        val lastUpdateCheck = longPreferencesKey("last_update_check")
        val recentWorkflow = stringPreferencesKey("recent_workflow")
        val recentWorkflows = stringPreferencesKey("recent_workflows")
        val cacheOutputRules = stringPreferencesKey("cache_output_rules")
        val cacheClearedAt = longPreferencesKey("cache_cleared_at")
        val favoriteResultKeys = stringPreferencesKey("favorite_result_keys")
        val saveFolderUri = stringPreferencesKey("save_folder_uri")
        val quickEnabledParams = stringPreferencesKey("quick_enabled_params")
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
            localDraftsEnabled = preferences[Keys.localDraftsEnabled] ?: false,
            lastUpdateCheck = preferences[Keys.lastUpdateCheck] ?: 0L,
            recentWorkflows = decodeStrings(preferences[Keys.recentWorkflows].orEmpty())
                .ifEmpty { listOfNotNull(preferences[Keys.recentWorkflow]?.takeIf(String::isNotBlank)) }
                .take(RecentWorkflows.MAX_SIZE),
            cacheOutputRules = decodeCacheOutputRules(preferences[Keys.cacheOutputRules].orEmpty()),
            cacheClearedAt = preferences[Keys.cacheClearedAt] ?: 0L,
            favoriteResultKeys = decodeStrings(preferences[Keys.favoriteResultKeys].orEmpty()).toSet(),
            saveFolderUri = preferences[Keys.saveFolderUri].orEmpty(),
            quickEnabledParamsByWorkflow = decodeQuickParams(preferences[Keys.quickEnabledParams].orEmpty()),
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

    suspend fun saveLlmConfig(config: LlmConfig) {
        context.dataStore.edit { preferences ->
            preferences[Keys.llmConfig] = JSONObject()
                .put("baseUrl", config.baseUrl.trim())
                .put("apiKey", config.apiKey.trim())
                .put("model", config.model.trim())
                .put("preset", config.preset.id)
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
            preset = LlmPreset.fromId(item.optString("preset")),
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
