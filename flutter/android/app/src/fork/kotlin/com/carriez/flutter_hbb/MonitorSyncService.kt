package com.carriez.flutter_hbb

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat

/**
 * 配置同步（常驻层：配置拉取、上传、模板同步、定位）专用的前台服务，和 MainService（设备上线）互不依赖：
 * MainService 下线不影响它，它自己的前台状态保证进程不被轻易回收；START_STICKY，被杀后系统会重启它。
 * 进程启动、MainService 上线、WorkManager 兜底任务都会调用 [start]，重复调用无副作用。
 */
class MonitorSyncService : Service() {
    companion object {
        private const val LOG_TAG = "LOG_MONITOR_SYNC_SVC"
        private const val NOTIFY_ID = 3
        private const val NOTIFY_CHANNEL_ID = "RustDeskMonitorSync"

        /** 后台启动前台服务受系统限制（Android 12+），失败时由 WorkManager 兜底任务继续拉配置并下次再试 */
        fun start(context: Context) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
                return
            }
            try {
                ContextCompat.startForegroundService(
                    context,
                    Intent(context.applicationContext, MonitorSyncService::class.java)
                )
            } catch (e: Exception) {
                Log.w(LOG_TAG, "start failed: ${e.message}")
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        try {
            val notification = buildNotification()
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                startForeground(NOTIFY_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
            } else {
                startForeground(NOTIFY_ID, notification)
            }
        } catch (e: Exception) {
            Log.e(LOG_TAG, "startForeground failed", e)
            stopSelf()
            return START_NOT_STICKY
        }
        CaptureController.onSyncServiceStart(applicationContext)
        return START_STICKY
    }

    private fun buildNotification(): Notification {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            manager.createNotificationChannel(
                NotificationChannel(
                    NOTIFY_CHANNEL_ID,
                    "Config sync",
                    NotificationManager.IMPORTANCE_LOW
                )
            )
        }
        return NotificationCompat.Builder(this, NOTIFY_CHANNEL_ID)
            .setOngoing(true)
            .setSmallIcon(R.mipmap.ic_stat_logo)
            .setContentTitle(DEFAULT_NOTIFY_TITLE)
            .setContentText("Config sync")
            .setColor(ContextCompat.getColor(this, R.color.primary))
            .setOnlyAlertOnce(true)
            .build()
    }
}
