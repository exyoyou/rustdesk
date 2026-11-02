// Android 摄像头采集（Rust 侧）。
//
// 整体流程：
//   1. Rust 通过 JNI 调用 MainService.rustGetByName("camera_info") 获取摄像头列表（Kotlin: CameraHook）；
//   2. 创建采集器时调用 rustSetByName("start_camera", 下标)，Kotlin 的 CameraCaptureService 打开 Camera2 和麦克风；
//   3. Kotlin 每帧把 YUV 平面通过 CameraNative.onYuv 传回来，这里用 libyuv 转成 RGBA 并旋转，存入 LATEST_FRAME；
//   4. 视频服务循环调用 frame() 取走最新一帧去编码。
// 摄像头帧单独走自己的 JNI，不和屏幕录制的 VIDEO_RAW 共用，两者互不影响。
use super::ffi::{call_main_service_get_by_name, call_main_service_set_by_name};
use crate::{Frame, PixelBuffer, TraitCapturer};
use base::message_proto::{DisplayInfo, Resolution};
use hbb_common::{bail, ResultType};
use jni::{
    objects::{JByteBuffer, JClass},
    sys::jint,
    JNIEnv,
};
use lazy_static::lazy_static;
use serde::Deserialize;
use std::{
    io,
    sync::{
        atomic::{AtomicUsize, Ordering},
        Mutex,
    },
    time::Duration,
};

/// Kotlin `CameraHook.get("camera_info")` 返回的 JSON 数组元素（宽高已是旋转正的竖屏尺寸）
#[derive(Deserialize)]
struct CameraInfo {
    name: String,
    width: i32,
    height: i32,
}

/// 最新一帧（RGBA）。JNI 线程写入，采集器 frame() 读取。fresh=true 表示这帧还没被取走。
#[derive(Default)]
struct LatestFrame {
    data: Vec<u8>,
    width: usize,
    height: usize,
    fresh: bool,
}

lazy_static! {
    // 最近一次查询到的摄像头列表缓存
    static ref SYNC_CAMERAS: Mutex<Vec<DisplayInfo>> = Mutex::new(Vec::new());
    static ref LATEST_FRAME: Mutex<LatestFrame> = Mutex::new(LatestFrame::default());
    // 需要旋转时的 I420 中间缓冲，复用以避免每帧分配内存
    static ref ROTATE_SRC: Mutex<Vec<u8>> = Mutex::new(Vec::new());
}

// 采集器编号。切换摄像头时新采集器先创建、旧采集器后销毁，
// 用编号判断“我还是最新的吗”，避免旧采集器 Drop 时把新摄像头也关掉。
static LAST_CAPTURER_ID: AtomicUsize = AtomicUsize::new(0);

/// 向 Kotlin 查询摄像头列表，转成 DisplayInfo（摄像头复用显示器的数据结构）。没有权限/没有摄像头时返回空。
fn query_info() -> Vec<DisplayInfo> {
    let Ok(res) = call_main_service_get_by_name("camera_info") else {
        return Vec::new();
    };
    let Ok(infos) = serde_json::from_str::<Vec<CameraInfo>>(&res) else {
        return Vec::new();
    };
    infos
        .into_iter()
        .filter(|info| info.width > 0 && info.height > 0)
        .map(|info| DisplayInfo {
        x: 0,
        y: 0,
        name: info.name,
        width: info.width,
        height: info.height,
        online: true,
        cursor_embedded: false,
        scale: 1.0,
        original_resolution: Some(Resolution {
            width: info.width,
            height: info.height,
            ..Default::default()
        })
        .into(),
        ..Default::default()
    })
    .collect()
}

/// 对应 Cameras::all_info：登录时查询并缓存摄像头列表
pub fn all_info() -> ResultType<Vec<DisplayInfo>> {
    let info = query_info();
    *SYNC_CAMERAS.lock().unwrap() = info.clone();
    Ok(info)
}

pub fn exists(index: usize) -> bool {
    // 下标是否在 Kotlin 返回的摄像头列表范围内
    index < query_info().len()
}

