package com.mcserver.launcher.server

import android.content.Context
import com.mcserver.launcher.McApplication
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

/**
 * 定时任务管理器。
 * 借鉴 Pterodactyl Schedules 和 MCSManager 的定时任务系统。
 *
 * 支持完整的标准 Cron 表达式（5 字段：分 时 日 月 周），包括：
 * - 通配符 *
 * - 具体数字 0-59
 * - 范围 1-5
 * - 步长: 星号斜线5 或 1-10斜线2
 * - 列表 1,3,5
 * - 混合使用 1,3-5,步长模式
 *
 * 支持的任务类型：
 * - COMMAND: 执行控制台命令
 * - BACKUP: 创建服务器备份
 * - RESTART: 重启服务器
 * - BROADCAST: 广播消息（通过 say 命令）
 * - STOP: 停止服务器
 * - WORLD_SAVE: 执行 save-all
 */
object ScheduleManager {

    private val context: Context get() = McApplication.instance
    private val storageFile: File get() = File(context.filesDir, "schedules.json")
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private var schedulerJob: Job? = null

    enum class TaskType(val label: String, val icon: String) {
        COMMAND("执行命令", "terminal"),
        BACKUP("创建备份", "backup"),
        RESTART("重启服务器", "restart"),
        BROADCAST("广播消息", "campaign"),
        STOP("停止服务器", "stop"),
        WORLD_SAVE("保存世界", "save");

        companion object {
            fun fromName(name: String): TaskType =
                entries.firstOrNull { it.name.equals(name, ignoreCase = true) } ?: COMMAND
        }
    }

    data class ScheduleTask(
        val id: String = UUID.randomUUID().toString().take(8),
        val name: String = "",
        val type: TaskType = TaskType.COMMAND,
        val cronExpression: String = "0 * * * *",
        val payload: String = "",
        val enabled: Boolean = true,
        val lastRun: Long = 0,
        val nextRun: Long = 0,
        val runCount: Int = 0,
        val createdAt: Long = System.currentTimeMillis()
    )

    private val _tasks = mutableListOf<ScheduleTask>()
    val tasks: List<ScheduleTask> get() = _tasks.toList()

    /** Cron 字段解析器 — 将单个字段值解析为匹配器 */
    private fun parseCronField(field: String, min: Int, max: Int): (Int) -> Boolean {
        val values = mutableSetOf<Int>()

        // 处理逗号分隔的多个表达式
        field.split(",").forEach { part ->
            val trimmed = part.trim()
            when {
                trimmed == "*" -> {
                    for (i in min..max) values.add(i)
                }
                trimmed.contains("/") -> {
                    // 步长：*/5 或 1-10/2 或 5/2
                    val (rangeStr, stepStr) = trimmed.split("/", limit = 2)
                    val step = stepStr.toIntOrNull() ?: 1
                    val (start, end) = if (rangeStr == "*") {
                        min to max
                    } else if (rangeStr.contains("-")) {
                        val parts = rangeStr.split("-", limit = 2)
                        (parts[0].toIntOrNull() ?: min) to (parts[1].toIntOrNull() ?: max)
                    } else {
                        val v = rangeStr.toIntOrNull() ?: min
                        v to max
                    }
                    for (i in start..end step step) {
                        if (i in min..max) values.add(i)
                    }
                }
                trimmed.contains("-") -> {
                    val parts = trimmed.split("-", limit = 2)
                    val start = parts[0].toIntOrNull() ?: min
                    val end = parts[1].toIntOrNull() ?: max
                    for (i in start..end) {
                        if (i in min..max) values.add(i)
                    }
                }
                else -> {
                    trimmed.toIntOrNull()?.let { if (it in min..max) values.add(it) }
                }
            }
        }
        return { value -> value in values }
    }

    /** 编译 cron 表达式为匹配函数 */
    private fun compileCron(cron: String): ((Int, Int, Int, Int, Int) -> Boolean)? {
        val parts = cron.trim().split("\\s+".toRegex())
        if (parts.size < 5) return null

        return try {
            val minuteMatcher = parseCronField(parts[0], 0, 59)
            val hourMatcher = parseCronField(parts[1], 0, 23)
            val dayMatcher = parseCronField(parts[2], 1, 31)
            val monthMatcher = parseCronField(parts[3], 1, 12)
            val weekdayMatcher = parseCronField(parts[4], 0, 7)

            object : (Int, Int, Int, Int, Int) -> Boolean {
                override fun invoke(minute: Int, hour: Int, day: Int, month: Int, weekday: Int): Boolean {
                    return minuteMatcher(minute) && hourMatcher(hour) &&
                        dayMatcher(day) && monthMatcher(month) &&
                        (weekdayMatcher(weekday) || (weekday == 0 && weekdayMatcher(7)) || (weekday == 7 && weekdayMatcher(0)))
                }
            }
        } catch (_: Exception) {
            null
        }
    }

