package com.local.comfyuimobile.data

import com.local.comfyuimobile.model.CacheOutputRule
import com.local.comfyuimobile.model.ResultMedia
import com.local.comfyuimobile.network.LanAddress

object CachePolicy {
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
    ): Boolean = media.jobId in submittedJobIds && media.createdAt >= cacheClearedAt && media.nodeType.isNotBlank() && rules.any { rule ->
        rule.enabled &&
            LanAddress.sameServer(rule.serverUrl, serverUrl) &&
            rule.nodeType == media.nodeType
    }
}
