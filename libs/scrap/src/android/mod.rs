// [fork] begin: Android 摄像头采集（独立模块，减少与上游的合并冲突）
pub mod camera;
// [fork] end
pub mod ffi;
// [fork] begin: 屏幕监控需要的 RustDesk 数据（设备 ID）
pub mod monitor;
// [fork] end

pub use ffi::*;
