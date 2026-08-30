package com.local.comfyuimobile.data

import com.local.comfyuimobile.model.ParameterKind
import com.local.comfyuimobile.model.ParameterSection
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 用真实的 Anima API 格式工作流验证解析器。
 *
 * 这个文件来自用户实际的 animamor+lora.json：UNETLoader + CLIPLoader 分别提供
 * model/clip，三个 LoraLoader 串联（92→93→94），链尾接到 KSamplerAdvanced
 * 与两个 CLIPTextEncode，最后 SaveImage。它正是「前端转换失败」的那个文件。
 */
class ApiPromptParserTest {

    private val animaPrompt = """
    {
      "71": {"inputs": {"clip_name": "qwen_3_06b_base.safetensors", "type": "qwen_image", "device": "default"},
             "class_type": "CLIPLoader", "_meta": {"title": "加载CLIP"}},
      "72": {"inputs": {"vae_name": "qwen_image_vae.safetensors"},
             "class_type": "VAELoader", "_meta": {"title": "加载VAE"}},
      "73": {"inputs": {"samples": ["79", 0], "vae": ["72", 0]},
             "class_type": "VAEDecode", "_meta": {"title": "VAE解码"}},
      "74": {"inputs": {"width": 920, "height": 1536, "batch_size": 1},
             "class_type": "EmptyLatentImage", "_meta": {"title": "空Latent图像"}},
      "75": {"inputs": {"text": "worst quality, low quality", "clip": ["94", 1]},
             "class_type": "CLIPTextEncode", "_meta": {"title": "CLIP Text Encode (Negative Prompt)"}},
      "77": {"inputs": {"text": "masterpiece, best quality, 1girl", "clip": ["94", 1]},
             "class_type": "CLIPTextEncode", "_meta": {"title": "CLIP Text Encode (Positive Prompt)"}},
      "78": {"inputs": {"unet_name": "miaomiaoHarem_anima14.safetensors", "weight_dtype": "default"},
             "class_type": "UNETLoader", "_meta": {"title": "UNet加载器"}},
      "79": {"inputs": {"add_noise": "enable", "noise_seed": 1017571273864125, "steps": 24, "cfg": 4,
                        "sampler_name": "euler_ancestral", "scheduler": "normal",
                        "start_at_step": 0, "end_at_step": 24,
                        "return_with_leftover_noise": "disable",
                        "model": ["94", 0], "positive": ["77", 0], "negative": ["75", 0],
                        "latent_image": ["74", 0]},
             "class_type": "KSamplerAdvanced", "_meta": {"title": "K采样器（高级）"}},
      "83": {"inputs": {"filename_prefix": "ComfyUI", "images": ["73", 0]},
             "class_type": "SaveImage", "_meta": {"title": "保存图像"}},
      "92": {"inputs": {"lora_name": "ANI-Anima_colorfix_v1_by_Volnovik.safetensors",
                        "strength_model": 1, "strength_clip": 1,
                        "model": ["78", 0], "clip": ["71", 0]},
             "class_type": "LoraLoader", "_meta": {"title": "加载LoRA"}},
      "93": {"inputs": {"lora_name": "ANI-fymriev6-2.safetensors", "strength_model": 1, "strength_clip": 1,
                        "model": ["92", 0], "clip": ["92", 1]},
             "class_type": "LoraLoader", "_meta": {"title": "加载LoRA"}},
      "94": {"inputs": {"lora_name": "ANI-BlueArchiveStyleB1.safetensors", "strength_model": 1, "strength_clip": 1,
                        "model": ["93", 0], "clip": ["93", 1]},
             "class_type": "LoraLoader", "_meta": {"title": "加载LoRA"}}
    }
    """.trimIndent()

