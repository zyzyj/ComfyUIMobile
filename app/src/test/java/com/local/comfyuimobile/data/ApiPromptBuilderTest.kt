package com.local.comfyuimobile.data

import com.local.comfyuimobile.model.ParameterField
import com.local.comfyuimobile.model.ParameterKind
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ApiPromptBuilderTest {

    private val base = JSONObject(
        """
        {
          "74": {"class_type": "EmptyLatentImage",
                 "inputs": {"width": 920, "height": 1536, "batch_size": 1}},
          "79": {"class_type": "KSamplerAdvanced",
                 "inputs": {"noise_seed": 111, "steps": 24, "cfg": 4,
                            "sampler_name": "euler_ancestral",
                            "model": ["94", 0], "latent_image": ["74", 0]}},
          "92": {"class_type": "LoraLoader",
                 "inputs": {"lora_name": "old.safetensors", "strength_model": 1, "strength_clip": 1,
                            "model": ["78", 0], "clip": ["71", 0]}},
          "94": {"class_type": "LoraLoader",
                 "inputs": {"lora_name": "keep.safetensors", "strength_model": 1, "strength_clip": 1,
                            "model": ["93", 0], "clip": ["93", 1]}}
        }
        """.trimIndent(),
    )

    private fun field(
        nodeId: String,
        name: String,
        newValueJson: String,
        originalValueJson: String? = null,
    ) = ParameterField(
        key = "$nodeId::$name",
        nodeId = nodeId,
        nodeTitle = "t",
        nodeType = "T",
        name = name,
        label = name,
        widgetType = "",
        kind = ParameterKind.TEXT,
        valueJson = newValueJson,
        originalValueJson = originalValueJson ?: newValueJson,
        displayValue = newValueJson,
    )

    @Test
    fun writesBackChangedFieldsOnly() {
        val result = ApiPromptBuilder.applyFields(
            base,
            listOf(
                field("79", "noise_seed", "999", originalValueJson = "111"),
                field("79", "steps", "24"), // 未改动
            ),
        )
        val prompt = JSONObject(result.promptJson)
        assertEquals(1, result.appliedCount)
        assertEquals(999L, prompt.getJSONObject("79").getJSONObject("inputs").getLong("noise_seed"))
        // 未改动的字段保持原值
        assertEquals(24, prompt.getJSONObject("79").getJSONObject("inputs").getInt("steps"))
        // 没出现在 fields 里的节点不受影响
        assertEquals("keep.safetensors", prompt.getJSONObject("94").getJSONObject("inputs").getString("lora_name"))
    }

    @Test
    fun keepsNumericTypesAndQuotesStrings() {
        val result = ApiPromptBuilder.applyFields(
            base,
            listOf(
                field("79", "cfg", "7.5", originalValueJson = "4"),
                field("79", "sampler_name", "\"dpmpp_2m\"", originalValueJson = "\"euler_ancestral\""),
            ),
        )
        val inputs = JSONObject(result.promptJson).getJSONObject("79").getJSONObject("inputs")
        assertEquals(7.5, inputs.getDouble("cfg"), 0.0001)
        assertEquals("dpmpp_2m", inputs.getString("sampler_name"))
    }

    @Test
    fun neverMutatesTheOriginalPrompt() {
        val snapshot = base.toString()
        ApiPromptBuilder.applyFields(
            base,
            listOf(field("79", "noise_seed", "42", originalValueJson = "111")),
            4,
        )
        assertEquals("原始 prompt 必须保持原样", snapshot, base.toString())
    }

    @Test
    fun injectsBatchSizeIntoBothSamplerAndLatent() {
        val result = ApiPromptBuilder.applyFields(base, emptyList(), 4)
        val prompt = JSONObject(result.promptJson)
        assertTrue(result.batchApplied)
        assertEquals(4, prompt.getJSONObject("79").getJSONObject("inputs").getInt("batch_size"))
        assertEquals(4, prompt.getJSONObject("74").getJSONObject("inputs").getInt("batch_size"))
    }

    @Test
    fun batchSizeIsIgnoredWhenWorkflowCannotBatch() {
        val noSampler = JSONObject("""{"1":{"class_type":"VAELoader","inputs":{"vae_name":"v.safetensors"}}}""")
        val result = ApiPromptBuilder.applyFields(noSampler, emptyList(), 4)
        assertFalse("没有 KSampler/EmptyLatentImage 时应报告未命中", result.batchApplied)
    }

    @Test
    fun leavesLinkInputsUntouched() {
        val result = ApiPromptBuilder.applyFields(base, emptyList())
        val inputs = JSONObject(result.promptJson).getJSONObject("79").getJSONObject("inputs")
        // model 是连线 ["94",0]，必须原样保留数组而不是被当成标量
        assertEquals("94", inputs.getJSONArray("model").getString(0))
        assertEquals(0, inputs.getJSONArray("model").getInt(1))
    }

    @Test
    fun rewritesLoraNameAndStrength() {
        val updated = ApiPromptBuilder.applyLora(
            base,
            mapOf(
                "92" to ApiPromptBuilder.LoraUpdate(
                    loraName = "new.safetensors",
                    strengthModel = 0.65,
                    strengthClip = 0.8,
                ),
            ),
        )
        val inputs = updated.getJSONObject("92").getJSONObject("inputs")
        assertEquals("new.safetensors", inputs.getString("lora_name"))
        assertEquals(0.65, inputs.getDouble("strength_model"), 0.0001)
        assertEquals(0.8, inputs.getDouble("strength_clip"), 0.0001)
        // 其他槽位不受影响
        assertEquals("keep.safetensors", updated.getJSONObject("94").getJSONObject("inputs").getString("lora_name"))
        // 原始对象未被修改
        assertEquals("old.safetensors", base.getJSONObject("92").getJSONObject("inputs").getString("lora_name"))
    }

    @Test
    fun skipsMissingLoraSlotQuietly() {
        val updated = ApiPromptBuilder.applyLora(
            base,
            mapOf("999" to ApiPromptBuilder.LoraUpdate(loraName = "x.safetensors")),
        )
        assertFalse("不存在的槽位不应被创建", updated.has("999"))
        assertEquals(base.length(), updated.length())
    }

    @Test
    fun ignoresMalformedValueJson() {
        val result = ApiPromptBuilder.applyFields(
            base,
            listOf(field("79", "cfg", "{{not json", originalValueJson = "4")),
        )
        assertEquals("非法值应跳过而不是写坏 prompt", 0, result.appliedCount)
        assertEquals(4, JSONObject(result.promptJson).getJSONObject("79").getJSONObject("inputs").getInt("cfg"))
    }
}
