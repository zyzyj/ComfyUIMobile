package com.local.comfyuimobile.bridge

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * v0.2.73：注入脚本的**契约冒烟测试**。
 *
 * 背景（来自外部架构审视）：`ComfyBridge` 是本项目唯一零单测覆盖的大模块——
 * WebView 里的真实 JS 执行在本地 JVM 单测里跑不了（Robolectric 也模拟不了）。
 * 而它同时是**风险密度最高**的地方（页面重载、渲染进程崩溃、前端升级都要在这里打补丁）。
 * 每次 ComfyUI 前端升级因此都是"只能等真机或用户反馈"的静默风险。
 *
 * 这组测试**测不了运行时行为**，退一步做**源码静态断言**：挡不住运行时问题，
 * 但能挡住"改脚本时手滑删掉关键调用/返回结构"——那类事故靠 review 很容易漏。
 *
 * 为什么直接读源文件：脚本只能由 `ComfyBridge` 的实例方法生成（类需要 Activity，
 * 单测里无法实例化）。与其为了可测性把近千行脚本搬来搬去（那本身是风险），
 * 不如直接断言源码里同时存在「脚本」与「它必须包含的调用」。
 *
 * Gradle 单测的工作目录是模块目录（`app/`），所以用 `../app/src/...` 定位；
 * 该行为在 CI（GitHub Actions）上一致。
 */
class ComfyBridgeScriptTest {

    private val bridgeSource: String by lazy {
        val candidates = listOf(
            "../app/src/main/java/com/local/comfyuimobile/bridge/ComfyBridge.kt",
            "app/src/main/java/com/local/comfyuimobile/bridge/ComfyBridge.kt",
        )
        candidates.map(::File).firstOrNull(File::isFile)?.readText()
            ?: error(
                "找不到 ComfyBridge.kt（工作目录=${System.getProperty("user.dir")}）。" +
                    "若调整了模块结构，请同步更新本测试的路径候选。",
            )
    }

    /** 断言某个脚本函数的存在，并返回它的正文（到下一个同级成员为止）。 */
    private fun scriptBody(fnName: String): String {
        val start = bridgeSource.indexOf("fun $fnName(")
        assertTrue("源码里找不到脚本函数 $fnName（被删或改名了？）", start >= 0)
        // 到下一个同级成员（4 空格缩进的 fun/val/var/companion）为止
        val rest = bridgeSource.substring(start)
        val end = Regex("\n    (?:private |internal )?(?:fun|val|var|companion object|const val) ")
            .find(rest)?.range?.first ?: rest.length
        return rest.substring(0, end)
    }

    // ===== 关键脚本必须存在（被删/改名即红）=====

    @Test
    fun allInjectedScriptsStillExist() {
        listOf(
            "ensureActiveWorkflowScript",
            "imageWorkflowScript",
            "workflowManifestScript",
            "promptApplyScript",
            "syncWorkflowScript",
        ).forEach { name ->
            assertTrue(
                "注入脚本 $name 不见了——它承担着前端交互的关键路径，删/改名要连带评估调用点",
                bridgeSource.contains("fun $name("),
            )
        }
    }

    // ===== 对外契约：ok / error / catch =====

    @Test
    fun workflowLoadingScriptsCallTheOfficialLoadGraphData() {
        // 复用官方 loadGraphData 是本项目的核心架构决定（组合子图展开、bypass/mute、
        // rgthree 全靠它）。这条拦的是"改脚本时把官方调用换成自研解析"。
        //
        // 断言**实际调用**（`app.loadGraphData(`）而不是裸词——后者在注释里也有出现，
        // 改掉真正的调用仍然会通过（这个漏洞是我第一次写这条时验证出来的）。
        // 只有 workflowManifestScript 负责把图灌进画布；
        // ensureActiveWorkflowScript 是"切换活动标签"用的（不调 loadGraphData）。
        //
        // 断言**至少 3 处**调用：它们分别覆盖"新版前端对象直取 / 旧版先取对象再灌 /
        // 拿不到对象时的兜底"三条路径。断言"存在 1 处"是不够的——删掉任意一条分支
        // 仍然会通过（这点是我实测出来的：破坏一处调用后测试照样绿）。
        val body = scriptBody("workflowManifestScript")
        val calls = Regex("app\\s*\\.\\s*loadGraphData\\s*\\(").findAll(body).count()
        assertTrue(
            "workflowManifestScript 的 app.loadGraphData(...) 调用只剩 $calls 处" +
                "（应至少 3 处，分别覆盖不同前端版本的路径）——" +
                "那是复用官方解析的唯一入口，删任何一条分支都要连带评估",
            calls >= 3,
        )
    }

