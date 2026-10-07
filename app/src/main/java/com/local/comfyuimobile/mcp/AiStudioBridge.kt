package com.local.comfyuimobile.mcp

import com.local.comfyuimobile.data.AppLogger
import com.local.comfyuimobile.data.AppPreferences
import com.local.comfyuimobile.model.AiStudioAccount
import com.local.comfyuimobile.network.AiStudioClient
import com.local.comfyuimobile.network.AiStudioException
import com.local.comfyuimobile.network.AiStudioRiskControl
import com.local.comfyuimobile.network.AiStudioTokenRefresher
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking

/**
 * MCP 侧访问百度 AI Studio 的适配层（v0.2.97）。
 *
 * 存在的理由：MCP server 跑在 [com.local.comfyuimobile.service.McpServerService] 里，
 * 而服务**没有界面那套状态**（`_state`、`kernelEndpoint`、`aiStudio.projects` 都只有
 * ViewModel 有）。AI 要能自己启动 GPU、跑启动脚本、连终端、等 ComfyUI 就绪，
 * 就必须有一条从服务直达 AI Studio 的通道。
 *
 * **凭据边界（用户拍板：仅内部使用，绝不外泄）**：本类只把 Cookie / bdToken 用于发请求，
 * 对外返回的一切文本都不含凭据。任何新增方法返回前都要守住这条。
 *
 * 账号每次都从偏好重读：这样用户在 App 里登录/切换账号后，MCP 侧不用重启服务就能跟随。
 * 项目 ID 刻意**不**在这里猜——由 AI 先调 `list_projects` 自己选（用户拍板），
 * 所以本类的方法都显式收 projectId。
 */
