package com.local.comfyuimobile.model

enum class ConnectionStatus { DISCONNECTED, CONNECTING, CONNECTED, RECONNECTING, ERROR }

data class ServerProfile(
    val id: String,
    val name: String,
    val baseUrl: String,
    val lastSeen: Long = 0L,
    val comfyVersion: String = "",
    val cookie: String = "",
)

data class DeviceStats(
    val name: String,
    val vramTotal: Long,
    val vramFree: Long,
)

data class SystemStats(
    val comfyVersion: String,
    val frontendVersion: String,
    val devices: List<DeviceStats>,
)

data class WorkflowEntry(
    val name: String,
    val path: String,
    val isDirectory: Boolean,
    val size: Long = 0L,
    val modified: Double = 0.0,
)

enum class ParameterKind { TEXT, MULTILINE, INTEGER, DECIMAL, BOOLEAN, COMBO, IMAGE, VIDEO, UNSUPPORTED }
enum class ParameterSection { PRIMARY, MORE }

/** 种子策略：默认随机，可切换固定/上一个。 */
enum class SeedMode { RANDOM, FIXED, PREVIOUS }

data class ParameterField(
    val key: String,
    val nodeId: String,
    val nodeTitle: String,
    val nodeType: String,
    val name: String,
    val label: String,
    val widgetType: String,
    val kind: ParameterKind,
    val valueJson: String,
    val originalValueJson: String = valueJson,
    val displayValue: String,
    val options: List<String> = emptyList(),
    val minimum: Double? = null,
    val maximum: Double? = null,
    val step: Double? = null,
    val linked: Boolean = false,
    val visible: Boolean = true,
    val section: ParameterSection = ParameterSection.MORE,
    val order: Int = 0,
    val warning: String? = null,
    val widgetIndex: Int = -1,
    val refreshesWorkflow: Boolean = false,
    val nodeOrder: Int = 0,
)

data class WorkflowNode(
    val id: String,
    val title: String,
    val type: String,
    val order: Int,
    val isController: Boolean = false,
    val isOutput: Boolean = false,
    val inputMarkers: List<WorkflowConnectionMarker> = emptyList(),
    val outputMarkers: List<WorkflowConnectionMarker> = emptyList(),
)

data class WorkflowConnectionMarker(
    val label: String,
    val type: String,
    val color: String,
    val portName: String,
)

data class WorkflowManifest(
    val fields: List<ParameterField>,
    val nodes: List<WorkflowNode>,
)

data class WorkflowDocument(
    val entry: WorkflowEntry,
    val rawJson: String,
    val fields: List<ParameterField>,
    val nodes: List<WorkflowNode> = emptyList(),
    val serverUrl: String = "",
    val baseModified: Double = entry.modified,
    val hasUnsavedChanges: Boolean = false,
    val dirtyFieldKeys: Set<String> = emptySet(),
)

enum class JobState { RUNNING, PENDING, SUCCESS, ERROR, CANCELLED, UNKNOWN }

data class JobSummary(
    val id: String,
    val state: JobState,
    val workflowName: String = "",
    val workflowPath: String = "",
    val workflowJson: String? = null,
    val progress: Float? = null,
    val currentNode: String? = null,
    val submittedByApp: Boolean = false,
    val message: String = "",
    val durationMillis: Long? = null,
)

enum class AppDestination { PARAMETERS, RESULTS }

data class AppNavigationRequest(
    val id: Long,
    val destination: AppDestination,
)

enum class MediaKind { IMAGE, VIDEO }
enum class ResultSource { LOCAL, CLOUD }

