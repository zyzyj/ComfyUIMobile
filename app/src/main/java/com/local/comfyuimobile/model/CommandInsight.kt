package com.local.comfyuimobile.model

/**
 * 命令输出的可视化卡片模型（v0.2.82）。
 *
 * 目的：`nvidia-smi` / `df -h` 这类输出信息量大但**关键数字只有一两个**，
 * 一大段等宽文本要自己找。解析成卡片后"显存还剩多少""磁盘够不够"一眼可读。
 *
 * 三条设计原则（决定实现边界）：
 *  1. **卡片与原文并存**：卡片在上、原文仍可展开。绝不只给卡片——解析错了用户还有原文。
 *  2. **解析失败就返回 null 退回原文**，不猜、不硬套。
 *  3. 解析器是纯函数、有单测——输出格式随驱动/系统版本变化，没测试锁住就是静默错误。
 */
data class InsightCard(
    /** 卡片标题，如 "GPU  Tesla V100-SXM2-32GB"。 */
    val title: String,
    /** 进度条指标（显存、磁盘用量这类"一眼看够不够"的）。 */
    val bars: List<InsightBar> = emptyList(),
    /** 并排展示的小指标（利用率/温度/功耗这类）。 */
    val metrics: List<InsightMetric> = emptyList(),
    /** 附加的明细行（占用进程、挂载点等）。 */
    val rows: List<InsightRow> = emptyList(),
    /** 明细区的标题，如"占用进程"。为空则不显示。 */
    val rowsTitle: String = "",
    /**
     * 卡片底部的一句说明（v0.2.84）。
     *
     * 用在"数字看着矛盾、需要解释一句"的场合——如显存 0 但整卡利用率 50%
     * （同机其他实例在用）。不加说明用户会以为 App 算错了。
     */
    val note: String = "",
)

/** 一条进度条：`used / total`，比例由 [ratio] 给出（0~1）。 */
data class InsightBar(
    val label: String,
    val used: Double,
    val total: Double,
    val ratio: Double,
    /** 主数字（v0.2.83：磁盘这里是"剩余 xxG"，直接回答"够不够"）。 */
    val display: String,
    /**
     * 副行（v0.2.83）：总量 / 已用 / 百分比这类参考信息放这里，小字弱化。
     * 主数字只放用户真正要用的那个（磁盘=剩余），不逼他心算。
     */
    val secondary: String = "",
)

/** 一个并排小指标：`label  value`。 */
data class InsightMetric(val label: String, val value: String)

/** 一行明细，可选前缀（如进程 PID）。 */
data class InsightRow(val primary: String, val secondary: String = "", val trailing: String = "")
