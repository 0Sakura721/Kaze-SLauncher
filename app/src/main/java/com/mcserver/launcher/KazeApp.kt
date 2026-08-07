package com.mcserver.launcher

import android.app.Application
import com.mcserver.launcher.core.env.EnvManager
import com.mcserver.launcher.core.server.InstanceStore
import com.mcserver.launcher.core.server.JreInstaller
import com.mcserver.launcher.core.server.ServerManager
import com.mcserver.launcher.data.SettingsStore

class KazeApp : Application() {
    companion object {
        lateinit var instance: KazeApp
            private set
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
        SettingsStore.init(this)
        EnvManager.init(this)
        InstanceStore.init(this)
        JreInstaller.init(this)
        ServerManager.init(this)
        // 保活前台服务常驻(App 启动即拉起,避免启动服务器时现场创建导致
        // vivo 等 ROM 服务延迟而闪退 ForegroundServiceDidNotStartInTimeException)
        com.mcserver.launcher.core.server.ServerKeepAliveService.start(this)
        // 孤儿服务器接管:App 曾被系统杀死而服务器仍在运行时,恢复监控与状态
        try {
            val recovered = ServerManager.recoverOrphans()
            if (recovered > 0) {
                android.util.Log.w("KazeSLauncher", "已接管 $recovered 个孤儿服务器进程")
            }
        } catch (_: Exception) { }
    }
}
