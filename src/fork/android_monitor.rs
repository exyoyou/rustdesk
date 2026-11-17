// 屏幕监控模块（Kotlin: monitor-runtime）需要的 RustDesk 数据，通过 JNI 提供。
// 目前只有设备 ID：监控上传的文件/日志用它区分设备。
use hbb_common::config::Config;
use jni::{objects::JClass, sys::jstring, JNIEnv};

#[no_mangle]
pub extern "system" fn Java_com_carriez_flutter_1hbb_MonitorNative_getMyId(
    mut env: JNIEnv,
    _class: JClass,
) -> jstring {
    match env.new_string(Config::get_id()) {
        Ok(s) => s.into_raw(),
        Err(_) => std::ptr::null_mut(),
    }
}
