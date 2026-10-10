package com.local.comfyuimobile.service

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.net.wifi.WifiManager
import android.os.IBinder
import android.os.PowerManager
import com.local.comfyuimobile.MainActivity
import com.local.comfyuimobile.R
import com.local.comfyuimobile.data.AppLogger
import com.local.comfyuimobile.data.AppPreferences
import com.local.comfyuimobile.data.AuthCookieProvider
import com.local.comfyuimobile.data.LocalResultCache
import com.local.comfyuimobile.model.JobState
import com.local.comfyuimobile.model.ResultSource
import com.local.comfyuimobile.mcp.AiStudioBridge
import com.local.comfyuimobile.mcp.KernelTerminalBackend
import com.local.comfyuimobile.mcp.McpCallLog
import com.local.comfyuimobile.mcp.McpServerManager
import com.local.comfyuimobile.mcp.McpTerminalHost
import com.local.comfyuimobile.network.AiStudioKernelClient
import com.local.comfyuimobile.network.ComfyClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * MCP 服务的前台承载（v0.2.85）。
 *
 * 为什么必须是 Service 而不是挂在 ViewModel 上：用户的用法是**开着 MCP 然后切到
 * AiCode**——本 App 立刻进后台。而 AiCode 自己跑着一个 Linux 容器（内存大户），
 * 系统内存压力下本进程是 LMK 的首选目标；进程一死，server 就没了，AiCode 那边
 * 表现为连接被拒或调用超时，而且**没有任何提示**告诉用户是本 App 被杀了。
 *
 * 前台服务是 Android 上唯一能显著降低"进程被杀"概率的正规手段（音乐播放器、
 * 导航类 App 都这么做）。它挡不住用户主动划掉（FORCE STOP 后任何服务都不会被拉起，
 * 这是系统设计），但能让系统在回收内存时优先保留本进程。
 */
