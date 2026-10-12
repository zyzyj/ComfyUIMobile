package com.local.comfyuimobile.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 缩略图单测（v0.3.8，P2-1）。
 *
 * 只测**不依赖 Android** 的部分：`BitmapFactory` 在本地 JVM 单测里是 "not mocked"，
 * 所以解码那段没有测——但采样倍数的选法（采小了会糊、采大了等于没省）与落点命名
 * 都是纯逻辑，恰好是最容易写错的地方。
 */
class ThumbnailerTest {

    // ===== 采样倍数 =====

    @Test
    fun smallImageIsNotSampled() {
        // 已经比目标小（或相等）就不采样——采了会把图缩到比目标还小，放回去更糊。
        assertEquals(1, BitmapThumbnailer.sampleSizeFor(width = 200, height = 200, maxPx = 256))
        assertEquals(1, BitmapThumbnailer.sampleSizeFor(width = 256, height = 128, maxPx = 256))
    }

    @Test
    fun largeImageIsSampledToPowersOfTwo() {
        // 2048 → 2048/8=256 ≥ 256；再 /16=128 < 256 所以停。得 8。
        assertEquals(8, BitmapThumbnailer.sampleSizeFor(width = 2048, height = 2048, maxPx = 256))
    }

    @Test
    fun sampleNeverUndershootsTarget() {
        // 关键性质：采样后的最长边仍 >= maxPx（宁稍大再精确缩放，也不要采过头）。
        listOf(300, 512, 1000, 1920, 4096).forEach { size ->
            val sample = BitmapThumbnailer.sampleSizeFor(size, size, maxPx = 256)
            assertTrue(
                "size=$size sample=$sample 采样后 $size/$sample 应仍 >= 256",
                size / sample >= 256,
            )
        }
    }

    @Test
    fun sampleIsAlwaysPowerOfTwo() {
        // BitmapFactory 只在 inSampleSize 是 2 的幂时才高效（文档明说）。
        listOf(100, 300, 777, 2048, 5000).forEach { size ->
            val sample = BitmapThumbnailer.sampleSizeFor(size, size, maxPx = 256)
            assertEquals("$sample 不是 2 的幂", 0, sample and (sample - 1))
        }
    }

    @Test
    fun zeroMaxPxFallsBackToNoSampling() {
        assertEquals(1, BitmapThumbnailer.sampleSizeFor(2048, 2048, maxPx = 0))
    }

    @Test
    fun nonSquareUsesLongestSide() {
        // 3000x100：按最长边算。3000/8=375 ≥ 256 → 采 8。
        assertEquals(8, BitmapThumbnailer.sampleSizeFor(width = 3000, height = 100, maxPx = 256))
    }

    // ===== 落点命名 =====

    @Test
    fun thumbnailPathIsSiblingOfOriginal() {
        val root = java.io.File("/tmp/rc")
        val cache = LocalResultCache(root)
        val media = com.local.comfyuimobile.model.ResultMedia(
            jobId = "job-1",
            nodeId = "9",
            filename = "a.png",
            subfolder = "",
            type = "output",
            kind = com.local.comfyuimobile.model.MediaKind.IMAGE,
            url = "http://x/a.png",
        )
        val original = cache.destination(media)
        val thumb = cache.thumbnailDestination(media)
        assertEquals(original.parentFile, thumb.parentFile)
        assertEquals(original.name + LocalResultCache.THUMB_SUFFIX, thumb.name)
        assertTrue(thumb.name.endsWith(".thumb.jpg"))
    }
}
