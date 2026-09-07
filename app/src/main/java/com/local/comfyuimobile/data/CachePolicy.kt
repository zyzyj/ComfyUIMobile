package com.local.comfyuimobile.data

import com.local.comfyuimobile.model.CacheOutputRule
import com.local.comfyuimobile.model.ResultMedia
import com.local.comfyuimobile.network.LanAddress

object CachePolicy {
    /**
     * v0.1.86：用户到底有没有用过"输出白名单"这套机制（跨服务器全局看）。
     *
     * 用来区分两种"一条都不存"：① 用户配置过但全关了——尊重用户；
     * ② 用户压根不知道要配——这不该让他承担后果。见 [shouldCache]。
     */
    fun hasAnyRule(rules: List<CacheOutputRule>): Boolean = rules.isNotEmpty()

    fun hasConfiguredOutput(
        rules: List<CacheOutputRule>,
        serverUrl: String?,
        outputNodeTypes: Set<String>,
    ): Boolean = !serverUrl.isNullOrBlank() && rules.any { rule ->
        rule.enabled &&
            // v0.1.82：以前这里用 == 比地址，而 normalize 会补上默认端口（https 补 443），
            // 规则里存的却常常是不带端口的原样地址。同一台服务器被判成两台，规则永远
            // 匹配不上，后台任务跑完一张图都不存。改用同一套归一化比较。
            LanAddress.sameServer(rule.serverUrl, serverUrl) &&
            rule.nodeType in outputNodeTypes
    }

    fun shouldCache(
        media: ResultMedia,
        submittedJobIds: Set<String>,
        rules: List<CacheOutputRule>,
        serverUrl: String,
        cacheClearedAt: Long = 0L,
        defaultCacheAll: Boolean = true,
    ): Boolean {
        if (media.jobId !in submittedJobIds) return false
        if (media.createdAt < cacheClearedAt) return false
        if (media.nodeType.isBlank()) return false
        // v0.1.86：用户从未配置过任何输出规则时，按"全都存"处理。
        //
        // 现场：日志里每一个"后台任务完成"都是"总输出=0，失败=0"——用户开了「生成完成
        // 自动保存到图片文件夹」却一张都存不下来，因为自动保存是搭在输出白名单之上的，
        // 而白名单要用户在输出节点上长按单独开启，这个依赖关系界面上从没说清楚过。
        // 开关既然叫"自动保存"，那它就该真的自动保存。
        //
        // 判定用"整个规则列表为空"而不是"这台服务器没有规则"：只要用户在任何服务器上
        // 配过规则，就说明他懂这套机制、是有意配置的，完全按规则走（对老用户零回归，
        // 也避免"在 A 服务器配了规则、B 服务器却被全存"的意外）。
        if (defaultCacheAll && rules.isEmpty()) return true
        return rules.any { rule ->
            rule.enabled &&
                LanAddress.sameServer(rule.serverUrl, serverUrl) &&
                rule.nodeType == media.nodeType
        }
    }
}
