package com.local.comfyuimobile.mcp

import java.io.File
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * `/files/{id}` 的文件登记处（v0.2.85）。
 *
 * 这是整条取图链路的关键一环：AiCode 的 `flattenContent` 不渲染图片（源码确证），
 * 而两个 Android App 的私有目录互相隔离、AiCode 打不开 `filesDir`（实测：容器里
 * `/data` 根本不存在）。唯一可行的是**由本 App 把字节经 HTTP 交给对方**——
 * 于是需要一个短命、不可猜的 URL 映射到磁盘文件。
 *
 * **不把图片留在内存**：这里只登记文件路径，`/files/{id}` 时流式读盘。ComfyUI 一张图
 * 常见 2~10 MB，若在进程里驻留一小时（TTL），叠加多次调用会把 LMK 命中率拉满——
 * 而 MCP 服务恰恰要在后台常驻，被杀就等于功能消失。
 *
 * 安全取法（规划书 §7.3）：
 *  - id 是随机 UUID，只在 MCP 响应里出现一次，等同凭证；
 *  - 已绑 127.0.0.1，本机之外连不上；
 *  - TTL 到期即失效并删文件——不做一个"永久可读的目录服务"。
 */
internal class McpFileStore(
    private val cacheDir: File,
    private val ttlMillis: Long = DEFAULT_TTL_MILLIS,
    private val maxEntries: Int = DEFAULT_MAX_ENTRIES,
    private val maxTotalBytes: Long = DEFAULT_MAX_TOTAL_BYTES,
    private val now: () -> Long = System::currentTimeMillis,
) {

    internal data class Entry(
        val file: File,
        val contentType: String,
        val size: Long,
        val expiresAt: Long,
    ) {
        /** 日志用：文件名（日志不记路径，避免带出目录结构）。 */
        fun filenameDisplay(): String = file.name
    }

    private val entries = ConcurrentHashMap<String, Entry>()

    init {
        runCatching { cacheDir.mkdirs() }
    }

    /**
     * 出图流程用：分配一个新的空文件供调用方写入（边下载边落盘，不经过内存），
     * 写完后调 [register] 登记。
     *
     * 顺手清理孤儿：进程在下载中途被强杀时，半截文件不会被 [register] 登记、
     * 也就不会被 [enforceLimits] 统计到，只能在这里挨目录扫。
     */
    fun newFile(extension: String): File {
        runCatching { cacheDir.mkdirs() }
        sweepOrphans()
        return File(cacheDir, "${UUID.randomUUID().toString().replace("-", "")}.${extension.ifBlank { "bin" }}")
    }

    /** 删掉目录里不在登记表中的文件（孤儿）。登记表为空时目录应被清空。 */
    private fun sweepOrphans() {
        runCatching {
            cacheDir.listFiles()?.forEach { file ->
                if (file.isFile && !entries.containsKey(file.name)) runCatching { file.delete() }
            }
        }
    }

    /** 登记一个已写好的文件，返回可拼进 URL 的 id。 */
    fun register(file: File, extension: String, contentType: String): String {
        evictExpired()
        val id = file.name
        entries[id] = Entry(file, contentType, file.length(), now() + ttlMillis)
        enforceLimits()
        return id
    }

    /** 取文件；不存在、已过期或文件已被删除返回 null（调用方回 404）。 */
    fun get(id: String): Entry? {
        // id 直接来自 URL 路径，必须挡住 `..` 之类的穿越尝试。
        if (id.isBlank() || id.contains('/') || id.contains('\\') || id.contains("..")) return null
        val entry = entries[id] ?: return null
        if (entry.expiresAt <= now() || !entry.file.isFile) {
            entries.remove(id)
            runCatching { entry.file.delete() }
            return null
        }
        return entry
    }

    fun evictExpired() {
        val current = now()
        entries.keys.toList().forEach { key ->
            val entry = entries[key] ?: return@forEach
            if (entry.expiresAt <= current) {
                entries.remove(key)
                runCatching { entry.file.delete() }
            }
        }
    }

    /** 条目数与总字节双上限：超了按到期时间淘汰最旧的，并删文件。 */
    private fun enforceLimits() {
        if (entries.size > maxEntries) {
            entries.entries
                .sortedBy { it.value.expiresAt }
                .take(entries.size - maxEntries)
                .forEach { drop(it.key) }
        }
        var total = entries.values.sumOf { it.size }
        if (total <= maxTotalBytes) return
        for (entry in entries.entries.sortedBy { it.value.expiresAt }) {
            if (total <= maxTotalBytes) break
            total -= entry.value.size
            drop(entry.key)
        }
    }

    private fun drop(id: String) {
        val entry = entries.remove(id) ?: return
        runCatching { entry.file.delete() }
    }

    /** 清空并删文件。App 退出 / 服务停止时调用，不留孤儿文件。 */
    fun clear() {
        entries.keys.toList().forEach { drop(it) }
        runCatching { cacheDir.listFiles()?.forEach { it.delete() } }
    }

    fun size(): Int = entries.size

    internal companion object {
        /** 1 小时：足够 AI 走完"生成 → 取图 → 看图"，又不会长期留着一个可读 URL。 */
        const val DEFAULT_TTL_MILLIS = 60 * 60 * 1000L
        const val DEFAULT_MAX_ENTRIES = 64

        /** 总字节上限 128MB：宁可淘汰旧的，也不让这个目录无限吃磁盘。 */
        const val DEFAULT_MAX_TOTAL_BYTES = 128L * 1024 * 1024

        /** 按扩展名推断 Content-Type；未知一律 `application/octet-stream`。 */
        fun contentTypeOf(extension: String): String = when (extension.lowercase()) {
            "png" -> "image/png"
            "jpg", "jpeg" -> "image/jpeg"
            "webp" -> "image/webp"
            "gif" -> "image/gif"
            "mp4" -> "video/mp4"
            "webm" -> "video/webm"
            else -> "application/octet-stream"
        }
    }
}
