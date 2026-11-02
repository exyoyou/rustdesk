package com.carriez.flutter_hbb

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.ImageFormat
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.media.ImageReader
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Process
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat

/**
 * 摄像头 + 麦克风采集的前台服务。
 *  - 有控制端“查看摄像头”时，由 Rust 经 [CameraHook] 通知开始；用完通知停止。
 *  - 每帧把 YUV 平面交给 Rust（CameraNative.onYuv）转换、旋转、编码。
 *  - 麦克风复用 AudioRecordHandle，和摄像头同起同停，声音走原有的音频推流。
 *  - 与屏幕录制、设备在线服务完全独立。
 */
class CameraCaptureService : Service() {
    companion object {
        private const val NOTIFY_ID = 3
        private const val NOTIFY_CHANNEL_ID = "RustDeskCamera"
        // 最小帧间隔（66ms ≈ 15fps），用来限制帧率、降低 CPU 和编码压力
        private const val MIN_FRAME_INTERVAL_MS = 66L
        const val EXTRA_INDEX = "camera_index"

        // 当前运行的服务实例，供 CameraHook 直接调用 startStreaming/stopStreaming
        @Volatile
        var instance: CameraCaptureService? = null
    }

    private val logTag = "LOG_CAMERA_SERVICE"

    // 状态都在专用的 HandlerThread 上访问，避免多线程竞争
    private var thread: HandlerThread? = null
    private var handler: Handler? = null
    private var cameraDevice: CameraDevice? = null
    private var session: CameraCaptureSession? = null
    private var imageReader: ImageReader? = null
    private var audioRecordHandle: AudioRecordHandle? = null
    private var micStarted = false
    private var stopped = false
    // 代数：每次打开/关闭摄像头都递增。异步回调（已打开/已配置）里对比代数，
    // 发现已经被切换/关闭就放弃，防止旧回调覆盖新状态
    private var generation = 0

    // 当前正在使用的摄像头选择（尺寸/方向）
    private var choice: CameraHook.Choice? = null
    // 上一帧的时间，用于限帧
    private var lastFrameTime = 0L

    override fun onCreate() {
        super.onCreate()
        thread = HandlerThread("CameraService", Process.THREAD_PRIORITY_BACKGROUND).apply {
            start()
            handler = Handler(looper)
        }
        if (!startForegroundCamera()) {
            stopSelf()
            return
        }
        instance = this
    }

    /** 开始采集：先释放旧的（即支持切换摄像头），再打开指定下标的摄像头 */
    fun startStreaming(index: Int) {
        handler?.post {
            release()
            openCamera(index)
        }
    }

    /** 停止采集，释放摄像头和麦克风，但保留前台服务（下次后台也能直接用） */
    fun stopStreaming() {
        handler?.post { release() }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // 服务未运行时由 CameraHook 拉起，带着摄像头下标；空转（prepare）时没有下标，不打开摄像头
        if (intent != null && intent.hasExtra(EXTRA_INDEX)) {
            startStreaming(intent.getIntExtra(EXTRA_INDEX, 0))
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        instance = null
        stopped = true
        val h = handler
        if (h != null) {
            h.post { release() }
            thread?.quitSafely()
            thread?.join(2000)
        } else {
            release()
        }
        thread = null
        handler = null
        super.onDestroy()
    }

    /**
     * 升级为前台服务。Android 10+ 必须传前台服务类型（摄像头，有麦克风权限再加上麦克风），
     * 并且和 AndroidManifest 中声明的 foregroundServiceType 一致，否则后台拿不到摄像头。
     */
    private fun startForegroundCamera(): Boolean {
        return try {
            val notification = buildNotification()
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                var type = ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA
                if (CameraHook.hasMicPermission(this)) {
                    type = type or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
                }
                startForeground(NOTIFY_ID, notification, type)
            } else {
                startForeground(NOTIFY_ID, notification)
            }
            true
        } catch (e: Exception) {
            Log.e(logTag, "startForeground failed", e)
            false
        }
    }

