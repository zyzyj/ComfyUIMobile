package com.local.comfyuimobile.data

import android.content.Context
import android.net.Uri
import com.local.comfyuimobile.model.MediaKind
import com.local.comfyuimobile.model.ResultKey
import com.local.comfyuimobile.model.ResultMedia
import com.local.comfyuimobile.model.ResultSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption

class LocalResultCache(context: Context) {
    private val root = File(context.filesDir, "result_cache")
    private val indexFile = File(root, "index.json")

    suspend fun load(): List<ResultMedia> = withContext(Dispatchers.IO) {
        mutex.withLock { readIndex().mapNotNull(::decodeRecord).sortedByDescending { it.createdAt } }
    }

    suspend fun contains(media: ResultMedia): Boolean = withContext(Dispatchers.IO) {
        mutex.withLock {
            val key = key(media)
            readIndex().any { it.optString("key") == key && File(it.optString("localPath")).isFile }
        }
    }

    fun destination(media: ResultMedia): File {
        val extension = media.filename.substringAfterLast('.', "bin").take(12)
        val folder = File(root, safe(media.jobId))
        return File(folder, "${safe(media.nodeId)}-${key(media).hashCode().toUInt()}.$extension")
    }

    suspend fun add(media: ResultMedia, file: File): ResultMedia = withContext(Dispatchers.IO) {
        mutex.withLock {
            val key = key(media)
            val records = readIndex().filterNot { it.optString("key") == key }.toMutableList()
            records += encodeRecord(media, file, key)
            writeIndex(records)
            media.copy(
                url = Uri.fromFile(file).toString(),
                source = ResultSource.LOCAL,
                localPath = file.absolutePath,
            )
        }
    }

    suspend fun clear(): Unit = withContext(Dispatchers.IO) {
        mutex.withLock {
            if (root.exists()) root.deleteRecursively()
        }
    }

    suspend fun remove(media: Collection<ResultMedia>): Int = withContext(Dispatchers.IO) {
        mutex.withLock {
            val keys = media.map(::key).toSet()
            if (keys.isEmpty()) return@withLock 0
            val records = readIndex()
            val removed = records.filter { it.optString("key") in keys }
            removed.forEach { File(it.optString("localPath")).delete() }
            writeIndex(records.filterNot { it.optString("key") in keys })
            removed.size
        }
    }

    // v0.1.87：加锁。以前这里是唯一不上锁的读路径，和 clear() 并发时 walkTopDown
    // 会遍历到刚被 deleteRecursively() 的目录，结果不准或直接抛 IO 异常。
    suspend fun sizeBytes(): Long = withContext(Dispatchers.IO) {
        mutex.withLock {
            if (!root.exists()) 0L else root.walkTopDown().filter { it.isFile }.sumOf { it.length() }
        }
    }

    private fun readIndex(): List<JSONObject> = runCatching {
        val array = JSONArray(indexFile.takeIf { it.isFile }?.readText(Charsets.UTF_8).orEmpty().ifBlank { "[]" })
        List(array.length()) { array.getJSONObject(it) }.map(::migrateRecord)
    }.getOrDefault(emptyList())

    /**
     * v0.1.87：把旧编码（五个字段用 `/` 直接拼）的索引记录重算成新编码。
     *
     * 记录里本来就存着 jobId / nodeId / type / subfolder / filename，所以能原地重建
     * ——用户已经下载好的图片不会因为这次换 key 就从"本地作品"里消失。
     * 顺带一提：旧编码下撞 key 的两条输出，迁移后会变成两条独立记录，
     * 其中一条指向的文件可能早被另一条覆盖了，`decodeRecord` 的 `file.isFile` 检查
     * 会把缺失的那条过滤掉，不会显示成空条目。
     */
    private fun migrateRecord(item: JSONObject): JSONObject {
        if (item.optInt("kv", 1) >= ResultKey.VERSION) return item
        return item
            .put("kv", ResultKey.VERSION)
            .put(
                "key",
                ResultKey.encode(
                    listOf(
                        item.optString("jobId"),
                        item.optString("nodeId"),
                        item.optString("type"),
                        item.optString("subfolder"),
                        item.optString("filename"),
                    )
                ),
            )
    }

    private fun writeIndex(records: List<JSONObject>) {
        root.mkdirs()
        val temporary = File(root, "index.tmp")
        temporary.writeText(JSONArray(records).toString(), Charsets.UTF_8)
        // v0.1.87：先删再改名中间有个窗口，进程正好在窗口里被杀就丢整份索引
        // （文件还在磁盘上，App 却再也找不到它们）。改成原子替换，和
        // WorkflowDraftStore / WorkflowSnapshotStore 的做法一致。
        // 与 WorkflowDraftStore / WorkflowSnapshotStore 一致：先试原子替换，
        // 文件系统不支持时退回普通替换（此时仍有短暂窗口，但至少不会整体失败）。
        runCatching {
            Files.move(
                temporary.toPath(),
                indexFile.toPath(),
                StandardCopyOption.REPLACE_EXISTING,
                StandardCopyOption.ATOMIC_MOVE,
            )
        }.getOrElse {
            Files.move(temporary.toPath(), indexFile.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
    }

    private fun encodeRecord(media: ResultMedia, file: File, key: String) = JSONObject()
        .put("key", key)
        .put("kv", ResultKey.VERSION)
        .put("jobId", media.jobId)
        .put("nodeId", media.nodeId)
        .put("nodeType", media.nodeType)
        .put("nodeTitle", media.nodeTitle)
        .put("filename", media.filename)
        .put("subfolder", media.subfolder)
        .put("type", media.type)
        .put("kind", media.kind.name)
        .put("createdAt", media.createdAt)
        .put("taskNumber", media.taskNumber)
        .put("workflowPath", media.workflowPath)
        .put("workflowName", media.workflowName)
        .put("localPath", file.absolutePath)

    private fun decodeRecord(item: JSONObject): ResultMedia? {
        val file = File(item.optString("localPath"))
        if (!file.isFile) return null
        return ResultMedia(
            jobId = item.optString("jobId"),
            nodeId = item.optString("nodeId"),
            nodeType = item.optString("nodeType"),
            nodeTitle = item.optString("nodeTitle"),
            filename = item.optString("filename"),
            subfolder = item.optString("subfolder"),
            type = item.optString("type"),
            kind = runCatching { MediaKind.valueOf(item.optString("kind")) }.getOrDefault(MediaKind.IMAGE),
            url = Uri.fromFile(file).toString(),
            createdAt = item.optLong("createdAt", file.lastModified()),
            taskNumber = item.optLong("taskNumber"),
            workflowPath = item.optString("workflowPath"),
            workflowName = item.optString("workflowName"),
            source = ResultSource.LOCAL,
            localPath = file.absolutePath,
        )
    }

    // v0.1.87：与 ResultMedia.stableKey() 共用一套无歧义编码，消除 "/" 拼接带来的
    // 撞 key（详见 ResultKey 的说明）。
    private fun key(media: ResultMedia): String = media.stableKey()

    private fun safe(value: String): String = value.replace(Regex("[^A-Za-z0-9._-]"), "_").take(80).ifBlank { "item" }

    companion object {
        private val mutex = Mutex()
    }
}
