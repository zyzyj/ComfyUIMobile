package com.local.comfyuimobile.data

import com.local.comfyuimobile.model.WorkflowEntry

object RecentWorkflows {
    const val MAX_SIZE = 10

    fun add(current: List<String>, path: String, replacedPath: String? = null): List<String> {
        if (path.isBlank()) return current.filter(String::isNotBlank).distinct().take(MAX_SIZE)
        // v0.1.87：先去重、再剔除 replacedPath。以前两步写在同一个 filter 里，
        // `path == replacedPath` 时刚 prepend 进去的新路径会被自己那条条件一起过滤掉
        // ——用户重命名/覆盖了某个工作流，它反而从"最近浏览"里彻底消失。
        return (listOf(path) + current)
            .filter { it.isNotBlank() }
            .distinct()
            .filter { it == path || it != replacedPath }
            .take(MAX_SIZE)
    }

    fun remove(current: List<String>, path: String): List<String> =
        current.filter { it.isNotBlank() && it != path }.distinct().take(MAX_SIZE)

    fun resolveEntries(paths: List<String>, available: List<WorkflowEntry>): List<WorkflowEntry> =
        paths.filter(String::isNotBlank).distinct().take(MAX_SIZE).map { path ->
            available.firstOrNull { !it.isDirectory && it.path == path }
                ?: WorkflowEntry(
                    name = path.substringAfterLast('/').ifBlank { path },
                    path = path,
                    isDirectory = false,
                )
        }
}
