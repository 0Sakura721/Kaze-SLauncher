package com.kaze.newage.util

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import androidx.core.content.ContextCompat
import java.io.File

/**
 * SAF 目录选择 → 真实文件路径转换。
 *
 * 应用运行服务端需要「真实 File 路径」（proot -b 绑定 + java -jar），
 * 因此 SAF 返回的 content:// tree URI 必须解析回 /storage/... 路径。
 * 支持系统 ExternalStorageProvider（主存储 primary 与 SD 卡卷）；其它 provider 返回 null。
 */
object StorageDirUtil {

    /**
     * 解析 OpenDocumentTree 返回的 tree URI 为真实目录。
     * @return 目录 File；无法解析（非 externalstorage provider）返回 null
     */
    fun treeUriToFile(uri: Uri?): File? {
        if (uri == null) return null
        if (uri.authority != "com.android.externalstorage.documents") return null
        // path 形如 /tree/primary%3AMCServer 或 /tree/1A2B-3C4D%3AMCServer
        val segments = uri.pathSegments
        if (segments.size < 2 || segments[0] != "tree") return null
        val docId = Uri.decode(segments[1])
        val colon = docId.indexOf(':')
        if (colon < 0) return null
        val volume = docId.substring(0, colon)
        val rel = docId.substring(colon + 1)
        val base = when (volume.lowercase()) {
            "primary", "emulated" -> Environment.getExternalStorageDirectory().absolutePath
            else -> "/storage/$volume"
        }
        // rel 为空 = 用户选了卷根（主存储 / SD 卡根目录）：拒绝。
        // 接受的话实例扫描会把存储根下任何直接含 .jar 的顶层目录登记成"实例"
        // （Download、Documents、其它应用的数据目录都会中招），而且每次启动都要浅扫一遍存储根。
        if (rel.isBlank()) return null
        val dir = File(base, rel)
        // 系统目录同样拒绝：即便写得进去，也不该把实例放在这里
        val lower = dir.absolutePath.lowercase()
        if (lower.contains("/android/data") || lower.contains("/android/obb")) return null
        return dir.takeIf { it.isDirectory }
    }

    /** 目录可写探测（创建并删除探针文件） */
    fun isWritableDir(dir: File): Boolean {
        val probe = File(dir, ".kaze_probe_${System.currentTimeMillis()}")
        return try {
            probe.writeText("ok")
            probe.delete()
        } catch (_: Exception) {
            false
        }
    }

    /** 用户可读的目录描述（对冗长路径折叠中间段） */
    fun displayPath(path: String): String =
        if (path.length <= 48) path
        else path.take(20) + "…" + path.takeLast(24)

    /**
     * 是否有权把实例目录放到任意位置。
     *
     * `Environment.isExternalStorageManager()` 是 **API 30 才加入**的，而本应用 minSdk 27：
     * 不加版本判断会在 Android 8.0–10 上抛 `NoSuchMethodError`（属于 Error，不会被
     * `catch (Exception)` 接住）→ 点「选择目录」直接崩。这些系统没有「所有文件访问」这个概念，
     * 走的是传统的 `WRITE_EXTERNAL_STORAGE` 运行时权限（manifest 已按 maxSdkVersion=28 声明）。
     */
    fun hasAllFilesAccess(context: Context): Boolean = when {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.R -> Environment.isExternalStorageManager()
        else -> ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.WRITE_EXTERNAL_STORAGE,
        ) == PackageManager.PERMISSION_GRANTED
    }

    /** Android 11 以下需要单独申请传统存储权限（11+ 用「所有文件访问」开关） */
    fun needsLegacyStoragePermission(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.R

    /**
     * 在系统文件管理器里打开目录，成功返回 true。
     *
     * **必须**用 FileProvider 的 `content://`：targetSdk ≥ 24 时把 `file://` 交给别的应用
     * 会抛 `FileUriExposedException`（Android 7.0 行为变更）。此前两处「打开实例目录」都用
     * `Uri.fromFile` 且把异常吞了，于是按钮在任何现代设备上都是"点了没反应"。
     *
     * 实例目录被用户改成自定义位置时（SAF 选的目录不在 FileProvider 允许的根之内）无法
     * 生成 URI，返回 false，由调用方提示实际路径。
     */
    fun openInFileManager(context: Context, dir: File): Boolean = runCatching {
        val uri = androidx.core.content.FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            dir,
        )
        val intent = android.content.Intent(android.content.Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "resource/folder")
            addFlags(
                android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION or
                    android.content.Intent.FLAG_ACTIVITY_NEW_TASK,
            )
        }
        context.startActivity(intent)
        true
    }.getOrDefault(false)
}
