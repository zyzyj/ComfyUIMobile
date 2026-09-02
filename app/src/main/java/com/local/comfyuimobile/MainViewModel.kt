package com.local.comfyuimobile

import android.app.Application
import android.content.ContentValues
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import android.os.Build
import android.os.Environment
import android.os.SystemClock
import android.provider.MediaStore
import android.provider.OpenableColumns
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.local.comfyuimobile.bridge.ComfyBridge
import com.local.comfyuimobile.bridge.AdvancedEditorSession
import com.local.comfyuimobile.bridge.WorkflowImageReader
import com.local.comfyuimobile.data.AppPreferences
import com.local.comfyuimobile.data.AppLogger
import com.local.comfyuimobile.data.AuthCookieProvider
import com.local.comfyuimobile.data.LocalResultCache
import com.local.comfyuimobile.data.PromptHistory
import com.local.comfyuimobile.data.RecentWorkflows
import com.local.comfyuimobile.data.WorkflowPolicy
import com.local.comfyuimobile.data.WorkflowPath
import com.local.comfyuimobile.data.WorkflowDraft
import com.local.comfyuimobile.data.WorkflowDraftFields
import com.local.comfyuimobile.data.WorkflowDraftStore
import com.local.comfyuimobile.data.WorkflowFormat
import com.local.comfyuimobile.data.WorkflowContentCache
import com.local.comfyuimobile.data.WorkflowSnapshotStore
import com.local.comfyuimobile.model.AppUiState
import com.local.comfyuimobile.model.AppDestination
import com.local.comfyuimobile.model.AppNavigationRequest
import com.local.comfyuimobile.model.CacheOutputRule
import com.local.comfyuimobile.model.ConnectionStatus
import com.local.comfyuimobile.model.JobState
import com.local.comfyuimobile.model.JobSummary
import com.local.comfyuimobile.model.MediaKind
import com.local.comfyuimobile.model.ParameterField
import com.local.comfyuimobile.model.ParameterKind
import com.local.comfyuimobile.model.ParameterSection
import com.local.comfyuimobile.model.ResultMedia
import com.local.comfyuimobile.model.ResultSource
import com.local.comfyuimobile.model.SeedMode
import com.local.comfyuimobile.model.ServerProfile
import com.local.comfyuimobile.model.WorkflowDocument
import com.local.comfyuimobile.model.WorkflowEntry
import com.local.comfyuimobile.model.WorkflowNode
import com.local.comfyuimobile.network.ActiveJobRecovery
import com.local.comfyuimobile.network.ComfyClient
import com.local.comfyuimobile.network.ExecutionNodeResolver
import com.local.comfyuimobile.network.LanAddress
import com.local.comfyuimobile.network.LanScanner
import com.local.comfyuimobile.network.ResultParser
import com.local.comfyuimobile.network.PromptSubmissionException
import com.local.comfyuimobile.network.PlatformResponseException
import com.local.comfyuimobile.network.ProgressStateParser
import com.local.comfyuimobile.service.JobMonitorService
import com.local.comfyuimobile.service.JobNotificationNavigation
import com.local.comfyuimobile.update.UpdateDownloadStatus
import com.local.comfyuimobile.update.UpdateManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.SupervisorJob
import kotlin.random.Random
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject
import java.io.File
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

class MainViewModel(application: Application) : AndroidViewModel(application) {
    private val app = application
    private val preferences = AppPreferences(application)
    private val localResultCache = LocalResultCache(application)
    private val workflowDrafts = WorkflowDraftStore(application)
    private val workflowSnapshots = WorkflowSnapshotStore(application)
    /**
     * 进程级作用域，只用于退出前的最后一次草稿保存。
     *
     * 这件事两边都不能碰：viewModelScope 会随 ViewModel 销毁一起取消，任务根本跑不起来；
     * 而 onCleared() 跑在主线程，用 runBlocking 干等磁盘写入完成，存储一慢就是 ANR
     * （系统回收进程时只给主线程几十毫秒）。所以单独开一个不受 ViewModel 生命周期约束
     * 的作用域做一次性 fire-and-forget：写不进去大不了丢这次草稿，不能拿主线程响应
     * 时间去换——何况平时早有防抖保存，这里只是最后一道兜底。
     */
    private val exitSaveScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val client = ComfyClient()
    private val scanner = LanScanner(application, client)
    private val updates = UpdateManager(application)
    private val clientId = application
        .getSharedPreferences("comfy_mobile_runtime", android.content.Context.MODE_PRIVATE)
        .let { store ->
            store.getString("stable_client_id", null)?.takeIf { it.isNotBlank() }
                ?: UUID.randomUUID().toString().also { store.edit().putString("stable_client_id", it).apply() }
        }
    private val _state = MutableStateFlow(AppUiState(loggingEnabled = AppLogger.isEnabled(application)))
    val state: StateFlow<AppUiState> = _state.asStateFlow()
    private var bridge: ComfyBridge? = null
    private var reconnectJob: Job? = null
    private var parameterRefreshJob: Job? = null
    private var generationJob: Job? = null
    private var workflowSaveJob: Job? = null
    private var workflowDraftSaveJob: Job? = null
    private var visibleNodeJob: Job? = null
    private val bridgeOperationMutex = Mutex()
    private val monitoredJobIds = ConcurrentHashMap.newKeySet<String>()
    private val awaitingQueueJobIds = ConcurrentHashMap.newKeySet<String>()
    private val takenOverJobIds = ConcurrentHashMap.newKeySet<String>()
    // v0.1.76：任务耗时统计。submittedAt = App 提交时刻（排队起点），completedAt =
    // execution_success/error 时刻。两者都在本机记录，同源无时钟偏差，用来给图片
    // 信息页补"总耗时（含排队）"——服务器 /history 只有执行耗时，AI Studio 这类
    // 平台排队时间长，用户体感严重偏短。
    private val submittedAt = ConcurrentHashMap<String, Long>()
    private val completedAt = ConcurrentHashMap<String, Long>()
    // v0.1.77：listWorkflows 连续成功计数。AI Studio 网关偶发 200 空列表假阳性，
    // 连续两次成功才把前端"云端工作流"开关置 true，避免 200/404 交替时开关乱翻。
    private var serverStoreSuccessStreak = 0
    @Volatile private var pendingReconnectNodeId: String? = null
    @Volatile private var pendingNotificationWorkflowPath: String? = null
    private var visibleNodeChangedAt = 0L
    @Volatile private var lastUpdateCheck: Long = 0L
    private var bridgeLoadedPath: String? = null
    private var serverInputSeeded = false
    /**
     * 已经回填过 Cookie 的服务器地址。
     *
     * 用户一旦手动改动 Cookie 输入框（包括主动清空），就再也不许自动回填覆盖，
     * 否则"清空重填"这个动作会被下一次 DataStore 推送直接抹掉。
     */
    private var cookieSeededAddress: String? = null
    private var cookiePersistJob: Job? = null

    init {
        viewModelScope.launch {
            preferences.settings.collect { stored ->
                val submittedJobsChanged = _state.value.submittedJobIds != stored.submittedJobs
                lastUpdateCheck = stored.lastUpdateCheck
                // 只在首次加载时用上次连接的地址填充输入框；之后只要还没连上
                // （activeServer == null），就绝不覆盖用户正在输入/刚设置的新地址，
                // 否则连接过程中的任何 DataStore 写入（如检查更新的时间戳）都会把
                // 输入框重置回旧的连接地址。
                val current = _state.value
                val resolvedServerInput = when {
                    current.activeServer != null -> current.activeServer.baseUrl
                    !serverInputSeeded -> {
                        serverInputSeeded = true
                        stored.activeServerUrl.ifBlank { current.serverInput }
                    }
                    else -> current.serverInput
                }
                // v0.1.69：Cookie 跟地址一样要回填。以前只在连接成功时随 ServerProfile
                // 存了一份，输入框却始终绑定着初始为空的 state.serverCookie，
                // 于是"第二次用还得重输一遍"（日志里 16:04:38 起连着 8 次
                // "需要登录或登录已失效"都是这么来的）。
                // 只在地址变化时回填一次，用户手改过就不再覆盖。
                val resolvedCookie = when {
                    cookieSeededAddress == resolvedServerInput -> current.serverCookie
                    else -> {
                        cookieSeededAddress = resolvedServerInput
                        cookieForAddress(resolvedServerInput, stored.profiles)
                    }
                }
                _state.update {
                    it.copy(
                        savedServers = stored.profiles,
                        serverCookie = resolvedCookie,
                        promptHistory = stored.promptHistory,
                        submittedJobIds = stored.submittedJobs,
                        autoSaveResults = stored.autoSaveResults,
                        localDraftsEnabled = stored.localDraftsEnabled,
                        cacheOutputRules = stored.cacheOutputRules,
                        cacheClearedAt = stored.cacheClearedAt,
                        favoriteResultKeys = stored.favoriteResultKeys,
                        recentWorkflowPaths = stored.recentWorkflows,
                        saveFolderUri = stored.saveFolderUri.ifBlank { null },
                        serverInput = resolvedServerInput,
                    )
                }
                if (submittedJobsChanged && _state.value.activeServer != null) {
                    refreshTasksInternal()
                }
                if (!stored.localDraftsEnabled) {
                    // Local drafts are off: make sure stale draft files (including
                    // corrupted ones from earlier versions) can never come back.
                    cancelPendingDraftSave()
                    runCatching { workflowDrafts.clearAll() }
                    _state.update { it.copy(localDraftCount = 0) }
                }
            }
        }
        viewModelScope.launch {
            val cached = localResultCache.load()
            _state.update { it.copy(localResults = cached) }
        }
    }

    fun attachBridge(value: ComfyBridge) {
        bridge = value
        value.onWebViewRecreated = {
            restoreBridgeAfterRendererRecreated(quick = false)
        }
        // AI Studio 平台每十几秒自动重载一轮页面：每次加载完成都快速恢复桥接，
        // 把"灰色窗口"压缩到几秒，而不是等渲染进程崩溃（那可能好几分钟才来一次）。
        value.onPageLoaded = {
            restoreBridgeAfterRendererRecreated(quick = true)
        }
    }

    fun openJobNotification(
        baseUrl: String,
        workflowPath: String,
        promptId: String,
        completed: Boolean,
    ) {
        AppLogger.info(
            "打开任务通知：任务=${promptId.ifBlank { "未知" }}，完成=$completed，工作流=${workflowPath.ifBlank { "未知" }}",
        )
        _state.update {
            it.copy(
                navigationRequest = AppNavigationRequest(
                    id = SystemClock.elapsedRealtimeNanos(),
                    destination = JobNotificationNavigation.destination(completed),
                ),
            )
        }
        workflowPath.trim().takeIf(String::isNotBlank)?.let { pendingNotificationWorkflowPath = it }

        val normalized = runCatching { LanAddress.normalize(baseUrl) }.getOrNull()
        val current = _state.value
        if (normalized == null) {
            if (current.status == ConnectionStatus.CONNECTED) restoreNotificationWorkflow()
            return
        }
        if (current.activeServer?.baseUrl == normalized && current.status == ConnectionStatus.CONNECTED) {
            restoreNotificationWorkflow()
            return
        }
        if (current.status == ConnectionStatus.CONNECTING && current.serverInput == normalized) return
        connect(normalized)
    }

    fun consumeNavigationRequest(id: Long) {
        _state.update { state ->
            if (state.navigationRequest?.id == id) state.copy(navigationRequest = null) else state
        }
    }

    fun setServerInput(value: String) {
        // 换了地址就允许重新回填一次 Cookie；不换则保持用户当前输入。
        if (cookieSeededAddress != value) cookieSeededAddress = null
        _state.update { it.copy(serverInput = value) }
    }

    fun setServerCookie(value: String) {
        // 记下"这个地址用户自己改过了"，别让下一次 DataStore 推送把输入覆盖掉。
        cookieSeededAddress = _state.value.serverInput
        _state.update { it.copy(serverCookie = value) }
        persistCookieFor(_state.value.serverInput, value)
    }

    /**
     * 把 Cookie 写回已保存的服务器档案。
     *
     * 以前 Cookie 只在连接成功那一刻才随 [ServerProfile] 落盘，于是"填了 Cookie
     * 但这次没连上"（比如手滑输错端口）就白填了。这里改成边输边存：只要这个地址
     * 已经存过档案，就顺手把 Cookie 一起更新。
     */
    private fun persistCookieFor(address: String, cookie: String) {
        val profile = savedProfileFor(address) ?: return
        if (profile.cookie == cookie) return
        // 用户粘贴/删除时 onValueChange 每个字符都来一次，直接存就是每键一次磁盘写
        // 外加一次全量状态广播。停 800ms 再落盘，输完再存。
        cookiePersistJob?.cancel()
        cookiePersistJob = viewModelScope.launch {
            delay(800)
            runCatching { preferences.saveServer(profile.copy(cookie = cookie)) }
                .onFailure { AppLogger.warn("保存认证 Cookie 失败", it) }
        }
    }

    /**
     * 按地址找已保存的服务器档案。
     *
     * 必须归一化后再比：`https://a.com:443/x` 和 `https://a.com/x` 是同一台服务器，
     * 但字符串不同。日志里用户第二次连的是带 `:443` 的地址，正是这个差异让
     * 之前存下的 Cookie 查不到。
     */
    private fun savedProfileFor(address: String): ServerProfile? {
        val target = runCatching { LanAddress.normalize(address) }.getOrNull() ?: return null
        return _state.value.savedServers.firstOrNull {
            runCatching { LanAddress.normalize(it.baseUrl) }.getOrDefault(it.baseUrl) == target
        }
    }

    /** 取某个地址已保存的 Cookie，没有就返回空串。 */
    private fun cookieForAddress(address: String, profiles: List<ServerProfile>): String {
        val target = runCatching { LanAddress.normalize(address) }.getOrNull() ?: return ""
        return profiles.firstOrNull {
            runCatching { LanAddress.normalize(it.baseUrl) }.getOrDefault(it.baseUrl) == target
        }?.cookie.orEmpty()
    }

    fun clearMessage() = _state.update { it.copy(error = null, notice = null) }

    fun openAdvancedEditor() {
        if (_state.value.loading || _state.value.generating || generationJob?.isActive == true) return
        val document = _state.value.selectedWorkflow ?: return
        _state.value.activeServer ?: return
        val activeBridge = bridge ?: return
        _state.update { it.copy(loading = true, error = null) }
        viewModelScope.launch {
            runCatching {
                bridgeOperationMutex.withLock {
                    ensureSelectedWorkflowLoaded()
                    val currentWorkflow = activeBridge.syncWorkflow(_state.value.fields)
                    if (document.hasUnsavedChanges) {
                        val updated = document.copy(rawJson = currentWorkflow, fields = _state.value.fields)
                        _state.update { it.copy(selectedWorkflow = updated) }
                        persistDraftSnapshot(draftSnapshot(updated, _state.value.fields))
                    }
                    AdvancedEditorSession.begin(currentWorkflow, document.entry.path)
                }
            }.onSuccess {
                _state.update { it.copy(advancedEditor = true, loading = false) }
            }.onFailure { error ->
                if (error is CancellationException) throw error
                AdvancedEditorSession.clear()
                AppLogger.error("打开高级编辑失败", error)
                _state.update {
                    it.copy(
                        advancedEditor = false,
                        loading = false,
                        error = "打开高级编辑失败：${error.message ?: error.javaClass.simpleName}",
                    )
                }
            }
        }
    }

