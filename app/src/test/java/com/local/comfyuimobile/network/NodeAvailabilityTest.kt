package com.local.comfyuimobile.network

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NodeAvailabilityTest {

    private val catalog = setOf(
        "CheckpointLoaderSimple",
        "CLIPTextEncode",
        "KSampler",
        "VAEDecode",
        "SaveImage",
        "LoraLoader",
    )

    // ===== 清单提取 =====

    @Test
    fun `从 object_info 提取类型名`() {
        val json = JSONObject()
            .put("KSampler", JSONObject().put("input", JSONObject()))
            .put("CLIPTextEncode", JSONObject().put("input", JSONObject()))
            .toString()
        assertEquals(setOf("KSampler", "CLIPTextEncode"), NodeAvailability.parseCatalog(json))
    }

    @Test
    fun `空对象视为查不到而不是查到 0 个`() {
        assertNull(NodeAvailability.parseCatalog("{}"))
    }

    @Test
    fun `非 JSON 返回 null`() {
        assertNull(NodeAvailability.parseCatalog("<html>502</html>"))
    }

    @Test
    fun `合法 JSON 数组也返回 null 而不是崩掉`() {
        assertNull(NodeAvailability.parseCatalog("[]"))
    }

    // ===== 缺失比对 =====

    @Test
    fun `全部装齐时不报缺失`() {
        val used = listOf("CheckpointLoaderSimple", "KSampler", "SaveImage")
        assertEquals(emptyList<String>(), NodeAvailability.findMissing(used, catalog))
    }

    @Test
    fun `缺什么报什么且按字典序`() {
        val used = listOf("KSampler", "AnimaTeaCache", "FLS_SamplerV4", "AnimaBoosterLoader")
        assertEquals(
            listOf("AnimaBoosterLoader", "AnimaTeaCache", "FLS_SamplerV4"),
            NodeAvailability.findMissing(used, catalog),
        )
    }

    @Test
    fun `清单为 null 时一律不报`() {
        val used = listOf("AnimaBoosterLoader", "FLS_SamplerV4")
        assertEquals(emptyList<String>(), NodeAvailability.findMissing(used, null))
    }

    @Test
    fun `清单为空集合时同样不报`() {
        assertEquals(emptyList<String>(), NodeAvailability.findMissing(listOf("X"), emptySet()))
    }

    @Test
    fun `重复类型只报一次`() {
        val used = listOf("Ghost", "Ghost", "Ghost")
        assertEquals(listOf("Ghost"), NodeAvailability.findMissing(used, catalog))
    }

    @Test
    fun `空白类型名被忽略`() {
        val used = listOf("", "   ", "Ghost")
        assertEquals(listOf("Ghost"), NodeAvailability.findMissing(used, catalog))
    }

    @Test
    fun `前后空格不影响匹配`() {
        val used = listOf("  KSampler  ")
        assertEquals(emptyList<String>(), NodeAvailability.findMissing(used, catalog))
    }

    @Test
    fun `前端专属节点不报`() {
        val used = listOf("Note", "MarkdownNote", "Reroute", "PrimitiveNode")
        assertEquals(emptyList<String>(), NodeAvailability.findMissing(used, catalog))
    }

    @Test
    fun `前端专属与真缺失混在一起时只报真的`() {
        val used = listOf("Note", "AnimaBoosterLoader")
        assertEquals(listOf("AnimaBoosterLoader"), NodeAvailability.findMissing(used, catalog))
    }

    // ===== 文案 =====

    @Test
    fun `没有缺失时不给文案`() {
        assertNull(NodeAvailability.describe(emptyList()))
    }

    @Test
    fun `文案列出节点名并说明后果`() {
        val text = NodeAvailability.describe(listOf("AnimaBoosterLoader", "FLS_SamplerV4"))!!
        assertTrue(text.contains("AnimaBoosterLoader"))
        assertTrue(text.contains("FLS_SamplerV4"))
        assertTrue(text.contains("失败"))
    }

    @Test
    fun `超过上限时用等多少个收口`() {
        val many = List(9) { "Missing$it" }
        val text = NodeAvailability.describe(many)!!
        assertTrue(text.contains("等 9 个"))
    }

    @Test
    fun `恰好等于上限时不加收口提示`() {
        val exact = List(5) { "Missing$it" }
        val text = NodeAvailability.describe(exact)!!
        assertTrue(!text.contains("等 5 个"))
    }
}
