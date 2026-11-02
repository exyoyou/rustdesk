import 'package:flutter/material.dart';

import '../../common.dart';
import '../../models/capture_model.dart';

/// Independent switches for the device-online service and the screen capture.
class CapturePanel extends StatefulWidget {
  const CapturePanel({Key? key}) : super(key: key);

  @override
  State<CapturePanel> createState() => _CapturePanelState();
}

class _CapturePanelState extends State<CapturePanel> {
  @override
  void initState() {
    super.initState();
    gCaptureModel.refresh().then((_) {
      if (gCaptureModel.serviceOn && !gFFI.serverModel.isStart) {
        gFFI.serverModel.startServiceOnly();
      }
    });
  }

  @override
  Widget build(BuildContext context) {
    return AnimatedBuilder(
      animation: gCaptureModel,
      builder: (context, _) => Card(
        margin: const EdgeInsets.symmetric(horizontal: 12, vertical: 6),
        child: Column(
          children: [
            SwitchListTile(
              title: Text(translate('Service')),
              value: gCaptureModel.serviceOn,
              onChanged: (on) => on
                  ? gFFI.serverModel.startServiceOnly()
                  : gFFI.serverModel.stopService(),
            ),
            SwitchListTile(
              title: Text(translate('Screen Capture')),
              value: gCaptureModel.captureOn,
              onChanged: (on) => on
                  ? gCaptureModel.startCapture()
                  : gCaptureModel.stopCapture(),
            ),
          ],
        ),
      ),
    );
  }
}