    /** 加载持久化的定时任务 */
    fun load() {
        try {
            if (!storageFile.exists()) return
            val json = JSONArray(storageFile.readText())
            _tasks.clear()
            for (i in 0 until json.length()) {
                val obj = json.getJSONObject(i)
                _tasks.add(ScheduleTask(
                    id = obj.optString("id", UUID.randomUUID().toString().take(8)),
                    name = obj.optString("name", ""),
                    type = TaskType.fromName(obj.optString("type", "COMMAND")),
                    cronExpression = obj.optString("cron", "0 * * * *"),
                    payload = obj.optString("payload", ""),
                    enabled = obj.optBoolean("enabled", true),
                    lastRun = obj.optLong("lastRun", 0),
                    nextRun = obj.optLong("nextRun", 0),
                    runCount = obj.optInt("runCount", 0),
                    createdAt = obj.optLong("createdAt", System.currentTimeMillis())
                ))
            }
        } catch (_: Exception) {
            _tasks.clear()
        }
    }

    /** 保存定时任务到文件 */
    private fun save() {
        try {
            val json = JSONArray()
            _tasks.forEach { task ->
                json.put(JSONObject().apply {
                    put("id", task.id)
                    put("name", task.name)
                    put("type", task.type.name)
                    put("cron", task.cronExpression)
                    put("payload", task.payload)
                    put("enabled", task.enabled)
                    put("lastRun", task.lastRun)
                    put("nextRun", task.nextRun)
                    put("runCount", task.runCount)
                    put("createdAt", task.createdAt)
                })
            }
            storageFile.writeText(json.toString(2))
        } catch (_: Exception) {}
    }

    /** 添加一个定时任务 */
    fun addTask(task: ScheduleTask): ScheduleTask {
        val newTask = task.copy(
            id = UUID.randomUUID().toString().take(8),
            createdAt = System.currentTimeMillis()
        )
        _tasks.add(newTask)
        save()
        recalculateNextRuns()
        return newTask
    }

    /** 更新定时任务 */
    fun updateTask(taskId: String, transform: (ScheduleTask) -> ScheduleTask) {
        val index = _tasks.indexOfFirst { it.id == taskId }
        if (index >= 0) {
            _tasks[index] = transform(_tasks[index])
            save()
            recalculateNextRuns()
        }
    }

    /** 删除定时任务 */
    fun deleteTask(taskId: String) {
        _tasks.removeAll { it.id == taskId }
        save()
    }

    /** 立即执行一次任务 */
    fun runTaskNow(taskId: String) {
        val task = _tasks.firstOrNull { it.id == taskId } ?: return
        executeTask(task)
        updateTask(taskId) {
            it.copy(lastRun = System.currentTimeMillis(), runCount = it.runCount + 1)
        }
        recalculateNextRuns()
    }

    /** 启用/禁用定时任务 */
    fun toggleTask(taskId: String) {
        updateTask(taskId) { it.copy(enabled = !it.enabled) }
    }

    /** 启动调度器 */
    fun startScheduler() {
        load()
        schedulerJob?.cancel()
        recalculateNextRuns()
        schedulerJob = scope.launch {
            while (isActive) {
                try {
                    checkAndExecuteTasks()
                } catch (_: Exception) {}
                delay(15000) // 每 15 秒检查一次（更精确）
            }
        }
    }

    /** 停止调度器 */
    fun stopScheduler() {
        schedulerJob?.cancel()
        schedulerJob = null
    }

    /** 检查并执行到期任务 */
    private suspend fun checkAndExecuteTasks() {
        val now = System.currentTimeMillis()
        val calendar = Calendar.getInstance().apply { timeInMillis = now }
        val minute = calendar.get(Calendar.MINUTE)
        val hour = calendar.get(Calendar.HOUR_OF_DAY)
        val day = calendar.get(Calendar.DAY_OF_MONTH)
        val month = calendar.get(Calendar.MONTH) + 1
        val weekday = calendar.get(Calendar.DAY_OF_WEEK) - 1 // 0=Sunday

        for (task in _tasks) {
            if (!task.enabled) continue

            val matcher = compileCron(task.cronExpression) ?: continue
            if (!matcher(minute, hour, day, month, weekday)) continue

            // 防止同一分钟内重复执行
            if (task.lastRun > 0 && (now - task.lastRun) < 55000) continue

            // 执行任务
            executeTask(task)
            updateTask(task.id) {
                it.copy(lastRun = now, runCount = it.runCount + 1)
            }
        }
    }

