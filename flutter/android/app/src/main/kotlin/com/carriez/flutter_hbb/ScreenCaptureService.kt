package com.carriez.flutter_hbb

import android.annotation.SuppressLint
import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.content.res.Configuration
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Process
import android.util.Log
import android.view.WindowManager
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import ffi.FFI
import kotlin.math.max
import kotlin.math.min

const val ACT_START_SERVICE_ONLY = "START_SERVICE_ONLY"
const val ACT_START_SCREEN_CAPTURE = "START_SCREEN_CAPTURE"
const val EXT_CAPTURE_RESULT_INTENT = "CAPTURE_RESULT_INTENT"

/**
 * Screen capture that runs independently of [MainService].
 * Frames go to [FrameDispatcher] (local consumers) and to Rust (remote peers).
 * Do not call `FFI.init` here, it would replace the context Rust uses to call [MainService].
 *
 * 关键点：
 *  1. 独立的前台服务（mediaProjection 类型），启停与 MainService 互不关联
 *  2. 由 CapturePermissionActivity 拿到录屏授权后启动（intent 里带授权结果）
 *  3. 画面同时送给本地监听者（FrameDispatcher）和 Rust（FFI.onVideoFrameUpdate）
 *  4. 屏幕尺寸变化（旋转等）时重建 VirtualDisplay，避免只显示 1/4 屏幕
 */
class ScreenCaptureService : Service() {
    companion object {
        @Volatile
        private var _isStart = false
        val isStart: Boolean
            get() = _isStart

        private const val NOTIFY_ID = 2
        private const val NOTIFY_CHANNEL_ID = "RustDeskCapture"
    }

    private val logTag = "LOG_CAPTURE_SERVICE"

    private var serviceThread: HandlerThread? = null
    private var serviceHandler: Handler? = null
    private var mediaProjection: MediaProjection? = null
    private var mediaProjectionCallback: MediaProjection.Callback? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null
    private var resizePending = false
    private val audioRecordHandle = AudioRecordHandle(this, { _isStart }, { false })

    private var frameCount = 0
    private var frameCountTime = 0L

    override fun onCreate() {
        super.onCreate()
        // 线程优先级用默认值，不用 BACKGROUND：后台线程的 CPU 份额很小，用户在手机上玩游戏/看视频时
        // 每帧的拷贝会被饥饿，远程端会明显卡顿
        serviceThread = HandlerThread("CaptureService", Process.THREAD_PRIORITY_DEFAULT).apply {
            start()
            serviceHandler = Handler(looper)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // 只响应 ACT_START_SCREEN_CAPTURE；其他启动方式（如被系统重启）直接退出
        if (intent?.action != ACT_START_SCREEN_CAPTURE) {
            stopSelf()
            return START_NOT_STICKY
        }
        // 必须先 startForeground，再使用 MediaProjection，否则 Android 14+ 会抛 SecurityException
        if (!startForegroundCapture()) {
            stopSelf()
            return START_NOT_STICKY
        }
        val resultIntent = intent?.getParcelableExtra<Intent>(EXT_CAPTURE_RESULT_INTENT)
        val manager = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        val projection = resultIntent?.let { manager.getMediaProjection(Activity.RESULT_OK, it) }
        if (projection == null || !startCapture(projection)) {
            stopSelf()
        }
        return START_NOT_STICKY
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        if (_isStart) {
            updateScreenInfo()
            serviceHandler?.post { resizeCaptureIfNeeded() }
        }
    }

    override fun onDestroy() {
        stopCapture()
        serviceThread?.quitSafely()
        serviceThread = null
        serviceHandler = null
        super.onDestroy()
    }

    private fun startForegroundCapture(): Boolean {
        return try {
            val notification = buildNotification()
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(
                    NOTIFY_ID,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
                )
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
                NotificationChannel(
                    NOTIFY_CHANNEL_ID,
                    "RustDesk Capture",
                    NotificationManager.IMPORTANCE_LOW
                )
            )
        }
        return NotificationCompat.Builder(this, NOTIFY_CHANNEL_ID)
            .setOngoing(true)
            .setSmallIcon(R.mipmap.ic_stat_logo)
            .setContentTitle(DEFAULT_NOTIFY_TITLE)
            .setContentText(translate("Screen Capture"))
            .setColor(ContextCompat.getColor(this, R.color.primary))
            .setOnlyAlertOnce(true)
            .build()
    }

