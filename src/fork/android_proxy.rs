// 应用内代理模块（Kotlin: monitor-proxy）需要的 RustDesk 能力，通过 JNI 提供。
// 代理不改 RustDesk 的任何配置（服务器地址、SOCKS 设置都不动）：直连不通时，Kotlin 侧让 Xray 在
// 127.0.0.1 上用和服务器相同的端口开 dokodemo-door，这里只负责把"连接服务器"的目标换成本地地址。
use hbb_common::{
    config::{get_online_state, Config},
    socket_client::split_host_port,
};
use jni::{
    objects::JClass,
    sys::{jboolean, jstring, JNI_TRUE},
    JNIEnv,
};
use std::sync::atomic::{AtomicBool, Ordering};

const LOCALHOST: &str = "127.0.0.1";

static REDIRECT: AtomicBool = AtomicBool::new(false);

pub fn redirecting() -> bool {
    REDIRECT.load(Ordering::SeqCst)
}

// 注册服务器列表：保留端口，主机换成本地
pub fn redirect_servers(servers: Vec<String>) -> Vec<String> {
    if !redirecting() {
        return servers;
    }
    servers
        .into_iter()
        .map(|s| match split_host_port(&s) {
            Some((_, port)) => format!("{LOCALHOST}:{port}"),
            None => LOCALHOST.to_owned(),
        })
        .collect()
}

// 中继服务器：假定和 hbbs 在同一台机器上，端口不变
pub fn redirect_relay(relay_server: String) -> String {
    if !redirecting() {
        return relay_server;
    }
    match split_host_port(&relay_server) {
        Some((_, port)) => format!("{LOCALHOST}:{port}"),
        None => LOCALHOST.to_owned(),
    }
}

#[no_mangle]
pub extern "system" fn Java_com_carriez_flutter_1hbb_ProxyNative_isOnline(
    _env: JNIEnv,
    _class: JClass,
) -> jboolean {
    (get_online_state() > 0) as jboolean
}

#[no_mangle]
pub extern "system" fn Java_com_carriez_flutter_1hbb_ProxyNative_getUserProxy(
    mut env: JNIEnv,
    _class: JClass,
) -> jstring {
    let proxy = crate::ui_interface::get_socks()
        .into_iter()
        .next()
        .unwrap_or_default();
    match env.new_string(proxy) {
        Ok(s) => s.into_raw(),
        Err(_) => std::ptr::null_mut(),
    }
}

#[no_mangle]
pub extern "system" fn Java_com_carriez_flutter_1hbb_ProxyNative_getServerHost(
    mut env: JNIEnv,
    _class: JClass,
) -> jstring {
    let host = Config::get_rendezvous_servers()
        .into_iter()
        .next()
        .map(|s| split_host_port(&s).map(|(h, _)| h).unwrap_or(s))
        .unwrap_or_default();
    match env.new_string(host) {
        Ok(s) => s.into_raw(),
        Err(_) => std::ptr::null_mut(),
    }
}

#[no_mangle]
pub extern "system" fn Java_com_carriez_flutter_1hbb_ProxyNative_setRedirect(
    _env: JNIEnv,
    _class: JClass,
    enabled: jboolean,
) {
    let enabled = enabled == JNI_TRUE;
    if REDIRECT.swap(enabled, Ordering::SeqCst) != enabled {
        crate::rendezvous_mediator::RendezvousMediator::restart();
    }
}
