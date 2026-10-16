package com.kaze.newage.core.mcp

import com.kaze.newage.core.console.ConsoleParser
import com.kaze.newage.core.server.BackupManager
import com.kaze.newage.core.server.ServerManager
import com.kaze.newage.core.server.ServerProperties
import com.kaze.newage.core.server.ServerState
import com.kaze.newage.data.model.ServerInstance
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * MCP 工具集：把服务端管理器的原语翻译成外部 AI 可调用的工具。
 *
 * 与内置 AI 助手的分工：这里的每个工具都**没有确认卡**——请求方是用户自己配置的
 * 外部 AI 客户端（Claude / Cursor 等），信任边界在「访问令牌」那一层（见 [McpServer]），
 * 不在每个工具调用上再拦一道。代价是令牌必须保管好，这也是设置页把它列为必配项的原因。
 *
 * 实例寻址支持 id 或实例名（AI 跟着 `list_instances` 的输出走，两者都能命中），
 * 命中不了时明确报错而不是静默挑第一个。
 */
class McpTools(
    private val serverManager: ServerManager,
    private val instanceById: (String) -> ServerInstance?,
    private val allInstances: () -> List<ServerInstance>,
    /**
     * 长任务（启动/停止/重启）的执行作用域，必须是**应用级**的（容器里的 appScope）：
     * 启动链路动辄数分钟，若在 HTTP 请求线程上同步执行，外部客户端 30~60 秒就超时，
     * 还会占死 worker 线程池让后续 get_server_status / read_console 也无响应。
     */
    private val taskScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
) {

    /** 工具执行结果：文本直接回给模型（结构化数据用 JSON 文本） */
    class ToolResult(val text: String, val isError: Boolean = false)

    class Tool(
        val name: String,
        val description: String,
        val inputSchema: JsonObject,
        val handler: suspend (JsonObject) -> ToolResult,
    )

    val tools: List<Tool> = listOf(
        Tool(
            name = "list_instances",
            description = "列出全部服务端实例（id、名称、核心、版本、状态、端口、运行时长）。后续工具用 id 或实例名寻址。",
            inputSchema = objSchema(),
        ) { listInstances() },
        Tool(
            name = "get_server_status",
            description = "查询单个实例的运行状态（状态、端口、内存、目录、运行时长等）。",
            inputSchema = objSchema(props(instanceIdProp()), listOf("instanceId")),
        ) { args -> serverStatus(args) },
        Tool(
            name = "start_server",
            description = "启动服务端实例（异步：提交后立即返回；含自动装 Java、接受 EULA 等准备流程，可能耗时数分钟，用 get_server_status 跟进）。",
            inputSchema = objSchema(props(instanceIdProp()), listOf("instanceId")),
        ) { args -> startServer(args) },
        Tool(
            name = "stop_server",
            description = "停止服务端实例。默认优雅停止（发 stop 等世界保存完，异步提交立即返回）；force=true 立即强制终止（同步，约 5 秒）。",
            inputSchema = objSchema(
                props(instanceIdProp(), "force" to boolProp("是否强制终止（默认 false 优雅停止）")),
                listOf("instanceId"),
            ),
        ) { args -> stopServer(args) },
        Tool(
            name = "restart_server",
            description = "重启服务端实例（异步：提交后立即返回；先优雅停止，停稳后自动再启动，等停最多 120 秒）。用 get_server_status 跟进。",
            inputSchema = objSchema(props(instanceIdProp()), listOf("instanceId")),
        ) { args -> restartServer(args) },
        Tool(
            name = "send_command",
            description = "向运行中的服务端发送一条控制台命令（如 stop、op <玩家>、say <消息>、list）。",
            inputSchema = objSchema(
                props(instanceIdProp(), "command" to strProp("要发送的控制台命令")),
                listOf("instanceId", "command"),
            ),
        ) { args -> sendCommand(args) },
        Tool(
            name = "read_console",
            description = "读取实例控制台的最近输出（从缓冲末尾取，最新在最后）。",
            inputSchema = objSchema(
                props(instanceIdProp(), "lines" to intProp("读取行数（1–500，默认 100）", 100)),
                listOf("instanceId"),
            ),
        ) { args -> readConsole(args) },
        Tool(
            name = "list_players",
            description = "列出当前在线玩家（由 list 响应与加入/离开事件推算）。",
            inputSchema = objSchema(props(instanceIdProp()), listOf("instanceId")),
        ) { args -> listPlayers(args) },
        Tool(
            name = "get_server_properties",
            description = "读取实例的 server.properties 全部配置项（键值 JSON）。",
            inputSchema = objSchema(props(instanceIdProp()), listOf("instanceId")),
        ) { args -> getProperties(args) },
        Tool(
            name = "set_server_property",
            description = "修改 server.properties 的一项配置（重启服务端后生效）。",
            inputSchema = objSchema(
                props(
                    instanceIdProp(),
                    "key" to strProp("配置键（如 max-players、gamemode、motd）"),
                    "value" to strProp("配置值"),
                ),
                listOf("instanceId", "key", "value"),
            ),
        ) { args -> setProperty(args) },
        Tool(
            name = "create_backup",
            description = "为实例创建一份完整备份（世界与配置打包到备份目录）。",
            inputSchema = objSchema(props(instanceIdProp()), listOf("instanceId")),
        ) { args -> createBackup(args) },
    )

    /** 按名字分发工具调用；工具抛出的异常统一包装成 isError 结果，不外泄栈 */
    suspend fun call(name: String, args: JsonObject): ToolResult {
        val tool = tools.firstOrNull { it.name == name }
            ?: return ToolResult("未知工具：$name（可用工具见 tools/list）", true)
        return try {
            tool.handler(args)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            ToolResult("执行 ${name} 失败：${e.message ?: e::class.java.simpleName}", true)
        }
    }

    // ── 工具实现 ──

    private fun listInstances(): ToolResult {
        val arr = buildJsonArray {
            allInstances().forEach { add(instanceSummary(it)) }
        }
        return ToolResult(pretty(arr))
    }

    private suspend fun serverStatus(args: JsonObject): ToolResult {
        val i = resolveInstance(args) ?: return instanceNotFound(args)
        val state = serverManager.states.value[i.id] ?: ServerState.Idle
        val info = buildJsonObject {
            instanceSummary(i).forEach { (k, v) -> put(k, v) }
            put("javaMajor", i.javaMajor)
            put("memoryMb", i.memoryMb)
            put("nogui", i.nogui)
            put("autoRestart", i.autoRestart)
            put("autoBackup", i.autoBackup)
            put("dir", i.dir.absolutePath)
        }
        return ToolResult(pretty(info) + "\nstate=${state.name}")
    }

    private fun startServer(args: JsonObject): ToolResult {
        val i = resolveInstance(args) ?: return instanceNotFound(args)
        val state = serverManager.states.value[i.id] ?: ServerState.Idle
        if (state == ServerState.Running || state.isStarting()) {
            return ToolResult("实例「${i.name}」已处于 ${state.name} 状态，忽略重复启动", true)
        }
        // 启动链路（部署环境/装 Java/装核心）动辄数分钟，绝不能在 HTTP 请求线程上同步等：
        // 外部客户端 30~60 秒就超时断开，还占死 worker 线程池，让后续 get_server_status /
        // read_console 全部排队无响应 —— 表现就是"启动像没发生一样"。丢进应用级作用域跑，
        // 失败原因会写进实例控制台（read_console 可见），状态也会落成 Error。
        taskScope.launch {
            try {
                serverManager.start(i)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // 失败详情已由 DefaultServerManager 写进实例控制台并置 Error 状态
            }
        }
        return ToolResult("已提交启动：${i.name}（后台执行中，完成前状态多为 Starting）。启动是异步过程，可用 get_server_status / read_console 跟进")
    }

    private fun stopServer(args: JsonObject): ToolResult {
        val i = resolveInstance(args) ?: return instanceNotFound(args)
        val force = args.bool("force") == true
        if (force) {
            // 强制终止最多 ~5 秒（SIGTERM → 5 秒 → SIGKILL），同步等没问题
            serverManager.forceStop(i)
            return ToolResult("已强制终止：${i.name}（SIGTERM → 5 秒 → SIGKILL）")
        }
        // 优雅停止要等世界保存完（大世界可能好几分钟），同样后台执行
        taskScope.launch {
            try {
                serverManager.stop(i)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // 停止失败详情已写进实例控制台
            }
        }
        return ToolResult("已请求优雅停止：${i.name}（发 stop 命令，等世界保存完成后退出）。可用 get_server_status 跟进")
    }

    private fun restartServer(args: JsonObject): ToolResult {
        val i = resolveInstance(args) ?: return instanceNotFound(args)
        // 与 AppViewModel.restartInstance 同一约定：等停稳再启，宁可这次不启也不硬启
        // （硬启会在同一个世界目录上拉起第二个 java）。整体后台执行，立即返回。
        taskScope.launch {
            try {
                serverManager.stop(i)
                val stopped = withTimeoutOrNull(120_000) {
                    serverManager.states.first { m -> (m[i.id] ?: ServerState.Idle).isSettled() }
                }
                if (stopped == null) {
                    serverManager.consoleFor(i.id).emit(
                        "> 重启中断：等待停止超时（120 秒，多半还在保存世界），已取消本次重启",
                        com.kaze.newage.core.console.LineType.Warn,
                    )
                    return@launch
                }
                serverManager.start(i)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // 失败详情已写进实例控制台
            }
        }
        return ToolResult("已提交重启：${i.name}（先优雅停止，停稳后自动再启动）。可用 get_server_status / read_console 跟进")
    }

    private fun sendCommand(args: JsonObject): ToolResult {
        val i = resolveInstance(args) ?: return instanceNotFound(args)
        val command = args.str("command")?.trim().orEmpty()
        if (command.isEmpty()) return ToolResult("command 不能为空", true)
        if (!serverManager.isRunning(i.id)) {
            return ToolResult("实例「${i.name}」未在运行，命令未发送（先调 start_server）", true)
        }
        serverManager.sendCommand(i, command)
        return ToolResult("已发送命令：$command")
    }

    private fun readConsole(args: JsonObject): ToolResult {
        val i = resolveInstance(args) ?: return instanceNotFound(args)
        val n = (args.int("lines") ?: 100).coerceIn(1, 500)
        val text = serverManager.consoleFor(i.id).snapshot()
            .takeLast(n)
            .joinToString("\n") { it.text }
        return ToolResult(text.ifBlank { "（控制台暂无输出）" })
    }

    private fun listPlayers(args: JsonObject): ToolResult {
        val i = resolveInstance(args) ?: return instanceNotFound(args)
        // 与 AppViewModel 的推算同一套语义：list 响应整体替换，join 增、leave 减
        var players: Set<String> = emptySet()
        serverManager.consoleFor(i.id).snapshot().forEach { line ->
            ConsoleParser.parseOnlinePlayers(line.text)?.let { players = it.toSet() }
            ConsoleParser.parseJoin(line.text)?.let { n -> players = players + n }
            ConsoleParser.parseLeave(line.text)?.let { n -> players = players - n }
        }
        val arr = buildJsonArray { players.forEach { add(it) } }
        return ToolResult(pretty(arr) + "\n共 ${players.size} 人在线")
    }

    private fun getProperties(args: JsonObject): ToolResult {
        val i = resolveInstance(args) ?: return instanceNotFound(args)
        val props: Map<String, String> = ServerProperties.load(i.dir)
        return ToolResult(pretty(buildJsonObject { props.forEach { (k, v) -> put(k, v) } }))
    }

    private fun setProperty(args: JsonObject): ToolResult {
        val i = resolveInstance(args) ?: return instanceNotFound(args)
        val key = args.str("key")?.trim().orEmpty()
        if (key.isEmpty()) return ToolResult("key 不能为空", true)
        val value = args.str("value") ?: ""
        val props = ServerProperties.load(i.dir)
        props[key] = value
        ServerProperties.save(i.dir, props)
        return ToolResult("已写入 server.properties：$key = $value（重启服务端后生效）")
    }

    private fun createBackup(args: JsonObject): ToolResult {
        val i = resolveInstance(args) ?: return instanceNotFound(args)
        val file = BackupManager.backup(i)
        return ToolResult("备份完成：${file.name}（${file.length()} 字节）")
    }

    // ── 寻址与输出 ──

    /** id 优先，实例名兜底（AI 跟着 list_instances 输出走，两种都能命中） */
    private fun resolveInstance(args: JsonObject): ServerInstance? {
        val key = args.str("instanceId") ?: args.str("instance") ?: return null
        return instanceById(key) ?: allInstances().firstOrNull { it.name == key }
    }

    private fun instanceNotFound(args: JsonObject): ToolResult =
        ToolResult("找不到实例：${args.str("instanceId") ?: args.str("instance") ?: "(空)"}（用 list_instances 查看可用实例）", true)

    private fun instanceSummary(i: ServerInstance): JsonObject {
        val state = serverManager.states.value[i.id] ?: ServerState.Idle
        val port = runCatching { ServerProperties.load(i.dir)["server-port"] }.getOrNull()
        return buildJsonObject {
            put("id", i.id)
            put("name", i.name)
            put("core", i.coreType.displayName)
            put("mcVersion", i.mcVersion)
            put("state", state.name)
            put("running", serverManager.isRunning(i.id))
            put("uptimeSec", serverManager.uptimeSec(i.id).value)
            put("port", port?.toIntOrNull() ?: -1)
        }
    }

    private fun ServerState.isStarting(): Boolean =
        this == ServerState.Starting || this == ServerState.FirstRun ||
            this == ServerState.AcceptingEula || this == ServerState.Stopping

    /** 已停稳：可以安全再启动（Idle 视为未在跑，允许启动） */
    private fun ServerState.isSettled(): Boolean =
        this == ServerState.Stopped || this == ServerState.Error || this == ServerState.Idle

    private fun pretty(el: JsonArray): String = el.toString()
    private fun pretty(el: JsonObject): String = el.toString()
}