internal class AiStudioBridge(
    private val preferences: AppPreferences,
) {

    private val client = AiStudioClient()

    /** 令牌刷新器：与界面用的是同一套（403 → 抓 `/overview` 抠新 bdToken）。 */
    private val refresher = AiStudioTokenRefresher()

    /**
     * 把刷新器与"刷新后回写偏好"接到终端那侧的 client 上。
     *
     * 两个 client（平台 HTTP 与 Jupyter 终端）共用同一个刷新器实例：底层是同一个 bdToken，
     * 各建一份只会各自拿一份"更新后"的令牌，反而互相覆盖——与 ViewModel 里同一约定。
     */
    fun bindKernel(kernel: com.local.comfyuimobile.network.AiStudioKernelClient) {
        kernel.tokenRefresher = refresher
        kernel.onAccountRefreshed = { updated ->
            persistRefreshed(updated)
            updated
        }
    }

    init {
        client.tokenRefresher = refresher
        // 刷新成功后把新令牌写回偏好。这里没有 ViewModel 的内存态，只能直接落盘；
        // 下次界面恢复状态时读到的就是新令牌（否则界面会拿旧令牌继续 403）。
        client.onAccountRefreshed = { updated ->
            persistRefreshed(updated)
            updated
        }
    }

    /** 取当前生效的账号（`aiStudioActiveId` 指的那个；为空时取第一个）。 */
    private suspend fun activeAccount(): AiStudioAccount? {
        val stored = runCatching { preferences.settings.first() }.getOrNull() ?: return null
        val accounts = stored.aiStudioAccounts
        if (accounts.isEmpty()) return null
        return accounts.firstOrNull { it.id == stored.aiStudioActiveId } ?: accounts.first()
    }

    private suspend fun requireAccount(): AiStudioAccount = activeAccount()
        ?: throw IllegalStateException(
            "AI Studio 尚未登录。请先在 App 的「账号」页登录百度 AI Studio。",
        )

    /**
     * 刷新成功后把新令牌写回偏好。
     *
     * 这里没有 ViewModel 的内存态，只能直接落盘；不落盘的话，下一次 [activeAccount]
     * 又会从偏好里读到旧令牌，每个请求都要先失败一次再重试。
     *
     * 用 runBlocking 是因为 [AiStudioClient.onAccountRefreshed] 是**非挂起**回调
     * （它的签名服务着 ViewModel，不便改），而 DataStore 的 edit 是挂起函数。
     * 这不在主线程上：MCP server 整个跑在 IO 工作线程里（见 [McpServer]），
     * 所以干等几十毫秒的磁盘写入不会造成 ANR。
     */
    private fun persistRefreshed(updated: AiStudioAccount) {
        runBlocking {
            runCatching { preferences.updateAiStudioAccount(updated) }
                .onFailure { AppLogger.warn("MCP 侧回写刷新后的令牌失败：${it.message.orEmpty()}") }
        }
    }

    // ===== 项目 =====

    /**
     * 取当前正在运行的项目（连同它的账号）。
     *
     * 终端与"等 ComfyUI 就绪"都要求项目**真的在跑**（否则没有 endpoint、也没有 8188）。
     * 项目 ID 不在偏好里，所以这里从项目列表里取运行中的那个：与启动 GPU 一样，
     * 用户不必先回 App 选项目。
     */
    suspend fun runningProject(): Pair<AiStudioAccount, com.local.comfyuimobile.model.AiStudioProject>? {
        val account = activeAccount() ?: return null
        val page = runCatching { client.listProjects(account, page = 1, pageSize = 30) }.getOrNull() ?: return null
        val running = page.projects.firstOrNull { it.running } ?: return null
        return account to running
    }

    /** 列出项目，返回给模型的文本（**不含**任何凭据）。 */
    suspend fun listProjects(): String {
        val account = requireAccount()
        val page = client.listProjects(account, page = 1, pageSize = 30)
        if (page.projects.isEmpty()) return "当前账号下没有项目。"
        return buildString {
            appendLine("账号「${account.displayName()}」下的项目（共 ${page.projects.size} 个）：")
            page.projects.forEach { project ->
                val state = if (project.running) {
                    "运行中" + project.runningGpuLabel.takeIf { it.isNotBlank() }?.let { "（$it）" }.orEmpty()
                } else {
                    "已停止"
                }
                appendLine("- projectId=${project.projectId}  ${project.displayName()}  [$state]")
            }
            append("用 projectId 调 gpu_status / start_gpu / stop_gpu。")
        }
    }

    // ===== GPU 档位 =====

    /**
     * 读某项目当前可选的档位与运行状态。
     *
     * 档位走 `list_schedules`（平台原生），运行档位走 `/studio/project/detail` 的
     * `runningClusterInfo.displayName`——后者只有项目真的在跑时才带，是最可靠的来源。
     */
    suspend fun gpuStatus(projectId: String): String {
        val account = requireAccount()
        val pid = requireProjectId(projectId)
        // 先单独查项目本身：区分 stopped / submitting / running / cookie_expired
        // （清单 §3.1 要求）。取项目列表可能因登录失效而失败，那种情况要说清楚是登录问题，
        // 不能笼统报成"查不到状态"。
        var loginExpired = false
        val project = runCatching { client.listProjects(account, page = 1, pageSize = 30) }
            .onFailure { error ->
                if (error is CancellationException) throw error
                loginExpired = (error as? AiStudioException)?.errorCode == 403
            }
            .getOrNull()
            ?.projects?.firstOrNull { it.projectId == pid }
        if (loginExpired) {
            return "项目 $pid 状态：${AiStudioSchedules.describeState(AiStudioSchedules.ProjectState.COOKIE_EXPIRED)}"
        }
        if (project == null) {
            return "找不到项目 $pid。请先用 list_projects 确认项目 ID" +
                "（ID 不存在时平台不报错、只返回空列表，所以必须显式挡一下）。"
        }

        val running = project.running
        val environmentReady = runCatching { client.fetchRunningGpuLabel(account, pid) }
            .getOrNull()?.isNotBlank() == true
        val state = AiStudioSchedules.projectState(
            loginExpired = false,
            running = running,
            environmentReady = environmentReady,
            starting = false,
        )

        val schedules = runCatching { client.listSchedules(account, pid) }
            .getOrElse { error -> throw describe("读取算力档位", error) }
        return buildString {
            appendLine("项目 $pid（${project.displayName()}）：")
            appendLine("- 状态：${AiStudioSchedules.describeState(state)}")
            appendLine("- 当前档位：${project.runningGpuLabel.ifBlank { "未运行或读不到" }}")
            appendLine("- 可选档位（共 ${schedules.size} 个）：")
            if (schedules.isEmpty()) {
                appendLine("  （读不到档位——可能是登录态失效，或该项目当前不可选档）")
            } else {
                schedules.forEach { schedule -> appendLine("  " + AiStudioSchedules.describe(schedule)) }
            }
            append("启动用 start_gpu，schedule 取上面各项的 schedule= 值。")
        }
    }

    /** 列出某项目可选的 GPU 档位。 */
    suspend fun listGpuOptions(projectId: String): String {
        val account = requireAccount()
        val pid = requireProjectId(projectId)
        val schedules = runCatching { client.listSchedules(account, pid) }
            .getOrElse { error -> throw describe("读取算力档位", error) }
        if (schedules.isEmpty()) return "读不到项目 $pid 的可选档位（可能登录态失效或该项目不可选档）。"
        return buildString {
            appendLine("项目 $pid 可选档位：")
            schedules.forEach { appendLine("- " + AiStudioSchedules.describe(it)) }
            append("start_gpu 的 schedule 参数取上面各项的 schedule= 值。")
        }
    }

    /**
     * 启动项目环境（占一张 GPU）。
     *
     * 档位名**必须**是 [listGpuOptions] 给出的 `schedule=` 值：`startProject` 会原样
     * 透传给平台，传错时平台不会报错、而是按默认档启动——用户看到的是"选了 A100
     * 结果开了 V100"，且账单照扣。所以这里先校验再提交。
     */
    suspend fun startGpu(projectId: String, scheduleName: String?): String {
        val account = requireAccount()
        val pid = requireProjectId(projectId)
        val wanted = scheduleName?.trim().orEmpty()
        val schedules = runCatching { client.listSchedules(account, pid) }
            .getOrElse { error -> throw describe("读取算力档位", error) }
        // 精确匹配，因为 startProject 把档位名原样透传给平台：传错不报错，直接按
        // 默认档启动（用户以为选了 A100，账单却是 V100）。匹配失败要早报而不是猜。
        val chosen = AiStudioSchedules.choose(schedules, wanted)
            ?: throw IllegalStateException(
                "读不到项目 $pid 的可选档位，无法启动（可能是登录态失效或该项目当前不可选档）。" +
                    "请先用 list_gpu_options 确认。",
            )
        val result = runCatching { client.startProject(account, pid, chosen.scheduleName) }
            .getOrElse { error -> throw describe("启动项目", error) }
        return buildString {
            appendLine("已提交启动请求：项目 $pid，档位 ${chosen.displayName()}（schedule=${chosen.scheduleName}）。")
            append("启动需要 1-2 分钟。之后可用 wait_for_comfy 等 ComfyUI 就绪，或 terminal 工具看启动日志。")
            if (result.isNotBlank()) appendLine().append(result)
        }
    }

    /** 停止项目环境（省钱：不停会一直扣算力卡）。 */
    suspend fun stopGpu(projectId: String): String {
        val account = requireAccount()
        val pid = requireProjectId(projectId)
        val result = runCatching { client.stopProject(account, pid) }
            .getOrElse { error -> throw describe("停止项目", error) }
        return "已提交停止请求：项目 $pid。$result"
    }

    // ===== 内部 =====

    private fun requireProjectId(raw: String): String {
        val pid = raw.trim()
        if (pid.isBlank()) {
            throw IllegalArgumentException("缺少 projectId。先用 list_projects 查项目 ID。")
        }
        return pid
    }

    /**
     * 把平台错误转成模型能据以行动的话。
     *
     * 8407（风控）与 403（令牌/登录）是最常见的两类，且**含义完全不同**：前者等一会
     * 儿重试就好，后者要用户重新登录。混成一句"请求失败"会让 AI 白重试很多轮。
     */
    private fun describe(action: String, error: Throwable): Throwable {
        if (error is CancellationException) throw error
        val code = (error as? AiStudioException)?.errorCode
        val hint = when {
            AiStudioRiskControl.isRetryable(code) -> AiStudioRiskControl.messageFor(code, null)
            code == 403 -> "登录态或令牌已失效——请在 App 的「账号」页重新登录后重试。"
            else -> null
        }
        val base = "$action 失败：" + (error.message ?: "未知错误")
        return IllegalStateException(if (hint.isNullOrBlank()) base else "$base\n$hint", error)
    }
}
