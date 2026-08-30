package com.local.comfyuimobile.data

/**
 * v0.1.68：工作流内容的进程内缓存。
 *
 * 用途：百度 AI Studio 这类反向代理不开放 /userdata，[ComfyClient.readWorkflow] 永远
 * 404。但工作流的内容在导入时就已经完整读进内存了，没必要再回服务器取一次。
 * 有了这份缓存，"导入成功 → 切到快捷生图 → 再打开"这条链路在不支持云端工作流
 * 的服务器上也能走通，而不是每次都死在读文件那一步。
 *
 * 只放内存、不落盘：工作流正文可能很大，落盘既占空间又要处理清理策略；
 * 内存缓存足够覆盖"一次会话内反复切换工作流"的场景，重启后重新导入即可。
 */
object WorkflowContentCache {
    private const val MAX_ENTRIES = 24
    private val entries = HashMap<String, String>()
    private val order = ArrayDeque<String>()

    @Synchronized
    fun put(serverUrl: String, path: String, json: String) {
        if (path.isBlank() || json.isBlank()) return
        val key = key(serverUrl, path)
        if (!entries.containsKey(key)) order.addLast(key)
        entries[key] = json
        trim()
    }

    @Synchronized
    operator fun get(serverUrl: String, path: String): String? {
        val key = key(serverUrl, path)
        val value = entries[key] ?: return null
        // 命中后挪到队尾，保持最近最少使用（LRU）的淘汰顺序。
        order.remove(key)
        order.addLast(key)
        return value
    }

    @Synchronized
    fun remove(serverUrl: String, path: String) {
        val key = key(serverUrl, path)
        if (entries.remove(key) != null) order.remove(key)
    }

    @Synchronized
    fun clear() {
        entries.clear()
        order.clear()
    }

    @Synchronized
    fun size(): Int = entries.size

    private fun trim() {
        while (order.size > MAX_ENTRIES) {
            val oldest = order.removeFirstOrNull() ?: return
            entries.remove(oldest)
        }
    }

    /**
     * 服务器地址统一小写去斜杠，保证同一台服务器的不同写法命中同一份缓存。
     * 只做规范化、不做哈希：内容只在内存里，不做持久化也不需要脱敏。
     */
    private fun key(serverUrl: String, path: String): String =
        "${serverUrl.trim().trimEnd('/').lowercase()}\n${path.trim()}"
}