    fun connect(address: String = state.value.serverInput) {
        // 地址里可能带 user:pass@，日志会被导出，这里只记录脱敏后的地址。
        AppLogger.info("请求连接服务器：${LanAddress.withoutCredentials(address)}")
        persistCurrentWorkflowDraft()
        reconnectJob?.cancel()
        client.closeWebSocket()
        viewModelScope.launch {
            runOperation("连接失败") {
                val activeBridge = bridge ?: error("前端桥接尚未初始化")
                _state.update {
                    it.copy(
                        status = ConnectionStatus.CONNECTING,
                        connectionMessage = "正在检查服务器地址格式",
                        connectionStep = 1,
                        loading = true,
                        activeServer = null,
                        bridgeReady = false,
                        error = null,
                    )
                }
                val normalized = LanAddress.normalize(address)
                // 状态里保留完整地址（可能含凭据），重连时才能继续带上登录信息；
                // 明文密码的屏蔽统一由界面层显示时处理。
                _state.update { it.copy(serverInput = normalized) }
                client.setServer(normalized)
                // v0.1.68：换服务器等于换平台，之前记的"某接口不支持"要作废重新探测，
                // 否则从 AI Studio 切回本地服务器后会沿用旧的退避判断。
                client.capabilities().reset(client.serverUrl())
                userdataUnsupportedLogged = false
                // v0.1.69：换服务器等于换平台，前端脚本的降级开关要复位，
                // 否则从 AI Studio 切回直连服务器后仍会跳过服务器工作流列表。
                // v0.1.72：复位的默认值必须是 false 而不是 true——这个开关决定前端脚本
                // 要不要走"服务器工作流列表/按路径加载"分支，而 AI Studio 一类平台根本不
                // 开放 /userdata。若复位成 true，从"连接完成"到"refreshWorkflowsInternal
                // 探测失败"之间的窗口期里，用户一点工作流，前端就会去读服务器上的中文
                // 路径文件，被百度网关以 400 Invalid char in url path 打回（实测日志
                // 14:11:37 预读取就是这个死法）。复位成 false 表示"探测完成前先按不支持
                // 云端存储处理"，反正加载永远有内容直载兜底；直连服务器探测成功后会由
                // refreshWorkflowsInternal 置回 true，不受影响。
                activeBridge.serverWorkflowStoreAvailable = false
                // 反向代理认证 Cookie：用户手动配置的登录态（如 AI Studio api_serving）。
                val cookie = _state.value.serverCookie
                client.setAuthCookie(cookie)
                activeBridge.setAuthCookie(cookie)
                AuthCookieProvider.current = cookie

                setConnectionStep(2, "地址检查通过，正在读取服务器版本和显卡信息")
                val (stats, profile) = client.probe(normalized)

                bridgeOperationMutex.withLock {
                    setConnectionStep(3, "服务器接口正常，正在打开 ComfyUI 网页")
                    activeBridge.loadServer(normalized)

                    setConnectionStep(4, "网页已经打开，正在初始化 ComfyUI 前端")
                    activeBridge.awaitReady()
                }

                setConnectionStep(5, "前端已经就绪，正在读取节点定义")
                client.features()
                require(client.objectInfo().length() > 0) { "服务器没有返回节点定义" }

                setConnectionStep(6, "节点定义正常，正在保存连接并同步数据")
                val savedProfile = profile.copy(cookie = cookie)
                preferences.saveServer(savedProfile)
                _state.update {
                    val sameServerDocument = it.selectedWorkflow?.takeIf { document ->
                        WorkflowDraftStore.normalizeServer(document.serverUrl) ==
                            WorkflowDraftStore.normalizeServer(savedProfile.baseUrl)
                    }
                    it.copy(
                        status = ConnectionStatus.CONNECTED,
                        connectionMessage = "已连接 ${savedProfile.name}",
                        connectionStep = it.connectionTotalSteps,
                        activeServer = savedProfile,
                        systemStats = stats,
                        bridgeReady = true,
                        loading = false,
                        selectedWorkflow = sameServerDocument,
                        fields = sameServerDocument?.fields.orEmpty(),
                        workflowDraftConflictRequired = if (sameServerDocument == null) false else it.workflowDraftConflictRequired,
                        workflowDraftConflictReason = if (sameServerDocument == null) "" else it.workflowDraftConflictReason,
                    )
                }
                if (_state.value.selectedWorkflow != null) {
                    // v0.1.78：恢复工作副本只是连接成功后的附加动作，任何失败都不该把
                    // 已经连上的服务器判成连接失败——服务器探测（/system_stats）明明过了。
                    // v0.1.77 及以前，/userdata 一次抖动就能让整条连接回滚，用户还得重连。
                    runCatching { restoreWorkingCopyAfterReconnect(activeBridge, profile.baseUrl) }
                        .onFailure { error -> AppLogger.error("恢复工作副本失败，已按本机草稿继续", error) }
                }
                openSocket()
                refreshAll()
                restoreNotificationWorkflow()
            }
        }
    }

    fun disconnect() {
        persistCurrentWorkflowDraft()
        reconnectJob?.cancel()
        client.closeWebSocket()
        awaitingQueueJobIds.clear()
        pendingReconnectNodeId = null
        pendingNotificationWorkflowPath = null
        _state.update {
            it.copy(
                status = ConnectionStatus.DISCONNECTED,
                connectionMessage = "已断开",
                connectionStep = 0,
                activeServer = null,
                systemStats = null,
                workflows = emptyList(),
                selectedWorkflow = null,
                fields = emptyList(),
                jobs = emptyList(),
                results = emptyList(),
                nodeProblems = emptyMap(),
                activeJobId = null,
                currentExecutingNodeId = null,
                generationProgress = null,
                generationMessage = "",
                bridgeReady = false,
                workflowDraftConflictRequired = false,
                workflowDraftConflictReason = "",
            )
        }
    }

    fun scanLan() {
        viewModelScope.launch {
            _state.update { it.copy(scanning = true, error = null) }
            runCatching { scanner.scan() }
                .onSuccess { found ->
                    _state.update { it.copy(discoveredServers = found, scanning = false, notice = "发现 ${found.size} 台 ComfyUI") }
                }
                .onFailure { error -> _state.update { it.copy(scanning = false, error = "扫描失败：${error.message}") } }
        }
    }

    fun refreshAll() {
        if (_state.value.status != ConnectionStatus.CONNECTED) return
        viewModelScope.launch {
            coroutineScope {
                listOf(
                    async { refreshStatsInternal() },
                    async { refreshWorkflowsInternal() },
                    async { refreshTasksInternal() },
                    async { refreshResultsInternal() },
                ).awaitAll()
            }
        }
    }

    fun refreshOrReconnect() {
        val current = _state.value
        if (current.loading || current.status == ConnectionStatus.CONNECTING) return
        val address = current.activeServer?.baseUrl ?: current.serverInput
        if (current.status != ConnectionStatus.CONNECTED) {
            connect(address)
            return
        }
        viewModelScope.launch {
            _state.update { it.copy(connectionMessage = "正在检查服务器连接", error = null) }
            val stats = runCatching { client.systemStats() }.getOrElse {
                _state.update {
                    it.copy(
                        status = ConnectionStatus.RECONNECTING,
                        connectionMessage = "刷新失败，正在重新连接",
                    )
                }
                connect(address)
                return@launch
            }
            _state.update {
                it.copy(
                    status = ConnectionStatus.CONNECTED,
                    connectionMessage = "已连接 ${it.activeServer?.name.orEmpty()}",
                    systemStats = stats,
                )
            }
            refreshAll()
        }
    }

    /**
     * v0.1.68：读工作流正文，服务器读不到就退回本地缓存。
     *
     * 百度 AI Studio 这类反向代理不开放 /userdata，readWorkflow 必然失败。但导入时
     * 内容已经完整进内存了，不该因为服务器不提供文件读写就把整条链路卡死——
     * 日志里用户连点四次快捷生图、每次都在 0.1 秒内失败，就是这个原因。
     *
     * v0.1.69：回退升级为两级（内存缓存 → 磁盘快照）。内存缓存进程一回收就没了，
     * 而这类平台上服务器那条路是**永久**走不通的，只靠内存的话用户导入一次能用、
     * 杀掉 App 再进来就又是"工作流加载失败"。
     * 服务器读成功时两边一起刷新，直连 ComfyUI 的行为跟以前完全一样。
     */
    private suspend fun readWorkflowWithFallback(serverUrl: String, path: String): String {
        val cached = WorkflowContentCache[serverUrl, path]
            ?: runCatching { workflowSnapshots.read(serverUrl, path) }.getOrNull()
        // 不能写成 runCatching{}.onSuccess{ cacheWorkflowContent(...) }：
        // onSuccess 的 lambda 不是 suspend 上下文，里面调不了挂起函数。
        val fetched = try {
            client.readWorkflow(path)
        } catch (error: CancellationException) {
            // 取消信号必须放行，否则退出页面后这个协程会继续往下走，
            // 把状态写进一个已经不存在的界面。
            throw error
        } catch (error: Throwable) {
            cached?.also {
                AppLogger.warn("服务器上读不到 $path，改用本地缓存的工作流内容", error)
            } ?: throw error
        }
        cacheWorkflowContent(serverUrl, path, fetched)
        return fetched
    }

    /** 把工作流正文写进两级缓存：内存（立即）+ 磁盘快照（尽力而为）。 */
    private suspend fun cacheWorkflowContent(serverUrl: String, path: String, json: String) {
        if (serverUrl.isBlank() || path.isBlank() || json.isBlank()) return
        WorkflowContentCache.put(serverUrl, path, json)
        runCatching { workflowSnapshots.write(serverUrl, path, json) }
            .onFailure { AppLogger.warn("工作流本地快照写入失败（不影响使用）", it) }
    }

    /**
     * 读工作流正文；读不到且本机也没存过内容时，报一条"该怎么办"的指引。
     *
     * 背景：AI Studio 这类平台的列表里会展示"最近浏览过的路径"占位条目，
     * 它们既读不到服务器、本机也没有内容，用户点了就是一句冷冰冰的
     * "该服务器不支持此接口"（这次日志里连点 15 次失败全是这个）。
     * 必须告诉用户出路：导入一次，本机就有底子了。
     */
    private suspend fun readWorkflowOrGuide(serverUrl: String, entry: WorkflowEntry): String =
        try {
            readWorkflowWithFallback(serverUrl, entry.path)
        } catch (error: CancellationException) {
            throw error
        } catch (error: IllegalStateException) {
            if (!isUserdataUnavailable(error)) throw error
            throw IllegalStateException(
                "${entry.name} 读不到：这台服务器不提供云端工作流存储，本机也没有保存过它的内容。" +
                    "请回「工作流」页点「打开工作流文件」从手机导入一次，" +
                    "导入后会自动保存在本机，以后直接点开就能用。",
            )
        }

    /** 本地快照跟着工作流改名 / 移动一起搬走，否则新路径读不到正文。 */
    private suspend fun renameWorkflowSnapshot(serverUrl: String, from: String, to: String) {
        if (serverUrl.isBlank() || from.isBlank() || to.isBlank() || from == to) return
        runCatching {
            workflowSnapshots.read(serverUrl, from)?.let { json ->
                workflowSnapshots.write(serverUrl, to, json)
                workflowSnapshots.remove(serverUrl, from)
            }
            WorkflowContentCache[serverUrl, from]?.let { json ->
                WorkflowContentCache.put(serverUrl, to, json)
                WorkflowContentCache.remove(serverUrl, from)
            }
        }.onFailure { AppLogger.warn("工作流本地快照改名失败（不影响使用）", it) }
    }

    /**
     * v0.1.68：/userdata 不可用只记一次日志。
     * 这个失败是周期性的（每轮刷新都来一次），全量记录会把诊断日志淹掉。
     */
    private var userdataUnsupportedLogged = false

    /**
     * v0.1.68：判断这次 /userdata 失败是不是"这台服务器根本没有云端工作流接口"。
     *
     * 两种都算：
     *  1. 能力门控已经把它标记为不支持（退避期内会快速失败，消息里不再有 404 字样）；
     *  2. 服务器直接回了 404/400（老版本 ComfyUI、或反代直接拒绝）。
     *
     * 以前只认第 2 种的字符串，门控一上就漏判，导入/保存会在 AI Studio 上直接失败。
     */
    private fun isUserdataUnavailable(error: Throwable): Boolean =
        (error is PlatformResponseException && error.unsupported) ||
            (error is IllegalStateException && error.message.orEmpty().let { it.contains("404") || it.contains("400") })

    /** 服务器不提供云端工作流时，用文件本身的信息构造一个"仅本地"的条目。 */
    private fun localOnlyEntry(name: String, json: JSONObject): WorkflowEntry =
        WorkflowEntry(
            name = name,
            path = "workflows/$name",
            isDirectory = false,
            size = json.toString().toByteArray().size.toLong(),
        )

    fun selectWorkflow(entry: WorkflowEntry, recordAsOpened: Boolean = false) {
        if (entry.isDirectory) return
        AppLogger.info("预读取工作流：${entry.path}")
        parameterRefreshJob?.cancel()
        viewModelScope.launch {
            runOperation("工作流加载失败") {
                flushCurrentDraft()
                _state.update {
                    it.copy(
                        loading = true,
                        error = null,
                        previewWorkflow = null,
                        selectedWorkflow = if (recordAsOpened) null else it.selectedWorkflow,
                        fields = if (recordAsOpened) emptyList() else it.fields,
                    )
                }
                val serverUrl = _state.value.activeServer?.baseUrl ?: error("尚未连接 ComfyUI 服务器")
                val draft = if (_state.value.localDraftsEnabled) {
                    workflowDrafts.load(serverUrl, entry.path)
                } else {
                    null
                }
                // Local drafts only carry parameter changes (delta) or, for
                // advanced-editor structure edits, the edited workflow JSON.
                // The server file is always the base, so a draft can never
                // replace the whole workflow with another workflow's data.
                val serverRaw = readWorkflowOrGuide(serverUrl, entry)
                val structuralDraft = draft != null && draft.structural && !draft.workflowJson.isNullOrBlank()
                val draftDiscarded = structuralDraft &&
                    WorkflowPolicy.draftStructureMismatched(draft.workflowJson!!, serverRaw)
                if (draftDiscarded) {
                    runCatching { workflowDrafts.delete(serverUrl, entry.path) }
                        .onFailure { AppLogger.error("清除不匹配的本地草稿失败", it) }
                }
                val raw = if (structuralDraft && !draftDiscarded) draft.workflowJson!! else serverRaw
                val manifest = bridgeOperationMutex.withLock {
                    (bridge ?: error("前端桥接不可用")).loadWorkflow(
                        rawJson = raw,
                        workflowPath = entry.path,
                    )
                }
                bridgeLoadedPath = entry.path
                val restoredFields = if (draft != null && !draftDiscarded) {
                    WorkflowDraftFields.restore(manifest.fields, draft.fields)
                } else {
                    manifest.fields
                }
                val conflict = draft != null && !draftDiscarded &&
                    WorkflowPolicy.hasModifiedConflict(draft.baseModified, entry.modified)
                val document = WorkflowDocument(
                    entry = entry,
                    rawJson = raw,
                    fields = restoredFields,
                    nodes = manifest.nodes,
                    serverUrl = serverUrl,
                    baseModified = if (draft != null && !draftDiscarded) draft.baseModified else entry.modified,
                    hasUnsavedChanges = draft != null && !draftDiscarded,
                    dirtyFieldKeys = if (draft != null && !draftDiscarded) {
                        draft.fields.mapTo(mutableSetOf()) { it.key }
                    } else {
                        emptySet()
                    },
                )
                _state.update {
                    val promote = recordAsOpened
                    val activeNode = ExecutionNodeResolver.resolve(
                        if (promote) it.currentExecutingNodeId else null,
                        manifest.nodes,
                    )
                    it.copy(
                        previewWorkflow = document,
                        selectedWorkflow = if (promote) document else it.selectedWorkflow,
                        fields = if (promote) restoredFields else it.fields,
                        loading = false,
                        nodeProblems = emptyMap(),
                        workflowDraftConflictRequired = conflict && promote,
                        workflowDraftConflictReason = if (conflict && promote) {
                            "手机里有未保存草稿，但服务器上的工作流已经变化。请选择继续手机草稿、读取服务器版本，或者另存为新工作流。"
                        } else {
                            ""
                        },
                        currentExecutingNodeId = if (promote) activeNode else it.currentExecutingNodeId,
                        generationMessage = if (promote && it.activeJobId != null && activeNode != null) {
                            executionMessage(activeNode, manifest.nodes, it.generationProgress)
                        } else {
                            it.generationMessage
                        },
                        notice = when {
                            !promote -> "已预读取 ${entry.name}，点击“打开参数”或双击进入参数页"
                            draftDiscarded -> "检测到本地草稿与当前工作流内容不匹配（可能是旧版本数据混入），已忽略并读取服务器版本"
                            draft != null -> "已恢复 ${entry.name} 的本地未保存草稿"
                            else -> "已加载 ${entry.name}"
                        },
                    )
                }
                if (recordAsOpened) {
                    updateRecentWorkflowState(entry.path)
                    preferences.setRecentWorkflow(entry.path)
                }
            }
        }
    }

    fun recordSelectedWorkflowOpened() {
        val path = _state.value.selectedWorkflow?.entry?.path ?: return
        updateRecentWorkflowState(path)
        viewModelScope.launch { preferences.setRecentWorkflow(path) }
    }

    /** 把当前预读取的工作流正式打开为参数页工作流。 */
    fun openPreviewedWorkflow() {
        val preview = _state.value.previewWorkflow ?: return
        val manifestNodes = preview.nodes
        _state.update { ui ->
            val activeNode = ExecutionNodeResolver.resolve(ui.currentExecutingNodeId, manifestNodes)
            ui.copy(
                selectedWorkflow = preview,
                fields = preview.fields,
                nodeProblems = emptyMap(),
                currentExecutingNodeId = activeNode,
                generationMessage = if (ui.activeJobId != null && activeNode != null) {
                    executionMessage(activeNode, manifestNodes, ui.generationProgress)
                } else {
                    ui.generationMessage
                },
            )
        }
        recordSelectedWorkflowOpened()
    }