    private val objectInfo = """
    {
      "KSamplerAdvanced": {"input": {"required": {
        "model": ["MODEL", {}], "positive": ["CONDITIONING", {}], "negative": ["CONDITIONING", {}],
        "latent_image": ["LATENT", {}],
        "add_noise": [["enable", "disable"], {}],
        "noise_seed": ["INT", {"default": 0, "min": 0, "max": 4294967295}],
        "steps": ["INT", {"default": 20, "min": 1, "max": 10000}],
        "cfg": ["FLOAT", {"default": 8.0, "min": 0.0, "max": 100.0, "step": 0.1}],
        "sampler_name": [["euler", "euler_ancestral", "dpmpp_2m"], {}],
        "scheduler": [["normal", "karras", "exponential"], {}]
      }}},
      "CLIPTextEncode": {"input": {"required": {
        "text": ["STRING", {"multiline": true}], "clip": ["CLIP", {}]
      }}},
      "LoraLoader": {"input": {"required": {
        "model": ["MODEL", {}], "clip": ["CLIP", {}],
        "lora_name": [["a.safetensors", "b.safetensors"], {}],
        "strength_model": ["FLOAT", {"default": 1.0, "min": -20.0, "max": 20.0, "step": 0.01}],
        "strength_clip": ["FLOAT", {"default": 1.0, "min": -20.0, "max": 20.0, "step": 0.01}]
      }}}
    }
    """.trimIndent()

    private fun parse() = ApiPromptParser.parse(JSONObject(animaPrompt), JSONObject(objectInfo))

    @Test
    fun readsEveryNodeAndResolvesFullExecutionChain() {
        val result = parse()
        assertEquals("应读出全部 12 个节点", 12, result.nodes.size)
        assertEquals("SaveImage 是唯一输出节点", listOf("83"), result.outputNodeIds)
        // 从 SaveImage 回溯应覆盖全部节点（所有东西都在输出链上）
        assertEquals(12, result.executionChain.size)
        assertTrue("执行链应包含 KSamplerAdvanced", result.executionChain.contains("79"))
        assertTrue("执行链应包含 CLIPLoader", result.executionChain.contains("71"))
    }

    @Test
    fun executionChainPutsUpstreamBeforeDownstream() {
        val chain = parse().executionChain
        fun idx(id: String) = chain.indexOf(id)
        // 92 依赖 78/71，93 依赖 92，94 依赖 93，79 依赖 94
        assertTrue("UNETLoader 必须在 LoraLoader(92) 之前", idx("78") < idx("92"))
        assertTrue("CLIPLoader 必须在 LoraLoader(92) 之前", idx("71") < idx("92"))
        assertTrue("LoraLoader 92→93→94 必须串联有序",
            idx("92") < idx("93") && idx("93") < idx("94"))
        assertTrue("LoraLoader(94) 必须在 KSampler 之前", idx("94") < idx("79"))
        assertTrue("KSampler 必须在 VAEDecode 之前", idx("79") < idx("73"))
        assertTrue("VAEDecode 必须在 SaveImage 之前", idx("73") < idx("83"))
    }

    @Test
    fun identifiesThreeChainedLoraSlots() {
        val slots = parse().loraSlots
        assertEquals("应有 3 个 LoRA 槽位", 3, slots.size)
        assertEquals(listOf("92", "93", "94"), slots.map { it.nodeId })
        assertEquals("ANI-Anima_colorfix_v1_by_Volnovik.safetensors", slots[0].loraName)
        assertEquals("ANI-BlueArchiveStyleB1.safetensors", slots[2].loraName)
        // 第一个槽位直接从加载器取，后两个串联前一个 LoRA 的输出
        assertEquals("78", slots[0].modelSource)
        assertEquals("71", slots[0].clipSource)
        assertEquals("92", slots[1].modelSource)
        assertEquals("92", slots[1].clipSource)
        assertEquals("93", slots[2].modelSource)
    }

    @Test
    fun loraModelAndClipComeFromDifferentLoaders() {
        val slots = parse().loraSlots
        val head = slots.first()
        assertTrue(
            "Anima 架构下 model 与 clip 来自不同节点（非 CheckpointLoaderSimple）",
            head.modelSource != head.clipSource,
        )
    }

