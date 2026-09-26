package com.local.comfyuimobile.data

import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.content.Context
import android.os.Build
import android.util.Log
import androidx.annotation.RequiresApi
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

object AppLogger {
    private const val TAG = "ComfyUIMobile"
    private const val PREFS = "diagnostic_logging"
    private const val ENABLED = "enabled"
    private const val LAST_EXIT_TIMESTAMP = "last_exit_timestamp"
    private const val MAX_BYTES = 2L * 1024L * 1024L
    private const val MAX_EXIT_TRACE_BYTES = 128 * 1024
    /** 待落盘行数上限，超出丢最旧（仅在磁盘持续跟不上写入时才可能触发）。 */
    private const val MAX_PENDING_LINES = 2_000
    private val traceKeyword = Regex(
        "fatal|sig[a-z0-9]+|crash|webview|chromium|abort|backtrace|fingerprint|abi|process|pid|tid|signal|tombstone|\\.so\\b",
        RegexOption.IGNORE_CASE,
    )
    private val lock = Any()
    /**
     * 线程安全的格式化器。旧的 SimpleDateFormat 非线程安全，而本类存在锁内、锁外
     * 两处并发调用（见 [recordHistoricalExits]），有可能同时 format 同一个实例。
     * java.time 的 DateTimeFormatter 不可变，天然安全；时区沿用系统默认。
     */
    private val formatter = DateTimeFormatter
        .ofPattern("yyyy-MM-dd HH:mm:ss.SSS", Locale.CHINA)
        .withZone(ZoneId.systemDefault())

