package com.local.comfyuimobile

import android.Manifest
import android.app.DownloadManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.OpenableColumns
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.local.comfyuimobile.bridge.ComfyBridge
import com.local.comfyuimobile.data.AppLogger
import com.local.comfyuimobile.service.JobMonitorService
import com.local.comfyuimobile.ui.ComfyMobileApp
import com.local.comfyuimobile.ui.ComfyMobileTheme
import com.local.comfyuimobile.update.UpdateManager
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    private val viewModel: MainViewModel by viewModels()
    private lateinit var bridge: ComfyBridge
    private var receiverRegistered = false
    private var localResultsReceiverRegistered = false

    private val downloadReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val id = intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1L)
            if (id == -1L) return
            lifecycleScope.launch {
                UpdateManager(this@MainActivity).verifyAndInstall(id)
                    .onFailure { Toast.makeText(this@MainActivity, "更新校验失败：${it.message}", Toast.LENGTH_LONG).show() }
            }
        }
    }

    private val localResultsReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            viewModel.onLocalResultsSaved(
                count = intent.getIntExtra(JobMonitorService.EXTRA_SAVED_COUNT, 0),
                failed = intent.getBooleanExtra(JobMonitorService.EXTRA_SAVE_FAILED, false),
                localSaveRequested = intent.getBooleanExtra(JobMonitorService.EXTRA_LOCAL_SAVE_REQUESTED, false),
                // v0.1.86：旧版本广播里没有这个字段，拿到空串时界面层退回按时间过滤。
                jobId = intent.getStringExtra(JobMonitorService.EXTRA_PROMPT_ID).orEmpty(),
            )
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        bridge = ComfyBridge(this).also { it.configure() }
        viewModel.attachBridge(bridge)
        requestRuntimePermissions()
        registerDownloadReceiver()
        registerLocalResultsReceiver()
        setContent {
            ComfyMobileTheme {
                ComfyMobileApp(viewModel, bridge)
            }
        }
        handleJobNotification(intent)
        handleSharedImage(intent)
        // v0.1.85：更新检查不再抢在启动最前面。它要并发打 GitHub 和国内镜像做
        // DNS/TLS 握手，实测吃掉 3.7 秒（日志 23:34:47.672 → 23:34:51.244），正好和
        // 连接抢网络；它还会在连接初期写一次 DataStore，触发一轮全局状态刷新。
        // 现在改成：连接成功后立刻查一次（见 MainViewModel.connect），这里只留一个
        // 20 秒的兜底，免得用户一直不连就永远收不到更新提示。24 小时节流仍在，
        // 两边不会重复跑。
        lifecycleScope.launch {
            delay(UPDATE_CHECK_FALLBACK_DELAY_MS)
            viewModel.checkUpdate(manual = false)
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleJobNotification(intent)
        handleSharedImage(intent)
    }

    /**
     * 处理从外部 App（相册 / 文件管理器）分享或「打开」进来的图片。
     *
     * 支持两种形式：ACTION_SEND 带 EXTRA_STREAM 的图片，与 ACTION_VIEW 直接指向图片的 URI。
     * 只接受图片类型（MIME 以 image 开头），普通文本分享不处理（避免把一段文字当图片读）。
     */
    @Suppress("DEPRECATION")
    private fun handleSharedImage(intent: Intent?) {
        val payload = intent ?: return
        val action = payload.action ?: return
        if (action != Intent.ACTION_SEND && action != Intent.ACTION_VIEW) return
        val rawUri: Uri? = when (action) {
            Intent.ACTION_SEND -> @Suppress("DEPRECATION") payload.getParcelableExtra(Intent.EXTRA_STREAM)
            else -> payload.data
        }
        val uri = rawUri ?: return
        if (uri.scheme == null) return
        // 这个入口是导出的：任意 App 都能对它发 Intent，URI 可能没 grant 读权限、
        // 也可能压根无效。getType / query 会对这种 URI 抛 SecurityException，
        // 而它们跑在主线程（onCreate / onNewIntent），不接住就是整个 Activity 崩掉。
        val mimeType = runCatching { payload.type ?: contentResolver.getType(uri) }
            .onFailure { AppLogger.warn("读取分享图片的 MIME 失败", it) }
            .getOrNull()
        if (mimeType?.startsWith("image/") != true) return
        // 分享进来的图片不一定能拿到文件名（content:// 常见），退回一个默认名。
        // 默认名的扩展名必须跟着 MIME 走：写成 .png 的话，一张 WebP 会在
        // importWorkflow 里被扩展名判成 PNG，原生解析直接按 PNG 签名失败。
        val name = runCatching {
            contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
                ?.use { cursor -> if (cursor.moveToFirst()) cursor.getString(0) else null }
        }.onFailure { AppLogger.warn("读取分享图片的文件名失败", it) }
            .getOrNull()
            ?.takeIf { it.isNotBlank() }
            ?: defaultSharedImageName(mimeType, uri)
        viewModel.importSharedImage(uri, name, mimeType)
    }

    /** 拿不到文件名时的兜底名；扩展名按 MIME 给，别把 WebP 钉成 png。 */
    private fun defaultSharedImageName(mimeType: String, uri: Uri): String {
        val fromMime = when (mimeType.substringBefore(';').trim().lowercase()) {
            "image/webp" -> "webp"
            "image/avif" -> "avif"
            "image/png" -> "png"
            else -> null
        }
        if (fromMime != null) return "shared-image.$fromMime"
        // MIME 没给出具体格式时，再退到 URI 路径里的扩展名。
        val fromPath = uri.lastPathSegment
            ?.substringAfterLast('.', "")
            ?.lowercase()
            ?.takeIf { it in setOf("png", "webp", "avif") }
        return if (fromPath != null) "shared-image.$fromPath" else "shared-image"
    }

    // v0.1.82：App 挂后台时 Android 会冻结 WebView 的 JS 定时器，云端平台
    // （AI Studio / CloudStudio）靠定时器做的自动页面重载就停了。而桥接就绪
    // 只认"页面加载完成"这一个信号，页面不重载就永远恢复不了，生图按钮一直
    // 黑着——实测回前台后还要干等 6 分 40 秒。现在回到前台主动催一次恢复。
    override fun onResume() {
        super.onResume()
        viewModel.onReturnedToForeground()
    }

    override fun onStop() {
        viewModel.persistCurrentWorkflowDraft()
        super.onStop()
    }

    override fun onDestroy() {
        if (receiverRegistered) unregisterReceiver(downloadReceiver)
        if (localResultsReceiverRegistered) unregisterReceiver(localResultsReceiver)
        bridge.destroy()
        super.onDestroy()
    }

    private fun registerDownloadReceiver() {
        val filter = IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE)
        ContextCompat.registerReceiver(this, downloadReceiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
        receiverRegistered = true
    }

    private fun registerLocalResultsReceiver() {
        val filter = IntentFilter(JobMonitorService.ACTION_LOCAL_RESULTS_UPDATED)
        ContextCompat.registerReceiver(this, localResultsReceiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
        localResultsReceiverRegistered = true
    }

    private fun handleJobNotification(intent: Intent?) {
        if (intent?.action != JobMonitorService.ACTION_OPEN_JOB) return
        viewModel.openJobNotification(
            baseUrl = intent.getStringExtra(JobMonitorService.EXTRA_BASE_URL).orEmpty(),
            workflowPath = intent.getStringExtra(JobMonitorService.EXTRA_WORKFLOW_PATH).orEmpty(),
            promptId = intent.getStringExtra(JobMonitorService.EXTRA_PROMPT_ID).orEmpty(),
            completed = intent.getBooleanExtra(JobMonitorService.EXTRA_OPEN_COMPLETED, false),
        )
    }

    private fun requestRuntimePermissions() {
        val permissions = buildList {
            if (Build.VERSION.SDK_INT >= 33) add(Manifest.permission.POST_NOTIFICATIONS)
            if (Build.VERSION.SDK_INT <= 28) add(Manifest.permission.WRITE_EXTERNAL_STORAGE)
        }.filter { ActivityCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED }
        if (permissions.isNotEmpty()) ActivityCompat.requestPermissions(this, permissions.toTypedArray(), 8100)
    }

    private companion object {
        /** v0.1.85：启动后延迟多久兜底查一次更新（正常情况下连接成功时就查过了）。 */
        const val UPDATE_CHECK_FALLBACK_DELAY_MS = 20_000L
    }
}
