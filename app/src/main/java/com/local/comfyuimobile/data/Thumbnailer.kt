package com.local.comfyuimobile.data

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import java.io.File

/**
 * 生成列表缩略图（v0.3.8，P2-1）。
 *
 * ## 为什么需要
 *
 * 结果页列表以前直接读**原图**：`.size(256)` 只告诉 Coil「最终 Bitmap 要 256px」，
 * 但读盘与解压仍然按原图全长做。一张 1.4MB 的 PNG 要**整读整解**，几十个格子
 * 就是上百 MB 的读盘 + 解压 —— 这就是列表滚动卡、灰块慢的根因。
 *
 * （顺带给 Coil 加磁盘缓存是没用的：那只省网络，本地图的读盘与解压省不掉。）
 *
 * ## 为什么做成接口
 *
 * 实现要用 `BitmapFactory`（Android framework），在本地 JVM 单测里是 "not mocked"
 * 直接抛异常。做成可注入的，落盘逻辑就仍能脱离 Android 单测——
 * 与 [LocalResultCache] 手写 `fileUri` 是同一个理由。
 */
fun interface Thumbnailer {
    /**
     * 把 [source] 缩到最长边不超过 [maxPx]，写到 [target]。
     *
     * @return true 表示 [target] 已生成；false 表示没做成（调用方记 null，不登记）。
     */
    fun create(source: File, target: File, maxPx: Int): Boolean
}

/**
 * 真正的实现：解码原图 → 等比缩小 → 存成 JPEG。
 *
 * 用 JPEG 而不是 PNG：缩略图是给列表看的，JPEG 体积小一个数量级，而列表不需要
 * 无损。质量 85 在 256px 上肉眼看不出差别。
 */
object BitmapThumbnailer : Thumbnailer {

    override fun create(source: File, target: File, maxPx: Int): Boolean = runCatching {
        if (!source.isFile) return false
        // 先只读尺寸（inJustDecodeBounds），再按需采样解码——直接整读会失去缩略的意义。
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(source.absolutePath, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return false

        val options = BitmapFactory.Options().apply {
            inSampleSize = sampleSizeFor(bounds.outWidth, bounds.outHeight, maxPx)
        }
        val decoded = BitmapFactory.decodeFile(source.absolutePath, options) ?: return false
        val scaled = scaleToMax(decoded, maxPx)
        target.parentFile?.mkdirs()
        val ok = target.outputStream().use { out ->
            scaled.compress(Bitmap.CompressFormat.JPEG, THUMB_QUALITY, out)
        }
        if (scaled !== decoded) scaled.recycle()
        decoded.recycle()
        ok
    }.getOrDefault(false)

    /**
     * 采样倍数（`inSampleSize` 必须是 2 的幂）。
     *
     * 抽成纯函数便于单测：采小了会糊，采大了等于没省。
     */
    fun sampleSizeFor(width: Int, height: Int, maxPx: Int): Int {
        if (maxPx <= 0) return 1
        var sample = 1
        // 目标：采样后的最长边仍**不小于** maxPx（宁可稍大，再精确缩放），
        // 避免采样把图缩到比目标还小、放回去反而糊。
        while (maxOf(width / (sample * 2), height / (sample * 2)) >= maxPx) {
            sample *= 2
        }
        return sample
    }

    /** 等比缩到最长边不超过 [maxPx]；已经足够小就原样返回（不放大）。 */
    fun scaleToMax(bitmap: Bitmap, maxPx: Int): Bitmap {
        val longest = maxOf(bitmap.width, bitmap.height)
        if (maxPx <= 0 || longest <= maxPx) return bitmap
        val ratio = maxPx.toFloat() / longest
        val w = (bitmap.width * ratio).toInt().coerceAtLeast(1)
        val h = (bitmap.height * ratio).toInt().coerceAtLeast(1)
        return Bitmap.createScaledBitmap(bitmap, w, h, true)
    }

    /** 列表缩略图的最长边。 */
    const val MAX_PX = 256

    private const val THUMB_QUALITY = 85
}
