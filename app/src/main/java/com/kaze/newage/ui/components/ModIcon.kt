package com.kaze.newage.ui.components

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.kaze.newage.core.addons.ModIconCache
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/**
 * 模组图标：从 jar 里抽出来的图标（本地）或 Modrinth 的 project icon（远程）。
 *
 * 两条硬约束决定了实现方式：
 *  1. **不能在组合期做 IO** —— 解压 + 解码一张图要几毫秒，列表里几十行同步做就是白屏；
 *     所以走 [produceState] 在后台线程解码，先渲染占位图标，到了再替换。
 *  2. **不能每次重组都重读** —— 内存 LRU（这里）+ 磁盘缓存（[ModIconCache] / 远程图写缓存目录）
 *     两层挡住；滚动来回时只是内存命中。
 *
 * 远程图仍然落磁盘：搜索结果里的图标来自 `cdn.modrinth.com`，手机网络下
 * 每次滚回来都重新下载既慢又费流量。
 */
private object IconMemoryCache {
    private const val MAX = 96

    private val map = object : LinkedHashMap<String, ImageBitmap>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, ImageBitmap>?): Boolean =
            size > MAX
    }

    @Synchronized
    fun get(key: String): ImageBitmap? = map[key]

    @Synchronized
    fun put(key: String, value: ImageBitmap) {
        map[key] = value
    }
}

/** 已装模组/插件的图标（jar 内嵌图）；没有图标时显示 [fallback] */
@Composable
fun ModIcon(
    jar: File?,
    fallback: ImageVector,
    modifier: Modifier = Modifier,
    size: Dp = 40.dp,
) {
    val key = jar?.let { "file:${it.absolutePath}:${it.length()}:${it.lastModified()}" }
    val bitmap by produceState<ImageBitmap?>(
        initialValue = key?.let { IconMemoryCache.get(it) },
        key,
        jar?.length(),
        jar?.lastModified(),
    ) {
        if (jar == null) {
            value = null
            return@produceState
        }
        val cached = key?.let { IconMemoryCache.get(it) }
        if (cached != null) {
            value = cached
            return@produceState
        }
        val decoded = runCatching {
            ModIconCache.fileFor(jar)?.let { decodeSampled(it) }
        }.getOrNull()
        if (decoded != null && key != null) IconMemoryCache.put(key, decoded)
        value = decoded
    }
    IconTile(bitmap, fallback, size, modifier)
}

/** 远程图标（Modrinth 搜索结果）；下载失败/没网时显示 [fallback] */@Composable
fun RemoteIcon(
    url: String?,
    fallback: ImageVector,
    modifier: Modifier = Modifier,
    size: Dp = 40.dp,
) {
    val bitmap by produceState<ImageBitmap?>(
        initialValue = url?.let { IconMemoryCache.get(it) },
        url,
    ) {
        if (url.isNullOrBlank()) {
            value = null
            return@produceState
        }
        IconMemoryCache.get(url)?.let {
            value = it
            return@produceState
        }
        // cdn.modrinth.com 在国内经常连不上：原地址失败后自动换 MCIM 的 /data/ 镜像，
        // 否则搜索结果会整片显示占位图标（"图标没补齐"就是这个原因）。
        val candidates = com.kaze.newage.core.content.ContentSources.preferredFileCandidates(url)
        val decoded = runCatching {
            candidates.firstNotNullOfOrNull { candidate ->
                downloadRemoteIcon(candidate)?.let { decodeSampled(it) }
            }
        }.getOrNull()
        if (decoded != null) IconMemoryCache.put(url, decoded)
        value = decoded
    }
    IconTile(bitmap, fallback, size, modifier)
}

/**
 * 磁盘上的图片作为图标：世界存档的 `icon.png`、基岩版附加包的 `pack_icon.png` 都走这里。
 *
 * 与 [ModIcon] 的区别只是"图片不在 jar 里，而是直接一个文件"；
 * 内存缓存/降采样共用同一套（key 里带大小与修改时间，文件被替换就自动失效）。
 */
