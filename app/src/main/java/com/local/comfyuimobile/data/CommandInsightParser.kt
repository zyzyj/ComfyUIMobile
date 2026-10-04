package com.local.comfyuimobile.data

import com.local.comfyuimobile.model.InsightBar
import com.local.comfyuimobile.model.InsightCard
import com.local.comfyuimobile.model.InsightMetric
import com.local.comfyuimobile.model.InsightRow

/**
 * 把常见只读命令的输出解析成可视化卡片（v0.2.82）。
 *
 * 纯函数、不依赖 Android，可直接单测——这是刻意的：`nvidia-smi` 的输出格式随驱动
 * 版本变化，`df` 的列随系统变化，没有测试锁住，格式一变就是**静默的错误卡片**
 * （显示错数字比不显示更糟）。所以任何一条解析不通过，一律返回 null 退回原文。
 *
 * 只做信息密度高、且数字是决策依据的命令；`ls` / `pip list` 这类不做卡片——渲染成
 * 卡片反而更难读（原文本来的列对齐就是最好的呈现）。
 */
object CommandInsightParser {

    fun parse(command: String, output: String): InsightCard? {
        if (output.isBlank()) return null
        val cmd = command.lowercase()
        return when {
            cmd.contains("nvidia-smi") -> parseNvidiaSmi(output)
            Regex("\\bdf\\b").containsMatchIn(cmd) -> parseDf(output)
            Regex("\\bfree\\b").containsMatchIn(cmd) -> parseFree(output)
            cmd.contains("ps ") && cmd.contains("grep") -> parsePs(output)
            // v0.2.84：`ls -l` 的 18 行里真正有用的是"有哪些名字"，权限/属主/日期
            // 全是噪音。注意仅对带 `-l` 的 ls 生效：纯 `ls` 本来就是简洁名单，
            // 再包一层卡片反而更难读（这是解析器原来的判断，对 `ls` 成立）。
            isLongListing(cmd) -> parseLsLong(output)
            else -> null
        }
    }

    /** 是不是 `ls -l` 这类长格式列表（带 `-l` / `-la` / `-lh` 等）。 */
    private fun isLongListing(cmd: String): Boolean =
        Regex("(^|\\s)ls\\s").containsMatchIn(cmd) &&
            Regex("(^|\\s)-[a-zA-Z]*l").containsMatchIn(cmd)

    // ===== nvidia-smi =====

    private val DRIVER = Regex("Driver Version:\\s*([\\d.]+)")
    private val CUDA = Regex("CUDA Version:\\s*([\\d.]+)")
    // GPU 行：`|   0  Tesla V100-SXM2-32GB           On  | ...`
    private val GPU_LINE = Regex("^\\|\\s+(\\d+)\\s+(.+?)\\s+(On|Off|N/A)\\s+\\|", RegexOption.MULTILINE)
    private val MEM = Regex("(\\d+)\\s*MiB\\s*/\\s*(\\d+)\\s*MiB")
    private val TEMP = Regex("(\\d+)C\\s+P\\d")
    private val POWER = Regex("(\\d+)W\\s*/\\s*(\\d+)W")
    private val UTIL = Regex("\\|\\s*(\\d+)%\\s+(?:Default|N/A|P\\d)")
    private val PROC = Regex(
        "^\\|\\s+\\d+\\s+N/A\\s+N/A\\s+(\\d+)\\s+[CG]\\s+(\\S.*?)\\s+(\\d+)MiB",
        RegexOption.MULTILINE,
    )

