package com.local.comfyuimobile.bridge

import org.json.JSONObject

/**
 * 向 API prompt 注入批量出图数量。
 *
 * ComfyUI 中 KSampler 与 EmptyLatentImage 都有 batch_size 输入，两者必须一致，
 * 否则 latent 尺寸不匹配会报错。因此这里同时修改所有 KSampler 类节点和
 * EmptyLatentImage 节点；工作流里一个都没有时返回 false，由调用方给出明确提示。
 */
object PromptBatch {
    fun inject(prompt: JSONObject, batchSize: Int): Boolean {
        if (batchSize <= 1) return true
        val keys = mutableListOf<String>()
        val keyIterator = prompt.keys()
        while (keyIterator.hasNext()) keys.add(keyIterator.next())
        var count = 0
        for (key in keys) {
            val node = prompt.optJSONObject(key) ?: continue
            val classType = node.optString("class_type")
            val isSampler = classType.contains("Sampler", ignoreCase = true)
            val isLatentSource = classType.equals("EmptyLatentImage", ignoreCase = true)
            if (!isSampler && !isLatentSource) continue
            val inputs = node.optJSONObject("inputs") ?: continue
            inputs.put("batch_size", batchSize)
            count++
        }
        return count > 0
    }
}
