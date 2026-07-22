package com.mcserver.launcher

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Intent
import android.os.Build
import com.mcserver.launcher.utils.L
import com.mcserver.launcher.server.ScheduleManager
import com.mcserver.launcher.server.ServerForegroundService
import com.mcserver.launcher.server.ServerManager

class McApplication : Application() {
    companion object {
        private const val TAG = "McApplication"
        const val CHANNEL_SERVER = "server_status"
        const val CHANNEL_NOTIFICATIONS = "server_notifications"
        lateinit var instance: McApplication
            private set

        fun showServerEventNotification(title: String, message: String, isError: Boolean = false) {
            try {
                val intent = Intent(instance, ServerForegroundService::class.java).apply {
                    action = ServerForegroundService.ACTION_SHOW_EVENT
                    putExtra("event_title", title)
                    putExtra("event_message", message)
                    putExtra("event_is_error", isError)
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    instance.startForegroundService(intent)
                } else {
                    instance.startService(intent)
                }
            } catch (e: Exception) {
                L.e(TAG, "showServerEventNotification failed", e)
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
        createNotificationChannels()
        ServerManager.init(this)
        ScheduleManager.startScheduler()
    }

    private fun createNotificationChannels() {
        val manager = getSystemService(NotificationManager::class.java)

        val statusChannel = NotificationChannel(
            CHANNEL_SERVER,
            "服务器状态",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "显示 Minecraft 服务器运行状态"
            setShowBadge(false)
        }

        val notifChannel = NotificationChannel(
            CHANNEL_NOTIFICATIONS,
            "服务器通知",
            NotificationManager.IMPORTANCE_DEFAULT
        ).apply {
            description = "服务器事件通知"
        }

        manager.createNotificationChannel(statusChannel)
        manager.createNotificationChannel(notifChannel)
    }
}
