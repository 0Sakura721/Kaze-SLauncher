package com.kaze.newage.core.monitor

import java.io.File

/**
 * 读服务端进程的 CPU / 内存占用（都带百分比）。
 *
 * ## 数据来源
 * 服务端跑在 proot 里，是应用进程的**孙进程**（app → proot → java），拿不到它的 `Process`
 * 句柄，所以按 pid 直接读内核：
 *  - `/proc/<pid>/stat`  → `utime + stime`（单位 tick，USER_HZ=100，即 1 tick = 10ms）
 *  - `/proc/<pid>/status` → `VmRSS`（kB，实际驻留内存）
 *  - `/proc/<pid>/cmdline` → 用来**认出**哪个 pid 是这台实例的服务端
 *
 * ## CPU 百分比怎么算
 * CPU 占用必须**两次采样求差**（累计 tick 本身没有意义）：
 * ```
 * 单核占用率 = Δtick / (Δt秒 × 100)          // 100 tick/秒 = 占满一个核
 * 整机占用率 = 单核占用率 / 核数 × 100
 * ```
 * 服务端是多线程的，单核占用率**可以超过 100%**（比如 250% = 吃满 2.5 个核）。
 * 用户看到的是"整机占用率"（0~100），同时把核数与"用了几个核"一起给出，
 * 否则一个 8 核机器上 12% 会让人以为很闲、其实是吃满了 1 个核。
 *
 * 纯函数全部拆出来单独测：`/proc` 的格式细节（字段里有空格、进程名带括号）很容易写错。
 */
object ProcessStats {

    /** 一次采样结果 */
    data class Reading(
        /** 整机 CPU 占用率（0~100，已按核数折算） */
        val cpuPercent: Float,
        /** 相当于占满几个核（可以 >1） */
        val coresUsed: Float,
        /** 常驻内存（KB） */
        val rssKb: Long,
    )

    /**
     * 解析 `/proc/<pid>/stat` 里累计的 CPU tick（utime + stime）。
     *
     * ⚠️ 第 2 个字段是**进程名**，可能含空格与括号（如 `(java) (1)`），
     * 所以不能按空格 split —— 必须从**最后一个 ')' 之后**再切。
     */
    fun parseCpuTicks(stat: String): Long? {
        val close = stat.lastIndexOf(')')
        if (close < 0 || close + 2 >= stat.length) return null
        val rest = stat.substring(close + 2).trim().split(Regex("\\s+"))
        // ')' 之后依次是 state(0) ppid(1) pgrp(2) session(3) tty(4) tpgid(5) flags(6)
        // minflt(7) cminflt(8) majflt(9) cmajflt(10) utime(11) stime(12)
        if (rest.size < 13) return null
        val utime = rest[11].toLongOrNull() ?: return null
        val stime = rest[12].toLongOrNull() ?: return null
        return utime + stime
    }

    /** 解析 `/proc/<pid>/status` 里的 `VmRSS:`（kB） */
    fun parseVmRssKb(status: String): Long? {
        for (line in status.lineSequence()) {
            if (line.startsWith("VmRSS:")) {
                return Regex("\\d+").find(line)?.value?.toLongOrNull()
            }
        }
        return null
    }

    /**
     * 由两次采样算占用。
     *
     * @param prevTicks 上次累计 tick；[ticks] 本次
     * @param elapsedMs 两次采样的间隔（毫秒）
     * @param cores 设备核数（用于折算整机占用率；<=0 时按 1 处理）
     */
    fun cpuFrom(prevTicks: Long, ticks: Long, elapsedMs: Long, cores: Int): Pair<Float, Float> {
        if (elapsedMs <= 0L) return 0f to 0f
        val deltaTicks = (ticks - prevTicks).coerceAtLeast(0L)
        val elapsedSec = elapsedMs / 1000.0
        // 100 tick/秒 = 占满一个核
        val coresUsed = (deltaTicks / (100.0 * elapsedSec)).toFloat()
        val total = if (cores <= 0) 1 else cores
        val percent = (coresUsed / total * 100f).coerceIn(0f, 100f)
        return percent to coresUsed
    }

    /** 读一次累计 tick + VmRSS；进程不在了返回 null */
    fun sample(pid: Int): Pair<Long, Long>? {
        val statFile = File("/proc/$pid/stat")
        val statusFile = File("/proc/$pid/status")
        if (!statFile.canRead()) return null
        val ticks = runCatching { statFile.readText() }.getOrNull()?.let { parseCpuTicks(it) } ?: return null
        val rss = runCatching { statusFile.readText() }.getOrNull()?.let { parseVmRssKb(it) } ?: 0L
        return ticks to rss
    }