    @Test
    fun extractsWidgetParametersWithoutObjectInfo() {
        // 没有 object_info 时也要能提取参数，只是类型判断退化
        val result = ApiPromptParser.parse(JSONObject(animaPrompt), null)
        val keys = result.fields.map { it.key }.toSet()
        assertTrue("应提取到正向提示词", keys.contains("77::text"))
        assertTrue("应提取到负向提示词", keys.contains("75::text"))
        assertTrue("应提取到种子", keys.contains("79::noise_seed"))
        assertTrue("应提取到 LoRA 名称", keys.contains("92::lora_name"))
        // 连线输入不能出现
        assertTrue("连线输入不能被当成参数", keys.none { it == "79::model" })
        assertTrue("连线输入不能被当成参数", keys.none { it == "83::images" })
    }

    @Test
    fun appliesObjectInfoTypesAndRanges() {
        val fields = parse().fields
        val seed = fields.first { it.key == "79::noise_seed" }
        assertEquals(ParameterKind.INTEGER, seed.kind)
        assertEquals(0.0, seed.minimum!!, 0.001)

        val cfg = fields.first { it.key == "79::cfg" }
        assertEquals(ParameterKind.DECIMAL, cfg.kind)
        assertEquals(0.0, cfg.minimum!!, 0.001)
        assertEquals(0.1, cfg.step!!, 0.001)

        val sampler = fields.first { it.key == "79::sampler_name" }
        assertEquals(ParameterKind.COMBO, sampler.kind)
        assertEquals(listOf("euler", "euler_ancestral", "dpmpp_2m"), sampler.options)

        val loraName = fields.first { it.key == "92::lora_name" }
        assertEquals(ParameterKind.COMBO, loraName.kind)
    }

    @Test
    fun classifiesPrimaryParametersIntoPrimarySection() {
        val fields = parse().fields
        // KSampler 的参数和提示词属于主区
        assertTrue("种子应在主区", fields.first { it.key == "79::noise_seed" }.section == ParameterSection.PRIMARY)
        assertTrue("提示词应在主区", fields.first { it.key == "77::text" }.section == ParameterSection.PRIMARY)
        assertEquals(ParameterKind.MULTILINE, fields.first { it.key == "77::text" }.kind)
    }

    @Test
    fun valueJsonRoundTripsBackToOriginalValue() {
        val fields = parse().fields
        val text = fields.first { it.key == "77::text" }
        assertEquals("masterpiece, best quality, 1girl", parseJsonString(text.valueJson))
        val seed = fields.first { it.key == "79::noise_seed" }
        assertEquals(1017571273864125L, seed.valueJson.toLong())
        val negative = fields.first { it.key == "75::text" }
        assertEquals("worst quality, low quality", parseJsonString(negative.valueJson))
    }

    private fun parseJsonString(json: String): String = JSONObject("{\"v\":$json}").optString("v")

    @Test
    fun ignoresNonPromptJsonGracefully() {
        val result = ApiPromptParser.parse(JSONObject("{\"nodes\":[]}"), null)
        assertTrue(result.nodes.isEmpty())
        assertTrue(result.executionChain.isEmpty())
        assertTrue(result.fields.isEmpty())
        assertNotNull(result.loraSlots)
    }

    @Test
    fun handlesPromptWithoutOutputNode() {
        // 只有加载器、没有输出节点：执行链应为空而不是崩溃
        val prompt = JSONObject("""{"1":{"class_type":"UNETLoader","inputs":{"unet_name":"a.safetensors"}}}""")
        val result = ApiPromptParser.parse(prompt, null)
        assertEquals(1, result.nodes.size)
        assertTrue("没有输出节点时执行链应为空", result.executionChain.isEmpty())
        assertNull(result.fields.firstOrNull { it.nodeId == "1" && it.name == "unet_name" })
    }
}
