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
 * v0.1.90：用户不必再去电脑浏览器复制 Cookie——在这里登录一次，App 直接
 * 从 WebView 的 CookieManager 里取出登录态（核心是 BDUSS）以及页面注入的
 * bdToken，写进账号存储即可。
 *
 * 刻意**不注册任何 JavaScript 接口**：账号密码只经过百度自己的登录页，
 * App 不解析、不留存，登录完成只取 Cookie。
 *
 * v0.1.63 曾有过一版内置登录、v0.1.64 又按用户反馈移除。这一版与当时的关键
 * 区别是：**只有用户主动点「登录 AI Studio」才进入本页**，直连局域网 ComfyUI
 * 的用户完全不受影响；同时补了「登录中」的自动识别，不用用户自己判断何时算登录成功。
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
        window.statusBarColor = Color.rgb(30, 30, 36)
        window.navigationBarColor = Color.rgb(18, 18, 22)

        AiStudioLoginSession.clear()

        webView = WebView(this).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.databaseEnabled = true
            settings.userAgentString = settings.userAgentString
                ?.replace("; wv", "")
                ?.replace(Regex("Version/\\d+\\.\\d+ "), "")
            webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView?, url: String?) {
                    checkLoginState()
                }
            }
        }

        setContentView(buildContentView())
        onBackPressedDispatcher.addCallback(this) { finish() }

        val cookieManager = CookieManager.getInstance()
        cookieManager.setAcceptCookie(true)
        cookieManager.setAcceptThirdPartyCookies(webView, true)
        webView.loadUrl(AiStudioProtocol.BASE_URL + "/")

        pollJob = lifecycleScope.launch {
            while (true) {
                delay(1_500)
                checkLoginState()
            }
        }
    }

    private fun buildContentView(): View {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.rgb(18, 18, 22))
        }

        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(24, 28, 24, 20)
            gravity = Gravity.CENTER_VERTICAL
        }
        val close = Button(this).apply {
            text = "关闭"
            setOnClickListener { finish() }
        }
        val title = TextView(this).apply {
            text = "登录百度 AI Studio"
            setTextColor(Color.WHITE)
            textSize = 17f
        }
        doneButton = Button(this).apply {
            text = "完成登录"
            isEnabled = false
            setOnClickListener { submitLogin() }
        }
        bar.addView(close)
        bar.addView(
            title,
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginStart = 16
            },
        )
        bar.addView(doneButton)
        root.addView(bar)

        status = TextView(this).apply {
            text = "请在下方的百度页面完成登录（扫码或账号密码均可）。\n登录成功后按钮会自动亮起。"
            setTextColor(Color.rgb(170, 170, 178))
            textSize = 13f
            setPadding(24, 0, 24, 16)
        }
        root.addView(status)

        root.addView(
            FrameLayout(this).apply { addView(webView) },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f),
        )
        return root
    }

    /** 轮询/页面加载完成时调用：Cookie 里出现 BDUSS 即视为已登录。 */
    private fun checkLoginState() {
        if (loggedIn) return
        val cookie = currentCookie()
        if (AiStudioProtocol.looksLoggedIn(cookie)) {
            loggedIn = true
            runOnUiThread {
                doneButton.isEnabled = true
                status.text = "已检测到登录态，点右上角「完成登录」保存到 App。"
                status.setTextColor(Color.rgb(120, 200, 140))
            }
        }
    }

    private fun currentCookie(): String = runCatching {
        CookieManager.getInstance().getCookie(AiStudioProtocol.BASE_URL)
    }.getOrDefault("").orEmpty()

    /**
     * 用户点「完成登录」。
     *
     * 除了 Cookie，还要尽量取到页面注入的 `window.aiStudio.bdToken`——业务接口
     * 要用它填 `x-studio-token` 头。取不到不阻断（部分接口不校验），只记一条日志。
     */
    private fun submitLogin() {
        val cookie = currentCookie()
        if (!AiStudioProtocol.looksLoggedIn(cookie)) {
            status.text = "还没有检测到登录态，请先在页面里完成登录。"
            status.setTextColor(Color.rgb(230, 120, 120))
            return
        }
        doneButton.isEnabled = false
        status.text = "正在读取登录凭据…"
        webView.evaluateJavascript(
            "(function(){try{return String((window.aiStudio&&window.aiStudio.bdToken)||'');}catch(e){return '';}})()",
        ) { encoded ->
            val bdToken = decodeJsString(encoded)
            AppLogger.info(
                "AI Studio 登录完成：Cookie 长度=${cookie.length}，bdToken=${if (bdToken.isBlank()) "未取到" else "已取到"}",
            )
            AiStudioLoginSession.complete(cookie, bdToken)
            setResult(Activity.RESULT_OK, Intent())
            finish()
        }
    }

    /** WebView 的 evaluateJavascript 回传是 JSON 字面量（带引号），要解一层。 */
    private fun decodeJsString(encoded: String?): String {
        val raw = encoded?.trim().orEmpty()
        if (raw.isEmpty() || raw == "null") return ""
        return runCatching {
            org.json.JSONArray("[$raw]").getString(0)
        }.getOrDefault("")
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
}
