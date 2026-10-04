package com.local.comfyuimobile.data

import com.local.comfyuimobile.model.ParameterField
import com.local.comfyuimobile.model.ParameterSection
import org.json.JSONObject
import kotlin.math.abs

object WorkflowPolicy {
    /**
     * 草稿与服务器工作流的“节点重合比例”下限。
     *
     * v0.2.81：从 0.5 降到 0.2。以前 0.5 会把"大改造"误判成"混入了别的工作流"——
     * 例如把 10 节点的 SDXL 工作流改成 SD1.5：保留 4 个原节点、新增 6 个，覆盖率
     * 4/10=0.4 < 0.5，用户的高级编辑被当作垃圾丢弃。这个检查要防的是"草稿文件
     * 混进了别的工作流的数据"，而草稿 key 是 serverUrl + workflowPath 的哈希，
     * 撞车概率极低；真正需要防的"服务器被其他设备改了"已由 hasModifiedConflict
     * （基于 modified 时间戳）在做。降到 0.2 保留"完全不是同一个工作流"的兜底。
     */
    private const val DRAFT_STRUCTURE_MIN_COVERAGE = 0.2

    fun hasModifiedConflict(loadedModified: Double, serverModified: Double?): Boolean =
        serverModified != null && abs(serverModified - loadedModified) > 0.001

    /**
     * Extracts the (node id -> type) map of a workflow JSON. Both the canvas
     * format ({nodes:[...]}) and the API prompt format ({id:{class_type,...}})
     * are supported; otherwise returns null.
     */
    fun workflowNodeSignature(workflowJson: String): Map<String, String>? = runCatching {
        val root = JSONObject(workflowJson)
        val nodes = root.optJSONArray("nodes")
        if (nodes != null) {
            buildMap {
                repeat(nodes.length()) { index ->
                    val node = nodes.getJSONObject(index)
                    put(node.optString("id"), node.optString("type"))
                }
            }
        } else {
            // API prompt 格式：{ "3": {"class_type":"KSampler", ...}, ... }
            val keys = root.keys()
            if (!keys.hasNext()) return null
            buildMap {
                while (keys.hasNext()) {
                    val id = keys.next()
                    val entry = root.optJSONObject(id) ?: continue
                    val classType = entry.optString("class_type")
                    if (classType.isBlank()) return null
                    put(id, classType)
                }
            }
        }
    }.getOrNull()

    /**
     * Fraction of the server workflow's (node id -> type) pairs that still exist
     * in the draft. Legitimate edits keep it near 1.0; a draft that accidentally
     * contains another workflow's JSON drops it towards 0.
     */
    fun draftStructureCoverage(draftJson: String, serverJson: String): Double {
        val draftNodes = workflowNodeSignature(draftJson) ?: return 0.0
        val serverNodes = workflowNodeSignature(serverJson) ?: return 0.0
        if (serverNodes.isEmpty()) return 1.0
        val matched = serverNodes.count { (id, type) -> draftNodes[id] == type }
        return matched.toDouble() / serverNodes.size
    }

    fun draftStructureMismatched(draftJson: String, serverJson: String): Boolean =
        draftStructureCoverage(draftJson, serverJson) < DRAFT_STRUCTURE_MIN_COVERAGE

    fun writeMobileLayout(workflow: JSONObject, fields: List<ParameterField>): JSONObject {
        val extra = workflow.optJSONObject("extra") ?: JSONObject().also { workflow.put("extra", it) }
        val mobile = extra.optJSONObject("comfyMobile") ?: JSONObject().also { extra.put("comfyMobile", it) }
        val values = mobile.optJSONObject("fields") ?: JSONObject().also { mobile.put("fields", it) }
        fields.forEach { field ->
            values.put(
                field.key,
                JSONObject()
                    .put("label", field.label)
                    .put("visible", field.visible)
                    .put("section", if (field.section == ParameterSection.PRIMARY) "primary" else "more")
                    .put("order", field.order),
            )
        }
        mobile.put("schema", 1)
        return workflow
    }
}
