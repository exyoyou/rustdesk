package com.carriez.flutter_hbb

import androidx.annotation.Keep

/**
 * JNI 入口：Rust（libs/scrap/src/android/monitor.rs）实现，给屏幕监控模块提供 RustDesk 的数据。
 * 独立成文件，避免修改上游的 FFI.kt。
 */
@Keep
object MonitorNative {
    init {
        System.loadLibrary("rustdesk")
    }

    /** 本机的 RustDesk ID，失败时返回 null */
    @JvmStatic
    external fun getMyId(): String?
}
