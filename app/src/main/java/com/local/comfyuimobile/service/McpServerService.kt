package com.local.comfyuimobile.service

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import com.local.comfyuimobile.MainActivity
import com.local.comfyuimobile.R
import com.local.comfyuimobile.data.AppLogger
import com.local.comfyuimobile.data.AppPreferences
import com.local.comfyuimobile.data.AuthCookieProvider
import com.local.comfyuimobile.data.LocalResultCache
import com.local.comfyuimobile.model.ResultSource
import com.local.comfyuimobile.mcp.McpServerManager
import com.local.comfyuimobile.network.ComfyClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

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
    private var manager: McpServerManager? = null
    private var client: ComfyClient? = null
    /** 服务当前生效的服务器地址与 Cookie；用于跳过偏好流里的重复推送。 */
    @Volatile private var appliedUrl: String = ""
    @Volatile private var appliedCookie: String = ""

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP || intent?.action == ACTION_PANIC) {
            if (intent.action == ACTION_PANIC) panicStop() else stopServer()
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
        val resolvedToken = token.ifBlank { stored?.mcpServerToken.orEmpty() }
        if (resolvedToken.isBlank()) {
            AppLogger.warn("MCP 前台服务启动被拒：没有访问令牌")
            stopServer()
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
        )
        this.manager = manager
        val bound = manager.start(
            resolvedToken,
            // 端口解析必须与 UI 同一条路（resolvePort）：直接传偏好原值的话，
            // 0 会被 ServerSocket 理解成"系统随机分配"——UI 显示 23456、
            // 配置片段也生成 23456，服务却绑在随机端口上，AiCode 必然连不上。
            requestedPort = McpServerManager.resolvePort(stored?.mcpServerPort ?: 0),
            resultSink = { media, file -> resultCache.add(media, file, ResultSource.MCP) },
            onSubmitted = { promptId -> adoptSubmittedJob(promptId) },
        )
        markRunning(true)
        AppLogger.info("MCP 前台服务已就绪：127.0.0.1:$bound")
        startForeground(FOREGROUND_ID, buildNotification(bound))
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
        val preferences = AppPreferences(this)
        val current = runCatching { preferences.settings.first().submittedJobs }.getOrDefault(emptySet())
        if (promptId in current) return
        runCatching { preferences.saveSubmittedJobs(current + promptId) }
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
        stopServer()
    }

    private fun stopServer() {
        runCatching { manager?.stop() }
        manager = null
        markRunning(false)
        scope.launch { runCatching { AppPreferences(this@McpServerService).setMcpServerEnabled(false) } }
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        runCatching { manager?.stop() }
        manager = null
        markRunning(false)
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
            .setContentTitle("MCP 服务运行中")
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
     * 紧急停止（v0.2.91）：清空远端队列 + 停掉本服务。
     *
     * 关服务**不等于**停任务——任务在 ComfyUI 的队列里，所以两件事都要做。
     * 队列操作失败也要继续停服务：至少本地不再接受新的出图请求。
     */
    private fun panicStop() {
        val client = this.client
        if (client != null) {
            runCatching {
                scope.launch {
                    runCatching { client.clearPending() }
                    runCatching {
                        client.queue()
                            .filter { it.state == com.local.comfyuimobile.model.JobState.RUNNING }
                            .forEach { runCatching { client.cancel(it) } }
                    }
                }
            }
            AppLogger.warn("MCP 紧急停止：已请求清空队列并中断执行中的任务")
        }
        stopServer()
    }

    companion object {
        const val CHANNEL_ID = "comfy_mcp"
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
