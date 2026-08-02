package com.example.mtsignin.service

import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.example.mtsignin.data.repository.SignRepository
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import java.util.concurrent.TimeUnit

@HiltWorker
class SignInWorker @AssistedInject constructor(
    @Assisted private val context: Context,
    @Assisted private val workerParams: WorkerParameters,
    private val repository: SignRepository
) : CoroutineWorker(context, workerParams) {

    override suspend fun doWork(): Result {
        try {
            // 并行签到所有启用的账号
            val results = repository.signInAll()

            if (results.isEmpty()) {
                return Result.success()
            }

            var successCount = 0
            var failCount = 0

            results.forEach { (_, result) ->
                if (result.isSuccess()) {
                    successCount++
                } else {
                    failCount++
                }
            }

            // 发送通知
            sendNotification(successCount, failCount)

            return Result.success()
        } catch (e: Exception) {
            return Result.retry()
        }
    }

    private fun sendNotification(successCount: Int, failCount: Int) {
        val title = "MT论坛签到完成"
        val message = "成功: $successCount, 失败: $failCount"

        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as android.app.NotificationManager
        val channelId = "sign_in_channel"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = android.app.NotificationChannel(
                channelId,
                "签到通知",
                android.app.NotificationManager.IMPORTANCE_DEFAULT
            )
            manager.createNotificationChannel(channel)
        }

        val notification = androidx.core.app.NotificationCompat.Builder(
            context,
            channelId
        )
            .setSmallIcon(android.R.drawable.stat_notify_more)
            .setContentTitle(title)
            .setContentText(message)
            .setPriority(androidx.core.app.NotificationCompat.PRIORITY_DEFAULT)
            .setAutoCancel(true)
            .build()

        androidx.core.app.NotificationManagerCompat.from(context).notify(1001, notification)
    }

    companion object {
        private const val WORK_NAME = "mt_forum_sign_in"

        /**
         * 启动定时签到任务
         */
        fun schedulePeriodicSignIn(context: Context) {
            val workRequest = PeriodicWorkRequestBuilder<SignInWorker>(
                repeatInterval = 24,
                repeatIntervalTimeUnit = TimeUnit.HOURS,
                flexTimeInterval = 1,
                flexTimeIntervalUnit = TimeUnit.HOURS
            )
                .setInitialDelay(8, TimeUnit.HOURS) // 延迟8小时后开始首次执行
                .build()

            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                WORK_NAME,
                ExistingPeriodicWorkPolicy.KEEP,
                workRequest
            )
        }

        /**
         * 取消定时签到任务
         */
        fun cancelPeriodicSignIn(context: Context) {
            WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME)
        }
    }
}

/**
 * 扩展函数：检查签到结果是否成功
 */
fun com.example.mtsignin.data.model.SignInResult.isSuccess(): Boolean {
    return this is com.example.mtsignin.data.model.SignInResult.Success
}
