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

    @Test fun injectsIntoLatentSourceOnly() {
        // KSampler 没有 batch_size 输入（官方 nodes.py 里根本没有这个字段），
        // 旧实现按名字含 "Sampler" 乱写，字段被服务端静默忽略——纯属白写。
        val prompt = promptWith("3" to "KSampler", "9" to "EmptyLatentImage", "4" to "CLIPTextEncode")
        assertTrue(PromptBatch.inject(prompt, 4))
        assertEquals(4, prompt.getJSONObject("9").getJSONObject("inputs").getInt("batch_size"))
        // 采样器与无关节点都不能被写入垃圾字段。
        assertFalse(prompt.getJSONObject("3").getJSONObject("inputs").has("batch_size"))
        assertFalse(prompt.getJSONObject("4").getJSONObject("inputs").has("batch_size"))
    }

    @Test fun samplersWithSamplerInNameAreNeverTouched() {
        // KSamplerAdvanced / SamplerCustom 同样没有 batch_size 输入；
        // 名字里带 "Sampler" 的第三方节点（如 KSamplerModel）也不该被波及。
        val prompt = promptWith("1" to "KSamplerAdvanced", "2" to "SamplerCustom", "7" to "KSamplerModel")
        assertFalse(PromptBatch.inject(prompt, 2))
        assertFalse(prompt.getJSONObject("1").getJSONObject("inputs").has("batch_size"))
        assertFalse(prompt.getJSONObject("2").getJSONObject("inputs").has("batch_size"))
        assertFalse(prompt.getJSONObject("7").getJSONObject("inputs").has("batch_size"))
    }

    @Test fun nodesWithActualBatchSizeInputAreInjected() {
        // 命中规则第一条：节点 inputs 里本来就有 batch_size，不管它叫什么名字都支持，
        // 未来新增的 latent 源节点无需改代码。
        val prompt = promptWith("6" to "SomeFutureLatentNode")
        prompt.getJSONObject("6").getJSONObject("inputs").put("batch_size", 1)
        assertTrue(PromptBatch.inject(prompt, 3))
        assertEquals(3, prompt.getJSONObject("6").getJSONObject("inputs").getInt("batch_size"))
    }

    @Test fun emptyLatentImageVariantsAreCovered() {
        // 命中规则第二条：Empty*Latent* 家族兜底，避免个别工作流里字段缺失时整个功能失效。
        val prompt = promptWith("1" to "EmptySD3LatentImage", "2" to "EmptyFlux2LatentImage", "3" to "EmptyLatentImageLarge")
        assertTrue(PromptBatch.inject(prompt, 2))
        assertEquals(2, prompt.getJSONObject("1").getJSONObject("inputs").getInt("batch_size"))
        assertEquals(2, prompt.getJSONObject("2").getJSONObject("inputs").getInt("batch_size"))
        assertEquals(2, prompt.getJSONObject("3").getJSONObject("inputs").getInt("batch_size"))
    }

    @Test fun nonLatentEmptyNodesAreIgnored() {
        // "Empty" 开头但和 latent 无关的节点（如 EmptyImage）不在兜底范围里。
        val prompt = promptWith("1" to "EmptyImage", "2" to "EmptyCheckpoint")
        assertFalse(PromptBatch.inject(prompt, 2))
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
