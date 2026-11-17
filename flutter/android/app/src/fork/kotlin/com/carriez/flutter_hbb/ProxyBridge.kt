package com.carriez.flutter_hbb

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.util.Log
import java.util.concurrent.Executors
import youyou.monitor.proxy.ProxyHost
import youyou.monitor.proxy.ProxyRuntime

/**
 * 应用内代理（monitor-proxy）和 RustDesk 之间的桥接（钩子）。
 * 由 [CaptureController] 在服务上线/下线时调用 [start] / [stop]。
 * 代理的启停、直连/隧道切换都在 monitor-proxy 里，这里只把 [ProxyHost] 接到 [ProxyNative]。
 * 加载内核可能失败（如缺少原生库），失败只影响代理，不影响直连。
 */
object ProxyBridge : ProxyHost {
    private const val LOG_TAG = "LOG_PROXY_BRIDGE"

    private val executor = Executors.newSingleThreadExecutor { Thread(it, "ProxyBridge") }

    @Volatile
    private var failed = false

    @Volatile
    private var appContext: Context? = null

    fun start(context: Context) {
        if (failed) return
        val appContext = context.applicationContext
        this.appContext = appContext
        executor.execute {
            try {
                ProxyRuntime.start(appContext, this)
            } catch (e: Throwable) {
                failed = true
                Log.e(LOG_TAG, "proxy start failed", e)
            }
        }
    }

    fun stop() {
        if (failed) return
        executor.execute {
            try {
                ProxyRuntime.stop()
            } catch (e: Throwable) {
                Log.e(LOG_TAG, "proxy stop failed", e)
            }
        }
    }

    override fun isOnline(): Boolean = ProxyNative.isOnline()

    override fun userProxy(): String = ProxyNative.getUserProxy() ?: ""

    override fun serverHost(): String = ProxyNative.getServerHost() ?: ""

    override fun hasNetwork(): Boolean {
        val cm = appContext?.getSystemService(ConnectivityManager::class.java) ?: return true
        val caps = cm.getNetworkCapabilities(cm.activeNetwork ?: return false) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    override fun setRedirect(enabled: Boolean) = ProxyNative.setRedirect(enabled)
}
