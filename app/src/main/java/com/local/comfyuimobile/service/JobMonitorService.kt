package com.local.comfyuimobile.service

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.net.Uri
import android.net.wifi.WifiManager
import android.os.IBinder
import android.os.PowerManager
import com.local.comfyuimobile.MainActivity
import com.local.comfyuimobile.R
import com.local.comfyuimobile.data.AppPreferences
import com.local.comfyuimobile.data.AppLogger
import com.local.comfyuimobile.data.CachePolicy
import com.local.comfyuimobile.data.LocalResultCache
import com.local.comfyuimobile.network.ComfyClient
import com.local.comfyuimobile.network.LanAddress
import com.local.comfyuimobile.network.ResultParser
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.first
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.net.URLEncoder
import java.util.concurrent.TimeUnit
import java.util.concurrent.ConcurrentHashMap

class JobMonitorService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    // 与 ComfyClient 保持一致：公网/云端服务器握手可能超过 5 秒，
    // 太短会让后台轮询在服务器可达的情况下持续假失败。
    private val client = OkHttpClient.Builder().connectTimeout(15, TimeUnit.SECONDS).readTimeout(30, TimeUnit.SECONDS).build()
    // 反向代理认证 Cookie（AI Studio 等需要登录态），由 handleStartCommand 从 intent 设置。
    @Volatile private var authCookie: String = ""
    private val monitors = ConcurrentHashMap<String, Job>()
    private val workflowNames = ConcurrentHashMap<String, String>()
    private val workflowPaths = ConcurrentHashMap<String, String>()
    private val serverUrls = ConcurrentHashMap<String, String>()
    private val authCookies = ConcurrentHashMap<String, String>()
    // v0.1.86：连接保活状态。非空表示"用户还连着这台服务器"，没有任务时前台服务也要留着。
    @Volatile private var keepAliveServer: String = ""
    @Volatile private var keepAliveName: String = ""
    private val localResultCache by lazy { LocalResultCache(applicationContext) }
    private val preferences by lazy { AppPreferences(applicationContext) }
    private val wakeLock by lazy {
        getSystemService(PowerManager::class.java).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "$packageName:comfy-job").apply {
            setReferenceCounted(false)
        }
    }
    private val wifiLock by lazy {
        getSystemService(WifiManager::class.java).createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "$packageName:comfy-job").apply {
            setReferenceCounted(false)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // v0.1.86：保活指令走轻量路径。下面那条常规路径会先挂一条"正在准备后台任务"
        // 的占位通知再交给 handleStartCommand——保活不该闪这一下错误状态，直接由
        // KEEP_ALIVE 分支自己建立"已连接"通知（满足 5 秒内 startForeground 的要求）。
        if (intent?.action == ACTION_KEEP_ALIVE) {
            return try {
                handleStartCommand(intent, startId)
            } catch (error: Throwable) {
                AppLogger.error("连接保活处理失败", error)
                runCatching { stopForeground(STOP_FOREGROUND_REMOVE) }
                stopSelf(startId)
                START_NOT_STICKY
            }
        }
        val promptId = intent?.getStringExtra(EXTRA_PROMPT_ID).orEmpty()
        val workflowName = intent?.getStringExtra(EXTRA_WORKFLOW_NAME).orEmpty().ifBlank {
            workflowNames[promptId].orEmpty().ifBlank { "ComfyUI 工作流" }
        }
        val workflowPath = intent?.getStringExtra(EXTRA_WORKFLOW_PATH).orEmpty().ifBlank {
            workflowPaths[promptId].orEmpty()
        }
        val baseUrl = intent?.getStringExtra(EXTRA_BASE_URL).orEmpty().ifBlank {
            serverUrls[promptId].orEmpty()
        }
        // 反向代理认证 Cookie：随 intent 传入并记忆（AI Studio 等需要登录态，
        // 后台轮询若不带上会被重定向到登录页返回 404/HTML）。
        val cookie = intent?.getStringExtra(EXTRA_AUTH_COOKIE).orEmpty().ifBlank {
            authCookies[promptId].orEmpty()
        }
        if (cookie.isNotBlank()) authCookies[promptId] = cookie
        authCookie = cookie
        return try {
            // startForegroundService() 启动后必须立刻建立前台通知。日志、锁和任务恢复均放在其后，
            // 避免系统在进程繁忙或锁获取变慢时抛出 ForegroundServiceDidNotStartInTimeException。
            startForeground(
                FOREGROUND_ID,
                notification("正在准备后台任务", workflowName, true, promptId = promptId, baseUrl = baseUrl, workflowPath = workflowPath),
            )
            AppLogger.info("后台前台通知已建立：任务=${promptId.ifBlank { "待恢复" }}")
            handleStartCommand(intent, startId)
        } catch (error: Throwable) {
            AppLogger.error("后台任务服务启动失败，任务=${promptId.ifBlank { "未知" }}", error)
            monitors.remove(promptId)?.cancel()
            workflowNames.remove(promptId)
            workflowPaths.remove(promptId)
            serverUrls.remove(promptId)
            runCatching { releaseBackgroundLocks() }
            runCatching { stopForeground(STOP_FOREGROUND_REMOVE) }
            stopSelf(startId)
            START_NOT_STICKY
        }
    }

    private fun handleStartCommand(intent: Intent?, startId: Int): Int {
        val promptId = intent?.getStringExtra(EXTRA_PROMPT_ID).orEmpty()
        // v0.1.86：连接保活。用户要的是"像音乐播放器那样"——连上服务器后常驻一条前台
        // 通知，让进程不再是普通后台进程。以前前台服务只在提交任务时才起，空闲就退出，
        // 于是没在生图时 App 毫无保护，被系统一杀就得重走 65 秒的连接流程。
        if (intent?.action == ACTION_KEEP_ALIVE) {
            val keepServer = intent.getStringExtra(EXTRA_BASE_URL).orEmpty().trimEnd('/')
            val keepName = intent.getStringExtra(EXTRA_SERVER_NAME).orEmpty()
            if (keepServer.isBlank()) {
                // 空地址即"断开连接"，撤掉保活；还有任务在跑的话 stopIfIdle 会保留前台。
                keepAliveServer = ""
                keepAliveName = ""
                stopIfIdle()
                return START_NOT_STICKY
            }
            keepAliveServer = keepServer
            keepAliveName = keepName
            startForeground(FOREGROUND_ID, keepAliveNotification(keepName, keepServer))
            AppLogger.info("连接保活已建立：${keepName.ifBlank { keepServer }}")
            return START_STICKY
        }
        if (intent?.action == ACTION_STOP) {
            monitors.remove(promptId)?.cancel()
            workflowNames.remove(promptId)
            workflowPaths.remove(promptId)
            serverUrls.remove(promptId)
            stopIfIdle()
            return START_NOT_STICKY
        }
        if (intent?.action == ACTION_PROGRESS) {
            if (!monitors.containsKey(promptId)) {
                stopIfIdle()
                return START_NOT_STICKY
            }
            val percent = intent.getIntExtra(EXTRA_PROGRESS, -1)
            val node = intent.getStringExtra(EXTRA_NODE).orEmpty()
            val name = workflowNames[promptId].orEmpty().ifBlank { "ComfyUI 工作流" }
            startForeground(
                FOREGROUND_ID,
                notification(
                    "正在生成${if (percent >= 0) " $percent%" else ""}",
                    listOf(name, node).filter { it.isNotBlank() }.joinToString(" · "),
                    ongoing = true,
                    progress = percent,
                    promptId = promptId,
                    baseUrl = serverUrls[promptId].orEmpty(),
                    workflowPath = workflowPaths[promptId].orEmpty(),
                ),
            )
            return START_NOT_STICKY
        }
        val baseUrl = intent?.getStringExtra(EXTRA_BASE_URL).orEmpty().trimEnd('/')
        val workflowName = intent?.getStringExtra(EXTRA_WORKFLOW_NAME).orEmpty().ifBlank { "ComfyUI 工作流" }
        val workflowPath = intent?.getStringExtra(EXTRA_WORKFLOW_PATH).orEmpty()
        if (baseUrl.isBlank() || promptId.isBlank()) {
            stopSelf(startId)
            return START_NOT_STICKY
        }
        workflowNames[promptId] = workflowName
        workflowPaths[promptId] = workflowPath
        serverUrls[promptId] = baseUrl
        authCookies[promptId] = authCookie
        AppLogger.info("后台开始监控任务：$promptId，工作流=$workflowName")
        startForeground(
            FOREGROUND_ID,
            notification("正在生成", workflowName, true, promptId = promptId, baseUrl = baseUrl, workflowPath = workflowPath),
        )
        holdBackgroundLocks()
        monitors.remove(promptId)?.cancel()
        // v0.1.67：兜底最近正在监控的 promptId 仍然指向自身，确保 stopSelf 不会被外层
        // 的外层 START_REDELIVER_INTENT 误重启——只要这次任务进来就顶替占位。
        val monitor = scope.launch(start = CoroutineStart.LAZY) {
            var consecutivePollFailures = 0
            // v0.1.71：给"重试也没用"的失败一次复检机会，所以上限从 2 提到 4。
            // 普通抖动（5xx / 网络）每次 +1，4 次（20 秒）后放弃；
            // 永久性失败（404/403 这类）每次 +2，两次即达上限，10 秒后放弃。
            // intent 在 onStartCommand 里是可空类型，重启场景下可能为 null，这里给默认值兜底。
            val maxConsecutiveFailures = intent?.getIntExtra(EXTRA_MAX_FAILURES, 4) ?: 4
            while (isActive) {
                runCatching { readStatus(baseUrl, promptId) }.onSuccess { status ->
                    consecutivePollFailures = 0
                    if (status.completed) {
                        if (status.error) {
                            getSystemService(NotificationManager::class.java)
                                .notify(
                                    promptId.hashCode(),
                                    completionNotification(
                                        "生成失败",
                                        workflowName,
                                        promptId,
                                        baseUrl,
                                        workflowPath,
                                    ),
                                )
                        } else {
                            val localSaveRequested = runCatching { hasLocalSaveRequested(baseUrl) }.getOrDefault(false)
                            startForeground(
                                FOREGROUND_ID,
                                notification(
                                    "正在整理并保存本地作品",
                                    workflowName,
                                    ongoing = true,
                                    promptId = promptId,
                                    baseUrl = baseUrl,
                                    workflowPath = workflowPath,
                                ),
                            )
                            var report = SaveReport(
                                total = 0,
                                failed = 1,
                                localSaveRequested = localSaveRequested,
                                detail = "尚未开始保存",
                            )
                            for (attempt in 0 until 12) {
                                report = runCatching { saveLocalOutputs(baseUrl, promptId) }
                                    .getOrElse {
                                        SaveReport(
                                            total = 0,
                                            failed = 1,
                                            localSaveRequested = localSaveRequested,
                                            detail = it.message.orEmpty(),
                                        )
                                    }
                                if (report.failed == 0) break
                                if (attempt < 11) {
                                    startForeground(
                                        FOREGROUND_ID,
                                        notification(
                                            "本地保存未完成，正在重试 ${attempt + 1}/12",
                                            workflowName,
                                            ongoing = true,
                                            promptId = promptId,
                                            baseUrl = baseUrl,
                                            workflowPath = workflowPath,
                                        ),
                                    )
                                    delay(minOf(30_000L, (attempt + 1) * 2_000L))
                                }
                            }
                            val savedCount = (report.total - report.failed).coerceAtLeast(0)
                            val title = JobNotificationNavigation.completionTitle(
                                localSaveRequested = report.localSaveRequested,
                                savedCount = savedCount,
                                failed = report.failed > 0,
                            )
                            AppLogger.info("后台任务完成：$promptId，总输出=${report.total}，失败=${report.failed}，详情=${report.detail}")
                            val detail = if (report.failed == 0) workflowName else listOf(workflowName, report.detail.ifBlank { "${report.failed} 项保存失败" }).joinToString(" · ")
                            getSystemService(NotificationManager::class.java)
                                .notify(
                                    promptId.hashCode(),
                                    completionNotification(title, detail, promptId, baseUrl, workflowPath),
                                )
                            sendBroadcast(
                                Intent(ACTION_LOCAL_RESULTS_UPDATED)
                                    .setPackage(packageName)
                                    .putExtra(EXTRA_SAVED_COUNT, savedCount)
                                    .putExtra(EXTRA_SAVE_FAILED, report.failed > 0)
                                    .putExtra(EXTRA_LOCAL_SAVE_REQUESTED, report.localSaveRequested)
                                    // v0.1.86：带上任务号。以前界面层只能拿"全库最新的
                                    // createdAt"去猜这次存了哪些图——万一这次任务一张都没
                                    // 缓存（比如输出类型不在白名单里），它就会把上一次任务的
                                    // 图重新存一遍。
                                    .putExtra(EXTRA_PROMPT_ID, promptId),
                            )
                        }
                        monitors.remove(promptId)
                        workflowNames.remove(promptId)
                        workflowPaths.remove(promptId)
                        serverUrls.remove(promptId)
                        stopIfIdle()
                        return@launch
                    }
                }.onFailure { error ->
                    // v0.1.71：区分"抖动"和"这条路根本走不通"。反向代理拿不到 Cookie 时
                    // /history 会稳定返回 403/404，重试一万次也不会变好，直接按 2 次计，
                    // 10 秒内放弃并发通知；5xx 与网络异常仍按 1 次计，多给几次机会。
                    val permanent = error is PollFailure && error.permanent
                    consecutivePollFailures += if (permanent) 2 else 1
                    if (consecutivePollFailures == 1 || consecutivePollFailures % 6 == 0) {
                        AppLogger.error("后台轮询任务失败：$promptId，连续失败=$consecutivePollFailures", error)
                    }
                    // v0.1.67：超过上限就主动放弃并 stop 服务。AI Studio / 类似反向代理
                    // 拿不到 Cookie 时会无限刷 404，日志里 80 次连刷就是这个原因。
                    // v0.1.71：这条注释当年说的场景其实没被修好（readStatus 把非 2xx 当成功），
                    // 现在才真正生效。
                    if (consecutivePollFailures >= maxConsecutiveFailures) {
                        AppLogger.warn("连续失败 $consecutivePollFailures 次，自动放弃监控任务 $promptId")
                        monitors.remove(promptId)
                        workflowNames.remove(promptId)
                        workflowPaths.remove(promptId)
                        serverUrls.remove(promptId)
                        authCookies.remove(promptId)
                        getSystemService(NotificationManager::class.java)
                            .notify(
                                promptId.hashCode(),
                                completionNotification(
                                    "任务已超时",
                                    // 用实际失败次数而不是上限：永久性失败按 2 次计，
                                    // 达到上限时计数可能正好卡在中间值。
                                    "${workflowName}（连续 ${consecutivePollFailures} 次轮询失败，已停止监控）",
                                    promptId,
                                    baseUrl,
                                    workflowPath,
                                ),
                            )
                        stopIfIdle()
                        return@launch
                    }
                }
                delay(5_000)
            }
        }
        monitors[promptId] = monitor
        monitor.start()
        return START_REDELIVER_INTENT
    }

    override fun onDestroy() {
        releaseBackgroundLocks()
        scope.cancel()
        super.onDestroy()
    }

    private fun readStatus(baseUrl: String, promptId: String): PollStatus {
        val encoded = URLEncoder.encode(promptId, Charsets.UTF_8.name())
        val builder = Request.Builder().url("$baseUrl/history/$encoded").get()
        if (authCookie.isNotBlank()) builder.header("Cookie", authCookie)
        val request = builder.build()
        client.newCall(request).execute().use { response ->
            // v0.1.71：以前这里遇到非 2xx 直接返回"未完成"，等于把失败吞掉，
            // 上面那个连续失败上限永远触发不了——AI Studio 拿不到 Cookie 时会无限刷
            // 404，日志里 80 次连刷就是这么来的。现在如实抛出，交给上层计数。
            // 顺带说明：ComfyUI 对"任务还没进历史"返回的是 200 + {}，不是 404，
            // 所以把 404 当失败处理不会误伤正常排队中的任务。
            if (!response.isSuccessful) {
                val code = response.code
                val permanent = code in 400..499 && code != 408 && code != 429
                throw PollFailure("轮询任务状态失败：HTTP $code", permanent)
            }
            val root = JSONObject(response.body?.string().orEmpty())
            val status = root.optJSONObject(promptId)?.optJSONObject("status") ?: return PollStatus(false, false)
            return PollStatus(
                completed = status.optBoolean("completed"),
                error = status.optString("status_str").equals("error", true),
            )
        }
    }

    /**
     * 轮询失败的细分类型。
     *
     * @property permanent true 表示"再试也不会好"（401/403/404 这类），上层按 2 次计数快速放弃；
     *                     false 表示 5xx / 超时这类抖动，值得多试几次。
     */
    private class PollFailure(message: String, val permanent: Boolean) : java.io.IOException(message)

    private suspend fun saveLocalOutputs(baseUrl: String, promptId: String): SaveReport {
        val resultClient = ComfyClient()
        resultClient.setServer(baseUrl)
        resultClient.setAuthCookie(authCookie)
        val history = resultClient.history(promptId)
        check(history.optJSONObject(promptId) != null) { "任务结果尚未写入历史" }
        val settings = preferences.settings.first()
        // v0.1.86：与 CachePolicy.shouldCache 的判定保持一致——用户从未配过任何规则时
        // 默认全存（localSaveRequested=true）；只要配过，就严格按规则走。
        // 以前只看"有没有启用的规则"，没配过白名单的用户永远拿到 false——日志里每一条
        // "后台任务完成"都是"总输出=0，失败=0"，开关开了却一张都存不下来。
        val localSaveRequested = !CachePolicy.hasAnyRule(settings.cacheOutputRules) ||
            settings.cacheOutputRules.any { rule ->
                rule.enabled && LanAddress.sameServer(rule.serverUrl, baseUrl)
            }
        val parsed = ResultParser.parse(baseUrl, history)
        val eligible = parsed.filter { media ->
            media.jobId == promptId && CachePolicy.shouldCache(
                media,
                settings.submittedJobs,
                settings.cacheOutputRules,
                baseUrl,
                settings.cacheClearedAt,
            )
        }
        // v0.1.82：以前"总输出=0，失败=0"这一行看不出任何原因——没配规则、输出还没
        // 写进历史、节点类型对不上，三种情况长得一模一样，只能靠猜。现在把判定过程中
        // 的每个中间量都记下来，下次一眼就能定位是哪一环断了。
        AppLogger.info(
            "后台保存诊断：任务=$promptId，服务器=$baseUrl，" +
                "规则已启用=$localSaveRequested，" +
                "历史里解析到=${parsed.size} 项，" +
                "节点类型=[${parsed.map { it.nodeType }.distinct().joinToString().ifBlank { "无" }}]，" +
                "符合规则=${eligible.size} 项，" +
                "已提交任务记录=${if (promptId in settings.submittedJobs) "有" else "缺"}",
        )
        if (localSaveRequested && eligible.isEmpty()) {
            return SaveReport(
                total = 0,
                failed = 1,
                localSaveRequested = true,
                detail = "尚未读取到白名单输出",
            )
        }
        var failed = 0
        var lastError = ""
        for (media in eligible) {
            if (localResultCache.contains(media)) continue
            val destination = localResultCache.destination(media)
            var saved = false
            repeat(3) { attempt ->
                if (saved) return@repeat
                runCatching {
                    destination.parentFile?.mkdirs()
                    destination.outputStream().use { output -> resultClient.downloadTo(media.url, output) }
                    localResultCache.add(media, destination)
                }.onSuccess {
                    saved = true
                }.onFailure { error ->
                    lastError = error.message.orEmpty()
                    destination.delete()
                    if (attempt < 2) delay((attempt + 1) * 1_000L)
                }
            }
            if (!saved) failed += 1
        }
        return SaveReport(
            total = eligible.size,
            failed = failed,
            localSaveRequested = localSaveRequested,
            detail = lastError,
        )
    }

    private fun stopIfIdle() {
        if (monitors.isNotEmpty()) {
            val promptId = monitors.keys.firstOrNull().orEmpty()
            val name = workflowNames[promptId].orEmpty().ifBlank { "ComfyUI 工作流" }
            startForeground(
                FOREGROUND_ID,
                notification(
                    "正在生成",
                    name,
                    ongoing = true,
                    promptId = promptId,
                    baseUrl = serverUrls[promptId].orEmpty(),
                    workflowPath = workflowPaths[promptId].orEmpty(),
                ),
            )
            return
        }
        // v0.1.86：还连着服务器就保持前台服务常驻，只是把通知降级回"已连接"。
        // 以前没有任务就彻底退出前台，进程随即变成普通后台进程——AI Studio 上重连一次
        // 要 65 秒，被杀一次的代价太大了。
        if (keepAliveServer.isNotBlank()) {
            startForeground(FOREGROUND_ID, keepAliveNotification(keepAliveName, keepAliveServer))
            return
        }
        releaseBackgroundLocks()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun holdBackgroundLocks() {
        if (!wakeLock.isHeld) wakeLock.acquire()
        if (!wifiLock.isHeld) wifiLock.acquire()
    }

    private fun releaseBackgroundLocks() {
        if (wifiLock.isHeld) wifiLock.release()
        if (wakeLock.isHeld) wakeLock.release()
    }

    private fun notification(
        title: String,
        text: String,
        ongoing: Boolean,
        progress: Int = -1,
        promptId: String = "",
        baseUrl: String = "",
        workflowPath: String = "",
    ): Notification {
        return Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle(title)
            .setContentText(text)
            .setContentIntent(contentIntent(promptId, baseUrl, workflowPath, completed = false))
            .setCategory(Notification.CATEGORY_PROGRESS)
            .setOngoing(ongoing)
            .setOnlyAlertOnce(true)
            .apply { if (ongoing) setProgress(100, progress.coerceIn(0, 100), progress < 0) }
            .build()
    }

    /**
     * v0.1.86：连接保活的常驻通知。
     *
     * 没有任务在跑时也要有一条——前台服务是 Android 上唯一能显著降低"进程被杀"概率的
     * 正规手段，而系统要求前台服务必须配一条用户看得见的通知。这就是音乐播放器、导航
     * 类 App 的做法，不是什么黑魔法：它不能阻止用户主动划掉 App（FORCE STOP 后任何
     * 服务都不会被拉起，这是系统设计），但能让系统内存回收时优先保留本进程。
     */
    private fun keepAliveNotification(name: String, baseUrl: String): Notification =
        Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle("已连接 ${name.ifBlank { "ComfyUI" }}")
            .setContentText("保持连接中，点按返回 App")
            .setContentIntent(contentIntent("", baseUrl, "", completed = false))
            .setCategory(Notification.CATEGORY_STATUS)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .build()

    private suspend fun hasLocalSaveRequested(baseUrl: String): Boolean =
        preferences.settings.first().cacheOutputRules.any { rule ->
            rule.enabled && rule.serverUrl == baseUrl
        }

    private fun completionNotification(
        title: String,
        text: String,
        promptId: String,
        baseUrl: String,
        workflowPath: String,
    ): Notification = Notification.Builder(this, COMPLETION_CHANNEL_ID)
        .setSmallIcon(R.drawable.ic_launcher_foreground)
        .setContentTitle(title)
        .setContentText(text)
        .setContentIntent(contentIntent(promptId, baseUrl, workflowPath, completed = true))
        .setCategory(Notification.CATEGORY_STATUS)
        .setAutoCancel(true)
        .build()

    private fun contentIntent(
        promptId: String,
        baseUrl: String,
        workflowPath: String,
        completed: Boolean,
    ): PendingIntent {
        val contentIntent = Intent(this, MainActivity::class.java)
            .setAction(ACTION_OPEN_JOB)
            .setData(Uri.parse("comfyuimobile://job/${Uri.encode(promptId.ifBlank { "current" })}"))
            .putExtra(EXTRA_PROMPT_ID, promptId)
            .putExtra(EXTRA_BASE_URL, baseUrl)
            .putExtra(EXTRA_WORKFLOW_PATH, workflowPath)
            .putExtra(EXTRA_OPEN_COMPLETED, completed)
            .addFlags(JobNotificationNavigation.activityFlags)
        val pendingIntent = PendingIntent.getActivity(
            this,
            JobNotificationNavigation.requestCode(promptId),
            contentIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return pendingIntent
    }

    private data class PollStatus(val completed: Boolean, val error: Boolean)
    private data class SaveReport(
        val total: Int,
        val failed: Int,
        val localSaveRequested: Boolean = false,
        val detail: String = "",
    )

    companion object {
        const val CHANNEL_ID = "comfy_jobs"
        const val COMPLETION_CHANNEL_ID = "comfy_job_completion_v1"
        const val EXTRA_BASE_URL = "base_url"
        const val EXTRA_PROMPT_ID = "prompt_id"
        const val EXTRA_AUTH_COOKIE = "auth_cookie"
        const val EXTRA_WORKFLOW_NAME = "workflow_name"
        const val EXTRA_WORKFLOW_PATH = "workflow_path"
        const val EXTRA_PROGRESS = "progress"
        const val EXTRA_NODE = "node"
        // v0.1.67：调用方可以传入最大连续失败次数，给 AI Studio / 反向代理环境调大点。
        const val EXTRA_MAX_FAILURES = "max_failures"
        const val ACTION_PROGRESS = "com.local.comfyuimobile.action.PROGRESS"
        const val ACTION_STOP = "com.local.comfyuimobile.action.STOP_MONITOR"
        const val ACTION_LOCAL_RESULTS_UPDATED = "com.local.comfyuimobile.action.LOCAL_RESULTS_UPDATED"
        const val ACTION_OPEN_JOB = "com.local.comfyuimobile.action.OPEN_JOB"
        const val EXTRA_SAVED_COUNT = "saved_count"
        const val EXTRA_SAVE_FAILED = "save_failed"
        const val EXTRA_LOCAL_SAVE_REQUESTED = "local_save_requested"
        /** v0.1.86：结果广播里带上任务号，界面层才能精确知道这次存了哪些图。 */
        const val EXTRA_PROMPT_ID = "prompt_id"
        /** v0.1.86：连接保活（常驻前台服务，降低被系统回收的概率）。带地址=建立，空地址=撤销。 */
        const val ACTION_KEEP_ALIVE = "com.local.comfyuimobile.action.KEEP_ALIVE"
        const val EXTRA_SERVER_NAME = "server_name"
        const val EXTRA_OPEN_COMPLETED = "open_completed"
        private const val FOREGROUND_ID = 8188
    }
}
