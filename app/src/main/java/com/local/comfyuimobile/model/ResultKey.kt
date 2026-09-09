package com.local.comfyuimobile.model

/**
 * 输出结果的唯一 key 编码（纯 Kotlin，可单元测试）。
 *
 * 背景：ComfyUI 的 `subfolder` 常规带日期斜杠（`2026-09-01/ComfyUI_00001_`），
 * 而早期实现是把 `jobId / nodeId / type / subfolder / filename` 五个字段直接用
 * `/` 拼起来。这是**有歧义**的编码：
 *
 * - `subfolder="a/b"`, `filename="c.png"`
 * - `subfolder="a"`,   `filename="b/c.png"`
 *
 * 两种完全不同的输出拼出来是同一个字符串。后果是后一条覆盖前一条的索引记录，
 * 而且 `LocalResultCache.destination()` 用 key 的哈希当文件名，第二次下载会直接
 * 把第一张已保存的图片覆盖掉。
 *
 * 这里改用"长度前缀 + 值"：`3:abc|5:x/y/z|`。字段值里出现任何分隔符都不会撞车。
 * 旧索引由 `LocalResultCache` 在读取时按字段重算迁移，已存的文件不会丢。
 */
object ResultKey {

    /** 索引记录里标记"这条 key 用的是新编码"，老记录没有这个字段（视作 1）。 */
    const val VERSION = 2

    fun encode(parts: List<String>): String = parts.joinToString("") { part ->
        "${part.length}:$part|"
    }
}
