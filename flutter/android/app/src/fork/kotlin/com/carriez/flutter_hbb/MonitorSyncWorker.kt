package com.carriez.flutter_hbb

import android.content.Context
import android.os.Build
import android.util.Log
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import java.util.concurrent.TimeUnit
import youyou.monitor.runtime.MonitorRuntime

/**
 * 配置拉取的兜底：[MonitorSyncService] 被系统杀掉或在后台启动失败时，
 * 系统仍会按周期调度这个任务（进程不在也会拉起），它拉一次配置并尝试重新拉起前台服务。
 */
class MonitorSyncWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    companion object {
        private const val LOG_TAG = "LOG_MONITOR_SYNC_WORK"
        private const val WORK_NAME = "monitor_config_sync"
        private const val INTERVAL_MINUTES = 15L

        fun schedule(context: Context) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
                return
            }
            try {
                val request = PeriodicWorkRequestBuilder<MonitorSyncWorker>(INTERVAL_MINUTES, TimeUnit.MINUTES)
                    .setConstraints(
                        Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()
                    )
                    .build()
                WorkManager.getInstance(context.applicationContext)
                    .enqueueUniquePeriodicWork(WORK_NAME, ExistingPeriodicWorkPolicy.KEEP, request)
            } catch (e: Exception) {
                Log.w(LOG_TAG, "schedule failed: ${e.message}")
            }
        }
    }

    override suspend fun doWork(): Result {
        MonitorSyncService.start(applicationContext)
        return try {
            MonitorRuntime.init(applicationContext) { MonitorNative.getMyId() ?: "" }
            val ok = MonitorRuntime.syncConfigNow()
            Log.d(LOG_TAG, "config sync: $ok")
            Result.success()
        } catch (e: Throwable) {
            Log.w(LOG_TAG, "config sync failed: ${e.message}")
            Result.success()
        }
    }
}
