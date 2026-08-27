package com.kaze.newage.core.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.kaze.newage.R

/**
 * 服务端守护前台服务（2026-08-16 重新加入，正确写法）。
 *
 * 历史教训：此前版本崩溃（RemoteServiceException$ForegroundServiceDidNotStartInTimeException）——
 * 根因是 `startForegroundService` 后 5 秒内未调用 `startForeground`（或类型权限缺失导致
 * `startForeground` 抛 SecurityException 后再崩）。
 *
 * 本次正确姿势（Android 16 实测要求）：
 *  1. `onCreate()` 里**第一时间**（创建通知渠道前只做轻量事）调用 `ServiceCompat.startForeground`
 *     并显式传 `FOREGROUND_SERVICE_TYPE_SPECIAL_USE`；
 *  2. manifest 声明 `foregroundServiceType="specialUse"` +
 *     `FOREGROUND_SERVICE_SPECIAL_USE` 权限 +
 *     `PROPERTY_SPECIAL_USE_FGS_SUBTYPE` 属性（缺一即 SecurityException）；
 *  3. 通知渠道 IMPORTANCE_LOW（静默、常驻）；
 *  4. 单实例显示名称/端口，多开时由 DefaultServerManager.updateGuard 聚合为
 *     "N 个实例运行中" + 实例名列表（单一通知，避免通知栏堆积）。
 */
class ServerGuardService : Service() {

    override fun onCreate() {
        super.onCreate()
        // ① 通知渠道
        val nm = getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(CHANNEL_ID) == null) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "服务端守护", NotificationManager.IMPORTANCE_LOW)
            )
        }
        // ② 立即前台化（必须在 5 秒窗口内，且类型权限已由 manifest 提供）
        ServiceCompat.startForeground(
            this,
            NOTIFICATION_ID,
            buildNotification("Kaze SLauncher", "服务端运行中"),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE,
        )
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // 每实例信息：title=实例名，text=状态行；无则保持默认
        intent?.let {
            updateNotification(
                it.getStringExtra(EXTRA_TITLE) ?: "Kaze SLauncher",
                it.getStringExtra(EXTRA_TEXT) ?: "服务端运行中",
            )
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun updateNotification(title: String, text: String) {
        getSystemService(NotificationManager::class.java)
            .notify(NOTIFICATION_ID, buildNotification(title, text))
    }

    private fun buildNotification(title: String, text: String): Notification =
        NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(text)
            .setOngoing(true)
            .setShowWhen(false)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()

    companion object {
        private const val CHANNEL_ID = "server_guard"
        private const val NOTIFICATION_ID = 1001
        private const val EXTRA_TITLE = "title"
        private const val EXTRA_TEXT = "text"

        fun start(context: Context, title: String, text: String) {
            try {
                val intent = Intent(context, ServerGuardService::class.java)
                    .putExtra(EXTRA_TITLE, title)
                    .putExtra(EXTRA_TEXT, text)
                androidx.core.content.ContextCompat.startForegroundService(context, intent)
            } catch (_: Exception) { }
        }

        fun stop(context: Context) {
            try {
                context.stopService(Intent(context, ServerGuardService::class.java))
            } catch (_: Exception) { }
        }
    }
}
