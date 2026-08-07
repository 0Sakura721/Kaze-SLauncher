package com.mcserver.launcher.core.server

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.os.IBinder

/**
 * 服务器保活前台服务:服务器运行期间常驻通知栏,
 * 提升进程优先级,防止后台被系统回收导致服务器中断。
 * 启动服务器时 startForegroundService,停止服务器时 stopService。
 */
class ServerKeepAliveService : Service() {

    companion object {
        private const val CHANNEL_ID = "server_keepalive"
        private const val NOTIFICATION_ID = 2
    }

    override fun onCreate() {
        super.onCreate()
        val channel = NotificationChannel(CHANNEL_ID, "服务器运行", NotificationManager.IMPORTANCE_LOW)
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val instanceName = intent?.getStringExtra("instanceName") ?: "Minecraft 服务器"
        val mcVersion = intent?.getStringExtra("mcVersion").orEmpty()

        val contentIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, com.mcserver.launcher.MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notification = Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("$instanceName 正在运行")
            .setContentText(if (mcVersion.isNotBlank()) "MC $mcVersion · 点击返回控制台" else "点击返回控制台")
            .setSmallIcon(com.mcserver.launcher.R.drawable.ic_notification)
            .setContentIntent(contentIntent)
            .setOngoing(true)
            .build()
        startForeground(NOTIFICATION_ID, notification)
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
