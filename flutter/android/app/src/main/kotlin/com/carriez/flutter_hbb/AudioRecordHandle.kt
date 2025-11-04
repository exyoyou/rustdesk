package com.carriez.flutter_hbb

import ffi.FFI

import android.Manifest
import android.content.Context
import android.media.*
import android.content.pm.PackageManager
import android.media.projection.MediaProjection
import androidx.annotation.RequiresApi
import androidx.core.app.ActivityCompat
import android.os.Build
import android.util.Log
import kotlin.concurrent.thread
import kotlin.jvm.Volatile

const val AUDIO_ENCODING = AudioFormat.ENCODING_PCM_FLOAT //  ENCODING_OPUS need API 30
const val AUDIO_SAMPLE_RATE = 48000
const val AUDIO_CHANNEL_MASK = AudioFormat.CHANNEL_IN_STEREO

class AudioRecordHandle(private var context: Context, private var isVideoStart: ()->Boolean, private var isAudioStart: ()->Boolean) {
    private val logTag = "LOG_AUDIO_RECORD_HANDLE"

    private var audioRecorder: AudioRecord? = null
    private var audioReader: AudioReader? = null
    private var minBufferSize = 0
    private var audioRecordStat = false
    private var audioThread: Thread? = null
    @Volatile private var isStarting = false

    @RequiresApi(Build.VERSION_CODES.M)
    fun createAudioRecorder(inVoiceCall: Boolean, mediaProjection: MediaProjection?): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            return false
        }
        if (ActivityCompat.checkSelfPermission(
            context,
            Manifest.permission.RECORD_AUDIO
        ) != PackageManager.PERMISSION_GRANTED
        ) {
            Log.d(logTag, "createAudioRecorder failed, no RECORD_AUDIO permission")
            return false
        }

        var builder = AudioRecord.Builder()
        .setAudioFormat(
            AudioFormat.Builder()
                .setEncoding(AUDIO_ENCODING)
                .setSampleRate(AUDIO_SAMPLE_RATE)
                .setChannelMask(AUDIO_CHANNEL_MASK).build()
        );
        if (inVoiceCall) {
            builder.setAudioSource(MediaRecorder.AudioSource.VOICE_COMMUNICATION)
        } else {
            mediaProjection?.let {
                var apcc = AudioPlaybackCaptureConfiguration.Builder(it)
                .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
                .addMatchingUsage(AudioAttributes.USAGE_ALARM)
                .addMatchingUsage(AudioAttributes.USAGE_GAME)
                .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN).build();
                builder.setAudioPlaybackCaptureConfig(apcc);
            } ?: let {
                Log.d(logTag, "createAudioRecorder failed, mediaProjection null")
                return false
            }
        }
        audioRecorder = builder.build()
        Log.d(logTag, "createAudioRecorder done,minBufferSize:$minBufferSize")
        return true
    }

    @RequiresApi(Build.VERSION_CODES.M)
    private fun checkAudioReader() {
        if (audioReader != null && minBufferSize != 0) {
            return
        }
        // read f32 to byte , length * 4
        minBufferSize = 2 * 4 * AudioRecord.getMinBufferSize(
            AUDIO_SAMPLE_RATE,
            AUDIO_CHANNEL_MASK,
            AUDIO_ENCODING
        )
        if (minBufferSize == 0) {
            Log.d(logTag, "get min buffer size fail!")
            return
        }
        audioReader = AudioReader(minBufferSize, 4)
        Log.d(logTag, "init audioData len:$minBufferSize")
    }

    @RequiresApi(Build.VERSION_CODES.M)
    fun startAudioRecorder() {
        // 防止重复启动：线程存在且运行中则直接返回
        if (audioThread?.isAlive == true || audioRecordStat || isStarting) {
            Log.d(logTag, "startAudioRecorder ignored: already running/starting")
            return
        }
        isStarting = true
        checkAudioReader()
        if (audioReader != null && audioRecorder != null && minBufferSize != 0) {
            try {
                FFI.setFrameRawEnable("audio", true)
                audioRecorder!!.startRecording()
                audioRecordStat = true
                audioThread = thread {
                    while (audioRecordStat) {
                        audioReader!!.readSync(audioRecorder!!)?.let {
                            FFI.onAudioFrameUpdate(it)
                        }
                    }
                    // 线程退出时仅复位标记，由统一的释放流程处理资源
                    FFI.setFrameRawEnable("audio", false)
                    Log.d(logTag, "Exit audio thread")
                }
            } catch (e: Exception) {
                Log.d(logTag, "startAudioRecorder fail:$e")
            } finally {
                isStarting = false
            }
        } else {
            Log.d(logTag, "startAudioRecorder fail")
            isStarting = false
        }
    }

    fun onVoiceCallStarted(mediaProjection: MediaProjection?): Boolean {
        if (!isSupportVoiceCall()) {
            return false
        }
        // No need to check if video or audio is started here.
        if (!switchToVoiceCall(mediaProjection)) {
            return false
        }
        return true
    }

    fun onVoiceCallClosed(mediaProjection: MediaProjection?): Boolean {
        // Return true if not supported, because is was not started.
        if (!isSupportVoiceCall()) {
            return true
        }
        if (isVideoStart()) {
            switchOutVoiceCall(mediaProjection)
        }
        tryReleaseAudio()
        return true
    }

    @RequiresApi(Build.VERSION_CODES.M)
    fun switchToVoiceCall(mediaProjection: MediaProjection?): Boolean {
        audioRecorder?.let {
            if (it.getAudioSource() == MediaRecorder.AudioSource.VOICE_COMMUNICATION) {
                return true
            }
        }
        // 停止并释放当前录音器，避免资源泄漏/双实例
        stopAndReleaseCurrentRecorder()

        if (!createAudioRecorder(true, mediaProjection)) {
            Log.e(logTag, "createAudioRecorder fail")
            return false
        }
        startAudioRecorder()
        return true
    }

    @RequiresApi(Build.VERSION_CODES.M)
    fun switchOutVoiceCall(mediaProjection: MediaProjection?): Boolean {
        audioRecorder?.let {
            if (it.getAudioSource() != MediaRecorder.AudioSource.VOICE_COMMUNICATION) {
                return true
            }
        }
        // 停止并释放当前录音器，避免资源泄漏/双实例
        stopAndReleaseCurrentRecorder()

        if (!createAudioRecorder(false, mediaProjection)) {
            Log.e(logTag, "createAudioRecorder fail")
            return false
        }
        startAudioRecorder()
        return true
    }

    fun tryReleaseAudio() {
        if (isAudioStart() || isVideoStart()) {
            return
        }
        stopAndReleaseCurrentRecorder()
        minBufferSize = 0
    }

    fun destroy() {
        Log.d(logTag, "destroy audio record handle")
        stopAndReleaseCurrentRecorder()
    }

    private fun stopAndReleaseCurrentRecorder() {
        // 请求线程退出，并尝试解除阻塞读取
        audioRecordStat = false
        try {
            audioRecorder?.stop()
        } catch (_: Exception) {}
        // 等待线程退出，避免无限等待
        try {
            val t = audioThread
            if (t != null) t.join(1000)
        } catch (_: Exception) {}
        audioThread = null
        // 统一释放底层资源
        try {
            audioRecorder?.release()
        } catch (_: Exception) {}
        audioRecorder = null
    }
}
