package com.local.comfyuimobile.bridge

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PromptBatchTest {
    private fun promptWith(vararg pairs: Pair<String, String>): JSONObject {
        val root = JSONObject()
        pairs.forEach { (id, type) ->
            root.put(id, JSONObject().put("class_type", type).put("inputs", JSONObject()))
        }
        return root
    }

    @Test fun injectsIntoKSamplerAndEmptyLatentImage() {
        val prompt = promptWith("3" to "KSampler", "9" to "EmptyLatentImage", "4" to "CLIPTextEncode")
        assertTrue(PromptBatch.inject(prompt, 4))
        assertEquals(4, prompt.getJSONObject("3").getJSONObject("inputs").getInt("batch_size"))
        assertEquals(4, prompt.getJSONObject("9").getJSONObject("inputs").getInt("batch_size"))
        // 无关节点不被修改。
        assertFalse(prompt.getJSONObject("4").getJSONObject("inputs").has("batch_size"))
    }

    @Test fun matchesKSamplerAdvancedAndSamplerCustom() {
        val prompt = promptWith("1" to "KSamplerAdvanced", "2" to "SamplerCustom")
        assertTrue(PromptBatch.inject(prompt, 2))
        assertEquals(2, prompt.getJSONObject("1").getJSONObject("inputs").getInt("batch_size"))
        assertEquals(2, prompt.getJSONObject("2").getJSONObject("inputs").getInt("batch_size"))
    }

    @Test fun returnsFalseWhenNoBatchableNodeExists() {
        val prompt = promptWith("4" to "CLIPTextEncode", "5" to "VAEDecode")
        assertFalse(PromptBatch.inject(prompt, 4))
    }

    @Test fun batchSizeOneLeavesPromptUntouched() {
        val prompt = promptWith("3" to "KSampler")
        assertTrue(PromptBatch.inject(prompt, 1))
        assertFalse(prompt.getJSONObject("3").getJSONObject("inputs").has("batch_size"))
    }
}
