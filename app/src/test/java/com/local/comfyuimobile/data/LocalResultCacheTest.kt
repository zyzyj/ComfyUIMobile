package com.local.comfyuimobile.data

import com.local.comfyuimobile.model.MediaKind
import com.local.comfyuimobile.model.ResultMedia
import com.local.comfyuimobile.model.ResultSource
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * 本地作品收存单测（v0.3.2）。
 *
 * 针对用户实测的两个 P0：
 *  1. **AI 出的图一小时后变灰块、刷新后彻底消失**——原先 `add` 只登记调用方给的
 *     路径，而 MCP 那条路给的是 McpFileStore 的临时文件（停服/重启/TTL 都会清空）。
 *     修后 `add` 内部把文件收进缓存自己的目录。
 *  2. **重启后「仅 AI 生成」筛选失效**——`source` 原先只存内存不落索引，
 *     读回时硬编码 LOCAL。修后随索引持久化。
 */
class LocalResultCacheTest {

    @get:Rule
    val temporaryFolder = TemporaryFolder()

    private fun media(filename: String = "out_0001_.png") = ResultMedia(
        jobId = "job-1",
        nodeId = "9",
        filename = filename,
        subfolder = "",
        type = "output",
        kind = MediaKind.IMAGE,
        url = "http://x/$filename",
    )

    private fun cacheIn(dir: File) = LocalResultCache(File(dir, "result_cache"))

    @Test
    fun addCopiesFileIntoCacheDirectory() = runBlocking {
        val cache = cacheIn(temporaryFolder.newFolder("base"))
        // 模拟 MCP 那条路：一个随时会被清掉的临时文件。
        val temp = temporaryFolder.newFile("mcp-temp.png").apply { writeBytes(byteArrayOf(1, 2, 3)) }

        val stored = cache.add(media(), temp, ResultSource.MCP)

        // 收存点必须在缓存自己的目录里，不能还是那个临时路径。
        val path = stored.localPath
        assertTrue("localPath 应已写入：$stored", path != null)
        assertTrue("应落在缓存目录内：$path", path!!.contains("result_cache"))
        assertTrue("收存文件应存在", File(path).isFile)
        assertTrue("内容应一致", File(path).readBytes().contentEquals(byteArrayOf(1, 2, 3)))
        // 复制完成后，即使临时文件被清（模拟 McpFileStore TTL/停服），收存的本体还在。
        temp.delete()
        assertTrue("临时文件删除后收存件不应受影响", File(path).isFile)
    }

    @Test
    fun addWithAlreadyStoredFileDoesNotCopyOntoItself() = runBlocking {
        // App 出图那条路（JobMonitorService）先用 destination() 算好落点再下载，
        // 传进来的 file 已经就是收存点——不能再对它做复制（同一文件原地复制无意义）。
        val cache = cacheIn(temporaryFolder.newFolder("base"))
        val media = media()
        val destination = cache.destination(media).apply {
            parentFile?.mkdirs()
            writeBytes(byteArrayOf(9, 9))
        }

        val stored = cache.add(media, destination, ResultSource.LOCAL)

        assertEquals(destination.absolutePath, stored.localPath)
        assertTrue(destination.isFile)
    }

    @Test
    fun sourceSurvivesReload() = runBlocking {
        val dir = temporaryFolder.newFolder("base")
        val cache = cacheIn(dir)
        val temp = temporaryFolder.newFile("m.png").apply { writeBytes(byteArrayOf(1)) }

        cache.add(media(), temp, ResultSource.MCP)

        // 新实例（模拟 App 重启后从索引读回）：source 必须还是 MCP，
        // 否则「仅 AI 生成」筛选在重启后把 AI 的图全部漏掉。
        val reloaded = cacheIn(dir).load()
        assertEquals(1, reloaded.size)
        assertEquals(ResultSource.MCP, reloaded.first().source)
    }

    @Test
    fun legacyIndexWithoutSourceFallsBackToLocal() = runBlocking {
        // 旧索引没有 source 字段：读回 LOCAL（它们确实全是 App 自己出图——
        // MCP 那路是 v0.2.91 才加的）。不能因此丢记录。
        val dir = temporaryFolder.newFolder("base")
        val img = temporaryFolder.newFile("old.png").apply { writeBytes(byteArrayOf(7)) }
        val cache = cacheIn(dir)
        val media = media("old.png")
        cache.add(media, img, ResultSource.LOCAL)
        // 手工把索引里的 source 字段删掉，模拟旧版本写下的索引。
        // （不用字符串替换：JSONObject 的字段顺序不保证，尾部逗号有没有也随顺序变。）
        val indexFile = File(File(dir, "result_cache"), "index.json")
        val array = org.json.JSONArray(indexFile.readText())
        array.getJSONObject(0).remove("source")
        indexFile.writeText(array.toString())

        val loaded = cache.load()

        assertEquals(1, loaded.size)
        assertEquals(ResultSource.LOCAL, loaded.first().source)
    }
}
