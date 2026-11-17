// 非上游代码：本 fork 新增的 Rust 模块都放在这里，上游文件里只留 `[fork]` 标记的钩子。

// [monitor-proxy] Android 应用内代理的 JNI 钩子
#[cfg(target_os = "android")]
pub mod android_proxy;

// 屏幕监控模块需要的 RustDesk 数据（设备 ID）
#[cfg(target_os = "android")]
mod android_monitor;
