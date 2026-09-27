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

    /**
     * 已接受的那一行。必须**按行锚定**。
     *
     * 旧实现是 `contains(Regex("(?i)eula\\s*=\\s*true"))` —— 任意位置的子串匹配，
     * 于是 `# eula=true` 这种**注释行**（MC 生成的 eula.txt 里就有一整段说明性注释）
     * 会被判成"用户已同意"：界面显示 eula 已接受、不再改写文件，服务端启动时读到的
     * 却是 false 直接退出，只报一句"环境或配置问题"，用户完全看不出问题在 eula.txt。
     */
    private val ACCEPTED_LINE = Regex("(?im)^\\s*eula\\s*=\\s*true\\s*$")

    fun isAccepted(serverDir: File): Boolean {
        val f = File(serverDir, "eula.txt")
        if (!f.exists()) return false
        return ACCEPTED_LINE.containsMatchIn(f.readText())
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
