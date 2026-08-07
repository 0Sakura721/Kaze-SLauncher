package com.mcserver.launcher.core.server

import com.mcserver.launcher.data.CoreType
import com.mcserver.launcher.data.InstanceConfig
import com.mcserver.launcher.data.ServerInstance
import com.mcserver.launcher.util.Logger
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import org.json.JSONObject

/**
 * 实例导出/导入(FCL 式"版本管理"):整个实例目录打包为 zip,
 * 换机迁移/备份/分享用。zip 内含 instance.json 描述实例信息。
 */
object ExportManager {

    /**
     * 导出实例为 zip(核心 jar + 世界 + 配置 + 插件/模组)。
     * 导出时将 instance.json 写入临时目录(而非实例目录),避免进程被杀后遗留。
     */
    fun exportInstance(instance: ServerInstance, destFile: File): Boolean {
        val tmpDir = File(InstanceStore.instancesDir.parentFile, "export_tmp_${System.currentTimeMillis()}")
        return try {
            val dir = instance.dir(InstanceStore.instancesDir)
            if (!dir.exists()) return false
            tmpDir.mkdirs()
            // 临时写入实例元信息(写在临时目录,而非实例目录)
            val meta = File(tmpDir, "instance.json")
            meta.writeText(
                JSONObject().apply {
                    put("name", instance.name)
                    put("coreType", instance.coreType.name)
                    put("mcVersion", instance.mcVersion)
                    put("buildId", instance.buildId)
                    put("config", configToJson(instance.config))
                }.toString()
            )
            ZipOutputStream(destFile.outputStream().buffered()).use { zip ->
                addToZip(zip, dir, "")
                zip.putNextEntry(ZipEntry("instance.json"))
                meta.inputStream().use { it.copyTo(zip) }
                zip.closeEntry()
            }
            Logger.i("实例导出完成: ${destFile.name} (${destFile.length() / 1024} KB)")
            true
        } catch (e: Exception) {
            Logger.w("实例导出失败", e)
            false
        } finally {
            tmpDir.deleteRecursively()
        }
    }

    /** 导入实例 zip:解压 → 读元信息 → 创建新实例 → 复制内容 */
    fun importInstance(zipFile: File): ServerInstance? {
        return try {
            val tmp = File(InstanceStore.instancesDir.parentFile, "import_tmp_${System.currentTimeMillis()}")
            tmp.mkdirs()
            // 解压
            ZipInputStream(zipFile.inputStream().buffered()).use { zin ->
                var entry = zin.nextEntry
                while (entry != null) {
                    if (!entry.isDirectory) {
                        val name = entry.name
                        if (!name.contains("..") && !name.startsWith("/")) {
                            val dest = File(tmp, name)
                            dest.parentFile?.mkdirs()
                            dest.outputStream().use { out -> zin.copyTo(out) }
                        }
                    }
                    zin.closeEntry()
                    entry = zin.nextEntry
                }
            }
            // 读元信息(可能不在 zip 根,搜一下)
            val metaFile = File(tmp, "instance.json")
            val j = if (metaFile.exists()) {
                runCatching { JSONObject(metaFile.readText()) }.getOrNull()
            } else null

            val name = j?.optString("name", "导入实例") ?: zipFile.name.removeSuffix(".zip").take(40)
            val type = runCatching { CoreType.valueOf(j?.optString("coreType", "VANILLA") ?: "VANILLA") }
                .getOrDefault(CoreType.VANILLA)
            val mcVersion = j?.optString("mcVersion", "") ?: ""
            val buildId = j?.optString("buildId", "") ?: ""
            val config = parseConfig(j?.optJSONObject("config"))

            val instance = InstanceStore.create(
                name = name,
                coreType = type,
                mcVersion = mcVersion,
                buildId = buildId,
                config = config
            )
            val dir = instance.dir(InstanceStore.instancesDir)
            // 复制内容(跳过 instance.json)
            tmp.listFiles()?.forEach { f ->
                if (f.name != "instance.json") {
                    if (f.isDirectory) copyTree(f, File(dir, f.name))
                    else f.copyTo(File(dir, f.name), overwrite = true)
                }
            }
            tmp.deleteRecursively()
            Logger.i("实例导入完成: ${instance.name}")
            instance
        } catch (e: Exception) {
            Logger.w("实例导入失败", e)
            null
        }
    }

