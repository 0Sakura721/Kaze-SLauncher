package com.mcserver.launcher.core.server

import com.mcserver.launcher.data.ServerInstance
import com.mcserver.launcher.util.Logger
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * 实例导出/导入(FCL 式"版本管理"):整个实例目录打包为 zip,
 * 换机迁移/备份/分享用。zip 内含 instance.json 描述实例信息。
 */
object ExportManager {

    /**
     * 导出实例为 zip(核心 jar + 世界 + 配置 + 插件/模组)。
     * 导出时临时写入 instance.json,完成后删除。
     */
    fun exportInstance(instance: ServerInstance, destFile: File): Boolean {
        return try {
            val dir = instance.dir(InstanceStore.instancesDir)
            if (!dir.exists()) return false
            // 临时写入实例元信息
            val meta = File(dir, "instance.json")
            meta.writeText(
                "{\"name\":\"${instance.name.replace("\"", "\\\"")}\"," +
                "\"coreType\":\"${instance.coreType.name}\"," +
                "\"mcVersion\":\"${instance.mcVersion}\"," +
                "\"buildId\":\"${instance.buildId}\"}"
            )
            ZipOutputStream(destFile.outputStream().buffered()).use { zip ->
                addToZip(zip, dir, "")
            }
            meta.delete()
            Logger.i("实例导出完成: ${destFile.name} (${destFile.length() / 1024} KB)")
            true
        } catch (e: Exception) {
            Logger.w("实例导出失败", e)
            false
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
            val meta = if (metaFile.exists()) {
                runCatching {
                    val j = org.json.JSONObject(metaFile.readText())
                    Triple(j.optString("name", "导入实例"), j.optString("coreType", "VANILLA"), j.optString("mcVersion", ""))
                }.getOrNull()
            } else null

            val name = meta?.first ?: zipFile.name.removeSuffix(".zip").take(40)
            val type = runCatching { com.mcserver.launcher.data.CoreType.valueOf(meta?.second ?: "VANILLA") }.getOrDefault(com.mcserver.launcher.data.CoreType.VANILLA)
            val mcVersion = meta?.third ?: ""

            val instance = InstanceStore.create(name = name, coreType = type, mcVersion = mcVersion)
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

    private fun addToZip(zip: ZipOutputStream, dir: File, basePath: String) {
        dir.listFiles()?.forEach { f ->
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