    /**
     * 落盘线程。日志来自各业务线程（含主线程与 WebView 回调），直接在调用线程写文件
     * 等于把磁盘 IO 塞进 UI/渲染路径。改为投递到单线程队列，调用方只做入队。
     */
    private val writer = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "comfy-mobile-logger").apply { isDaemon = true }
    }
    /** 尚未落盘的行；有界，防并发洪峰把内存撑爆（满了丢最旧）。 */
    private val pending = ArrayDeque<String>()
    /** 是否已有一个落盘任务在排队，用于合并高频写入、避免任务堆积。 */
    private val flushScheduled = AtomicBoolean(false)

    @Volatile private var context: Context? = null
    @Volatile private var enabled = false
    @Volatile private var installed = false

    fun initialize(value: Context) {
        context = value.applicationContext
        enabled = isEnabled(value)
        installCrashHandler()
        info("应用启动，日志记录=${if (enabled) "开启" else "关闭"}")
        if (enabled) scheduleHistoricalExits(value)
    }

    fun isEnabled(value: Context): Boolean =
        // v0.1.91：默认**开启**。日志的唯一价值就在出问题的那一刻，而那一刻用户
        // 往往已经无法复现——以前默认关，等用户打开开关再复现，日志里早错过了。
        // 记录内容本身已排除提示词、工作流正文与图片，隐私风险可控。
        value.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(ENABLED, true)

    fun setEnabled(value: Context, valueEnabled: Boolean) {
        if (!valueEnabled && enabled) info("用户关闭诊断日志")
        value.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(ENABLED, valueEnabled).apply()
        enabled = valueEnabled
        if (valueEnabled) {
            info("用户开启诊断日志")
            scheduleHistoricalExits(value)
        }
    }

    fun info(message: String) = write("信息", message)

    /** v0.1.67：补上警告级日志，供「非致命但需要注意」的场景使用（如自动放弃轮询）。 */
    fun warn(message: String, throwable: Throwable? = null) {
        val detail = if (throwable == null) message else "$message\n${stackTrace(throwable)}"
        write("警告", detail)
    }

    fun error(message: String, throwable: Throwable? = null) {
        val detail = if (throwable == null) message else "$message\n${stackTrace(throwable)}"
        write("错误", detail)
    }

    fun read(): String = synchronized(lock) {
        flushPendingLocked()
        val app = context ?: return@synchronized "日志器尚未初始化"
        val folder = File(app.filesDir, "logs")
        // v0.1.87：显式 UTF-8。写侧一直用 Charsets.UTF_8，这里却走平台默认字符集，
        // 是全代码库唯一漏掉的一处（WorkflowDraftStore / WorkflowSnapshotStore /
        // LocalResultCache 都显式传了）。中文日志一旦被按别的字符集解码就成了乱码。
        val previous = File(folder, "comfy-mobile.previous.log").takeIf(File::isFile)?.readText(Charsets.UTF_8).orEmpty()
        val current = File(folder, "comfy-mobile.log").takeIf(File::isFile)?.readText(Charsets.UTF_8).orEmpty()
        listOf(previous, current)
            .filter(String::isNotBlank)
            .joinToString("\n")
            .let(::compactBinaryTraceForDisplay)
            .ifBlank { "暂无诊断日志" }
    }

    fun clear() = synchronized(lock) {
        pending.clear()
        val app = context ?: return@synchronized
        val folder = File(app.filesDir, "logs")
        File(folder, "comfy-mobile.log").delete()
        File(folder, "comfy-mobile.previous.log").delete()
    }

    private fun write(level: String, message: String) {
        if (!enabled) return
        when (level) {
            "错误" -> Log.e(TAG, message)
            "警告" -> Log.w(TAG, message)
            else -> Log.i(TAG, message)
        }
        if (context == null) return
        // 时间戳在入队时取，反映日志真正发生的时刻，而不是落盘时刻。
        val line = "${formatter.format(Instant.now())} [$level] $message\n"
        synchronized(lock) {
            pending.addLast(line)
            while (pending.size > MAX_PENDING_LINES) pending.removeFirst()
        }
        scheduleFlush()
    }

    /**
     * 合并高频写入：同一时刻只让一个落盘任务排队。
     *
     * 若不合并，每条日志都会 submit 一个任务，而 WebView 桥接阶段这类高频日志会在
     * 队列里堆起大量空转任务。先置位再落盘有个关键顺序：任务内必须**先把标记清掉、
     * 再写盘**——反过来会漏掉"写盘开始后新来的行"（它看到标记仍为 true 就不排队，
     * 而写盘已经取过 pending 了）。
     */
    private fun scheduleFlush() {
        if (!flushScheduled.compareAndSet(false, true)) return
        runCatching {
            writer.execute {
                flushScheduled.set(false)
                flushPending()
            }
        }.onFailure { flushScheduled.set(false) }
    }

    /** 把待落盘的行写出。调用方须已持有 [lock]。 */
    private fun flushPendingLocked() {
        if (pending.isEmpty()) return
        runCatching {
            val lines = pending.joinToString("")
            pending.clear()
            val app = context ?: return@runCatching
            val folder = File(app.filesDir, "logs").apply { mkdirs() }
            val file = File(folder, "comfy-mobile.log")
            if (file.length() >= MAX_BYTES) {
                val previous = File(folder, "comfy-mobile.previous.log")
                previous.delete()
                file.renameTo(previous)
            }
            file.appendText(lines, Charsets.UTF_8)
        }
    }

    /** 立即同步落盘（崩溃处理与读取日志前用，不等异步队列）。 */
    private fun flushPending(): Unit = synchronized(lock) { flushPendingLocked() }

    /** 恢复历史退出要读 trace 流（每个可达 128KB），不能在主线程做。 */
    private fun scheduleHistoricalExits(value: Context) {
        runCatching { writer.execute { recordHistoricalExits(value) } }
    }

    private fun installCrashHandler() {
        if (installed) return
        synchronized(lock) {
            if (installed) return
            val previous = Thread.getDefaultUncaughtExceptionHandler()
            Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
                error("未捕获闪退，线程=${thread.name}", throwable)
                // 崩溃后进程随时会被结束，异步队列来不及落盘，这里同步写一次。
                flushPending()
                previous?.uncaughtException(thread, throwable)
            }
            installed = true
        }
    }

    private fun recordHistoricalExits(value: Context) {
        if (Build.VERSION.SDK_INT < 30) return
        runCatching {
            val preferences = value.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            val lastRecorded = preferences.getLong(LAST_EXIT_TIMESTAMP, 0L)
            val exits = value.getSystemService(ActivityManager::class.java)
                .getHistoricalProcessExitReasons(value.packageName, 0, 8)
                .filter { it.timestamp > lastRecorded }
                .sortedBy { it.timestamp }
            exits.forEach { exit ->
                info(
                    "系统历史退出：时间=${formatter.format(Instant.ofEpochMilli(exit.timestamp))}，原因=${exitReason(exit.reason)}，" +
                        "进程=${exit.processName.orEmpty()}，PID=${exit.pid}，状态=${exit.status}，" +
                        "重要性=${exit.importance}，PSS=${exit.pss}KB，RSS=${exit.rss}KB，" +
                        "描述=${exit.description.orEmpty()}",
                )
                readExitTrace(exit)?.let { trace ->
                    info("系统退出追踪：进程=${exit.processName.orEmpty()}\n$trace")
                }
            }
            exits.maxOfOrNull { it.timestamp }?.let { newest ->
                preferences.edit().putLong(LAST_EXIT_TIMESTAMP, newest).apply()
            }
        }.onFailure { error("读取系统历史退出原因失败", it) }
    }

    @RequiresApi(Build.VERSION_CODES.R)
    private fun readExitTrace(exit: ApplicationExitInfo): String? = runCatching {
        val stream = exit.traceInputStream ?: return@runCatching null
        val output = ArrayList<Byte>(MAX_EXIT_TRACE_BYTES)
        var truncated = false
        stream.use { input ->
            val buffer = ByteArray(8 * 1024)
            while (output.size < MAX_EXIT_TRACE_BYTES) {
                val remaining = MAX_EXIT_TRACE_BYTES - output.size
                val count = input.read(buffer, 0, minOf(buffer.size, remaining))
                if (count < 0) break
                repeat(count) { output.add(buffer[it]) }
            }
            truncated = input.read() >= 0
        }
        if (output.isEmpty()) return@runCatching null
        val bytes = ByteArray(output.size) { output[it] }
        val readable = extractReadableTrace(bytes)
        if (readable.isBlank()) "二进制追踪中未提取到可读的崩溃信息，共读取 ${bytes.size} 字节"
        else buildString {
            append(readable)
            if (truncated) append("\n……追踪内容过长，已截断……")
        }
    }.getOrElse { throwable ->
        "读取退出追踪失败：${throwable.javaClass.simpleName}: ${throwable.message.orEmpty()}"
    }

    private fun extractReadableTrace(bytes: ByteArray): String {
        val runs = mutableListOf<String>()
        val current = StringBuilder()
        fun flush() {
            current.toString().trim().takeIf { it.length >= 4 }?.let(runs::add)
            current.clear()
        }
        bytes.forEach { byte ->
            val value = byte.toInt() and 0xff
            if (value in 0x20..0x7e || value == '\t'.code) current.append(value.toChar()) else flush()
        }
        flush()
        val distinct = runs.asSequence().map(String::trim).filter(String::isNotBlank).distinct().toList()
        val important = distinct.filter(traceKeyword::containsMatchIn)
        return (important.ifEmpty { distinct.take(12) })
            .take(60)
            .joinToString("\n")
            .take(16 * 1024)
    }

    private fun compactBinaryTraceForDisplay(raw: String): String = raw.lineSequence()
        .mapNotNull { line ->
            if (line.count { it == '\uFFFD' } < 3) return@mapNotNull line
            val ascii = line.map { char ->
                if (char.code in 0x20..0x7e || char == '\t') char else ' '
            }.joinToString("").replace(Regex("[ \\t]+"), " ").trim()
            ascii.takeIf(traceKeyword::containsMatchIn)?.take(1_200)
        }
        .joinToString("\n")

    private fun exitReason(reason: Int): String = when (reason) {
        ApplicationExitInfo.REASON_CRASH -> "Java/Kotlin 闪退"
        ApplicationExitInfo.REASON_CRASH_NATIVE -> "原生闪退"
        ApplicationExitInfo.REASON_ANR -> "无响应"
        ApplicationExitInfo.REASON_LOW_MEMORY -> "内存不足"
        ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE -> "资源使用过多"
        ApplicationExitInfo.REASON_USER_REQUESTED -> "用户或系统请求停止"
        ApplicationExitInfo.REASON_SIGNALED -> "系统信号终止"
        ApplicationExitInfo.REASON_DEPENDENCY_DIED -> "依赖进程终止"
        ApplicationExitInfo.REASON_PERMISSION_CHANGE -> "权限变化"
        ApplicationExitInfo.REASON_PACKAGE_UPDATED -> "软件更新"
        else -> "其他($reason)"
    }

    private fun stackTrace(throwable: Throwable): String = StringWriter().also { writer ->
        throwable.printStackTrace(PrintWriter(writer))
    }.toString()
}
