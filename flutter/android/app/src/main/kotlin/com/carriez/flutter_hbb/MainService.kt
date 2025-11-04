package com.carriez.flutter_hbb

import ffi.FFI
import ffi.AndroidYuv420Frame

/**
 * Capture screen,get video and audio,send to rust.
 * Dispatch notifications
 *
 * Inspired by [droidVNC-NG] https://github.com/bk138/droidVNC-NG
 */

import android.Manifest
import android.annotation.SuppressLint
import android.app.*
import android.app.PendingIntent.FLAG_IMMUTABLE
import android.app.PendingIntent.FLAG_UPDATE_CURRENT
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.content.res.Configuration.ORIENTATION_LANDSCAPE
import android.graphics.Color
import android.graphics.PixelFormat
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CaptureRequest
import android.hardware.display.DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR
import android.hardware.display.VirtualDisplay
import android.media.*
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.*
import android.util.DisplayMetrics
import android.util.Log
import android.util.Size
import android.graphics.Rect
import android.view.Surface
import android.view.Surface.FRAME_RATE_COMPATIBILITY_DEFAULT
import android.view.WindowManager
import androidx.annotation.Keep
import androidx.annotation.RequiresApi
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import io.flutter.embedding.android.FlutterActivity
import org.json.JSONArray
import java.util.concurrent.Executors
import org.json.JSONException
import org.json.JSONObject
import kotlin.math.max
import kotlin.math.min

const val DEFAULT_NOTIFY_TITLE = "RustDesk"
const val DEFAULT_NOTIFY_TEXT = "Service is running"
const val DEFAULT_NOTIFY_ID = 1
const val NOTIFY_ID_OFFSET = 100

const val MIME_TYPE = MediaFormat.MIMETYPE_VIDEO_VP9

// video const

const val MAX_SCREEN_SIZE = 1200

const val VIDEO_KEY_BIT_RATE = 1024_000
const val VIDEO_KEY_FRAME_RATE = 30

class MainService : Service() {
    // 捕获状态机，防止并发/重复启动与交叉停止
    private enum class CaptureState { Idle, Starting, Running, Stopping }
    @Volatile private var captureState: CaptureState = CaptureState.Idle

    @Keep
    @RequiresApi(Build.VERSION_CODES.N)
    fun rustPointerInput(kind: Int, mask: Int, x: Int, y: Int) {
        // turn on screen with LEFT_DOWN when screen off
        if (!powerManager.isInteractive && (kind == 0 || mask == LEFT_DOWN)) {
            if (wakeLock.isHeld) {
                Log.d(logTag, "Turn on Screen, WakeLock release")
                wakeLock.release()
            }
            Log.d(logTag,"Turn on Screen")
            wakeLock.acquire(5000)
        } else {
            when (kind) {
                0 -> { // touch
                    InputService.ctx?.onTouchInput(mask, x, y)
                }
                1 -> { // mouse
                    InputService.ctx?.onMouseInput(mask, x, y)
                }
                else -> {
                }
            }
        }
    }

    @Keep
    @RequiresApi(Build.VERSION_CODES.N)
    fun rustKeyEventInput(input: ByteArray) {
        InputService.ctx?.onKeyEvent(input)
    }

    @Keep
    fun rustGetByName(name: String): String {
        return when (name) {
            "screen_size" -> {
                JSONObject().apply {
                    put("width",SCREEN_INFO.width)
                    put("height",SCREEN_INFO.height)
                    put("scale",SCREEN_INFO.scale)
                }.toString()
            }
            "is_start" -> {
                isCapture.toString()
            }
            else -> ""
        }
    }

