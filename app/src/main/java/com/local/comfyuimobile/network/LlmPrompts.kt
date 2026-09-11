package com.local.comfyuimobile.network

import com.local.comfyuimobile.model.AiAssistMode
import com.local.comfyuimobile.model.LlmPreset

/**
 * v0.1.88：AI 提示词助手的 system prompt。
 *
 * 这些文本是"规则"而不是"代码"，所以直接内联；其中 ANIMA 那段摘自
 * comfyui-good-anima 的 `comfyui-animatool/SKILL.md`（那个仓库只有 Markdown
 * 文档，没有可嵌入的库，把规则搬过来就是它能提供的全部价值）。
 */
object LlmPrompts {

    private val GENERAL = """
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

    private val ANIMA = """
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

    private val NEGATIVE_OVERRIDE = """
IMPORTANT CONTEXT CHANGE: the field you are writing to is the NEGATIVE prompt.
Everything above about ordering and quality tags is suspended. Output ONLY negative tags -
things that must NOT appear in the image (e.g. lowres, bad anatomy, worst quality, watermark,
signature, jpeg artifacts, blurry, extra digits, mutated hands). Never describe what SHOULD appear.
If the user gave a positive-sounding description, convert it into the opposite defects to avoid.
""".trim()

    fun systemPrompt(preset: LlmPreset, isNegative: Boolean): String {
        val base = when (preset) {
            LlmPreset.GENERAL -> GENERAL
            LlmPreset.ANIMA -> ANIMA
        }
        return if (isNegative) "$base\n\n$NEGATIVE_OVERRIDE" else base
    }

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
