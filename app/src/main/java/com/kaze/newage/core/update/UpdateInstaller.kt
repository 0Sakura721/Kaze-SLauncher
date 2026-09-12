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
 * 多镜像测速择优下载到 cacheDir/updates，校验 APK 魔数**与发布方给出的 SHA-256** 后
 * 经 FileProvider 调起系统安装器。
 */
object UpdateInstaller {

    private val MAGIC = byteArrayOf(0x50, 0x4B, 0x03, 0x04) // PK\x03\x04

    /**
     * 下载到 cacheDir/updates/<tag>.apk（断点续传；已存在且校验通过直接复用）。
     * @return 下载好的 APK 文件；失败（取消/全部源不可用/校验不通过）返回 null
     */
    suspend fun download(
        context: Context,
        info: UpdateChecker.ReleaseInfo,
        onProgress: (doneMb: Long, totalMb: Long, percent: Float) -> Unit = { _, _, _ -> },
        shouldCancel: () -> Boolean = { false },
    ): File? = withContext(Dispatchers.IO) {
        val dir = File(context.cacheDir, "updates").apply { mkdirs() }
        val file = File(dir, "kaze-slauncher-${info.tag}.apk")
        // 完成哨兵：仅当一次下载走完才写。半成品 APK 往往已 >1MB 且以 PK 开头——
        // 若只看这两个条件复用，取消一次后每次重试都被短路当作完整包，
        // 装出"解析包失败"，且断点续传永远没有机会补完剩余字节
        val doneMarker = File(dir, "kaze-slauncher-${info.tag}.apk.done")
        if (file.exists() && doneMarker.exists() && isUsableApk(file, info)) {
            return@withContext file
        }
        val used = Downloader.downloadFromSources(
            urls = UpdateChecker.sources(info.apkUrl),
            dest = file,
            onProgress = { done, total ->
                onProgress(done / 1024 / 1024, total / 1024 / 1024, if (total > 0) done.toFloat() / total else 0f)
            },
            shouldCancel = shouldCancel,
            validate = { f -> isUsableApk(f, info) },
        )
        if (used == null) null
        else {
            runCatching { doneMarker.writeText(info.tag) }
            file
        }
    }

    /** 体积 + 魔数 + 哈希，三者都过才算可用 */
    private fun isUsableApk(f: File, info: UpdateChecker.ReleaseInfo): Boolean =
        f.length() > 1_000_000 && isApk(f) && matchesDigest(f, info)

    /**
     * 校验发布方给出的 SHA-256。
     *
     * APK 实际是从多个**第三方加速镜像**下载的（[UpdateChecker.sources]），任一镜像被控制
     * 就能返回一个「魔数合法、体积足够」的篡改包，而应用会引导用户安装它。哈希来自
     * `api.github.com`（直连、不经过镜像），是这条链路上唯一的完整性依据。
     *
     * 发布方没有给出 digest 时返回 true：那只可能是 GitHub 侧没提供（响应同样来自
     * api.github.com，镜像影响不到它），此时退回"魔数 + 体积"的原有校验，
     * 不因缺字段而让更新彻底不可用。
     */
    private fun matchesDigest(f: File, info: UpdateChecker.ReleaseInfo): Boolean {
        val expect = info.apkSha256 ?: return true
        return Downloader.sha256Of(f)?.equals(expect, ignoreCase = true) == true
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