pub fn get_sync_cameras() -> Vec<DisplayInfo> {
    // 摄像头尺寸会随“摄像头最高分辨率”设置变化，所以这里重新查询，不信任旧缓存。
    // 否则编码器按旧尺寸初始化、实际帧尺寸不同，会报 "src rect > dst rect"。
    // 查询失败（空）时仍使用旧缓存。
    let info = query_info();
    let mut cache = SYNC_CAMERAS.lock().unwrap();
    if !info.is_empty() {
        *cache = info;
    }
    cache.clone()
}

pub fn get_capturer(index: usize) -> ResultType<Box<dyn TraitCapturer>> {
    Ok(Box::new(AndroidCameraCapturer::new(index)?))
}

pub struct AndroidCameraCapturer {
    id: usize,
    // 与 LATEST_FRAME.data 交换使用，避免每帧拷贝
    data: Vec<u8>,
    width: usize,
    height: usize,
}

impl AndroidCameraCapturer {
    fn new(index: usize) -> ResultType<Self> {
        // 丢弃上一个摄像头遗留的旧帧（切换摄像头时尺寸可能不同）
        LATEST_FRAME.lock().unwrap().fresh = false;
        let id = LAST_CAPTURER_ID.fetch_add(1, Ordering::SeqCst) + 1;
        // 通知 Kotlin 打开指定下标的摄像头（已在运行时则切换）
        if let Err(e) =
            call_main_service_set_by_name("start_camera", Some(&index.to_string()), None)
        {
            bail!("Failed to start the camera: {:?}", e);
        }
        Ok(Self {
            id,
            data: Vec::new(),
            width: 0,
            height: 0,
        })
    }
}

impl Drop for AndroidCameraCapturer {
    fn drop(&mut self) {
        // 切换摄像头时，更新的采集器已经接管了摄像头，旧采集器销毁时不能再发 stop_camera。
        // 只有最后一个采集器销毁时才真正关闭摄像头和麦克风。
        if LAST_CAPTURER_ID.load(Ordering::SeqCst) == self.id {
            let _ = call_main_service_set_by_name("stop_camera", None, None);
        }
    }
}

impl TraitCapturer for AndroidCameraCapturer {
    fn frame<'a>(&'a mut self, _timeout: Duration) -> io::Result<Frame<'a>> {
        {
            let mut latest = LATEST_FRAME.lock().unwrap();
            // 没有新帧就返回 WouldBlock，视频服务循环会稍后重试，不会重复编码同一帧
            if !latest.fresh {
                return Err(io::ErrorKind::WouldBlock.into());
            }
            latest.fresh = false;
            std::mem::swap(&mut self.data, &mut latest.data);
            self.width = latest.width;
            self.height = latest.height;
        }
        Ok(Frame::PixelBuffer(PixelBuffer::new(
            &self.data,
            self.width,
            self.height,
        )))
    }
}

/// 校验并取出一个平面的起始地址：必须是 direct buffer，且容量足够覆盖 rows x cols 个采样（带 stride），
/// 防止 libyuv 越界读取。
fn plane(env: &JNIEnv, buffer: &JByteBuffer, stride: jint, pixel_stride: jint, rows: usize, cols: usize) -> Option<*const u8> {
    let ptr = env.get_direct_buffer_address(buffer).ok()?;
    let cap = env.get_direct_buffer_capacity(buffer).ok()?;
    if ptr.is_null() || stride <= 0 || pixel_stride <= 0 {
        return None;
    }
    let need = (rows - 1) * stride as usize + (cols - 1) * pixel_stride as usize;
    if cap < need {
        return None;
    }
    Some(ptr as *const u8)
}

