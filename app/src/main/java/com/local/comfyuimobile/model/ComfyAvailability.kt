package com.local.comfyuimobile.model

/**
 * 「ComfyUI 为什么不可用」的原因判定（v0.2.97）。纯函数，可单测。
 *
 * 为什么单独抽出来：原先把一切非 CONNECTED 都显示成
 * 「未连接 —— 工具不可用，请先回工作流页连接服务器」。但不可用至少有四种原因，
 * **照着这句话做往往完全没用**——项目根本没启动时，让用户去刷新 Cookie、重新连接，
 * 全是白费功夫（而平台侧还会把"项目未启动"报成"需要登录"，更容易带偏）。
 *
 * 规划书 §六 要求区分这四种，因为**每种要采取的行动都不同**：
 *
 * | 原因 | 用户/AI 该做什么 |
 * |---|---|
 * | 项目未启动 | 去启动它 |
 * | 环境分配中 | 等 |
 * | 就绪但 ComfyUI 没跑 | 去跑启动脚本 |
 * | 登录态失效 | 回 App 重新登录 |
 */
object ComfyAvailability {

    enum class Reason {
        /** ComfyUI 在线，工具可用。 */
        READY,

        /** 还没连任何 ComfyUI 地址（也没配过服务器）。 */
        NO_SERVER,

        /** 正在连接/重连中。 */
        CONNECTING,

        /** 云端项目没在跑——最容易被误报成"要登录"的那种。 */
        PROJECT_NOT_RUNNING,

        /** 项目在跑、环境已就绪，但 8188 连不上（启动脚本还没跑完或没跑）。 */
        ENVIRONMENT_NOT_READY,

        /** 有项目在跑但环境尚未分配完成。 */
        PROJECT_STARTING,

        /** 登录态失效（需要重新登录）。 */
        LOGIN_EXPIRED,

        /** 其它（网络等），无法进一步归因。 */
        UNKNOWN,
    }

    /**
     * 判定原因。
     *
     * @param connected ComfyUI 客户端当前是否已连上（这是最终判据，优先看它）
     * @param hasServer 是否配过服务器地址
     * @param connecting 是否正在连接/重连
     * @param loginExpired 是否已知登录态失效
     * @param runningProjects 有项目在运行的数量
     * @param startingProjects 正在启动/环境分配中的数量
     * @param environmentReady 运行中项目的环境是否已就绪（拿到了 endpoint）
     */
    fun resolve(
        connected: Boolean,
        hasServer: Boolean,
        connecting: Boolean,
        loginExpired: Boolean,
        runningProjects: Int,
        startingProjects: Int,
        environmentReady: Boolean,
    ): Reason = when {
        // 已连上就一定是就绪：下面那些判据都是"连不上时怎么解释"，不该覆盖事实。
        connected -> Reason.READY
        loginExpired -> Reason.LOGIN_EXPIRED
        // 连接中是个瞬时态，优先报它（否则会在重连期间反复喊"项目没跑"）。
        connecting -> Reason.CONNECTING
        // 压根没配过地址：必须先说这个，否则会被下面的"项目没跑"盖住，
        // 让人去启动项目——而他连地址都没填（这是判据顺序踩过的坑）。
        !hasServer -> Reason.NO_SERVER
        startingProjects > 0 && runningProjects == 0 -> Reason.PROJECT_STARTING
        runningProjects == 0 -> Reason.PROJECT_NOT_RUNNING
        !environmentReady -> Reason.ENVIRONMENT_NOT_READY
        else -> Reason.UNKNOWN
    }

    /**
     * 给用户看的一句话 + 该做什么。
     *
     * 措辞里**必须带行动**：只说"不可用"，用户只能猜（这也是这一段存在的全部理由）。
     */
    fun describe(reason: Reason, serverName: String = ""): String = when (reason) {
        Reason.READY -> "已连接${serverName.takeIf { it.isNotBlank() }?.let { " · $it" }.orEmpty()}"
        Reason.NO_SERVER -> "未连接 —— 还没配置 ComfyUI 地址，请到工作流页连接服务器"
        Reason.CONNECTING -> "连接中…"
        Reason.PROJECT_NOT_RUNNING -> "未连接 —— 云端项目没有运行。请先启动项目（可用 start_gpu），或到「账号」页启动"
        Reason.PROJECT_STARTING -> "未连接 —— 项目已提交，平台正在分配环境，请稍等 1-2 分钟"
        Reason.ENVIRONMENT_NOT_READY -> "未连接 —— 项目在跑，但环境还没就绪或 8188 不可达。请确认 ComfyUI 启动脚本已跑（可用 terminal_exec）"
        Reason.LOGIN_EXPIRED -> "未连接 —— 登录态已失效，请到「账号」页重新登录后重连"
        Reason.UNKNOWN -> "未连接 —— 具体原因未知。请到工作流页重试连接；若一直失败，检查项目状态与登录态"
    }

    /** 该原因是否属于"工具不可用"。 */
    fun isBlocking(reason: Reason): Boolean = reason != Reason.READY
}
