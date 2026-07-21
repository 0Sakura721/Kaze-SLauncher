package com.mcserver.launcher.server

import com.mcserver.launcher.McApplication
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.jar.JarFile

/**
 * 插件管理器 — 解析 Minecraft 服务器 plugins 目录中的插件信息。
 * 支持 plugin.yml / paper-plugin.yml / bungee.yml / fabric.mod.json 等元数据格式。
 */
object PluginManager {

    private val serverDir: File get() = ProotServerManager.serverDir(McApplication.instance)
    val pluginsDir: File get() = File(serverDir, "plugins")

    data class PluginInfo(
        val name: String,
        val version: String,
        val author: String = "",
        val description: String = "",
        val main: String = "",
        val fileName: String,
        val fileSize: Long,
        val enabled: Boolean = true
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

    /** 扫描 plugins 目录，解析所有插件的元数据 */
    suspend fun scanPlugins(): List<PluginInfo> = withContext(Dispatchers.IO) {
        val list = mutableListOf<PluginInfo>()
        if (!pluginsDir.exists()) return@withContext list

        pluginsDir.listFiles()?.filter { it.extension.equals("jar", ignoreCase = true) }?.forEach { jar ->
            val info = parsePluginJar(jar)
            if (info != null) list.add(info)
        }
        list.sortedBy { it.name.lowercase() }
    }

    /** 检查某个插件是否已禁用（通过检查 .disabled 后缀文件） */
    private fun isPluginDisabled(jarFile: File): Boolean {
        return File(jarFile.parentFile, "${jarFile.name}.disabled").exists() ||
               jarFile.extension.equals("jar_disabled", ignoreCase = true)
    }

    /** 解析 JAR 内的插件元数据 */
    private fun parsePluginJar(jarFile: File): PluginInfo? {
        return try {
            JarFile(jarFile).use { jar ->
                // 尝试多种元数据文件
                val entryNames = listOf("plugin.yml", "paper-plugin.yml", "bungee.yml", "velocity-plugin.json")
                var name = jarFile.nameWithoutExtension
                var version = "未知"
                var author = ""
                var description = ""
                var main = ""

                for (entryName in entryNames) {
                    val entry = jar.getJarEntry(entryName) ?: continue
                    val content = jar.getInputStream(entry).bufferedReader().readText()

                    if (entryName.endsWith(".yml") || entryName.endsWith(".yaml")) {
                        try {
                            // 使用简单的手动解析，避免 SnakeYAML 在 Android 上的兼容性问题
                            name = extractYamlValue(content, "name") ?: name
                            version = extractYamlValue(content, "version") ?: version
                            author = extractYamlValue(content, "author") ?: extractYamlValue(content, "authors") ?: ""
                            description = extractYamlValue(content, "description") ?: ""
                            main = extractYamlValue(content, "main") ?: ""
                        } catch (_: Exception) {
                            name = extractYamlValue(content, "name") ?: name
                            version = extractYamlValue(content, "version") ?: version
                            author = extractYamlValue(content, "author") ?: ""
                            main = extractYamlValue(content, "main") ?: ""
                        }
                    } else if (entryName == "velocity-plugin.json") {
                        try {
                            val json = org.json.JSONObject(content)
                            name = json.optString("name", name)
                            version = json.optString("version", version)
                            author = json.optJSONArray("authors")?.let { arr ->
                                (0 until arr.length()).map { arr.getString(it) }.joinToString(", ")
                            } ?: json.optString("author", "")
                            description = json.optString("description", "")
                            main = json.optString("main", "")
                        } catch (_: Exception) {}
                    }
                    break // 第一个成功解析的就用
                }

                // 也检查 fabric.mod.json
                val fabricEntry = jar.getJarEntry("fabric.mod.json")
                if (fabricEntry != null && main.isEmpty()) {
                    try {
                        val content = jar.getInputStream(fabricEntry).bufferedReader().readText()
                        val json = org.json.JSONObject(content)
                        name = json.optString("name", name)
                        version = json.optString("version", version)
                        author = json.optJSONArray("authors")?.let { arr ->
                            (0 until arr.length()).map { arr.getString(it) }.joinToString(", ")
                        } ?: ""
                        description = json.optString("description", "")
                        main = json.optString("entrypoints", "")
                    } catch (_: Exception) {}
                }

                PluginInfo(
                    name = name,
                    version = version,
                    author = author,
                    description = description,
                    main = main,
                    fileName = jarFile.name,
                    fileSize = jarFile.length(),
                    enabled = !isPluginDisabled(jarFile)
                )
            }
        } catch (e: Exception) {
            // 损坏的 JAR，返回基本信息
            PluginInfo(
                name = jarFile.nameWithoutExtension,
                version = "未知",
                fileName = jarFile.name,
                fileSize = jarFile.length(),
                enabled = !isPluginDisabled(jarFile),
                description = "无法读取插件信息：${e.message}"
            )
        }
    }

    /** 简单的手动 YAML 值提取（不依赖 SnakeYAML 时的回退方案） */
    private fun extractYamlValue(content: String, key: String): String? {
        val lines = content.lines()
        for (line in lines) {
            val trimmed = line.trim()
            if (trimmed.startsWith("$key:") || trimmed.startsWith("$key :")) {
                return trimmed.substringAfter(":").trim().removeSurrounding("\"", "'")
                    .removePrefix("\"").removeSuffix("\"").trim()
            }
        }
        return null
    }

    /** 启用/禁用插件（通过重命名） */
    suspend fun togglePlugin(plugin: PluginInfo): Boolean = withContext(Dispatchers.IO) {
        validateFileName(plugin.fileName)
        val jarFile = File(pluginsDir, plugin.fileName)
        if (!validatePathInsideRoot(jarFile, pluginsDir)) {
            throw SecurityException("文件路径越界: ${plugin.fileName}")
        }
        if (plugin.enabled) {
            // 禁用：添加 .disabled 后缀文件
            val disabledMarker = File(pluginsDir, "${plugin.fileName}.disabled")
            if (!validatePathInsideRoot(disabledMarker, pluginsDir)) {
                throw SecurityException("文件路径越界: ${plugin.fileName}.disabled")
            }
            disabledMarker.createNewFile()
        } else {
            // 启用：删除 .disabled 标记
            val disabledMarker = File(pluginsDir, "${plugin.fileName}.disabled")
            if (!validatePathInsideRoot(disabledMarker, pluginsDir)) {
                throw SecurityException("文件路径越界: ${plugin.fileName}.disabled")
            }
            disabledMarker.delete()
        }
        true
    }

    /** 删除插件 */
    suspend fun deletePlugin(plugin: PluginInfo): Boolean = withContext(Dispatchers.IO) {
        validateFileName(plugin.fileName)
        val jarFile = File(pluginsDir, plugin.fileName)
        if (!validatePathInsideRoot(jarFile, pluginsDir)) {
            throw SecurityException("文件路径越界: ${plugin.fileName}")
        }
        val disabledMarker = File(pluginsDir, "${plugin.fileName}.disabled")
        if (!validatePathInsideRoot(disabledMarker, pluginsDir)) {
            throw SecurityException("文件路径越界: ${plugin.fileName}.disabled")
        }
        disabledMarker.delete()
        jarFile.delete()
    }
}
