package com.local.comfyuimobile.mcp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 文件端点登记的 TTL 与命名（v0.2.85）。
 *
 * TTL 是这条链路唯一的安全边界之一（另一个是绑 loopback）：一个不过期的
 * `/files/{id}` 等于把出图结果永久挂在可读位置。
 */
class McpFileStoreTest {

    @Test
    fun storesAndRetrievesBytes() {
        val store = McpFileStore(now = { 1_000 })
        val id = store.put(byteArrayOf(1, 2, 3), "png", "image/png")
        val entry = store.get(id)
        assertNotNull(entry)
        assertEquals(listOf<Byte>(1, 2, 3), entry!!.bytes.toList())
        assertEquals("image/png", entry.contentType)
    }

    @Test
    fun idIsUnguessableAndKeepsExtension() {
        val store = McpFileStore(now = { 1_000 })
        val first = store.put(byteArrayOf(1), "png", "image/png")
        val second = store.put(byteArrayOf(1), "png", "image/png")
        // 32 位十六进制 + ".png"
        assertTrue(first.endsWith(".png"))
        assertTrue("id 必须是随机值，不能可猜", first != second)
        assertTrue(Regex("^[0-9a-f]{32}\\.png$").matches(first))
    }

    @Test
    fun entryExpiresAfterTtl() {
        var clock = 1_000L
        val store = McpFileStore(ttlMillis = 500, now = { clock })
        val id = store.put(byteArrayOf(1), "png", "image/png")
        clock = 1_400L
        assertNotNull("未到期应还能取", store.get(id))
        clock = 1_501L
        assertNull("到期后必须失效", store.get(id))
    }

    @Test
    fun evictExpiredDropsOldEntries() {
        var clock = 0L
        val store = McpFileStore(ttlMillis = 100, now = { clock })
        store.put(byteArrayOf(1), "png", "image/png")
        clock = 200L
        store.evictExpired()
        assertEquals(0, store.size())
    }

    @Test
    fun maxEntriesEvictsOldest() {
        var clock = 0L
        val store = McpFileStore(ttlMillis = 10_000, maxEntries = 2, now = { clock })
        val first = store.put(byteArrayOf(1), "png", "image/png")
        clock = 10
        store.put(byteArrayOf(2), "png", "image/png")
        clock = 20
        store.put(byteArrayOf(3), "png", "image/png")
        assertNull("最早的条目应被挤出", store.get(first))
        assertEquals(2, store.size())
    }

    @Test
    fun contentTypeIsInferredFromExtension() {
        assertEquals("image/png", McpFileStore.contentTypeOf("png"))
        assertEquals("image/jpeg", McpFileStore.contentTypeOf("JPG"))
        assertEquals("image/webp", McpFileStore.contentTypeOf("webp"))
        assertEquals("video/mp4", McpFileStore.contentTypeOf("mp4"))
        assertEquals("application/octet-stream", McpFileStore.contentTypeOf("bin"))
    }
}
