package com.local.comfyuimobile

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.addCallback
import androidx.core.view.WindowCompat
import androidx.lifecycle.lifecycleScope
import com.local.comfyuimobile.bridge.AiStudioLoginSession
import com.local.comfyuimobile.data.AppLogger
import com.local.comfyuimobile.network.AiStudioProtocol
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * AI Studio 内置登录页。
 *
 * v0.1.91：直接加载**百度官方登录页**（passport.baidu.com），而不是 AI Studio
 * 首页。用户进来第一眼就是登录界面，手机号 + 短信验证码、扫码、账密都在那里——
 * 百度的登录协议涉及验证码图片与风控，App 侧逆向它既脆弱又容易触发封号；
 * 官方登录页本身就是完整的手机验证码登录流程，WebView 里走它是唯一稳妥路径。
 *
 * `u=` 参数让登录成功后跳回 AI Studio，此时 Cookie 已落到 baidu.com 域下，
 * 轮询器发现 BDUSS 就**自动结束登录**（不用用户找「完成登录」按钮）。
 *
 * 安全边界不变：不注册任何 JavaScript 接口，账号/密码/验证码只经过百度自己的
 * 页面，App 只在登录完成后读取 Cookie。
 */
class AiStudioLoginActivity : ComponentActivity() {

    private lateinit var webView: WebView
    private lateinit var status: TextView
    private lateinit var doneButton: Button
    private var pollJob: Job? = null
    @Volatile private var loggedIn = false

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, true)
        AiStudioLoginSession.clear()

        webView = WebView(this).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.databaseEnabled = true
            // 百度登录页对 WebView UA 会给降级版页面；去掉 wv 标记让它给完整版。
            settings.userAgentString = settings.userAgentString
                ?.replace("; wv", "")
                ?.replace(Regex("Version/\\d+\\.\\d+ "), "")
            webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView?, url: String?) {
                    checkLoginState()
                }

                override fun shouldOverrideUrlLoading(view: WebView?, url: String?): Boolean {
                    // 登录成功后 passport 会 302 到 u= 指定的回跳地址；
                    // 我们在轮询里发现登录态，不需要拦截，WebView 自己跳就行。
                    return false
                }
            }
        }

        setContentView(buildContentView())
        onBackPressedDispatcher.addCallback(this) { finish() }

        val cookieManager = CookieManager.getInstance()
        cookieManager.setAcceptCookie(true)
        cookieManager.setAcceptThirdPartyCookies(webView, true)
        webView.loadUrl(LOGIN_URL)

        pollJob = lifecycleScope.launch {
            while (true) {
                delay(1_200)
                checkLoginState()
            }
        }
    }

    private fun buildContentView(): View {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.WHITE)
        }

        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(32, 32, 24, 24)
            gravity = Gravity.CENTER_VERTICAL
        }
        val title = TextView(this).apply {
            text = "登录百度账号"
            setTextColor(Color.rgb(24, 24, 28))
            textSize = 18f
        }
        doneButton = Button(this).apply {
            text = "完成"
            isEnabled = false
            setOnClickListener { submitLogin() }
        }
        bar.addView(
            title,
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f),
        )
        bar.addView(doneButton)
        root.addView(bar)

        status = TextView(this).apply {
            text = "输入手机号获取短信验证码，或扫码 / 账号密码登录。\n登录成功会自动完成，无需手动操作。"
            setTextColor(Color.rgb(110, 110, 118))
            textSize = 13f
            setPadding(32, 0, 32, 20)
        }
        root.addView(status)

        root.addView(
            FrameLayout(this).apply { addView(webView) },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f),
        )
        return root
    }

    /**
     * 登录态判定：Cookie 里出现 BDUSS 即视为已登录。
     *
     * 登录成功**自动完成**：直接提交并结束页面。用户不需要找按钮——
     * 这个按钮只留作「自动检测失灵时」的手动兜底。
     */
    private fun checkLoginState() {
        if (loggedIn) return
        val cookie = currentCookie()
        if (AiStudioProtocol.looksLoggedIn(cookie)) {
            loggedIn = true
            runOnUiThread {
                status.text = "登录成功，正在保存账号…"
                status.setTextColor(Color.rgb(40, 150, 80))
                doneButton.isEnabled = true
            }
            submitLogin()
        }
    }

    private fun currentCookie(): String = runCatching {
        CookieManager.getInstance().getCookie("https://aistudio.baidu.com")
            ?: CookieManager.getInstance().getCookie("https://baidu.com")
    }.getOrDefault("").orEmpty()

    /**
     * 提交登录结果。
     *
     * 除了 Cookie，还要取页面注入的 `window.aiStudio.bdToken`（业务接口要放进
     * `x-studio-token` 头）。此时 WebView 若已跳回 AI Studio 域，页面里就有这个
     * 值；取不到不阻断——部分接口不校验，缺了让接口报错再提示重新登录。
     */
    private fun submitLogin() {
        val cookie = currentCookie()
        if (!AiStudioProtocol.looksLoggedIn(cookie)) {
            status.text = "还没有检测到登录态，请先完成登录。"
            status.setTextColor(Color.rgb(210, 70, 70))
            return
        }
        doneButton.isEnabled = false
        // 延迟一拍让平台自己的登录跳转先走完，页面全局变量才注入完成。
        lifecycleScope.launch {
            delay(1_500)
            val probe = runCatching { readPageIdentity() }.getOrDefault(PageIdentity())
            AppLogger.info(
                "AI Studio 登录完成：Cookie 长度=${cookie.length}，" +
                    "bdToken=${if (probe.bdToken.isBlank()) "未取到" else "已取到"}，" +
                    "uid=${probe.uid.ifBlank { "未取到" }}，昵称=${probe.nickname.ifBlank { "未取到" }}",
            )
            AiStudioLoginSession.complete(cookie, probe.bdToken, probe.uid, probe.nickname)
            setResult(Activity.RESULT_OK, Intent())
            finish()
        }
    }

    private data class PageIdentity(
        val bdToken: String = "",
        val uid: String = "",
        val nickname: String = "",
    )

    /**
     * 一次性从页面全局变量里取出 bdToken 与用户 id / 昵称。
     *
     * 平台把登录信息直接注入在 `window.aiStudio` 上（前端自己就是这么读的），
     * 比调接口猜测字段名可靠得多。用一段 JS 返回 JSON 字符串，只在 App 侧解析。
     */
    private suspend fun readPageIdentity(): PageIdentity {
        val script = """
            (function(){
              try {
                var a = window.aiStudio || {};
                var u = a.userInfo || {};
                if (typeof u === 'string') { try { u = JSON.parse(u); } catch(e) { u = {}; } }
                return JSON.stringify({
                  bdToken: String(a.bdToken || ''),
                  uid: String(u.id == null ? '' : u.id),
                  nickname: String(u.nickname || u.userName || u.name || '')
                });
              } catch(e) { return '{}'; }
            })()
        """.trimIndent()
        val raw = suspendCancellableCompat(script)
        if (raw.isBlank()) return PageIdentity()
        return runCatching {
            val obj = org.json.JSONObject(raw)
            PageIdentity(
                bdToken = obj.optString("bdToken"),
                uid = obj.optString("uid"),
                nickname = obj.optString("nickname"),
            )
        }.getOrDefault(PageIdentity())
    }

    /** evaluateJavascript 的协程封装：单次求值，10 秒超时兜底。 */
    private suspend fun suspendCancellableCompat(script: String): String =
        kotlinx.coroutines.suspendCancellableCoroutine { cont ->
            runCatching {
                webView.evaluateJavascript(script) { encoded ->
                    val raw = encoded?.trim().orEmpty()
                    val value = if (raw.isEmpty() || raw == "null") ""
                    else runCatching { org.json.JSONArray("[$raw]").getString(0) }.getOrDefault("")
                    if (cont.isActive) cont.resumeWith(Result.success(value))
                }
            }.onFailure {
                if (cont.isActive) cont.resumeWith(Result.failure(it))
            }
        }

    override fun onDestroy() {
        pollJob?.cancel()
        runCatching {
            (webView.parent as? ViewGroup)?.removeView(webView)
            webView.stopLoading()
            webView.destroy()
        }
        super.onDestroy()
    }

    override fun onPause() {
        super.onPause()
        webView.onPause()
    }

    override fun onResume() {
        super.onResume()
        webView.onResume()
    }

    private companion object {
        /**
         * 移动版登录页。
         *
         * 踩过的坑：`passport.baidu.com/v2/?login&tpl=mn` 在**手机 UA** 下不渲染
         * 登录表单，只给一个「赶快来登录」宣传页（真机截图已确认）。百度移动端
         * 的登录表单在 `wappass.baidu.com/passport/`，它会跳到 `#/insert_account`，
         * 页面就是一个「请输入手机号/用户名/邮箱」输入框 + 下一步——正是我们要的。
         *
         * `u=` 参数让登录成功后回跳 AI Studio，Cookie 才会落到对应域下。
         */
        const val LOGIN_URL =
            "https://wappass.baidu.com/passport/?login&u=" +
                "https%3A%2F%2Faistudio.baidu.com%2F"
    }
}
