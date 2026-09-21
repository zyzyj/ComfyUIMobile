package com.local.comfyuimobile.model

/**
 * 百度 AI Studio（星河社区）相关的数据模型。
 *
 * v0.1.90：这些字段来自平台网页前端的真实接口（从 CDN 前端 bundle 反查），
 * 不是官方文档承诺的公开 API。平台改版时最先坏的就是这里，所以解析一律走
 * opt + 兜底默认值，缺字段只降级不抛异常。
 */

/** 一个 AI Studio 账号。Cookie 即凭证（核心是 BDUSS），等价于账号密码。 */
data class AiStudioAccount(
    val id: String,
    /** 展示用昵称，取自登录后抓到的用户资料；抓不到时用 UID 或"账号 N"兜底。 */
    val nickname: String = "",
    val uid: String = "",
    /**
     * 完整的 Cookie 串。
     *
     * 与 ComfyUI 反代那份 Cookie 刻意分开存：那份是"某台 ComfyUI 服务器"的，
     * 这份是"AI Studio 平台账号"的，两者生命周期与用途都不同，混在一起会在
     * 切换账号时互相污染。
     */
    val cookie: String = "",
    /**
     * 平台前端注入在页面里的 bdToken，调业务接口要放进 `x-studio-token` 头。
     * 抓不到时留空，请求仍会发（部分接口不校验），失败会让用户重新登录。
     */
    val bdToken: String = "",
    val lastUsedAt: Long = 0L,
    /** 最近一次签到成功的时间戳（本机记录，用于界面展示"今天已签"）。 */
    val lastSignInAt: Long = 0L,
) {
    fun displayName(): String = nickname.ifBlank { uid.ifBlank { "未命名账号" } }
}

/** 我的项目列表里的一项。 */
data class AiStudioProject(
    val projectId: String,
    val name: String,
    val description: String = "",
    /** 平台侧状态码的原始值，含义随平台变化，仅用于展示判断。 */
    val statusRaw: Int = 0,
    val running: Boolean = false,
    val updatedAt: Long = 0L,
    val isNotebook: Boolean = true,
) {
    fun displayName(): String = name.ifBlank { "项目 $projectId" }
}

/**
 * 启动环境时可选的一档算力。
 *
 * scheduleName 是接口里真正要传的值（形如 `normalSchedule` 或某档 GPU 的调度名），
 * label 是给人看的（如 `V100 16G`），两者都可能为空——平台用哪套命名会变，
 * 所以界面上优先显示 label，为空时退回 scheduleName。
 */
data class AiStudioSchedule(
    val scheduleName: String,
    val label: String = "",
    val gpuType: String = "",
    /** 该档位是否可用/有余额。未知为 true，不要因为解析不到就禁用。 */
    val available: Boolean = true,
) {
    fun displayName(): String = label.ifBlank { gpuType.ifBlank { scheduleName } }
}

/** AI Studio 面板的整体状态，挂在 AppUiState 下。 */
data class AiStudioState(
    val accounts: List<AiStudioAccount> = emptyList(),
    val activeAccountId: String? = null,
    val projects: List<AiStudioProject> = emptyList(),
    val schedules: List<AiStudioSchedule> = emptyList(),
    val loadingProjects: Boolean = false,
    val signingIn: Boolean = false,
    val startingProjectId: String? = null,
    val stoppingProjectId: String? = null,
    /**
     * 社区积分。null 表示**没读到**，不是 0 —— 界面要显示「—」而不是编一个数。
     * 平台接口改版是常态，把「未知」和「真的是 0」区分开才不会骗人。
     */
    val points: Int? = null,
    /** 算力卡余额的可读文案（如 "32.5 点"）。null 同上。 */
    val computeCard: String? = null,
    /** A币余额。null 同上。 */
    val aCoin: String? = null,
    /** 今天是否已签到（本机记录 + 接口状态共同决定）。 */
    val signedInToday: Boolean = false,
    val message: String? = null,
    val error: String? = null,
    /** 调试用：最近一次接口的原始响应摘要，方便真机把结构发回来校准解析。 */
    val lastRawResponse: String? = null,
) {
    fun activeAccount(): AiStudioAccount? = accounts.firstOrNull { it.id == activeAccountId }
}
