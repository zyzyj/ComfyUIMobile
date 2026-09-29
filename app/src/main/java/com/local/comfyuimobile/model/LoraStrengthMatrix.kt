package com.local.comfyuimobile.model

/**
 * LoRA 强度矩阵（v0.2.52）：控制变量法批量测试。
 *
 * 与 v0.1.83「批量对比」的区别：
 *  - 批量对比轮换的是**LoRA 名字**（A → B → C），一次一个字段；
 *  - 本功能保持工作流里已选的 LoRA 组合不变，只让**某一个 LoRA 的强度**在区间内
 *    按间距递增（0.1、0.2 … 1.0），其余 LoRA 与所有其它参数完全一致。
 *    这样产出的图只差一个变量，才能直接横向对比。
 *
 * 纯 Kotlin：区间展开、上限、标签、任务展开都可脱离 Android SDK 单测。
 */
enum class LoraStrengthTarget { MODEL, CLIP, BOTH }

/** 一个可调强度的 LoRA 槽位（对应工作流里的一个 LoraLoader 节点）。 */
data class LoraStrengthSlot(
    val nodeId: String,
    val nodeTitle: String,
    val loraName: String,
    /** 参数 key：`<nodeId>::lora_name`。 */
    val nameFieldKey: String,
    /** 参数 key：`<nodeId>::strength_model`。 */
    val modelFieldKey: String,
    /** 参数 key：`<nodeId>::strength_clip`；节点没有该输入时为 null。 */
    val clipFieldKey: String?,
    val currentModel: Double,
    val currentClip: Double,
    /** 供 token 归一后的兜底判断。 */
    val isLoraLoader: Boolean = true,
) {
    val displayName: String get() = loraName.ifBlank { nodeTitle }
}

/** 某次矩阵测试的一条任务：一个槽位 + 一个强度值。 */
data class LoraMatrixTask(
    val slot: LoraStrengthSlot,
    val strength: Double,
    val target: LoraStrengthTarget,
) {
    /** 图上的标注，如 `角色A (model) 0.7`。 */
    val label: String get() {
        val scope = when (target) {
            LoraStrengthTarget.MODEL -> "model"
            LoraStrengthTarget.CLIP -> "clip"
            LoraStrengthTarget.BOTH -> "model+clip"
        }
        return "${shortName(slot.displayName)} · $scope ${formatStrength(strength)}"
    }

    private fun shortName(name: String): String =
        name.substringAfterLast('/').substringAfterLast('\\')
}

enum class StrengthPhase { RUNNING, PAUSED, DONE, CANCELLED }

/** 单条矩阵任务的结果（含失败原因与产出图）。 */
data class StrengthItemResult(
    val strength: Double,
    val target: LoraStrengthTarget,
    val slotNodeId: String,
    val slotName: String,
    val label: String,
    val promptId: String? = null,
    val success: Boolean = false,
    val message: String = "",
    val media: List<ResultMedia> = emptyList(),
    val elapsedMs: Long? = null,
)

/** 一次强度矩阵测试的完整状态（内存态：进程被杀即中断，已提交的服务器任务不受影响）。 */
data class LoraStrengthRun(
    val id: String,
    val workflowPath: String,
    val workflowName: String,
    val slotNodeId: String,
    val slotTitle: String,
    val target: LoraStrengthTarget,
    val seed: String,
    /** 被改动槽位的原始强度，用于结束后恢复。 */
    val originalModel: Double,
    val originalClip: Double,
    val pending: List<LoraMatrixTask> = emptyList(),
    val current: LoraMatrixTask? = null,
    val items: List<StrengthItemResult> = emptyList(),
    val phase: StrengthPhase = StrengthPhase.RUNNING,
    val startedAt: Long = 0L,
    val message: String = "",
    /** 开始时定格的计划总数：防呆上限，拦住任何"队列永不减少"的回归。 */
    val plannedTotal: Int = 0,
) {
    val total: Int get() = pending.size + items.size + (if (current != null) 1 else 0)
    val finished: Int get() = items.size
    val successCount: Int get() = items.count { it.success }
    val failedCount: Int get() = items.count { !it.success }
}

