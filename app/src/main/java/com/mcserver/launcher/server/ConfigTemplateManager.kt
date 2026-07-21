package com.mcserver.launcher.server

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import com.mcserver.launcher.McApplication
import com.mcserver.launcher.data.ServerConfig
import com.mcserver.launcher.data.StartupMode
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

object ConfigTemplateManager {

    private val context: Context get() = McApplication.instance

    data class ServerTemplate(
        val name: String,
        val description: String,
        val config: ServerConfig,
        val serverProperties: Map<String, String> = emptyMap(),
        val templateVersion: Int = 1,
        val warnings: List<String> = emptyList()
    )

    /**
     * 将当前配置导出为 JSON 模板文件
     */
    fun exportTemplate(config: ServerConfig, name: String = "server-config"): Result<File> {
        return try {
            val json = JSONObject().apply {
                put("templateVersion", 1)
                put("name", name)
                put("description", "MCServer Launcher 配置模板")
                put("exportedAt", SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date()))

                put("config", JSONObject().apply {
                    put("name", config.name)
                    put("jarPath", config.jarPath)
                    put("javaPath", config.javaPath)
                    put("javaVersion", config.javaVersion)
                    put("allocatedMemoryMB", config.allocatedMemoryMB)
                    put("serverPort", config.serverPort)
                    put("additionalArgs", config.additionalArgs)
                    put("autoRestart", config.autoRestart)
                    put("nogui", config.nogui)
                    put("startupMode", config.startupMode.name)
                    put("shScriptPath", config.shScriptPath)
                    put("shMaxRamMB", config.shMaxRamMB)
                    put("shMinRamMB", config.shMinRamMB)
                    put("motd", config.motd)
                    put("maxPlayers", config.maxPlayers)
                    put("gamemode", config.gamemode)
                    put("difficulty", config.difficulty)
                    put("pvp", config.pvp)
                    put("onlineMode", config.onlineMode)
                    put("whiteList", config.whiteList)
                    put("spawnProtection", config.spawnProtection)
                    put("viewDistance", config.viewDistance)
                    put("maxRestarts", config.maxRestarts)
                    put("restartCooldownSec", config.restartCooldownSec)
                    put("backupOnStop", config.backupOnStop)
                    put("rconEnabled", config.rconEnabled)
                    put("rconPassword", "")
                    put("rconPort", config.rconPort)
                })

                // 读取当前 server.properties
                val propsFile = File(ProotServerManager.serverDir(context), "server.properties")
                if (propsFile.exists()) {
                    val props = JSONObject()
                    propsFile.readLines().forEach { line ->
                        val trimmed = line.trim()
                        if (trimmed.isNotEmpty() && !trimmed.startsWith("#") && trimmed.contains("=")) {
                            val parts = trimmed.split("=", limit = 2)
                            if (parts.size == 2) {
                                props.put(parts[0].trim(), parts[1].trim())
                            }
                        }
                    }
                    put("serverProperties", props)
                }
            }

            val exportDir = File(context.getExternalFilesDir(null), "templates")
            exportDir.mkdirs()
            val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
            val file = File(exportDir, "mcserver_template_$timestamp.json")
            file.writeText(json.toString(2))

            Result.success(file)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * 从 JSON 文件导入配置
     */
    fun importTemplate(file: File): Result<ServerTemplate> {
        return try {
            val json = JSONObject(file.readText())

            val configJson = json.getJSONObject("config")
            val config = ServerConfig(
                name = configJson.optString("name", "Minecraft Server"),
                jarPath = configJson.optString("jarPath", ""),
                javaPath = configJson.optString("javaPath", ""),
                javaVersion = configJson.optInt("javaVersion", 21),
                allocatedMemoryMB = configJson.optInt("allocatedMemoryMB", 2048),
                serverPort = configJson.optInt("serverPort", 25565),
                additionalArgs = configJson.optString("additionalArgs", ""),
                autoRestart = configJson.optBoolean("autoRestart", false),
                nogui = configJson.optBoolean("nogui", true),
                startupMode = try { StartupMode.valueOf(configJson.optString("startupMode", "DIRECT_JAR")) } catch (_: Exception) { StartupMode.DIRECT_JAR },
                shScriptPath = configJson.optString("shScriptPath", ""),
                shMaxRamMB = configJson.optInt("shMaxRamMB", 4096),
                shMinRamMB = configJson.optInt("shMinRamMB", 2048),
                motd = configJson.optString("motd", "A Minecraft Server"),
                maxPlayers = configJson.optInt("maxPlayers", 20),
                gamemode = configJson.optString("gamemode", "survival"),
                difficulty = configJson.optString("difficulty", "easy"),
                pvp = configJson.optBoolean("pvp", true),
                onlineMode = configJson.optBoolean("onlineMode", true),
                whiteList = configJson.optBoolean("whiteList", false),
                spawnProtection = configJson.optInt("spawnProtection", 16),
                viewDistance = configJson.optInt("viewDistance", 10),
                maxRestarts = configJson.optInt("maxRestarts", 3),
                restartCooldownSec = configJson.optInt("restartCooldownSec", 5),
                backupOnStop = configJson.optBoolean("backupOnStop", false),
                rconEnabled = configJson.optBoolean("rconEnabled", true),
                rconPassword = configJson.optString("rconPassword", ""),
                rconPort = configJson.optInt("rconPort", 25575)
            )

            val props = mutableMapOf<String, String>()
            if (json.has("serverProperties")) {
                val propsJson = json.getJSONObject("serverProperties")
                propsJson.keys().forEach { key -> props[key] = propsJson.getString(key) }
            }

            val warnings = mutableListOf<String>()
            if (config.rconEnabled && config.rconPassword.isEmpty()) {
                warnings.add("RCON 密码已被清空，请重新设置 RCON 密码以确保安全")
            }
            Result.success(ServerTemplate(
                name = json.optString("name", "导入的配置"),
                description = json.optString("description", ""),
                config = config,
                serverProperties = props,
                warnings = warnings
            ))
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * 通过 Android Share Intent 分享配置模板
     */
    fun shareTemplate(context: Context, config: ServerConfig) {
        val result = exportTemplate(config)
        if (result.isSuccess) {
            val file = result.getOrThrow()
            val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
            val shareIntent = Intent(Intent.ACTION_SEND).apply {
                type = "application/json"
                putExtra(Intent.EXTRA_STREAM, uri)
                putExtra(Intent.EXTRA_SUBJECT, "Minecraft 服务器配置模板")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            context.startActivity(Intent.createChooser(shareIntent, "分享配置模板"))
        }
    }

    /**
     * 内置预设模板
     */
    fun getPresetTemplates(): List<ServerTemplate> = listOf(
        ServerTemplate(
            name = "小型服务器 (1-2人)",
            description = "适合低配设备的精简配置，512MB-1GB 内存",
            config = ServerConfig(
                name = "Small Server",
                allocatedMemoryMB = 1024,
                serverPort = 25565,
                additionalArgs = "-XX:+UseG1GC -XX:MaxGCPauseMillis=200 -XX:+DisableExplicitGC",
                maxPlayers = 5,
                gamemode = "survival",
                difficulty = "easy",
                viewDistance = 8,
                onlineMode = false,
                autoRestart = true,
                maxRestarts = 3
            )
        ),
        ServerTemplate(
            name = "中型服务器 (5-10人)",
            description = "均衡配置，2-4GB 内存，推荐 Paper 核心",
            config = ServerConfig(
                name = "Medium Server",
                allocatedMemoryMB = 3072,
                serverPort = 25565,
                additionalArgs = "-XX:+UseG1GC -XX:MaxGCPauseMillis=200 -XX:+ParallelRefProcEnabled -XX:+DisableExplicitGC -XX:+AlwaysPreTouch -XX:G1NewSizePercent=30",
                maxPlayers = 20,
                gamemode = "survival",
                difficulty = "normal",
                viewDistance = 10,
                onlineMode = true,
                autoRestart = true,
                maxRestarts = 5,
                rconEnabled = true
            )
        ),
        ServerTemplate(
            name = "大型服务器 (20+人)",
            description = "高性能配置，6-8GB 内存，Paper/Purpur 优化参数",
            config = ServerConfig(
                name = "Large Server",
                allocatedMemoryMB = 6144,
                serverPort = 25565,
                additionalArgs = "-XX:+UseG1GC -XX:MaxGCPauseMillis=200 -XX:+ParallelRefProcEnabled -XX:+DisableExplicitGC -XX:+AlwaysPreTouch -XX:G1HeapRegionSize=4M -XX:+UnlockExperimentalVMOptions -XX:G1NewSizePercent=30 -XX:G1MaxNewSizePercent=40 -XX:G1MixedGCLiveThresholdPercent=90 -XX:+PerfDisableSharedMem -XX:+UseStringDeduplication",
                maxPlayers = 50,
                gamemode = "survival",
                difficulty = "hard",
                viewDistance = 16,
                onlineMode = true,
                autoRestart = true,
                maxRestarts = 10,
                rconEnabled = true
            )
        )
    )
}
