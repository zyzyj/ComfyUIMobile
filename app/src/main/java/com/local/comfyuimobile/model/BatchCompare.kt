package com.local.comfyuimobile.model

/**
 * v0.1.83 批量 LoRA 对比：数据模型与纯逻辑。
 *
 * 设计要点（对应《批量 LoRA 对比》v1 设计定稿）：
 *  - 逐个提交：引擎一次只提交一张，跑完确认后再提交下一张（MainViewModel.batchLoop）。
 *  - 固定种子：批量开始时把种子策略临时切到 FIXED，结束恢复。
 *  - 失败不卡队列：单张失败重试一次，仍失败就跳过记原因，继续下一张。
 *  - 连续失败自动暂停：连续 3 张失败（无论原因）自动暂停，防止云端断线/会话过期
 *    时把所有候选烧成一排失败记录。
 *  - 适配预检只做"启发式标黄"：按文件名关键词猜基模型家族，疑似不匹配默认不勾选，
 *    但不阻止勾选——标黄是提示不是禁令，ComfyUI 端不匹配的 LoRA 多数是静默无效
 *    （key 匹配不上直接忽略，图照出但没有效果），所以试错兜底依然保留。
 */
enum class BatchPhase { RUNNING, PAUSED, DONE, CANCELLED }

/** 单张结果：loraName 对应一个提交任务。 */
data class BatchItemResult(
    val loraName: String,
    val promptId: String? = null,
    val success: Boolean = false,
    val message: String = "",
    val media: List<ResultMedia> = emptyList(),
    val elapsedMs: Long? = null,
)

/** 一次批量对比的完整状态（内存态：进程被杀批次中断，v1 已知限制）。 */
data class BatchRun(
    val id: String,
    val workflowPath: String,
    val workflowName: String,
    val loraFieldKey: String,
    val loraNodeTitle: String,
    val seed: String,
    val originalLoraValue: String,
    val pending: List<String> = emptyList(),
    val current: String? = null,
    val items: List<BatchItemResult> = emptyList(),
    val phase: BatchPhase = BatchPhase.RUNNING,
    val startedAt: Long = 0L,
    val message: String = "",
    /** v0.1.84：开始时定格的候选总数。防呆上限——完成数达到它就强制收工，
     *  拦住任何"pending 永不减少"的回归（v0.1.83 现场无限刷图 20/25 还在涨）。 */
    val plannedTotal: Int = 0,
) {
    /** 总张数（已跑 + 正在跑 + 待跑）。 */
    val total: Int get() = pending.size + items.size + (if (current != null) 1 else 0)

    val finished: Int get() = items.size

    val successCount: Int get() = items.count { it.success }

    val failedCount: Int get() = items.count { !it.success }
}

object BatchCompareLogic {

    /** 候选上限：防误触全选跑一晚上。 */
    const val MAX_CANDIDATES = 30

    /** 连续失败达到该次数自动暂停。 */
    const val AUTO_PAUSE_AFTER_FAILURES = 3

    // ===== v0.1.84：状态转移纯函数 =====
    //
    // v0.1.83 的事故：引擎循环里 pending.first() 取了候选却从没把它移出队列，
    // 同一个 LoRA 被无限重跑（现场日志 1/6 → 20/25 还在涨，全是同一张图）。
    // 根因是把状态机藏在协程里、纯逻辑又只测了计数属性。现在把"队列推进 /
    // 单张完成 / 防呆上限"抽成纯函数并用单测锁死不变量。

    /** 取下一个候选：pending 首项 → current，并从 pending 移除。队列空返回 null。 */
    fun advanceQueue(run: BatchRun): BatchRun? {
        if (run.pending.isEmpty()) return null
        return run.copy(current = run.pending.first(), pending = run.pending.drop(1))
    }

    /** 单张完成：current 归档进 items。 */
    fun completeItem(run: BatchRun, result: BatchItemResult): BatchRun =
        run.copy(items = run.items + result, current = null)