/// JNI：Kotlin 每帧调用。把 YUV_420_888 用 libyuv（NEON 加速）转成 RGBA，并顺时针旋转 `rotation` 度，
/// 结果写入 LATEST_FRAME。Android420ToABGR 自动处理 I420/NV12/NV21 等不同的 uv 排布（由 uv_pixel_stride 决定）。
#[no_mangle]
pub extern "system" fn Java_com_carriez_flutter_1hbb_CameraNative_onYuv(
    env: JNIEnv,
    _class: JClass,
    y: JByteBuffer,
    u: JByteBuffer,
    v: JByteBuffer,
    y_stride: jint,
    u_stride: jint,
    v_stride: jint,
    uv_pixel_stride: jint,
    width: jint,
    height: jint,
    rotation: jint,
) {
    if width <= 0 || height <= 0 {
        return;
    }
    let (w, h) = (width as usize, height as usize);
    // 校验三个平面的内存范围（色度平面宽高为一半，向上取整）
    let (cw, ch) = ((w + 1) / 2, (h + 1) / 2);
    let (Some(y_ptr), Some(u_ptr), Some(v_ptr)) = (
        plane(&env, &y, y_stride, 1, h, w),
        plane(&env, &u, u_stride, uv_pixel_stride, ch, cw),
        plane(&env, &v, v_stride, uv_pixel_stride, ch, cw),
    ) else {
        return;
    };
    let mode = match rotation {
        90 => Some(crate::RotationMode::kRotate90),
        180 => Some(crate::RotationMode::kRotate180),
        270 => Some(crate::RotationMode::kRotate270),
        _ => None,
    };
    // 旋转 90/270 度后宽高互换
    let (out_w, out_h) = if rotation == 90 || rotation == 270 {
        (h, w)
    } else {
        (w, h)
    };
    let size = w * h * 4;
    // 取走 LATEST_FRAME 的旧缓冲复用，转换过程中不持有锁，避免阻塞编码线程
    let mut out = std::mem::take(&mut LATEST_FRAME.lock().unwrap().data);
    out.resize(size, 0);
    let ok = unsafe {
        match mode {
            // 不需要旋转：YUV 直接转成 RGBA 写到输出缓冲
            None => {
                crate::common::Android420ToABGR(
                    y_ptr,
                    y_stride,
                    u_ptr,
                    u_stride,
                    v_ptr,
                    v_stride,
                    uv_pixel_stride,
                    out.as_mut_ptr(),
                    width * 4,
                    width,
                    height,
                ) == 0
            }
            // 需要旋转：先在 I420 域里旋转（每像素 1.5 字节），再转成 RGBA。
            // 比“先转 RGBA 再旋转”（每像素 4 字节读写）少一半以上的内存带宽，4K 下明显更快。
            Some(mode) => {
                let (out_w, out_h) = (out_w as i32, out_h as i32);
                let (cw2, ch2) = (((out_w + 1) / 2) as usize, ((out_h + 1) / 2) as usize);
                let y_len = (out_w * out_h) as usize;
                let mut scratch = ROTATE_SRC.lock().unwrap();
                scratch.resize(y_len + 2 * cw2 * ch2, 0);
                let (dst_y, uv) = scratch.split_at_mut(y_len);
                let (dst_u, dst_v) = uv.split_at_mut(cw2 * ch2);
                crate::common::Android420ToI420Rotate(
                    y_ptr,
                    y_stride,
                    u_ptr,
                    u_stride,
                    v_ptr,
                    v_stride,
                    uv_pixel_stride,
                    dst_y.as_mut_ptr(),
                    out_w,
                    dst_u.as_mut_ptr(),
                    cw2 as i32,
                    dst_v.as_mut_ptr(),
                    cw2 as i32,
                    width,
                    height,
                    mode,
                ) == 0
                    && crate::common::I420ToABGR(
                        dst_y.as_ptr(),
                        out_w,
                        dst_u.as_ptr(),
                        cw2 as i32,
                        dst_v.as_ptr(),
                        cw2 as i32,
                        out.as_mut_ptr(),
                        out_w * 4,
                        out_w,
                        out_h,
                    ) == 0
            }
        }
    };
    // 失败时不标记为新帧，但仍把缓冲还回去，避免下次重新分配
    let mut latest = LATEST_FRAME.lock().unwrap();
    latest.data = out;
    latest.fresh = false;
    if ok {
        latest.width = out_w;
        latest.height = out_h;
        latest.fresh = true;
    }
}
