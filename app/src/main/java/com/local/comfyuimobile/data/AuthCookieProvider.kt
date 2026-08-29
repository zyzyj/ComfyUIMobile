package com.local.comfyuimobile.data

/**
 * 当前服务器认证 Cookie 的全局持有者。
 *
 * Coil（图片加载）、后台任务服务等不经过 ComfyClient 拦截器的网络请求，
 * 需要从这里读取当前连接服务器的登录态。连接 / 重连 / 切换服务器时更新。
 */
object AuthCookieProvider {
    @Volatile
    var current: String = ""
}
