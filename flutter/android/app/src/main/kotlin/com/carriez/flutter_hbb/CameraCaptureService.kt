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
 * Streams the camera (RGBA frames, upright) and the microphone to Rust while a peer is viewing the camera.
 * Started and stopped by Rust through [CameraHook]; independent of screen capture.
 */
class CameraCaptureService : Service() {
    companion object {
        private const val NOTIFY_ID = 3
        private const val NOTIFY_CHANNEL_ID = "RustDeskCamera"
        private const val MIN_FRAME_INTERVAL_MS = 66L
        const val EXTRA_INDEX = "camera_index"

        @Volatile
        var instance: CameraCaptureService? = null
    }

    private val logTag = "LOG_CAMERA_SERVICE"

    private var thread: HandlerThread? = null
    private var handler: Handler? = null
    private var cameraDevice: CameraDevice? = null
    private var session: CameraCaptureSession? = null
    private var imageReader: ImageReader? = null
    private var audioRecordHandle: AudioRecordHandle? = null
    private var micStarted = false
    private var stopped = false
    private var generation = 0

    private var choice: CameraHook.Choice? = null
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

    fun startStreaming(index: Int) {
        handler?.post {
            release()
            openCamera(index)
        }
    }

    fun stopStreaming() {
        handler?.post { release() }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
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
            val planes = image.planes
            CameraNative.onYuv(
                planes[0].buffer, planes[1].buffer, planes[2].buffer,
                planes[0].rowStride, planes[1].rowStride, planes[2].rowStride, planes[1].pixelStride,
                image.width, image.height, chosen.rotation
            )
        } catch (e: Exception) {
            Log.e(logTag, "frame failed", e)
        } finally {
            image.close()
        }
    }

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
