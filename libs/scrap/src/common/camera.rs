use std::{
    io,
    sync::{Arc, Mutex},
};

#[cfg(any(target_os = "windows", target_os = "linux"))]
use nokhwa::{
    pixel_format::RgbAFormat,
    query,
    utils::{ApiBackend, CameraIndex, RequestedFormat, RequestedFormatType},
    Camera,
};

use hbb_common::message_proto::{DisplayInfo, Resolution};

#[cfg(feature = "vram")]
use crate::AdapterDevice;

use crate::common::{bail, ResultType};
use crate::{Frame, TraitCapturer};
#[cfg(any(target_os = "windows", target_os = "linux"))]
use crate::{PixelBuffer, Pixfmt};

// Android: fetch camera list via MainService.getCameraListJson through JNI
#[cfg(target_os = "android")]
use crate::android::ffi::call_camera_list_json;
#[cfg(target_os = "android")]
use serde::Deserialize;
#[cfg(target_os = "android")]
use crate::android::ffi::{get_camera_raw, start_camera_capture, stop_camera_capture};

pub const PRIMARY_CAMERA_IDX: usize = 0;
lazy_static::lazy_static! {
    static ref SYNC_CAMERA_DISPLAYS: Arc<Mutex<Vec<DisplayInfo>>> = Arc::new(Mutex::new(Vec::new()));
}

#[cfg(not(any(target_os = "windows", target_os = "linux")))]
const CAMERA_NOT_SUPPORTED: &str = "This platform doesn't support camera yet";

pub struct Cameras;

// pre-condition
pub fn primary_camera_exists() -> bool {
    Cameras::exists(PRIMARY_CAMERA_IDX)
}

#[cfg(any(target_os = "windows", target_os = "linux"))]
impl Cameras {
    pub fn all_info() -> ResultType<Vec<DisplayInfo>> {
        match query(ApiBackend::Auto) {
            Ok(cameras) => {
                let mut camera_displays = SYNC_CAMERA_DISPLAYS.lock().unwrap();
                camera_displays.clear();
                // FIXME: nokhwa returns duplicate info for one physical camera on linux for now.
                // issue: https://github.com/l1npengtul/nokhwa/issues/171
                // Use only one camera as a temporary hack.
                cfg_if::cfg_if! {
                    if #[cfg(target_os = "linux")] {
                        let Some(info) = cameras.first() else {
                            bail!("No camera found")
                        };
                        // Use index (0) camera as main camera, fallback to the first camera if index (0) is not available.
                        // But maybe we also need to check index (1) or the lowest index camera.
                        //
                        // https://askubuntu.com/questions/234362/how-to-fix-this-problem-where-sometimes-dev-video0-becomes-automatically-dev
                        // https://github.com/rustdesk/rustdesk/pull/12010#issue-3125329069
                        let mut camera_index = info.index().clone();
                        if !matches!(camera_index, CameraIndex::Index(0)) {
                            if cameras.iter().any(|cam| matches!(cam.index(), CameraIndex::Index(0))) {
                                camera_index = CameraIndex::Index(0);
                            }
                        }
                        let camera = Self::create_camera(&camera_index)?;
                        let resolution = camera.resolution();
                        let (width, height) = (resolution.width() as i32, resolution.height() as i32);
                        camera_displays.push(DisplayInfo {
                            x: 0,
                            y: 0,
                            name: info.human_name().clone(),
                            width,
                            height,
                            online: true,
                            cursor_embedded: false,
                            scale:1.0,
                            original_resolution: Some(Resolution {
                                width,
                                height,
                                ..Default::default()
                            }).into(),
                            ..Default::default()
                        });
                    } else {
                        let mut x = 0;
                        for info in &cameras {
                            let camera = Self::create_camera(info.index())?;
                            let resolution = camera.resolution();
                            let (width, height) = (resolution.width() as i32, resolution.height() as i32);
                            camera_displays.push(DisplayInfo {
                                x,
                                y: 0,
                                name: info.human_name().clone(),
                                width,
                                height,
                                online: true,
                                cursor_embedded: false,
                                scale:1.0,
                                original_resolution: Some(Resolution {
                                    width,
                                    height,
                                    ..Default::default()
                                }).into(),
                                ..Default::default()
                            });
                            x += width;
                        }
                    }
                }
                Ok(camera_displays.clone())
            }
            Err(e) => {
                bail!("Query cameras error: {}", e)
            }
        }
    }

    pub fn exists(index: usize) -> bool {
        match query(ApiBackend::Auto) {
            Ok(cameras) => index < cameras.len(),
            _ => return false,
        }
    }

    fn create_camera(index: &CameraIndex) -> ResultType<Camera> {
        let format_type = if cfg!(target_os = "linux") {
            RequestedFormatType::None
        } else {
            RequestedFormatType::AbsoluteHighestResolution
        };
        let result = Camera::new(
            index.clone(),
            RequestedFormat::new::<RgbAFormat>(format_type),
        );
        match result {
            Ok(camera) => Ok(camera),
            Err(e) => bail!("create camera{} error:  {}", index, e),
        }
    }

    pub fn get_camera_resolution(index: usize) -> ResultType<Resolution> {
        let index = CameraIndex::Index(index as u32);
        let camera = Self::create_camera(&index)?;
        let resolution = camera.resolution();
        Ok(Resolution {
            width: resolution.width() as i32,
            height: resolution.height() as i32,
            ..Default::default()
        })
    }

    pub fn get_sync_cameras() -> Vec<DisplayInfo> {
        SYNC_CAMERA_DISPLAYS.lock().unwrap().clone()
    }

    pub fn get_capturer(current: usize) -> ResultType<Box<dyn TraitCapturer>> {
        Ok(Box::new(CameraCapturer::new(current)?))
    }
}

