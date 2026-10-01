package com.local.comfyuimobile.network

import com.local.comfyuimobile.model.AiAssistMode
import com.local.comfyuimobile.model.PromptPreset
import com.local.comfyuimobile.model.PromptPresets

/**
 * v0.1.88：AI 提示词助手的 system prompt。
 *
 * v0.2.54：预设正文搬到了 [PromptPresets]（改成了数据模型，用户可自建预设）；
 * 这里只负责把预设正文 + 负向规则 + **当前工作流上下文**拼成最终 system prompt。
 */
object LlmPrompts {

    /**
     * 当前工作流的上下文（v0.2.54）。
     *
     * 这是“让 AI 写得对味”的关键：同一个描述，在 Anima 工作流和 SDXL 工作流里
     * 应该产出不同的标签。以前预设是写死的死规则，AI 不知道你在用哪套模型，
     * 写出来自然时好时坏。
     */
    data class WorkflowContext(
        val checkpoint: String = "",
        val loras: List<String> = emptyList(),
    ) {
        val isEmpty: Boolean get() = checkpoint.isBlank() && loras.isEmpty()
    }

    fun systemPrompt(preset: PromptPreset, isNegative: Boolean, context: WorkflowContext? = null): String {
        val base = preset.systemPrompt.ifBlank { PromptPresets.GENERAL_SYSTEM }
        val withContext = buildString {
            append(base)
            context?.takeIf { !it.isEmpty }?.let { append("\n\n").append(contextBlock(it)) }
        }
        return if (isNegative) "$withContext\n\n${PromptPresets.NEGATIVE_OVERRIDE}" else withContext
    }

    /**
     * 把工作流信息写成一段供模型参考的说明。
     *
     * 只陈述事实，不下命令：模型自己决定怎么利用（例如避开与当前 LoRA 风格冲突的标签）。
     * 名称去掉 `.safetensors` 等后缀——模型看到裸名字更容易理解其含义。
     */
    private fun contextBlock(context: WorkflowContext): String = buildString {
        append("The user is generating with this exact setup:")
        if (context.checkpoint.isNotBlank()) {
            append("\n- Base model (checkpoint): ").append(shortModelName(context.checkpoint))
        }
        if (context.loras.isNotEmpty()) {
            append("\n- LoRAs loaded (in chain order): ")
            append(context.loras.joinToString(", ") { shortModelName(it) })
        }
        append("\nWrite tags that fit this setup. Do not emit tags for styles or concepts that conflict with these models.")
    }

    /** 去掉目录与常见权重后缀，只留可读名字。 */
    fun shortModelName(name: String): String =
        name.substringAfterLast('/').substringAfterLast('\\')
            .removeSuffix(".safetensors").removeSuffix(".sft")
            .removeSuffix(".ckpt").removeSuffix(".pt").removeSuffix(".gguf")

    /**
     * 把终端命令结果喂回模型时的前缀。 */
    fun commandResultPrefix(): String =
        "Here is the output of the command(s) you proposed:"

    /** 把用户的意图和当前提示词拼成一次 user 消息。 */
    fun userPrompt(mode: AiAssistMode, current: String, idea: String): String =
        buildString {
            when (mode) {
                AiAssistMode.GENERATE -> {
                    append("Write a prompt for this idea:\n").append(idea.trim())
                }
                AiAssistMode.POLISH -> {
                    append("Rewrite this prompt to be more effective, keeping its meaning:\n")
                    append(current.trim())
                    if (idea.isNotBlank()) append("\n\nExtra requirements:\n").append(idea.trim())
                }
                AiAssistMode.APPEND -> {
                    if (current.isNotBlank()) append("Existing prompt:\n").append(current.trim()).append("\n\n")
                    append("Append tags for this additional idea. Output ONLY the new part to append:")
                    append("\n").append(idea.trim())
                }
            }
        }
}
