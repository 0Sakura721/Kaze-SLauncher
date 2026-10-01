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
     * 一行**有效**的 `eula=true`（大小写不敏感，允许键值两侧空白）。
     * 行首的 `#`/`!` 是 Properties 的注释前缀，被注释掉的备用行不算数。
     */
    private val TRUE_LINE = Regex("(?i)^eula\\s*=\\s*true\\s*$")

    /**
     * eula.txt 里是否已经 `eula=true`。
     *
     * 必须**逐行锚定**匹配，不能用无锚定的 `contains("eula=true")`：
     * 服务端生成的 eula.txt 头部注释里就写着
     * `#By changing the setting below to TRUE you are indicating your agreement…`，
     * 而用户/教程也常留下 `# eula=true` 这类被注释掉的备用行 —— 无锚定匹配会把它们
     * 当成"已接受"，于是服务端启动后立刻因为 `You need to agree to the EULA` 退出，
     * 界面只报一句笼统的早退错误，用户完全不知道问题出在 eula.txt。
     */
    fun isAccepted(serverDir: File): Boolean {
        val f = File(serverDir, "eula.txt")
        if (!f.exists()) return false
        return f.readLines().any { TRUE_LINE.matches(it.trim()) }
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
