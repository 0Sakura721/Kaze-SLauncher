package com.kaze.newage.data.prefs

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import com.kaze.newage.core.ai.AiCommandPolicy
import com.kaze.newage.core.ai.AiConfig
import com.kaze.newage.core.ai.AiProfile
import com.kaze.newage.core.ai.AiProfileStore
import com.kaze.newage.core.ai.AiSearch
import com.kaze.newage.core.ai.AiToolPolicy
import com.kaze.newage.core.mcp.McpPorts
import com.kaze.newage.core.mcp.McpServer
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File

/**
 * UI 设置存储（SharedPreferences）：背景图、毛玻璃参数。
 * 借鉴 ZalithLauncher2 的背景设置体系（GPL-3.0），简化实现。
 */
class SettingsPrefs(
    context: Context,
    private val encryptAiSecret: (String) -> String? = { AiKeyCipher.encrypt(it) },
    private val decryptAiSecret: (String) -> String? = { AiKeyCipher.decrypt(it) },
) {

    private val appContext: Context = context.applicationContext
    private val prefs = context.getSharedPreferences("kaze_ui_settings", Context.MODE_PRIVATE)
    /** AI credentials live separately so backup rules can exclude them without losing ordinary settings. */
    private val secretPrefs = context.getSharedPreferences(SECRET_PREFS_NAME, Context.MODE_PRIVATE)
        .also { migrateAiSecrets(it, prefs) }
    private val bgFile = File(context.filesDir, "background.png")

    /** 背景图是否存在（State：保存/清除后自动刷新 UI） */
    private val bgExists = mutableStateOf(bgFile.exists())

    /**
     * 背景图版本号：每次换图/清除都自增。
     *
     * 背景图始终写同一个路径（filesDir/background.png），只靠 
emember(path) 缓存位图的话，
     * 换一张新图后 path 没变 → 不会重新解码，界面仍显示旧图，必须重启应用才生效。
     */
    val bgRevision = mutableStateOf(0)
    val hasBackgroundImage: Boolean get() = bgExists.value

    /** 是否启用背景图 */
    val bgEnabled = mutableStateOf(prefs.getBoolean("bg_enabled", false))

    /** 主题模式：m3（Material 3 动态取色，默认）/ glass（液态玻璃，已封锁） */
    val themeMode = mutableStateOf(prefs.getString("theme_mode", "clear") ?: "clear")

    init {
        // 液态玻璃功能封锁：旧档若处于 glass 主题，强制回退 m3（点击入口已弹提示）
        if (themeMode.value == "glass") {
            themeMode.value = "m3"
            prefs.edit().putString("theme_mode", "m3").apply()
        }
    }

    /** 深色模式（旧键，迁移到 themeModeValue 后弃用） */
    val forceDark = mutableStateOf<Boolean?>(
        if (prefs.contains("force_dark")) prefs.getBoolean("force_dark", false) else null
    )

    /** 主题模式（照搬 BiliPai AppThemeMode）：0=跟随系统 1=浅色 2=深色 */
    val themeModeValue = mutableStateOf(
        when {
            prefs.contains("theme_mode_value") -> prefs.getInt("theme_mode_value", 0)
            prefs.contains("force_dark") -> if (prefs.getBoolean("force_dark", false)) 2 else 1
            else -> 0
        }
    )

    /** 深色样式（照搬 BiliPai DarkThemeStyle）：0=普通黑 1=AMOLED纯黑 */
    val darkStyle = mutableStateOf(prefs.getInt("dark_style", 0))

    /** MD3 颜色来源（照搬 BiliPai Md3ColorSource）：wallpaper=跟随系统壁纸 / custom=自定义颜色 */
    val md3ColorSource = mutableStateOf(prefs.getString("md3_color_source", "wallpaper") ?: "wallpaper")

    /** MD3 自定义种子色（hex，默认 #00FFFF 青色） */
    val md3CustomColor = mutableStateOf(prefs.getString("md3_custom_color", "#00FFFF") ?: "#00FFFF")

    /** 液态玻璃模式（照搬 BiliPai LiquidGlassMode）：clear/balanced/frosted */
    val glassMode = mutableStateOf(prefs.getString("glass_mode", "balanced") ?: "balanced")

    /** Linux 环境存放到外部存储（内部空间不足时；切换后需重新部署） */
    val envExternal = mutableStateOf(prefs.getBoolean("env_external", false))

    /**
     * 自定义实例存储目录（空 = 默认 app 外部目录 instances/）。
     * 通过 SAF 目录选择器写入（需「所有文件访问」权限）；实例可直接存到该目录并从该目录加载运行。
     */
    val instanceDirPath = mutableStateOf(prefs.getString("instance_dir_path", "") ?: "")

    /** 自定义目录对应的 SAF tree URI（持久授权记录，切换/恢复时释放） */
    val instanceDirUri = mutableStateOf(prefs.getString("instance_dir_uri", "") ?: "")

    /** 原生模糊（Haze/RenderEffect，Android 12+；个别 GPU 异常时可关闭回退半透明玻璃） */
    val glassBlur = mutableStateOf(prefs.getBoolean("glass_blur", true))

    /** 玻璃强度（0.5..1.5，默认 1.0；作用于饱和度增强与透镜折射幅度） */
    val glassIntensity = mutableFloatStateOf(prefs.getFloat("glass_intensity", 1.0f))

    /** 是否已请求过电池优化白名单（只自动弹一次） */
    val batteryPrompted = mutableStateOf(prefs.getBoolean("battery_prompted", false))

    /** 背景模糊强度（0..25） */
    val bgBlur = mutableFloatStateOf(prefs.getFloat("bg_blur", 12f))

    /** 背景不透明度（0..100，影响背景图整体显示） */
    val bgOpacity = mutableFloatStateOf(prefs.getFloat("bg_opacity", 25f))

    /** 图标与文字颜色模式：auto（跟随主题）/ light（白色）/ dark（黑色） */
    val fgColorMode = mutableStateOf(prefs.getString("fg_color_mode", "auto") ?: "auto")

    /** 每次启动自动检查更新（默认开） */
    val autoUpdate = mutableStateOf(prefs.getBoolean("auto_update", true))
    /** 更新通道：preview（预览版，默认，含 prerelease）/ stable（仅正式版） */
    val updateChannel = mutableStateOf(prefs.getString("update_channel", "preview") ?: "preview")

    /**
     * 更新方式：`full`（完整安装包，**默认**）/ `patch`（增量补丁，省流量）。
     *
     * 默认整包是刻意的：补丁要拿本机已装 APK 当基线拼装，虽然每一道都校验
     * （baseSha256 / targetSha256 / 签名），但它终究比"下一个完整包"多一环；
     * 让用户显式选"省流量"更稳妥。
     */
    val updateMode = mutableStateOf(prefs.getString("update_mode", "full") ?: "full")

    // ── 控制台 DIY（快捷命令 / 字号 / 时间戳）──
    //
    // 级别过滤与搜索词是会话态（重进恢复"全部"更符合直觉），这里只存跨会话的偏好。

    /**
     * 控制台快捷命令（chip 行）。JSON 数组落盘：命令内容里可以有引号、逗号、反斜杠，
     * 手写分隔符必然转义出错。写入端裁剪（≤12 条、每条 ≤200 字符），读端解析失败按空处理。
     */
    val consoleQuickCommands = mutableStateOf(loadQuickCommands())

    /** 控制台日志字号（10..20sp，默认 12） */
    val consoleFontSp = mutableFloatStateOf(prefs.getFloat("console_font_sp", 12f))

    /** 控制台时间戳前缀（[HH:mm:ss]，默认关） */
    val consoleTimestamps = mutableStateOf(prefs.getBoolean("console_timestamps", false))

    /**
     * 控制台常驻 AI 入口（命令输入行左侧的 AI 助手按钮，默认关）。
     *
     * 默认关是刻意的：入口常驻会一直占着输入行一截位置，不用 AI 的人没有理由天天看到它；
     * 要用的人在设置里打开一次即可（「服务端」页顶栏另有入口，不受此项影响）。
     */
    val consoleAiEntry = mutableStateOf(prefs.getBoolean("console_ai_entry", false))

    private fun loadQuickCommands(): List<String> =
        runCatching {
            prefs.getString("console_quick_commands", null)
                ?.takeIf { it.isNotBlank() }
                ?.let { Json.decodeFromString<List<String>>(it) }
        }.getOrNull().orEmpty()
            .map { it.trim().take(200) }
            .filter { it.isNotEmpty() }
            .distinct()
            .take(12)

    fun setConsoleQuickCommands(list: List<String>) {
        val cleaned = list.map { it.trim().take(200) }.filter { it.isNotEmpty() }.distinct().take(12)
        consoleQuickCommands.value = cleaned
        prefs.edit().putString("console_quick_commands", Json.encodeToString(cleaned)).apply()
    }

    fun setConsoleFontSp(v: Float) {
        val sp = v.coerceIn(10f, 20f)
        consoleFontSp.floatValue = sp
        prefs.edit().putFloat("console_font_sp", sp).apply()
    }

    fun setConsoleTimestamps(v: Boolean) {
        consoleTimestamps.value = v
        prefs.edit().putBoolean("console_timestamps", v).apply()
    }

    fun setConsoleAiEntry(v: Boolean) {
        consoleAiEntry.value = v
        prefs.edit().putBoolean("console_ai_entry", v).apply()
    }

    // ── MCP 服务（外部 AI 控制接口，见 core/mcp/）──

    /**
     * MCP 服务开关（默认关）。
     *
     * 这是**开放给外部的控制面**（可启停服务端、发控制台命令）——默认敞着等于
     * 同一 Wi-Fi 下谁都能操作你的服务端，所以必须由用户显式打开，且配访问令牌。
     */
    val mcpEnabled = mutableStateOf(prefs.getBoolean("mcp_enabled", false))

    /**
     * 映射端口：外部 AI 客户端连接的入口端口（被占用时按 [mcpPortRangeStart] 范围顺延）。
     * 服务实际监听的端口可能与它不同，以 McpServer.boundPort 为准。
     */
    val mcpPort = mutableStateOf(prefs.getInt("mcp_port", McpServer.DEFAULT_PORT))

    /** 自动分配范围：映射端口被占用时在该范围内顺延取第一个可用端口 */
    val mcpPortRangeStart = mutableStateOf(prefs.getInt("mcp_port_range_start", McpServer.DEFAULT_RANGE_START))
    val mcpPortRangeEnd = mutableStateOf(prefs.getInt("mcp_port_range_end", McpServer.DEFAULT_RANGE_END))

    /**
     * 监听地址：0.0.0.0 = 所有网卡（局域网可访问，外部 AI 的默认用法）；
     * 127.0.0.1 = 仅本机；也可以是某个具体网卡 IPv4。
     */
    val mcpBindAddress = mutableStateOf(
        prefs.getString("mcp_bind_address", McpPorts.DEFAULT_BIND_ADDRESS) ?: McpPorts.DEFAULT_BIND_ADDRESS,
    )

    /** 访问令牌（Bearer 认证；Keystore 加密存，首次启用前就生成好） */
    val mcpToken = mutableStateOf(loadMcpToken())

    fun setMcpEnabled(v: Boolean) {
        mcpEnabled.value = v
        prefs.edit().putBoolean("mcp_enabled", v).apply()
    }

    fun setMcpPort(v: Int) {
        val p = v.coerceIn(1024, 65535)
        mcpPort.value = p
        prefs.edit().putInt("mcp_port", p).apply()
    }

    /** 端口范围（自动分配）；非法（起>止或越界）时保持原值不写 */
    fun setMcpPortRange(start: Int, end: Int) {
        if (start !in 1024..65535 || end !in 1024..65535 || start > end) return
        mcpPortRangeStart.value = start
        mcpPortRangeEnd.value = end
        prefs.edit().putInt("mcp_port_range_start", start).putInt("mcp_port_range_end", end).apply()
    }

    /** 监听地址（格式由设置页校验；这里只兜底空值，改完由 rebindMcp 生效） */
    fun setMcpBindAddress(v: String) {
        val a = v.trim().ifEmpty { McpPorts.DEFAULT_BIND_ADDRESS }
        mcpBindAddress.value = a
        prefs.edit().putString("mcp_bind_address", a).apply()
    }

    /** 重新生成访问令牌（旧令牌立即失效；服务端即时读取，不用重启） */
    fun regenerateMcpToken(): String {
        val t = newMcpToken()
        mcpToken.value = t
        prefs.edit().putString("mcp_token", AiKeyCipher.encrypt(t) ?: t).apply()
        return t
    }

    private fun loadMcpToken(): String {
        val raw = prefs.getString("mcp_token", "")?.trim().orEmpty()
        val token = if (raw.isEmpty()) "" else AiKeyCipher.decrypt(raw).orEmpty()
        return token.ifBlank {
            // 首次（或旧值不可解）现生成并落盘：启用服务前就要有，界面直接显示可用
            val t = newMcpToken()
            prefs.edit().putString("mcp_token", AiKeyCipher.encrypt(t) ?: t).apply()
            t
        }
    }

    private fun newMcpToken(): String =
        java.util.UUID.randomUUID().toString().replace("-", "")

    // ── AI 助手（OpenAI 兼容接口 + 联网搜索，见 core/ai/）──
    //
    // 模型配置采用 Operit 式的「档案 + 功能分配」轻量版：
    //   档案 = 命名的一组（服务地址 / Key / 模型名列表），存 JSON；
    //   分配 = 「对话」这一个功能用哪个档案（以后加功能只需再加一个分配键）；
    //   思考强度 = DeepSeek V4 起由请求参数 thinking 控制（不再是两个模型名）；
    //   其它服务商若思考=换模型名，可把第二个模型名填进档案。
    // 新凭据必须通过 Keystore 加密才能写入；加密失败时拒绝保存，不降级落盘为明文。
    // 历史明文会先迁到不参与备份的专用 prefs，并在 Keystore 可用时尽力升级为密文。
    val aiSecretStorageError = mutableStateOf(false)
    private val rawAiProfiles = secretPrefs.getString("ai_profiles", "") ?: ""
    val aiProfiles = mutableStateOf(decProfiles(rawAiProfiles))

    /** 「对话」功能分配到的档案 id */
    val aiChatProfileId = mutableStateOf(prefs.getString("ai_chat_profile_id", "") ?: "")

    /**
     * 档案 JSON 损坏标志（设置页要提示 + 给清除入口）。
     *
     * 损坏时 [aiProfiles] 会是空列表（界面不能因此打不开），但**原来那份 JSON 一个字都不能丢**：
     * 原文已在 init 里转存到 [CORRUPT_BACKUP_KEY]，用户有机会人工救回里面的 Key。
     * 覆盖前转存是关键 —— 原来 decode 直接返回 emptyList()，下一次保存就把整份配置静默顶掉了。
     */
    val aiProfilesCorrupt = mutableStateOf(AiProfileStore.decodeChecked(rawAiProfiles).corrupt)

    /** 思考强度：false = 标准（快），true = 深度思考（更聪明也更慢） */
    val aiThinking = mutableStateOf(prefs.getBoolean("ai_thinking", false))

    /**
     * 控制台命令执行档位（借鉴 Harness 的权限分层）：
     * suggest = 仅建议（默认）/ safe = 白名单自动 / all = 全部自动。
     */
    val aiCommandMode = mutableStateOf(
        prefs.getString("ai_command_mode", AiCommandPolicy.Mode.SUGGEST.id) ?: AiCommandPolicy.Mode.SUGGEST.id
    )

    fun setAiCommandMode(v: String) {
        aiCommandMode.value = v
        prefs.edit().putString("ai_command_mode", v).apply()
    }

    /**
     * **每个工具**的权限档位（借鉴 Operit：Allow automatically / Ask every time / Deny）。
     *
     * 存的是一个字符串（`toolId=modeId;…`，见 [AiToolPolicy]）而不是每个工具一个键：
     * 工具清单还会增长，用一条串可以让"读坏一个键"只影响策略本身、不影响别的设置。
     * 只存被改动过的项 —— 默认值留在代码里，这样以后调整默认值对老用户同样生效。
     */
    val aiToolPolicies = mutableStateOf(prefs.getString("ai_tool_policies", "") ?: "")

    /** 读某工具当前档位（未配置回落 [AiToolPolicy.defaultMode]） */
    fun aiToolMode(toolId: String): AiToolPolicy.Mode =
        AiToolPolicy.modeOf(aiToolPolicies.value, toolId)

    fun setAiToolPolicy(toolId: String, mode: AiToolPolicy.Mode) {
        val next = AiToolPolicy.encode(aiToolPolicies.value, toolId, mode)
        aiToolPolicies.value = next
        prefs.edit().putString("ai_tool_policies", next).apply()
    }

    // ── 联网搜索 ──
    val aiSearchOn = mutableStateOf(prefs.getBoolean("ai_search_on", false))
    val aiSearchProviderId = mutableStateOf(
        prefs.getString("ai_search_provider", AiSearch.Provider.TAVILY.id) ?: AiSearch.Provider.TAVILY.id
    )

    /**
     * 各搜索源的凭据（provider id → Key / SearXNG 实例地址），**按源分槽**。
     *
     * 分槽的理由：只有一个全局 Key 时，切换搜索源会把 A 家的 Key 原样发给 B 家。
     * 内存里是解密后的值，落盘时逐槽加密到专用 prefs（见 [setAiSearch]）。
     */
    val aiSearchKeys = mutableStateOf(loadSearchKeys())

    /** 某个搜索源当前的凭据（免凭据源恒为空；SearXNG 返回的是实例地址） */
    fun searchKeyFor(providerId: String): String = AiSearch.keyFor(providerId, aiSearchKeys.value)

    private fun loadSearchKeys(): Map<String, String> {
        val out = LinkedHashMap<String, String>()
        AiSearch.Provider.entries.forEach { p ->
            val slot = AiSearch.Provider.keySlot(p.id)
            val raw = secretPrefs.getString(slot, "")?.trim().orEmpty()
            if (raw.isNotEmpty()) {
                val plain = decryptAiSecret(raw).orEmpty()
                if (plain.isNotEmpty()) {
                    out[p.id] = plain
                    // Upgrade legacy plaintext values after migration whenever Keystore is available.
                    if (!raw.startsWith("enc1:")) {
                        val encrypted = encryptAiSecret(plain)
                        if (encrypted != null && secretPrefs.edit().putString(slot, encrypted).commit()) {
                            aiSecretStorageError.value = false
                        } else {
                            aiSecretStorageError.value = true
                        }
                    }
                }
            }
        }
        return out
    }

    init {
        // 损坏检测放在最前面：后面那段"旧单配置 → 档案"的迁移会覆写 ai_profiles，
        // 必须先把原文转存出去（用户还可能人工救回里面的 Key）
        val rawProfiles = rawAiProfiles
        if (aiProfilesCorrupt.value && rawProfiles.isNotEmpty()) {
            secretPrefs.edit().putString(CORRUPT_BACKUP_KEY, rawProfiles).commit()
        }
        // 旧单配置 → 档案：P0/P1 存的是散键（ai_api_key + ai_base_url + ai_model_standard/thinking，
        // 更早还有 ai_model）。只要填过 Key 就拼成一个「DeepSeek」档案并设为对话配置，绝不丢 Key。
        // 放在 aiProfiles 等声明之后：属性按声明顺序初始化。
        val legacyKey = prefs.getString("ai_api_key", "")?.trim().orEmpty()
        if (!aiProfilesCorrupt.value && legacyKey.isNotBlank() &&
            aiProfiles.value.none { it.apiKey.trim() == legacyKey }
        ) {
            val standard = prefs.getString("ai_model_standard", null)
                ?: prefs.getString("ai_model", null)
                ?: AiConfig.DEFAULT_MODEL
            val thinking = prefs.getString("ai_model_thinking", null).orEmpty()
            val baseUrl = prefs.getString("ai_base_url", "")?.trim().orEmpty()
                .ifBlank { AiConfig.DEFAULT_BASE_URL }
            val models = normalizeProfileModels(
                listOf(standard.trim(), thinking.trim()).filter { it.isNotEmpty() }
                    .ifEmpty { listOf(AiConfig.DEFAULT_MODEL) },
                baseUrl,
            )
            val legacy = AiProfile(
                name = if (aiProfiles.value.isEmpty()) "DeepSeek" else "DeepSeek（旧配置）",
                baseUrl = baseUrl,
                apiKey = legacyKey,
                models = models,
            )
            val migratedProfiles = aiProfiles.value + legacy
            if (aiProfiles.value.isEmpty() && aiChatProfileId.value.isBlank()) {
                aiChatProfileId.value = legacy.id
            }
            // If Keystore or storage is unavailable, keep the source key untouched for a later retry.
            if (persistProfiles(migratedProfiles)) {
                aiProfiles.value = migratedProfiles
                if (aiProfiles.value.size == 1) {
                    prefs.edit().putString("ai_chat_profile_id", legacy.id).commit()
                }
            } else {
                // Keep it available in this process, but never write an unencrypted copy.
                aiProfiles.value = migratedProfiles
            }
        }
        // 存量档案里的旧模型名归一：deepseek-chat / deepseek-reasoner 在官方端点已停用
        // （2026-07-24），deepseek.com 的档案统一为 deepseek-flash，避免界面继续显示旧名
        // 让人误以为配置没问题（请求侧还有一层 requestModel 归一兜底）。
        // 第三方端点不改写（那里 deepseek-chat 可能仍是有效模型名）。
        val normalized = aiProfiles.value.map {
            it.copy(models = normalizeProfileModels(it.models, it.baseUrl))
        }
        if (normalized != aiProfiles.value) {
            if (persistProfiles(normalized)) aiProfiles.value = normalized
            else aiProfiles.value = normalized
        }
        // Remove the historical scattered Key only after a copy is confirmed in the dedicated store.
        if (legacyKey.isNotBlank()) {
            val storedProfiles = decProfiles(secretPrefs.getString("ai_profiles", "") ?: "")
            if (storedProfiles.any { it.apiKey.trim() == legacyKey }) {
                prefs.edit()
                    .remove("ai_api_key")
                    .remove("ai_base_url")
                    .remove("ai_model")
                    .remove("ai_model_standard")
                    .remove("ai_model_thinking")
                    .commit()
            }
        }
        // 分配指向的档案被删/损坏时回退到第一个（或空 = 未配置，界面会引导新增）
        if (aiChatProfileId.value.isNotBlank() &&
            aiProfiles.value.none { it.id == aiChatProfileId.value }
        ) {
            aiChatProfileId.value = aiProfiles.value.firstOrNull()?.id.orEmpty()
        }
    }

    /** 模型名列表归一：官方端点上旧名映射到现行名，去重去空（归一后 chat/reasoner 会合并为一条） */
    private fun normalizeProfileModels(models: List<String>, baseUrl: String): List<String> =
        models.map { AiConfig.normalizeLegacyModel(it, baseUrl) }
            .filter { it.isNotEmpty() }
            .distinct()
            .ifEmpty { listOf(AiConfig.DEFAULT_MODEL) }

    /** Encrypt every non-empty Key; null means persistence must fail closed. */
    private fun encProfiles(profiles: List<AiProfile>): List<AiProfile>? {
        val encrypted = ArrayList<AiProfile>(profiles.size)
        for (profile in profiles) {
            if (profile.apiKey.isBlank()) encrypted += profile
            else {
                val value = encryptAiSecret(profile.apiKey) ?: return null
                encrypted += profile.copy(apiKey = value)
            }
        }
        return encrypted
    }

    /** Read boundary: legacy plaintext remains readable long enough to migrate without data loss. */
    private fun decProfiles(raw: String): List<AiProfile> {
        val stored = AiProfileStore.decode(raw)
        val decoded = stored.map { profile ->
            if (profile.apiKey.isBlank()) profile
            else profile.copy(apiKey = decryptAiSecret(profile.apiKey).orEmpty())
        }
        // Best-effort upgrade of old plaintext profiles after migration to the excluded secret store.
        val hasLegacyPlaintext = stored.any { it.apiKey.isNotBlank() && !it.apiKey.startsWith("enc1:") }
        if (hasLegacyPlaintext && !AiProfileStore.decodeChecked(raw).corrupt) {
            val encrypted = encProfiles(decoded)
            if (encrypted != null &&
                secretPrefs.edit().putString("ai_profiles", AiProfileStore.encode(encrypted)).commit()
            ) aiSecretStorageError.value = false
            else aiSecretStorageError.value = true
        }
        return decoded
    }

    private fun persistProfiles(profiles: List<AiProfile>): Boolean {
        val encrypted = encProfiles(profiles)
        if (encrypted == null) {
            aiSecretStorageError.value = true
            return false
        }
        val editor = secretPrefs.edit()
        if (aiProfilesCorrupt.value && rawAiProfiles.isNotEmpty()) {
            editor.putString(CORRUPT_BACKUP_KEY, rawAiProfiles)
        }
        editor.putString("ai_profiles", AiProfileStore.encode(encrypted))
        val saved = editor.commit()
        aiSecretStorageError.value = !saved
        if (saved) aiProfilesCorrupt.value = false
        return saved
    }

    /** 新增或更新档案（按 id 覆盖，编辑保持原位置）；首个档案自动成为对话配置 */
    fun saveAiProfile(profile: AiProfile): Boolean {
        val list = if (aiProfiles.value.any { it.id == profile.id }) {
            aiProfiles.value.map { if (it.id == profile.id) profile else it }
        } else {
            aiProfiles.value + profile
        }
        if (!persistProfiles(list)) return false
        aiProfiles.value = list
        aiProfilesCorrupt.value = false
        if (aiChatProfileId.value.isBlank() || aiProfiles.value.none { it.id == aiChatProfileId.value }) {
            setChatAiProfile(profile.id)
        }
        return true
    }

    /**
     * 用户明确确认已处理损坏配置后，删除损坏的活动 JSON 与它的原文转存。
     * 两个键一起提交：失败时保留原始数据，避免只删掉可恢复副本。
     */
    fun clearCorruptAiProfiles() {
        val saved = secretPrefs.edit()
            .remove("ai_profiles")
            .remove(CORRUPT_BACKUP_KEY)
            .commit()
        if (saved) {
            aiProfiles.value = emptyList()
            aiProfilesCorrupt.value = false
            aiSecretStorageError.value = false
        } else {
            aiSecretStorageError.value = true
        }
    }

    /** 删除档案；删的是当前对话配置时回退到剩余第一个 */
    fun deleteAiProfile(id: String) {
        val list = aiProfiles.value.filterNot { it.id == id }
        if (!persistProfiles(list)) return
        aiProfiles.value = list
        if (aiChatProfileId.value == id) setChatAiProfile(list.firstOrNull()?.id.orEmpty())
    }

    /** 功能分配：把「对话」指向某个档案 */
    fun setChatAiProfile(id: String) {
        aiChatProfileId.value = id
        prefs.edit().putString("ai_chat_profile_id", id).apply()
    }

    /** 思考强度开关（聊天面板随时可切） */
    fun setAiThinking(v: Boolean) {
        aiThinking.value = v
        prefs.edit().putBoolean("ai_thinking", v).apply()
    }

    /**
     * 保存联网搜索配置（源 + 该源的凭据）。
     *
     * 只写**当前源这一格**：切源不再覆盖别家的 Key，也不会把上一家的 Key 带过去。
     */
    fun setAiSearch(providerId: String, key: String): Boolean {
        val p = AiSearch.Provider.byId(providerId.trim()).id
        val k = key.trim()
        val slot = AiSearch.Provider.keySlot(p)
        val edit = secretPrefs.edit()
        if (k.isEmpty()) edit.remove(slot)
        else {
            val encrypted = encryptAiSecret(k)
            if (encrypted == null) {
                aiSecretStorageError.value = true
                return false
            }
            edit.putString(slot, encrypted)
        }
        if (!edit.commit()) {
            aiSecretStorageError.value = true
            return false
        }
        prefs.edit().putString("ai_search_provider", p).commit()
        aiSearchProviderId.value = p
        val next = LinkedHashMap(aiSearchKeys.value)
        if (k.isEmpty()) next.remove(p) else next[p] = k
        aiSearchKeys.value = next
        aiSecretStorageError.value = false
        return true
    }

    /** 联网搜索开关（聊天面板随时可切） */
    fun setAiSearchOn(v: Boolean) {
        aiSearchOn.value = v
        prefs.edit().putBoolean("ai_search_on", v).apply()
    }

    /**
     * 当前对话配置快照：解析分配到的档案 → [AiConfig]。
     * 没有任何档案时返回 DeepSeek 默认值（Key 为空 → isConfigured=false，界面会引导配置）。
     */
    fun aiConfig(): AiConfig {
        val p = aiProfiles.value.firstOrNull { it.id == aiChatProfileId.value }
            ?: aiProfiles.value.firstOrNull()
        return AiConfig(
            baseUrl = p?.baseUrl?.trim()?.ifBlank { AiConfig.DEFAULT_BASE_URL } ?: AiConfig.DEFAULT_BASE_URL,
            model = p?.models?.firstOrNull()?.trim()?.ifBlank { null } ?: AiConfig.DEFAULT_MODEL,
            thinkingModel = p?.models?.getOrNull(1)?.trim().orEmpty(),
            apiKey = p?.apiKey?.trim().orEmpty(),
            thinking = aiThinking.value,
            extraBody = p?.extraBody.orEmpty(),
        )
    }

    fun setAutoUpdate(v: Boolean) {
        autoUpdate.value = v
        prefs.edit().putBoolean("auto_update", v).apply()
    }

    fun setUpdateMode(v: String) {
        updateMode.value = v
        prefs.edit().putString("update_mode", v).apply()
    }

    fun setUpdateChannel(v: String) {
        updateChannel.value = v
        prefs.edit().putString("update_channel", v).apply()
    }

    fun setFgColorMode(v: String) {
        fgColorMode.value = v
        prefs.edit().putString("fg_color_mode", v).apply()
    }

    fun backgroundImagePath(): String? = bgFile.takeIf { it.exists() }?.absolutePath

    /**
     * 保存选中的背景图（SAF Uri，压缩到 [BG_MAX_DIM] 见方，避免大图内存问题）。
     *
     * **必须先探边界再按 inSampleSize 解码**：`BitmapFactory.decodeStream` 一把梭会把整张原图
     * 解进内存 —— 4800 万像素的相机照是 ARGB_8888 下的 192MB，任何机型的堆都扛不住，
     * 结果是 OOM 闪退（而且是在用户刚点完选图的瞬间）。
     * 两遍解码的代价只是读一次文件头。
     */
    fun saveBackgroundImage(uri: android.net.Uri) {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        try {
            appContext.contentResolver.openInputStream(uri)?.use {
                BitmapFactory.decodeStream(it, null, bounds)
            } ?: return
        } catch (_: Exception) {
            return
        }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return
        val sample = sampleSizeFor(bounds.outWidth, bounds.outHeight, BG_MAX_DIM)
        val decoded: Bitmap? = try {
            appContext.contentResolver.openInputStream(uri)?.use {
                BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample })
            }
        } catch (_: Exception) {
            null
        }
        val src = decoded ?: return
        val scale = minOf(1f, BG_MAX_DIM.toFloat() / maxOf(src.width, src.height))
        val scaled = if (scale < 1f) {
            Bitmap.createScaledBitmap(src, (src.width * scale).toInt(), (src.height * scale).toInt(), true)
        } else src
        runCatching { bgFile.outputStream().use { scaled.compress(Bitmap.CompressFormat.JPEG, 85, it) } }
        if (scaled != src) scaled.recycle()
        src.recycle()
        bgExists.value = true
        // 让依赖背景图的 UI（AppBackground 的位图缓存）知道内容变了
        bgRevision.value = bgRevision.value + 1
    }

    fun clearBackgroundImage() {
        bgFile.delete()
        bgExists.value = false
        // 让依赖背景图的 UI（AppBackground 的位图缓存）知道内容变了
        bgRevision.value = bgRevision.value + 1
    }

    fun setBgEnabled(v: Boolean) {
        bgEnabled.value = v
        prefs.edit().putBoolean("bg_enabled", v).apply()
    }

    fun setThemeMode(v: String) {
        themeMode.value = v
        prefs.edit().putString("theme_mode", v).apply()
    }

    fun setForceDark(v: Boolean?) {
        forceDark.value = v
        if (v == null) prefs.edit().remove("force_dark").apply()
        else prefs.edit().putBoolean("force_dark", v).apply()
    }

    fun setThemeModeValue(v: Int) {
        themeModeValue.value = v
        prefs.edit().putInt("theme_mode_value", v).apply()
    }

    fun setDarkStyle(v: Int) {
        darkStyle.value = v
        prefs.edit().putInt("dark_style", v).apply()
    }

    fun setMd3ColorSource(v: String) {
        md3ColorSource.value = v
        prefs.edit().putString("md3_color_source", v).apply()
    }

    fun setMd3CustomColor(v: String) {
        md3CustomColor.value = v
        prefs.edit().putString("md3_custom_color", v).apply()
    }

    fun setGlassMode(v: String) {
        glassMode.value = v
        prefs.edit().putString("glass_mode", v).apply()
    }

    fun setEnvExternal(v: Boolean) {
        envExternal.value = v
        prefs.edit().putBoolean("env_external", v).apply()
    }

    /** 设置自定义实例目录（path 为空 = 恢复默认） */
    fun setInstanceDir(path: String, uri: String = "") {
        instanceDirPath.value = path
        instanceDirUri.value = uri
        prefs.edit()
            .putString("instance_dir_path", path)
            .putString("instance_dir_uri", uri)
            .apply()
    }

    fun setGlassBlur(v: Boolean) {
        glassBlur.value = v
        prefs.edit().putBoolean("glass_blur", v).apply()
    }

    fun setGlassIntensity(v: Float) {
        glassIntensity.floatValue = v
        prefs.edit().putFloat("glass_intensity", v).apply()
    }

    fun setBatteryPrompted(v: Boolean) {
        batteryPrompted.value = v
        prefs.edit().putBoolean("battery_prompted", v).apply()
    }

    fun setBgBlur(v: Float) {
        bgBlur.floatValue = v
        prefs.edit().putFloat("bg_blur", v).apply()
    }

    fun setBgOpacity(v: Float) {
        bgOpacity.floatValue = v
        prefs.edit().putFloat("bg_opacity", v).apply()
    }

    companion object {
        /** 专门存储 AI 凭据的 SharedPreferences 文件（备份规则按文件名排除） */
        internal const val SECRET_PREFS_NAME = "kaze_ai_secrets"
        internal const val CORRUPT_BACKUP_KEY = "ai_profiles_corrupt_raw"
        private const val OLD_CORRUPT_BACKUP_KEY = "ai_profiles_corrupt_backup"

        /** Copy credentials before deleting backupable copies; failed copies must keep the source. */
        internal fun migrateAiSecrets(
            secret: android.content.SharedPreferences,
            legacy: android.content.SharedPreferences,
        ) {
            val pending = LinkedHashMap<String, String>()
            fun copyIfMissing(key: String) {
                if (!secret.contains(key) && legacy.contains(key)) {
                    val value = legacy.getString(key, null) ?: return
                    pending[key] = value
                }
            }
            copyIfMissing("ai_profiles")
            AiSearch.Provider.entries.forEach { provider -> copyIfMissing(AiSearch.Provider.keySlot(provider.id)) }
            if (legacy.contains("ai_search_key")) {
                val provider = AiSearch.Provider.byId(legacy.getString("ai_search_provider", "") ?: "")
                val slot = AiSearch.Provider.keySlot(provider.id)
                if (!secret.contains(slot) && !pending.containsKey(slot)) {
                    legacy.getString("ai_search_key", null)?.let { pending[slot] = it }
                }
            }
            if (!secret.contains(CORRUPT_BACKUP_KEY) && legacy.contains(OLD_CORRUPT_BACKUP_KEY)) {
                legacy.getString(OLD_CORRUPT_BACKUP_KEY, null)?.let { pending[CORRUPT_BACKUP_KEY] = it }
            }
            if (pending.isNotEmpty()) {
                val edit = secret.edit()
                pending.forEach { (key, value) -> edit.putString(key, value) }
                if (!edit.commit()) return
            }
            val cleanup = legacy.edit()
                .remove("ai_profiles")
                .remove("ai_search_key")
                .remove(OLD_CORRUPT_BACKUP_KEY)
            AiSearch.Provider.entries.forEach { provider -> cleanup.remove(AiSearch.Provider.keySlot(provider.id)) }
            cleanup.commit()
        }

        /** 背景图保存后的最长边：显示端只做 Crop，超过这个尺寸纯属浪费内存与磁盘 */
        private const val BG_MAX_DIM = 1600

        /**
         * 采样率：让解码结果的最长边**不小于** [target] 的最小 2 的幂。
         *
         * 必须"不小于"：取大了只是多占一点内存（显示端还会再缩放），取小了会先丢掉分辨率、
         * 再被放大回来看，背景图直接糊掉。
         */
        internal fun sampleSizeFor(width: Int, height: Int, target: Int): Int {
            var sample = 1
            while (maxOf(width, height) / (sample * 2) >= target) sample *= 2
            return sample
        }
    }
}
