package com.kaze.newage.ui

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 防回归：**"定义了却没人调用"**。
 *
 * 这个仓库已经栽过两次：`ConsoleStream.snapshot()`（切回正在运行的实例时控制台空白）、
 * 以及 `AppViewModel.startProcStatsPolling()`（控制台永远显示「CPU 采样中…」）。
 * 后者尤其阴：它**只在服务端运行中才有可见效果**，单测与截图测试都照不到，
 * 所以上一次的"修复"改了 pid 识别逻辑却始终没启动循环，真机上一直是采样中。
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

    @Test
    fun `占用数据轮询被真正启动`() {
        val src = sourceFile("src/main/java/com/kaze/newage/ui/AppViewModel.kt").readText()
        val hits = Regex("""startProcStatsPolling\(\)""").findAll(src).count()
        assertTrue(
            "startProcStatsPolling() 只出现 $hits 次：函数有定义却没人调用，" +
                "控制台会永远停在「CPU 采样中…」（真机反馈过这个 bug）",
            hits >= 2,
        )
    }
}
