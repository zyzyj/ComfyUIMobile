package com.local.comfyuimobile

import android.app.Application
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import coil.ImageLoader
import coil.ImageLoaderFactory
import com.local.comfyuimobile.data.AppLogger
import com.local.comfyuimobile.data.AuthCookieProvider
import com.local.comfyuimobile.service.JobMonitorService
import com.local.comfyuimobile.update.UpdateManager
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

class ComfyMobileApplication : Application(), ImageLoaderFactory {
    override fun onCreate() {
        super.onCreate()
        AppLogger.initialize(this)
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(JobMonitorService.CHANNEL_ID, getString(R.string.monitor_channel), NotificationManager.IMPORTANCE_LOW),
        )
        manager.createNotificationChannel(
            NotificationChannel(
                JobMonitorService.COMPLETION_CHANNEL_ID,
                getString(R.string.completion_channel),
                NotificationManager.IMPORTANCE_HIGH,
            ).apply {
                description = getString(R.string.completion_channel_description)
                enableVibration(true)
                setShowBadge(true)
                lockscreenVisibility = Notification.VISIBILITY_PUBLIC
            },
        )
        manager.createNotificationChannel(
            NotificationChannel(UpdateManager.CHANNEL_ID, getString(R.string.update_channel), NotificationManager.IMPORTANCE_DEFAULT),
        )
    }

    /**
     * Coil 图片加载全局配置：给网络层挂上认证 Cookie 拦截器。
     * 云端反向代理（AI Studio 等）要求 /view 等图片请求携带登录态，而 Coil 默认
     * 网络栈不经过 App 的 OkHttp 拦截器，导致图片 404。这里统一注入。
     */
    override fun newImageLoader(): ImageLoader = ImageLoader.Builder(this)
        .okHttpClient { imageOkHttpClient() }
        .build()

    companion object {
        /**
         * 带认证 Cookie 拦截器的 OkHttpClient，供 Coil 图片加载使用。
         * Cookie 来自 AuthCookieProvider（连接服务器时由 MainViewModel 更新）。
         */
        @Volatile
        private var cachedImageClient: OkHttpClient? = null

        fun imageOkHttpClient(): OkHttpClient = cachedImageClient ?: synchronized(this) {
            cachedImageClient ?: OkHttpClient.Builder()
                .connectTimeout(20, TimeUnit.SECONDS)
                .readTimeout(60, TimeUnit.SECONDS)
                .addInterceptor { chain ->
                    val cookie = AuthCookieProvider.current
                    val original = chain.request()
                    if (cookie.isBlank()) {
                        chain.proceed(original)
                    } else {
                        chain.proceed(original.newBuilder().header("Cookie", cookie).build())
                    }
                }
                .build()
                .also { cachedImageClient = it }
        }
    }
}
