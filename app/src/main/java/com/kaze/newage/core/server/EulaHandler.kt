package com.kaze.newage.core.server

import java.io.File

/** eula.txt 处理结果 */
data class EulaResult(
    val file: File,
    val accepted: Boolean,
    val changed: Boolean,
)

/**
 * eula.txt 处理器：
 *  - 若不存在：首次启动服务端让其自动生成，或主动写入 eula=true
 *  - 若存在且 eula=false：改写为 true
 */
object EulaHandler {

    fun isAccepted(serverDir: File): Boolean {
        val f = File(serverDir, "eula.txt")
        if (!f.exists()) return false
        return f.readText().contains(Regex("(?i)eula\\s*=\\s*true"))
    }

    /** 主动写入 eula=true（覆盖/新建） */
    fun accept(serverDir: File): EulaResult {
        val f = File(serverDir, "eula.txt")
        val existed = f.exists()
        val content = buildString {
            appendLine("#By changing the setting below to TRUE you are indicating your agreement to our EULA (https://aka.ms/MinecraftEULA).")
            appendLine("eula=true")
        }
        f.writeText(content)
        return EulaResult(f, accepted = true, changed = !existed || !isAccepted(serverDir))
    }

    /** 把已有文件中的 false 改为 true（保留注释与格式） */
    fun flipToTrue(serverDir: File): EulaResult {
        val f = File(serverDir, "eula.txt")
        if (!f.exists()) return accept(serverDir)
        val original = f.readText()
        val replaced = original.replace(Regex("(?m)^\\s*eula\\s*=\\s*false\\s*$"), "eula=true")
        if (replaced != original) {
            f.writeText(replaced)
            return EulaResult(f, accepted = true, changed = true)
        }
        return EulaResult(f, accepted = isAccepted(serverDir), changed = false)
    }
}