    private fun configToJson(c: InstanceConfig): JSONObject = JSONObject().apply {
        put("serverPort", c.serverPort)
        put("maxPlayers", c.maxPlayers)
        put("gamemode", c.gamemode)
        put("difficulty", c.difficulty)
        put("pvp", c.pvp)
        put("onlineMode", c.onlineMode)
        put("whiteList", c.whiteList)
        put("motd", c.motd)
        put("levelName", c.levelName)
        put("levelSeed", c.levelSeed)
        put("levelType", c.levelType)
        put("hardcore", c.hardcore)
        put("allowNether", c.allowNether)
        put("allowFlight", c.allowFlight)
        put("spawnMonsters", c.spawnMonsters)
        put("spawnAnimals", c.spawnAnimals)
        put("maxWorldSize", c.maxWorldSize)
        put("maxRamMB", c.maxRamMB)
        put("viewDistance", c.viewDistance)
        put("spawnProtection", c.spawnProtection)
        put("nogui", c.nogui)
        put("jvmArgs", c.jvmArgs)
        put("rconEnabled", c.rconEnabled)
        put("rconPassword", c.rconPassword)
        put("rconPort", c.rconPort)
        put("autoRestart", c.autoRestart)
        put("maxRestarts", c.maxRestarts)
        put("backupOnStop", c.backupOnStop)
        put("autoBackupHours", c.autoBackupHours)
    }

    private fun parseConfig(cj: JSONObject?): InstanceConfig {
        if (cj == null) return InstanceConfig()
        return InstanceConfig(
            serverPort = cj.optInt("serverPort", 25565),
            maxPlayers = cj.optInt("maxPlayers", 20),
            gamemode = cj.optString("gamemode", "survival"),
            difficulty = cj.optString("difficulty", "normal"),
            pvp = cj.optBoolean("pvp", true),
            onlineMode = cj.optBoolean("onlineMode", false),
            whiteList = cj.optBoolean("whiteList", false),
            motd = cj.optString("motd", "A Minecraft Server"),
            levelName = cj.optString("levelName", "world"),
            levelSeed = cj.optString("levelSeed", ""),
            levelType = cj.optString("levelType", "default"),
            hardcore = cj.optBoolean("hardcore", false),
            allowNether = cj.optBoolean("allowNether", true),
            allowFlight = cj.optBoolean("allowFlight", false),
            spawnMonsters = cj.optBoolean("spawnMonsters", true),
            spawnAnimals = cj.optBoolean("spawnAnimals", true),
            maxWorldSize = cj.optInt("maxWorldSize", 29999984),
            maxRamMB = cj.optInt("maxRamMB", 2048),
            viewDistance = cj.optInt("viewDistance", 10),
            spawnProtection = cj.optInt("spawnProtection", 16),
            nogui = cj.optBoolean("nogui", true),
            jvmArgs = cj.optString("jvmArgs", ""),
            rconEnabled = cj.optBoolean("rconEnabled", true),
            rconPassword = cj.optString("rconPassword", ""),
            rconPort = cj.optInt("rconPort", 25575),
            autoRestart = cj.optBoolean("autoRestart", true),
            maxRestarts = cj.optInt("maxRestarts", 3),
            backupOnStop = cj.optBoolean("backupOnStop", true),
            autoBackupHours = cj.optInt("autoBackupHours", 0)
        )
    }

    private fun addToZip(zip: ZipOutputStream, dir: File, basePath: String) {
        dir.listFiles()?.forEach { f ->
            // instance.json 由 exportInstance 从临时目录单独写入,跳过避免重复 zip 条目
            if (f.name == "instance.json") return@forEach
            val entryPath = if (basePath.isEmpty()) f.name else "$basePath/${f.name}"
            if (f.isDirectory) {
                zip.putNextEntry(ZipEntry("$entryPath/"))
                zip.closeEntry()
                addToZip(zip, f, entryPath)
            } else {
                zip.putNextEntry(ZipEntry(entryPath))
                f.inputStream().use { it.copyTo(zip) }
                zip.closeEntry()
            }
        }
    }

    private fun copyTree(src: File, dst: File) {
        dst.mkdirs()
        src.listFiles()?.forEach { f ->
            if (f.isDirectory) copyTree(f, File(dst, f.name))
            else f.copyTo(File(dst, f.name), overwrite = true)
        }
    }
}
