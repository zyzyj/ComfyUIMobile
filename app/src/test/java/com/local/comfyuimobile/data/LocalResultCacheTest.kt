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

    /** 可注入缩略图生成器（v0.3.8，P2-1）：真实现要用 BitmapFactory（JVM 单测 not mocked）。 */
    private fun cacheWithThumb(dir: File, thumbnailer: Thumbnailer?) =
        LocalResultCache(File(dir, "result_cache"), thumbnailer)

    @Test
    fun addGeneratesAndRecordsThumbnail() = runBlocking {
        // 注入一个假生成器：只写几个字节，不真解码。
        val calls = mutableListOf<Pair<File, File>>()
        val thumbnailer = Thumbnailer { source, target, _ ->
            calls += source to target
            target.parentFile?.mkdirs()
            target.writeBytes(byteArrayOf(9, 9))
            true
        }
        val cache = cacheWithThumb(temporaryFolder.newFolder("base"), thumbnailer)
        val temp = temporaryFolder.newFile("orig.png").apply { writeBytes(byteArrayOf(1, 2, 3)) }

        val stored = cache.add(media(), temp, ResultSource.LOCAL)

        assertEquals(1, calls.size)
        // 缩略图必须与原图同目录、名字带后缀（否则清理时成孤儿）。
        assertEquals(calls[0].first.parentFile, calls[0].second.parentFile)
        assertTrue(calls[0].second.name.endsWith(LocalResultCache.THUMB_SUFFIX))
        assertTrue("应记下 thumbPath", stored.thumbPath != null)
        assertTrue(File(stored.thumbPath!!).isFile)
        // 重新加载后 thumbPath 仍应读回来（落盘了，不只是内存）。
        assertEquals(stored.thumbPath, cache.load().first().thumbPath)
    }

    @Test
    fun failedThumbnailIsNotRecorded() = runBlocking {
        // 生成失败 → thumbPath 记 null（UI 降级读原图），**不能**把整条记录当无效。
        val cache = cacheWithThumb(temporaryFolder.newFolder("base"), Thumbnailer { _, _, _ -> false })
        val temp = temporaryFolder.newFile("orig.png").apply { writeBytes(byteArrayOf(1)) }
        val stored = cache.add(media(), temp)
        assertTrue("应退回 null", stored.thumbPath == null)
        assertEquals(1, cache.load().size)
    }

    @Test
    fun missingThumbnailFallsBackToNullOnLoad() = runBlocking {
        // 缩略图文件被删（磁盘清理等）→ 读回 null，但记录仍有效（原图才是主体）。
        val cache = cacheWithThumb(temporaryFolder.newFolder("base"), Thumbnailer { _, target, _ ->
            target.parentFile?.mkdirs()
            target.writeBytes(byteArrayOf(7))
            true
        })
        val temp = temporaryFolder.newFile("orig.png").apply { writeBytes(byteArrayOf(1)) }
        val stored = cache.add(media(), temp)
        File(stored.thumbPath!!).delete()

        val reloaded = cache.load().first()
        assertTrue("缩略图没了应降级为 null", reloaded.thumbPath == null)
        assertTrue("原图仍应在", File(reloaded.localPath!!).isFile)
    }

    @Test
    fun removingResultAlsoDeletesThumbnail() = runBlocking {
        // 缩略图不跟着删会变成永久占空间的孤儿。
        val cache = cacheWithThumb(temporaryFolder.newFolder("base"), Thumbnailer { _, target, _ ->
            target.parentFile?.mkdirs()
            target.writeBytes(byteArrayOf(7))
            true
        })
        val temp = temporaryFolder.newFile("orig.png").apply { writeBytes(byteArrayOf(1)) }
        val stored = cache.add(media(), temp)
        val thumb = File(stored.thumbPath!!)
        assertTrue(thumb.isFile)

        cache.remove(listOf(stored))
        assertTrue("缩略图应一并删除", !thumb.isFile)
    }

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
    fun copyFailureThrowsInsteadOfFallingBackToTemp() = runBlocking {
        // v0.3.12（P0-2）：复制失败（源文件不存在/磁盘满/权限）**不静默退回临时路径**。
        // 以前退回会让记录指向临时文件，TTL 一到就变灰块。现在失败应抛异常，
        // 调用方（MCP 的 resultSink / App 的 JobMonitorService）各自处理。
        val cache = cacheIn(temporaryFolder.newFolder("base"))
        val nonexistent = File(temporaryFolder.root, "does-not-exist.png")
        assertTrue("前置：源文件确实不存在", !nonexistent.isFile)

        val error = runCatching { cache.add(media(), nonexistent, ResultSource.MCP) }.exceptionOrNull()

        assertTrue("应抛异常而不是静默退回：$error", error != null)
        // 索引里也不该出现指向临时文件的记录。
        assertTrue("索引应保持空", cache.load().isEmpty())
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