data class ResultMedia(
    val jobId: String,
    val nodeId: String,
    val nodeType: String = "",
    val nodeTitle: String = "",
    val filename: String,
    val subfolder: String,
    val type: String,
    val kind: MediaKind,
    val url: String,
    val createdAt: Long = 0L,
    val taskNumber: Long = 0L,
    val workflowPath: String = "",
    val workflowName: String = "",
    val elapsedMs: Long? = null,
    // v0.1.76：总耗时（含排队），来自 App 本地"提交时刻→任务完成时刻"。
    // 服务器 /history 的 elapsedMs 只算 execution_start→execution_success 的执行时间，
    // 在 AI Studio 这类排队时间长的平台上用户体感严重偏短，补上排队部分。
    val totalElapsedMs: Long? = null,
    val seed: String? = null,
    val positivePrompt: String? = null,
    val source: ResultSource = ResultSource.CLOUD,
    val localPath: String? = null,
    // v0.1.67：图片查看页要直接显示分辨率并支持复制种子。ComfyUI /history
    // 不返回 width/height，所以留空、由 UI 在第一次解码成功后回填。
    val intrinsicWidth: Int? = null,
    val intrinsicHeight: Int? = null,
) {
    fun stableKey(): String = listOf(jobId, nodeId, type, subfolder, filename).joinToString("/")

    /** 解析后的分辨率文案，未知时返回 null。 */
    fun resolutionLabel(): String? {
        val w = intrinsicWidth ?: return null
        val h = intrinsicHeight ?: return null
        return "${w} × ${h}"
    }

    /** 复制种子时的展示文本。 */
    fun seedCopyValue(): String? = seed?.takeIf { it.isNotBlank() }
}

data class CacheOutputRule(
    val serverUrl: String,
    val nodeType: String,
    val nodeTitle: String,
    val workflowPath: String = "",
    val workflowName: String = "",
    val nodeId: String = "",
    val enabled: Boolean = true,
)

data class FieldProblem(val fieldKey: String, val nodeId: String, val message: String)

data class GeneratedPrompt(
    val promptJson: String,
    val workflowJson: String,
)

data class UpdateInfo(
    val tag: String,
    val apkUrl: String,
    val sha256Url: String?,
    val releaseUrl: String,
)

data class AppUiState(
    val status: ConnectionStatus = ConnectionStatus.DISCONNECTED,
    val connectionMessage: String = "尚未连接",
    val connectionStep: Int = 0,
    val connectionTotalSteps: Int = 6,
    // 留空以便显示输入框的提示语；这里原先硬编码了开发者的内网地址，属于无谓的信息泄漏。
    val serverInput: String = "",
    val serverCookie: String = "",
    val activeServer: ServerProfile? = null,
    val savedServers: List<ServerProfile> = emptyList(),
    val discoveredServers: List<ServerProfile> = emptyList(),
    val systemStats: SystemStats? = null,
    val queueRemaining: Int = 0,
    val workflows: List<WorkflowEntry> = emptyList(),
    val recentWorkflowPaths: List<String> = emptyList(),
    val selectedWorkflow: WorkflowDocument? = null,
    val previewWorkflow: WorkflowDocument? = null,
    val fields: List<ParameterField> = emptyList(),
    val jobs: List<JobSummary> = emptyList(),
    val results: List<ResultMedia> = emptyList(),
    val localResults: List<ResultMedia> = emptyList(),
    val cacheOutputRules: List<CacheOutputRule> = emptyList(),
    val cacheClearedAt: Long = 0L,
    val localDraftCount: Int = 0,
    val favoriteResultKeys: Set<String> = emptySet(),
    val nodeProblems: Map<String, List<String>> = emptyMap(),
    val activeJobId: String? = null,
    val currentExecutingNodeId: String? = null,
    val generationProgress: Float? = null,
    val generationMessage: String = "",
    val navigationRequest: AppNavigationRequest? = null,
    val promptHistory: List<String> = emptyList(),
    val submittedJobIds: Set<String> = emptySet(),
    val autoSaveResults: Boolean = false,
    val localDraftsEnabled: Boolean = false,
    val loggingEnabled: Boolean = false,
    val loading: Boolean = false,
    val scanning: Boolean = false,
    val generating: Boolean = false,
    val batchCount: Int = 1,
    val seedMode: SeedMode = SeedMode.RANDOM,
    val saveFolderUri: String? = null,
    val quickWorkflowPath: String? = null,
    val quickWorkflowName: String? = null,
    val quickFields: List<ParameterField> = emptyList(),
    val quickEnabledParams: List<String> = emptyList(),
    val bridgeReady: Boolean = false,
    val advancedEditor: Boolean = false,
    val workflowOverwriteRequired: Boolean = false,
    val workflowOverwriteReason: String = "",
    val workflowDraftConflictRequired: Boolean = false,
    val workflowDraftConflictReason: String = "",
    val error: String? = null,
    val notice: String? = null,
    val updateInfo: UpdateInfo? = null,
    val updateDownloading: Boolean = false,
    val updateDownloadProgress: Float? = null,
    val updateDownloadSource: String? = null,
)
