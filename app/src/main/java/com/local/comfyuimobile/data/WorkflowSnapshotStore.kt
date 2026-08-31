package com.local.comfyuimobile.data

import android.content.Context
import com.local.comfyuimobile.model.WorkflowEntry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.UUID

/**
 * 工作流正文的本地快照（v0.1.69）。
 *
 * 百度 AI Studio 这类反向代理不转发 ComfyUI 的 /userdata 接口，工作流内容在手机上
 * 只有"这一次机会"：导入时内存里有，可一旦进程被回收、或者换个入口再打开，就再也
 * 读不回来，界面只剩一句"工作流加载失败"（日志里 16:02:41、16:05:39、16:17:47 都是）。
 *
 * 这里把每次成功读到 / 成功导入的正文按「服务器 + 路径」落盘一份，作为内存缓存
 * [WorkflowContentCache] 之下的第二级兜底：
 *
 * 1. 内存缓存命中 → 直接用；
 * 2. 内存没有 → 读磁盘快照先用着；
 * 3. 同时照常请求服务器，成功了就刷新两级缓存。
 *
 * 第 3 步保证它永远只是**兜底**而不是"新数据源"——直连 ComfyUI 时服务器一定能读
 * 成功，快照每轮都被最新内容覆盖，不会拖后腿，也不会出现删了服务器文件手机还能
 * 打开的诡异情况（那种情况只在服务器接口整体不可用时才会用到快照）。
 */
class WorkflowSnapshotStore internal constructor(private val directory: File) {
    constructor(context: Context) : this(File(context.filesDir, DIRECTORY_NAME))

    private val mutex = Mutex()

    suspend fun read(serverUrl: String, workflowPath: String): String? = withContext(Dispatchers.IO) {
        mutex.withLock { readNow(serverUrl, workflowPath) }
    }

    /**
     * 列出这台服务器上"本机确实存有正文"的工作流。
     *
     * 用途：AI Studio 这类平台不开放 /userdata，服务器工作流列表永远拿不到，
     * 以前只能拿"最近浏览过的路径"当占位——那种条目只有路径没有内容，用户点了
     * 必然报"工作流加载失败"（日志里连点 15 次全是它）。快照列表里的每一条
     * 都能真正打开。需要 Android 的 [WorkflowEntry]（在 model 包），本类其余
     * 部分保持纯 Kotlin 以便单测。
     */
    suspend fun list(serverUrl: String): List<WorkflowEntry> = withContext(Dispatchers.IO) {
        mutex.withLock { listNow(serverUrl) }
    }

    suspend fun write(serverUrl: String, workflowPath: String, json: String) = withContext(Dispatchers.IO) {
        if (serverUrl.isBlank() || workflowPath.isBlank() || json.isBlank()) return@withContext
        mutex.withLock { writeNow(serverUrl, workflowPath, json) }
    }

    suspend fun remove(serverUrl: String, workflowPath: String) = withContext(Dispatchers.IO) {
        mutex.withLock { Files.deleteIfExists(fileFor(serverUrl, workflowPath).toPath()) }
    }

    suspend fun clearAll(): Int = withContext(Dispatchers.IO) {
        mutex.withLock {
            val files = directory.listFiles { file -> file.isFile && file.extension == "json" }.orEmpty()
            files.forEach { file -> runCatching { file.delete() } }
            files.count { !it.exists() }
        }
    }

    private fun readNow(serverUrl: String, workflowPath: String): String? {
        val file = fileFor(serverUrl, workflowPath)
        if (!file.isFile) return null
        return runCatching {
            val root = JSONObject(file.readText(Charsets.UTF_8))
            // 目录名是哈希，但正文里也存了明文路径：既能校验哈希没撞车，
            // 也方便出问题时直接打开看是哪个工作流。
            if (normalizeServer(root.optString("serverUrl")) != normalizeServer(serverUrl)) return@runCatching null
            if (root.optString("workflowPath") != workflowPath) return@runCatching null
            root.optString("json").takeIf { it.isNotBlank() }
        }.getOrElse {
            // 快照写坏或者格式不认识：删掉别留着，下次重新从服务器取。
            runCatching { file.delete() }
            null
        }
    }

    private fun listNow(serverUrl: String): List<WorkflowEntry> {
        val normalized = normalizeServer(serverUrl)
        if (normalized.isBlank()) return emptyList()
        return directory.listFiles { file -> file.isFile && file.extension == "json" }
            .orEmpty()
            .mapNotNull { file ->
                runCatching {
                    val root = JSONObject(file.readText(Charsets.UTF_8))
                    if (normalizeServer(root.optString("serverUrl")) != normalized) return@mapNotNull null
                    val path = root.optString("workflowPath")
                    val json = root.optString("json")
                    if (path.isBlank() || json.isBlank()) return@mapNotNull null
                    WorkflowEntry(
                        name = path.substringAfterLast('/'),
                        path = path,
                        isDirectory = false,
                        size = json.toByteArray().size.toLong(),
                        // WorkflowEntry.modified 用秒（和 ComfyUI /userdata 一致），快照存毫秒。
                        modified = root.optLong("updatedAt") / 1000.0,
                    )
                }.getOrNull()
            }
            // 最近保存的排前面：用户刚导入的应该第一个看到。
            .sortedByDescending { it.modified }
    }

    private fun writeNow(serverUrl: String, workflowPath: String, json: String) {        directory.mkdirs()
        val target = fileFor(serverUrl, workflowPath)
        val temporary = File(directory, ".${target.name}.${UUID.randomUUID()}.tmp")
        try {
            temporary.outputStream().bufferedWriter(Charsets.UTF_8).use { writer ->
                writer.write(
                    JSONObject()
                        .put("schema", SCHEMA)
                        .put("serverUrl", normalizeServer(serverUrl))
                        .put("workflowPath", workflowPath)
                        .put("updatedAt", System.currentTimeMillis())
                        .put("json", json)
                        .toString(),
                )
                writer.flush()
            }
            runCatching {
                Files.move(
                    temporary.toPath(),
                    target.toPath(),
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING,
                )
            }.getOrElse {
                Files.move(temporary.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
            }
            pruneNow()
        } finally {
            if (temporary.exists()) temporary.delete()
        }
    }

    private fun pruneNow() {
        directory.listFiles { file -> file.isFile && file.extension == "json" }
            .orEmpty()
            .sortedByDescending(File::lastModified)
            .drop(MAX_SNAPSHOTS)
            .forEach(File::delete)
    }

    private fun fileFor(serverUrl: String, workflowPath: String): File =
        File(directory, "${identity(serverUrl, workflowPath)}.json")

    companion object {
        /** 工作流正文动辄几百 KB，只留最近用过的这些，避免把手机存储吃干。 */
        const val MAX_SNAPSHOTS = 30
        private const val SCHEMA = 1
        private const val DIRECTORY_NAME = "workflow_snapshots"

        fun normalizeServer(value: String): String = value.trim().trimEnd('/').lowercase()

        fun identity(serverUrl: String, workflowPath: String): String {
            val bytes = MessageDigest.getInstance("SHA-256")
                .digest("${normalizeServer(serverUrl)}\n$workflowPath".toByteArray(Charsets.UTF_8))
            return bytes.joinToString("") { byte -> "%02x".format(byte) }
        }
    }
}
