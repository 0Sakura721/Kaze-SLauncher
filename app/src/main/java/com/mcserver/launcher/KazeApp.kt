package com.mcserver.launcher

import android.app.Application
import com.mcserver.launcher.core.env.EnvManager
import com.mcserver.launcher.core.server.InstanceStore
import com.mcserver.launcher.core.server.JreInstaller
import com.mcserver.launcher.core.server.ServerManager
import com.mcserver.launcher.data.SettingsStore

class KazeApp : Application() {
    override fun onCreate() {
        super.onCreate()
        SettingsStore.init(this)
        EnvManager.init(this)
        InstanceStore.init(this)
        JreInstaller.init(this)
        ServerManager.init(this)
    }
}
