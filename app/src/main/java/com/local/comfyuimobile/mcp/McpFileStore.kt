package com.local.comfyuimobile.mcp

import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * `/files/{id}` 的临时文件登记处（v0.2.85）。
 *
 * 这是整条取图链路的关键一环：AiCode 的 `flattenContent` 不渲染图片（源码确证），
 * 而两个 Android App 的私有目录互相隔离、AiCode 打不开 `filesDir`（实测：容器里
 * `/data` 根本不存在）。唯一可行的是**由本 App 把字节经 HTTP 交给对方**——
 * 于是需要一个短命、不可猜的 URL 映射。
 *
 * 安全取法（规划书 §7.3）：
 *  - id 是随机 UUID，只在 MCP 响应里出现一次，等同凭证；
 *  - 已绑 127.0.0.1，本机之外连不上；
 *  - TTL 到期即失效——不做一个"永久可读的目录服务"。
 *
 * 不落地复制文件：直接持有字节数组。出图结果本身已由 [com.local.comfyuimobile.data.LocalResultCache]
 * 落盘，这里再复制一份纯属浪费；且内存态天然随进程结束而消失。
 */
internal class McpFileStore(
    private val ttlMillis: Long = DEFAULT_TTL_MILLIS,
    private val maxEntries: Int = DEFAULT_MAX_ENTRIES,
    private val now: () -> Long = System::currentTimeMillis,
) {

    internal data class Entry(
        val bytes: ByteArray,
        val contentType: String,
        val expiresAt: Long,
    )

    private val entries = ConcurrentHashMap<String, Entry>()

    /** 存入字节，返回可直接拼进 URL 的 id（含扩展名，便于对方按类型处理）。 */
    fun put(bytes: ByteArray, extension: String, contentType: String): String {
        evictExpired()
        val id = UUID.randomUUID().toString().replace("-", "") +
            if (extension.isBlank()) "" else ".$extension"
        entries[id] = Entry(bytes, contentType, now() + ttlMillis)
        // 兜底上限：TTL 内若被疯狂写入，仍不无限增长（丢最旧的）。
        if (entries.size > maxEntries) {
            entries.entries
                .sortedBy { it.value.expiresAt }
                .take(entries.size - maxEntries)
                .forEach { entries.remove(it.key) }
        }
        return id
    }

    /** 取文件；不存在或已过期返回 null（调用方回 404）。 */
    fun get(id: String): Entry? {
        val entry = entries[id] ?: return null
        if (entry.expiresAt <= now()) {
            entries.remove(id)
            return null
        }
        return entry
    }

    fun evictExpired() {
        val current = now()
        entries.keys.toList().forEach { key ->
            if ((entries[key]?.expiresAt ?: Long.MAX_VALUE) <= current) entries.remove(key)
        }
    }

    fun size(): Int = entries.size

    internal companion object {
        /** 1 小时：足够 AI 走完"生成 → 取图 → 看图"，又不会长期留着一个可读 URL。 */
        const val DEFAULT_TTL_MILLIS = 60 * 60 * 1000L
        const val DEFAULT_MAX_ENTRIES = 128

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
