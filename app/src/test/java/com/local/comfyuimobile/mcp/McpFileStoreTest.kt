package com.local.comfyuimobile.mcp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * 文件登记处的 TTL 与淘汰（v0.2.85）。
 *
 * TTL 是这条链路唯一的安全边界之一（另一个是绑 loopback）：一个不过期的
 * `/files/{id}` 等于把出图结果永久挂在可读位置。
 *
 * 这里存的是**磁盘文件**而非内存字节，所以同时要盯住"过期/淘汰时必须把文件删掉"——
 * 否则淘汰只从 Map 里移走、磁盘上却越堆越多。
 */
class McpFileStoreTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private fun store(
        ttlMillis: Long = 60_000,
        maxEntries: Int = 64,
        maxTotalBytes: Long = 1024 * 1024,
        now: () -> Long = { 1_000L },
    ) = McpFileStore(
        cacheDir = File(tempFolder.root, "mcp_files"),
        ttlMillis = ttlMillis,
        maxEntries = maxEntries,
        maxTotalBytes = maxTotalBytes,
        now = now,
    )

    private fun McpFileStore.write(bytes: ByteArray, extension: String = "png"): String {
        val file = newFile(extension)
        file.writeBytes(bytes)
        return register(file, extension, McpFileStore.contentTypeOf(extension))
    }

    @Test
    fun storesAndRetrievesFile() {
        val store = store()
        val id = store.write(byteArrayOf(1, 2, 3))
        val entry = store.get(id)
        assertNotNull(entry)
        assertEquals(listOf<Byte>(1, 2, 3), entry!!.file.readBytes().toList())
        assertEquals("image/png", entry.contentType)
    }

    @Test
    fun idIsUnguessableAndKeepsExtension() {
        val store = store()
        val first = store.write(byteArrayOf(1))
        val second = store.write(byteArrayOf(1))
        assertTrue(first.endsWith(".png"))
        assertTrue("id 必须是随机值，不能可猜", first != second)
        assertTrue(Regex("^[0-9a-f]{32}\\.png$").matches(first))
    }

    @Test
    fun entryExpiresAfterTtl() {
        var clock = 1_000L
        val store = store(ttlMillis = 500, now = { clock })
        val id = store.write(byteArrayOf(1))
        clock = 1_400L
        assertNotNull("未到期应还能取", store.get(id))
        clock = 1_501L
        assertNull("到期后必须失效", store.get(id))
    }

    @Test
    fun expiredEntryDeletesItsFile() {
        var clock = 0L
        val store = store(ttlMillis = 100, now = { clock })
        val id = store.write(byteArrayOf(1))
        val file = store.get(id)!!.file
        clock = 200L
        store.get(id)
        assertFalse("过期条目必须把文件一起删掉，否则磁盘只增不减", file.exists())
    }

    @Test
    fun maxEntriesEvictsOldestAndDeletesIt() {
        var clock = 0L
        val store = store(ttlMillis = 10_000, maxEntries = 2, now = { clock })
        val first = store.write(byteArrayOf(1))
        val firstFile = store.get(first)!!.file
        clock = 10
        store.write(byteArrayOf(2))
        clock = 20
        store.write(byteArrayOf(3))
        assertNull("最早的条目应被挤出", store.get(first))
        assertFalse("被淘汰的文件也要删", firstFile.exists())
        assertEquals(2, store.size())
    }

    @Test
    fun totalBytesLimitEvictsOldest() {
        var clock = 0L
        // 每条 10 字节，总上限 25 字节 → 最多留 2 条。
        val store = store(ttlMillis = 10_000, maxTotalBytes = 25, now = { clock })
        val first = store.write(ByteArray(10))
        clock = 10
        store.write(ByteArray(10))
        clock = 20
        store.write(ByteArray(10))
        assertNull("超总字节上限时挤掉最旧的", store.get(first))
        assertEquals(2, store.size())
    }

    @Test
    fun rejectsPathTraversalIds() {
        // id 直接来自 URL 路径，必须挡住穿越尝试。
        val store = store()
        assertNull(store.get("../secret"))
        assertNull(store.get(".."))
        assertNull(store.get("a/b"))
        assertNull(store.get(""))
    }

    @Test
    fun clearRemovesTrackedFiles() {
        val store = store()
        store.write(byteArrayOf(1))
        val file = store.get(store.write(byteArrayOf(2)))!!.file
        store.clear()
        assertEquals(0, store.size())
        assertFalse(file.exists())
    }

    @Test
    fun missingFileReportsNotFound() {
        // 文件被外部删掉（如系统清理）后，登记项不能再当作有效。
        val store = store()
        val id = store.write(byteArrayOf(1))
        store.get(id)!!.file.delete()
        assertNull(store.get(id))
    }

    @Test
    fun orphanFilesAreSweptOnNewFile() {
        // 进程在下载中途被强杀会留下未登记的半截文件；newFile 必须顺手扫掉。
        val store = store()
        val dir = File(tempFolder.root, "mcp_files")
        val leftover = File(dir, "deadbeef.png").apply { writeBytes(byteArrayOf(1, 2, 3)) }
        assertTrue(leftover.exists())
        store.newFile("png")
        assertFalse("未登记的孤儿文件应被清理", leftover.exists())
    }

    @Test
    fun registeredFilesSurviveOrphanSweep() {
        // 清理只针对未登记文件，不能误伤已登记的（否则 /files 会 404）。
        val store = store()
        val id = store.write(byteArrayOf(9))
        val file = store.get(id)!!.file
        store.newFile("png")
        assertTrue("已登记的文件不能被孤儿清理误删", file.exists())
        assertNotNull(store.get(id))
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
