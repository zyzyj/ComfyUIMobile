package com.local.comfyuimobile.bridge

/**
 * 内置登录的一次性结果传递。
 *
 * 与 [AdvancedEditorSession] 同一套路：Activity 之间不传大对象，用一个进程内的
 * 单例暂存「输入/输出」，Activity 结束前写、调用方回来读。
 *
 * 这里暂存的是**账号凭据**（Cookie + bdToken），读完立刻清空，不在内存里长留。
 */
data class AiStudioLoginResult(
    val cookie: String,
    val bdToken: String,
    /**
     * 从页面 `window.aiStudio.userInfo` 直接取到的用户 id / 昵称。
     *
     * 不再猜接口：平台把登录用户信息直接注入在页面全局变量里，登录完成那一刻
     * 读它最准。取不到就留空，由调用方退回用资料接口或占位名。
     */
    val uid: String = "",
    val nickname: String = "",
)

object AiStudioLoginSession {
    @Volatile private var pending: AiStudioLoginResult? = null

    fun complete(cookie: String, bdToken: String, uid: String = "", nickname: String = "") {
        pending = AiStudioLoginResult(cookie, bdToken, uid, nickname)
    }

    /** 取走结果并清空，避免下次登录误用上一次的凭据。 */
    @Synchronized
    fun consume(): AiStudioLoginResult? = pending.also { pending = null }

    @Synchronized
    fun clear() {
        pending = null
    }
}
