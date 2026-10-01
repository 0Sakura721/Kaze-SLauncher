package com.kaze.newage.core.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.app.PendingIntent
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import android.os.PowerManager
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
 *     "N 个实例运行中" + 实例名列表（单一通知，避免通知栏堆积）；
 *  5. 服务存活期持有**部分唤醒锁**：前台服务只保进程不死，不保证 CPU 常醒 ——
 *     熄屏后 proot 里的 java 子进程可能被内核挂起，服务端表现为"没人动它也卡住"。
 *     服务的生命周期 = 「有实例在跑」，acquire/release 挂在 onCreate/onDestroy 上
 *     天然与之一一对应，不需要在管理器里做引用计数。
 */
class ServerGuardService : Service() {

    private var wakeLock: PowerManager.WakeLock? = null

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
        // ③ 部分唤醒锁（manifest 的 WAKE_LOCK 权限即为此声明）
        acquireWakeLock()
    }

    override fun onDestroy() {
        try {
            wakeLock?.takeIf { it.isHeld }?.release()
        } catch (_: Exception) { }
        wakeLock = null
        super.onDestroy()
    }

    private fun acquireWakeLock() {
        try {
            val pm = getSystemService(PowerManager::class.java) ?: return
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "KazeSLauncher:server-guard")?.apply {
                setReferenceCounted(false)
                // 不设超时：服务端就是要小时级运行，超时到点 CPU 睡了服务端会无声卡死。
                // 释放路径只有 onDestroy 与进程死亡（内核随进程清理），两条路都覆盖。
                acquire()
            }
        } catch (e: Exception) {
            // 拿不到锁不致命（服务端仍可运行），但熄屏后可能被挂起 —— 留线索
            android.util.Log.w(TAG, "唤醒锁获取失败: ${e.message}")
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // 每实例信息：title=实例名，text=状态行；无则保持默认
        intent?.let {
            updateNotification(
                it.getStringExtra(EXTRA_TITLE) ?: "Kaze SLauncher",
                it.getStringExtra(EXTRA_TEXT) ?: "服务端运行中",
            )
        }
        // START_NOT_STICKY：进程被系统回收后不要自动重建。
        // START_STICKY 会用 null Intent 重建服务，于是通知又挂出"服务端运行中"——
        // 而此时进程已死、服务端早就不在了，且没有任何代码会再撤下这条假通知。
        return START_NOT_STICKY
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
            // 点击回到应用：原来没有 contentIntent，点常驻通知毫无反应，用户回不到界面
            .setContentIntent(
                PendingIntent.getActivity(
                    this,
                    0,
                    Intent(this, com.kaze.newage.MainActivity::class.java).apply {
                        flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
                    },
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                )
            )
            .build()

    companion object {
        private const val TAG = "KazeSLauncher"
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
            } catch (e: Exception) {
                // 不再完全静默：Android 12+ 在后台路径（如自动重启）启动 FGS 会抛
                // ForegroundServiceStartNotAllowedException —— 服务端将在**无守护**状态下裸跑，
                // 旧实现连 logcat 都没有，排查"熄屏后服务端卡死"时没有任何线索。
                val msg = "前台守护服务启动失败（服务端仍在运行，但熄屏后可能被系统挂起）：" +
                    "${e.javaClass.simpleName}: ${e.message}"
                android.util.Log.w(TAG, msg)
                // 同步进诊断日志：用户看得见的地方（设置 → 诊断日志），崩溃前那一段也留得住
                runCatching {
                    (context.applicationContext as? com.kaze.newage.NewAgeApp)
                        ?.container?.appLog?.appendRaw("[FGS] $msg")
                }
            }
        }

        fun stop(context: Context) {
            try {
                context.stopService(Intent(context, ServerGuardService::class.java))
            } catch (e: Exception) {
                android.util.Log.w(TAG, "停止守护服务失败: ${e.message}")
            }
        }
    }
}