// Android-side camera info shape returned by MainService.getCameraListJson
#[cfg(target_os = "android")]
#[derive(Debug, Deserialize, Clone)]
struct AndroidCameraInfo {
    id: String,
    name: String,
    width: i32,
    height: i32,
    #[allow(dead_code)]
    facing: i32,
}

#[cfg(target_os = "android")]
lazy_static::lazy_static! {
    static ref ANDROID_CAMERA_INFOS: Arc<Mutex<Vec<AndroidCameraInfo>>> = Arc::new(Mutex::new(Vec::new()));
}

// Android implementation
#[cfg(target_os = "android")]
impl Cameras {
    pub fn all_info() -> ResultType<Vec<DisplayInfo>> {
        // 调用 Android 层拿到相机信息 JSON
        let json = match call_camera_list_json() {
            Ok(s) => s,
            Err(e) => bail!("Query cameras error (android jni): {}", e),
        };
        let cams: Vec<AndroidCameraInfo> = match serde_json::from_str(&json) {
            Ok(v) => v,
            Err(e) => bail!("Parse android camera json failed: {}", e),
        };

        let mut camera_displays = SYNC_CAMERA_DISPLAYS.lock().unwrap();
        camera_displays.clear();
        // 保存原始 Android 相机信息，供后续启动捕获使用
        let mut android_infos = ANDROID_CAMERA_INFOS.lock().unwrap();
        *android_infos = cams.clone();

        // 横向排列（与桌面端一致），x 累加。
        let mut x = 0;
        for c in cams.iter() {
            let width = c.width.max(0);
            let height = c.height.max(0);
            camera_displays.push(DisplayInfo {
                x,
                y: 0,
                name: c.name.clone(),
                width,
                height,
                online: true,
                cursor_embedded: false,
                scale: 1.0,
                original_resolution: Some(Resolution {
                    width,
                    height,
                    ..Default::default()
                })
                .into(),
                ..Default::default()
            });
            x += width;
        }
        Ok(camera_displays.clone())
    }

    pub fn exists(index: usize) -> bool {
        // 依赖已缓存的列表；若为空则尝试刷新一次
        let len = {
            let v = SYNC_CAMERA_DISPLAYS.lock().unwrap();
            v.len()
        };
        if len == 0 {
            if Self::all_info().is_err() {
                return false;
            }
        }
        let v = SYNC_CAMERA_DISPLAYS.lock().unwrap();
        index < v.len()
    }

    pub fn get_camera_resolution(index: usize) -> ResultType<Resolution> {
        let v = SYNC_CAMERA_DISPLAYS.lock().unwrap();
        if index < v.len() {
            let d = &v[index];
            Ok(Resolution {
                width: d.width,
                height: d.height,
                ..Default::default()
            })
        } else {
            bail!("No camera found at index {}", index)
        }
    }

    pub fn get_sync_cameras() -> Vec<DisplayInfo> {
        SYNC_CAMERA_DISPLAYS.lock().unwrap().clone()
    }

    pub fn get_capturer(current: usize) -> ResultType<Box<dyn TraitCapturer>> {
        // 构造 Android 相机采集器：从 JNI 的 get_video_raw 读取 RGBA 帧
        // 依赖 Java 侧在打开相机后通过 FFI.onVideoFrameUpdate 推送帧数据
        if !Self::exists(current) {
            bail!("No camera found at index {}", current);
        }
        // 获取相机分辨率与 id
        let displays = SYNC_CAMERA_DISPLAYS.lock().unwrap();
        let d = &displays[current];
        let infos = ANDROID_CAMERA_INFOS.lock().unwrap();
        if current >= infos.len() {
            bail!("No camera info for index {}", current);
        }
        let cam_id = infos[current].id.clone();

        // 启动 Android 侧相机采集
        if let Err(e) = start_camera_capture(&cam_id) {
            bail!("start_camera_capture failed: {}", e);
        }

        Ok(Box::new(AndroidCameraCapturer::new(d.width as usize, d.height as usize, cam_id)))
    }
}

// Android 专用相机采集器实现：通过 get_video_raw 拉取 RGBA 帧
#[cfg(target_os = "android")]
struct AndroidCameraCapturer {
    width: usize,
    height: usize,
    data: Vec<u8>,
    last_data: Vec<u8>,
    camera_id: String,
}

