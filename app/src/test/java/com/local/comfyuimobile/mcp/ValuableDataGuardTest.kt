package com.local.comfyuimobile.mcp

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 高价值目录删除拦截单测（v0.2.98，清单 §4.6）。
 *
 * 这层的风险**不是漏拦，而是误拦**：误拦会让 AI 白折腾（清理临时文件被拒）。
 * 所以用例一半是"该拦的拦住"，另一半是"不该拦的必须放行"。
 */
class ValuableDataGuardTest {

    // ===== 该拦 =====

    @Test
    fun blocksRecursiveDeleteOfModelsDir() {
        assertTrue(ValuableDataGuard.isValuableDeletion("rm -rf ~/ComfyUI/models"))
        assertTrue(ValuableDataGuard.isValuableDeletion("rm -rf /home/aistudio/ComfyUI/models/"))
        assertTrue(ValuableDataGuard.isValuableDeletion("rm -r ~/ComfyUI/models"))
    }

    @Test
    fun blocksLorasAndCheckpoints() {
        assertTrue(ValuableDataGuard.isValuableDeletion("rm -rf ~/ComfyUI/models/loras"))
        assertTrue(ValuableDataGuard.isValuableDeletion("rm -rf ./checkpoints"))
        assertTrue(ValuableDataGuard.isValuableDeletion("rm -rf ~/ComfyUI/workflows"))
    }

    @Test
    fun blocksChineseDirectoryNames() {
        assertTrue(ValuableDataGuard.isValuableDeletion("rm -rf ~/模型"))
        assertTrue(ValuableDataGuard.isValuableDeletion("rm -rf ./工作流"))
    }

    @Test
    fun blocksInsideCompoundCommand() {
        // 复合命令必须逐段看——只看第一段会漏（`ls` 在前）。
        assertTrue(ValuableDataGuard.isValuableDeletion("ls && rm -rf ~/ComfyUI/models"))
        assertTrue(ValuableDataGuard.isValuableDeletion("echo start; rm -rf ./loras"))
    }

    @Test
    fun blocksFindDeleteOnModelsDir() {
        assertTrue(ValuableDataGuard.isValuableDeletion("find ~/ComfyUI/models -name '*.tmp' -delete"))
    }

    @Test
    fun blocksRmdir() {
        assertTrue(ValuableDataGuard.isValuableDeletion("rmdir ~/ComfyUI/models"))
    }

    // ===== 必须放行（防误拦）=====

    @Test
    fun allowsNonRecursiveDeleteInsideModelsDir() {
        // 删单个文件是可逆代价小的操作，不该拦——否则 AI 连清理缓存都做不到。
        assertFalse(ValuableDataGuard.isValuableDeletion("rm ~/ComfyUI/models/old.safetensors"))
    }

    @Test
    fun allowsDeletingLookalikeDirectoryNames() {
        // 路径段必须**相等**，不能用 contains——`models_backup` 不是模型目录。
        assertFalse(ValuableDataGuard.isValuableDeletion("rm -rf ~/models_backup_old"))
        assertFalse(ValuableDataGuard.isValuableDeletion("rm -rf ./my_models_cache"))
    }

    @Test
    fun allowsDeletingTemporaryDirs() {
        assertFalse(ValuableDataGuard.isValuableDeletion("rm -rf /tmp/build"))
        assertFalse(ValuableDataGuard.isValuableDeletion("rm -rf ~/tmp_output"))
    }

    @Test
    fun allowsListingAndMoving() {
        // 查看/移动不是删除；尤其 mv 到回收目录正是我们推荐的替代做法。
        assertFalse(ValuableDataGuard.isValuableDeletion("ls -la ~/ComfyUI/models"))
        assertFalse(ValuableDataGuard.isValuableDeletion("mv ~/ComfyUI/models ~/.trash/"))
        assertFalse(ValuableDataGuard.isValuableDeletion("mkdir -p ~/.trash && mv ~/ComfyUI/models ~/.trash/"))
        assertFalse(ValuableDataGuard.isValuableDeletion("du -sh ~/ComfyUI/models"))
    }

    @Test
    fun allowsNonDeleteCommandsMentioningModels() {
        assertFalse(ValuableDataGuard.isValuableDeletion("python train.py --models ./models"))
        assertFalse(ValuableDataGuard.isValuableDeletion("cat ~/ComfyUI/models/README.md"))
    }

    @Test
    fun allowsEmptyCommand() {
        assertFalse(ValuableDataGuard.isValuableDeletion(""))
        assertFalse(ValuableDataGuard.isValuableDeletion("   "))
    }
}
