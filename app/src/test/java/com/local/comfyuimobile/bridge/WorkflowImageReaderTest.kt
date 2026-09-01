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
