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

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopServer()
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
        val resolvedToken = token.ifBlank {
            runCatching { AppPreferences(this).settings.first().mcpServerToken }.getOrDefault("")
        }
        if (resolvedToken.isBlank()) {
            AppLogger.warn("MCP 前台服务启动被拒：没有访问令牌")
            stopServer()
            return
        }
        // 用**服务自己的** ComfyClient：baseUrl / Cookie 从偏好里恢复。
        // 它只被 MCP 工具调用，不与界面里的那份共享状态，避免两边互相改 baseUrl。
        val client = ComfyClient()
        val stored = runCatching { AppPreferences(this).settings.first() }.getOrNull()
        val activeUrl = stored?.activeServerUrl.orEmpty()
        if (activeUrl.isNotBlank()) {
            client.setServer(activeUrl)
            AuthCookieProvider.current = stored?.profiles
                ?.firstOrNull { it.baseUrl == activeUrl }?.cookie.orEmpty()
            client.setAuthCookie(AuthCookieProvider.current)
        }

        val created = McpServerManager(
            client = client,
            cacheDir = filesDir,
            currentWorkflowPath = { null },
            // 反代会话失效时刷新一次：MCP 侧没有账号上下文，只能用已有 Cookie 重试。
            refreshCookie = { },
            clientId = "comfy-mobile-mcp",
        )
        val bound = created.start(resolvedToken)
        manager = created
        markRunning(true)
        AppLogger.info("MCP 前台服务已就绪：127.0.0.1:$bound")
        startForeground(FOREGROUND_ID, buildNotification(bound))
    }

    private fun onStartFailed(error: Throwable) {
        AppLogger.error("MCP 前台服务启动失败", error)
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
    private fun buildNotification(port: Int): Notification {
        val open = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle("MCP 服务运行中")
            .setContentText(
                if (port > 0) "127.0.0.1:$port · AiCode 可连接，点按返回 App"
                else "正在启动…",
            )
            .setContentIntent(open)
            .setCategory(Notification.CATEGORY_SERVICE)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .build()
    }

    companion object {
        const val CHANNEL_ID = "comfy_mcp"
        private const val FOREGROUND_ID = 4201
        const val ACTION_STOP = "com.local.comfyuimobile.mcp.STOP"
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
