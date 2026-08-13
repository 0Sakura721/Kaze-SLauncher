package com.mcserver.launcher

import android.app.Application
import com.mcserver.launcher.data.AppPaths
import com.mcserver.launcher.data.SettingsStore
import com.mcserver.launcher.util.KLog
import java.io.File

class KazeApp : Application() {

    override fun onCreate() {
        super.onCreate()
        AppPaths.init(this)
        KLog.init(File(AppPaths.logsDir, "kaze.log"))
        SettingsStore.init(this)
        KLog.i("Kaze SLauncher 2.0 启动")
    }
}