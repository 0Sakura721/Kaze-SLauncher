package com.kaze.newage.core.console

import com.kaze.newage.core.addons.ModMetadataReader
import com.kaze.newage.core.server.ServerProperties
import com.kaze.newage.core.server.WhitelistFile
import com.kaze.newage.testutil.TestJars
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class OfflineConsoleTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private lateinit var instance: com.kaze.newage.data.model.ServerInstance
    private lateinit var root: File

    @Before
    fun setUp() {
        root = tmp.newFolder()
        instance = TestJars.instance(root, name = "离线服")
        // 一个"已经跑过一次"的实例目录
        ServerProperties.save(
            root,
            linkedMapOf("motd" to "hello", "server-port" to "25565", "online-mode" to "false"),
        )
        File(root, "eula.txt").writeText("eula=false\n")
        File(root, "mods").mkdirs()
        TestJars.fabricMod(File(root, "mods/lithium.jar"))
        File(root, "logs").mkdirs()
        File(root, "logs/latest.log").writeText("[INFO] Done (3.2s)!\n[WARN] something\n")
        ModMetadataReader.invalidate()
    }

    private fun run(input: String, shell: (suspend (String) -> Result<String>)? = null): List<OfflineConsole.Line> =
        runBlocking { OfflineConsole.run(instance, input, shell) }

    private fun text(lines: List<OfflineConsole.Line>) = lines.joinToString("\n") { it.text }

    @Test
    fun helpListsCommands() {
        val out = run("help")
        val t = text(out)
        assertTrue(t.contains("离线控制台命令"))
        assertTrue(t.contains("props"))
        assertTrue(t.contains("sh <Linux 命令>"))
    }

    @Test
    fun showsInstanceInfo() {
        val t = text(run("info"))
        assertTrue(t.contains("离线服"))
        assertTrue(t.contains("25565"))
    }

    @Test
    fun readsAndWritesProperties() {
        assertTrue(text(run("props motd")).contains("hello"))

        val set = run("props motd=欢迎 来到")
        assertTrue(text(set).contains("已写入"))
        assertTrue(text(run("props motd")).contains("欢迎 来到"))
        assertTrue(ServerProperties.load(root)["motd"] == "欢迎 来到")

        val setArg = run("props set max-players 8")
        assertTrue(text(setArg).contains("max-players=8"))
        assertTrue(text(run("props")).contains("online-mode=false"))
    }

    @Test
    fun listsAndCatsFiles() {
        val ls = text(run("ls"))
        assertTrue(ls.contains("server.properties"))
        assertTrue(ls.contains("mods/"))

        val cat = text(run("cat logs/latest.log"))
        assertTrue(cat.contains("Done (3.2s)"))
        assertTrue(cat.contains("共 2 行"))
    }

    @Test
    fun blocksPathTraversal() {
        File(root.parentFile, "outside-secret.txt").writeText("TOP SECRET")
        val out = run("cat ../outside-secret.txt")
        assertTrue(text(out).contains("路径不在实例目录内"))
        assertFalse(out.any { it.text.contains("TOP SECRET") })

        assertTrue(text(run("ls ..")).contains("路径不在实例目录内"))
    }

    @Test
    fun grepsTextFiles() {
        val out = text(run("grep WARN"))
        assertTrue(out.contains("logs/latest.log:2"))
        assertTrue(out.contains("something"))
    }

    @Test
    fun managesWhitelist() {
        assertTrue(text(run("wl add Steve")).contains("已加入白名单"))
        val entries = WhitelistFile.read(root)
        assertTrue(entries.any { it.name == "Steve" })
        assertTrue(text(run("wl")).contains("Steve"))
        assertTrue(text(run("wl remove Steve")).contains("已从白名单移除"))
        assertTrue(WhitelistFile.read(root).isEmpty())
    }

    @Test
    fun listsAndTogglesMods() {
        val list = text(run("mods"))
        assertTrue(list.contains("Lithium"))
        assertTrue(list.contains("启用"))

        assertTrue(text(run("mods info 1")).contains("lithium"))
        assertTrue(text(run("mods disable 1")).contains("已停用"))

        val disabled = File(root, "mods/lithium.jar.disabled")
        assertTrue(disabled.isFile)
        assertTrue(text(run("mods enable 1")).contains("已启用"))
        assertTrue(File(root, "mods/lithium.jar").isFile)
    }

    @Test
    fun acceptsEula() {
        assertTrue(text(run("eula")).contains("未接受"))
        assertTrue(text(run("eula accept")).contains("已写入 eula=true"))
        assertTrue(text(run("eula")).contains("已接受"))
    }

    @Test
    fun runsShellThroughInjectedRunner() {
        val shell: suspend (String) -> Result<String> = { cmd -> Result.success("echoed: $cmd") }
        val out = text(run("sh uname -a", shell))
        assertTrue(out.contains("echoed: uname -a"))
    }

    @Test
    fun shellWithoutEnvironmentExplains() {
        val out = run("sh ls")
        assertTrue(text(out).contains("Linux 环境还没准备好"))
    }

    @Test
    fun unknownCommandSuggestsHelp() {
        val out = text(run("say hello"))
        assertTrue(out.contains("不认识"))
    }

    @Test
    fun clearIsHandledByUi() {
        assertTrue(text(run("clear")).contains("清屏"))
    }

    @Test
    fun tokenizerHandlesQuotes() {
        // 引号里的空格属于同一个参数：`props motd="欢迎 来到"` 是 2 个 token
        val tokens = OfflineConsole.tokenize("props motd=\"欢迎 来到\"")
        assertTrue(tokens.size == 2)
        assertTrue(tokens[1] == "motd=欢迎 来到")
        assertTrue(OfflineConsole.tokenize("  ls  ").size == 1)
    }

    @Test
    fun outputIsBounded() {
        // 造一个 500 行的文件：cat 默认只显示 40 行，且提示剩余行数
        File(root, "big.txt").writeText((1..500).joinToString("\n") { "line $it" })
        val out = run("cat big.txt")
        assertTrue(out.size <= OfflineConsole.MAX_OUTPUT_LINES)
        assertTrue(text(out).contains("其余 460 行未显示"))
    }
}
