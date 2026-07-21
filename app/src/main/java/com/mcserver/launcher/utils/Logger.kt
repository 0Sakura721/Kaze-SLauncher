package com.mcserver.launcher.utils

import android.util.Log
import com.mcserver.launcher.BuildConfig

/**
 * 统一日志封装。
 * Release 构建会自动忽略 VERBOSE/DEBUG 级别，保留 INFO 以上。
 * 任何日志调用都应通过此工具，便于未来集中替换为文件/上报通道。
 */
object L {

    fun v(tag: String, msg: String) {
        if (BuildConfig.DEBUG) Log.v(tag, msg)
    }

    fun d(tag: String, msg: String) {
        if (BuildConfig.DEBUG) Log.d(tag, msg)
    }

    fun i(tag: String, msg: String) {
        Log.i(tag, msg)
    }

    fun w(tag: String, msg: String, tr: Throwable? = null) {
        if (tr != null) Log.w(tag, msg, tr) else Log.w(tag, msg)
    }

    fun e(tag: String, msg: String, tr: Throwable? = null) {
        if (tr != null) Log.e(tag, msg, tr) else Log.e(tag, msg)
    }
}