// ── JSON Schema 构造（工具描述用；模型靠它生成调用参数）──

private fun objSchema(
    properties: JsonObject = buildJsonObject { },
    required: List<String> = emptyList(),
): JsonObject = buildJsonObject {
    put("type", "object")
    put("properties", properties)
    if (required.isNotEmpty()) {
        put("required", buildJsonArray { required.forEach { add(it) } })
    }
}

private fun props(vararg pairs: Pair<String, JsonObject>): JsonObject =
    buildJsonObject { pairs.forEach { (k, v) -> put(k, v) } }

private fun instanceIdProp(): Pair<String, JsonObject> =
    "instanceId" to strProp("实例 id 或实例名")

private fun strProp(description: String): JsonObject = buildJsonObject {
    put("type", "string")
    put("description", description)
}

private fun intProp(description: String, default: Int? = null): JsonObject = buildJsonObject {
    put("type", "integer")
    put("description", description)
    if (default != null) put("default", default)
}

private fun boolProp(description: String): JsonObject = buildJsonObject {
    put("type", "boolean")
    put("description", description)
}

// ── JSON 参数读取（客户端传什么都可能，读不到按缺省处理）──

private fun JsonObject.str(key: String): String? =
    this[key]?.jsonPrimitive?.contentOrNull

private fun JsonObject.int(key: String): Int? =
    this[key]?.jsonPrimitive?.intOrNull

private fun JsonObject.bool(key: String): Boolean? =
    this[key]?.jsonPrimitive?.booleanOrNull