    fun updateField(key: String, value: String) {
        val changedField = _state.value.fields.firstOrNull { it.key == key }
        val refreshesWorkflow = changedField?.refreshesWorkflow == true
        _state.update { ui ->
            val updatedFields = ui.fields.map { field ->
                if (field.key != key) field else field.copy(
                    displayValue = value,
                    valueJson = valueJson(field.kind, value),
                )
            }
            ui.copy(
                fields = updatedFields,
                selectedWorkflow = ui.selectedWorkflow?.copy(
                    fields = updatedFields,
                    hasUnsavedChanges = true,
                    dirtyFieldKeys = (ui.selectedWorkflow?.dirtyFieldKeys ?: emptySet()) + key,
                ),
                nodeProblems = changedField?.nodeId?.let { ui.nodeProblems - it } ?: ui.nodeProblems,
            )
        }
        scheduleDraftSave()
        if (refreshesWorkflow) refreshParametersAfterWorkflowSwitch()
    }

    fun setFieldVisibility(key: String, visible: Boolean) = updateFieldLayout(key) { it.copy(visible = visible) }
    fun setFieldSection(key: String, section: ParameterSection) = updateFieldLayout(key) { it.copy(section = section) }
    fun renameField(key: String, label: String) = updateFieldLayout(key) { it.copy(label = label.ifBlank { it.name }) }

    fun moveField(key: String, direction: Int) {
        _state.update { ui ->
            val sorted = ui.fields.sortedBy { it.order }.toMutableList()
            val index = sorted.indexOfFirst { it.key == key }
            val target = index + direction
            if (index !in sorted.indices || target !in sorted.indices) return@update ui
            val first = sorted[index]
            val second = sorted[target]
            sorted[index] = first.copy(order = second.order)
            sorted[target] = second.copy(order = first.order)
            val updatedFields = sorted.sortedWith(compareBy<ParameterField> { it.section.ordinal }.thenBy { it.order })
            ui.copy(
                fields = updatedFields,
                selectedWorkflow = ui.selectedWorkflow?.copy(
                    fields = updatedFields,
                    hasUnsavedChanges = true,
                    dirtyFieldKeys = (ui.selectedWorkflow?.dirtyFieldKeys ?: emptySet()) + first.key + second.key,
                ),
            )
        }
        scheduleDraftSave()
    }