    private fun parseNvidiaSmi(output: String): InsightCard? {
        // 没有显存行就不是我们认识的 nvidia-smi 输出（报错、空表、格式变了）→ 退回原文。
        val memMatches = MEM.findAll(output).toList()
        if (memMatches.isEmpty()) return null

        val gpuNames = GPU_LINE.findAll(output).map { it.groupValues[2].trim() }.toList()
        val util = UTIL.find(output)?.groupValues?.get(1)
        val temp = TEMP.find(output)?.groupValues?.get(1)
        val power = POWER.find(output)

        val bars = memMatches.mapIndexed { index, m ->
            val used = m.groupValues[1].toDoubleOrNull() ?: return null
            val total = m.groupValues[2].toDoubleOrNull() ?: return null
            if (total <= 0) return null
            // 单位统一成 MiB → GiB 展示。
            val label = if (memMatches.size > 1) "显存 GPU$index" else "显存"
            InsightBar(
                label = label,
                used = used,
                total = total,
                ratio = (used / total).coerceIn(0.0, 1.0),
                display = "${round1(used / 1024.0)} / ${round1(total / 1024.0)} GB",
            )
        }

        val metrics = buildList {
            // v0.2.83：只留手机玩家真正看的三个。驱动版本 / CUDA / 功耗不显示——
            // "显存 0、利用率 0% 却耗 72W"会让用户困惑，而这两项与"能不能跑模型"无关。
            util?.let { add(InsightMetric("利用率", "$it%")) }
            temp?.let { add(InsightMetric("温度", "${it}°C")) }
        }

        val rows = PROC.findAll(output).map { m ->
            InsightRow(
                primary = m.groupValues[2].trim(),
                secondary = "PID ${m.groupValues[1]}",
                trailing = "${m.groupValues[3]} MiB",
            )
        }.toList()

        val title = when {
            gpuNames.isEmpty() -> "GPU"
            gpuNames.size == 1 -> "GPU  ${gpuNames.first()}"
            else -> "GPU  ${gpuNames.first()} 等 ${gpuNames.size} 张卡"
        }
        // v0.2.84：显存为 0 但整卡利用率不低——数字看着矛盾，不解释一句用户会以为
        // App 算错了。云端 GPU 可能被同机其他实例共享，nvidia-smi 看到的是整卡状态。
        val usedMiB = memMatches.firstOrNull()?.groupValues?.get(1)?.toDoubleOrNull()
        val utilPercent = util?.toIntOrNull()
        val note = if ((usedMiB ?: 0.0) <= 0.0 && (utilPercent ?: 0) > 5) {
            "本容器未占用显存，但整卡利用率 $utilPercent%——可能是同机其他实例在用。"
        } else {
            ""
        }
        return InsightCard(
            title = title,
            bars = bars,
            metrics = metrics,
            rows = rows.take(5),
            rowsTitle = if (rows.isEmpty()) "" else "占用进程",
            note = note,
        )
    }

    // ===== ls -l =====

    /**
     * `ls -l` 行：权限 链接数 属主 属组 大小 月 日 (时间|年) 名称。
     * 名称可能含空格（截到行尾）；时间列可能是 `10:00`（半年内）或 `2024`（更早），两种都要认。
     */
    private val LS_LONG_LINE = Regex(
        "^([-dlbcps][rwxstST-]{9})\\s+\\d+\\s+\\S+\\s+\\S+\\s+\\d+\\s+\\S+\\s+\\d+\\s+\\S+\\s+(.+)$",
    )

    private fun parseLsLong(output: String): InsightCard? {
        val entries = output.lines().mapNotNull { raw ->
            val line = raw.trim()
            if (line.isBlank() || line.startsWith("total ")) return@mapNotNull null
            val m = LS_LONG_LINE.find(line) ?: return@mapNotNull null
            val perms = m.groupValues[1]
            val name = m.groupValues[2].trim()
            if (name.isBlank()) return@mapNotNull null
            // 符号链接 `name -> target`：只留名字，链接指向是次要信息。
            val displayName = name.substringBefore(" -> ").trim()
            // 目录名后加 `/`：一眼区分目录与文件（不需要额外列）。
            val isDir = perms.startsWith("d")
            (if (isDir) "$displayName/" else displayName) to isDir
        }
        if (entries.isEmpty()) return null

        val dirs = entries.count { it.second }
        val files = entries.size - dirs
        val title = buildString {
            append("共 ${entries.size} 项")
            if (dirs > 0 && files > 0) append("（$dirs 目录 / $files 文件）")
            else if (dirs == entries.size) append("目录")
            else if (files == entries.size) append("文件")
        }
        return InsightCard(
            title = title,
            rows = entries.take(20).map { InsightRow(primary = it.first) },
            rowsTitle = "名称",
        )
    }

    // ===== df -h =====

    private val DF_LINE = Regex("^(\\S+)\\s+([\\d.]+[KMGTP]?)\\s+([\\d.]+[KMGTP]?)\\s+([\\d.]+[KMGTP]?)\\s+(\\d+)%\\s+(\\S+)")