    /** 执行单个任务 */
    private fun executeTask(task: ScheduleTask) {
        val serverManager = ServerManager.instance
        // 使用 ServerManager 的 ProotServerManager 实例，确保命令能到达运行中的服务器
        val server = serverManager.prootServerManager

        try {
            when (task.type) {
                TaskType.COMMAND -> {
                    if (task.payload.isNotBlank()) {
                        server.sendCommand(task.payload)
                        server.notifyConsole("> [定时任务:${task.name}] 执行命令: ${task.payload}")
                    }
                }
                TaskType.BACKUP -> {
                    server.notifyConsole("> [定时任务:${task.name}] 开始自动备份...")
                    CoroutineScope(Dispatchers.IO).launch {
                        BackupManager.createBackup("auto_${task.id.take(4)}").onSuccess { path ->
                            server.notifyConsole("> [定时任务:${task.name}] 备份完成: $path")
                        }.onFailure { e ->
                            server.notifyConsole("> [定时任务:${task.name}] 备份失败: ${e.message}")
                        }
                    }
                }
                TaskType.RESTART -> {
                    if (serverManager.isRunning) {
                        server.notifyConsole("> [定时任务:${task.name}] 执行定时重启")
                        if (task.payload.isNotBlank()) {
                            server.sendCommand("say ${task.payload}")
                        }
                        server.sendCommand("say §e[定时任务] 服务器将在 30 秒后重启")
                        CoroutineScope(Dispatchers.IO).launch {
                            delay(30000)
                            server.sendCommand("save-all")
                            delay(5000)
                            serverManager.stopServer()
                            delay(5000)
                            server.notifyConsole("> [定时任务:${task.name}] 服务器已停止，如需自动启动请开启自动重启")
                        }
                    }
                }
                TaskType.BROADCAST -> {
                    if (task.payload.isNotBlank() && serverManager.isRunning) {
                        server.sendCommand("say §b[定时] ${task.payload}")
                    }
                }
                TaskType.STOP -> {
                    if (serverManager.isRunning) {
                        server.notifyConsole("> [定时任务:${task.name}] 执行定时停止")
                        if (task.payload.isNotBlank()) {
                            server.sendCommand("say ${task.payload}")
                        }
                        server.sendCommand("save-all")
                        CoroutineScope(Dispatchers.IO).launch {
                            delay(5000)
                            serverManager.stopServer()
                        }
                    }
                }
                TaskType.WORLD_SAVE -> {
                    if (serverManager.isRunning) {
                        server.sendCommand("save-all")
                        server.notifyConsole("> [定时任务:${task.name}] 已执行世界保存")
                    }
                }
            }
        } catch (e: Exception) {
            server.notifyConsole("> [定时任务:${task.name}] 执行失败: ${e.message}")
        }
    }

    /** 重新计算所有任务的下次执行时间 */
    private fun recalculateNextRuns() {
        val now = System.currentTimeMillis()
        _tasks.forEachIndexed { index, task ->
            if (task.enabled) {
                _tasks[index] = task.copy(nextRun = calculateNextRun(task.cronExpression, now))
            } else {
                _tasks[index] = task.copy(nextRun = 0)
            }
        }
        save()
    }

    /** 计算 cron 表达式的下次执行时间 */
    private fun calculateNextRun(cron: String, from: Long): Long {
        return try {
            val matcher = compileCron(cron) ?: return 0
            val calendar = Calendar.getInstance().apply { timeInMillis = from }

            // 从当前分钟开始，向前搜索最多 365 天
            calendar.set(Calendar.SECOND, 0)
            calendar.set(Calendar.MILLISECOND, 0)
            calendar.add(Calendar.MINUTE, 1) // 从下一分钟开始

            val maxIterations = 525600 // 365天 * 24小时 * 60分钟
            for (i in 0 until maxIterations) {
                val minute = calendar.get(Calendar.MINUTE)
                val hour = calendar.get(Calendar.HOUR_OF_DAY)
                val day = calendar.get(Calendar.DAY_OF_MONTH)
                val month = calendar.get(Calendar.MONTH) + 1
                val weekday = calendar.get(Calendar.DAY_OF_WEEK) - 1

                if (matcher(minute, hour, day, month, weekday)) {
                    return calendar.timeInMillis
                }
                calendar.add(Calendar.MINUTE, 1)
            }
            0 // 未找到
        } catch (_: Exception) {
            0
        }
    }