@Composable
fun FileImageIcon(
    file: File?,
    fallback: ImageVector,
    modifier: Modifier = Modifier,
    size: Dp = 40.dp,
) {
    val key = file?.let { "img:${it.absolutePath}:${it.length()}:${it.lastModified()}" }
    val bitmap by produceState<ImageBitmap?>(
        initialValue = key?.let { IconMemoryCache.get(it) },
        key,
        file?.length(),
        file?.lastModified(),
    ) {
        if (file == null) {
            value = null
            return@produceState
        }
        IconMemoryCache.get(key!!)?.let {
            value = it
            return@produceState
        }
        val decoded = runCatching { decodeSampled(file) }.getOrNull()
        if (decoded != null) IconMemoryCache.put(key, decoded)
        value = decoded
    }
    IconTile(bitmap, fallback, size, modifier)
}

@Composable
private fun IconTile(
    bitmap: ImageBitmap?,
    fallback: ImageVector,
    size: Dp,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier
            .size(size)
            .clip(RoundedCornerShape(size * 0.24f))
            .background(MaterialTheme.colorScheme.surfaceContainerHighest),
        contentAlignment = Alignment.Center,
    ) {
        if (bitmap != null) {
            Image(
                bitmap = bitmap,
                contentDescription = null,
                modifier = Modifier.fillMaxSize(),
                // Crop 而不是 Fit：模组图标基本是正方形，Fit 在非正方形图里会留一圈难看的透明边
                contentScale = ContentScale.Crop,
            )
        } else {
            Icon(
                fallback,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(size * 0.5f),
            )
        }
    }
}

/** 按长边降到 ≤ 128px 再解码：40dp 的位置放 512×512 的图只是白占内存 */
private fun decodeSampled(file: File): ImageBitmap? {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(file.absolutePath, bounds)
    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
    var sample = 1
    while (bounds.outWidth / sample > 256 || bounds.outHeight / sample > 256) sample *= 2
    val bmp: Bitmap = BitmapFactory.decodeFile(
        file.absolutePath,
        BitmapFactory.Options().apply {
            inSampleSize = sample
            inPreferredConfig = Bitmap.Config.ARGB_8888
        },
    ) ?: return null
    return bmp.asImageBitmap()
}

/** 下载远程图标到缓存目录（最长 8 秒、最大 2 MB），返回本地文件 */
private fun downloadRemoteIcon(url: String): File? {
    val dir = ModIconCache.cacheDir() ?: return null
    val name = "url-" + sha1(url).take(24) + ".img"
    val out = File(dir, name)
    if (out.isFile && out.length() > 0) return out
    val conn = (URL(url).openConnection() as HttpURLConnection).apply {
        // 图标只是装饰：单个候选最多等 5 秒，失败立刻换下一个（镜像通常 <1 秒）
        connectTimeout = 5000
        readTimeout = 5000
        setRequestProperty("User-Agent", "KazeSLauncher/3.0 (Android)")
    }
    try {
        if (conn.responseCode != 200) return null
        val bytes = conn.inputStream.use { input ->
            val buf = java.io.ByteArrayOutputStream()
            val chunk = ByteArray(16 * 1024)
            while (true) {
                val n = input.read(chunk)
                if (n <= 0) break
                buf.write(chunk, 0, n)
                if (buf.size() > 2 * 1024 * 1024) return null
            }
            buf.toByteArray()
        }
        if (bytes.size < 16) return null
        out.writeBytes(bytes)
        return out
    } finally {
        conn.disconnect()
    }
}

private fun sha1(s: String): String = runCatching {
    MessageDigest.getInstance("SHA-1").digest(s.toByteArray())
        .joinToString("") { "%02x".format(it) }
}.getOrElse { Integer.toHexString(s.hashCode()) }
