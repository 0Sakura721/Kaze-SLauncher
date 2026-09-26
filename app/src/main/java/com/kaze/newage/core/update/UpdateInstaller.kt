package com.kaze.newage.core.update

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import com.kaze.newage.util.Downloader
import java.io.File
import org.json.JSONObject
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
        // tag 来自远端（tag_name），参与拼文件名前先净化
        val safeTag = info.tag.replace(Regex("[^A-Za-z0-9._-]"), "_")
        val file = File(dir, "kaze-slauncher-$safeTag.apk")
        // 完成哨兵：仅当一次下载走完才写。半成品 APK 往往已 >1MB 且以 PK 开头——
        // 若只看这两个条件复用，取消一次后每次重试都被短路当作完整包，
        // 装出"解析包失败"，且断点续传永远没有机会补完剩余字节
        val doneMarker = File(dir, "kaze-slauncher-$safeTag.apk.done")
        if (file.exists() && doneMarker.exists() && isUsableApk(context, file, info)) {
            return@withContext file
        }
        // 先试增量补丁：能省 90%+ 流量。任何一步不成立就静默回退整包 ——
        // 补丁是"加速手段"而不是"必经路径"，它失败绝不能让用户更新不了。
        tryPatchUpdate(context, info, onProgress, shouldCancel)?.let { return@withContext it }
        val used = Downloader.downloadFromSources(
            urls = UpdateChecker.sources(info.apkUrl),
            dest = file,
            onProgress = { done, total ->
                onProgress(done / 1024 / 1024, total / 1024 / 1024, if (total > 0) done.toFloat() / total else 0f)
            },
            shouldCancel = shouldCancel,
            validate = { f -> isUsableApk(context, f, info) },
        )
        if (used == null) null
        else {
            runCatching { doneMarker.writeText(info.tag) }
            file
        }
    }

    /**
     * 尝试走**增量补丁**；任何一步不成立就返回 null（调用方回退整包）。
     *
     * 判定"哪一份补丁对我有效"靠的是 `baseSha256 == 本机已装 APK 的 sha256`，
     * 而不是文件名里的架构 —— 用户装的若是别处来的同签名包、或版本对不上，
     * 这里自然匹配不到，安全回退整包。
     *
     * 为什么用 [android.content.pm.ApplicationInfo.sourceDir]：那是**本机已安装的 APK**，
     * 补丁就是拿它当基线拼的；不需要额外下载旧包。
     */
    private suspend fun tryPatchUpdate(
        context: Context,
        info: UpdateChecker.ReleaseInfo,
        onProgress: (Long, Long, Float) -> Unit,
        shouldCancel: () -> Boolean,
    ): File? {
        if (info.patchAssets.isEmpty()) return null
        val installedApk = runCatching { File(context.applicationInfo.sourceDir) }.getOrNull() ?: return null
        if (!installedApk.isFile) return null
        val baseSha = runCatching { ApkPatchApplier.sha256Of(installedApk) }.getOrNull() ?: return null
        val dir = File(context.cacheDir, "updates").apply { mkdirs() }

        for (asset in info.patchAssets) {
            if (shouldCancel()) return null
            // ① 元数据只有 ~10KB：先拿它判断"这份补丁的基线是不是本机这个包"
            val metaText = runCatching {
                Downloader.downloadText(asset.jsonUrl, timeoutMs = 20_000)
            }.getOrNull() ?: continue
            val meta = runCatching { JSONObject(metaText) }.getOrNull() ?: continue
            val base = meta.optString("baseSha256", "").trim().lowercase()
            if (base.length != 64 || !base.equals(baseSha, ignoreCase = true)) continue

            // ② 下补丁（走与整包同一条多镜像 + 断点续传链路）
            val patchZip = File(dir, asset.name.removeSuffix(".json") + ".zip")
            // downloadFromSources 返回的是"最终用了哪个源"（字符串），文件在 dest 上
            val usedSource = Downloader.downloadFromSources(
                urls = UpdateChecker.sources(asset.zipUrl),
                dest = patchZip,
                onProgress = { done, total ->
                    onProgress(done, total, if (total > 0) done.toFloat() / total else 0f)
                },
                shouldCancel = shouldCancel,
                validate = { f -> f.length() > 1024 },
            ) ?: continue
            if (usedSource.isBlank() || !patchZip.isFile) continue

            // ③ 拼装：apply 内部会比对 targetSha256（补丁被篡改/传输损坏都在这拦下）
            val out = File(dir, "patched-${safeTagOf(info)}.apk")
            val got = runCatching { ApkPatchApplier.apply(installedApk, patchZip, out) }.getOrNull() ?: continue

            // ④ 与发布方给出的整包 sha256 对齐（有的话），再比对签名
            if (info.apkSha256 != null && !got.equals(info.apkSha256, ignoreCase = true)) continue
            if (!isSignedBySameKey(context, out)) continue
            return out
        }
        return null
    }

    private fun safeTagOf(info: UpdateChecker.ReleaseInfo): String =
        info.tag.replace(Regex("[^A-Za-z0-9._-]"), "_")

    /** 体积 + 魔数 + 哈希 + **签名**，四者都过才算可用 */
    private fun isUsableApk(context: Context, f: File, info: UpdateChecker.ReleaseInfo): Boolean =
        f.length() > 1_000_000 && isApk(f) && matchesDigest(f, info) && isSignedBySameKey(context, f)

    /**
     * APK 的签名证书是否与**已安装的本应用**一致。
     *
     * 这比"发布方给出的 SHA-256"更根本：哈希只在 GitHub 返回了 `digest` 字段时才存在，
     * 而 APK 的字节是从多个**第三方加速镜像**下载的（[UpdateChecker.sources]）——
     * 镜像被控制就能返回一个"魔数合法、体积足够"的包，而应用会引导用户安装它。
     * 签名比对不依赖任何远端字段：没有私钥就伪造不出同签名的包。
     *
     * 拿不到签名信息时一律判为**不可用**（宁可让用户自己去 GitHub 下载，
     * 也不要装来路不明的包）。
     */
    fun isSignedBySameKey(context: Context, apk: File): Boolean = try {
        val pm = context.packageManager
        val candidate = archivePackageInfo(pm, apk)
        val installed = pm.getPackageInfo(context.packageName, signingFlags())
        val a = signersOf(candidate)
        val b = signersOf(installed)
        a.isNotEmpty() && a == b
    } catch (_: Exception) {
        false
    }

    private fun signingFlags(): Int =
        if (android.os.Build.VERSION.SDK_INT >= 28) {
            android.content.pm.PackageManager.GET_SIGNING_CERTIFICATES
        } else {
            @Suppress("DEPRECATION")
            android.content.pm.PackageManager.GET_SIGNATURES
        }

    private fun archivePackageInfo(
        pm: android.content.pm.PackageManager,
        apk: File,
    ): android.content.pm.PackageInfo? =
        if (android.os.Build.VERSION.SDK_INT >= 33) {
            pm.getPackageArchiveInfo(
                apk.absolutePath,
                android.content.pm.PackageManager.PackageInfoFlags.of(signingFlags().toLong()),
            )
        } else {
            @Suppress("DEPRECATION")
            pm.getPackageArchiveInfo(apk.absolutePath, signingFlags())
        }

    private fun signersOf(pi: android.content.pm.PackageInfo?): Set<String> {
        if (pi == null) return emptySet()
        val sigs = if (android.os.Build.VERSION.SDK_INT >= 28) {
            pi.signingInfo?.apkContentsSigners
        } else {
            @Suppress("DEPRECATION")
            pi.signatures
        }
        return sigs?.map { it.toCharsString() }?.toSet().orEmpty()
    }

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