    @Test
    fun manifestScriptKeepsTheApiFormatConversionBranch() {
        // API 格式（顶层是 {节点id:{class_type,inputs}}）在旧前端需要脚本显式转换。
        // 断言脚本里真的读取了 class_type 字段（而不是只提在注释里）。
        val body = scriptBody("workflowManifestScript")
        assertTrue(
            "workflowManifestScript 应保留 API 格式判断（读取 class_type 字段）",
            Regex("\\.\\s*class_type").containsMatchIn(body),
        )
    }

    @Test
    fun tabSwitchScriptStillShortCircuitsWhenServerStoreIsUnavailable() {
        // ensureActiveWorkflowScript 在"服务器不提供云端工作流存储"（AI Studio 这类反代）
        // 时必须直接跳过——以前它硬报"找不到被编辑的工作流"，导致高级编辑保存不了。
        val body = scriptBody("ensureActiveWorkflowScript")
        assertTrue(
            "必须在 serverWorkflowStoreAvailable 为假时短路返回",
            body.contains("serverWorkflowStoreAvailable"),
        )
    }

    @Test
    fun scriptsReportFailureWithAnErrorMessage() {
        // 失败时必须带 error 字段：上层统一读它给用户提示；
        // 缺了就只剩一句无信息量的"操作失败"。
        listOf("ensureActiveWorkflowScript", "imageWorkflowScript", "workflowManifestScript",
            "promptApplyScript", "syncWorkflowScript").forEach { name ->
            assertTrue(
                "$name 的失败路径必须带 error 字段",
                scriptBody(name).contains("error"),
            )
        }
    }

    @Test
    fun scriptsCatchTheirOwnExceptions() {
        // 脚本里若不兜异常，JS 抛错时 Promise 永不 settle，Android 侧只能白等到超时。
        listOf("ensureActiveWorkflowScript", "imageWorkflowScript", "workflowManifestScript",
            "promptApplyScript", "syncWorkflowScript").forEach { name ->
            assertTrue("$name 必须自己兜住异常（catch）", scriptBody(name).contains("catch"))
        }
    }

    // ===== 兼容分支不能被"简化"掉 =====

    @Test
    fun manifestScriptStillHandlesApiFormatWorkflows() {
        // 工作流可能是 API 格式（顶层是 {节点id:{class_type,inputs}}，没有 nodes 数组），
        // 旧版前端需要脚本显式转换成图。这条挡的是"误删兼容分支"——删了之后旧前端静默失败。
        //
        // 注：这个判断在 workflowManifestScript 里（不是图片解析脚本——后者走官方
        // pnginfo API 读图片元数据，不涉及节点结构）。
        assertTrue(
            "工作流清单脚本必须保留 API 格式的兼容判断（class_type）",
            scriptBody("workflowManifestScript").contains("class_type"),
        )
    }

    @Test
    fun imageScriptUsesTheOfficialPnginfoApi() {
        // 图片里的工作流靠官方 pnginfo 模块解析（PNG/WebP/AVIF 三种各自的方法）。
        // 这条拦的是"改成自研解析"——那会重踩元数据格式的坑。
        val body = scriptBody("imageWorkflowScript")
        assertTrue("必须使用官方 pnginfo", body.contains("pnginfo"))
        listOf("getPngMetadata", "getWebpMetadata", "getAvifMetadata").forEach { api ->
            assertTrue("$api 的调用不能删——那是图片工作流解析的唯一路径", body.contains(api))
        }
    }

    @Test
    fun manifestScriptStillHandlesTwoFrontendApiNamingConventions() {
        // ComfyUI 前端升级时改过全局对象的暴露位置，脚本同时兼容两代命名。
        // 删掉任一支都会让对应版本的前端直接不可用。
        val body = scriptBody("workflowManifestScript")
        assertTrue("必须兼容 __comfyMobileApp", body.contains("__comfyMobileApp"))
        assertTrue("必须兼容 comfyAPI 旧命名", body.contains("comfyAPI"))
    }

    // ===== 安全相关：同源校验不能被削弱 =====

    @Test
    fun pageReadinessStillEnforcesSameOrigin() {
        // isPageReadyForScripts 里的同源校验是安全边界：不能退化成前缀匹配，
        // 否则伪装页面会被判定为就绪，后续 loadGraphData / graphToPrompt 会执行在它上面。
        assertTrue(
            "页面就绪判定必须仍然做同源校验",
            bridgeSource.contains("isSameOrigin"),
        )
        assertFalse(
            "同源校验不能退化成 startsWith（注释里明确禁止过）",
            Regex("allowedOrigin\\.startsWith\\(currentUrl\\)").containsMatchIn(bridgeSource),
        )
    }
}
