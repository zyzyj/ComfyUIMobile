package com.local.comfyuimobile.bridge

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.nio.charset.StandardCharsets
import java.util.zip.CRC32

class WorkflowImageReaderTest {
    @Test fun readsWorkflowFromComfyUiTextChunk() {
        val workflow = """{"nodes":[{"id":1,"type":"KSampler"}]}"""
        val metadata = "workflow\u0000$workflow".toByteArray(StandardCharsets.UTF_8)
        val png = ByteArrayOutputStream().apply {
            write(byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A))
            writeChunk("tEXt", metadata)
            writeChunk("IEND", byteArrayOf())
        }.toByteArray()

        assertEquals(workflow, WorkflowImageReader.readPngWorkflow(ByteArrayInputStream(png)))
    }

    @Test fun readsUtf8WorkflowFromInternationalTextChunk() {
        val workflow = """{"nodes":[{"id":1,"title":"中文节点"}]}"""
        val metadata = ByteArrayOutputStream().apply {
            write("workflow".toByteArray(StandardCharsets.ISO_8859_1))
            write(0)
            write(0) // 不压缩
            write(0)
            write(0) // 空语言标记
            write(0) // 空翻译关键字
            write(workflow.toByteArray(StandardCharsets.UTF_8))
        }.toByteArray()
        val png = ByteArrayOutputStream().apply {
            write(byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A))
            writeChunk("iTXt", metadata)
            writeChunk("IEND", byteArrayOf())
        }.toByteArray()

        assertEquals(workflow, WorkflowImageReader.readPngWorkflow(ByteArrayInputStream(png)))
    }

    @Test fun rejectsDecompressionBomb() {
        // v0.1.71：1MB 的全零数据能压到 1KB 左右，解压后却要 1MB 内存。
        // 上限设成 8MB，所以这里拿 64MB 的原始数据（压缩后只有几十 KB）当炸弹——
        // 老实现会老老实实把它全读进内存，手机直接 OOM。
        val bomb = ByteArray(64 * 1024 * 1024)
        val metadata = ByteArrayOutputStream().apply {
            write("workflow".toByteArray(StandardCharsets.ISO_8859_1))
            write(0)
            write(0) // zTXt 的压缩方法固定为 0
            write(deflate(bomb))
        }.toByteArray()
        val png = ByteArrayOutputStream().apply {
            write(byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A))
            writeChunk("zTXt", metadata)
            writeChunk("IEND", byteArrayOf())
        }.toByteArray()

        val error = runCatching { WorkflowImageReader.readPngWorkflow(ByteArrayInputStream(png)) }.exceptionOrNull()
        assertTrue("解压炸弹必须被拦下，实际结果：$error", error is IllegalArgumentException)
        assertTrue(error!!.message!!.contains("解压"))
    }

    @Test
    fun dispatchesByMimeAndExtension() {
        val workflow = """{"nodes":[{"id":1}]}"""
        val png = ByteArrayOutputStream().apply {
            write(byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A))
            writeChunk("tEXt", "workflow\u0000$workflow".toByteArray(StandardCharsets.UTF_8))
            writeChunk("IEND", byteArrayOf())
        }.toByteArray()
        assertEquals(workflow, WorkflowImageReader.readWorkflow(ByteArrayInputStream(png), "image/png"))
        assertEquals(workflow, WorkflowImageReader.readWorkflow(ByteArrayInputStream(png), "png"))

        val webp = buildWebpExif("workflow:$workflow")
        assertEquals(workflow, WorkflowImageReader.readWorkflow(ByteArrayInputStream(webp), "image/webp"))
    }

    @Test
    fun detectsKindFromHeader() {
        val png = ByteArrayOutputStream().apply {
            write(byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A))
            writeChunk("IEND", byteArrayOf())
        }.toByteArray()
        assertEquals("png", WorkflowImageReader.detectKind(ByteArrayInputStream(png)))
        assertEquals("webp", WorkflowImageReader.detectKind(ByteArrayInputStream(buildWebpExif("workflow:{}"))))
        // 认不出来返回 null，而不是抛异常（MIME / 扩展名都没给出格式时会走到这里）。
        assertEquals(null, WorkflowImageReader.detectKind(ByteArrayInputStream(byteArrayOf(1, 2, 3, 4))))
        assertEquals(null, WorkflowImageReader.detectKind(null))
    }

    @Test
    fun rejectsUnsupportedFormat() {
        val error = runCatching {
            WorkflowImageReader.readWorkflow(ByteArrayInputStream(ByteArray(0)), "image/gif")
        }.exceptionOrNull()
        assertTrue(error is IllegalStateException)
    }

    // ===== WebP（EXIF）=====

    @Test
    fun readsWorkflowFromWebpExif() {
        val workflow = """{"nodes":[{"id":1,"type":"KSampler"}]}"""
        val webp = buildWebpExif("workflow:$workflow", "prompt:{}")
        assertEquals(workflow, WorkflowImageReader.readWebpWorkflow(ByteArrayInputStream(webp)))
    }

    @Test
    fun readsWorkflowFromWebpExifWithPrefix() {
        val workflow = """{"nodes":[{"id":2}]}"""
        val webp = buildWebpExifWithPrefix("workflow:$workflow")
        assertEquals(workflow, WorkflowImageReader.readWebpWorkflow(ByteArrayInputStream(webp)))
    }

    @Test
    fun webpWithoutWorkflowFails() {
        val webp = buildWebpExif("Software:ComfyUI")
        val error = runCatching { WorkflowImageReader.readWebpWorkflow(ByteArrayInputStream(webp)) }.exceptionOrNull()
        assertTrue("没有 workflow 标签时应报错，实际：$error", error is IllegalStateException)
    }

    @Test
    fun rejectsNonWebp() {
        val notWebp = "RIFF".toByteArray() + ByteArray(4) + "XXXX".toByteArray()
        val error = runCatching { WorkflowImageReader.readWebpWorkflow(ByteArrayInputStream(notWebp)) }.exceptionOrNull()
        assertTrue(error is IllegalArgumentException)
    }

    /**
     * 造一个最小可用的 WebP：RIFF 头 + EXIF 块（内含一个 TIFF IFD，条目为 ASCII 字符串）。
     * 每条字符串写成 type=2 的 tag，值形如官方前端的 `key:value`。
     */
    private fun buildWebpExif(vararg strings: String): ByteArray {
        val exif = buildExifPayload(strings.toList())
        return wrapWebpExif(exif)
    }

    /** 带 `Exif\0\0` 前缀的变体（部分写入器会加，官方前端也专门判断它）。 */
    private fun buildWebpExifWithPrefix(vararg strings: String): ByteArray {
        val prefix = "Exif\u0000\u0000".toByteArray(StandardCharsets.US_ASCII)
        return wrapWebpExif(prefix + buildExifPayload(strings.toList()))
    }

    private fun wrapWebpExif(exif: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        out.write("RIFF".toByteArray(StandardCharsets.US_ASCII))
        // RIFF 大小字段（内容总长 - 8），本测试不校验，填 0。
        out.writeLittleInt(0)
        out.write("WEBP".toByteArray(StandardCharsets.US_ASCII))
        out.write("EXIF".toByteArray(StandardCharsets.US_ASCII))
        out.writeLittleInt(exif.size)
        out.write(exif)
        if (exif.size % 2 == 1) out.write(0)
        return out.toByteArray()
    }

    private fun buildExifPayload(strings: List<String>): ByteArray {
        // TIFF 头（little-endian）："II" + 0x002A（小端为 2A 00）+ 首个 IFD 偏移 8（小端）。
        val header = ByteArray(8)
        header[0] = 'I'.code.toByte()
        header[1] = 'I'.code.toByte()
        header[2] = 0x2A.toByte()
        header[3] = 0x00
        header[4] = 0x08
        header[5] = 0x00
        header[6] = 0x00
        header[7] = 0x00

        val entryCount = strings.size
        val ifd = ByteArrayOutputStream()
        ifd.writeLittleShort(entryCount)
        // 字符串数据从 IFD 之后写起。
        val dataStart = 8 + 2 + entryCount * 12 + 4
        val data = ByteArrayOutputStream()
        strings.forEachIndexed { index, value ->
            // tag 用递增的伪 tag 号；type=2（ASCII）；count=长度+1（含结尾 \0）。
            val valueBytes = value.toByteArray(StandardCharsets.UTF_8) + byteArrayOf(0)
            ifd.writeLittleShort(0x0100 + index) // tag
            ifd.writeLittleShort(2) // type = ASCII
            ifd.writeLittleInt(valueBytes.size) // count
            ifd.writeLittleInt(dataStart + data.size()) // value offset
            data.write(valueBytes)
        }
        ifd.writeLittleInt(0) // 下一个 IFD 偏移 = 0

        return ByteArrayOutputStream().apply {
            write(header)
            write(ifd.toByteArray())
            write(data.toByteArray())
        }.toByteArray()
    }

    private fun ByteArrayOutputStream.writeLittleShort(value: Int) {
        write(value and 0xFF)
        write((value ushr 8) and 0xFF)
    }

    private fun ByteArrayOutputStream.writeLittleInt(value: Int) {
        write(value and 0xFF)
        write((value ushr 8) and 0xFF)
        write((value ushr 16) and 0xFF)
        write((value ushr 24) and 0xFF)
    }

    @Test fun stillReadsReasonablyLargeCompressedWorkflow() {
        // 别把正常的大工作流一起误杀：2MB 文本在 8MB 上限之内，应该照常读出来。
        val workflow = "{\"nodes\":[" + (1..20_000).joinToString(",") { "{\"id\":$it}" } + "]}"
        val metadata = ByteArrayOutputStream().apply {
            write("workflow".toByteArray(StandardCharsets.ISO_8859_1))
            write(0)
            write(0)
            write(deflate(workflow.toByteArray(StandardCharsets.UTF_8)))
        }.toByteArray()
        val png = ByteArrayOutputStream().apply {
            write(byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A))
            writeChunk("zTXt", metadata)
            writeChunk("IEND", byteArrayOf())
        }.toByteArray()

        assertEquals(workflow, WorkflowImageReader.readPngWorkflow(ByteArrayInputStream(png)))
    }

    private fun deflate(data: ByteArray): ByteArray {
        val output = ByteArrayOutputStream()
        java.util.zip.DeflaterOutputStream(output).use { it.write(data) }
        return output.toByteArray()
    }

    private fun ByteArrayOutputStream.writeChunk(type: String, data: ByteArray) {
        val typeBytes = type.toByteArray(StandardCharsets.US_ASCII)
        val crc = CRC32().apply { update(typeBytes); update(data) }
        DataOutputStream(this).apply {
            writeInt(data.size)
            write(typeBytes)
            write(data)
            writeInt(crc.value.toInt())
        }
    }
}
