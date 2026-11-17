# Fork 说明

本仓库基于上游 [rustdesk/rustdesk](https://github.com/rustdesk/rustdesk)。为了降低合并上游的代价，自己写的代码按下面的规则存放：

- **新增代码**：放在各语言的 `fork/` 目录，或 `monitor-*` 子模块里。上游不会动这些文件，合并时不会冲突。
- **上游文件里的改动**：只留尽量小的钩子，用 `[fork] begin` / `[fork] end` 包起来，应用内代理相关的用 `[monitor-proxy]`。改动了上游原有行的地方，用 `[upstream]` 注释保留原文。

合并上游后可以这样检查：

```bash
git grep -nE "\[(fork|monitor-proxy)\]"   # 所有钩子
git grep -n "\[upstream\]"                # 改动过的上游原行
```

## 新增代码位置

| 语言 | 目录 | 说明 |
|---|---|---|
| Kotlin | `flutter/android/app/src/fork/kotlin/com/carriez/flutter_hbb/` | 包名与上游相同，由 `app/build.gradle` 加入 `kotlin.srcDirs`；JNI 名称、Manifest 里的 `.Xxx` 写法都不用变 |
| Rust（主 crate） | `src/fork/` | `android_proxy.rs`：应用内代理钩子；`android_monitor.rs`：给监控模块提供设备 ID |
| Rust（scrap） | `libs/scrap/src/android/fork/` | `camera.rs`：Android 摄像头采集 |
| Dart | `flutter/lib/fork/` | `models/capture_model.dart`、`widgets/capture_panel.dart` |
| Gradle 子模块 | `flutter/android/monitor-*`、`flutter/android/opencv` | git submodule，由 `flutter/android/monitor_modules.gradle` 引入 |

## 上游文件中的钩子

下面的行号以当前提交为准，合并上游后行号可能变化，以 `git grep` 的结果为准。

### Android 构建

| 文件 | 行 | 内容 |
|---|---|---|
| `.gitmodules` | 4 | monitor-* / opencv 子模块 |
| `flutter/android/settings.gradle` | 27 | `apply from: "monitor_modules.gradle"` |
| `flutter/android/gradle.properties` | 1 | 加大 Gradle 堆内存（改了上游原行） |
| `flutter/android/app/build.gradle` | 89 | 加入 `src/fork/kotlin` 源码目录 |
| | 140 | kotlin-stdlib 1.9.10 → 2.1.21（改了上游原行） |
| | 146 | `implementation project(':monitor-runtime')` |
| `libs/hbb_common` | — | 子模块指向自己的仓库 |

### AndroidManifest.xml

| 行 | 内容 |
|---|---|
| 18 | 摄像头权限 |
| 34 | 应用名 RustDesk → SystemAI（改了上游原行） |
| 56 | InputService 名称 → SystemAI（改了上游原行） |
| 93 | deep link scheme rustdesk → systemai（改了上游原行） |
| 115 | 录屏/摄像头的 Activity 和 Service 声明 |

### Kotlin（`flutter/android/app/src/main/kotlin/com/carriez/flutter_hbb/`）

| 文件 | 行 | 内容 |
|---|---|---|
| `MainApplication.kt` | 16 | 进程启动时启动常驻监控层 |
| `MainActivity.kt` | 92 | 注册 CaptureChannel |
| `BootReceiver.kt` | 35 | 开机只启动服务，不绑录屏（改了上游原行） |
| | 49 | 开机拉起透明 Activity 启动摄像头服务 |
| `MainService.kt` | 108 | 录屏状态包含 ScreenCaptureService（改了上游原行） |
| | 113 | 未识别的 get 交给 CameraHook（改了上游原行） |
| | 136 | 查看屏幕的连接计入 CaptureController |
| | 186 | `authorize_connection` / `remove_connection` |
| | 204 | 未识别的 set 交给 CameraHook |
| | 288 | 服务下线通知 CaptureController |
| | 377 | `ACT_START_SERVICE_ONLY`：只上线不录屏 |
| | 672 | 录屏中不关闭 video 帧通道（改了上游原行） |

### Rust

| 文件 | 行 | 内容 |
|---|---|---|
| `src/lib.rs` | 70 | `mod fork;` |
| `src/flutter.rs` | 1538 | 连接断开时通知 Kotlin |
| `src/ui_cm_interface.rs` | 371 | 连接被同意时通知 Kotlin |
| `src/server/connection.rs` | 2017 | Android 声明支持查看摄像头 |
| | 2234 | 查看摄像头时订阅音频 |
| `src/rendezvous_mediator.rs` | 315, 745, 774, 1066, 1088 | `[monitor-proxy]` 代理生效时重定向服务器、强制中继、关闭 WebRTC |
| `libs/scrap/src/android/mod.rs` | 1 | `pub mod fork;` |
| `libs/scrap/src/common/camera.rs` | 164, 174, 188, 198 | Android 摄像头转到 `android/fork/camera.rs`（改了上游原行） |

### Dart

| 文件 | 行 | 内容 |
|---|---|---|
| `flutter/lib/mobile/pages/server_page.dart` | 16 | import CapturePanel |
| | 220 | 显示 CapturePanel |
| `flutter/lib/models/server_model.dart` | 9 | import capture_model |
| | 448 | `startServiceOnly()`（用到私有字段 `_isStart`，只能放在原类里） |
| | 469 | 停止服务时同步关闭服务通道 |