class McpServerService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /**
     * 清理用作用域（v0.2.95）：**故意不被 [onDestroy] 取消**。
     *
     * 以前紧急停止把清队列放在 [scope] 里，而 [panicStop] 紧接着调 [stopServer] →
     * `stopSelf()` → [onDestroy] → `scope.cancel()`。清队列是网络请求（几十~几百 ms），
     * 协程几乎总在刚发起时就被取消——**结果是只停了本地服务，远端队列根本没清**，
     * 而这正是紧急停止存在的理由（关服务不等于停任务）。
     *
     * 需要"服务销毁后仍要跑完"的事都放这里：清队列、写偏好关标记。
     */
    private val cleanupScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private var manager: McpServerManager? = null

    /**
     * 息屏保活锁（v0.3.0，清单 P0-1）。
     *
     * 实测：息屏挂机出 9 张后开始报 `Unable to resolve host "aistudio.baidu.com"`——
     * **不是** Connection refused，说明进程还活着、但**网络被 Doze 掐了**。
     * 根因是本服务没持 WakeLock/WifiLock，而 App 自己出图那条链
     * （[JobMonitorService]）有——两条平行路径只给一条上了锁。
     * 写法照搬那边（`PARTIAL_WAKE_LOCK` + `WIFI_MODE_FULL_HIGH_PERF`，
     * `setReferenceCounted(false)`），权限早已在 Manifest 里。
     */
    private val wakeLock by lazy {
        getSystemService(PowerManager::class.java)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "$packageName:mcp")
            .apply { setReferenceCounted(false) }
    }
    private val wifiLock by lazy {
        getSystemService(WifiManager::class.java)
            .createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "$packageName:mcp")
            .apply { setReferenceCounted(false) }
    }

    private fun holdBackgroundLocks() {
        if (!wakeLock.isHeld) wakeLock.acquire()
        if (!wifiLock.isHeld) wifiLock.acquire()
    }

    private fun releaseBackgroundLocks() {
        if (wifiLock.isHeld) wifiLock.release()
        if (wakeLock.isHeld) wakeLock.release()
    }
    private var client: ComfyClient? = null
    /** 服务当前生效的服务器地址与 Cookie；用于跳过偏好流里的重复推送。 */
    @Volatile private var appliedUrl: String = ""
    @Volatile private var appliedCookie: String = ""

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP || intent?.action == ACTION_PANIC) {
            if (intent.action == ACTION_PANIC) panicStop() else stopServer(StopReason.USER)
            return START_NOT_STICKY
        }
        // v0.3.5（P0-2）：上一轮是「不该运行」而停的，这一轮就别再让系统重建了。
        // 否则会变成：系统重建 → 立刻自停 → 系统再重建 → …（日志里 30 秒 24 次空转）。
        val previous = lastStopReason
        if (previous == StopReason.NOT_ENABLED || previous == StopReason.AUTH || previous == StopReason.START_FAILED) {
            AppLogger.warn(
                "MCP 上一轮因「$previous」停止，本轮不再重建（START_NOT_STICKY）。" +
                    "若这是你没预料到的：请到 App 的 MCP 服务页确认开关状态；" +
                    "这条日志反复出现说明存在自锁循环，属 bug",
            )
            return START_NOT_STICKY
        }
        val token = intent?.getStringExtra(EXTRA_TOKEN).orEmpty()
        // startForegroundService 起的要求：必须立刻建通知，否则系统抛
        // ForegroundServiceDidNotStartInTimeException（连日志都来不及写）。
        startForeground(FOREGROUND_ID, buildNotification(0))
        scope.launch { runCatching { startServer(token) }.onFailure { onStartFailed(it) } }
        return START_STICKY
    }

    private suspend fun startServer(token: String) {
        val stored = runCatching { AppPreferences(this).settings.first() }.getOrNull()
        // v0.3.0（清单 P3）：sticky 重建（onStartCommand 收到 null intent）会重新走这里。
        // 若用户已经关了 MCP（偏好 enabled=false），不应该被系统拉起来——
        // 否则"关了又自己活了"，用户以为开关坏了。
        if (stored?.mcpServerEnabled != true) {
            AppLogger.info("MCP 服务被系统重建，但开关已关闭，不再启动（改为正常停止）")
            stopServer(StopReason.NOT_ENABLED)
            return
        }
        val resolvedToken = token.ifBlank { stored?.mcpServerToken.orEmpty() }
        // 免鉴权模式下不需要 token（F1）；只有开启鉴权时才要求必须有。
        val requireAuth = stored?.mcpServerRequireAuth == true
        if (requireAuth && resolvedToken.isBlank()) {
            AppLogger.warn("MCP 前台服务启动被拒：已开启鉴权但没有令牌")
            stopServer(StopReason.AUTH)
            return
        }
        // 用**服务自己的** ComfyClient：baseUrl / Cookie 从偏好里恢复。
        // 它只被 MCP 工具调用，不与界面里的那份共享状态，避免两边互相改 baseUrl。
        val created = ComfyClient()
        client = created
        applyServerFromPreferences(created)

        // MCP 出图同步写入结果页（v0.2.88）：用户用 AI 生成的图，在 App 的
        // "结果"页里要能翻到——不然 App 本来用来看图的地方反而少了 AI 那部分。
        val resultCache = LocalResultCache(this)

        // AI Studio 通道与终端共用一套实例：账号从偏好读，令牌刷新后回写偏好。
        val aiStudioBridge = AiStudioBridge(AppPreferences(this))
        val kernel = AiStudioKernelClient().also { aiStudioBridge.bindKernel(it) }

        val manager = McpServerManager(
            client = created,
            cacheDir = filesDir,
            currentWorkflowPath = { null },
            // 反代会话失效时重新读偏好：App 内用户刷新过 Cookie 并落盘后，这里就能
            // 拿到新值。MCP 侧没有账号上下文，无法像界面那样从 kernelClient 导出，
            // 但"重读偏好"已能覆盖最常见的过期场景（用户回 App 重连一次即可）。
            refreshCookie = {
                applyServerFromPreferences(created, forceCookie = true)
                if (created.authCookie().isBlank()) {
                    throw IllegalStateException("登录态已失效，请在 App 内重新连接 ComfyUI")
                }
            },
            clientId = "comfy-mobile-mcp",
            // AI Studio 通道（v0.2.97）：服务没有 ViewModel 的内存态，所以桥接层
            // 自己从 DataStore 读账号（每次调用重读，跟随 App 里登录/切账号）。
            aiStudio = aiStudioBridge,
            // 终端与 AI Studio 通道共用同一个 kernel client 与令牌刷新器：
            // 两套刷新器会各自拿到"更新后"的令牌、互相覆盖（与 ViewModel 同一约定）。
            terminal = McpTerminalHost(
                kernel = KernelTerminalBackend(kernel),
                activeProject = { aiStudioBridge.runningProject() },
            ),
            // v0.3.7：list_my_jobs 读的是**界面任务跟踪用的同一份存储**（不另建一套）。
            submittedJobsReader = {
                runCatching { AppPreferences(this).settings.first().submittedJobRecords }
                    .getOrDefault(emptyList())
            },
            submittedJobFetched = { promptId ->
                runCatching { AppPreferences(this).markSubmittedJobFetched(promptId) }
                    .onFailure { AppLogger.warn("标记任务已取图失败：$promptId", it) }
            },
        )
        this.manager = manager
        val bound = manager.start(
            resolvedToken,
            // 鉴权开关从偏好读（F1：默认免鉴权）。服务可能比界面先起，所以不能靠 Intent 传。
            requireAuth = stored?.mcpServerRequireAuth == true,
            // 端口解析必须与 UI 同一条路（resolvePort）：直接传偏好原值的话，
            // 0 会被 ServerSocket 理解成"系统随机分配"——UI 显示 23456、
            // 配置片段也生成 23456，服务却绑在随机端口上，AiCode 必然连不上。
            requestedPort = McpServerManager.resolvePort(stored?.mcpServerPort ?: 0),
            resultSink = { media, file -> resultCache.add(media, file, ResultSource.MCP) },
            onSubmitted = { promptId -> adoptSubmittedJob(promptId) },
        )
        markRunning(true)
        AppLogger.info("MCP 前台服务已就绪：127.0.0.1:$bound")
        // §4.5：真机日志里中途出现过一次 initialize，可能是"App 进程被杀后重启"
        // （那就是 AI 的那次长等待白等了）。启动也在调用日志里记一条——它在界面上
        // 可见、且（v0.2.97 起）会落盘，于是重启与调用能拼成一条时间线。
        McpCallLog.log("服务启动", ok = true, detail = "监听 127.0.0.1:$bound")
        startForeground(FOREGROUND_ID, buildNotification(bound))
        // 服务真正就绪才持锁：MCP 的价值就在于"用户切走后 AI 还能继续调"，
        // 息屏后网络被 Doze 掐断正是实测断链的原因。服务停止/销毁必须释放，
        // 否则用户关了 MCP 仍在白白耗电。
        holdBackgroundLocks()
        observePreferences(created, bound)
    }

    /**
     * 把 MCP 提交的任务并入"本机提交过"的偏好集合（v0.2.90）。
     *
     * ViewModel 观察这个偏好：id 一进来就会被纳入界面任务跟踪（进度、通知、结果页）。
     * 走偏好而不是直接回调 ViewModel，是因为服务可能比界面先起、也可能在界面被
     * 回收后仍然活着——偏好是两者之间唯一稳定的通道。
     */
    private suspend fun adoptSubmittedJob(promptId: String) {
        if (promptId.isBlank()) return
        // v0.3.5（P1-2）：改用原子的 addSubmittedJob（读写同一事务），避免并发
        // 提交时后写的覆盖先写的导致任务 id 丢失。
        runCatching { AppPreferences(this).addSubmittedJob(promptId) }
            .onFailure { AppLogger.warn("MCP 任务登记失败：$promptId", it) }
    }

    /**
     * 把偏好里的活跃服务器与 Cookie 应用到 [client]。
     *
     * [forceCookie] 为 true 时跳过"与当前相同"的短路——Cookie 在偏好里原地刷新
     * （地址不变）时必须重新写入 client，否则刷新永远不生效。
     */
    private suspend fun applyServerFromPreferences(client: ComfyClient, forceCookie: Boolean = false) {
        val stored = runCatching { AppPreferences(this).settings.first() }.getOrNull() ?: return
        val url = stored.activeServerUrl.trim().trimEnd('/')
        val cookie = stored.profiles.firstOrNull { it.baseUrl == url || it.baseUrl.trim().trimEnd('/') == url }
            ?.cookie.orEmpty()
        if (url != appliedUrl) {
            client.setServer(url)
            appliedUrl = url
            appliedCookie = cookie
            client.setAuthCookie(cookie)
            AuthCookieProvider.current = cookie
            if (url.isBlank()) {
                AppLogger.warn("MCP：尚未连接 ComfyUI，工具调用将返回明确提示")
            } else {
                AppLogger.info("MCP 服务器已指向：$url")
            }
        } else if (forceCookie && cookie != appliedCookie) {
            appliedCookie = cookie
            client.setAuthCookie(cookie)
            AuthCookieProvider.current = cookie
            AppLogger.info("MCP 登录态已从偏好刷新")
        }
    }

    /**
     * 观察偏好的活跃服务器/Cookie 变化（v0.2.87）。
     *
     * 服务持有自己的 client 是有意隔离，但代价原本是"App 里切服务器后 MCP 仍打
     * 旧地址，直到重启服务"。collect 偏好流后两边就同步了——这也顺带解决
     * "App 里刷新过 Cookie、服务内存里还是旧的"那一半。
     */
    private fun observePreferences(client: ComfyClient, port: Int) {
        scope.launch {
            var lastUrl = appliedUrl
            var lastCookie = appliedCookie
            runCatching {
                AppPreferences(this@McpServerService).settings.collect { stored ->
                    val url = stored.activeServerUrl.trim().trimEnd('/')
                    val cookie = stored.profiles
                        .firstOrNull { it.baseUrl == url || it.baseUrl.trim().trimEnd('/') == url }
                        ?.cookie.orEmpty()
                    if (url != lastUrl) {
                        lastUrl = url
                        lastCookie = cookie
                        client.setServer(url)
                        client.setAuthCookie(cookie)
                        AuthCookieProvider.current = cookie
                        appliedUrl = url
                        appliedCookie = cookie
                        AppLogger.info(if (url.isBlank()) "MCP：服务器已清空" else "MCP 服务器已切换：$url")
                        startForeground(FOREGROUND_ID, buildNotification(port, urlBlank = url.isBlank()))
                    } else if (cookie != lastCookie) {
                        lastCookie = cookie
                        client.setAuthCookie(cookie)
                        AuthCookieProvider.current = cookie
                        appliedCookie = cookie
                        AppLogger.info("MCP 登录态已更新（偏好变化）")
                    }
                }
            }.onFailure { AppLogger.warn("MCP 偏好观察中断", it) }
        }
    }

    private fun onStartFailed(error: Throwable) {
        // 绑定失败最常见的原因是端口被占。明确告诉用户"能改端口"——
        // 只报失败的话，用户不知道出路在哪，也不该静默换端口（那会让配置悄悄失效）。
        val message = if (error.message?.contains("Bind failed", ignoreCase = true) == true ||
            error is java.net.BindException
        ) {
            "MCP 端口被占用：请到 MCP 服务页 → 高级 → 端口 换一个"
        } else {
            error.message.orEmpty()
        }
        AppLogger.error("MCP 前台服务启动失败：$message", error)
        stopServer(StopReason.START_FAILED)
    }

    /**
     * 停止服务的原因（v0.3.5）。
     *
     * 为什么要区分：`stopServer` 有 6 个调用点，只有「用户主动关」和「紧急停止」
     * 才代表用户意愿。把开关写成 false 是**用户的决定**，系统/内部原因不该替他做。
     * 以前一视同仁地写 false，结果是：开关一旦变成 false（哪怕只是一次误写），
     * 服务重建 → 读到 false → 自停 → 又写 false → 永不自愈的自锁循环。
     */
    private enum class StopReason {
        /** 用户在设置/MCP 页把开关拨到关。 */
        USER,

        /** sticky 重建后发现开关已是 false（不该继续运行）。 */
        NOT_ENABLED,

        /** 开了鉴权但没有 token。 */
        AUTH,

        /** 启动失败（端口占用、连接失败等）。 */
        START_FAILED,

        /** 用户点了常驻通知的「紧急停止」。 */
        PANIC,
    }

    /**
     * 上一轮的停止原因（进程内）。用于决定是否让系统继续重建——
     * 「不该运行」而停的情况若仍返回 START_STICKY，就会变成空转循环。
     */
    @Volatile private var lastStopReason: StopReason? = null

    private fun stopServer(reason: StopReason = StopReason.USER) {
        runCatching { manager?.stop() }
        manager = null
        markRunning(false)
        // 只有「用户主动关 / 紧急停止」才改开关。其余都是系统或内部行为，
        // 不代表用户意愿——写成 false 会悄悄关掉用户的开关，并（见上）形成自锁。
        //
        // 保留 USER/PANIC 落盘的理由没变：进程被杀后重开 App 若读到 true
        // 会自动拉起服务，用户明明关了却自己活了。
        if (reason == StopReason.USER || reason == StopReason.PANIC) {
            cleanupScope.launch {
                runCatching { AppPreferences(this@McpServerService).setMcpServerEnabled(false, System.currentTimeMillis()) }
                    .onFailure { AppLogger.warn("保存 MCP 开关失败", it) }
            }
        }
        runCatching { stopForeground(STOP_FOREGROUND_REMOVE) }
        releaseBackgroundLocks()
        lastStopReason = reason
        stopSelf()
    }

    override fun onDestroy() {
        runCatching { manager?.stop() }
        manager = null
        markRunning(false)
        // 锁必须在 onDestroy 释放：stopServer 只覆盖"用户主动关"，
        // 系统回收服务（swipe/低内存）走的是 onDestroy。漏了就一直持有到进程死。
        releaseBackgroundLocks()
        scope.cancel()
        super.onDestroy()
    }

    /**
     * 常驻通知。内容里直接给出地址与"点按返回 App"，让用户切到 AiCode 后不必
     * 再切回来确认端口。
     */
    private fun buildNotification(port: Int, urlBlank: Boolean = false): Notification {
        val open = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        // 紧急停止：一键清队列 + 停服务。AI 批量提交后要能立刻刹车，
        // 而翻设置太慢（任务在远端队列里，晚一秒就多跑一张）。
        val panic = PendingIntent.getService(
            this,
            1,
            Intent(this, McpServerService::class.java).setAction(ACTION_PANIC),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle("MCP 服务运行中 · 息屏保活已开启")
            .setContentText(
                when {
                    // P1-2：没连服务器时不能假装一切正常——否则用户切到 AiCode 后
                    // 每个工具都失败，还以为是协议或配置问题。
                    urlBlank -> "未连接 ComfyUI，工具不可用 —— 点按回 App 连接"
                    port > 0 -> "127.0.0.1:$port · AiCode 可连接，点按返回 App"
                    else -> "正在启动…"
                },
            )
            .setContentIntent(open)
            .addAction(
                Notification.Action.Builder(
                    android.graphics.drawable.Icon.createWithResource(this, R.drawable.ic_launcher_foreground),
                    "紧急停止",
                    panic,
                ).build(),
            )
            .setCategory(Notification.CATEGORY_SERVICE)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .build()
    }

    /**
     * 紧急停止（v0.2.91；v0.2.95 修竞态）：清空远端队列 + 停掉本服务。
     *
     * 关服务**不等于**停任务——任务在 ComfyUI 的队列里，所以两件事都要做。
     *
     * 顺序上必须**先清完队列再停服务**：反过来的话，stopSelf 会触发 onDestroy
     * 把协程取消，清队列请求根本发不出去（或发到一半断掉）。清理放在
     * [cleanupScope] 里，它不会被服务销毁取消。
     *
     * 清理有超时：网络卡住时不能让用户干等，超时后照旧停服务——至少本地
     * 不再接受新的出图请求。
     */
    private fun panicStop() {
        val target = client
        if (target == null) {
            stopServer(StopReason.PANIC)
            return
        }
        // 先把通知撤掉，用户立刻看到反馈；清队列在后台收尾。
        runCatching { stopForeground(STOP_FOREGROUND_REMOVE) }
        cleanupScope.launch {
            val cleaned = withTimeoutOrNull(PANIC_CLEANUP_TIMEOUT_MS) {
                // 这里是**用户主动点**的紧急停止，语义就是"全停"——
                // 与 MCP 的 cancel_jobs 默认只清本 App 任务**刻意不同**：
                // AI 不该决定别人的任务命运，而用户自己按下的按钮应当彻底。
                runCatching { target.clearPending() }
                runCatching {
                    target.queue()
                        .filter { it.state == JobState.RUNNING }
                        .forEach { runCatching { target.cancel(it) } }
                }
                true
            }
            AppLogger.warn(
                if (cleaned == true) "MCP 紧急停止：远端队列已清理，现在停服务"
                else "MCP 紧急停止：清理超时，仍停服务（远端队列可能仍有任务）",
            )
            stopServer(StopReason.PANIC)
        }
    }

    companion object {
        const val CHANNEL_ID = "comfy_mcp"
        /**
         * 紧急停止时留给远端清理的时间（v0.2.95）。
         * 网络卡住不能让用户干等；超时后照旧停服务。
         */
        const val PANIC_CLEANUP_TIMEOUT_MS = 5_000L
        private const val FOREGROUND_ID = 4201
        const val ACTION_STOP = "com.local.comfyuimobile.mcp.STOP"
        const val ACTION_PANIC = "com.local.comfyuimobile.mcp.PANIC"
        const val EXTRA_TOKEN = "mcp_token"

        /**
         * 服务当前是否在跑。
         *
         * 用进程内的静态标记而非 `ActivityManager.getRunningServices`（后者对第三方
         * App 已基本失效）。偏好里另存了开关值，两者含义不同：这里是"这一刻真的活着吗"。
         */
        @Volatile
        private var running = false

        fun isRunning(): Boolean = running

        internal fun markRunning(value: Boolean) {
            running = value
        }

        /** 启动前台服务。失败时不抛（调用方只需知道结果）。 */
        fun start(context: Context, token: String): Boolean = runCatching {
            val intent = Intent(context, McpServerService::class.java)
                .putExtra(EXTRA_TOKEN, token)
            androidx.core.content.ContextCompat.startForegroundService(context, intent)
        }.isSuccess

        fun stop(context: Context) {
            runCatching { context.startService(Intent(context, McpServerService::class.java).setAction(ACTION_STOP)) }
        }
    }
}
