package com.kaze.newage.util

import android.content.Context
import android.net.Uri
import android.os.Environment
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
        return File(base, rel).let { if (it.isDirectory) it else null }
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

    /** 是否有「所有文件访问」权限（自定义目录的 File API 读写前提，Android 11+ 分区存储限制） */
    fun hasAllFilesAccess(context: Context): Boolean =
        Environment.isExternalStorageManager()
}
