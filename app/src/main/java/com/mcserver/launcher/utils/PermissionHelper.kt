package com.mcserver.launcher.utils

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 运行时权限助手，集中处理 Android 13+ 通知权限请求等。
 * 静态工具 + 状态订阅，确保 UI 能实时反映权限状态。
 */
object PermissionHelper {

    private val _notificationGranted = MutableStateFlow(false)
    val notificationGranted: StateFlow<Boolean> = _notificationGranted.asStateFlow()

    /**
     * 在 Activity onCreate 后调用一次，初始化通知权限状态并按需注册回调。
     */
    fun bind(activity: ComponentActivity) {
        refreshNotificationStatus(activity)
    }

    /** 刷新通知权限状态 */
    fun refreshNotificationStatus(context: Context) {
        _notificationGranted.value = hasNotificationPermission(context)
    }

    /**
     * 注册通知权限请求器。需要在 Activity 中持有此 launcher 的引用，
     * 典型用法：在 onCreate 中调用并持有返回值。
     */
    fun createNotificationLauncher(activity: ComponentActivity) =
        activity.registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            _notificationGranted.value = granted
        }

    /** 检查通知权限 */
    fun hasNotificationPermission(context: Context): Boolean {
        // Android 13 以下默认拥有通知权限
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return true
        return ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.POST_NOTIFICATIONS
        ) == PackageManager.PERMISSION_GRANTED
    }

    /**
     * 当前是否需要请求通知权限（Android 13+ 且尚未授权时）。
     */
    fun shouldRequestNotificationPermission(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return false
        return !hasNotificationPermission(context)
    }
}
