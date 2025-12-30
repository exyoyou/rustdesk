// ffi.kt

package ffi

import android.content.Context
import java.nio.ByteBuffer

import com.carriez.flutter_hbb.RdClipboardManager
import androidx.annotation.Keep

// Encapsulate Android YUV_420_888 planes and metadata for JNI parsing
@Keep
data class AndroidYuv420Frame(
    val width: Int,
    val height: Int,
    val y: ByteBuffer,
    val u: ByteBuffer,
    val v: ByteBuffer,
    val yRowStride: Int,
    val uRowStride: Int,
    val vRowStride: Int,
    val uPixelStride: Int,
    val vPixelStride: Int,
    val tsNanos: Long = 0L,
)

object FFI {
    init {
        System.loadLibrary("rustdesk")
    }

    external fun init(ctx: Context)
    external fun onAppStart(ctx: Context)
    external fun setClipboardManager(clipboardManager: RdClipboardManager)
    external fun startServer(app_dir: String, custom_client_config: String)
    external fun startService()
    external fun onVideoFrameUpdate(buf: ByteBuffer)
    // New API: pass three-plane YUV_420_888 frame; Rust will pack to I420 via libyuv
    external fun onCameraYuvFrame(frame: AndroidYuv420Frame)
    external fun onAudioFrameUpdate(buf: ByteBuffer)
    external fun translateLocale(localeName: String, input: String): String
    external fun refreshScreen()
    external fun setFrameRawEnable(name: String, value: Boolean)
    external fun setCodecInfo(info: String)
    external fun getLocalOption(key: String): String
    external fun getMyId(): String  // 新增：获取设备 ID，与 Flutter 的 mainGetMyId() 一致
    external fun onClipboardUpdate(clips: ByteBuffer)
    external fun isServiceClipboardEnabled(): Boolean
}
