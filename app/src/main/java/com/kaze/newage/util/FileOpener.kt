package com.kaze.newage.util

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import java.io.File

/**
 * 把文件交给**系统或外部应用**打开（ACTION_VIEW），例如用第三方文本编辑器改 server.properties。
 *
 * 真机需求："在实例给个直接打开 server.properties 的通道，不使用 kazeslauncher 打开，
 * 依赖系统或外部应用，供用户自由编辑。"
 *
 * 两个必须这么做的地方：
 *  1. targetSdk ≥ 24 传 `file://` 会抛 **FileUriExposedException**，必须走 FileProvider 的
 *     `content://`（主页「实例目录」按钮当年就是栽在这里，异常被吞掉后按钮"点了没反应"）。
 *  2. 用户要能**保存回去**，所以要显式授写权限 `FLAG_GRANT_WRITE_URI_PERMISSION`；
 *     只读授权的话，编辑器打开后会发现存不进去。
 *
 * FileProvider 只能暴露 `res/xml/file_paths.xml` 里配置过的根目录。实例根默认是
 * `/sdcard/KazeS`（已加进 roots），但**用户自定义的实例目录可能在任何位置** ——
 * 那时取 URI 会抛 IllegalArgumentException。这种情况下返回 false，
 * 由调用方把真实路径告诉用户（不要静默失败，也不要假装打开了）。
 */
fun openFileExternally(context: Context, file: File, mime: String = "text/plain"): Boolean {
    if (!file.isFile) return false
    val uri = runCatching {
        FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
    }.getOrNull() ?: return false
    val view = Intent(Intent.ACTION_VIEW).apply {
        setDataAndType(uri, mime)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
    return runCatching {
        val chooser = Intent.createChooser(view, "用其他应用打开")
        chooser.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(chooser)
        true
    }.getOrElse { false }
}