    /** 格式化最后运行时间 */
    fun formatLastRun(timestamp: Long): String {
        if (timestamp <= 0) return "从未执行"
        val now = System.currentTimeMillis()
        val diff = now - timestamp
        return when {
            diff < 60000 -> "刚刚"
            diff < 3600000 -> "${diff / 60000} 分钟前"
            diff < 86400000 -> "${diff / 3600000} 小时前"
            else -> SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()).format(Date(timestamp))
        }
    }

    /** 格式化下次运行时间 */
    fun formatNextRun(timestamp: Long): String {
        if (timestamp <= 0) return "—"
        return SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()).format(Date(timestamp))
    }

    /** 获取 cron 表达式的可读描述 */
    fun describeCron(cron: String): String {
        val parts = cron.trim().split("\\s+".toRegex())
        if (parts.size < 5) return cron

        val minute = parts[0]
        val hour = parts[1]
        val day = parts[2]
        val month = parts[3]
        val weekday = parts[4]

        return when {
            // 每分钟
            minute == "*" && hour == "*" && day == "*" && month == "*" && weekday == "*" -> "每分钟"
            // 每小时
            minute != "*" && hour == "*" && day == "*" && month == "*" && weekday == "*" -> {
                val minDesc = describeField(minute, "分")
                "每小时${minDesc}"
            }
            // 每天
            hour != "*" && day == "*" && month == "*" && weekday == "*" -> {
                val hourDesc = describeField(hour, "时")
                val minDesc = if (minute != "*") describeField(minute, "分") else ""
                "每天 $hourDesc$minDesc"
            }
            // 每周
            weekday != "*" && day == "*" -> {
                val weekdayDesc = describeWeekday(weekday)
                val timeDesc = buildTimeDesc(hour, minute)
                "每$weekdayDesc $timeDesc"
            }
            // 每月
            day != "*" && month == "*" -> {
                val dayDesc = describeField(day, "日")
                val timeDesc = buildTimeDesc(hour, minute)
                "每月${dayDesc} $timeDesc"
            }
            // 特定月份
            month != "*" -> {
                val monthDesc = describeMonth(month)
                val dayDesc = if (day != "*") describeField(day, "日") else ""
                val timeDesc = buildTimeDesc(hour, minute)
                "${monthDesc}${dayDesc} $timeDesc"
            }
            else -> cron
        }
    }

    private fun describeField(field: String, suffix: String): String {
        return when {
            field.startsWith("*/") -> {
                val step = field.removePrefix("*/")
                "每${step}$suffix"
            }
            field == "*" -> ""
            else -> field
        }
    }

    private fun buildTimeDesc(hourField: String, minuteField: String): String {
        val h = if (hourField == "*") "0" else hourField
        val m = if (minuteField == "*") "0" else minuteField
        return try {
            "${h.toInt().toString().padStart(2, '0')}:${m.toInt().toString().padStart(2, '0')}"
        } catch (_: Exception) { "$h:$m" }
    }

    private fun describeWeekday(field: String): String {
        val weekdays = mapOf(
            0 to "周日", 7 to "周日",
            1 to "周一", 2 to "周二", 3 to "周三",
            4 to "周四", 5 to "周五", 6 to "周六"
        )
        val num = field.toIntOrNull()
        return weekdays[num] ?: field
    }

    private fun describeMonth(field: String): String {
        val num = field.toIntOrNull()
        return if (num != null) "${num}月" else field
    }

    /** 验证 cron 表达式是否有效 */
    fun validateCron(cron: String): Boolean {
        return compileCron(cron) != null
    }

    /** 生成常用的 cron 预设 */
    fun getCronPresets(): List<Pair<String, String>> = listOf(
        "*/5 * * * *" to "每5分钟",
        "*/30 * * * *" to "每30分钟",
        "0 * * * *" to "每小时",
        "0 */2 * * *" to "每2小时",
        "0 */6 * * *" to "每6小时",
        "0 */12 * * *" to "每12小时",
        "0 0 * * *" to "每天凌晨",
        "0 3 * * *" to "每天凌晨3点",
        "0 6 * * *" to "每天早晨6点",
        "0 12 * * *" to "每天中午12点",
        "0 18 * * *" to "每天傍晚6点",
        "0 0 * * 0" to "每周日凌晨",
        "0 3 * * 0" to "每周日凌晨3点",
        "0 0 1 * *" to "每月1号凌晨",
        "0 0 15 * *" to "每月15号凌晨"
    )
}
