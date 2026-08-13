package com.mcserver.launcher.core.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.mcserver.launcher.MainActivity
import com.mcserver.launcher.R
import com.mcserver.launcher.core.engine.ServerEngine
import com.mcserver.launcher.core.instance.InstanceStore
import com.mcserver.launcher.data.ServerState
import com.mcserver.launcher.util.KLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * 前台服务：托管服务端进程，常驻通知（状态 + 停止按钮）。
 * 不随 Activity 销毁而停止；引擎转为 Idle/Crashed 后自动 stopSelf。
 */
class ServerService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var observerJob: Job? = null

    companion object {
        const val CHANNEL_ID = "kaze_server"
        const val NOTIFICATION_ID = 1001
        const val ACTION_STOP = "com.mcserver.launcher.STOP_SERVER"
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createChannel()
        observeEngine()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                ServerEngine.stop()
                return START_NOT_STICKY
            }
        }
        startForeground(NOTIFICATION_ID, buildNotification())
        return START_NOT_STICKY
    }

    private fun observeEngine() {
        observerJob?.cancel()
        observerJob = scope.launch {
            ServerEngine.state.collect { st ->
                updateNotification()
                if (st is ServerState.Idle || st is ServerState.Crashed) {
                    KLog.i("引擎空闲，前台服务退出")
                    stopSelf()
                }
            }
        }
    }

    private fun updateNotification() {
        val nm = getSystemService(NotificationManager::class.java)
        try {
            nm.notify(NOTIFICATION_ID, buildNotification())
        } catch (_: Exception) {
        }
    }

    private fun buildNotification(): Notification {
        val st = ServerEngine.state.value
        val stats = ServerEngine.stats.value
        val instName = ServerEngine.runningInstanceId()?.let { InstanceStore.find(it)?.name } ?: "服务器"

        val statusText = when (st) {
            is ServerState.Running -> "运行中 · ${stats.playerCount} 人在线 · TPS ${if (stats.tps > 0) "%.1f".format(stats.tps) else "--"}"
            is ServerState.Starting -> "启动中…"
            is ServerState.Stopping -> "正在停止…"
            is ServerState.Crashed -> "已崩溃 (code ${st.exitCode})"
            else -> "已停止"
        }

        val contentIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val stopIntent = PendingIntent.getService(
            this, 1,
            Intent(this, ServerService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(instName)
            .setContentText(statusText)
            .setContentIntent(contentIntent)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .addAction(0, "停止", stopIntent)
            .build()
    }

    private fun createChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID, "服务器运行状态",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Minecraft 服务端运行状态与快捷控制"
            setShowBadge(false)
        }
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    override fun onDestroy() {
        observerJob?.cancel()
        scope.cancel()
        super.onDestroy()
    }
}