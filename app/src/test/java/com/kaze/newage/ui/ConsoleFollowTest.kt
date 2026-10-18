package com.kaze.newage.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import com.kaze.newage.NewAgeApp
import com.kaze.newage.data.model.CoreType
import com.kaze.newage.data.model.ServerInstance
import com.kaze.newage.ui.theme.AppThemeMode
import com.kaze.newage.ui.theme.NewAgeTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * 控制台的"跟随最新一行"行为测试（Robolectric + Compose Test，不需要设备）。
 *
 * 起因（用户实报）：服务端运行中，从控制台切到别的页面再切回来，日志停在旧位置，
 * 得自己往下翻；而且输出一直在来，怎么翻都追不上。
 *
 * 根因是 `LaunchedEffect(lines.size)`：lines.size 每来一行就变一次，**每次变化都会取消
 * 上一个 effect**，而 animateScrollToItem 是持续多帧的动画，被取消就停在半路。
 * 平时"人已经在底部、只差 1 行"看不出来；从别的页面切回来差了几百行，动画永远走不完。
 *
 * 关键：断言"最新那一行是否在屏幕上"。LazyColumn 只组合可见项，所以能取到最新行
 * 就等于确实停在底部 —— 这比断言滚动态更难被误判。
 *
 * ## 偶发失败：Detected multithreaded access to SnapshotStateObserver
 *
 * 旧版本用 `thread { emit(...) }` 在**后台线程**里往控制台写日志，主线程同时用
 * `composeRule.waitForIdle()` 抽帧 —— 两个线程先后读取同一个 `SnapshotStateObserver`，
 * Compose 就抛 `IllegalArgumentException: Detected multithreaded access to
 * SnapshotStateObserver: previousThreadId=97, currentThread=Main`。
 *
 * 这是**测试自己制造**的线程冲突，不是生产代码的问题：真实运行时控制台的写入来自
 * `Dispatchers.IO`，界面读取全在主线程，且 AppViewModel 用的是线程安全的
 * `MutableStateFlow` + 80ms 批量发布，不会出现"后台线程直接驱动 Compose 组合"。
 * （曾试图把 AppViewModel 的状态发布整体切到主线程，结果 Robolectric 下
 * `Dispatchers.Main.immediate` 调度不可靠，行几乎到不了状态里，测试全红 —— 已回滚。
 * 修 flake 不该动生产线程模型。）
 *
 * 所以这里改成：**所有 emit 都在测试线程上同步完成**，再用 [awaitLines] 等 ViewModel
 * 的收集协程把行搬进状态。"边输出边切页"的语义仍然保留（日志分批写入，中间跨过
 * 不在控制台页的窗口）。
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w411dp-h891dp-xhdpi")
class ConsoleFollowTest {

    @get:Rule
    val composeRule = createComposeRule()

    private lateinit var vm: AppViewModel
    private lateinit var instance: ServerInstance

    private fun setUpApp() {
        vm = AppViewModel(ApplicationProvider.getApplicationContext<NewAgeApp>())
        val dir = vm.instanceStore.createInstanceDir("控制台测试服")
        instance = ServerInstance(
            name = "控制台测试服",
            coreType = CoreType.VANILLA,
            mcVersion = "1.21.4",
            dir = dir,
        )
        vm.instanceStore.add(instance)
        vm.selectInstance(instance)

        composeRule.setContent {
            NewAgeTheme(mode = AppThemeMode.M3, darkTheme = true, colorSource = "custom") {
                AppRoot(viewModel = vm)
            }
        }
        composeRule.waitForIdle()
    }

    /**
     * 直接往该实例的控制台流里写日志（等价于服务端输出）。
     *
     * 刻意**在调用线程上同步写**，不开后台线程 —— 见类注释里关于
     * SnapshotStateObserver 多线程访问的说明。
     */
    private fun emit(range: IntRange) {
        val stream = vm.serverManager.consoleFor(instance.id)
        for (i in range) stream.emit("日志行 $i")
    }

    private fun clickTab(label: String) {
        composeRule.onAllNodesWithContentDescription(label).onFirst().performClick()
        composeRule.waitForIdle()
    }

    /**
     * 等到 ViewModel 真正收到第 n 行。
     *
     * 刻意**不用** composeRule.waitUntil：它依赖 Compose 的 idle 判定与虚拟时钟，
     * CI 机器上曾因此稳定超时（本地却通过）—— 而这里等的其实是 Dispatchers.IO 上的
     * 收集协程，需要真实时间流逝。用"抽一帧 + sleep"的轮询，语义直白且不挑环境。
     */
    private fun awaitLines(n: Int) {
        val deadline = System.currentTimeMillis() + 30_000
        while (vm.consoleLines.value.size < n && System.currentTimeMillis() < deadline) {
            composeRule.waitForIdle()
            Thread.sleep(10)
        }
        composeRule.waitForIdle()
        check(vm.consoleLines.value.size >= n) {
            "只收到 ${vm.consoleLines.value.size} 行，期望至少 $n 行"
        }
    }

    @Test
    fun `进控制台自动停在最新一行`() {
        setUpApp()
        emit(1..120)
        clickTab("控制台")
        awaitLines(120)
        composeRule.onNodeWithText("日志行 120").assertIsDisplayed()
    }

    @Test
    fun `切走再切回来仍停在最新一行（期间持续输出）`() {
        setUpApp()
        emit(1..120)
        clickTab("控制台")
        awaitLines(120)
        composeRule.onNodeWithText("日志行 120").assertIsDisplayed()

        // 切到别的页面
        clickTab("主页")

        // 服务端在后台继续刷日志：分两批写，中间跨过"不在控制台页"的窗口，
        // 等价于旧版本用后台线程持续输出 —— 但不再引入第二个线程碰 Compose。
        emit(121..260)
        awaitLines(260)
        emit(261..400)

        clickTab("控制台")
        awaitLines(400)

        // 停在底部 ⇒ 最新那一行必须在屏幕上
        composeRule.onNodeWithText("日志行 400").assertIsDisplayed()
    }

    @Test
    fun `暂停跟随后新日志不该把视图拽回底部`() {
        setUpApp()
        emit(1..120)
        clickTab("控制台")
        awaitLines(120)

        // 用户上滑（等价于关掉跟随）：这里直接点"跟随"开关
        composeRule.onAllNodesWithContentDescription("暂停自动滚动").onFirst().performClick()
        composeRule.waitForIdle()

        emit(121..200)
        awaitLines(200)

        // 关掉跟随后，最新的第 200 行不应被自动滚进视野。
        // LazyColumn 不组合屏幕外的行，所以"取不到"就等于"确实没被滚到"。
        composeRule.onNodeWithText("日志行 200").assertDoesNotExist()
    }
}
