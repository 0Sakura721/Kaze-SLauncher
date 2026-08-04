package com.mcserver.launcher.util

import android.util.Log

/** 统一日志 */
object Logger {
    private const val TAG = "KazeSLauncher"

    fun d(msg: String) = Log.d(TAG, msg)
    fun i(msg: String) = Log.i(TAG, msg)
    fun w(msg: String, tr: Throwable? = null) = if (tr != null) Log.w(TAG, msg, tr) else Log.w(TAG, msg)
    fun e(msg: String, tr: Throwable? = null) = if (tr != null) Log.e(TAG, msg, tr) else Log.e(TAG, msg)
}