    /** 前台服务必须显示的通知（低重要性，不打扰用户） */
    private fun buildNotification(): Notification {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            manager.createNotificationChannel(
                NotificationChannel(NOTIFY_CHANNEL_ID, "RustDesk Camera", NotificationManager.IMPORTANCE_LOW)
            )
        }
        return NotificationCompat.Builder(this, NOTIFY_CHANNEL_ID)
            .setOngoing(true)
            .setSmallIcon(R.mipmap.ic_stat_logo)
            .setContentTitle(DEFAULT_NOTIFY_TITLE)
            .setContentText(translate("Camera"))
            .setColor(ContextCompat.getColor(this, R.color.primary))
            .setOnlyAlertOnce(true)
            .build()
    }

    /**
     * 打开指定下标的摄像头。流程：ImageReader(YUV) → openCamera → createCaptureSession → setRepeatingRequest。
     * 每一步的异步回调都先检查 generation，防止被后来的切换/关闭打断后还继续执行。
     */
    @Suppress("MissingPermission")
    private fun openCamera(index: Int) {
        if (stopped || cameraDevice != null) {
            return
        }
        if (!CameraHook.hasCameraPermission(this)) {
            Log.e(logTag, "no CAMERA permission")
            return
        }
        val chosen = CameraHook.choose(this, index)
        if (chosen == null) {
            Log.e(logTag, "no usable camera at index $index")
            return
        }
        val gen = ++generation
        choice = chosen
        // ImageReader 缓冲 2 帧；帧到达时在 handler 线程回调 onImage
        val reader = ImageReader.newInstance(chosen.srcWidth, chosen.srcHeight, ImageFormat.YUV_420_888, 2)
        reader.setOnImageAvailableListener({ onImage(it) }, handler)
        imageReader = reader
        val manager = getSystemService(Context.CAMERA_SERVICE) as CameraManager
        try {
            manager.openCamera(chosen.id, object : CameraDevice.StateCallback() {
                override fun onOpened(camera: CameraDevice) {
                    if (stopped || gen != generation) {
                        camera.close()
                        return
                    }
                    cameraDevice = camera
                    startSession(camera, reader, gen)
                }

                override fun onDisconnected(camera: CameraDevice) {
                    Log.w(logTag, "camera disconnected")
                    camera.close()
                    if (gen == generation) release()
                }

                override fun onError(camera: CameraDevice, error: Int) {
                    Log.e(logTag, "camera error $error")
                    camera.close()
                    if (gen == generation) release()
                }
            }, handler)
        } catch (e: Exception) {
            Log.e(logTag, "openCamera failed", e)
            release()
        }
    }

    /** 创建采集会话并开始连续预览；会话建好后才开麦克风，让声音和画面同步开始 */
    @Suppress("DEPRECATION")
    private fun startSession(camera: CameraDevice, reader: ImageReader, gen: Int) {
        try {
            val request = camera.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
                addTarget(reader.surface)
                set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO)
            }.build()
            camera.createCaptureSession(listOf(reader.surface), object : CameraCaptureSession.StateCallback() {
                override fun onConfigured(captureSession: CameraCaptureSession) {
                    if (stopped || gen != generation) {
                        captureSession.close()
                        return
                    }
                    session = captureSession
                    try {
                        captureSession.setRepeatingRequest(request, null, handler)
                        startMic()
                    } catch (e: Exception) {
                        Log.e(logTag, "setRepeatingRequest failed", e)
                        release()
                    }
                }

                override fun onConfigureFailed(captureSession: CameraCaptureSession) {
                    Log.e(logTag, "configure session failed")
                    if (gen == generation) release()
                }
            }, handler)
        } catch (e: Exception) {
            Log.e(logTag, "startSession failed", e)
            release()
        }
    }

    /**
     * 启动麦克风：复用原有的 AudioRecordHandle，switchToVoiceCall 会开启麦克风采集并把音频推给 Rust。
     * Rust 侧 connection.rs 已让查看摄像头的连接订阅音频，所以控制端能听到声音。
     */
    private fun startMic() {
        if (micStarted || !CameraHook.hasMicPermission(this)) {
            return
        }
        val record = AudioRecordHandle(this, { false }, { false })
        micStarted = record.switchToVoiceCall(null)
        if (micStarted) {
            audioRecordHandle = record
        } else {
            Log.w(logTag, "microphone not started")
        }
    }

    /** 每帧回调：取最新一帧（丢弃积压的旧帧），限帧后把 YUV 平面交给 Rust 处理 */
    private fun onImage(reader: ImageReader) {
        val image = try {
            reader.acquireLatestImage()
        } catch (e: Exception) {
            null
        } ?: return
        try {
            val now = System.currentTimeMillis()
            val chosen = choice
            if (stopped || chosen == null || now - lastFrameTime < MIN_FRAME_INTERVAL_MS) {
                return
            }
            lastFrameTime = now
            // planes[1]/[2] 是 U/V 平面，它们的 pixelStride 相同，取 planes[1] 的即可
            val planes = image.planes
            CameraNative.onYuv(
                planes[0].buffer, planes[1].buffer, planes[2].buffer,
                planes[0].rowStride, planes[1].rowStride, planes[2].rowStride, planes[1].pixelStride,
                image.width, image.height, chosen.rotation
            )
        } catch (e: Exception) {
            Log.e(logTag, "frame failed", e)
        } finally {
            // 必须关闭 Image，否则 ImageReader 缓冲耗尽后不再出帧
            image.close()
        }
    }

    /** 释放摄像头、会话、ImageReader 和麦克风。generation++ 使所有尚未返回的异步回调作废 */
    private fun release() {
        generation++
        choice = null
        try {
            session?.close()
        } catch (e: Exception) {
            Log.e(logTag, "close session failed", e)
        }
        session = null
        try {
            cameraDevice?.close()
        } catch (e: Exception) {
            Log.e(logTag, "close camera failed", e)
        }
        cameraDevice = null
        imageReader?.close()
        imageReader = null
        if (micStarted) {
            audioRecordHandle?.destroy()
            micStarted = false
        }
        audioRecordHandle = null
    }
}
