package com.mcserver.launcher.core.instance

import com.mcserver.launcher.data.AppPaths
import java.io.File

/**
 * EULA 管理：对应 Termux 启动流程中的
 * "服务端生成 eula → 编辑 eula 文件 false→true → 再次启动"。
 * 首次启动由服务端自行生成 eula.txt，用户同意后写回 true。
 */
object EulaManager {

    fun fileOf(instanceId: String): File = File(AppPaths.instanceDir(instanceId), "eula.txt")

    /** eula.txt 是否存在（服务端已生成） */
    fun exists(instanceId: String): Boolean = fileOf(instanceId).exists()

    /** 是否已同意 */
    fun isAgreed(instanceId: String): Boolean {
        val f = fileOf(instanceId)
        return f.exists() && f.readText().contains("eula=true")
    }

    /** 生成模板（false，可选：让 UI 有内容可编辑展示） */
    fun ensureTemplate(instanceId: String) {
        val f = fileOf(instanceId)
        if (!f.exists()) {
            f.parentFile?.mkdirs()
            f.writeText("# Minecraft EULA\neula=false\n")
        }
    }

    /** 用户同意：false → true */
    fun accept(instanceId: String): Boolean = try {
        val f = fileOf(instanceId)
        f.parentFile?.mkdirs()
        val text = f.takeIf { it.exists() }?.readText() ?: ""
        val replaced = if (text.isEmpty()) "eula=true\n" else text.replace(Regex("eula\\s*=\\s*false"), "eula=true")
        f.writeText(replaced)
        true
    } catch (e: Exception) {
        false
    }
}