    /** 防呆：完成数达到计划总数（plannedTotal > 0 时启用）。 */
    fun reachedPlannedCap(run: BatchRun): Boolean =
        run.plannedTotal in 1..run.items.size

    /**
     * 基模型家族关键词 → 归一家族名。
     *
     * 归一规则吸收了同家族的常见别名：
     *  - pony / illustrious / noobai / xl 都是 SDXL 系
     *  - anima 是 Qwen 图像架构（CircleStone Labs，文本编码器走 Qwen3），qwen LoRA 与
     *    anima checkpoint 视为同家族
     *  - sd15 的各种写法归一成 sd15
     */
    private val familyKeywords: List<Pair<String, String>> = listOf(
        "sdxl" to "sdxl",
        "sd_xl" to "sdxl",
        "sd-xl" to "sdxl",
        "pony" to "sdxl",
        "illustrious" to "sdxl",
        "noobai" to "sdxl",
        "xl" to "sdxl",
        "sd15" to "sd15",
        "sd_15" to "sd15",
        "sd1.5" to "sd15",
        "sd_1.5" to "sd15",
        "v1-5" to "sd15",
        "v1_5" to "sd15",
        "flux" to "flux",
        "qwen" to "qwen",
        "anima" to "qwen",
        "sd3" to "sd3",
        "sd_3" to "sd3",
        "sd3.5" to "sd3",
    )

    /**
     * 启发式判断 LoRA 是否疑似与 checkpoint 不匹配。
     *
     * 只有当两边都能识别出家族、且家族无交集时才标黄：
     *  - checkpoint 名识别不出家族（如 "anima_base_v1" 之外的纯代号）→ 不标（信息不足）
     *  - LoRA 名识别不出家族（大量 Civitai 文件名不带基模型词）→ 不标
     * 识别不出就不猜——标黄是提示，宁可漏报不可误报。
     */
    fun suspectIncompatible(checkpointName: String?, loraName: String): Boolean {
        if (checkpointName.isNullOrBlank()) return false
        val ckptFamilies = familiesOf(checkpointName)
        if (ckptFamilies.isEmpty()) return false
        val loraFamilies = familiesOf(loraName)
        if (loraFamilies.isEmpty()) return false
        return loraFamilies.intersect(ckptFamilies).isEmpty()
    }

    private fun familiesOf(name: String): Set<String> {
        val lower = name.lowercase()
        return familyKeywords
            .filter { (keyword, _) -> containsToken(lower, keyword) }
            .map { (_, family) -> family }
            .toSet()
    }

    /** 词边界匹配：关键词前后不能紧邻字母或数字（"xl" 不能命中 "exlsx"）。 */
    private fun containsToken(text: String, keyword: String): Boolean {
        var index = text.indexOf(keyword)
        while (index >= 0) {
            val beforeOk = index == 0 || !text[index - 1].isLetterOrDigit()
            val after = index + keyword.length
            val afterOk = after >= text.length || !text[after].isLetterOrDigit()
            if (beforeOk && afterOk) return true
            index = text.indexOf(keyword, index + 1)
        }
        return false
    }

    /** 候选截断 + 去空 + 去重，防止误触全选跑一晚上。 */
    fun truncate(candidates: List<String>): List<String> =
        candidates.filter { it.isNotBlank() }.distinct().take(MAX_CANDIDATES)

    /** 连续失败达到阈值 → 自动暂停。 */
    fun shouldAutoPause(consecutiveFailures: Int): Boolean =
        consecutiveFailures >= AUTO_PAUSE_AFTER_FAILURES

    /**
     * 耗时预估文案。平均值取不到（还没有成功任务）返回 null，由 UI 决定展示什么。
     */
    fun estimateMinutes(count: Int, avgItemMillis: Long?): Int? {
        if (count <= 0) return null
        val avg = avgItemMillis?.takeIf { it > 0 } ?: return null
        val minutes = (count * avg + 59_999L) / 60_000L
        return minutes.toInt().coerceAtLeast(1)
    }
}
