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

class ServerForegroundService : Service() {

    companion object {
        const val ACTION_STOP = "com.mcserver.launcher.action.STOP_SERVER"
        const val ACTION_SEND_COMMAND = "com.mcserver.launcher.action.SEND_COMMAND"
        const val EXTRA_COMMAND = "com.mcserver.launcher.extra.COMMAND"
        const val NOTIFICATION_ID = 1001
        /** 标记本前台服务是否存活，供命令转发判断（避免后台启动 Service 限制） */
        @Volatile
        var isRunning = false
            private set
    }

    /** 接收来自控制台（应用切到后台时）的命令，转发到服务器命名管道 */
    private val commandReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == ACTION_SEND_COMMAND) {
                val cmd = intent.getStringExtra(EXTRA_COMMAND) ?: return
                try {
                    TermuxManager().writeCommandToPipe(this@ServerForegroundService, cmd)
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
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(commandReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(commandReceiver, filter)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                ServerManager.instance.stopServer()
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }

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

        val notification = NotificationCompat.Builder(this, McApplication.CHANNEL_SERVER)
            .setContentTitle("Minecraft 服务器运行中")
            .setContentText("服务器正在后台运行")
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentIntent(pendingIntent)
            .addAction(android.R.drawable.ic_media_pause, "停止", stopIntent)
            .setOngoing(true)
            .build()

        startForeground(NOTIFICATION_ID, notification)
        return START_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        isRunning = false
        try { unregisterReceiver(commandReceiver) } catch (_: Exception) {}
        ServerManager.instance.stopServer()
    }
}
