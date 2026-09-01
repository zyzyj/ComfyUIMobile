package com.local.comfyuimobile.bridge

import org.json.JSONObject

/**
 * 向 API prompt 注入批量出图数量。
 *
 * v0.1.71 修正：ComfyUI 里真正决定"一次出几张"的是 **latent 源节点**（EmptyLatentImage
 * 一类）的 batch_size。对照官方源码（nodes.py）核实过：KSampler、KSamplerAdvanced、
 * SamplerCustom 这些采样器的输入定义里**根本没有 batch_size**——这个类原来的注释
 * "KSampler 与 EmptyLatentImage 都有 batch_size" 是想当然的。
 *
 * 以前按"名字里含不含 Sampler"去猜（子串匹配），后果有两个，且都和直觉相反：
 * 1. 名字里带 Sampler 的非采样器（KSamplerModel、各种第三方节点）会被写进一个
 *    它不认识的字段。服务端并不会报错——execution.py 只读取节点声明过的输入，
 *    多出来的字段直接忽略——所以是**静默**写出垃圾字段，还随 prompt 一起存进
 *    history 和 PNG 元数据，用户完全无感。
 * 2. `count > 0` 被当成"批量注入成功"，可真正干活的只有 latent 源。
 *
 * 现在的命中规则（两条满足其一即写）：
 * 1. 节点的 inputs 里**本来就带 batch_size**——最可靠，未来任何新的 latent 源节点
 *    自动支持，不用改代码；
 * 2. 名字形如 Empty*Latent*（EmptyLatentImage / EmptySD3LatentImage / EmptyFlux2LatentImage）
 *    ——兜住个别工作流里该字段没出现在 inputs 的情况，保持与旧行为的兼容。
 *
 * 一个都命中不了时返回 false，由调用方给出明确提示。
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
            val inputs = node.optJSONObject("inputs") ?: continue
            val classType = node.optString("class_type")
            val hasBatchInput = inputs.has("batch_size")
            val isLatentSource = classType.startsWith("Empty", ignoreCase = true) &&
                classType.contains("Latent", ignoreCase = true)
            if (!hasBatchInput && !isLatentSource) continue
            inputs.put("batch_size", batchSize)
            count++
        }
        return count > 0
    }
}