    fun uploadField(field: ParameterField, uri: Uri) {
        viewModelScope.launch {
            runOperation("文件上传失败") {
                _state.update { it.copy(loading = true) }
                val resolver = app.contentResolver
                var size = -1L
                val name = resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { cursor ->
                    if (!cursor.moveToFirst()) null else {
                        val sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE)
                        if (sizeIndex >= 0 && !cursor.isNull(sizeIndex)) size = cursor.getLong(sizeIndex)
                        cursor.getString(cursor.getColumnIndexOrThrow(OpenableColumns.DISPLAY_NAME))
                    }
                } ?: "upload_${System.currentTimeMillis()}"
                val mime = resolver.getType(uri)
                val subfolder = "ComfyUIMobile/${UUID.randomUUID()}"
                val result = client.upload(name, mime, size, { resolver.openInputStream(uri) ?: error("无法读取所选文件") }, subfolder)
                updateField(field.key, listOf(result.subfolder, result.name).filter { it.isNotBlank() }.joinToString("/"))
                _state.update { it.copy(loading = false, notice = "已上传 ${result.name}") }
            }
        }
    }

    fun finishAdvancedEditor(saved: Boolean) {
        val document = _state.value.selectedWorkflow
        _state.update { it.copy(advancedEditor = false, loading = saved && document != null) }
        if (!saved || document == null) {
            AdvancedEditorSession.clear()
            return
        }
        val result = AdvancedEditorSession.consumeOutput()
        if (result == null) {
            _state.update { it.copy(loading = false, error = "高级编辑没有返回工作流内容") }
            return
        }
        val manifestIsEmpty = result.manifest.fields.isEmpty() && result.manifest.nodes.isEmpty()
        val edited = document.copy(
            rawJson = result.workflowJson,
            fields = if (manifestIsEmpty) document.fields else result.manifest.fields,
            nodes = if (manifestIsEmpty) document.nodes else result.manifest.nodes,
            hasUnsavedChanges = true,
            dirtyFieldKeys = if (manifestIsEmpty) document.dirtyFieldKeys else {
                result.manifest.fields.mapTo(mutableSetOf()) { it.key }
            },
        )
        _state.update {
            it.copy(
                selectedWorkflow = edited,
                fields = edited.fields,
                nodeProblems = emptyMap(),
                loading = !manifestIsEmpty,
                notice = if (manifestIsEmpty) {
                    "高级编辑已返回内容，但当前工作流没有已连线的输出节点，参数页暂时保持原样；请连接输出节点后重新进入高级编辑"
                } else {
                    "已读取网页当前工作流，正在同步生成环境"
                },
            )
        }
        if (manifestIsEmpty) {
            // 用户临时断开了输出连线（正常中间状态）。保留编辑后的 JSON 为结构草稿，
            // 不重新加载桥接（那会再次因无输出节点失败），避免卡死。
            viewModelScope.launch {
                persistDraftSnapshot(structuralDraftSnapshot(edited, result.workflowJson, document.fields))
            }
            return
        }
        viewModelScope.launch {
            runOperation("高级编辑同步失败") {
                val structural = !sameNodeStructure(document.nodes, result.manifest.nodes)
                if (structural) {
                    persistDraftSnapshot(structuralDraftSnapshot(edited, result.workflowJson, result.manifest.fields))
                } else {
                    persistDraftSnapshot(draftSnapshot(edited, result.manifest.fields))
                }
                val manifest = bridgeOperationMutex.withLock {
                    (bridge ?: error("前端桥接不可用")).loadWorkflow(
                        rawJson = result.workflowJson,
                        workflowPath = document.entry.path,
                    )
                }
                bridgeLoadedPath = document.entry.path
                _state.update { ui ->
                    if (ui.selectedWorkflow?.entry?.path != document.entry.path) {
                        ui.copy(loading = false)
                    } else {
                        ui.copy(
                            selectedWorkflow = edited.copy(fields = manifest.fields, nodes = manifest.nodes),
                            fields = manifest.fields,
                            loading = false,
                            notice = "高级编辑内容已同步，尚未保存到服务器",
                        )
                    }
                }
            }
        }
    }

    fun invokeSeedAction(nodeId: String, actionToken: String, successMessage: String) {
        val document = _state.value.selectedWorkflow ?: return
        viewModelScope.launch {
            runOperation("种子操作失败") {
                val activeBridge = bridge ?: error("前端桥接不可用")
                val (raw, manifest) = bridgeOperationMutex.withLock {
                    val updatedRaw = activeBridge.invokeWidgetButton(nodeId, actionToken)
                    updatedRaw to activeBridge.loadWorkflow(updatedRaw, workflowPath = document.entry.path)
                }
                bridgeLoadedPath = document.entry.path
                _state.update {
                    it.copy(
                        selectedWorkflow = document.copy(
                            rawJson = raw,
                            fields = manifest.fields,
                            nodes = manifest.nodes,
                            hasUnsavedChanges = true,
                        ),
                        fields = manifest.fields,
                        nodeProblems = emptyMap(),
                        notice = successMessage,
                    )
                }
                scheduleDraftSave(immediate = true)
            }
        }
    }

    fun removeServer(baseUrl: String) {
        viewModelScope.launch { preferences.removeServer(baseUrl) }
    }

    fun setBatchCount(count: Int) {
        val clamped = count.coerceIn(1, 16)
        _state.update { it.copy(batchCount = clamped) }
    }

    fun setSeedMode(mode: SeedMode) {
        _state.update { it.copy(seedMode = mode) }
    }

    /** 每个工作流记住上一次实际使用的种子，供"上一个种子"模式复用。 */
    private val lastSeedByWorkflow = mutableMapOf<String, String>()

    /**
     * 提交前按种子策略调整 seed 字段：
     *  - RANDOM：替换为随机种子（默认，每次出图都不同）
     *  - FIXED：保持工作流/用户填写的值
     *  - PREVIOUS：恢复该工作流上一次实际使用的种子
     */
    private fun applySeedMode(fields: List<ParameterField>, workflowPath: String): List<ParameterField> {
        val mode = _state.value.seedMode
        if (mode == SeedMode.FIXED) return fields
        var lastSeed: String? = null
        val updated = fields.map { field ->
            val isSeed = field.kind == ParameterKind.INTEGER && field.name.contains("seed", ignoreCase = true)
            if (!isSeed) {
                field
            } else {
                when (mode) {
                    SeedMode.RANDOM -> {
                        val value = Math.abs(Random.nextLong()).toString()
                        lastSeed = value
                        field.copy(valueJson = value, displayValue = value)
                    }
                    SeedMode.PREVIOUS -> lastSeedByWorkflow[workflowPath]?.let { previous ->
                        field.copy(valueJson = previous, displayValue = previous)
                    } ?: field
                    else -> field
                }
            }
        }
        if (mode == SeedMode.RANDOM && lastSeed != null) {
            lastSeedByWorkflow[workflowPath] = lastSeed!!
        }
        return updated
    }

    /** 自定义图片保存目录：传 null 恢复默认（系统相册）。 */
    fun setSaveFolder(uri: Uri?) {
        _state.update { it.copy(saveFolderUri = uri?.toString()) }
        viewModelScope.launch { preferences.setSaveFolderUri(uri?.toString().orEmpty()) }
    }

    fun quickSelectWorkflow(entry: WorkflowEntry) {
        if (entry.isDirectory) return
        AppLogger.info("快捷生图选择工作流：${entry.path}")
        viewModelScope.launch {
            runOperation("快捷工作流加载失败") {
                _state.update { it.copy(loading = true, error = null) }
                val serverUrl = _state.value.activeServer?.baseUrl ?: error("尚未连接 ComfyUI 服务器")
                val raw = readWorkflowOrGuide(serverUrl, entry)
                val manifest = bridgeOperationMutex.withLock {
                    (bridge ?: error("前端桥接不可用")).loadWorkflow(rawJson = raw, workflowPath = entry.path)
                }
                val enabledKeys = preferences.settings.first().quickEnabledParamsByWorkflow[entry.path].orEmpty()
                _state.update {
                    it.copy(
                        quickWorkflowPath = entry.path,
                        quickWorkflowName = entry.name,
                        quickFields = manifest.fields,
                        quickEnabledParams = enabledKeys.filter { key -> manifest.fields.any { it.key == key } },
                        loading = false,
                        notice = "已加载快捷工作流：${entry.name}",
                    )
                }
            }
        }
    }

    /** 快捷生图页：更新某个参数的值（仅记录用户改动，未改动的保持工作流原值）。 */
    fun quickUpdateField(key: String, value: String) {
        _state.update { st ->
            st.copy(quickFields = st.quickFields.map { field ->
                if (field.key == key) field.copy(
                    valueJson = valueJson(field.kind, value),
                    displayValue = value,
                ) else field
            })
        }
    }

    /** 快捷生图页：勾选/取消某个 DIY 参数，持久化到该工作流的配置。 */
    fun quickToggleParam(key: String) {
        val path = _state.value.quickWorkflowPath ?: return
        val updated = _state.value.quickEnabledParams.toMutableList().apply {
            if (contains(key)) remove(key) else add(key)
        }
        _state.update { it.copy(quickEnabledParams = updated) }
        viewModelScope.launch { preferences.saveQuickEnabledParams(path, updated) }
    }

    /** 快捷生图页：按用户改过的参数 + 批量数量直接提交生成。 */
    fun quickGenerate() {
        if (
            _state.value.generating || _state.value.loading ||
            generationJob?.isActive == true || workflowSaveJob?.isActive == true
        ) return
        val workflowPath = _state.value.quickWorkflowPath ?: return
        val workflowName = _state.value.quickWorkflowName ?: return
        val fields = _state.value.quickFields
        if (fields.isEmpty()) return
        AppLogger.info("快捷生图提交：$workflowPath，参数 ${fields.size} 项，批量 ${_state.value.batchCount}")
        _state.update {
            it.copy(
                generating = true,
                error = null,
                currentExecutingNodeId = null,
                generationProgress = null,
                generationMessage = "正在整理快捷生图参数",
            )
        }
        generationJob = viewModelScope.launch {
            runOperation("提交生成失败") {
                val fields = applySeedMode(_state.value.quickFields, workflowPath)
                val generated = bridgeOperationMutex.withLock {
                    ensureBridgeReadyForQuick()
                    (bridge ?: error("前端桥接不可用")).buildPrompt(fields, _state.value.batchCount)
                }
                val response = try {
                    client.queuePrompt(
                        generated.promptJson,
                        generated.workflowJson,
                        clientId,
                        workflowPath,
                        workflowName,
                    )
                } catch (error: PromptSubmissionException) {
                    _state.update { it.copy(nodeProblems = error.nodeProblems) }
                    throw error
                }
                awaitingQueueJobIds.add(response.promptId)
                submittedAt[response.promptId] = System.currentTimeMillis()
                val submitted = _state.value.submittedJobIds + response.promptId
                preferences.saveSubmittedJobs(submitted)
                _state.update {
                    it.copy(
                        submittedJobIds = submitted,
                        generating = false,
                        nodeProblems = emptyMap(),
                        activeJobId = response.promptId,
                        currentExecutingNodeId = null,
                        generationProgress = null,
                        generationMessage = "已经加入队列，等待服务器执行",
                        notice = "已加入队列：${response.promptId.take(8)}",
                    )
                }
                startMonitor(response.promptId, workflowName, workflowPath)
                refreshTasksInternal()
            }
        }.also { job ->
            job.invokeOnCompletion { if (generationJob === job) generationJob = null }
        }
    }

    private suspend fun ensureBridgeReadyForQuick() {
        // 参数页可能在快捷页之后加载了别的工作流，桥接画布因此指向了别的图；
        // 快捷生成前必须确保桥接侧就是快捷页选中的工作流，否则参数会写错图。
        val path = _state.value.quickWorkflowPath ?: return
        if (bridgeLoadedPath != path) {
            val raw = readWorkflowWithFallback(_state.value.activeServer?.baseUrl.orEmpty(), path)
            (bridge ?: error("前端桥接不可用")).loadWorkflow(rawJson = raw, workflowPath = path)
            bridgeLoadedPath = path
        }
    }

    fun generate() {
        if (
            _state.value.generating || _state.value.loading ||
            generationJob?.isActive == true || workflowSaveJob?.isActive == true
        ) return
        val workflow = _state.value.selectedWorkflow ?: return
        AppLogger.info("开始提交生成：${workflow.entry.path}")
        _state.update {
            it.copy(
                generating = true,
                error = null,
                currentExecutingNodeId = null,
                generationProgress = null,
                generationMessage = "正在整理工作流参数",
            )
        }
        generationJob = viewModelScope.launch {
            runOperation("提交生成失败") {
                val fields = applySeedMode(_state.value.fields, workflow.entry.path)
                val generated = bridgeOperationMutex.withLock {
                    ensureSelectedWorkflowLoaded()
                    (bridge ?: error("前端桥接不可用")).buildPrompt(fields, _state.value.batchCount)
                }
                val response = try {
                    client.queuePrompt(
                        generated.promptJson,
                        generated.workflowJson,
                        clientId,
                        workflow.entry.path,
                        workflow.entry.name,
                    )
                } catch (error: PromptSubmissionException) {
                    _state.update { it.copy(nodeProblems = error.nodeProblems) }
                    throw error
                }
                awaitingQueueJobIds.add(response.promptId)
                submittedAt[response.promptId] = System.currentTimeMillis()
                val submitted = _state.value.submittedJobIds + response.promptId
                preferences.saveSubmittedJobs(submitted)
                var history = _state.value.promptHistory
                _state.value.fields
                    .filter { it.kind == ParameterKind.MULTILINE && it.nodeType.contains("TextEncode", true) }
                    .forEach { history = PromptHistory.add(history, it.displayValue) }
                preferences.savePromptHistory(history)
                _state.update {
                    it.copy(
                        submittedJobIds = submitted,
                        promptHistory = history,
                        generating = false,
                        nodeProblems = emptyMap(),
                        activeJobId = response.promptId,
                        currentExecutingNodeId = null,
                        generationProgress = null,
                        generationMessage = "已经加入队列，等待服务器执行",
                        notice = "已加入队列：${response.promptId.take(8)}",
                    )
                }
                AppLogger.info("生成任务已加入队列：${response.promptId}")
                startMonitor(response.promptId, workflow.entry.name, workflow.entry.path)
                refreshTasksInternal()
            }
        }.also { job ->
            job.invokeOnCompletion {
                if (generationJob === job) generationJob = null
            }
        }
    }

    fun saveWorkflow(force: Boolean = false) {
        if (workflowSaveJob?.isActive == true || generationJob?.isActive == true || _state.value.generating) return
        val document = _state.value.selectedWorkflow ?: return
        _state.update {
            it.copy(
                loading = true,
                workflowOverwriteRequired = false,
                workflowOverwriteReason = "",
                error = null,
            )
        }
        workflowSaveJob = viewModelScope.launch {
            runOperation("工作流保存失败") {
                flushCurrentDraft()
                val current = client.listWorkflows().firstOrNull { it.path == document.entry.path }
                if (!force && current != null) {
                    val changed = WorkflowPolicy.hasModifiedConflict(document.baseModified, current.modified)
                    _state.update {
                        it.copy(
                            loading = false,
                            workflowOverwriteRequired = true,
                            workflowOverwriteReason = if (changed) {
                                "服务器上的同名工作流已被其他设备修改。强制覆盖会丢失服务器上的改动，是否继续？"
                            } else {
                                "服务器已有同名工作流。是否用当前参数和工作流强制覆盖服务器文件？"
                            },
                        )
                    }
                    return@runOperation
                }
                val workflowJson = bridgeOperationMutex.withLock {
                    ensureSelectedWorkflowLoaded()
                    (bridge ?: error("前端桥接不可用")).syncWorkflow(_state.value.fields)
                }
                // 部分云端平台（如百度 AI Studio 的 api_serving 代理）不开放 /userdata
                // 工作流管理接口，保存会返回 404/400。此时降级为本地草稿保存，功能不中断。
                // v0.1.72：探测已知不支持时直接降级，省掉一发必败的写入请求。
                if (bridge?.serverWorkflowStoreAvailable != true) {
                    saveWorkflowAsLocalDraft(document, workflowJson)
                    return@runOperation
                }
                val saved = try {
                    client.writeWorkflow(document.entry.path, workflowJson, overwrite = current != null)
                } catch (error: IllegalStateException) {
                    // v0.1.68：改用统一判定，能力门控下的"已暂停重试"也能识别为不支持。
                    if (!isUserdataUnavailable(error)) throw error
                    saveWorkflowAsLocalDraft(document, workflowJson)
                    return@runOperation
                }
                // v0.1.69：保存成功后本地快照也要跟着更新，否则下次回退到旧正文。
                cacheWorkflowContent(document.serverUrl, saved.path, workflowJson)
                val updated = document.copy(
                    entry = saved,
                    rawJson = workflowJson,
                    fields = _state.value.fields,
                    baseModified = saved.modified,
                    hasUnsavedChanges = false,
                )
                runCatching { workflowDrafts.delete(document.serverUrl, document.entry.path) }
                    .onFailure { AppLogger.error("清理已保存工作流草稿失败", it) }
                _state.update {
                    it.copy(
                        selectedWorkflow = updated,
                        loading = false,
                        workflowOverwriteRequired = false,
                        workflowOverwriteReason = "",
                        workflowDraftConflictRequired = false,
                        workflowDraftConflictReason = "",
                        notice = "工作流已保存到服务器",
                    )
                }
                refreshWorkflowsInternal()
            }
        }.also { job ->
            job.invokeOnCompletion {
                if (workflowSaveJob === job) workflowSaveJob = null
            }
        }
    }

    /**
     * 服务器不支持云端工作流管理（如 AI Studio api_serving 未开放 /userdata）时的降级保存：
     * 把工作流内容写入本地草稿，保证下次进入还能继续使用；同时明确提示用户。
     */
    private suspend fun saveWorkflowAsLocalDraft(document: WorkflowDocument, workflowJson: String) {
        val serverUrl = document.serverUrl.ifBlank { _state.value.activeServer?.baseUrl.orEmpty() }
        workflowDrafts.save(
            WorkflowDraft(
                serverUrl = serverUrl,
                workflowPath = document.entry.path,
                workflowName = document.entry.name,
                baseModified = document.baseModified,
                workflowJson = workflowJson,
                structural = false,
                fields = WorkflowDraftFields.capture(_state.value.fields),
            ),
        )
        _state.update {
            it.copy(
                loading = false,
                workflowOverwriteRequired = false,
                workflowOverwriteReason = "",
                notice = "此服务器不支持云端保存工作流，已保存到本地草稿（下次打开自动恢复）",
            )
        }
    }

    fun saveWorkflowAs(name: String, folder: String) {
        if (workflowSaveJob?.isActive == true || generationJob?.isActive == true || _state.value.generating) return
        val document = _state.value.selectedWorkflow ?: return
        _state.update { it.copy(loading = true, error = null) }
        workflowSaveJob = viewModelScope.launch {
            runOperation("工作流另存失败") {
                flushCurrentDraft()
                val fileName = WorkflowPath.fileName(name)
                val destination = "${WorkflowPath.folder(folder)}/$fileName"
                require(destination != document.entry.path) { "另存名称不能与当前工作流相同" }
                // v0.1.72：AI Studio 这类不开放 /userdata 的服务器上 listWorkflows 必失败，
                // 别让"另存为"连输入框都过不去——重名检查只对有云端存储的服务器做。
                val serverStoreAvailable = bridge?.serverWorkflowStoreAvailable == true
                if (serverStoreAvailable) {
                    require(client.listWorkflows().none { it.path == destination }) { "同名工作流已存在，请换一个名称" }
                }

                val workflowJson = bridgeOperationMutex.withLock {
                    ensureSelectedWorkflowLoaded()
                    (bridge ?: error("前端桥接不可用")).syncWorkflow(_state.value.fields)
                }
                val savedJson = JSONObject(workflowJson)
                    .put("id", UUID.randomUUID().toString())
                    .put("revision", 0)
                    .toString()
                val saved = if (serverStoreAvailable) {
                    client.writeWorkflow(destination, savedJson, overwrite = false)
                } else {
                    // v0.1.72：不支持云端存储时，"另存为"就是在本机留一份副本——
                    // 快照 + 草稿，下次打开照常用。
                    AppLogger.info("此服务器不提供云端工作流存储，另存为已保存在本机")
                    WorkflowEntry(
                        name = fileName,
                        path = "workflows/$fileName",
                        isDirectory = false,
                        size = savedJson.toByteArray().size.toLong(),
                        modified = System.currentTimeMillis() / 1000.0,
                    )
                }
                cacheWorkflowContent(document.serverUrl, saved.path, savedJson)
                val manifest = bridgeOperationMutex.withLock {
                    (bridge ?: error("前端桥接不可用")).loadWorkflow(
                        rawJson = savedJson,
                        workflowPath = saved.path,
                    )
                }
                bridgeLoadedPath = saved.path
                val updated = document.copy(
                    entry = saved,
                    rawJson = savedJson,
                    fields = manifest.fields,
                    nodes = manifest.nodes,
                    serverUrl = _state.value.activeServer?.baseUrl ?: document.serverUrl,
                    baseModified = saved.modified,
                    hasUnsavedChanges = false,
                )
                runCatching { workflowDrafts.delete(document.serverUrl, document.entry.path) }
                    .onFailure { AppLogger.error("清理另存前的工作流草稿失败", it) }
                runCatching { workflowDrafts.delete(updated.serverUrl, saved.path) }
                    .onFailure { AppLogger.error("清理另存后的工作流草稿失败", it) }
                preferences.setRecentWorkflow(saved.path)
                _state.update {
                    it.copy(
                        selectedWorkflow = updated,
                        fields = manifest.fields,
                        loading = false,
                        nodeProblems = emptyMap(),
                        workflowDraftConflictRequired = false,
                        workflowDraftConflictReason = "",
                        notice = if (serverStoreAvailable) "已另存为 $fileName" else "已另存到本机 $fileName（此服务器不支持云端存储）",
                    )
                }
                refreshWorkflowsInternal()
            }
        }.also { job ->
            job.invokeOnCompletion {
                if (workflowSaveJob === job) workflowSaveJob = null
            }
        }
    }

    fun dismissWorkflowOverwrite() {
        _state.update { it.copy(workflowOverwriteRequired = false, workflowOverwriteReason = "") }
    }

    fun dismissWorkflowDraftConflict() {
        _state.update { it.copy(workflowDraftConflictRequired = false, workflowDraftConflictReason = "") }
    }

    fun discardLocalWorkflowDraft() {
        val document = _state.value.selectedWorkflow ?: return
        viewModelScope.launch {
            runOperation("读取服务器工作流失败") {
                flushCurrentDraft()
                _state.update { it.copy(loading = true, error = null) }
                val serverUrl = _state.value.activeServer?.baseUrl.orEmpty()
                val current = runCatching { client.listWorkflows().firstOrNull { it.path == document.entry.path } }
                    .getOrElse { error ->
                        // v0.1.68：不支持云端工作流的服务器上，这一步注定失败，
                        // 但"丢弃草稿"这个意图仍然可以完成——用本地已有的内容重建。
                        if (!isUserdataUnavailable(error)) throw error
                        null
                    }
                val entry = current ?: document.entry
                val targetPath = entry.path
                val raw = runCatching { readWorkflowWithFallback(serverUrl, targetPath) }
                    .getOrDefault(document.rawJson)
                val manifest = bridgeOperationMutex.withLock {
                    (bridge ?: error("前端桥接不可用")).loadWorkflow(
                        rawJson = raw,
                        workflowPath = targetPath,
                    )
                }
                bridgeLoadedPath = targetPath
                workflowDrafts.delete(document.serverUrl, document.entry.path)
                _state.update {
                    it.copy(
                        selectedWorkflow = WorkflowDocument(
                            entry = entry,
                            rawJson = raw,
                            fields = manifest.fields,
                            nodes = manifest.nodes,
                            serverUrl = document.serverUrl,
                            baseModified = entry.modified,
                            hasUnsavedChanges = false,
                        ),
                        fields = manifest.fields,
                        loading = false,
                        nodeProblems = emptyMap(),
                        workflowDraftConflictRequired = false,
                        workflowDraftConflictReason = "",
                        notice = "已放弃手机草稿并读取服务器版本",
                    )
                }
            }
        }
    }

    fun duplicateWorkflow(name: String) {
        val document = _state.value.previewWorkflow ?: return
        viewModelScope.launch {
            runOperation("复制工作流失败") {
                val folder = document.entry.path.substringBeforeLast('/', "workflows")
                val fileName = WorkflowPath.fileName(name)
                val currentJson = bridgeOperationMutex.withLock {
                    (bridge ?: error("前端桥接不可用")).syncWorkflow(document.fields)
                }
                val json = JSONObject(currentJson)
                    .put("id", UUID.randomUUID().toString())
                    .put("revision", 0)
                val entry = client.writeWorkflow("$folder/$fileName", json.toString(), overwrite = false)
                refreshWorkflowsInternal()
                selectWorkflow(entry)
            }
        }
    }

    fun renameWorkflow(name: String) {
        val document = _state.value.previewWorkflow ?: return
        viewModelScope.launch {
            runOperation("工作流改名失败") {
                flushCurrentDraft()
                val folder = document.entry.path.substringBeforeLast('/', "workflows")
                val fileName = WorkflowPath.fileName(name)
                val currentJson = bridgeOperationMutex.withLock {
                    (bridge ?: error("前端桥接不可用")).syncWorkflow(document.fields)
                }
                val moved = client.moveWorkflow(document.entry.path, "$folder/$fileName")
                renameWorkflowSnapshot(document.serverUrl, document.entry.path, moved.path)
                val manifest = bridgeOperationMutex.withLock {
                    (bridge ?: error("前端桥接不可用")).loadWorkflow(currentJson, workflowPath = moved.path)
                }
                bridgeLoadedPath = moved.path
                val updated = document.copy(
                    entry = moved,
                    rawJson = currentJson,
                    fields = manifest.fields,
                    nodes = manifest.nodes,
                    baseModified = moved.modified,
                )
                workflowDrafts.delete(document.serverUrl, document.entry.path)
                if (updated.hasUnsavedChanges) persistDraftSnapshot(draftSnapshot(updated, manifest.fields))
                _state.update {
                    it.copy(
                        previewWorkflow = updated,
                        selectedWorkflow = it.selectedWorkflow?.takeIf { sel -> sel.entry.path == document.entry.path }?.let { sel ->
                            sel.copy(
                                entry = moved,
                                rawJson = currentJson,
                                fields = manifest.fields,
                                nodes = manifest.nodes,
                                baseModified = moved.modified,
                            )
                        } ?: it.selectedWorkflow,
                        fields = if (it.selectedWorkflow?.entry?.path == document.entry.path) manifest.fields else it.fields,
                        notice = "已改名为 $fileName",
                    )
                }
                preferences.setRecentWorkflow(moved.path, replacedPath = document.entry.path)
                refreshWorkflowsInternal()
            }
        }
    }

    fun moveWorkflow(folder: String) {
        val document = _state.value.previewWorkflow ?: return
        viewModelScope.launch {
            runOperation("移动工作流失败") {
                flushCurrentDraft()
                val destination = "${WorkflowPath.folder(folder)}/${document.entry.name}"
                val currentJson = bridgeOperationMutex.withLock {
                    (bridge ?: error("前端桥接不可用")).syncWorkflow(document.fields)
                }
                val moved = client.moveWorkflow(document.entry.path, destination)
                renameWorkflowSnapshot(document.serverUrl, document.entry.path, moved.path)
                val manifest = bridgeOperationMutex.withLock {
                    (bridge ?: error("前端桥接不可用")).loadWorkflow(currentJson, workflowPath = moved.path)
                }
                bridgeLoadedPath = moved.path
                val updated = document.copy(
                    entry = moved,
                    rawJson = currentJson,
                    fields = manifest.fields,
                    nodes = manifest.nodes,
                    baseModified = moved.modified,
                )
                workflowDrafts.delete(document.serverUrl, document.entry.path)
                if (updated.hasUnsavedChanges) persistDraftSnapshot(draftSnapshot(updated, manifest.fields))
                _state.update {
                    it.copy(
                        previewWorkflow = updated,
                        selectedWorkflow = it.selectedWorkflow?.takeIf { sel -> sel.entry.path == document.entry.path }?.let { sel ->
                            sel.copy(
                                entry = moved,
                                rawJson = currentJson,
                                fields = manifest.fields,
                                nodes = manifest.nodes,
                                baseModified = moved.modified,
                            )
                        } ?: it.selectedWorkflow,
                        fields = if (it.selectedWorkflow?.entry?.path == document.entry.path) manifest.fields else it.fields,
                        notice = "已移动到 ${WorkflowPath.folder(folder)}",
                    )
                }
                preferences.setRecentWorkflow(moved.path, replacedPath = document.entry.path)
                refreshWorkflowsInternal()
            }
        }
    }

    fun deleteWorkflow() {
        val document = _state.value.previewWorkflow ?: return
        viewModelScope.launch {
            runOperation("删除工作流失败") {
                flushCurrentDraft()
                client.deleteWorkflow(document.entry.path)
                workflowDrafts.delete(document.serverUrl, document.entry.path)
                runCatching { workflowSnapshots.remove(document.serverUrl, document.entry.path) }
                preferences.removeRecentWorkflow(document.entry.path)
                _state.update {
                    it.copy(
                        previewWorkflow = null,
                        selectedWorkflow = it.selectedWorkflow?.takeUnless { sel -> sel.entry.path == document.entry.path },
                        fields = if (it.selectedWorkflow?.entry?.path == document.entry.path) emptyList() else it.fields,
                        notice = "已删除 ${document.entry.name}",
                    )
                }
                refreshWorkflowsInternal()
            }
        }
    }

    /**
     * 按路径删除工作流：不依赖是否已成功打开/识别。
     * 用于清理"导入后打不开"（如 API 格式转换失败）的残留工作流。
     */
    fun deleteWorkflowByPath(path: String, name: String) {
        val serverUrl = _state.value.activeServer?.baseUrl ?: return
        viewModelScope.launch {
            runOperation("删除工作流失败") {
                flushCurrentDraft()
                client.deleteWorkflow(path)
                runCatching { workflowDrafts.delete(serverUrl, path) }
                runCatching { workflowSnapshots.remove(serverUrl, path) }
                preferences.removeRecentWorkflow(path)
                _state.update {
                    it.copy(
                        previewWorkflow = it.previewWorkflow?.takeUnless { wf -> wf.entry.path == path },
                        selectedWorkflow = it.selectedWorkflow?.takeUnless { sel -> sel.entry.path == path },
                        fields = if (it.selectedWorkflow?.entry?.path == path) emptyList() else it.fields,
                        notice = "已删除 $name",
                    )
                }
                refreshWorkflowsInternal()
            }
        }
    }

    fun importWorkflow(uri: Uri, filename: String, mimeType: String?) {
        viewModelScope.launch {
            runOperation("导入工作流失败") {
                _state.update { it.copy(loading = true, error = null) }
                val extension = filename.substringAfterLast('.', "").lowercase()
                val isImage = mimeType.orEmpty().startsWith("image/") || extension in setOf("png", "webp", "avif")
                val raw = if (isImage) {
                    if (extension == "png" || mimeType.equals("image/png", ignoreCase = true)) {
                        withContext(Dispatchers.IO) {
                            app.contentResolver.openInputStream(uri)?.use { WorkflowImageReader.readPngWorkflow(it) }
                                ?: error("无法读取所选图片")
                        }
                    } else {
                        (bridge ?: error("前端桥接不可用")).extractWorkflowFromImage(uri, mimeType, filename)
                    }
                } else {
                    withContext(Dispatchers.IO) {
                        app.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
                            ?: error("无法读取所选文件")
                    }
                }
                val json = JSONObject(raw)
                require(WorkflowFormat.isCanvas(json) || WorkflowFormat.isApiPrompt(json)) {
                    "不是可识别的 ComfyUI 工作流（需要画布格式或 API 格式）"
                }
                val sourceName = filename.substringAfterLast('/').substringAfterLast('\\')
                val targetName = if (isImage) sourceName.substringBeforeLast('.', sourceName) else sourceName
                val safeName = WorkflowPath.fileName(targetName)
                // 服务器支持云端工作流管理时，先检查重名并写入服务器；
                // 不支持（AI Studio 等返回 404/400）时降级为仅本地加载，不中断导入。
                val baseName = safeName.substringBeforeLast(".json", safeName)
                var candidateName = safeName
                val entry = if (bridge?.serverWorkflowStoreAvailable == true) {
                    // 服务器支持云端工作流管理：先检查重名再写入服务器。
                    try {
                        val existingPaths = client.listWorkflows().mapTo(mutableSetOf()) { it.path }
                        var copyNumber = 2
                        while ("workflows/$candidateName" in existingPaths) {
                            candidateName = "$baseName-$copyNumber.json"
                            copyNumber += 1
                        }
                        client.writeWorkflow("workflows/$candidateName", json.toString(), overwrite = false)
                    } catch (error: IllegalStateException) {
                        if (!isUserdataUnavailable(error)) throw error
                        AppLogger.warn("服务器不支持云端工作流，导入降级为仅本地加载", error)
                        localOnlyEntry(candidateName, json)
                    }
                } else {
                    // v0.1.72：已知这台服务器不开放 /userdata（AI Studio 等反向代理）。
                    // 以前这里也会先白跑一遍 listWorkflows + writeWorkflow——在代理上这
                    // 两发请求是必败的，中文文件名还会额外打一条 400 到日志，然后才降级。
                    // 现在直接本地化，不浪费那两发注定失败的请求。
                    AppLogger.info("此服务器不提供云端工作流存储，导入直接保存在本机")
                    localOnlyEntry(candidateName, json)
                }
                // 内存 + 磁盘各存一份：AI Studio 上服务器那份永远读不回来，
                // 只放内存的话杀掉 App 再进就又打不开了。
                cacheWorkflowContent(
                    _state.value.activeServer?.baseUrl.orEmpty(),
                    entry.path,
                    json.toString(),
                )
                runCatching { refreshWorkflowsInternal() }
                val manifest = bridgeOperationMutex.withLock {
                    (bridge ?: error("前端桥接不可用")).loadWorkflow(
                        rawJson = json.toString(),
                        workflowPath = entry.path,
                    )
                }
                bridgeLoadedPath = entry.path
                val document = WorkflowDocument(
                    entry = entry,
                    rawJson = json.toString(),
                    fields = manifest.fields,
                    nodes = manifest.nodes,
                    serverUrl = _state.value.activeServer?.baseUrl.orEmpty(),
                    baseModified = entry.modified,
                )
                _state.update {
                    it.copy(
                        previewWorkflow = document,
                        selectedWorkflow = document,
                        fields = manifest.fields,
                        loading = false,
                        nodeProblems = emptyMap(),
                        notice = "已从${if (isImage) "图片" else "文件"}导入 $candidateName",
                    )
                }
            }
        }
    }

    fun currentWorkflowExport(): Pair<String, String>? = _state.value.previewWorkflow?.let { it.entry.name to it.rawJson }

    fun refreshTasks() = viewModelScope.launch { refreshTasksInternal() }
    fun refreshResults() = viewModelScope.launch { refreshResultsInternal() }
    fun refreshLocalResults() = viewModelScope.launch {
        val local = localResultCache.load()
        _state.update { it.copy(localResults = local) }
    }

    fun onLocalResultsSaved(count: Int, failed: Boolean, localSaveRequested: Boolean) = viewModelScope.launch {
        val local = localResultCache.load()
        // 自动保存：只处理"最近一次任务"新增的结果（按 createdAt 识别）。
        // 按用户偏好写入"图片保存位置"（自定义文件夹）；未设置自定义文件夹时
        // 不写入系统相册（避免相册被生成图刷屏），仅保留在本地作品缓存里。
        var autoSaved = 0
        var autoSkipped = false
        val saveFolderUri = _state.value.saveFolderUri
        if (_state.value.autoSaveResults && local.isNotEmpty()) {
            if (saveFolderUri.isNullOrBlank()) {
                autoSkipped = true
            } else {
                val latestCreatedAt = local.maxOf { it.createdAt }
                local.filter { it.source == ResultSource.LOCAL && it.createdAt == latestCreatedAt }
                    .forEach { media ->
                        runCatching { saveToMediaStore(media) }
                            .onSuccess { autoSaved += 1 }
                    }
            }
        }
        val autoSavedNote = when {
            autoSaved > 0 -> "，已自动保存 $autoSaved 张到图片文件夹"
            autoSkipped -> "，未设置图片保存位置，已跳过自动保存（仅保留在本地作品）"
            else -> ""
        }
        _state.update {
            it.copy(
                localResults = local,
                generationMessage = when {
                    localSaveRequested && failed -> "生成完成，但本地作品保存失败"
                    localSaveRequested -> "本地保存完成，共 $count 项$autoSavedNote"
                    else -> "生成完成$autoSavedNote"
                },
                notice = when {
                    localSaveRequested && failed -> "本地作品保存失败，可保持连接后重试"
                    localSaveRequested -> "本地保存完成，共 $count 项$autoSavedNote"
                    else -> "生成完成$autoSavedNote"
                },
            )
        }
    }

    fun cancelJob(job: JobSummary) {
        viewModelScope.launch {
            runOperation("取消任务失败") {
                client.cancel(job)
                takenOverJobIds.remove(job.id)
                stopMonitor(job.id)
                if (_state.value.activeJobId == job.id) {
                    _state.update {
                        it.copy(
                            activeJobId = null,
                            currentExecutingNodeId = null,
                            generationProgress = null,
                            generationMessage = "任务已取消",
                            notice = "任务已取消：${job.id.take(8)}",
                        )
                    }
                }
                delay(250)
                refreshTasksInternal()
            }
        }
    }

    fun clearPendingJobs() {
        viewModelScope.launch {
            runOperation("清空队列失败") {
                client.clearPending()
                refreshTasksInternal()
            }
        }
    }

    fun setAutoSaveResults(enabled: Boolean) {
        viewModelScope.launch {
            preferences.setAutoSaveResults(enabled)
            _state.update { it.copy(autoSaveResults = enabled) }
        }
    }

    fun setLoggingEnabled(enabled: Boolean) {
        AppLogger.setEnabled(app, enabled)
        _state.update {
            it.copy(
                loggingEnabled = enabled,
                notice = if (enabled) "诊断日志已开启" else "诊断日志已关闭",
            )
        }
    }

    fun diagnosticLog(): String = AppLogger.read()

    fun clearDiagnosticLog() {
        AppLogger.clear()
        _state.update { it.copy(notice = "诊断日志已清空") }
    }

    fun reportDiagnosticLogExport(success: Boolean) {
        _state.update {
            if (success) it.copy(notice = "诊断日志已导出")
            else it.copy(error = "诊断日志导出失败")
        }
    }

    /**
     * 任务页点运行中的任务：接管该任务（无论是否本 App 提交），并加载它对应
     * 的工作流，让参数页跟着这个任务走。
     */
    fun takeoverJob(job: JobSummary) {
        if (job.state !in setOf(JobState.RUNNING, JobState.PENDING)) return
        if (_state.value.activeJobId == job.id && _state.value.selectedWorkflow?.entry?.path == job.workflowPath) {
            _state.update { it.copy(notice = "正在跟踪任务：${job.id.take(8)}") }
            return
        }
        takenOverJobIds.add(job.id)
        _state.update {
            it.copy(
                activeJobId = job.id,
                currentExecutingNodeId = ExecutionNodeResolver.resolve(
                    job.currentNode,
                    it.selectedWorkflow?.nodes.orEmpty(),
                ),
                generationProgress = job.progress,
                generationMessage = if (job.state == JobState.PENDING) {
                    "已经加入队列，等待服务器执行"
                } else {
                    "已接管任务：${job.id.take(8)}"
                },
                notice = "已接管任务：${job.id.take(8)}",
            )
        }
        val path = job.workflowPath.takeIf { it.isNotBlank() }
        if (path != null) {
            val entry = _state.value.workflows.firstOrNull { !it.isDirectory && it.path == path }
                ?: WorkflowEntry(
                    name = path.substringAfterLast('/'),
                    path = path,
                    isDirectory = false,
                )
            selectWorkflow(entry, recordAsOpened = true)
        } else {
            // 电脑浏览器等提交的任务没有 comfy_mobile.workflow_path，但任务里
            // 内嵌了执行时的工作流图，直接用它打开参数页。
            val workflowJson = job.workflowJson?.takeIf { it.isNotBlank() }
            if (workflowJson != null) loadTaskEmbeddedWorkflow(workflowJson, job)
        }
        _state.update {
            it.copy(
                navigationRequest = AppNavigationRequest(
                    id = SystemClock.elapsedRealtimeNanos(),
                    destination = AppDestination.PARAMETERS,
                ),
            )
        }
    }

    private fun loadTaskEmbeddedWorkflow(workflowJson: String, job: JobSummary) {
        viewModelScope.launch {
            runOperation("加载任务工作流失败") {
                val serverUrl = _state.value.activeServer?.baseUrl ?: error("尚未连接 ComfyUI 服务器")
                val manifest = bridgeOperationMutex.withLock {
                    (bridge ?: error("前端桥接不可用")).loadWorkflow(
                        rawJson = workflowJson,
                        workflowPath = null,
                    )
                }
                bridgeLoadedPath = null
                val name = job.workflowName.ifBlank { "任务快照-${job.id.take(8)}" }
                val entry = WorkflowEntry(
                    name = name,
                    path = "workflows/$name.json",
                    isDirectory = false,
                )
                val document = WorkflowDocument(
                    entry = entry,
                    rawJson = workflowJson,
                    fields = manifest.fields,
                    nodes = manifest.nodes,
                    serverUrl = serverUrl,
                    baseModified = 0.0,
                    hasUnsavedChanges = false,
                )
                _state.update {
                    it.copy(
                        previewWorkflow = document,
                        selectedWorkflow = document,
                        fields = manifest.fields,
                        loading = false,
                        nodeProblems = emptyMap(),
                        notice = "已加载任务对应工作流（任务内嵌快照）",
                    )
                }
            }
        }
    }

    fun refreshLocalDraftCount() {
        viewModelScope.launch {
            val count = if (_state.value.localDraftsEnabled) {
                runCatching { workflowDrafts.count() }.getOrDefault(0)
            } else {
                0
            }
            _state.update { it.copy(localDraftCount = count) }
        }
    }

    fun setLocalDraftsEnabled(enabled: Boolean) {
        viewModelScope.launch {
            preferences.setLocalDraftsEnabled(enabled)
            cancelPendingDraftSave()
            if (enabled) {
                _state.update {
                    it.copy(
                        localDraftsEnabled = true,
                        notice = "已开启本地草稿，未保存修改会自动恢复",
                    )
                }
                refreshLocalDraftCount()
            } else {
                runCatching { workflowDrafts.clearAll() }
                _state.update { it.copy(localDraftsEnabled = false) }
                val document = _state.value.selectedWorkflow
                if (document != null && document.hasUnsavedChanges && document.serverUrl.isNotBlank()) {
                    discardLocalWorkflowDraft()
                } else {
                    _state.update {
                        it.copy(
                            localDraftCount = 0,
                            selectedWorkflow = it.selectedWorkflow?.copy(hasUnsavedChanges = false),
                            workflowDraftConflictRequired = false,
                            workflowDraftConflictReason = "",
                            notice = "已关闭本地草稿，之后打开工作流将直接读取服务器版本",
                        )
                    }
                }
            }
        }
    }

    fun clearAllWorkflowDrafts() {
        viewModelScope.launch {
            runOperation("清除本地草稿失败") {
                cancelPendingDraftSave()
                val removed = workflowDrafts.clearAll()
                _state.update {
                    it.copy(
                        localDraftCount = 0,
                        notice = "已清除 $removed 个本地工作流草稿",
                    )
                }
            }
        }
    }

    fun removePromptHistory(value: String) {
        viewModelScope.launch {
            val updated = _state.value.promptHistory.filterNot { it == value }
            preferences.savePromptHistory(updated)
            _state.update { it.copy(promptHistory = updated) }
        }
    }

    fun clearPromptHistory() {
        viewModelScope.launch {
            preferences.savePromptHistory(emptyList())
            _state.update { it.copy(promptHistory = emptyList()) }
        }
    }

    fun toggleCacheOutput(node: WorkflowNode) {
        if (!node.isOutput) return
        viewModelScope.launch {
            val serverUrl = client.serverUrl()
            val current = _state.value.cacheOutputRules
            val existing = current.firstOrNull {
                it.serverUrl == serverUrl && it.nodeType == node.type
            }
            val updated = if (existing == null) {
                current.filterNot { it.serverUrl == serverUrl && it.nodeType == node.type } + CacheOutputRule(
                    serverUrl = serverUrl,
                    nodeTitle = node.title,
                    nodeType = node.type,
                )
            } else {
                current - existing
            }
            preferences.saveCacheOutputRules(updated)
            _state.update {
                it.copy(
                    cacheOutputRules = updated,
                    notice = if (existing == null) {
                        "已将 ${node.title} 加入全工作流保存白名单"
                    } else {
                        "已将 ${node.title} 移出全工作流保存白名单"
                    },
                )
            }
        }
    }

    fun setCacheRuleEnabled(rule: CacheOutputRule, enabled: Boolean) {
        viewModelScope.launch {
            val updated = _state.value.cacheOutputRules.map { if (it == rule) it.copy(enabled = enabled) else it }
            preferences.saveCacheOutputRules(updated)
            _state.update { it.copy(cacheOutputRules = updated) }
        }
    }

    fun removeCacheRule(rule: CacheOutputRule) {
        viewModelScope.launch {
            val updated = _state.value.cacheOutputRules - rule
            preferences.saveCacheOutputRules(updated)
            _state.update { it.copy(cacheOutputRules = updated) }
        }
    }

    fun clearLocalCache() {
        viewModelScope.launch {
            runOperation("删除本地作品失败") {
                val clearedAt = System.currentTimeMillis()
                preferences.setCacheClearedAt(clearedAt)
                localResultCache.clear()
                _state.update {
                    it.copy(
                        localResults = emptyList(),
                        cacheClearedAt = clearedAt,
                        notice = "本地作品已删除，旧任务不会重新下载",
                    )
                }
            }
        }
    }

    fun saveResult(media: ResultMedia) {
        saveResults(listOf(media))
    }

    fun saveResultWithFeedback(media: ResultMedia, onComplete: (String) -> Unit) {
        viewModelScope.launch {
            val verb = if (media.source == com.local.comfyuimobile.model.ResultSource.CLOUD) "下载" else "保存"
            runCatching { saveToMediaStore(media) }
                .onSuccess {
                    val message = "${verb}完成"
                    _state.update { it.copy(notice = "$message：${media.filename}") }
                    onComplete(message)
                }
                .onFailure { error ->
                    val message = "${verb}失败：${error.message ?: error.javaClass.simpleName}"
                    _state.update { it.copy(error = message) }
                    onComplete(message)
                }
        }
    }

    fun saveResults(media: Collection<ResultMedia>) {
        viewModelScope.launch {
            val items = media.distinctBy(ResultMedia::stableKey)
            val verb = if (items.any { it.source == com.local.comfyuimobile.model.ResultSource.CLOUD }) "下载" else "保存"
            runOperation("${verb}结果失败") {
                var succeeded = 0
                var failed = 0
                items.forEach { item ->
                    runCatching { saveToMediaStore(item) }
                        .onSuccess { succeeded += 1 }
                        .onFailure { failed += 1 }
                }
                _state.update {
                    it.copy(
                        notice = if (failed == 0) "已${verb} $succeeded 项" else "已${verb} $succeeded 项，$failed 项失败",
                    )
                }
            }
        }
    }

    fun deleteLocalResults(media: Collection<ResultMedia>) {
        viewModelScope.launch {
            runOperation("删除本地作品失败") {
                val deleted = localResultCache.remove(media.filter { it.source == com.local.comfyuimobile.model.ResultSource.LOCAL })
                val remaining = localResultCache.load()
                _state.update { it.copy(localResults = remaining, notice = "已删除 $deleted 项本地作品") }
            }
        }
    }

    fun toggleResultFavorite(media: ResultMedia) {
        viewModelScope.launch {
            val key = media.stableKey()
            val updated = _state.value.favoriteResultKeys.toMutableSet().apply {
                if (!add(key)) remove(key)
            }.toSet()
            preferences.saveFavoriteResultKeys(updated)
            _state.update { it.copy(favoriteResultKeys = updated) }
        }
    }

    fun shareResult(media: ResultMedia) {
        viewModelScope.launch {
            runOperation("分享结果失败") {
                val file = media.localPath?.let(::File)?.takeIf { it.isFile } ?: run {
                    val dir = File(app.cacheDir, "shared").apply { mkdirs() }
                    File(dir, media.filename).also { client.downloadTo(media.url, it.outputStream()) }
                }
                val uri = FileProvider.getUriForFile(app, "${app.packageName}.fileprovider", file)
                val intent = Intent(Intent.ACTION_SEND).apply {
                    type = mimeType(media)
                    putExtra(Intent.EXTRA_STREAM, uri)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                app.startActivity(Intent.createChooser(intent, "分享生成结果").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }
        }
    }

    fun openResult(media: ResultMedia) {
        val localFile = media.localPath?.let(::File)?.takeIf { it.isFile }
        val uri = localFile?.let { FileProvider.getUriForFile(app, "${app.packageName}.fileprovider", it) }
            ?: Uri.parse(media.url)
        val intent = Intent(Intent.ACTION_VIEW, uri).apply {
            setDataAndType(uri, mimeType(media))
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        runCatching { app.startActivity(intent) }
            .onFailure { _state.update { state -> state.copy(error = "无法打开原文件：${it.message}") } }
    }

    fun checkUpdate(manual: Boolean = true) {
        val now = System.currentTimeMillis()
        if (!manual && now - lastUpdateCheck < 24 * 60 * 60 * 1_000L) return
        viewModelScope.launch {
            runOperation("检查更新失败") {
                val info = updates.checkLatest()
                preferences.setLastUpdateCheck(now)
                lastUpdateCheck = now
                _state.update {
                    it.copy(
                        updateInfo = info,
                        notice = if (info == null && manual) "已是最新版本" else if (info != null) "发现新版本 ${info.tag}" else it.notice,
                    )
                }
            }
        }
    }

    fun downloadUpdate() {
        val info = _state.value.updateInfo ?: return
        viewModelScope.launch {
            runOperation("更新下载失败") {
                val result = updates.enqueue(info)
                _state.update {
                    it.copy(
                        updateDownloading = true,
                        updateDownloadSource = result.source,
                        updateDownloadProgress = 0f,
                        notice = "已通过${result.source}（${result.latencyMillis}ms）开始下载",
                    )
                }
                while (true) {
                    delay(800)
                    val progress = updates.downloadState(result.downloadId) ?: break
                    when (progress.status) {
                        UpdateDownloadStatus.SUCCESSFUL -> {
                            _state.update {
                                it.copy(
                                    updateDownloading = false,
                                    updateDownloadProgress = 1f,
                                    notice = "下载完成，正在校验并安装",
                                )
                            }
                            break
                        }
                        UpdateDownloadStatus.FAILED -> {
                            _state.update {
                                it.copy(
                                    updateDownloading = false,
                                    updateDownloadProgress = null,
                                    error = "更新下载失败，请稍后重试",
                                )
                            }
                            break
                        }
                        UpdateDownloadStatus.DOWNLOADING -> {
                            _state.update { it.copy(updateDownloadProgress = progress.fraction) }
                        }
                    }
                }
            }
        }
    }

    private fun openSocket() {
        client.openWebSocket(
            clientId = clientId,
            onOpen = {
                reconnectJob?.cancel()
                _state.update { it.copy(status = ConnectionStatus.CONNECTED, connectionMessage = "已连接 ${it.activeServer?.name.orEmpty()}") }
            },
            onMessage = ::handleSocketMessage,
            onFailure = { scheduleReconnect() },
        )
    }

    private fun scheduleReconnect() {
        if (_state.value.activeServer == null || reconnectJob?.isActive == true) return
        reconnectJob = viewModelScope.launch {
            _state.update {
                it.copy(
                    status = ConnectionStatus.RECONNECTING,
                    connectionMessage = "连接中断，正在重连",
                    bridgeReady = false,
                )
            }
            for (seconds in listOf(1L, 2L, 5L, 10L, 30L)) {
                delay(seconds * 1_000)
                // v0.1.81：这里以前是 `return@launch`——用户主动断开时 activeServer
                // 变 null，重连任务直接退出，状态却永远停在 RECONNECTING /
                // bridgeReady=false（因为下面那句"服务器离线"被跳过了），界面就卡在
                // "连接中断，正在重连"。改成 break 走统一的收尾，再按"还有没有服务器"
                // 决定该报离线还是保持已断开。
                val server = _state.value.activeServer ?: break
                if (!isActive) return@launch
                // 重连时恢复该服务器保存的认证 Cookie。
                client.setAuthCookie(server.cookie)
                bridge?.setAuthCookie(server.cookie)
                AuthCookieProvider.current = server.cookie
                val stats = runCatching { client.systemStats() }.getOrNull() ?: continue
                val restored = runCatching {
                    val activeBridge = bridge ?: error("前端桥接不可用")
                    // v0.1.78：锁里只留纯前端操作（loadServer / awaitReady），
                    // 工作流列表和正文的 HTTP 请求挪到锁外——它们是秒级耗时，
                    // 压在互斥锁里会把用户随后的所有操作一起堵住。
                    bridgeOperationMutex.withLock {
                        activeBridge.loadServer(server.baseUrl, timeoutMillis = 20_000L)
                        activeBridge.awaitReady(timeoutMillis = 45_000L)
                    }
                    restoreWorkingCopyAfterReconnect(activeBridge, server.baseUrl)
                }.onFailure { error ->
                    if (error !is CancellationException) AppLogger.error("重连后恢复本地工作副本失败", error)
                }.isSuccess
                if (!restored) continue
                _state.update {
                    it.copy(
                        status = ConnectionStatus.CONNECTED,
                        connectionMessage = "已重新连接并恢复工作副本",
                        systemStats = stats,
                        bridgeReady = true,
                    )
                }
                openSocket()
                refreshAll()
                return@launch
            }
            // v0.1.81：能走到这里有两种情况——重连轮次用尽（真的离线），或者
            // 期间 activeServer 被清空（用户主动断开 / 换了服务器）。后者不该
            // 报"服务器离线"，否则界面上会出现一个明明已断开却在喊重连的僵尸状态。
            if (_state.value.activeServer != null) {
                _state.update { it.copy(status = ConnectionStatus.ERROR, connectionMessage = "服务器离线") }
            }
        }
    }

    private fun handleSocketMessage(message: JSONObject) {
        val type = message.optString("type")
        val data = message.optJSONObject("data") ?: JSONObject()
        when (type) {
            "execution_start" -> {
                val id = data.optString("prompt_id")
                if (id.isNotBlank()) {
                    markJobObserved(id)
                    pendingReconnectNodeId = null
                    updateJob(id) { it.copy(state = JobState.RUNNING, progress = 0f, currentNode = null) }
                    if (tracksVisibleJob(id)) {
                        visibleNodeJob?.cancel()
                        visibleNodeChangedAt = 0L
                        _state.update {
                            it.copy(
                                activeJobId = id,
                                currentExecutingNodeId = null,
                                // v0.1.76：执行开始阶段（加载模型/CLIP/VAE）没有进度消息，
                                // 置 null 让 UI 显示不确定进度条，不再停在 0% 假装卡住。
                                generationProgress = null,
                                generationMessage = "服务器已经开始生成，正在加载模型",
                            )
                        }
                    }
                }
            }
            "status" -> {
                val remaining = data.optJSONObject("status")?.optJSONObject("exec_info")?.optInt("queue_remaining") ?: 0
                _state.update { it.copy(queueRemaining = remaining) }
                viewModelScope.launch { refreshTasksInternal() }
            }
            "progress" -> {
                val id = data.optString("prompt_id")
                val value = data.optDouble("value")
                val max = data.optDouble("max")
                if (id.isNotBlank() && max > 0) {
                    markJobObserved(id)
                    val progress = (value / max).toFloat()
                    updateJob(id) { it.copy(progress = progress) }
                    updateMonitor(id, (progress * 100).toInt(), null)
                    if (tracksVisibleJob(id)) {
                        _state.update {
                            it.copy(
                                activeJobId = id,
                                generationProgress = progress,
                                generationMessage = "正在生成 ${(progress * 100).toInt()}%",
                            )
                        }
                    }
                }
            }
            "progress_state" -> {
                ProgressStateParser.parse(data)?.let { update ->
                    markJobObserved(update.promptId)
                    val nodeId = resolveVisibleNode(update.nodeId)
                    updateJob(update.promptId) { it.copy(progress = update.progress, currentNode = nodeId, state = JobState.RUNNING) }
                    updateMonitor(update.promptId, (update.progress * 100).toInt(), nodeId)
                    if (tracksVisibleJob(update.promptId)) {
                        showVisibleExecutingNode(update.promptId, nodeId, update.progress)
                    }
                }
            }
            "executing" -> {
                val eventPromptId = data.optTextOrEmpty("prompt_id")
                val runtimeNode = data.optTextOrEmpty("display_node_id")
                    .ifBlank { data.optTextOrEmpty("display_node") }
                    .ifBlank { data.optTextOrEmpty("node_id") }
                    .ifBlank { data.optTextOrEmpty("node") }
                val id = ActiveJobRecovery.resolveEventPromptId(
                    eventPromptId = eventPromptId,
                    activeJobId = _state.value.activeJobId,
                    jobs = _state.value.jobs,
                ).orEmpty()
                if (id.isBlank() && runtimeNode.isNotBlank()) {
                    // ComfyUI 重连补发当前节点时不带 prompt_id；等 /queue 恢复任务 ID 后再应用。
                    pendingReconnectNodeId = runtimeNode
                    AppLogger.info("已收到重连当前部件，等待关联运行任务：部件=$runtimeNode")
                    return
                }
                val node = resolveVisibleNode(runtimeNode).orEmpty()
                if (id.isNotBlank()) {
                    markJobObserved(id)
                    pendingReconnectNodeId = null
                    val previousState = _state.value.jobs.firstOrNull { it.id == id }?.state
                    val wasFailed = previousState in setOf(JobState.ERROR, JobState.CANCELLED)
                    updateJob(id) {
                        when {
                            node.isNotBlank() -> it.copy(currentNode = node, state = JobState.RUNNING)
                            wasFailed -> it.copy(currentNode = null)
                            else -> it.copy(currentNode = null, state = JobState.SUCCESS, progress = 1f)
                        }
                    }
                    if (node.isNotBlank()) updateMonitor(id, -1, node) else updateMonitor(id, 100, null)
                    if (tracksVisibleJob(id)) {
                        if (node.isBlank()) {
                            if (!wasFailed) {
                                finishVisibleExecution(id)
                            }
                        } else {
                            showVisibleExecutingNode(id, node)
                        }
                    }
                    if (node.isBlank()) {
                        // ComfyUI 在最终 node=null 之前才把历史写入磁盘；稍后刷新才能取得完整输出。
                        viewModelScope.launch {
                            delay(250)
                            refreshTasksInternal()
                            refreshResultsInternal()
                        }
                    }
                }
            }
            "executed" -> Unit
            "execution_success" -> {
                val id = data.optTextOrEmpty("prompt_id")
                if (id.isNotBlank()) {
                    markJobObserved(id)
                    completedAt[id] = System.currentTimeMillis()
                    updateJob(id) { it.copy(state = JobState.SUCCESS, progress = 1f, currentNode = null) }
                    if (tracksVisibleJob(id)) {
                        finishVisibleExecution(id)
                    }
                }
                // 某些版本的 execution_success 早于历史落盘，保留延迟刷新作为兼容兜底。
                viewModelScope.launch {
                    delay(250)
                    refreshTasksInternal()
                    refreshResultsInternal()
                }
            }
            "execution_error", "execution_interrupted" -> {
                val id = data.optTextOrEmpty("prompt_id")
                val nodeId = resolveVisibleNode(data.optTextOrEmpty("node_id")).orEmpty()
                val detail = data.optTextOrEmpty("exception_message").ifBlank { if (type == "execution_interrupted") "任务已中断" else "服务器执行失败" }
                if (id.isNotBlank()) {
                    markJobObserved(id)
                    completedAt[id] = System.currentTimeMillis()
                    updateJob(id) { it.copy(state = if (type == "execution_interrupted") JobState.CANCELLED else JobState.ERROR, currentNode = nodeId.ifBlank { null }, message = detail) }
                    if (tracksVisibleJob(id)) {
                        _state.update {
                            it.copy(
                                activeJobId = id,
                                currentExecutingNodeId = nodeId.ifBlank { null },
                                generationProgress = null,
                                generationMessage = "生成失败：$detail",
                                error = "生成失败：$detail",
                            )
                        }
                    }
                }
                viewModelScope.launch { refreshTasksInternal(); refreshResultsInternal() }
            }
        }
    }

    private suspend fun refreshStatsInternal() {
        runCatching { client.systemStats() }.onSuccess { stats ->
            val updatedProfile = _state.value.activeServer?.copy(lastSeen = System.currentTimeMillis(), comfyVersion = stats.comfyVersion)
            _state.update { it.copy(systemStats = stats, activeServer = updatedProfile ?: it.activeServer) }
            if (updatedProfile != null) preferences.saveServer(updatedProfile)
        }
    }

    private suspend fun refreshWorkflowsInternal() {
        // v0.1.67：AI Studio 等反向代理服务器没有开放 /userdata 接口，listWorkflows 会 404。
        // 失败时不重置 workflows 列表，保留旧工作流展示给用户；如果旧列表为空，再用
        // RecentWorkflows 按当前 serverKey 恢复最近浏览过的路径作为兜底。
        runCatching { client.listWorkflows() }.onSuccess { entries ->
            // v0.1.77：AI Studio 网关对 /userdata 偶发"200 空列表"假阳性——之前一成功
            // 就把前端开关置 true，前端就去服务器按路径加载（中文路径 400，高级编辑
            // 退出直接卡死）。改成连续两次成功才置 true：直连服务器稳定 200 两次无感
            // 恢复；AI Studio 200/404 交替时永远到不了 2 次，开关保持 false 走内容直载。
            serverStoreSuccessStreak += 1
            if (serverStoreSuccessStreak >= 2) {
                bridge?.serverWorkflowStoreAvailable = true
            }
            _state.update { ui ->
                val document = ui.selectedWorkflow
                val current = document?.let { selected -> entries.firstOrNull { it.path == selected.entry.path } }
                val conflict = document?.hasUnsavedChanges == true &&
                    (current == null || WorkflowPolicy.hasModifiedConflict(document.baseModified, current.modified))
                ui.copy(
                    workflows = entries,
                    selectedWorkflow = if (document != null && current != null) document.copy(entry = current) else document,
                    workflowDraftConflictRequired = ui.workflowDraftConflictRequired || conflict,
                    workflowDraftConflictReason = when {
                        ui.workflowDraftConflictRequired -> ui.workflowDraftConflictReason
                        conflict && current == null -> "手机草稿仍然保留，但服务器上的原工作流已经不存在。请另存为新工作流。"
                        conflict -> "手机草稿仍然保留，但服务器版本已经变化。请选择继续手机草稿、读取服务器版本，或者另存。"
                        else -> ui.workflowDraftConflictReason
                    },
                )
            }
        }.onFailure { error ->
            // v0.1.67：失败时保留旧列表，再尝试按最近浏览的路径构造占位条目，避免用户
            // 看到空白列表。同时给 UI 一个柔和的 notice，提示云端工作流不可用。
            // v0.1.68：这种失败每 11 秒就来一次（AI Studio 实测 17 分钟 56 次），
            // 只在第一次记日志，后面静默跳过，不然诊断日志还是会被同一句话占满。
            // v0.1.74：无论什么原因列表读不到，一律把前端开关置 false，不再只认
            // "unsupported" 标记。百度 AI Studio 的网关对 /userdata 是"半死不活"的：
            // 列表可能返回 200 空（被误判为支持）、也可能 403 空 body（旧逻辑里 403
            // 不在 unsupported 判定范围，开关就停在 true）——但不管哪种，单文件读写
            // 都是必败的（query 被网关剥掉、中文路径 400）。只要列表这次读不到，
            // 就按"不支持云端存储"处理，让前端走内容直载，绝不在加载时去读服务器文件。
            serverStoreSuccessStreak = 0
            bridge?.serverWorkflowStoreAvailable = false
            if (!userdataUnsupportedLogged) {
                AppLogger.warn("工作流刷新失败，保留本地最近浏览路径作为占位", error)
                userdataUnsupportedLogged = true
            }
            _state.update { ui ->
                val serverKey = ui.activeServer?.baseUrl.orEmpty()
                // v0.1.70：优先展示本机保存过正文的工作流——列表里的每一条都真正打得开。
                // 以前只有"最近浏览过的路径"占位，那种条目只有路径没有内容，用户点了
                // 必然报"该服务器不支持此接口"（日志里连点 15 次失败全是它），看起来就像 App 坏了。
                val snapshots = runCatching { workflowSnapshots.list(serverKey) }.getOrElse { emptyList() }
                val placeholders = RecentWorkflows.resolveEntries(ui.recentWorkflowPaths, ui.workflows)
                // 快照优先，占位里和快照重复的（同路径）去掉，避免一行出现两次。
                val entries = snapshots +
                    placeholders.filter { place -> snapshots.none { it.path == place.path } }
                when {
                    entries.isEmpty() -> ui
                    // v0.1.74：不再区分"永久不支持"和"暂时性故障"——AI Studio 的网关
                    // 对 /userdata 行为不稳定（404/403/200 空列表轮着来），探测结果
                    // 不可信，两种情况的处理方式也一致：把本机打得开的快照条目摆出来，
                    // 诚实提示云端列表读不到。恢复连接后列表能读回来时自然回到正常态。
                    else -> ui.copy(
                        workflows = entries,
                        notice = ui.notice ?: "云端工作流列表暂时读不到，正在显示保存在本机的工作流；" +
                            "新工作流请点「打开工作流文件」导入",
                    )
                }
            }
        }
    }

    private suspend fun refreshTasksInternal() {
        runCatching {
            val existing = _state.value.jobs.associateBy { it.id }
            val live = client.queue()
            val history = client.historyJobs()
            val submitted = _state.value.submittedJobIds
            (live + history).distinctBy { it.id }.map { fresh ->
                val previous = existing[fresh.id]
                fresh.copy(
                    progress = if (fresh.state in setOf(JobState.RUNNING, JobState.PENDING)) previous?.progress else fresh.progress,
                    currentNode = if (fresh.state == JobState.RUNNING) previous?.currentNode else null,
                    submittedByApp = fresh.id in submitted,
                )
            }
        }.onSuccess { fetchedJobs ->
            fetchedJobs.forEach { markJobObserved(it.id) }
            val activeAppJobs = fetchedJobs.filter {
                (it.submittedByApp || it.id in takenOverJobIds) &&
                    it.state in setOf(JobState.RUNNING, JobState.PENDING)
            }
            val awaiting = awaitingQueueJobIds.toSet()
            val reconnectRuntimeNode = pendingReconnectNodeId
            var reconnectNodeApplied = false
            _state.update { ui ->
                // 网络刷新期间 WebSocket 仍可能推进节点；再次与最新 UI 状态合并，不能倒退绿框和进度。
                val jobs = fetchedJobs.map { fresh ->
                    val live = ui.jobs.firstOrNull { it.id == fresh.id }
                    fresh.copy(
                        progress = if (fresh.state in setOf(JobState.RUNNING, JobState.PENDING)) {
                            live?.progress ?: fresh.progress
                        } else {
                            fresh.progress
                        },
                        currentNode = if (fresh.state == JobState.RUNNING) {
                            live?.currentNode ?: fresh.currentNode
                        } else {
                            null
                        },
                    )
                }
                takenOverJobIds.retainAll { id ->
                    jobs.any { it.id == id && it.state in setOf(JobState.RUNNING, JobState.PENDING) }
                }
                val selection = ActiveJobRecovery.select(ui.activeJobId, jobs, awaiting, takenOverJobIds)
                val active = selection.job ?: return@update ui.copy(jobs = jobs)
                val sameActiveJob = ui.activeJobId == active.id
                val recoveredRuntimeNode = reconnectRuntimeNode.takeIf { active.state == JobState.RUNNING }
                val resolvedNode = ExecutionNodeResolver.resolve(
                    ActiveJobRecovery.currentNodeId(recoveredRuntimeNode, active.currentNode),
                    ui.selectedWorkflow?.nodes.orEmpty(),
                ) ?: if (sameActiveJob && active.state in setOf(JobState.RUNNING, JobState.PENDING)) {
                    ui.currentExecutingNodeId
                } else {
                    null
                }
                val progress = active.progress ?: if (sameActiveJob) ui.generationProgress else null
                val updatedJobs = jobs.map { job ->
                    if (job.id == active.id && resolvedNode != null) {
                        job.copy(currentNode = resolvedNode, progress = progress)
                    } else {
                        job
                    }
                }
                if (recoveredRuntimeNode != null) reconnectNodeApplied = true
                ui.copy(
                    jobs = updatedJobs,
                    activeJobId = active.id,
                    currentExecutingNodeId = resolvedNode,
                    generationProgress = progress,
                    generationMessage = when {
                        resolvedNode != null -> executionMessage(
                            resolvedNode,
                            ui.selectedWorkflow?.nodes.orEmpty(),
                            progress,
                        )
                        sameActiveJob && ui.generationMessage.isNotBlank() -> ui.generationMessage
                        active.state == JobState.PENDING -> "已经加入队列，等待服务器执行"
                        else -> "服务器已经开始生成，等待当前部件状态"
                    },
                    notice = if (selection.isTakeover) {
                        "已继续跟踪任务：${active.id.take(8)}"
                    } else {
                        ui.notice
                    },
                )
            }
            if (reconnectNodeApplied && pendingReconnectNodeId == reconnectRuntimeNode) {
                pendingReconnectNodeId = null
            }
            activeAppJobs.forEach { job ->
                startMonitor(
                    job.id,
                    job.workflowName.ifBlank {
                        _state.value.selectedWorkflow?.entry?.name ?: "ComfyUI 工作流"
                    },
                    job.workflowPath.ifBlank {
                        _state.value.selectedWorkflow?.entry?.path.orEmpty()
                    },
                )
            }
        }
    }

    private suspend fun refreshResultsInternal() {
        runCatching {
            val history = client.history()
            ResultParser.parse(client.serverUrl(), history)
        }.onSuccess { results ->
            // v0.1.76：把本机记录的"提交→完成"总耗时合并进结果（含排队时间）。
            // 没有本机记录的（App 重启后从 history 重新加载的历史任务）保持 null。
            val enhanced = if (submittedAt.isEmpty() || completedAt.isEmpty()) results else results.map { media ->
                val submitted = submittedAt[media.jobId] ?: return@map media
                val completed = completedAt[media.jobId] ?: return@map media
                val total = completed - submitted
                if (total > 0) media.copy(totalElapsedMs = total) else media
            }
            _state.update { it.copy(results = enhanced) }
        }
    }

    private suspend fun saveToMediaStore(media: ResultMedia): Uri = withContext(Dispatchers.IO) {
        val resolver = app.contentResolver
        // 用户自定义保存目录（SAF 文档树）：优先写入所选目录，未设置才走系统相册。
        _state.value.saveFolderUri?.let { treeUri ->
            val tree = Uri.parse(treeUri)
            val doc = DocumentsContract.createDocument(resolver, tree, mimeType(media), media.filename)
                ?: error("无法在所选目录创建文件")
            try {
                val output = resolver.openOutputStream(doc) ?: error("无法写入所选目录")
                val localFile = media.localPath?.let(::File)?.takeIf { it.isFile }
                if (localFile != null) {
                    output.use { target -> localFile.inputStream().use { source -> source.copyTo(target) } }
                } else {
                    client.downloadTo(media.url, output)
                }
                return@withContext doc
            } catch (error: Throwable) {
                resolver.delete(doc, null, null)
                throw error
            }
        }
        val collection = if (media.kind == MediaKind.IMAGE) MediaStore.Images.Media.EXTERNAL_CONTENT_URI else MediaStore.Video.Media.EXTERNAL_CONTENT_URI
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, media.filename)
            put(MediaStore.MediaColumns.MIME_TYPE, mimeType(media))
            if (Build.VERSION.SDK_INT >= 29) {
                val folder = if (media.kind == MediaKind.IMAGE) Environment.DIRECTORY_PICTURES else Environment.DIRECTORY_MOVIES
                put(MediaStore.MediaColumns.RELATIVE_PATH, "$folder/ComfyUIMobile")
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
        }
        val uri = resolver.insert(collection, values) ?: error("无法创建媒体文件")
        try {
            val output = resolver.openOutputStream(uri) ?: error("无法写入媒体文件")
            val localFile = media.localPath?.let(::File)?.takeIf { it.isFile }
            if (localFile != null) {
                output.use { target -> localFile.inputStream().use { source -> source.copyTo(target) } }
            } else {
                client.downloadTo(media.url, output)
            }
            if (Build.VERSION.SDK_INT >= 29) {
                values.clear()
                values.put(MediaStore.MediaColumns.IS_PENDING, 0)
                resolver.update(uri, values, null, null)
            }
            uri
        } catch (error: Throwable) {
            resolver.delete(uri, null, null)
            throw error
        }
    }

    private fun startMonitor(promptId: String, workflowName: String, workflowPath: String) {
        if (!monitoredJobIds.add(promptId)) return
        val intent = Intent(app, JobMonitorService::class.java)
            .putExtra(JobMonitorService.EXTRA_BASE_URL, client.serverUrl())
            .putExtra(JobMonitorService.EXTRA_PROMPT_ID, promptId)
            .putExtra(JobMonitorService.EXTRA_AUTH_COOKIE, client.authCookie())
            .putExtra(JobMonitorService.EXTRA_WORKFLOW_NAME, workflowName)
            .putExtra(JobMonitorService.EXTRA_WORKFLOW_PATH, workflowPath)
        runCatching { ContextCompat.startForegroundService(app, intent) }
            .onFailure { error ->
                monitoredJobIds.remove(promptId)
                AppLogger.error("后台监控启动失败，任务=$promptId", error)
                _state.update {
                    it.copy(notice = "任务已提交，但后台监控启动失败：${error.message.orEmpty()}")
                }
            }
    }

    private fun updateMonitor(promptId: String, progress: Int, node: String?) {
        if (promptId !in _state.value.submittedJobIds) return
        val intent = Intent(app, JobMonitorService::class.java)
            .setAction(JobMonitorService.ACTION_PROGRESS)
            .putExtra(JobMonitorService.EXTRA_PROMPT_ID, promptId)
            .putExtra(JobMonitorService.EXTRA_PROGRESS, progress)
            .putExtra(JobMonitorService.EXTRA_NODE, node)
        runCatching { app.startService(intent) }
            .onFailure { AppLogger.error("后台进度通知失败，任务=$promptId", it) }
    }

    private fun stopMonitor(promptId: String) {
        monitoredJobIds.remove(promptId)
        val intent = Intent(app, JobMonitorService::class.java)
            .setAction(JobMonitorService.ACTION_STOP)
            .putExtra(JobMonitorService.EXTRA_PROMPT_ID, promptId)
        runCatching { app.startService(intent) }
            .onFailure { AppLogger.error("停止后台监控失败，任务=$promptId", it) }
    }

    private fun updateFieldLayout(key: String, transform: (ParameterField) -> ParameterField) {
        _state.update { ui ->
            val updatedFields = ui.fields.map { if (it.key == key) transform(it) else it }
            ui.copy(
                fields = updatedFields,
                selectedWorkflow = ui.selectedWorkflow?.copy(
                    fields = updatedFields,
                    hasUnsavedChanges = true,
                    dirtyFieldKeys = (ui.selectedWorkflow?.dirtyFieldKeys ?: emptySet()) + key,
                ),
            )
        }
        scheduleDraftSave()
    }

    private fun restoreNotificationWorkflow() {
        val path = pendingNotificationWorkflowPath?.takeIf(String::isNotBlank) ?: return
        if (_state.value.selectedWorkflow?.entry?.path == path) {
            pendingNotificationWorkflowPath = null
            return
        }
        val entry = _state.value.workflows.firstOrNull { !it.isDirectory && it.path == path }
            ?: WorkflowEntry(
                name = path.substringAfterLast('/'),
                path = path,
                isDirectory = false,
            )
        pendingNotificationWorkflowPath = null
        selectWorkflow(entry, recordAsOpened = true)
    }

    private fun updateRecentWorkflowState(path: String, replacedPath: String? = null) {
        _state.update { ui ->
            ui.copy(
                recentWorkflowPaths = RecentWorkflows.add(
                    current = ui.recentWorkflowPaths,
                    path = path,
                    replacedPath = replacedPath,
                ),
            )
        }
    }

    /**
     * 在生成 / 保存前，确保隐藏 WebView 当前加载的是 [selectedWorkflow]。
     * 单点工作流只会把工作流载入 WebView 做预读取（并更新 previewWorkflow），
     * 但不会把它设为参数页工作流；如果之后用户直接生成或保存，WebView 里还留着
     * 预读取的工作流，必须先把参数页工作流重新载入，否则会生成/保存错误的内容。
     * 调用方必须已经持有 bridgeOperationMutex。
     */
    private suspend fun ensureSelectedWorkflowLoaded() {
        val document = _state.value.selectedWorkflow ?: return
        if (bridgeLoadedPath == document.entry.path) return
        val activeBridge = bridge ?: error("前端桥接不可用")
        val manifest = activeBridge.loadWorkflow(
            rawJson = document.rawJson,
            workflowPath = document.entry.path,
        )
        bridgeLoadedPath = document.entry.path
        _state.update { ui ->
            if (ui.selectedWorkflow?.entry?.path != document.entry.path) {
                ui
            } else {
                // 只刷新节点结构（保证参数页绿框/连线信息正确），保留用户尚未保存的
                // 字段编辑；随后的 buildPrompt / syncWorkflow 会把当前 fields 再应用到
                // WebView 上。
                ui.copy(
                    selectedWorkflow = document.copy(nodes = manifest.nodes),
                    fields = ui.fields,
                )
            }
        }
    }

    private fun refreshParametersAfterWorkflowSwitch() {
        parameterRefreshJob?.cancel()
        parameterRefreshJob = viewModelScope.launch {
            delay(80)
            runOperation("切换工作流程失败") {
                val document = _state.value.selectedWorkflow ?: return@runOperation
                val activeBridge = bridge ?: error("前端桥接不可用")
                val snapshot = _state.value.fields
                _state.update { it.copy(loading = true, error = null) }
                val (raw, manifest) = bridgeOperationMutex.withLock {
                    val updatedRaw = activeBridge.syncWorkflow(snapshot)
                    updatedRaw to activeBridge.loadWorkflow(updatedRaw, workflowPath = document.entry.path)
                }
                bridgeLoadedPath = document.entry.path
                _state.update { ui ->
                    if (ui.selectedWorkflow?.entry?.path != document.entry.path) {
                        ui.copy(loading = false)
                    } else {
                        val activeNode = ExecutionNodeResolver.resolve(
                            ui.currentExecutingNodeId,
                            manifest.nodes,
                        )
                        ui.copy(
                            selectedWorkflow = document.copy(
                                rawJson = raw,
                                fields = manifest.fields,
                                nodes = manifest.nodes,
                                hasUnsavedChanges = true,
                            ),
                            fields = manifest.fields,
                            loading = false,
                            currentExecutingNodeId = activeNode,
                            generationMessage = if (ui.activeJobId != null && activeNode != null) {
                                executionMessage(activeNode, manifest.nodes, ui.generationProgress)
                            } else {
                                ui.generationMessage
                            },
                            notice = "已切换工作流程并刷新可设置节点",
                        )
                    }
                }
                scheduleDraftSave(immediate = true)
            }
        }
    }

    private fun updateJob(id: String, transform: (JobSummary) -> JobSummary) {
        _state.update { ui ->
            val current = ui.jobs.firstOrNull { it.id == id } ?: JobSummary(id, JobState.RUNNING, submittedByApp = id in ui.submittedJobIds)
            ui.copy(jobs = listOf(transform(current)) + ui.jobs.filterNot { it.id == id })
        }
    }

    private fun markJobObserved(promptId: String) {
        if (promptId.isNotBlank()) awaitingQueueJobIds.remove(promptId)
    }

    /** 本 App 提交的任务，或用户在任务页主动接管/正在跟踪的任务。 */
    private fun tracksVisibleJob(id: String): Boolean =
        id.isNotBlank() && (id in _state.value.submittedJobIds || id == _state.value.activeJobId)

    private fun resolveVisibleNode(runtimeNodeId: String?): String? =
        ExecutionNodeResolver.resolve(runtimeNodeId, _state.value.selectedWorkflow?.nodes.orEmpty())

    private fun executionMessage(
        nodeId: String,
        nodes: List<WorkflowNode>,
        progress: Float?,
    ): String {
        val title = nodes.firstOrNull { it.id == nodeId }?.title
        val percent = progress?.let { " · ${(it * 100).toInt()}%" }.orEmpty()
        return "正在执行：${title ?: "部件 $nodeId"}$percent"
    }

    private fun showVisibleExecutingNode(promptId: String, nodeId: String?, progress: Float? = null) {
        val resolvedNodeId = resolveVisibleNode(nodeId) ?: return
        val applyUpdate = {
            _state.update { ui ->
                if (promptId != ui.activeJobId && promptId !in ui.submittedJobIds) ui else ui.copy(
                    activeJobId = promptId,
                    currentExecutingNodeId = resolvedNodeId,
                    generationProgress = progress ?: ui.generationProgress,
                    generationMessage = executionMessage(
                        resolvedNodeId,
                        ui.selectedWorkflow?.nodes.orEmpty(),
                        progress,
                    ),
                )
            }
            visibleNodeChangedAt = SystemClock.elapsedRealtime()
        }

        val current = _state.value.currentExecutingNodeId
        if (current == null || current == resolvedNodeId) {
            visibleNodeJob?.cancel()
            if (current == resolvedNodeId) {
                _state.update { ui ->
                    ui.copy(
                        activeJobId = promptId,
                        generationProgress = progress ?: ui.generationProgress,
                        generationMessage = executionMessage(
                            resolvedNodeId,
                            ui.selectedWorkflow?.nodes.orEmpty(),
                            progress,
                        ),
                    )
                }
            } else {
                applyUpdate()
            }
            return
        }

        val waitMillis = (MIN_VISIBLE_NODE_MILLIS - (SystemClock.elapsedRealtime() - visibleNodeChangedAt)).coerceAtLeast(0L)
        visibleNodeJob?.cancel()
        visibleNodeJob = viewModelScope.launch {
            delay(waitMillis)
            if (_state.value.activeJobId == promptId) applyUpdate()
        }
    }

    private fun finishVisibleExecution(promptId: String) {
        val waitMillis = (MIN_VISIBLE_NODE_MILLIS - (SystemClock.elapsedRealtime() - visibleNodeChangedAt)).coerceAtLeast(0L)
        visibleNodeJob?.cancel()
        visibleNodeJob = viewModelScope.launch {
            delay(waitMillis)
            _state.update { ui ->
                if (ui.activeJobId != promptId) ui else ui.copy(
                    currentExecutingNodeId = null,
                    generationProgress = 1f,
                    generationMessage = "生成完成，正在后台保存本地作品",
                )
            }
        }
    }

    private fun JSONObject.optTextOrEmpty(name: String): String {
        val value = opt(name)
        return if (value == null || value === JSONObject.NULL || value.toString().equals("null", ignoreCase = true)) "" else value.toString()
    }

    fun persistCurrentWorkflowDraft() {
        scheduleDraftSave(immediate = true)
    }

    private fun scheduleDraftSave(immediate: Boolean = false) {
        val snapshot = currentDraftSnapshot() ?: return
        workflowDraftSaveJob?.cancel()
        workflowDraftSaveJob = viewModelScope.launch {
            if (!immediate) delay(DRAFT_SAVE_DEBOUNCE_MILLIS)
            runCatching { persistDraftSnapshot(snapshot) }
                .onFailure { AppLogger.error("保存本地工作流草稿失败", it) }
        }
    }

    private fun currentDraftSnapshot(): WorkflowDraft? {
        if (!_state.value.localDraftsEnabled) return null
        val ui = _state.value
        val document = ui.selectedWorkflow ?: return null
        if (!document.hasUnsavedChanges || document.serverUrl.isBlank()) return null
        return draftSnapshot(document, ui.fields)
    }

    private fun draftSnapshot(document: WorkflowDocument, fields: List<ParameterField>): WorkflowDraft {
        val dirtyKeys = document.dirtyFieldKeys
        // Delta draft: only the fields the user changed, applied on top of the
        // server workflow when restored. Never stores a whole workflow copy.
        val captured = if (dirtyKeys.isEmpty()) fields else fields.filter { it.key in dirtyKeys }
        return WorkflowDraft(
            serverUrl = document.serverUrl,
            workflowPath = document.entry.path,
            workflowName = document.entry.name,
            baseModified = document.baseModified,
            workflowJson = null,
            structural = false,
            fields = WorkflowDraftFields.capture(captured),
        )
    }

    private fun structuralDraftSnapshot(
        document: WorkflowDocument,
        workflowJson: String,
        fields: List<ParameterField>,
    ): WorkflowDraft = WorkflowDraft(
        serverUrl = document.serverUrl,
        workflowPath = document.entry.path,
        workflowName = document.entry.name,
        baseModified = document.baseModified,
        workflowJson = workflowJson,
        structural = true,
        fields = WorkflowDraftFields.capture(fields),
    )

    private fun sameNodeStructure(a: List<WorkflowNode>, b: List<WorkflowNode>): Boolean {
        val byIdA = a.associate { it.id to it.type }
        val byIdB = b.associate { it.id to it.type }
        return byIdA == byIdB
    }

    private suspend fun persistDraftSnapshot(snapshot: WorkflowDraft) {
        if (!_state.value.localDraftsEnabled) return
        workflowDrafts.save(snapshot)
        AppLogger.info("本地工作流草稿已保存：${snapshot.workflowPath}")
    }

    private suspend fun cancelPendingDraftSave() {
        workflowDraftSaveJob?.cancelAndJoin()
        workflowDraftSaveJob = null
    }

    private suspend fun flushCurrentDraft() {
        val snapshot = currentDraftSnapshot()
        cancelPendingDraftSave()
        if (snapshot != null) persistDraftSnapshot(snapshot)
    }

    private var rendererRecoveryJob: kotlinx.coroutines.Job? = null
    private var rendererRecoveryFailures = 0

    /**
     * 桥接恢复（页面重载完成或渲染进程崩溃重建后调用）。
     *
     * quick=true：页面每完成一次加载都会触发（AI Studio 平台每十几秒自动重载一轮），
     * 用短超时快速恢复，失败不重连——HTTP 是通的，等下一次页面加载完成自动重试，
     * 避免 loadServer 主动 reload 与平台重载叠加成"重载风暴"（渲染进程崩溃重建后
     * 如果再用 90 秒长超时死等，期间 bridgeReady 一直 false，三个生图按钮全灰，
     * 用户完全无法操作，这正是 v0.1.74 在 AI Studio 上的现场）。
     *
     * quick=false：渲染进程崩溃重建，用长超时完整恢复；连续失败超过阈值才回退重连。
     */
    private fun restoreBridgeAfterRendererRecreated(quick: Boolean = false) {
        if (rendererRecoveryJob?.isActive == true) return
        val server = _state.value.activeServer ?: return
        rendererRecoveryJob = viewModelScope.launch {
            _state.update {
                it.copy(
                    bridgeReady = false,
                    connectionMessage = if (quick) "ComfyUI 网页已重载，正在恢复本地工作副本" else "ComfyUI 网页已重建，正在恢复本地工作副本",
                )
            }
            // v0.1.77：AI Studio 平台每 11-12 秒自动重载一轮页面，恢复动作经常被
            // 下一次重载打断。以前失败就等下一次 onPageLoaded（可能 10 秒+），
            // 按钮灰色窗口被拉满。现在改为 40 秒窗口内每秒重试——前端初始化完成
            // 的瞬间（通常 2-5 秒）就恢复成功并保持到下一次重载，灰色窗口最短。
            // 渲染进程崩溃路径超时也从 90 秒砍到 30 秒，不再让用户干等。
            val deadline = System.currentTimeMillis() + if (quick) 40_000L else 30_000L
            var attempt = 0
            while (System.currentTimeMillis() < deadline && isActive) {
                val outcome = runCatching {
                    val activeBridge = bridge ?: error("前端桥接不可用")
                    // v0.1.78：等前端就绪要持锁（它在跑 JS 注入），但读工作流列表和
                    // 正文的 HTTP 请求必须挪到锁外，否则网络一慢，锁就被占住，
                    // 用户同一时间的生图/改参数/高级编辑全被堵在后面。
                    bridgeOperationMutex.withLock {
                        activeBridge.awaitReady(timeoutMillis = if (quick) 8_000L else 30_000L)
                    }
                    restoreWorkingCopyAfterReconnect(activeBridge, server.baseUrl)
                }
                if (outcome.isSuccess) {
                    rendererRecoveryFailures = 0
                    _state.update {
                        it.copy(
                            bridgeReady = true,
                            connectionMessage = "已恢复本地工作副本",
                        )
                    }
                    return@launch
                }
                attempt += 1
                rendererRecoveryFailures += 1
                AppLogger.error(
                    if (quick) "页面重载后恢复工作副本失败（第 $attempt 次）" else "WebView 重建后恢复工作副本失败（第 $attempt 次）",
                    outcome.exceptionOrNull(),
                )
                if (!quick && rendererRecoveryFailures >= 3) {
                    rendererRecoveryFailures = 0
                    scheduleReconnect()
                    return@launch
                }
                delay(1_000L)
            }
            if (quick) AppLogger.warn("页面重载后桥接未能在 40 秒内恢复，等待下一次页面加载重试")
        }.also { job ->
            job.invokeOnCompletion {
                if (rendererRecoveryJob === job) rendererRecoveryJob = null
            }
        }
    }

    /**
     * 重连/页面重载后把工作副本重新灌回前端。
     *
     * v0.1.78 拆成两段：**网络在锁外，桥接在锁内**。以前整段（含
     * listWorkflows / readWorkflow 两个 HTTP 请求）都压在 bridgeOperationMutex 里，
     * AI Studio 上 /userdata 卡上几秒，锁就被占死，用户这期间的生图、改参数、
     * 进高级编辑全部排队干等——这正是 v0.1.77 现场"按钮灰一段"的另一半原因。
     */
    private suspend fun restoreWorkingCopyAfterReconnect(activeBridge: ComfyBridge, serverUrl: String) {
        val lookup = resolveServerWorkflowCopy(serverUrl) ?: return
        bridgeOperationMutex.withLock {
            applyWorkingCopyAfterReconnect(activeBridge, lookup)
        }
    }

    /**
     * 服务器上的同名工作流查找结果。
     *
     * @param entry 找到的条目；null 表示服务器上没有这个工作流。
     * @param reachable false 表示**这次根本没读到列表**（网络抖动、接口被代理拦了），
     *   不等于"文件不存在"——两者的提示文案必须分开，否则 AI Studio 上 /userdata
     *   抖一下就会把用户吓一跳，以为自己在服务器上存的工作流没了。
     */
    private data class ServerWorkflowLookup(val entry: WorkflowEntry?, val reachable: Boolean)

    private suspend fun resolveServerWorkflowCopy(serverUrl: String): ServerWorkflowLookup? {
        val document = _state.value.selectedWorkflow ?: return null
        if (WorkflowDraftStore.normalizeServer(document.serverUrl) != WorkflowDraftStore.normalizeServer(serverUrl)) return null
        // v0.1.68：不支持云端工作流的服务器上它必然失败，容忍掉，退回本地内容。
        // v0.1.78：以前只容忍"平台不支持"这一类，剩下的（JSONException、超时、502、
        // 两次重试都失败的 IllegalStateException）统统往外抛，最后把整条连接判成失败
        // ——/system_stats 探测明明已经通过，说明服务器活得好好的。现在一律降级：
        // 读不到就当服务器上没有，用本机草稿继续，最多把差异提示交给下面的冲突逻辑。
        return runCatching { client.listWorkflows().firstOrNull { it.path == document.entry.path } }
            .onFailure { error -> AppLogger.warn("读取服务器工作流列表失败，改用本机草稿", error) }
            .fold(
                onSuccess = { entry -> ServerWorkflowLookup(entry, reachable = true) },
                onFailure = { ServerWorkflowLookup(null, reachable = false) },
            )
    }

    private suspend fun applyWorkingCopyAfterReconnect(activeBridge: ComfyBridge, lookup: ServerWorkflowLookup) {
        val document = _state.value.selectedWorkflow ?: return
        val current = lookup.entry
        val serverChanged = current == null || WorkflowPolicy.hasModifiedConflict(document.baseModified, current.modified)
        val needServerVersion = !document.hasUnsavedChanges && serverChanged
        // v0.1.78：读正文一样不能让流程崩。失败就当没读到，退回本机草稿——
        // 关键是 loadServerVersion 要跟着变 false，否则会把本机草稿当成服务器
        // 最新内容记账，用户的"未保存改动"就被悄悄抹掉了。
        val serverRaw = if (needServerVersion && current != null) {
            runCatching { client.readWorkflow(current.path) }
                .onFailure { error -> AppLogger.warn("读取服务器工作流正文失败，改用本机草稿", error) }
                .getOrNull()
        } else {
            null
        }
        val loadServerVersion = serverRaw != null
        val raw = serverRaw ?: document.rawJson
        val manifest = activeBridge.loadWorkflow(
            rawJson = raw,
            workflowPath = current?.path,
        )
        val fields = if (document.hasUnsavedChanges && !loadServerVersion) {
            WorkflowDraftFields.restore(manifest.fields, WorkflowDraftFields.capture(_state.value.fields))
        } else {
            manifest.fields
        }
        bridgeLoadedPath = document.entry.path
        val updated = document.copy(
            entry = current ?: document.entry,
            rawJson = raw,
            fields = fields,
            nodes = manifest.nodes,
            baseModified = if (loadServerVersion && current != null) current.modified else document.baseModified,
            hasUnsavedChanges = if (loadServerVersion) false else document.hasUnsavedChanges,
        )
        val conflict = updated.hasUnsavedChanges && serverChanged
        _state.update {
            it.copy(
                selectedWorkflow = updated,
                fields = fields,
                nodeProblems = emptyMap(),
                workflowDraftConflictRequired = conflict,
                workflowDraftConflictReason = if (conflict) {
                    when {
                        !lookup.reachable ->
                            "手机草稿仍然保留，但这次没能读到服务器上的工作流列表（网络或接口不稳定）。" +
                                "可以稍后手动刷新，确认服务器版本。"
                        current == null ->
                            "手机草稿仍然保留，但服务器上的原工作流已经不存在。请另存为新工作流。"
                        else ->
                            "手机草稿仍然保留，但服务器版本在断线期间发生了变化。请选择继续手机草稿、读取服务器版本，或者另存。"
                    }
                } else {
                    ""
                },
                notice = if (loadServerVersion) "服务器已更新，已重新读取最新工作流" else it.notice,
            )
        }
    }

    private fun valueJson(kind: ParameterKind, value: String): String = when (kind) {
        ParameterKind.INTEGER -> value.toLongOrNull()?.toString() ?: "0"
        ParameterKind.DECIMAL -> value.toDoubleOrNull()?.toString() ?: "0.0"
        ParameterKind.BOOLEAN -> value.toBooleanStrictOrNull()?.toString() ?: "false"
        else -> JSONObject.quote(value)
    }

    private fun mimeType(media: ResultMedia): String = when (media.filename.substringAfterLast('.', "").lowercase()) {
        "jpg", "jpeg" -> "image/jpeg"
        "webp" -> "image/webp"
        "gif" -> "image/gif"
        "mp4", "m4v" -> "video/mp4"
        "webm" -> "video/webm"
        "mov" -> "video/quicktime"
        else -> if (media.kind == MediaKind.IMAGE) "image/png" else "video/*"
    }

    private suspend fun runOperation(prefix: String, block: suspend () -> Unit) {
        val operation = prefix.removeSuffix("失败")
        AppLogger.info("$operation 开始")
        runCatching { block() }
            .onSuccess { AppLogger.info("$operation 完成") }
            .onFailure { error ->
                if (error is CancellationException) throw error
                AppLogger.error(prefix, error)
                _state.update {
                    val detail = error.message ?: error.javaClass.simpleName
                    val connecting = prefix.startsWith("连接")
                    it.copy(
                        loading = false,
                        generating = false,
                        scanning = false,
                        error = "$prefix：$detail",
                        status = if (connecting) ConnectionStatus.ERROR else it.status,
                        connectionMessage = if (connecting) "第 ${it.connectionStep} 步失败：$detail" else it.connectionMessage,
                        activeServer = if (connecting) null else it.activeServer,
                        bridgeReady = if (connecting) false else it.bridgeReady,
                    )
                }
            }
    }

    private fun setConnectionStep(step: Int, message: String) {
        _state.update {
            it.copy(
                status = ConnectionStatus.CONNECTING,
                connectionStep = step.coerceIn(1, it.connectionTotalSteps),
                connectionMessage = message,
            )
        }
    }

    override fun onCleared() {
        workflowDraftSaveJob?.cancel()
        currentDraftSnapshot()?.let { snapshot ->
            exitSaveScope.launch {
                runCatching { workflowDrafts.save(snapshot) }
                    .onFailure { AppLogger.error("退出前保存工作流草稿失败", it) }
            }
        }
        client.closeWebSocket()
        super.onCleared()
    }

    private companion object {
        const val MIN_VISIBLE_NODE_MILLIS = 450L
        const val DRAFT_SAVE_DEBOUNCE_MILLIS = 250L
    }
}
