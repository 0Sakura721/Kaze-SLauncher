package com.mcserver.launcher.server

import com.mcserver.launcher.McApplication
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * 资源包管理器 — 管理 Minecraft 服务器 resourcepacks 目录。
 * 借鉴 MCSManager 的资源管理设计。
 *
 * 功能：
 * - 列出 resourcepacks 目录中的资源包
 * - 启用/禁用资源包（通过重命名）
 * - 设置 server.properties 中的资源包相关选项
 * - 设置资源包 URL、SHA1 哈希等
 */
object ResourcePackManager {

    private val serverDir: File get() = ProotServerManager.serverDir(McApplication.instance)
    val packsDir: File get() = File(serverDir, "resourcepacks")

    data class PackInfo(
        val name: String,
        val fileName: String,
        val fileSize: Long,
        val isZip: Boolean,
        val enabled: Boolean,
        val lastModified: Long
    )

    private fun validateFileName(name: String) {
        require(name.isNotBlank()) { "文件名不能为空" }
        require(!name.contains("/") && !name.contains("\\") && !name.contains("..") && !name.contains(":")) {
            "文件名包含非法字符: $name"
        }
    }

    private fun validatePathInsideRoot(file: File, root: File): Boolean {
        return try {
            file.canonicalFile.startsWith(root.canonicalFile)
        } catch (_: Exception) {
            false
        }
    }

    /**
     * 资源包配置（写入 server.properties）
     */
    data class PackConfig(
        val requireResourcePack: Boolean = false,
        val resourcePackUrl: String = "",
        val resourcePackSha1: String = "",
        val resourcePackPrompt: String = "",
        val resourcePackEnforce: Boolean = false
    )

    /** 列出资源包 */
    suspend fun listPacks(): List<PackInfo> = withContext(Dispatchers.IO) {
        if (!packsDir.exists()) return@withContext emptyList()
        packsDir.listFiles()
            ?.filter { it.isFile }
            ?.map { f ->
                val name = f.nameWithoutExtension
                val isDisabled = f.extension.equals("disabled", ignoreCase = true) ||
                    f.name.endsWith(".zip.disabled")
                val isZip = f.name.endsWith(".zip") || f.name.endsWith(".zip.disabled")
                PackInfo(
                    name = name,
                    fileName = f.name,
                    fileSize = f.length(),
                    isZip = isZip,
                    enabled = !isDisabled,
                    lastModified = f.lastModified()
                )
            }
            ?.sortedBy { it.name.lowercase() } ?: emptyList()
    }

    /** 启用/禁用资源包 */
    suspend fun togglePack(pack: PackInfo): Boolean = withContext(Dispatchers.IO) {
        validateFileName(pack.fileName)
        val file = File(packsDir, pack.fileName)
        if (!validatePathInsideRoot(file, packsDir)) {
            throw SecurityException("文件路径越界: ${pack.fileName}")
        }
        if (pack.enabled) {
            // 禁用：重命名为 .disabled
            val disabled = File(packsDir, "${pack.fileName}.disabled")
            if (!validatePathInsideRoot(disabled, packsDir)) {
                throw SecurityException("文件路径越界: ${pack.fileName}.disabled")
            }
            file.renameTo(disabled)
        } else {
            // 启用：去掉 .disabled
            val original = if (pack.fileName.endsWith(".disabled")) {
                val origName = pack.fileName.removeSuffix(".disabled")
                validateFileName(origName)
                File(packsDir, origName)
            } else {
                File(packsDir, pack.fileName)
            }
            if (!validatePathInsideRoot(original, packsDir)) {
                throw SecurityException("文件路径越界")
            }
            file.renameTo(original)
        }
        true
    }

    /** 删除资源包 */
    suspend fun deletePack(pack: PackInfo): Boolean = withContext(Dispatchers.IO) {
        validateFileName(pack.fileName)
        val file = File(packsDir, pack.fileName)
        if (!validatePathInsideRoot(file, packsDir)) {
            throw SecurityException("文件路径越界: ${pack.fileName}")
        }
        file.delete()
    }

    /** 读取当前的资源包配置 */
    suspend fun getPackConfig(): PackConfig = withContext(Dispatchers.IO) {
        val props = File(serverDir, "server.properties")
        if (!props.exists()) return@withContext PackConfig()
        val lines = props.readLines()
        var require = false; var url = ""; var sha1 = ""; var prompt = ""; var enforce = false
        for (line in lines) {
            when {
                line.startsWith("require-resource-pack=") ->
                    require = line.substringAfter("=").trim().toBooleanStrictOrNull() ?: false
                line.startsWith("resource-pack=") ->
                    url = line.substringAfter("=").trim()
                line.startsWith("resource-pack-sha1=") ->
                    sha1 = line.substringAfter("=").trim()
                line.startsWith("resource-pack-prompt=") ->
                    prompt = line.substringAfter("=").trim()
                line.startsWith("require-resource-pack=") -> {} // already handled
            }
        }
        PackConfig(require, url, sha1, prompt, enforce)
    }

    /** 保存资源包配置到 server.properties */
    suspend fun savePackConfig(config: PackConfig): Boolean = withContext(Dispatchers.IO) {
        try {
            val props = File(serverDir, "server.properties")
            val lines = if (props.exists()) props.readLines().toMutableList()
            else mutableListOf()

            val desired = mapOf(
                "require-resource-pack" to config.requireResourcePack.toString(),
                "resource-pack" to config.resourcePackUrl,
                "resource-pack-sha1" to config.resourcePackSha1,
                "resource-pack-prompt" to config.resourcePackPrompt
            )

            for ((key, value) in desired) {
                var found = false
                for (i in lines.indices) {
                    if (lines[i].trim().startsWith("$key=")) {
                        lines[i] = "$key=$value"
                        found = true
                        break
                    }
                }
                if (!found) lines.add("$key=$value")
            }
            props.writeText(lines.joinToString("\n") + "\n")
            true
        } catch (_: Exception) { false }
    }

    /** 格式化文件大小 */
    fun formatSize(bytes: Long): String {
        if (bytes < 1024) return "$bytes B"
        val kb = bytes / 1024.0
        if (kb < 1024) return "%.1f KB".format(kb)
        val mb = kb / 1024.0
        return "%.1f MB".format(mb)
    }
}
