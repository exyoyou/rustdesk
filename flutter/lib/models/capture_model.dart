import 'package:flutter/material.dart';
import 'package:flutter/services.dart';

import '../common.dart';

/// Android only. Controls the device-online service and the screen capture
/// independently through the `mChannelCapture` channel.
class CaptureModel with ChangeNotifier {
  final MethodChannel _channel = const MethodChannel('mChannelCapture');
  bool _serviceOn = false;
  bool _captureOn = false;
  bool _cameraOn = false;
  int _cameraHeight = 1080;

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

  Future<void> requestCameraPermission() async {
    if (!isAndroid) return;
    await _channel.invokeMethod('request_camera_permission');
  }

  Future<void> setCameraHeight(int height) async {
    if (!isAndroid) return;
    _cameraHeight = height;
    notifyListeners();
    await _channel.invokeMethod('set_camera_height', height);
  }
}

final gCaptureModel = CaptureModel();
