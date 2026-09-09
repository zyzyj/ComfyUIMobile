package com.local.comfyuimobile.data

import com.local.comfyuimobile.model.WorkflowEntry

object WorkflowBrowser {
    const val ROOT = "workflows"

    /**
     * v0.1.87：路径里没有 `/` 时（根目录里的散装工作流）以前返回空串，
     * `entries()` 拿它去和 folder 比永远比不上，这类工作流在浏览模式下直接不可见，
     * 只能靠搜索碰运气。统一归到 ROOT 下。
     */
    fun parent(path: String): String =
        path.trimEnd('/').substringBeforeLast('/', "").ifBlank { ROOT }

    fun entries(entries: List<WorkflowEntry>, folder: String, search: String): List<WorkflowEntry> {
        val query = search.trim()
        return entries.filter { entry ->
            if (query.isNotBlank()) {
                entry.name.contains(query, ignoreCase = true) || entry.path.contains(query, ignoreCase = true)
            } else {
                parent(entry.path) == folder.trimEnd('/')
            }
        }.sortedWith(compareBy<WorkflowEntry> { !it.isDirectory }.thenBy(String.CASE_INSENSITIVE_ORDER) { it.name })
    }

    fun up(folder: String): String = parent(folder).takeIf { it.startsWith(ROOT) } ?: ROOT
}
