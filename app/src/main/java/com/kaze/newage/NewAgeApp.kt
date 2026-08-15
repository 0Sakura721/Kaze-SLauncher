package com.kaze.newage

import android.app.Application
import android.content.Context
import com.kaze.newage.core.console.ConsoleStream
import com.kaze.newage.core.env.ProotEnvironment
import com.kaze.newage.core.java.RootfsJavaManager
import com.kaze.newage.core.server.DefaultServerManager
import com.kaze.newage.data.InstanceStore
import com.kaze.newage.data.prefs.SettingsPrefs

/** 应用入口：初始化全局单例 */
class NewAgeApp : Application() {

    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
    }
}

/** 依赖容器（简单手动 DI，避免引入框架） */
class AppContainer(context: Context) {
    private val appContext = context.applicationContext

    val console: ConsoleStream = ConsoleStream()

    val uiPrefs: SettingsPrefs = SettingsPrefs(appContext)

    // Linux 环境目录：默认内部存储；设置里可切换外部存储（空间不足场景，切换后需重新部署）
    val env: ProotEnvironment = ProotEnvironment(appContext) {
        val base = if (uiPrefs.envExternal.value) {
            appContext.getExternalFilesDir(null) ?: appContext.filesDir
        } else {
            appContext.filesDir
        }
        java.io.File(base, "linux")
    }

    val javaManager: RootfsJavaManager = RootfsJavaManager(env)

    val serverManager: DefaultServerManager = DefaultServerManager(env, javaManager, console)

    val instanceStore: InstanceStore = InstanceStore(appContext)
}

/** 便捷获取容器 */
val Context.container: AppContainer
    get() = (applicationContext as NewAgeApp).container
