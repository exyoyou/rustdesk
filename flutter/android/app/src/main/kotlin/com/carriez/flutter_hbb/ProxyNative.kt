package com.carriez.flutter_hbb

import androidx.annotation.Keep

/**
 * JNI 入口：Rust（src/android_proxy_hook.rs）实现，给应用内代理模块（monitor-proxy）读写 RustDesk 的代理和在线状态。
 * 独立成文件，避免修改上游的 FFI.kt。
 */
@Keep
object ProxyNative {
    init {
        System.loadLibrary("rustdesk")
    }

    /** 是否已在 hbbs 注册成功 */
    @JvmStatic
    external fun isOnline(): Boolean

    /** 用户在 RustDesk 里自己设置的 SOCKS5 代理，没有则为空串 */
    @JvmStatic
    external fun getUserProxy(): String?

    /** 当前配置的 ID 服务器主机（不含端口） */
    @JvmStatic
    external fun getServerHost(): String?

    /** 把 RustDesk 连接服务器的目标换成 127.0.0.1（端口不变）或恢复，状态变化时会触发重连。不改任何配置 */
    @JvmStatic
    external fun setRedirect(enabled: Boolean)
}
