package com.kaze.newage.ui

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 防回归：**"定义了却没人调用"** 以及 **"启动点踩到 Kotlin 初始化顺序"**。
 *
 * 这个仓库已经栽过两次"定义了却没人调"：`ConsoleStream.snapshot()`（切回正在运行的实例时
 * 控制台空白）、`AppViewModel.startProcStatsPolling()`（控制台永远显示「CPU 采样中…」）。
 * 后者尤其阴：**只在服务端运行中才有可见效果**，单测与截图测试都照不到。
 *
 * 更阴的是修好"没人调用"之后踩的第二个坑：把 `startProcStatsPolling()` 写进了文件上方那个
 * `init`，而 `_procStats` 声明在 441 行 —— Kotlin 的 init 块按声明顺序执行，协程被
 * `Dispatchers.IO` 立刻调度时读到 null 就抛 NPE，循环当场死掉，界面依旧"采样中"。
 * 那次的信号只在 CI 上出现（12 个 UI 测试报 UncaughtExceptionsBeforeTest），本机 aarch64
 * 跑不了 Robolectric 图形测试，完全照不到。
 *
 * 纯 JVM 测试（不需要 Robolectric），任何机器上都能跑。
 */
class ProcStatsWiringTest {

    /** 单测工作目录通常是模块目录（app/），也允许从仓库根跑：往上找几层 */
    private fun sourceFile(relative: String): File {
        var dir: File? = File("").absoluteFile
        repeat(5) {
            val f = File(dir, relative)
            if (f.isFile) return f
            dir = dir?.parentFile
        }
        throw AssertionError("找不到源文件 $relative（当前工作目录 ${File("").absolutePath}）")
    }

    private fun source(): String =
        sourceFile("src/main/java/com/kaze/newage/ui/AppViewModel.kt").readText()

    private fun lineOf(src: String, index: Int): Int = src.take(index).count { it == '\n' } + 1

    @Test
    fun `占用数据轮询被真正启动`() {
        val src = source()
        val hits = Regex("""startProcStatsPolling\(\)""").findAll(src).count()
        assertTrue(
            "startProcStatsPolling() 只出现 $hits 次：函数有定义却没人调用，" +
                "控制台会永远停在「CPU 采样中…」（真机反馈过这个 bug）",
            hits >= 2,
        )
    }

    @Test
    fun `启动点必须排在 _procStats 声明之后`() {
        val src = source()
        val decl = src.indexOf("private val _procStats")
        val launchInit = src.lastIndexOf("init {")
        assertTrue("源码里找不到 `private val _procStats` 声明", decl >= 0)
        assertTrue(
            "_procStats 声明在第 ${lineOf(src, decl)} 行，而启动轮询的 init 在第 " +
                "${lineOf(src, launchInit)} 行 —— Kotlin 的 init 块按声明顺序执行，" +
                "协程会读到还没初始化的 _procStats 抛 NPE，循环当场死掉（界面仍是「采样中」）",
            decl in 0 until launchInit,
        )
    }
}
