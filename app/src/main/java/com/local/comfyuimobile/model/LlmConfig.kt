package com.local.comfyuimobile.model

/**
 * v0.1.88：AI 提示词助手所用的外部大模型配置。
 *
 * 刻意做成"OpenAI 兼容端点"这一种形态：市面上绝大多数服务（OpenAI、DeepSeek、
 * 智谱、硅基流动、各种中转站、本地 ollama / llama.cpp）都走 `/v1/chat/completions`，
 * 填三个字段就能用，不必为每家单独写一套请求。
 */
data class LlmConfig(
    val baseUrl: String = "",
    val apiKey: String = "",
    val model: String = "",
    val preset: LlmPreset = LlmPreset.GENERAL,
    val temperature: Float = DEFAULT_TEMPERATURE,
) {
    /**
     * 接口地址与模型名齐全即视为可用。
     * apiKey **允许留空** —— 本地 ollama / llama.cpp 之类根本不需要鉴权，
     * 强行要求填反而把这类用户挡在门外。
     */
    fun isConfigured(): Boolean = baseUrl.isNotBlank() && model.isNotBlank()

    companion object {
        const val DEFAULT_TEMPERATURE = 0.9f
        private val TEMPERATURE_RANGE = 0f..2f

        fun normalizeTemperature(value: Float): Float =
            if (value.isNaN()) DEFAULT_TEMPERATURE else value.coerceIn(TEMPERATURE_RANGE)
    }
}

/**
 * 提示词风格预设，只影响喂给大模型的 system prompt。
 *
 * ANIMA 那套规则取自 comfyui-good-anima 的 `comfyui-animatool/SKILL.md`：
 * 组装顺序 `quality_meta_year_safe → count → character → series → artist →
 * style → appearance → tags → environment → nltags`，外加固定的质量前缀。
 * 该项目本身没有可执行代码（只是一组 Skill 文档），所以这里把规则文本搬过来，
 * 而不是"引入依赖"。
 */
enum class LlmPreset(val id: String, val label: String, val hint: String) {
    GENERAL(
        id = "general",
        label = "通用",
        hint = "SDXL / Flux / SD15 等绝大多数模型都能用，输出逗号分隔的短语标签",
    ),
    ANIMA(
        id = "anima",
        label = "Anima",
        hint = "Qwen3 文本编码器，走质量前缀 + 固定字段顺序，偏自然语言长句",
    ),
    ;

    companion object {
        fun fromId(id: String?): LlmPreset = entries.firstOrNull { it.id == id } ?: GENERAL
    }
}

/** AI 助手这一次的动作。 */
enum class AiAssistMode(val label: String, val hint: String) {
    GENERATE("生成", "按你的描述从零写一段提示词，替换当前内容"),
    POLISH("润色", "保留原意，把当前提示词改得更专业、更有效"),
    APPEND("追加", "在现有提示词后面追加内容，原文不动"),
}

/** 当前正在被 AI 编辑的字段，以及它属于参数页还是快捷页。 */
enum class AiAssistScope { PARAM, QUICK }

data class AiAssistTarget(
    val fieldKey: String,
    val label: String,
    val isNegative: Boolean,
    val scope: AiAssistScope,
)