    /** Keep [SCREEN_INFO] in sync with the display, honoring the scale set elsewhere. */
    private fun updateScreenInfo() {
        val windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val (rawW, rawH) = getScreenSize(windowManager)
        if (rawW == 0 || rawH == 0) {
            return
        }
        val longSide = max(rawW, rawH)
        val shortSide = min(rawW, rawH)
        val landscape = resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
        val scale = max(SCREEN_INFO.scale, 1)
        val w = (if (landscape) longSide else shortSide) / scale
        val h = (if (landscape) shortSide else longSide) / scale
        val dpi = resources.configuration.densityDpi / scale
        if (SCREEN_INFO.width != w || SCREEN_INFO.height != h) {
            Log.d(logTag, "updateScreenInfo:w:$w,h:$h")
            SCREEN_INFO.width = w
            SCREEN_INFO.height = h
            SCREEN_INFO.scale = scale
            SCREEN_INFO.dpi = dpi
            FFI.refreshScreen()
        }
    }

    @Synchronized
    private fun startCapture(projection: MediaProjection): Boolean {
        if (_isStart) {
            stopCapture()
        }
        updateScreenInfo()
        if (SCREEN_INFO.width == 0 || SCREEN_INFO.height == 0) {
            Log.e(logTag, "startCapture failed, screen size unknown")
            projection.stop()
            return false
        }
        val callback = object : MediaProjection.Callback() {
            // 用户在系统里结束录屏（如点状态栏停止）时，同步关闭本服务
            override fun onStop() {
                Log.d(logTag, "MediaProjection stopped")
                stopSelf()
            }
        }
        projection.registerCallback(callback, serviceHandler)
        mediaProjection = projection
        mediaProjectionCallback = callback

        val reader = createImageReader()
        val display = try {
            projection.createVirtualDisplay(
                "RustDeskCaptureVD",
                SCREEN_INFO.width, SCREEN_INFO.height, SCREEN_INFO.dpi,
                VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                reader.surface, null, null
            )
        } catch (e: SecurityException) {
            Log.e(logTag, "createVirtualDisplay failed", e)
            null
        }
        if (display == null) {
            reader.close()
            releaseProjection()
            return false
        }
        imageReader = reader
        virtualDisplay = display
        _isStart = true
        // 打开 Rust 的 video 帧通道，之后每帧通过 FFI.onVideoFrameUpdate 推送
        FFI.setFrameRawEnable("video", true)
        MainActivity.rdClipboardManager?.setCaptureStarted(true)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val audioStarted = audioRecordHandle.createAudioRecorder(false, projection) &&
                    audioRecordHandle.startAudioRecorder()
            Log.d(logTag, if (audioStarted) "audio recorder start" else "audio recorder start failed")
        }
        CaptureChannel.notifyState("capture", true)
        Log.d(logTag, "Start Capture ${SCREEN_INFO}")
        return true
    }

    @Synchronized
    private fun stopCapture() {
        val wasStart = _isStart
        _isStart = false
        if (wasStart) {
            // 如果旧的 MainService 录屏路径还在用 video 通道，就不能关；否则会把它的画面也断掉
            if (!MainService.isStart) {
                FFI.setFrameRawEnable("video", false)
                MainActivity.rdClipboardManager?.setCaptureStarted(false)
            }
            audioRecordHandle.tryReleaseAudio()
        }
        virtualDisplay?.release()
        virtualDisplay = null
        imageReader?.close()
        imageReader = null
        releaseProjection()
        if (wasStart) {
            CaptureChannel.notifyState("capture", false)
            Log.d(logTag, "Stop Capture")
        }
    }

    private fun releaseProjection() {
        val projection = mediaProjection
        val callback = mediaProjectionCallback
        mediaProjection = null
        mediaProjectionCallback = null
        if (projection != null && callback != null) {
            projection.unregisterCallback(callback)
        }
        projection?.stop()
    }

    @SuppressLint("WrongConstant")
    private fun createImageReader(): ImageReader {
        return ImageReader.newInstance(
            SCREEN_INFO.width,
            SCREEN_INFO.height,
            PixelFormat.RGBA_8888,
            // 用 acquireLatestImage 只取最新帧，3 个缓冲足够；每个缓冲是整屏 RGBA（如 1440x3120 约 18MB），少一个省一个
            3
        ).apply {
            setOnImageAvailableListener({ reader -> onImageAvailable(reader) }, serviceHandler)
        }
    }

    private fun onImageAvailable(reader: ImageReader) {
        try {
            // If acquireLatestImage is not called, the listener will not be called again
            reader.acquireLatestImage()?.use { image ->
                if (!_isStart) {
                    return
                }
                if (image.width != SCREEN_INFO.width || image.height != SCREEN_INFO.height) {
                    // 帧尺寸和当前屏幕信息不一致（刚旋转/缩放）：丢弃这帧并只触发一次重建，防止重复 post
                    if (!resizePending) {
                        resizePending = true
                        Log.d(logTag, "frame ${image.width}x${image.height} != $SCREEN_INFO, resize")
                        serviceHandler?.post { resizeCaptureIfNeeded() }
                    }
                    return
                }
                val plane = image.planes[0]
                val buffer = plane.buffer
                buffer.rewind()
                FrameDispatcher.dispatch(buffer, image.width, image.height, plane.rowStride)
                buffer.rewind()
                FFI.onVideoFrameUpdate(buffer)
                logFrameRate(image.width, image.height)
            }
        } catch (ignored: Exception) {
        }
    }

    @Synchronized
    private fun resizeCaptureIfNeeded() {
        resizePending = false
        val display = virtualDisplay ?: return
        val oldReader = imageReader ?: return
        if (oldReader.width == SCREEN_INFO.width && oldReader.height == SCREEN_INFO.height) {
            return
        }
        val reader = createImageReader()
        val projection = mediaProjection
        if (Build.VERSION.SDK_INT > Build.VERSION_CODES.TIRAMISU || projection == null) {
            // Resizing while attached may leave the mirrored content unscaled (only a part of
            // the screen is shown until the display restarts). Detach first so the system
            // restarts the mirroring with the new size.
            display.surface = null
            imageReader = reader
            oldReader.close()
            serviceHandler?.postDelayed({ attachResizedDisplay(display, reader) }, 150)
            return
        } else {
            // resize() on a mirror display may not rescale the content before Android 14,
            // which shows only a part of the screen. Recreate it, as MainService does.
            display.release()
            virtualDisplay = try {
                projection.createVirtualDisplay(
                    "RustDeskCaptureVD",
                    SCREEN_INFO.width, SCREEN_INFO.height, SCREEN_INFO.dpi,
                    VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                    reader.surface, null, null
                )
            } catch (e: SecurityException) {
                Log.e(logTag, "recreate VirtualDisplay failed", e)
                null
            }
            if (virtualDisplay == null) {
                reader.close()
                stopSelf()
                return
            }
        }
        imageReader = reader
        oldReader.close()
        Log.d(logTag, "Capture resized: $SCREEN_INFO, sdk:${Build.VERSION.SDK_INT}")
    }

    @Synchronized
    private fun attachResizedDisplay(display: VirtualDisplay, reader: ImageReader) {
        if (virtualDisplay !== display || imageReader !== reader) {
            return
        }
        display.resize(reader.width, reader.height, SCREEN_INFO.dpi)
        display.surface = reader.surface
        Log.d(logTag, "Capture resized: ${reader.width}x${reader.height}, $SCREEN_INFO, sdk:${Build.VERSION.SDK_INT}")
    }

    private fun logFrameRate(width: Int, height: Int) {
        frameCount++
        val now = System.currentTimeMillis()
        if (now - frameCountTime >= 1000) {
            Log.d(logTag, "local frames: $frameCount/s, ${width}x$height")
            frameCount = 0
            frameCountTime = now
        }
    }
}
