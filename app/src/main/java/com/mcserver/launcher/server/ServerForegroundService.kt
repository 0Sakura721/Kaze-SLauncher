package com.mcserver.launcher.server

import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.mcserver.launcher.MainActivity
import com.mcserver.launcher.McApplication
import com.mcserver.launcher.R
import com.mcserver.launcher.data.ServerState
import kotlinx.coroutines.*

class ServerForegroundService : Service() {

    companion object {
        const val ACTION_STOP = "com.mcserver.launcher.action.STOP_SERVER"
        const val ACTION_SEND_COMMAND = "com.mcserver.launcher.action.SEND_COMMAND"
        const val ACTION_SHOW_EVENT = "com.mcserver.launcher.action.SHOW_EVENT"
        const val EXTRA_COMMAND = "com.mcserver.launcher.extra.COMMAND"
        const val NOTIFICATION_ID = 1001

        /** 标记本前台服务是否存活，供命令转发判断（避免后台启动 Service 限制） */
        @Volatile
        var isRunning = false
            private set
    }

    private var updateJob: Job? = null
    private val serviceScope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    /** 接收来自控制台（应用切到后台时）的命令，转发到服务器命名管道 */
    private val commandReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == ACTION_SEND_COMMAND) {
                val cmd = intent.getStringExtra(EXTRA_COMMAND) ?: return
                try {
                    ServerManager.instance.termuxManager.writeCommandToPipe(this@ServerForegroundService, cmd)
                } catch (_: Exception) {}
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        isRunning = true
        // 仅接收本应用发出的命令广播，无需在 Manifest 声明
        val filter = IntentFilter(ACTION_SEND_COMMAND)
        ContextCompat.registerReceiver(
            this, commandReceiver, filter,
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val initialNotification = NotificationCompat.Builder(this, McApplication.CHANNEL_SERVER)
            .setContentTitle("服务器正在启动...")
            .setContentText("正在准备 Minecraft 服务器")
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setOngoing(true)
            .build()
        startForeground(NOTIFICATION_ID, initialNotification)

        when (intent?.action) {
            ACTION_STOP -> {
                isRunning = false
                ServerManager.instance.stopServer()
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_SHOW_EVENT -> {
                val title = intent.getStringExtra("event_title") ?: return START_STICKY
                val message = intent.getStringExtra("event_message") ?: return START_STICKY
                val isError = intent.getBooleanExtra("event_is_error", false)
                sendEventNotification(title, message, isError)
                return START_STICKY
            }
        }

        // 启动通知更新循环（每 5 秒更新一次通知内容）
        startNotificationUpdates()

        return START_STICKY
    }

    private fun startNotificationUpdates() {
        updateJob?.cancel()
        updateJob = serviceScope.launch {
            while (isActive && isRunning) {
                updateNotification()
                delay(5000)
            }
        }
    }

    private fun updateNotification() {
        try {
            val serverManager = ServerManager.instance
            val status = serverManager.serverStatus.value
            val metrics = PerformanceMonitor.instance.metrics.value

            val pendingIntent = PendingIntent.getActivity(
                this, 0,
                Intent(this, MainActivity::class.java),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )

            val stopIntent = PendingIntent.getService(
                this, 0,
                Intent(this, ServerForegroundService::class.java).apply { action = ACTION_STOP },
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )

            // 构建通知内容：显示内存、玩家、运行时间
            val contentText = buildString {
                append("内存: ${metrics.memoryUsedMB}MB")
                append(" | 玩家: ${metrics.playerCount}")
                if (status.uptimeSeconds > 0) {
                    append(" | 运行: ${formatNotificationUptime(status.uptimeSeconds)}")
                }
                if (metrics.tps > 0 && metrics.tps < 20f) {
                    append(" | TPS: ${"%.1f".format(metrics.tps)}")
                }
            }

            val titleText = when (status.state) {
                ServerState.RUNNING -> "Minecraft 服务器运行中"
                ServerState.STARTING -> "服务器正在启动..."
                ServerState.STOPPING -> "服务器正在停止..."
                else -> "Minecraft 服务器"
            }

            val notification = NotificationCompat.Builder(this, McApplication.CHANNEL_SERVER)
                .setContentTitle(titleText)
                .setContentText(contentText)
                .setSmallIcon(R.drawable.ic_launcher_foreground)
                .setContentIntent(pendingIntent)
                .addAction(android.R.drawable.ic_media_pause, "停止", stopIntent)
                .setOngoing(true)
                .setOnlyAlertOnce(true) // 避免频繁更新发出声音
                .build()

            val manager = getSystemService(NOTIFICATION_SERVICE) as android.app.NotificationManager
            manager.notify(NOTIFICATION_ID, notification)
        } catch (_: Exception) {}
    }

    fun sendEventNotification(title: String, message: String, isError: Boolean = false) {
        try {
            val pendingIntent = PendingIntent.getActivity(
                this, 1,
                Intent(this, MainActivity::class.java),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            val notification = NotificationCompat.Builder(this, McApplication.CHANNEL_NOTIFICATIONS)
                .setContentTitle(title)
                .setContentText(message)
                .setSmallIcon(R.drawable.ic_launcher_foreground)
                .setContentIntent(pendingIntent)
                .setAutoCancel(true)
                .setPriority(if (isError) NotificationCompat.PRIORITY_HIGH else NotificationCompat.PRIORITY_DEFAULT)
                .build()
            val manager = getSystemService(NOTIFICATION_SERVICE) as android.app.NotificationManager
            manager.notify(System.currentTimeMillis().toInt(), notification)
        } catch (_: Exception) {}
    }

    private fun formatNotificationUptime(seconds: Long): String {
        val h = seconds / 3600
        val m = (seconds % 3600) / 60
        return when {
            h > 0 -> "${h}h${m}m"
            m > 0 -> "${m}m"
            else -> "${seconds}s"
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        isRunning = false
        updateJob?.cancel()
        serviceScope.cancel()
        try { unregisterReceiver(commandReceiver) } catch (_: Exception) {}
    }
}