    @Keep
    fun rustSetByName(name: String, arg1: String, arg2: String) {
        when (name) {
            "add_connection" -> {
                try {
                    val jsonObject = JSONObject(arg1)
                    val id = jsonObject["id"] as Int
                    val username = jsonObject["name"] as String
                    val peerId = jsonObject["peer_id"] as String
                    val authorized = jsonObject["authorized"] as Boolean
                    val isFileTransfer = jsonObject["is_file_transfer"] as Boolean
                    val isViewCamera = jsonObject["is_view_camera"] as Boolean
                    val isTerminal = jsonObject["is_view_camera"] as Boolean
                    var isCaptureModel = true
                    var type = translate("Share screen")
                    if (isFileTransfer) {
                        type = translate("Transfer file")
                        isCaptureModel =false
                    } else if (isViewCamera) {
                        type = translate("View camera")
                        isCaptureModel =false
                    } else if (isTerminal) {
                        type = translate("Terminal")
                        isCaptureModel =false
                    }
                    if (authorized) {
                        if (isCaptureModel) {
                            // 仅在当前已在采集时才执行“清理并重启”，避免非录屏场景（如仅使用摄像头）误触发录屏
                            // 保留 MediaProjection 权限（不弹二次授权）
                            stopCapture(false)
                            val ok = startCapture()
                            if (!ok) {
                                requestMediaProjection()
                            }

                        }
                        onClientAuthorizedNotification(id, type, username, peerId)
                    } else {
                        loginRequestNotification(id, type, username, peerId)
                    }
                } catch (e: JSONException) {
                    e.printStackTrace()
                }
            }
            "update_voice_call_state" -> {
                try {
                    val jsonObject = JSONObject(arg1)
                    val id = jsonObject["id"] as Int
                    val username = jsonObject["name"] as String
                    val peerId = jsonObject["peer_id"] as String
                    val inVoiceCall = jsonObject["in_voice_call"] as Boolean
                    val incomingVoiceCall = jsonObject["incoming_voice_call"] as Boolean
                    if (!inVoiceCall) {
                        if (incomingVoiceCall) {
                            voiceCallRequestNotification(id, "Voice Call Request", username, peerId)
                        } else {
                            if (!audioRecordHandle.switchOutVoiceCall(mediaProjection)) {
                                Log.e(logTag, "switchOutVoiceCall fail")
                                MainActivity.flutterMethodChannel?.invokeMethod("msgbox", mapOf(
                                    "type" to "custom-nook-nocancel-hasclose-error",
                                    "title" to "Voice call",
                                    "text" to "Failed to switch out voice call."))
                            }
                        }
                    } else {
                        if (!audioRecordHandle.switchToVoiceCall(mediaProjection)) {
                            Log.e(logTag, "switchToVoiceCall fail")
                            MainActivity.flutterMethodChannel?.invokeMethod("msgbox", mapOf(
                                "type" to "custom-nook-nocancel-hasclose-error",
                                "title" to "Voice call",
                                "text" to "Failed to switch to voice call."))
                        }
                    }
                } catch (e: JSONException) {
                    e.printStackTrace()
                }
            }
            "stop_capture" -> {
                Log.d(logTag, "from rust:stop_capture (keep MediaProjection)")
                // 断开连接等场景，仅停止推流/编码等管线，保留 MediaProjection 权限
                stopCapture(false)
            }
            "half_scale" -> {
                val halfScale = arg1.toBoolean()
                if (isHalfScale != halfScale) {
                    isHalfScale = halfScale
                    updateScreenInfo(resources.configuration.orientation)
                }
                
            }
            "start_camera" -> {
                // arg1: cameraId (e.g., "0")
                 startCameraCapture(arg1)
                Log.d(logTag, "from rust:start_camera")
            }
            "stop_camera" -> {
                // Debounce/guard: stop from an old service may arrive after a new start during switches.
                serviceHandler?.postDelayed({
                    val now = SystemClock.elapsedRealtime()
                    val sinceStart = now - lastCameraStartAtMs
                    if (isCameraCapturing && sinceStart < 1000L) {
                        Log.w(logTag, "Ignore stop_camera due to recent start (${sinceStart}ms ago), likely stale from previous service")
                    } else {
                        stopCameraCapture()
                    }
                }, 300L)
                Log.d(logTag, "from rust:stop_camera")
            }
            else -> {
            }
        }
    }

    private var serviceLooper: Looper? = null
    private var serviceHandler: Handler? = null

    private val powerManager: PowerManager by lazy { applicationContext.getSystemService(Context.POWER_SERVICE) as PowerManager }
    private val wakeLock: PowerManager.WakeLock by lazy { powerManager.newWakeLock(PowerManager.ACQUIRE_CAUSES_WAKEUP or PowerManager.SCREEN_BRIGHT_WAKE_LOCK, "rustdesk:wakelock")}

    companion object {
        private var _isMainServer = false // media permission ready status
        private var _isCapture = false // screen capture start status
        private var _isAudioStart = false // audio capture start status
        val isMainServer: Boolean
            get() = _isMainServer
        val isCapture: Boolean
            get() = _isCapture
        val isAudioStart: Boolean
            get() = _isAudioStart
    }

    private val logTag = "LOG_SERVICE"
    private val useVP9 = false
    private val binder = LocalBinder()

    private var reuseVirtualDisplay = Build.VERSION.SDK_INT > 33

    // video
    private var mediaProjection: MediaProjection? = null
    private var mediaProjectionCallback: MediaProjection.Callback? = null
    private var surface: Surface? = null
    private val sendVP9Thread = Executors.newSingleThreadExecutor()
    private var videoEncoder: MediaCodec? = null
    private var imageReader: ImageReader? = null
    private var virtualDisplay: VirtualDisplay? = null
    // camera
    private var cameraDevice: CameraDevice? = null
    private var cameraSession: CameraCaptureSession? = null
    private var cameraImageReader: ImageReader? = null
    private var isCameraCapturing: Boolean = false
    private var activeCameraId: String? = null
    // Guard concurrent opens/switches: only the latest token is valid
    private var cameraOpenToken: Int = 0
    // Watchdog: last time we received a camera frame (elapsedRealtime ms)
    private var lastCameraFrameAtMs: Long = 0L
    // Timestamp when current camera session started (elapsedRealtime ms)
    private var lastCameraStartAtMs: Long = 0L

    // audio
    private val audioRecordHandle = AudioRecordHandle(this, { isCapture }, { isAudioStart })

    // notification
    private lateinit var notificationManager: NotificationManager
    private lateinit var notificationChannel: String
    private lateinit var notificationBuilder: NotificationCompat.Builder

