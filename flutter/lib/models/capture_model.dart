import 'package:flutter/material.dart';
import 'package:flutter/services.dart';

import '../common.dart';

/// Android only. Controls the device-online service and the screen capture
/// independently through the `mChannelCapture` channel.
///
/// 关键点：服务、录屏、摄像头权限 三个状态各自独立，原生侧状态变化通过 on_state 回调推过来；
/// 打开页面时用 [refresh] 主动同步一次，防止界面和原生真实状态不一致。
class CaptureModel with ChangeNotifier {
  final MethodChannel _channel = const MethodChannel('mChannelCapture');
  bool _serviceOn = false;
  bool _captureOn = false;
  bool _cameraOn = false; // 是否已授予摄像头权限
  int _cameraHeight = 1080; // 摄像头最高分辨率（高），下次连接生效

  int get cameraHeight => _cameraHeight;
  bool get serviceOn => _serviceOn;
  bool get captureOn => _captureOn;
  bool get cameraOn => _cameraOn;

  CaptureModel() {
    if (!isAndroid) return;
    _channel.setMethodCallHandler((call) async {
      if (call.method == 'on_state') {
        final name = call.arguments['name'];
        final value = call.arguments['value'] == true;
        if (name == 'service') {
          _serviceOn = value;
        } else if (name == 'capture') {
          _captureOn = value;
        } else if (name == 'camera') {
          _cameraOn = value;
        }
        notifyListeners();
      }
    });
  }

  Future<void> refresh() async {
    if (!isAndroid) return;
    final state = await _channel.invokeMethod('get_state');
    _serviceOn = state['service'] == true;
    _captureOn = state['capture'] == true;
    _cameraOn = state['camera'] == true;
    _cameraHeight = state['camera_height'] ?? _cameraHeight;
    notifyListeners();
  }

  Future<void> startService() async {
    if (!isAndroid) return;
    await _channel.invokeMethod('start_service');
  }

  Future<void> stopService() async {
    if (!isAndroid) return;
    await _channel.invokeMethod('stop_service');
  }

  Future<void> startCapture() async {
    if (!isAndroid) return;
    await _channel.invokeMethod('start_capture');
  }

  Future<void> stopCapture() async {
    if (!isAndroid) return;
    await _channel.invokeMethod('stop_capture');
  }

  /// 申请摄像头/麦克风权限（结果通过 on_state 的 'camera' 回传）
  Future<void> requestCameraPermission() async {
    if (!isAndroid) return;
    await _channel.invokeMethod('request_camera_permission');
  }

  /// 设置摄像头最高分辨率（720/1080/2160），保存在 Kotlin 侧，下次连接生效
  Future<void> setCameraHeight(int height) async {
    if (!isAndroid) return;
    _cameraHeight = height;
    notifyListeners();
    await _channel.invokeMethod('set_camera_height', height);
  }
}

final gCaptureModel = CaptureModel();
