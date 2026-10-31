package com.kaze.newage.shell

import android.app.Activity
import android.content.Context
import android.content.Intent

/**
 * 界面外壳切换。
 *
 * 本应用有两套完全独立的界面实现，共用同一份 core / ViewModel / 设置：
 *  - `shell`：手写原生 View 的「岚」（本包）
 *  - `m3`   ：Compose + Material 3（`ui/` 包，原有界面）
 *
 * 切换不靠"条件渲染"，而是**换 Activity**：
 * 两套界面各自持有自己的主题体系（Compose 的 CompositionLocal vs 自绘的全局令牌）
 * 与自己的视图树，塞进同一棵树里只会让两者互相污染。换 Activity 的代价是一次
 * 冷启动级别的重建（几十毫秒），换来的是两层彻底隔离。
 *
 * 用 `FLAG_ACTIVITY_NEW_TASK or CLEAR_TASK`：旧 Activity 连同它持有的
 * ViewModelStore 一起清掉，避免两个 Activity 同时订阅 console 流。
 */
object ShellSwitch {

    const val SHELL = "shell"
    const val M3 = "m3"

    /** 当前应该启动哪个入口 Activity 的类名 */
    fun entryFor(shell: String): String =
        if (shell == M3) "com.kaze.newage.MainActivity" else "com.kaze.newage.shell.ShellActivity"

    /**
     * 切到指定外壳并重启界面。
     *
     * 用类名而非直接引用：这样 `ui` 包与 `shell` 包之间不需要互相 import，
     * 任何一个删掉都不会让另一个编译不过（这也是把两套界面真正解耦的底线）。
     */
    fun restart(context: Context, shell: String) {
        val intent = Intent().apply {
            setClassName(context.packageName, entryFor(shell))
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        }
        context.startActivity(intent)
        if (context is Activity) {
            context.finish()
            context.overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out)
        }
    }
}
