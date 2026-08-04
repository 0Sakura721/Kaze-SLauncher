package com.mcserver.launcher.core.download

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.os.IBinder

/** 下载前台服务:任务进行时保持进程存活 */
class DownloadService : Service() {

    override fun onCreate() {
        super.onCreate()
        val channel = NotificationChannel(CHANNEL_ID, "下载任务", NotificationManager.IMPORTANCE_LOW)
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val active = DownloadCenter.activeCount
        if (active > 0) {
            val notification = Notification.Builder(this, CHANNEL_ID)
                .setContentTitle("Kaze SLauncher")
                .setContentText("有 $active 个下载任务进行中")
                .setSmallIcon(android.R.drawable.stat_sys_download)
                .setOngoing(true)
                .build()
            startForeground(1, notification)
        } else {
            stopSelf()
        }
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        private const val CHANNEL_ID = "download"
    }
}