    /**
     * 找出这台实例的服务端 pid。
     *
     * 服务端是 proot 的子进程，句柄拿不到，只能扫 `/proc`：
     * 谁的 cmdline 里同时出现 `java` 与这台实例的目录名，谁就是。
     * 扫不到返回 null（未启动 / 无权限 / 已经退出）。
     */
    /**
     * 上一次扫描的计数，给界面显示用。
     *
     * 真机反馈"一直采样中"时，光看"采样中"没法判断是**匹配规则**不对，还是
     * `/proc/<别的 pid>` **根本读不到** —— 这两件事的修法完全不同。
     * 把候选数 / 可读 cmdline 数 / java 命中数 / 后代数摆在界面上，
     * 用户截一张图就能定位，不必连 adb。
     */
    @Volatile
    var lastDiag: String = ""

    fun findServerPid(instanceDirName: String): Int? {
        val proc = File("/proc")
        val candidates = proc.listFiles { f -> f.isDirectory && f.name.all { it.isDigit() } }
            ?: return null
        val myPid = android.os.Process.myPid()
        val mine = ArrayList<Pair<Int, String>>()   // 后代里的 java
        val anyJava = ArrayList<Pair<Int, String>>()  // 兜底：所有 java
        var dirs = 0
        var readableCmdline = 0
        var unreadable = 0
        for (dir in candidates) {
            val pid = dir.name.toIntOrNull() ?: continue
            dirs++
            // 读不到 cmdline（Android 对 /proc/<别的 pid>/cmdline 有限制、
            // 或 proot 屏蔽）与"读到了但不是 java"是两回事，分开计数
            val cmdline = runCatching { File(dir, "cmdline").readBytes() }.getOrNull()
            if (cmdline == null) { unreadable++; continue }
            if (cmdline.isEmpty()) continue
            readableCmdline++
            val text = String(cmdline, Charsets.UTF_8).replace('\u0000', ' ')
            if (!text.contains("java")) continue
            anyJava.add(pid to text)
            if (isDescendant(pid, myPid)) mine.add(pid to text)
        }
        // ① 优先：**自己进程的后代**里的 java。
        //    服务端是 app → proot → java，所以 java 一定是我们的后代。
        //    这一条比"cmdline 里带实例目录名"可靠得多 —— proot 会把子进程 cmdline 里的
        //    路径改写成 /mnt/...，实例目录名（Forge-26.3 之类）根本不出现，
        //    旧版就是因此永远匹配不到、这一行数据从来不显示。
        // ② 其中若有人真的带实例目录名，就是它。
        // ③ 否则取 RSS 最大的那个（服务端是这里面最重的进程）。
        lastDiag = "pid目录 $dirs · 可读 $readableCmdline · 读不到 $unreadable · java ${
            anyJava.size
        } · 后代 ${
            mine.size
        } · 自己 $myPid"
        for ((pid, text) in mine) if (text.contains(instanceDirName)) return pid
        if (mine.isNotEmpty()) return mine.maxByOrNull { rssKb(it.first) }?.first
        for ((pid, text) in anyJava) if (instanceDirName.isNotBlank() && text.contains(instanceDirName)) return pid
        return anyJava.maxByOrNull { rssKb(it.first) }?.first
    }

    /**
     * [pid] 是不是 [root] 的后代 —— 顺着 `/proc/<pid>/stat` 的第 4 个字段（ppid）往上爬。
     * 用于在 proot 下认出服务端 java：路径会被 proot 改写，但父子关系不会。
     */
    private fun isDescendant(pid: Int, root: Int): Boolean {
        var cur = pid
        repeat(16) {   // 防环 / 防异常深度
            if (cur <= 1) return false
            val ppid = runCatching {
                File("/proc/$cur/stat").readText()
                    .substringAfterLast(')')   // 进程名可能带空格与括号，得从最后一个 ')' 之后切
                    .trim().split(' ').getOrNull(1)?.toIntOrNull()
            }.getOrNull() ?: return false
            if (ppid == root) return true
            if (ppid == cur) return false
            cur = ppid
        }
        return false
    }

    /** 单进程 RSS（KB）；读不到返回 -1（排序用，不进 UI） */
    private fun rssKb(pid: Int): Long =
        runCatching { File("/proc/$pid/status").readText() }.getOrNull()
            ?.let { parseVmRssKb(it) } ?: -1L

    /** 设备核数（用来把"用了几个核"折算成整机占用率） */
    val cores: Int = Runtime.getRuntime().availableProcessors().coerceAtLeast(1)

    /** 设备总内存（KB），用于算内存百分比 */
    fun totalMemKb(context: android.content.Context): Long {
        val mi = android.app.ActivityManager.MemoryInfo()
        (context.getSystemService(android.content.Context.ACTIVITY_SERVICE) as android.app.ActivityManager).getMemoryInfo(mi)
        return mi.totalMem / 1024
    }
}