    override fun onCreate() {
        super.onCreate()
        Log.d(logTag,"MainService onCreate, sdk int:${Build.VERSION.SDK_INT} reuseVirtualDisplay:$reuseVirtualDisplay")
        FFI.init(this)
        HandlerThread("Service", Process.THREAD_PRIORITY_BACKGROUND).apply {
            start()
            serviceLooper = looper
            serviceHandler = Handler(looper)
        }
        updateScreenInfo(resources.configuration.orientation)
        initNotification()

        // keep the config dir same with flutter
        val prefs = applicationContext.getSharedPreferences(KEY_SHARED_PREFERENCES, FlutterActivity.MODE_PRIVATE)
        val configPath = prefs.getString(KEY_APP_DIR_CONFIG_PATH, "") ?: ""
        FFI.startServer(configPath, "")

        createForegroundNotification()
    }

    override fun onDestroy() {
        checkMediaPermission()
        stopService(Intent(this, FloatingWindowService::class.java))
        super.onDestroy()
    }

    private var isHalfScale: Boolean? = null;
    private fun updateScreenInfo(orientation: Int) {
        var w: Int
        var h: Int
        var dpi: Int
        val windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager

        @Suppress("DEPRECATION")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val m = windowManager.maximumWindowMetrics
            w = m.bounds.width()
            h = m.bounds.height()
            dpi = resources.configuration.densityDpi
        } else {
            val dm = DisplayMetrics()
            windowManager.defaultDisplay.getRealMetrics(dm)
            w = dm.widthPixels
            h = dm.heightPixels
            dpi = dm.densityDpi
        }

