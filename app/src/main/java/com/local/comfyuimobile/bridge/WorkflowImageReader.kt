package com.local.comfyuimobile.bridge

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.InputStream
import java.nio.charset.StandardCharsets
import java.util.zip.InflaterInputStream

object WorkflowImageReader {
    private val pngSignature = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)

    fun readPngWorkflow(input: InputStream): String {
        DataInputStream(input.buffered()).use { source ->
            val signature = ByteArray(pngSignature.size).also(source::readFully)
            require(signature.contentEquals(pngSignature)) { "所选文件不是有效的 PNG 图片" }
            while (true) {
                val length = try {
                    source.readInt()
                } catch (_: java.io.EOFException) {
                    break
                }
                require(length in 0..MAX_CHUNK_SIZE) { "PNG 数据块过大（$length 字节），已停止解析" }
                val type = ByteArray(4).also(source::readFully).toString(StandardCharsets.US_ASCII)
                val data = ByteArray(length).also(source::readFully)
                source.readInt() // 跳过 CRC；随后仍会校验 workflow 是否为有效 JSON。
                val entry = when (type) {
                    "tEXt" -> readText(data)
                    "zTXt" -> readCompressedText(data)
                    "iTXt" -> readInternationalText(data)
                    else -> null
                }
                if (entry != null && entry.first.equals("workflow", ignoreCase = true)) {
                    return entry.second
                }
                if (type == "IEND") break
            }
        }
        error("图片中没有可导入的 ComfyUI 工作流")
    }

    private fun readText(data: ByteArray): Pair<String, String>? {
        val separator = data.indexOf(0)
        if (separator <= 0) return null
        val key = data.copyOfRange(0, separator).toString(StandardCharsets.ISO_8859_1)
        val value = data.copyOfRange(separator + 1, data.size).toString(StandardCharsets.UTF_8)
        return key to value
    }

    private fun readCompressedText(data: ByteArray): Pair<String, String>? {
        val separator = data.indexOf(0)
        if (separator <= 0 || separator + 2 > data.size) return null
        val key = data.copyOfRange(0, separator).toString(StandardCharsets.ISO_8859_1)
        val compressed = data.copyOfRange(separator + 2, data.size)
        return key to inflate(compressed).toString(StandardCharsets.UTF_8)
    }

    private fun readInternationalText(data: ByteArray): Pair<String, String>? {
        val keywordEnd = data.indexOf(0)
        if (keywordEnd <= 0 || keywordEnd + 3 > data.size) return null
        val key = data.copyOfRange(0, keywordEnd).toString(StandardCharsets.ISO_8859_1)
        val compressed = data[keywordEnd + 1].toInt() == 1
        var cursor = keywordEnd + 3
        cursor = data.indexOf(0, cursor).takeIf { it >= 0 }?.plus(1) ?: return null
        cursor = data.indexOf(0, cursor).takeIf { it >= 0 }?.plus(1) ?: return null
        val text = data.copyOfRange(cursor, data.size)
        val decoded = if (compressed) inflate(text) else text
        return key to decoded.toString(StandardCharsets.UTF_8)
    }

    /**
     * 解压 zTXt / iTXt 里的文本。
     *
     * v0.1.71：原来直接 `readBytes()` 一把梭，而 chunk 体积上限是 64MB——精心构造的
     * zlib 流压缩比能到 1000:1，64MB 能炸出几十 GB 内存，手机秒 OOM。所以边解压边数
     * 字节，超过 [MAX_INFLATED_SIZE] 立刻停手。ComfyUI 的工作流 JSON 一般也就几百 KB，
     * 8MB 已经留了极大余量。
     */
    private fun inflate(data: ByteArray, limit: Int = MAX_INFLATED_SIZE): ByteArray {
        val output = ByteArrayOutputStream(minOf(data.size * 4, limit).coerceAtLeast(1024))
        InflaterInputStream(ByteArrayInputStream(data)).use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val read = input.read(buffer)
                if (read <= 0) break
                if (output.size() + read > limit) {
                    throw IllegalArgumentException(
                        "PNG 元数据解压后超过 ${limit / 1024 / 1024}MB，已停止解析（可能是被恶意构造的图片）",
                    )
                }
                output.write(buffer, 0, read)
            }
        }
        return output.toByteArray()
    }

    private fun ByteArray.indexOf(value: Int, start: Int = 0): Int {
        for (index in start until size) if (this[index].toInt() and 0xFF == value) return index
        return -1
    }

    // 单个 chunk 压缩后的体积上限。8MB 对工作流文本已经是天文数字（正常的几百 KB）。
    private const val MAX_CHUNK_SIZE = 8 * 1024 * 1024
    // 解压后的体积上限，防 zlib 解压炸弹。
    private const val MAX_INFLATED_SIZE = 8 * 1024 * 1024
}
