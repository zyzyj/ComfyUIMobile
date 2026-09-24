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
 * scheduleName 是接口里真正要传的值（平台枚举固定，见 [AiStudioProtocol.SCHEDULE_*]），
 * label 是给人看的（如 `V100 16GB`）。
 *
 * costPerHour 是**这档每小时消耗多少算力卡**（平台展示 `costPerHour/100` 后配
 * 「算力卡/小时」）。同一份算力卡余额，换成不同显卡能跑的小时数完全不同，
 * 所以界面上要跟看消耗速度一起看。
 */
data class AiStudioSchedule(
    val scheduleName: String,
    val label: String = "",
    val gpuType: String = "",
    /** 每小时消耗的算力卡（分）。null = 未知。 */
    val costPerHour: Double? = null,
    /** 该档归属的配额类型（如 V100 / A100），用于显示本周剩余。 */
    val weekQuotaType: String = "",
    /** 该档本周剩余可用（分钟）。null = 未知。 */
    val weekRemainingMinutes: Double? = null,
    /** 该档是否可用/有余额。未知为 true，不要因为解析不到就禁用。 */
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
    /** GPU 档位是否已拉取完成。用于区分“还没拉”与“拉到了空”。 */
    val schedulesLoaded: Boolean = false,
    val loadingProjects: Boolean = false,
    val signingIn: Boolean = false,
    val startingProjectId: String? = null,
    val stoppingProjectId: String? = null,
    /**
     * 环境地址已确认可用的项目 ID。
     *
     * 平台的 `running=true` 只是**受理回执**（实测提交后 4 秒就变 true），
     * 真正的环境分配还要 6~24 秒（繁忙时数分钟）。这个字段记录「我们已经确认
     * 拿到环境地址」，用来区分「已受理」与「真的可用」，避免界面过早报「运行中」。
     */
    val environmentReadyProjectId: String? = null,
    /**
     * 社区积分。null 表示**没读到**，不是 0 —— 界面要显示「—」而不是编一个数。
     * 平台接口改版是常态，把「未知」和「真的是 0」区分开才不会骗人。
     */
    val points: Int? = null,
    /**
     * 算力卡余额 **原始值（分钟）**。
     *
     * 刻意同时保留原文与「折算小时」：平台自身把它展示为「算力卡」标签 + 折算小时，
     * 因为它是**按基础版折算的可用时长**；换成 V100/A100 消耗速度不同，能跑的小时数
     * 也不同。只报小时会让人误以为「什么显卡都能跑这么久」，所以两个都给。
     */
    val computeCardMinutes: Double? = null,
    /** 算力卡余额的展示文案（如 "62.7 小时（按基础版折算）"）。null 同上。 */
    val computeCard: String? = null,
    /** A币余额。null 同上。 */
    val aCoin: String? = null,
    /** 本周各档配额剩余（key 如 V100/A100/DCU/DEV，值=分钟）。 */
    val weekQuota: Map<String, Double> = emptyMap(),
    /** 控制台：终端输出缓冲（按行，上限由 ViewModel 截断）。 */
    val terminalLines: List<String> = emptyList(),
    val consoleBusy: Boolean = false,
    /** 控制台是否已连上云端终端。 */
    val consoleConnected: Boolean = false,
    /**
     * 控制台命令输入框的内容。
     *
     * 放在状态里而不是 Composable 的 remember，是为了让「快捷命令」芯片能把命令
     * 填进输入框（跨组件传递）。
     */
    val consoleDraft: String = "comfyui",
    /**
     * 运行中项目暴露的 ComfyUI 地址。
     *
     * 平台前端会把终端里出现的 `http://127.0.0.1:{port}` 自动改写成
     * `{baseUrl}api_serving/{port}/`（前端 7613.js 就是这么拼的），
     * 所以 ComfyUI 的 8188 端口对应 `{baseUrl}api_serving/8188`。
     */
    val comfyUiUrl: String? = null,
    /** 今天是否已签到（本机记录 + 接口状态共同决定）。 */
    val signedInToday: Boolean = false,
    val message: String? = null,
    val error: String? = null,
    /** 调试用：最近一次接口的原始响应摘要，方便真机把结构发回来校准解析。 */
    val lastRawResponse: String? = null,
) {
    fun activeAccount(): AiStudioAccount? = accounts.firstOrNull { it.id == activeAccountId }
}
