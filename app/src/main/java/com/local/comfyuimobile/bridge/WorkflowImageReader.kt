package com.local.comfyuimobile.bridge

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.InputStream
import java.nio.charset.StandardCharsets
import java.util.zip.InflaterInputStream

object WorkflowImageReader {
    private val pngSignature = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)

    /**
     * 从图片里读出 ComfyUI 工作流。
     *
     * PNG 走自己的 tEXt/zTXt/iTXt（[readPngWorkflow]）；WebP 走 EXIF（[readWebpWorkflow]）。
     * 两者都不需要连服务器，未连接时也能导入——这是相对"交给前端 WebView 解析"的重要区别。
     */
    fun readWorkflow(input: InputStream, mimeOrExtension: String): String {
        val kind = mimeOrExtension.substringAfterLast('/').substringAfterLast('.').lowercase()
        return when (kind) {
            "png" -> readPngWorkflow(input)
            "webp" -> readWebpWorkflow(input)
            else -> error("不支持的图片格式：$mimeOrExtension")
        }
    }

    /**
     * 按文件头判断图片是 PNG 还是 WebP；认不出来返回 null。
     *
     * 用途：MIME 和扩展名都靠不住时的最后一道判断（分享进来的图常常只有
     * `content://` URI，文件名是兜底生成的）。只读开头几个字节就关流。
     */
    fun detectKind(input: InputStream?): String? {
        if (input == null) return null
        return runCatching {
            input.use { stream ->
                val header = ByteArray(12)
                val read = stream.read(header)
                when {
                    read >= 8 && header.copyOfRange(0, 8).contentEquals(pngSignature) -> "png"
                    read >= 12 &&
                        header.copyOfRange(0, 4).toString(StandardCharsets.US_ASCII) == "RIFF" &&
                        header.copyOfRange(8, 12).toString(StandardCharsets.US_ASCII) == "WEBP" -> "webp"
                    else -> null
                }
            }
        }.getOrNull()
    }

    /**
     * 从 WebP 的 EXIF 块里取工作流。
     *
     * ComfyUI 的动画 WebP 把元数据写在 EXIF 标签里，值形如 `workflow:{JSON}`（冒号分隔的
     * 字符串）；这与官方前端 `getWebpMetadata` 的做法一致（遍历所有字符串值，按第一个冒号
     * 拆成 key/value）。这里在 App 侧原生做，不依赖服务器前端版本。
     */
    fun readWebpWorkflow(input: InputStream): String {
        val bytes = readCapped(input, MAX_WEBP_BYTES)
        require(bytes.size >= 12) { "所选文件不是有效的 WebP 图片" }
        require(
            bytes.copyOfRange(0, 4).toString(StandardCharsets.US_ASCII) == "RIFF" &&
                bytes.copyOfRange(8, 12).toString(StandardCharsets.US_ASCII) == "WEBP",
        ) { "所选文件不是有效的 WebP 图片" }
        var offset = 12
        while (offset + 8 <= bytes.size) {
            val fourCC = bytes.copyOfRange(offset, offset + 4).toString(StandardCharsets.US_ASCII)
            val length = readUInt32LE(bytes, offset + 4)
            // length 用 Long：无符号 32 位若高位置 1 会变成负数，误以为读完了。
            if (offset + 8L + length > bytes.size) break
            if (fourCC == "EXIF") {
                var start = offset + 8
                var size = length.toInt()
                // EXIF 块常带 "Exif\0\0" 前缀，TIFF 头在其后。
                if (size >= 6 && bytes.copyOfRange(start, start + 6).toString(StandardCharsets.US_ASCII) == "Exif\u0000\u0000") {
                    start += 6
                    size -= 6
                }
                val tags = parseExifStrings(bytes, start, size)
                // 与前端一致：值形如 `key:value`，取第一个冒号拆开。
                tags.forEach { value ->
                    val index = value.indexOf(':')
                    if (index > 0) {
                        val key = value.substring(0, index)
                        if (key.equals("workflow", ignoreCase = true)) {
                            return value.substring(index + 1)
                        }
                    }
                }
                break
            }
            // RIFF 规定奇数长度的块要补一字节对齐。
            offset += (8 + length + (length % 2)).toInt()
        }
        error("图片中没有可导入的 ComfyUI 工作流")
    }

    /** 解析 EXIF 里的 TIFF IFD，取出所有 ASCII（type=2）标签的字符串值。 */
    private fun parseExifStrings(bytes: ByteArray, start: Int, size: Int): List<String> {
        if (size < 8 || start < 0 || start + size > bytes.size) return emptyList()
        val little = bytes[start] == 'I'.code.toByte() && bytes[start + 1] == 'I'.code.toByte()
        val ifdOffset = readInt(bytes, start + 4, 4, little)
        val base = start + ifdOffset
        if (base < start || base + 2 > bytes.size) return emptyList()
        val count = readInt(bytes, base, 2, little)
        if (count <= 0 || count > MAX_EXIF_ENTRIES) return emptyList()
        val values = mutableListOf<String>()
        for (index in 0 until count) {
            val entry = base + 2 + index * 12
            if (entry + 12 > bytes.size) break
            val type = readInt(bytes, entry + 2, 2, little)
            if (type != 2) continue // 只关心 ASCII 字符串
            val numValues = readInt(bytes, entry + 4, 4, little)
            if (numValues <= 0 || numValues > MAX_EXIF_STRING) continue
            val valueOffset = readInt(bytes, entry + 8, 4, little)
            // 长度 <= 4 时值直接内联在偏移字段里。
            val valueStart = if (numValues <= 4) entry + 8 else start + valueOffset
            val valueEnd = valueStart + numValues - 1
            if (valueStart < 0 || valueEnd > bytes.size || valueStart > valueEnd) continue
            values += bytes.copyOfRange(valueStart, valueEnd).toString(StandardCharsets.UTF_8)
        }
        return values
    }

    private fun readInt(bytes: ByteArray, offset: Int, length: Int, little: Boolean): Int {
        var result = 0
        for (i in 0 until length) {
            val b = bytes[offset + i].toInt() and 0xFF
            result = if (little) result or (b shl (8 * i)) else (result shl 8) or b
        }
        return result
    }

    private fun readUInt32LE(bytes: ByteArray, offset: Int): Long =
        (bytes[offset].toLong() and 0xFF) or
            ((bytes[offset + 1].toLong() and 0xFF) shl 8) or
            ((bytes[offset + 2].toLong() and 0xFF) shl 16) or
            ((bytes[offset + 3].toLong() and 0xFF) shl 24)

    /** 读取整个流，但超过 [limit] 就停——防止超大图片把内存吃爆。 */
    private fun readCapped(input: InputStream, limit: Int): ByteArray {
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        while (true) {
            val read = input.read(buffer)
            if (read <= 0) break
            if (output.size() + read > limit) {
                throw IllegalArgumentException("图片过大（超过 ${limit / 1024 / 1024}MB），已停止解析")
            }
            output.write(buffer, 0, read)
        }
        return output.toByteArray()
    }

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
    // EXIF IFD 条目数上限，防畸形图片让解析器死循环/吃内存。
    private const val MAX_EXIF_ENTRIES = 512
    // 单个 EXIF 字符串长度上限（工作流 JSON 几百 KB，1MB 已很宽裕）。
    private const val MAX_EXIF_STRING = 1024 * 1024
    // 允许解析的 WebP 文件大小上限。
    private const val MAX_WEBP_BYTES = 64 * 1024 * 1024
}
