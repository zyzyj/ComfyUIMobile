package com.local.comfyuimobile

import android.Manifest
import android.app.DownloadManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.local.comfyuimobile.bridge.ComfyBridge
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
