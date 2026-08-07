package com.mcserver.launcher.core.server

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder

/**
 * 服务器保活前台服务:App 启动即常驻(通知栏常驻,防止后台被杀),
 * 服务器运行时更新通知为实例信息。
 *
 * 设计要点:
 * - 服务在 App 启动(init)时通过 startForegroundService 拉起并常驻,
 *   启动服务器时不再现场创建前台服务——避免 vivo 等 ROM 服务创建延迟
 *   导致的 ForegroundServiceDidNotStartInTimeException 闪退
 * - onCreate 第一行即 startForeground(5 秒窗口内),onStartCommand 只更新内容
 */
class ServerKeepAliveService : Service() {

    companion object {
        private const val CHANNEL_ID = "server_keepalive"
        private const val NOTIFICATION_ID = 2
        private const val EXTRA_NAME = "instanceName"
        private const val EXTRA_VERSION = "mcVersion"

        /** App 启动时拉起保活服务(常驻) */
        fun start(context: Context) {
            try {
                androidx.core.content.ContextCompat.startForegroundService(
                    context,
                    Intent(context, ServerKeepAliveService::class.java)
                )
            } catch (_: Exception) {
                try { context.startService(Intent(context, ServerKeepAliveService::class.java)) } catch (_: Exception) { }
            }
        }

        /** 服务器启动/停止时更新通知内容(不新建服务) */
        fun update(context: Context, instanceName: String?, mcVersion: String = "") {
            try {
                val intent = Intent(context, ServerKeepAliveService::class.java)
                    .putExtra(EXTRA_NAME, instanceName ?: "")
                    .putExtra(EXTRA_VERSION, mcVersion)
                context.startService(intent)
            } catch (_: Exception) { }
        }
    }

    private lateinit var notificationManager: NotificationManager

    override fun onCreate() {
        super.onCreate()
        notificationManager = getSystemService(NotificationManager::class.java)
        notificationManager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "服务器运行", NotificationManager.IMPORTANCE_LOW)
        )
        // 立即进入前台(5 秒超时窗口内),内容稍后更新
        startForeground(NOTIFICATION_ID, buildNotification("Kaze SLauncher", "服务端管理器运行中"))
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val name = intent?.getStringExtra(EXTRA_NAME).orEmpty()
        val version = intent?.getStringExtra(EXTRA_VERSION).orEmpty()
        val (title, text) = if (name.isNotEmpty()) {
            "$name 正在运行" to (if (version.isNotBlank()) "MC $version · 点击返回控制台" else "点击返回控制台")
        } else {
            "Kaze SLauncher" to "服务端管理器运行中"
        }
        try {
            notificationManager.notify(NOTIFICATION_ID, buildNotification(title, text))
        } catch (_: Exception) { }
        return START_STICKY
    }

    private fun buildNotification(title: String, text: String): Notification {
        val contentIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, com.mcserver.launcher.MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return Notification.Builder(this, CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(text)
            .setSmallIcon(com.mcserver.launcher.R.drawable.ic_notification)
            .setContentIntent(contentIntent)
            .setOngoing(true)
            .build()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