object LoraStrengthMatrix {

    /** 单次上限：与批量对比一致的量级，防误触跑一晚上。 */
    const val MAX_TASKS = 30

    /** 区间最多能展开出的档位数（护栏：0.1~1 间距 0.001 会炸出 901 档）。 */
    const val MAX_STEPS = 200

    /**
     * 把「起止值 + 间距」展开成档位列表。
     *
     * 用户输入 0.1~1 间距 0.1 → [0.1, 0.2, …, 1.0]（含结束值）。
     * 除不尽时（0.0~1.0 间距 0.3）最后一档就是最后一个不超过结束值的数（0.9），
     * 再补一次结束值——否则用户会觉得"我填了 1.0 怎么没跑到"。
     *
     * 间距 <= 0、起止非法、档位超 [MAX_STEPS] 都返回空列表，由界面提示用户。
     */
    fun expandSteps(start: Double, end: Double, step: Double): List<Double> {
        if (!start.isFinite() || !end.isFinite() || !step.isFinite()) return emptyList()
        if (step <= 0.0) return emptyList()
        if (end < start) return emptyList()
        val span = end - start
        val estimated = span / step
        if (estimated > MAX_STEPS) return emptyList()

        val values = mutableListOf<Double>()
        // 用乘法推进而不是累加：0.1 累加 10 次会变成 0.9999999999999999。
        var index = 0
        while (index <= MAX_STEPS) {
            val value = start + step * index
            if (value > end + EPSILON) break
            values += round(value)
            index += 1
        }
        // 结束值落在步进上（或极接近）时上面已经包含；差得远就补一档。
        val last = values.lastOrNull()
        if (last == null || kotlin.math.abs(last - end) > EPSILON) values += round(end)
        return values
    }

    /**
     * 生成任务列表：只让 targetSlot 变化，其余槽位保持工作流当前值。
     *
     * @param slots 工作流里全部 LoRA 槽位
     * @param targetSlotNodeId 被测试的槽位 id
     * @param strengths 由 [expandSteps] 展开的档位
     */
    fun buildTasks(
        slots: List<LoraStrengthSlot>,
        targetSlotNodeId: String,
        strengths: List<Double>,
        target: LoraStrengthTarget,
    ): List<LoraMatrixTask> {
        val slot = slots.firstOrNull { it.nodeId == targetSlotNodeId } ?: return emptyList()
        if (strengths.isEmpty()) return emptyList()
        if (target == LoraStrengthTarget.CLIP && slot.clipFieldKey == null) return emptyList()
        return strengths
            .take(MAX_TASKS)
            .map { LoraMatrixTask(slot = slot, strength = it, target = target) }
    }

    /**
     * 任务是否会超出单次上限。界面提前用它禁用「开始」并给出提示。
     */
    fun exceedsLimit(count: Int): Boolean = count > MAX_TASKS

    /** 档位数的展示文案：`0.1, 0.2, 0.3 … 1.0（共 10 档）`。 */
    fun describeSteps(strengths: List<Double>): String {
        if (strengths.isEmpty()) return "无有效档位"
        val head = strengths.take(4).joinToString(", ") { formatStrength(it) }
        val tail = if (strengths.size > 4) " … ${formatStrength(strengths.last())}" else ""
        return "$head$tail（共 ${strengths.size} 档）"
    }

    /** 图上标注用的数值：整数去掉小数点，其余保留两位。 */
    fun formatStrength(value: Double): String {
        val rounded = round(value)
        return if (rounded == rounded.toLong().toDouble()) {
            rounded.toLong().toString()
        } else {
            "%.2f".format(rounded)
        }
    }

    private fun round(value: Double): Double =
        kotlin.math.round(value * 1000.0) / 1000.0

    private const val EPSILON = 1e-9
}