    private fun parseDf(output: String): InsightCard? {
        val entries = output.lines().mapNotNull { line ->
            val m = DF_LINE.find(line.trim()) ?: return@mapNotNull null
            val size = humanToBytes(m.groupValues[2]) ?: return@mapNotNull null
            val used = humanToBytes(m.groupValues[3]) ?: return@mapNotNull null
            // v0.2.84：用 df 自己的 **Avail** 列，不能用 size - used。
            // ext4 默认给 root 留 5% 保留块，于是 Used + Avail ≠ Size——减法必然高估。
            // 实测差 50 倍：`/` 真剩 0.1G，减出来的"剩 5.0G"。而守则要求 AI 拿
            // 卡片上的余量去对照下载体积——这个数字错了就直接导致判断错。
            val avail = humanToBytes(m.groupValues[4]) ?: return@mapNotNull null
            val percent = m.groupValues[5].toDoubleOrNull() ?: return@mapNotNull null
            // 伪文件系统（体积解析不出来或为 0）跳过——它们不占实际磁盘。
            if (size <= 0) return@mapNotNull null
            Quad(m.groupValues[1], m.groupValues[6], size, used, avail, percent)
        }
        if (entries.isEmpty()) return null

        val bars = entries.take(6).map { e ->
            InsightBar(
                label = e.mount,
                used = e.used,
                total = e.size,
                // v0.2.84：进度条用 df 自己的 Use% 列，而不是 used/size。
                // 两者在保留块存在时不一致，用后者会让进度条与文字对不上。
                ratio = (e.percent / 100.0).coerceIn(0.0, 1.0),
                display = "剩余 ${human(e.avail)}",
                secondary = "共 ${human(e.size)}，已用 ${human(e.used)}（${e.percent.toInt()}%）",
            )
        }
        return InsightCard(title = "磁盘用量", bars = bars)
    }

    private data class Quad(
        val filesystem: String,
        val mount: String,
        val size: Double,
        val used: Double,
        /** df 的 Avail 列（真实可用，已扣保留块）。 */
        val avail: Double,
        val percent: Double,
    )

    // ===== free -h =====

    private val FREE_MEM = Regex("^Mem:\\s+([\\d.]+[KMGTP]?i?)\\s+([\\d.]+[KMGTP]?i?)\\s+([\\d.]+[KMGTP]?i?)", RegexOption.MULTILINE)

    private fun parseFree(output: String): InsightCard? {
        val m = FREE_MEM.find(output) ?: return null
        val total = humanToBytes(m.groupValues[1]) ?: return null
        val used = humanToBytes(m.groupValues[2]) ?: return null
        val free = humanToBytes(m.groupValues[3]) ?: return null
        if (total <= 0) return null
        return InsightCard(
            title = "内存",
            bars = listOf(
                InsightBar(
                    label = "内存",
                    used = used,
                    total = total,
                    ratio = (used / total).coerceIn(0.0, 1.0),
                    display = "${human(used)} / ${human(total)}",
                ),
            ),
            metrics = listOf(InsightMetric("空闲", human(free))),
        )
    }

    // ===== ps aux | grep =====

    private fun parsePs(output: String): InsightCard? {
        val rows = output.lines().mapNotNull { raw ->
            val line = raw.trim()
            if (line.isBlank() || line.startsWith("USER")) return@mapNotNull null
            val parts = line.split(Regex("\\s+"), limit = 11)
            // ps aux 格式：USER PID %CPU %MEM VSZ RSS TTY STAT START TIME COMMAND
            if (parts.size < 11) return@mapNotNull null
            val pid = parts[1].toIntOrNull() ?: return@mapNotNull null
            // 过滤 grep 自身（命令行里带 grep 的进程）。
            if (parts[10].contains("grep")) return@mapNotNull null
            InsightRow(
                primary = parts[10].take(60),
                secondary = "PID $pid",
                trailing = "${parts[2]}% CPU",
            )
        }
        if (rows.isEmpty()) return null
        return InsightCard(title = "相关进程", rows = rows.take(5), rowsTitle = "进程")
    }

    // ===== 小工具 =====

    private fun round1(value: Double): String {
        val rounded = Math.round(value * 10.0) / 10.0
        return if (rounded == rounded.toLong().toDouble()) rounded.toLong().toString() else rounded.toString()
    }

    /** `1.5G` / `512M` / `1024` → 字节数。识别不出返回 null。 */
    private fun humanToBytes(raw: String): Double? {
        val text = raw.trim().trimEnd('i', 'B', 'b')
        if (text.isBlank()) return null
        val unit = text.lastOrNull() ?: return null
        val multiplier = when (unit) {
            'K', 'k' -> 1024.0
            'M', 'm' -> 1024.0 * 1024
            'G', 'g' -> 1024.0 * 1024 * 1024
            'T', 't' -> 1024.0 * 1024 * 1024 * 1024
            'P', 'p' -> 1024.0 * 1024 * 1024 * 1024 * 1024
            else -> return text.toDoubleOrNull()
        }
        val number = text.dropLast(1).toDoubleOrNull() ?: return null
        return number * multiplier
    }

    private fun human(bytes: Double): String {
        val units = listOf("B", "K", "M", "G", "T", "P")
        var value = bytes
        var index = 0
        while (value >= 1024 && index < units.lastIndex) {
            value /= 1024
            index += 1
        }
        val rounded = Math.round(value * 10.0) / 10.0
        val shown = if (rounded == rounded.toLong().toDouble()) rounded.toLong().toString() else rounded.toString()
        return "$shown${units[index]}"
    }
}
