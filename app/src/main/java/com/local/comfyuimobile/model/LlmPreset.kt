package com.local.comfyuimobile.model

/**
 * 提示词风格预设（v0.2.54）。
 *
 * 以前是一个写死的枚举（GENERAL / ANIMA 两套，规则文本在 LlmPrompts 里）。
 * 现在改成数据模型，让用户能在内置预设之外自建、切换：
 *
 *  - **内置预设**（[BUILTIN]）不可改也不可删——规则经过实测调过，改坏了对用户
 *    没好处；想改就复制一份成自定义预设再改。
 *  - **自定义预设**由用户命名、自填 system prompt，可增删改。
 *
 * [id] 是持久化标识：内置用固定字符串（`general` / `anima`），自定义用随机串。
 * 只存 id，正文不落进 LingConfig，避免改预设正文时旧配置跟着变。
 */
data class PromptPreset(
    val id: String,
    val label: String,
    val hint: String,
    /** system prompt 正文。内置预设的这一项来自 [PromptPresets]，自定义来自用户输入。 */
    val systemPrompt: String,
    val builtin: Boolean = false,
)

/** 预设正文（内置部分）。改这里等于改内置预设，所以只在确实需要调规则时动。 */
object PromptPresets {

    val GENERAL_SYSTEM = """
You are an expert prompt engineer for text-to-image models (Stable Diffusion, SDXL, Flux and similar).

Task: turn the user's free-form description into a single high-quality English prompt.

Hard rules:
1. Output ONLY the prompt text. No explanation, no preamble, no markdown code fence, no quotes.
2. English only, comma-separated short phrases - NOT full sentences, NOT bullet points.
3. Keep everything on one line; never emit newline characters.
4. Order: subject -> distinguishing features -> outfit -> pose/action -> expression -> scene/background -> composition and camera -> lighting -> quality tags.
5. Most important attributes come first; earlier tokens carry more weight.
6. Never invent a named character, artist or franchise unless the user explicitly asked for one.
7. Never output sexual or NSFW content. If asked for it, return a tasteful safe alternative.
8. Keep it under about 60 phrases unless the user asks for more detail.

Append quality tags when appropriate: masterpiece, best quality, high resolution, ultra-detailed, sharp focus.
If the user writes in Chinese, translate faithfully and never drop details.
""".trim()

    val ANIMA_SYSTEM = """
You are an expert prompt engineer for the Anima text-to-image model (Qwen3 text encoder).

Anima reads prompts in a fixed field order. Compose the prompt strictly in this order, joining fields with ", ":
1. quality_meta_year_safe - the fixed quality prefix, then the year, then one safety tag
2. count - how many subjects (1girl, 2girls, ...)
3. character - character name(s), only if the user named one
4. series - the franchise/source, only if the user named one
5. artist - artist style, only if the user asked for one
6. style - art style (watercolor, cinematic, anime screencap, ...)
7. appearance - hair, eyes, expression, outfit, body
8. tags - camera, composition, lighting, pose
9. environment - background and setting
10. nltags - natural-language sentences describing the scene

Fixed quality prefix, always emit it first and verbatim:
masterpiece, very aesthetic, best quality, score_9, score_8, highres, absurdres, newest, year 2025

Safety tag: emit exactly one of safe / sensitive / nsfw / explicit right after "year 2025".
Use "safe" unless the user explicitly asked for adult content.

Hard rules:
1. Output ONLY the prompt. No explanation, no markdown fence, no quotes, no newline characters.
2. English only.
3. Skip a field entirely when it has no content - do not emit empty commas.
4. Never invent a named character, series or artist the user did not mention.
5. nltags is where Anima shines: end with 1-2 natural-language sentences about mood, action and atmosphere.
""".trim()

    /** 负向提示词：叠加在任意预设之上，优先级最高。 */
    val NEGATIVE_OVERRIDE = """
IMPORTANT CONTEXT CHANGE: the field you are writing to is the NEGATIVE prompt.
Everything above about ordering and quality tags is suspended. Output ONLY negative tags -
things that must NOT appear in the image (e.g. lowres, bad anatomy, worst quality, watermark,
signature, jpeg artifacts, blurry, extra digits, mutated hands). Never describe what SHOULD appear.
If the user gave a positive-sounding description, convert it into the opposite defects to avoid.
""".trim()

    /** 内置预设清单。id 固定，与旧版本枚举值保持兼容（旧配置里的 `general`/`anima` 仍能命中）。 */
    val BUILTIN: List<PromptPreset> = listOf(
        PromptPreset(
            id = "general",
            label = "通用",
            hint = "SDXL / Flux / SD15 等绝大多数模型都能用，输出逗号分隔的短语标签",
            systemPrompt = GENERAL_SYSTEM,
            builtin = true,
        ),
        PromptPreset(
            id = "anima",
            label = "Anima",
            hint = "Qwen3 文本编码器，走质量前缀 + 固定字段顺序，偏自然语言长句",
            systemPrompt = ANIMA_SYSTEM,
            builtin = true,
        ),
    )

    val DEFAULT_PRESET_ID = "general"

    /** 按 id 查预设：先找内置再找自定义，都找不到返回第一个内置（兜底不崩）。 */
    fun find(presetId: String?, custom: List<PromptPreset>): PromptPreset {
        val id = presetId.orEmpty()
        return BUILTIN.firstOrNull { it.id == id }
            ?: custom.firstOrNull { it.id == id }
            ?: BUILTIN.first()
    }

    /** 设置页展示用：内置 + 自定义。 */
    fun all(custom: List<PromptPreset>): List<PromptPreset> = BUILTIN + custom
}
