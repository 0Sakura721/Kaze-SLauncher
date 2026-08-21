package com.kaze.newage.core.update

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import com.kaze.newage.util.Downloader
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 更新 APK 下载与安装（设置页手动检查与启动自动检查共用）：
 * 多镜像测速择优下载到 cacheDir/updates，校验 APK 魔数后经 FileProvider 调起系统安装器。
 */
object UpdateInstaller {

    private val MAGIC = byteArrayOf(0x50, 0x4B, 0x03, 0x04) // PK\x03\x04

    /**
     * 下载到 cacheDir/updates/<tag>.apk（断点续传；已存在且魔数合法直接复用）。
     * @return 下载好的 APK 文件；失败（取消/全部源不可用）返回 null
     */
    suspend fun download(
        context: Context,
        info: UpdateChecker.ReleaseInfo,
        onProgress: (doneMb: Long, totalMb: Long, percent: Float) -> Unit = { _, _, _ -> },
        shouldCancel: () -> Boolean = { false },
    ): File? = withContext(Dispatchers.IO) {
        val dir = File(context.cacheDir, "updates").apply { mkdirs() }
        val file = File(dir, "kaze-slauncher-${info.tag}.apk")
        if (file.exists() && file.length() > 1_000_000 && isApk(file)) return@withContext file
        val used = Downloader.downloadFromSources(
            urls = UpdateChecker.sources(info.apkUrl),
            dest = file,
            onProgress = { done, total ->
                onProgress(done / 1024 / 1024, total / 1024 / 1024, if (total > 0) done.toFloat() / total else 0f)
            },
            shouldCancel = shouldCancel,
            validate = { f -> f.length() > 1_000_000 && isApk(f) },
        )
        if (used == null) null else file
    }

    /** 校验 APK 魔数 PK\x03\x04（防镜像返回 HTML 错误页） */
    private fun isApk(f: File): Boolean = try {
        f.inputStream().use { ins ->
            val head = ByteArray(4)
            ins.read(head) == 4 && head.contentEquals(MAGIC)
        }
    } catch (_: Exception) { false }

    /** 调起系统安装器（需 REQUEST_INSTALL_PACKAGES，manifest 已声明） */
    fun install(context: Context, file: File): Boolean = try {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
        true
    } catch (_: Exception) { false }
}
