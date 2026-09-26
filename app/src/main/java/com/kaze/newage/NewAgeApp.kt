package com.kaze.newage

import android.app.Application
import android.content.Context
import com.kaze.newage.core.console.ConsoleStream
import com.kaze.newage.core.env.ProotEnvironment
import com.kaze.newage.core.java.RootfsJavaManager
import com.kaze.newage.core.log.AppLogStore
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
    val appContext: Context = context.applicationContext

    /**
     * **应用级**协程作用域：给"不能因为用户退出界面就中断"的长任务用。
     *
     * `viewModelScope` 绑在 Activity 的 ViewModelStore 上——按返回键退出应用时会被 clear，
     * 于是环境部署 / Java 安装 / 核心下载 / 服务端启动都会在挂起点被静默取消，
     * 而应用文案承诺的是"前台服务守护、后台不被打断"（按 Home 键确实成立，按返回键却不成立）。
     * 这些任务的成果都落在磁盘上，退出界面后继续跑完才是正确行为。
     */
    val appScope: kotlinx.coroutines.CoroutineScope =
        kotlinx.coroutines.CoroutineScope(
            kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.IO
        )

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

    val serverManager: DefaultServerManager = DefaultServerManager(env, javaManager, console, appContext)

    val instanceStore: InstanceStore = InstanceStore(appContext, uiPrefs)

    /**
     * 应用自身的日志采集（「设置 → 诊断日志」查看）。
     *
     * 在这里 start 而不是在界面里：日志的价值恰恰在于**崩溃前那一段**，
     * 挂在界面上会因为切页/退出而中断，事后就查不到了。
     */
    val appLog: AppLogStore = AppLogStore(appContext).also { it.start(appScope) }
}

/** 便捷获取容器 */
val Context.container: AppContainer
    get() = (applicationContext as NewAgeApp).container