        val max = max(w,h)
        val min = min(w,h)
        if (orientation == ORIENTATION_LANDSCAPE) {
            w = max
            h = min
        } else {
            w = min
            h = max
        }
        Log.d(logTag,"updateScreenInfo:w:$w,h:$h")
        var scale = 1
        if (w != 0 && h != 0) {
            if (isHalfScale == true && (w > MAX_SCREEN_SIZE || h > MAX_SCREEN_SIZE)) {
                scale = 2
                w /= scale
                h /= scale
                dpi /= scale
            }
            // 仅当任一关键参数变化时才触发刷新
            val changed = SCREEN_INFO.width != w || SCREEN_INFO.height != h || SCREEN_INFO.dpi != dpi || SCREEN_INFO.scale != scale
            if (changed) {
                SCREEN_INFO.width = w
                SCREEN_INFO.height = h
                SCREEN_INFO.scale = scale
                SCREEN_INFO.dpi = dpi
                if (isCapture) {
                    // 仅在稳定运行态下才执行内部重启，避免与“启动中/停止中”交叉
                    if (captureState == CaptureState.Running) {
                        // For internal restart due to resolution/orientation change,
                        // do NOT stop MediaProjection, only recycle video pipeline.
                        stopCapture(false)
                        FFI.refreshScreen()
                        startCapture()
                    } else {
                        // 非 Running 阶段，只做刷新同步，等待当前流程完成
                        FFI.refreshScreen()
                    }
                } else {
                    FFI.refreshScreen()
                }
            }

        }
    }

    override fun onBind(intent: Intent): IBinder {
        Log.d(logTag, "service onBind")
        return binder
    }

    inner class LocalBinder : Binder() {
        init {
            Log.d(logTag, "LocalBinder init")
        }

        fun getService(): MainService = this@MainService
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d("whichService", "this service: ${Thread.currentThread()}")
        super.onStartCommand(intent, flags, startId)
        if (intent?.action == ACT_INIT_MEDIA_PROJECTION_AND_SERVICE) {
            createForegroundNotification()

            if (intent.getBooleanExtra(EXT_INIT_FROM_BOOT, false)) {
                FFI.startService()
            }
            Log.d(logTag, "service starting: ${startId}:${Thread.currentThread()}")
            val mediaProjectionManager =
                getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager

            intent.getParcelableExtra<Intent>(EXT_MEDIA_PROJECTION_RES_INTENT)?.let {
                mediaProjection =
                    mediaProjectionManager.getMediaProjection(Activity.RESULT_OK, it)
                // Watch for system stop of MediaProjection to keep Flutter state consistent
                val callback = object : MediaProjection.Callback() {
                    override fun onStop() {
                        Log.d(logTag, "MediaProjection onStop: system stopped capture")
                        stopCapture()
                    }
                }
                mediaProjectionCallback = callback
                mediaProjection?.registerCallback(callback, serviceHandler)
                checkMediaPermission()
                // Do NOT mark main server started here; capture can run independently
                // Start capture immediately after permission is granted
                startCapture()
            } ?: let {
                Log.d(logTag, "getParcelableExtra intent null, invoke requestMediaProjection")
                requestMediaProjection()
            }
        } else if (intent?.action == ACT_START_SERVICE_ONLY) {
            createForegroundNotification()
            _isMainServer = true
            checkMediaPermission()
            // If started from boot and user preset capture, do NOT auto pop permission, only notify
            try {
                val fromBoot = intent.getBooleanExtra(EXT_INIT_FROM_BOOT, false)
                if (fromBoot && !_isCapture) {
                    val prefs = getSharedPreferences(KEY_SHARED_PREFERENCES, MODE_PRIVATE)
                    val preset = prefs.getBoolean(KEY_CAPTURE_PRESET, false)
                    if (preset) {
                        // 不发通知，直接请求录屏权限
                        requestMediaProjection()
                    }
                }
            } catch (_: Exception) {}
        }
        return START_NOT_STICKY // don't use sticky (auto restart), the new service (from auto restart) will lose control
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        updateScreenInfo(newConfig.orientation)
    }

    private fun requestMediaProjection() {
        val intent = Intent(this, PermissionRequestTransparentActivity::class.java).apply {
            action = ACT_REQUEST_MEDIA_PROJECTION
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }
        startActivity(intent)
    }

    @SuppressLint("WrongConstant")
    private fun createSurface(): Surface? {
        return if (useVP9) {
            // TODO
            null
        } else {
            Log.d(logTag, "ImageReader.newInstance:INFO:$SCREEN_INFO")
            imageReader =
                ImageReader.newInstance(
                    SCREEN_INFO.width,
                    SCREEN_INFO.height,
                    PixelFormat.RGBA_8888,
                    4
                ).apply {
                    setOnImageAvailableListener({ imageReader: ImageReader ->
                        try {
                            // If not call acquireLatestImage, listener will not be called again
                            imageReader.acquireLatestImage().use { image ->
                                if (image == null || !isCapture) return@setOnImageAvailableListener
                                val planes = image.planes
                                val buffer = planes[0].buffer
                                buffer.rewind()
                                FFI.onVideoFrameUpdate(buffer)
                            }
                        } catch (ignored: java.lang.Exception) {
                        }
                    }, serviceHandler)
                }
            Log.d(logTag, "ImageReader.setOnImageAvailableListener done")
            imageReader?.surface
        }
    }

    fun onVoiceCallStarted(): Boolean {
        return audioRecordHandle.onVoiceCallStarted(mediaProjection)
    }

    fun onVoiceCallClosed(): Boolean {
        return audioRecordHandle.onVoiceCallClosed(mediaProjection)
    }

    fun startCapture(): Boolean {
        // 基于状态机的并发防护：Starting/Running 直接返回，避免二次启动
        when (captureState) {
            CaptureState.Starting, CaptureState.Running -> return true
            CaptureState.Stopping -> {
                Log.w(logTag, "startCapture ignored: currently stopping")
                return false
            }
            CaptureState.Idle -> {}
        }
        captureState = CaptureState.Starting
        if (mediaProjection == null) {
            Log.w(logTag, "startCapture fail,mediaProjection is null")
            captureState = CaptureState.Idle
            return false
        }
        
        updateScreenInfo(resources.configuration.orientation)
        Log.d(logTag, "Start Capture")
        surface = createSurface()

        if (useVP9) {
            startVP9VideoRecorder(mediaProjection!!)
        } else {
            startRawVideoRecorder(mediaProjection!!)
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            if (!audioRecordHandle.createAudioRecorder(false, mediaProjection)) {
                Log.d(logTag, "createAudioRecorder fail")
            } else {
                Log.d(logTag, "audio recorder start")
                audioRecordHandle.startAudioRecorder()
            }
        }
        checkMediaPermission()
        _isCapture = true
        captureState = CaptureState.Running
        FFI.setFrameRawEnable("video",true)
        MainActivity.rdClipboardManager?.setCaptureStarted(_isCapture)
        return true
    }

    fun stopMainServerOnly() {
        _isMainServer = false
        checkMediaPermission()
    }

    @Synchronized
    fun stopCapture(releaseProjection: Boolean = true) {
        // Idempotent guard: if nothing to stop, just emit current state
        if (!_isCapture && mediaProjection == null && virtualDisplay == null && imageReader == null && videoEncoder == null) {
            checkMediaPermission()
            return
        }
        captureState = CaptureState.Stopping
        Log.d(logTag, "Stop Capture")
        FFI.setFrameRawEnable("video",false)
        _isCapture = false
        MainActivity.rdClipboardManager?.setCaptureStarted(_isCapture)
        // release video
        try {
            virtualDisplay?.release()
        } catch (_: Exception) {}
        virtualDisplay = null
        // suface needs to be release after `imageReader.close()` to imageReader access released surface
        // https://github.com/rustdesk/rustdesk/issues/4118#issuecomment-1515666629
        imageReader?.close()
        imageReader = null
        videoEncoder?.let {
            it.signalEndOfInputStream()
            it.stop()
            it.release()
        }
        videoEncoder = null
        // suface needs to be release after `imageReader.close()` to imageReader access released surface
        // https://github.com/rustdesk/rustdesk/issues/4118#issuecomment-1515666629
        surface?.release()

        // release audio
        _isAudioStart = false
        audioRecordHandle.tryReleaseAudio()
        if (releaseProjection) {
            // stop MediaProjection to close system recording UI
            try {
                mediaProjectionCallback?.let { cb ->
                    mediaProjection?.unregisterCallback(cb)
                }
            } catch (_: Exception) {}
            mediaProjectionCallback = null
            try {
                mediaProjection?.stop()
            } catch (_: Exception) {}
            mediaProjection = null
        }
        // notify flutter about capture state change
        checkMediaPermission()
        captureState = CaptureState.Idle
    }

    fun destroy() {
        Log.d(logTag, "destroy service")
        _isMainServer = false
        _isAudioStart = false

        stopCapture()

        if (reuseVirtualDisplay) {
            virtualDisplay?.release()
            virtualDisplay = null
        }

        mediaProjection = null
        checkMediaPermission()
        stopForeground(true)
        stopService(Intent(this, FloatingWindowService::class.java))
        stopSelf()
    }

    fun checkMediaPermission(): Boolean {
        // Report capture status as `media`, and server status as `server`
        Handler(Looper.getMainLooper()).post {
            MainActivity.flutterMethodChannel?.invokeMethod(
                "on_state_changed",
                mapOf("name" to "media", "value" to isCapture.toString())
            )
        }
        Handler(Looper.getMainLooper()).post {
            MainActivity.flutterMethodChannel?.invokeMethod(
                "on_state_changed",
                mapOf("name" to "server", "value" to isMainServer.toString())
            )
        }
        Handler(Looper.getMainLooper()).post {
            MainActivity.flutterMethodChannel?.invokeMethod(
                "on_state_changed",
                mapOf("name" to "input", "value" to InputService.isOpen.toString())
            )
        }
        return isCapture
    }

    private fun startRawVideoRecorder(mp: MediaProjection) {
        Log.d(logTag, "startRawVideoRecorder,screen info:$SCREEN_INFO")
        if (surface == null) {
            Log.d(logTag, "startRawVideoRecorder failed,surface is null")
            return
        }
        createOrSetVirtualDisplay(mp, surface!!)
    }

    private fun startVP9VideoRecorder(mp: MediaProjection) {
        createMediaCodec()
        videoEncoder?.let {
            surface = it.createInputSurface()
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                surface!!.setFrameRate(1F, FRAME_RATE_COMPATIBILITY_DEFAULT)
            }
            it.setCallback(cb)
            it.start()
            createOrSetVirtualDisplay(mp, surface!!)
        }
    }

    // https://github.com/bk138/droidVNC-NG/blob/b79af62db5a1c08ed94e6a91464859ffed6f4e97/app/src/main/java/net/christianbeier/droidvnc_ng/MediaProjectionService.java#L250
    // Reuse virtualDisplay if it exists, to avoid media projection confirmation dialog every connection.
    private fun createOrSetVirtualDisplay(mp: MediaProjection, s: Surface) {
        try {
            virtualDisplay?.let {
                it.resize(SCREEN_INFO.width, SCREEN_INFO.height, SCREEN_INFO.dpi)
                it.setSurface(s)
            } ?: let {
                virtualDisplay = mp.createVirtualDisplay(
                    "RustDeskVD",
                    SCREEN_INFO.width, SCREEN_INFO.height, SCREEN_INFO.dpi, VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                    s, null, null
                )
            }
        } catch (e: SecurityException) {
            Log.w(logTag, "createOrSetVirtualDisplay: got SecurityException, re-requesting confirmation");
            // This initiates a prompt dialog for the user to confirm screen projection.
            requestMediaProjection()
        }
    }

    private val cb: MediaCodec.Callback = object : MediaCodec.Callback() {
        override fun onInputBufferAvailable(codec: MediaCodec, index: Int) {}
        override fun onOutputFormatChanged(codec: MediaCodec, format: MediaFormat) {}

        override fun onOutputBufferAvailable(
            codec: MediaCodec,
            index: Int,
            info: MediaCodec.BufferInfo
        ) {
            codec.getOutputBuffer(index)?.let { buf ->
                sendVP9Thread.execute {
                    val byteArray = ByteArray(buf.limit())
                    buf.get(byteArray)
                    // sendVp9(byteArray)
                    codec.releaseOutputBuffer(index, false)
                }
            }
        }

        override fun onError(codec: MediaCodec, e: MediaCodec.CodecException) {
            Log.e(logTag, "MediaCodec.Callback error:$e")
        }
    }

    private fun createMediaCodec() {
        Log.d(logTag, "MediaFormat.MIMETYPE_VIDEO_VP9 :$MIME_TYPE")
        videoEncoder = MediaCodec.createEncoderByType(MIME_TYPE)
        val mFormat =
            MediaFormat.createVideoFormat(MIME_TYPE, SCREEN_INFO.width, SCREEN_INFO.height)
        mFormat.setInteger(MediaFormat.KEY_BIT_RATE, VIDEO_KEY_BIT_RATE)
        mFormat.setInteger(MediaFormat.KEY_FRAME_RATE, VIDEO_KEY_FRAME_RATE)
        mFormat.setInteger(
            MediaFormat.KEY_COLOR_FORMAT,
            MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible
        )
        mFormat.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 5)
        try {
            videoEncoder!!.configure(mFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        } catch (e: Exception) {
            Log.e(logTag, "mEncoder.configure fail!")
        }
    }

    private fun initNotification() {
        notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationChannel = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channelId = "RustDesk"
            val channelName = "RustDesk Service"
            val channel = NotificationChannel(
                channelId,
                channelName, NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "RustDesk Service Channel"
            }
            channel.lightColor = Color.BLUE
            channel.lockscreenVisibility = Notification.VISIBILITY_PRIVATE
            notificationManager.createNotificationChannel(channel)
            channelId
        } else {
            ""
        }
        notificationBuilder = NotificationCompat.Builder(this, notificationChannel)
    }

    @SuppressLint("UnspecifiedImmutableFlag")
    private fun createForegroundNotification() {
        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED
            action = Intent.ACTION_MAIN
            addCategory(Intent.CATEGORY_LAUNCHER)
            putExtra("type", type)
        }
        val pendingIntent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            PendingIntent.getActivity(this, 0, intent, FLAG_UPDATE_CURRENT or FLAG_IMMUTABLE)
        } else {
            PendingIntent.getActivity(this, 0, intent, FLAG_UPDATE_CURRENT)
        }
        val notification = notificationBuilder
            .setOngoing(true)
            .setSmallIcon(R.mipmap.ic_stat_logo)
            .setDefaults(Notification.DEFAULT_ALL)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setContentTitle(DEFAULT_NOTIFY_TITLE)
            .setContentText(translate(DEFAULT_NOTIFY_TEXT))
            .setOnlyAlertOnce(true)
            .setContentIntent(pendingIntent)
            .setColor(ContextCompat.getColor(this, R.color.primary))
            .setWhen(System.currentTimeMillis())
            .build()
        startForeground(DEFAULT_NOTIFY_ID, notification)
    }

    private fun loginRequestNotification(
        clientID: Int,
        type: String,
        username: String,
        peerId: String
    ) {
        val notification = notificationBuilder
            .setOngoing(false)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setContentTitle(translate("Do you accept?"))
            .setContentText("$type:$username-$peerId")
            // .setStyle(MediaStyle().setShowActionsInCompactView(0, 1))
            // .addAction(R.drawable.check_blue, "check", genLoginRequestPendingIntent(true))
            // .addAction(R.drawable.close_red, "close", genLoginRequestPendingIntent(false))
            .build()
        notificationManager.notify(getClientNotifyID(clientID), notification)
    }

    private fun onClientAuthorizedNotification(
        clientID: Int,
        type: String,
        username: String,
        peerId: String
    ) {
        cancelNotification(clientID)
        val notification = notificationBuilder
            .setOngoing(false)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setContentTitle("$type ${translate("Established")}")
            .setContentText("$username - $peerId")
            .build()
        notificationManager.notify(getClientNotifyID(clientID), notification)
    }

    private fun voiceCallRequestNotification(
        clientID: Int,
        type: String,
        username: String,
        peerId: String
    ) {
        val notification = notificationBuilder
            .setOngoing(false)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setContentTitle(translate("Do you accept?"))
            .setContentText("$type:$username-$peerId")
            .build()
        notificationManager.notify(getClientNotifyID(clientID), notification)
    }

    private fun getClientNotifyID(clientID: Int): Int {
        return clientID + NOTIFY_ID_OFFSET
    }

    fun cancelNotification(clientID: Int) {
        notificationManager.cancel(getClientNotifyID(clientID))
    }

    @SuppressLint("UnspecifiedImmutableFlag")
    private fun genLoginRequestPendingIntent(res: Boolean): PendingIntent {
        val intent = Intent(this, MainService::class.java).apply {
            action = ACT_LOGIN_REQ_NOTIFY
            putExtra(EXT_LOGIN_REQ_NOTIFY, res)
        }
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            PendingIntent.getService(this, 111, intent, FLAG_IMMUTABLE)
        } else {
            PendingIntent.getService(this, 111, intent, FLAG_UPDATE_CURRENT)
        }
    }

    private fun setTextNotification(_title: String?, _text: String?) {
        val title = _title ?: DEFAULT_NOTIFY_TITLE
        val text = _text ?: translate(DEFAULT_NOTIFY_TEXT)
        val notification = notificationBuilder
            .clearActions()
            .setStyle(null)
            .setContentTitle(title)
            .setContentText(text)
            .build()
        notificationManager.notify(DEFAULT_NOTIFY_ID, notification)
    }

    /**
     * 获取所有摄像头的信息（id、名字、分辨率、前后摄像头）
     * 结果格式为 JSON 字符串，便于 Rust/JNI 解析
     */
    @Keep
    fun getCameraListJson(ctx: Context): String {
        val mgr = ctx.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        val arr = JSONArray()
        try {
            for (id in mgr.cameraIdList) {
                val chars = mgr.getCameraCharacteristics(id)
                val lensFacing = chars.get(CameraCharacteristics.LENS_FACING)
                val streamMap = chars.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
                var width = 0
                var height = 0
                var name = "Camera-$id"
                if (streamMap != null) {
                    // 获取支持的输出分辨率（选最大分辨率）
                    val sizes: Array<Size>? =
                        streamMap.getOutputSizes(android.graphics.ImageFormat.YUV_420_888)
                    if (sizes != null && sizes.isNotEmpty()) {
                        val best = sizes.maxByOrNull { it.width * it.height } ?: sizes[0]
                        width = best.width
                        height = best.height
                    }
                }
                if (lensFacing == CameraCharacteristics.LENS_FACING_FRONT)
                    name += "-front"
                else if (lensFacing == CameraCharacteristics.LENS_FACING_BACK)
                    name += "-back"

                val obj = JSONObject()
                obj.put("id", id)
                obj.put("name", name)
                obj.put("width", width)
                obj.put("height", height)
                obj.put("facing", lensFacing ?: -1)
                arr.put(obj)
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return arr.toString()
    }


    // region Camera capture (YUV_420_888 -> RGBA -> FFI.onVideoFrameUpdate)
    @Keep
    fun startCameraCapture(cameraId: String): Boolean {
        // 已在采集中且目标相机相同，直接复用
        if (isCameraCapturing && activeCameraId == cameraId) return true
        // 已在采集中但目标不同，先停止再切换
        if (isCameraCapturing && activeCameraId != cameraId) {
            // 停止当前相机，但不要在此处增加 token，避免后续新打开的 token 被意外失效
            stopCameraCaptureInternal(skipCloseSession = false)
        }
        // 在确保旧相机已停止后，再“统一”地生成新的 openToken，避免切换时出现双重递增导致的新会话帧被误判为过期
        val openToken = (cameraOpenToken + 1).coerceAtLeast(1)
        cameraOpenToken = openToken
        val hasPerm = ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.CAMERA
        ) == PackageManager.PERMISSION_GRANTED
        if (!hasPerm) {
            Log.w(logTag, "startCameraCapture: CAMERA permission not granted")
            return false
        }

        val mgr = getSystemService(Context.CAMERA_SERVICE) as CameraManager
        val chars = try {
            mgr.getCameraCharacteristics(cameraId)
        } catch (e: Exception) {
            Log.e(logTag, "getCameraCharacteristics fail: $e")
            return false
        }
        val map = chars.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
        if (map == null) {
            Log.e(logTag, "No SCALER_STREAM_CONFIGURATION_MAP for camera $cameraId")
            return false
        }
        val sizes = map.getOutputSizes(android.graphics.ImageFormat.YUV_420_888)
        if (sizes == null || sizes.isEmpty()) {
            Log.e(logTag, "No YUV_420_888 output sizes for camera $cameraId")
            return false
        }
        val best = sizes.maxByOrNull { it.width * it.height } ?: sizes[0]
        val w = best.width
        val h = best.height
        Log.d(logTag, "startCameraCapture: choose size ${w}x${h} for $cameraId")

        cameraImageReader =
            ImageReader.newInstance(w, h, android.graphics.ImageFormat.YUV_420_888, 4).apply {
                setOnImageAvailableListener({ reader ->
                    try {
                        reader.acquireLatestImage()?.use { image ->
                            // Drop frames if capture stopped or a newer open has superseded us
                            if (!isCameraCapturing || openToken != cameraOpenToken) return@use
                            // Build AndroidYuv420Frame and hand off to Rust for libyuv packing
                            val planes = image.planes
                            // Rewind buffers to ensure position=0 before handing to JNI
                            planes[0].buffer.rewind()
                            planes[1].buffer.rewind()
                            planes[2].buffer.rewind()
                            // Update watchdog timestamp
                            lastCameraFrameAtMs = SystemClock.elapsedRealtime()
                            val frame = AndroidYuv420Frame(
                                width = w,
                                height = h,
                                y = planes[0].buffer,
                                u = planes[1].buffer,
                                v = planes[2].buffer,
                                yRowStride = planes[0].rowStride,
                                uRowStride = planes[1].rowStride,
                                vRowStride = planes[2].rowStride,
                                uPixelStride = planes[1].pixelStride,
                                vPixelStride = planes[2].pixelStride,
                                tsNanos = try { image.timestamp } catch (_: Throwable) { 0L }
                            )
                            FFI.onCameraYuvFrame(frame)
                        }
                    } catch (_: Exception) {
                    }
                }, serviceHandler)
            }

        try {
            mgr.openCamera(cameraId, object : CameraDevice.StateCallback() {
                override fun onOpened(device: CameraDevice) {
                    // Ignore stale callbacks from a previous open attempt
                    if (openToken != cameraOpenToken) {
                        try { device.close() } catch (_: Exception) {}
                        return
                    }
                    cameraDevice = device
                    try {
                        val surface = cameraImageReader!!.surface
                        val req = device.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
                            addTarget(surface)
                            set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_AUTO)
                            // 设置最小可用倍率（最广角）
                            try {
                                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                                    val zoomRange = chars.get(CameraCharacteristics.CONTROL_ZOOM_RATIO_RANGE)
                                    val minZoom = zoomRange?.lower ?: 1.0f
                                    set(CaptureRequest.CONTROL_ZOOM_RATIO, minZoom)
                                } else {
                                    val active: Rect? = chars.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE)
                                    if (active != null) {
                                        // 1.0x 缩放 = 使用完整 active array（无裁剪）
                                        set(CaptureRequest.SCALER_CROP_REGION, active)
                                    }
                                }
                            } catch (_: Exception) {}
                        }
                        device.createCaptureSession(
                            listOf(surface),
                            object : CameraCaptureSession.StateCallback() {
                                override fun onConfigured(session: CameraCaptureSession) {
                                    if (openToken != cameraOpenToken) {
                                        try { session.close() } catch (_: Exception) {}
                                        return
                                    }
                                    cameraSession = session
                                    try {
                                        session.setRepeatingRequest(
                                            req.build(),
                                            null,
                                            serviceHandler
                                        )
                                        isCameraCapturing = true
                                        activeCameraId = cameraId
                                        // Reset watchdog time and start watchdog loop
                                        val nowTs = SystemClock.elapsedRealtime()
                                        lastCameraStartAtMs = nowTs
                                        lastCameraFrameAtMs = nowTs
                                        FFI.setFrameRawEnable("camera", true)
                                        Log.d(
                                            logTag,
                                            "Camera capture started: $cameraId @ ${w}x${h}"
                                        )
                                        scheduleCameraWatchdog(cameraId, openToken)
                                    } catch (e: Exception) {
                                        Log.e(logTag, "setRepeatingRequest failed: $e")
                                        stopCameraCapture()
                                        scheduleCameraRetry(cameraId)
                                    }
                                }

                                override fun onConfigureFailed(session: CameraCaptureSession) {
                                    Log.e(logTag, "Camera capture session configure failed")
                                    stopCameraCapture()
                                    scheduleCameraRetry(cameraId)
                                }
                            },
                            serviceHandler
                        )
                    } catch (e: Exception) {
                        Log.e(logTag, "create session failed: $e")
                        stopCameraCapture()
                        scheduleCameraRetry(cameraId)
                    }
                }

                override fun onDisconnected(device: CameraDevice) {
                    Log.w(logTag, "Camera disconnected: $cameraId")
                    // 在断开时，可能底层已进入错误/关闭态，避免触发 stopRepeating 的异常日志
                    stopCameraCaptureInternal(skipCloseSession = true)
                    scheduleCameraRetry(cameraId)
                }

                override fun onError(device: CameraDevice, error: Int) {
                    Log.e(logTag, "Camera error($error): $cameraId")
                    // 相机发生严重错误时，避免在 close() 内触发 stopRepeating 异常日志
                    stopCameraCaptureInternal(skipCloseSession = true)
                    scheduleCameraRetry(cameraId)
                }
            }, serviceHandler)
        } catch (e: SecurityException) {
            Log.e(logTag, "openCamera SecurityException: $e")
            return false
        } catch (e: Exception) {
            Log.e(logTag, "openCamera exception: $e")
            return false
        }
        return true
    }

    @Keep
    fun stopCameraCapture() {
        stopCameraCaptureInternal(skipCloseSession = false)
    }

    private fun stopCameraCaptureInternal(skipCloseSession: Boolean) {
        Log.d(logTag, "stopCameraCapture${if (skipCloseSession) "(skip session close)" else ""}")
        isCameraCapturing = false
        activeCameraId = null
        // Invalidate any in-flight callbacks from previous open
        cameraOpenToken += 1
        // Reset watchdog
        lastCameraFrameAtMs = 0L
        // 先关闭帧推送，避免并发拉帧
        try { FFI.setFrameRawEnable("camera", false) } catch (_: Exception) {}
        // 尝试停止/中止请求，防止 close 内部再触发 stopRepeating 异常
        if (!skipCloseSession) {
            try { cameraSession?.stopRepeating() } catch (_: Exception) {}
            try { cameraSession?.abortCaptures() } catch (_: Exception) {}
            try { cameraSession?.close() } catch (_: Exception) {}
        }
        cameraSession = null
        try { cameraDevice?.close() } catch (_: Exception) {}
        cameraDevice = null
        try { cameraImageReader?.close() } catch (_: Exception) {}
        cameraImageReader = null
    }

    private fun scheduleCameraRetry(cameraId: String, delayMs: Long = 250L) {
        // Only retry if nothing is currently capturing and target camera is same or none
        serviceHandler?.postDelayed({
            if (!isCameraCapturing && (activeCameraId == null || activeCameraId == cameraId)) {
                Log.w(logTag, "Retry opening camera $cameraId after failure")
                startCameraCapture(cameraId)
            }
        }, delayMs)
    }

    private fun scheduleCameraWatchdog(cameraId: String, tokenAtStart: Int, intervalMs: Long = 1000L) {
        // Periodically check if frames are flowing; if stalled, restart the camera
        serviceHandler?.postDelayed({
            // Abort if a new open has started or capture stopped
            if (tokenAtStart != cameraOpenToken || !isCameraCapturing || activeCameraId != cameraId) {
                return@postDelayed
            }
            val now = SystemClock.elapsedRealtime()
            val last = lastCameraFrameAtMs
            // If no frames for > 1.5s, assume session is stuck and restart quickly
            if (last > 0 && now - last > 1500L) {
                Log.w(logTag, "Camera watchdog: no frames for ${now - last}ms, restarting $cameraId")
                stopCameraCaptureInternal(skipCloseSession = true)
                scheduleCameraRetry(cameraId, 100L)
                return@postDelayed
            }
            // Reschedule next watchdog tick
            scheduleCameraWatchdog(cameraId, tokenAtStart, intervalMs)
        }, intervalMs)
    }

    // endregion

}