#[cfg(target_os = "android")]
impl AndroidCameraCapturer {
    fn new(width: usize, height: usize, camera_id: String) -> Self {
        Self { width, height, data: Vec::new(), last_data: Vec::new(), camera_id }
    }
}

#[cfg(target_os = "android")]
impl TraitCapturer for AndroidCameraCapturer {
    fn frame<'a>(&'a mut self, _timeout: std::time::Duration) -> std::io::Result<Frame<'a>> {
        if get_camera_raw(&mut self.data, &mut self.last_data).is_some() {
            Ok(Frame::PixelBuffer(crate::PixelBuffer::new_i420(
                &self.data,
                self.width,
                self.height,
            )))
        } else {
            Err(std::io::ErrorKind::WouldBlock.into())
        }
    }
}

#[cfg(target_os = "android")]
impl Drop for AndroidCameraCapturer {
    fn drop(&mut self) {
        // 停止 Android 侧相机采集（忽略错误）
        let _ = stop_camera_capture();
    }
}

#[cfg(not(any(target_os = "windows", target_os = "linux", target_os = "android")))]
impl Cameras {
    pub fn all_info() -> ResultType<Vec<DisplayInfo>> {
        return Ok(Vec::new());
    }

    pub fn exists(_index: usize) -> bool {
        false
    }

    pub fn get_camera_resolution(_index: usize) -> ResultType<Resolution> {
        bail!(CAMERA_NOT_SUPPORTED);
    }

    pub fn get_sync_cameras() -> Vec<DisplayInfo> {
        vec![]
    }

    pub fn get_capturer(_current: usize) -> ResultType<Box<dyn TraitCapturer>> {
        bail!(CAMERA_NOT_SUPPORTED);
    }
}

#[cfg(any(target_os = "windows", target_os = "linux"))]
pub struct CameraCapturer {
    camera: Camera,
    data: Vec<u8>,
    last_data: Vec<u8>, // for faster compare and copy
}

#[cfg(not(any(target_os = "windows", target_os = "linux")))]
pub struct CameraCapturer;

impl CameraCapturer {
    #[cfg(any(target_os = "windows", target_os = "linux"))]
    fn new(current: usize) -> ResultType<Self> {
        let index = CameraIndex::Index(current as u32);
        let camera = Cameras::create_camera(&index)?;
        Ok(CameraCapturer {
            camera,
            data: Vec::new(),
            last_data: Vec::new(),
        })
    }

    #[allow(dead_code)]
    #[cfg(not(any(target_os = "windows", target_os = "linux")))]
    fn new(_current: usize) -> ResultType<Self> {
        bail!(CAMERA_NOT_SUPPORTED);
    }
}

impl TraitCapturer for CameraCapturer {
    #[cfg(any(target_os = "windows", target_os = "linux"))]
    fn frame<'a>(&'a mut self, _timeout: std::time::Duration) -> std::io::Result<Frame<'a>> {
        // TODO: move this check outside `frame`.
        if !self.camera.is_stream_open() {
            if let Err(e) = self.camera.open_stream() {
                return Err(io::Error::new(
                    io::ErrorKind::Other,
                    format!("Camera open stream error: {}", e),
                ));
            }
        }
        match self.camera.frame() {
            Ok(buffer) => {
                match buffer.decode_image::<RgbAFormat>() {
                    Ok(decoded) => {
                        self.data = decoded.as_raw().to_vec();
                        crate::would_block_if_equal(&mut self.last_data, &self.data)?;
                        // FIXME: macos's PixelBuffer cannot be directly created from bytes slice.
                        cfg_if::cfg_if! {
                            if #[cfg(any(target_os = "linux", target_os = "windows"))] {
                                Ok(Frame::PixelBuffer(PixelBuffer::new(
                                    &self.data,
                                    Pixfmt::RGBA,
                                    decoded.width() as usize,
                                    decoded.height() as usize,
                                )))
                            } else {
                                Err(io::Error::new(
                                    io::ErrorKind::Other,
                                    format!("Camera is not supported on this platform yet"),
                                ))
                            }
                        }
                    }
                    Err(e) => Err(io::Error::new(
                        io::ErrorKind::Other,
                        format!("Camera frame decode error: {}", e),
                    )),
                }
            }
            Err(e) => Err(io::Error::new(
                io::ErrorKind::Other,
                format!("Camera frame error: {}", e),
            )),
        }
    }

    #[cfg(not(any(target_os = "windows", target_os = "linux")))]
    fn frame<'a>(&'a mut self, _timeout: std::time::Duration) -> std::io::Result<Frame<'a>> {
        Err(io::Error::new(
            io::ErrorKind::Other,
            CAMERA_NOT_SUPPORTED.to_string(),
        ))
    }

    #[cfg(windows)]
    fn is_gdi(&self) -> bool {
        true
    }

    #[cfg(windows)]
    fn set_gdi(&mut self) -> bool {
        true
    }

    #[cfg(feature = "vram")]
    fn device(&self) -> AdapterDevice {
        AdapterDevice::default()
    }

    #[cfg(feature = "vram")]
    fn set_output_texture(&mut self, _texture: bool) {}
}
