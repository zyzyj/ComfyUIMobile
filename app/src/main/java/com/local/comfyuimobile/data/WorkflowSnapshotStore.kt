package com.local.comfyuimobile.data

import android.content.Context
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

    private fun writeNow(serverUrl: String, workflowPath: String, json: String) {
        directory.mkdirs()
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
