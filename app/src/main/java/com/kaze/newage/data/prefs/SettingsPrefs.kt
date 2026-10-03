package com.kaze.newage.data.prefs

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import com.kaze.newage.core.ai.AiConfig
import com.kaze.newage.core.ai.AiProfile
import com.kaze.newage.core.ai.AiProfileStore
import com.kaze.newage.core.ai.AiSearch
import java.io.File

/**
 * UI 设置存储（SharedPreferences）：背景图、毛玻璃参数。
 * 借鉴 ZalithLauncher2 的背景设置体系（GPL-3.0），简化实现。
 */
class SettingsPrefs(context: Context) {

    private val appContext: Context = context.applicationContext
    private val prefs = context.getSharedPreferences("kaze_ui_settings", Context.MODE_PRIVATE)

    /**
     * 只装 AI 密钥（模型档案里的 apiKey + 各搜索源的 Key）的独立 SharedPreferences 文件。
     *
     * 为什么不跟其它设置放一个文件：SharedPreferences 是**明文**存储，而
     * `allowBackup="true"` 会把 `kaze_ui_settings` 整个带进云备份 / 换机迁移。
     * 密钥独立成文件后，备份规则（res/xml/backup_rules.xml 与 data_extraction_rules.xml）
     * 就能按 sharedpref 域精确排除它，而主题、背景、更新通道这些普通设置照常备份。
     *
     * 旧数据的一次性迁移挂在属性初始化里：Kotlin 按声明顺序执行，`aiProfiles`
     * 与搜索 Key 都在下面声明，必须保证它们读到的已经是迁移后的值。
     */
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

    // ── AI 助手（OpenAI 兼容接口 + 联网搜索，见 core/ai/）──
    //
    // 模型配置采用 Operit 式的「档案 + 功能分配」轻量版：
    //   档案 = 命名的一组（服务地址 / Key / 模型名列表），存 JSON；
    //   分配 = 「对话」这一个功能用哪个档案（以后加功能只需再加一个分配键）；
    //   思考强度 = DeepSeek V4 起由请求参数 thinking 控制（不再是两个模型名）；
    //   其它服务商若思考=换模型名，可把第二个模型名填进档案。
    // API Key / 搜索 Key 只存应用私有的 [secretPrefs]（别的应用读不到，也不进云备份），
    // 绝不入库、绝不写日志。
    /** 档案 JSON 的**原始串**：损坏时靠它兜底（见 [aiProfilesCorrupt]） */
    private val rawAiProfiles: String = secretPrefs.getString("ai_profiles", "") ?: ""
    private val decodedAiProfiles = AiProfileStore.decodeResult(rawAiProfiles)

    /**
     * 档案 JSON 损坏（有内容但解析不了）。
     *
     * 界面必须提示，且**不允许被静默覆盖**：原实现 decode 失败只返回 emptyList()，
     * 界面显示"还没有配置"，用户照着引导新建一份就把损坏的原串覆盖掉 —— 里面的 Key
     * 全部无声消失。现在：损坏时保留原串、置位这个标志，用户显式清除或保存新配置时
     * 才把原文另存到 [CORRUPT_PROFILES_BACKUP_KEY] 再覆盖。
     */
    val aiProfilesCorrupt = mutableStateOf(decodedAiProfiles.corrupt)
    val aiProfiles = mutableStateOf(decodedAiProfiles.profiles)

    /** 「对话」功能分配到的档案 id */
    val aiChatProfileId = mutableStateOf(prefs.getString("ai_chat_profile_id", "") ?: "")

    /** 思考强度：false = 标准（快），true = 深度思考（更聪明也更慢） */
    val aiThinking = mutableStateOf(prefs.getBoolean("ai_thinking", false))

    // ── 联网搜索 ──
    val aiSearchOn = mutableStateOf(prefs.getBoolean("ai_search_on", false))
    val aiSearchProviderId = mutableStateOf(
        prefs.getString("ai_search_provider", AiSearch.Provider.TAVILY.id) ?: AiSearch.Provider.TAVILY.id
    )

    /**
     * 各搜索源**各自的** Key（槽名见 [AiSearch.Provider.keySlot]）。
     *
     * 为什么必须分槽：原来全局只有一个 `ai_search_key`，切换搜索源时那份 Key 会被原样
     * 发给新的服务商 —— 点一下"换成博查"，Tavily 的密钥就交给了博查。
     * 分槽后换个源只会读到它自己那一格（没填过就是空，界面提示重新填写）。
     */
    private val searchKeys = mutableStateMapOf<String, String>().apply {
        AiSearch.Provider.entries.forEach { p ->
            secretPrefs.getString(AiSearch.Provider.keySlot(p.id), "")?.takeIf { it.isNotEmpty() }?.let { put(p.id, it) }
        }
    }

    /** 取某个搜索源该用的 Key（免 Key 的源恒为空串，见 [AiSearch.keyFor]） */
    fun aiSearchKey(providerId: String): String = AiSearch.keyFor(providerId, searchKeys)

    init {
        // 旧单配置 → 档案：P0/P1 存的是散键（ai_api_key + ai_base_url + ai_model_standard/thinking，
        // 更早还有 ai_model）。只要填过 Key 就拼成一个「DeepSeek」档案并设为对话配置，绝不丢 Key。
        // 放在 aiProfiles 等声明之后：属性按声明顺序初始化。
        //
        // 档案 JSON 损坏时**跳过迁移**：迁移会往 `ai_profiles` 写新内容，等于把损坏的原串
        // （里面可能还有能救回来的 Key）当场覆盖掉。先让用户看到提示、由他决定怎么处理。
        if (aiProfiles.value.isEmpty() && !aiProfilesCorrupt.value) {
            val legacyKey = prefs.getString("ai_api_key", "")?.trim().orEmpty()
            if (legacyKey.isNotBlank()) {
                val standard = prefs.getString("ai_model_standard", null)
                    ?: prefs.getString("ai_model", null)
                    ?: AiConfig.DEFAULT_MODEL
                val thinking = prefs.getString("ai_model_thinking", null).orEmpty()
                // base 先算出来：模型名归一只对官方端点生效，这里必须与档案实际写入的地址一致
                val baseUrl = prefs.getString("ai_base_url", "")?.trim().orEmpty()
                    .ifBlank { AiConfig.DEFAULT_BASE_URL }
                val models = normalizeProfileModels(
                    listOf(standard.trim(), thinking.trim()).filter { it.isNotEmpty() }
                        .ifEmpty { listOf(AiConfig.DEFAULT_MODEL) },
                    baseUrl,
                )
                val legacy = AiProfile(
                    name = "DeepSeek",
                    baseUrl = baseUrl,
                    apiKey = legacyKey,
                    models = models,
                )
                aiProfiles.value = listOf(legacy)
                aiChatProfileId.value = legacy.id
                secretPrefs.edit()
                    .putString("ai_profiles", AiProfileStore.encode(listOf(legacy)))
                    .apply()
                prefs.edit()
                    .putString("ai_chat_profile_id", legacy.id)
                    .apply()
            }
        }
        // 更老的散键 ai_api_key 已经进了档案（上面那段迁移）：同一份明文 Key 不该继续躺在
        // 会被云备份带走的 kaze_ui_settings 里。**先确认档案里确实有这个 Key 再删**，
        // 免得某条路径没迁移成功时把用户的 Key 直接抹掉。
        val legacyApiKey = prefs.getString("ai_api_key", "")?.trim().orEmpty()
        if (legacyApiKey.isNotBlank() && aiProfiles.value.any { it.apiKey.trim() == legacyApiKey }) {
            prefs.edit().remove("ai_api_key").apply()
        }
        // 存量档案里的旧模型名归一：deepseek-chat / deepseek-reasoner 在官方端点已停用
        // （2026-07-24），deepseek.com 的档案统一为 deepseek-flash，避免界面继续显示旧名
        // 让人误以为配置没问题（请求侧还有一层 requestModel 归一兜底）。
        // 第三方端点不改写（那里 deepseek-chat 可能仍是有效模型名）。
        val normalized = aiProfiles.value.map {
            it.copy(models = normalizeProfileModels(it.models, it.baseUrl))
        }
        if (normalized != aiProfiles.value) {
            aiProfiles.value = normalized
            secretPrefs.edit().putString("ai_profiles", AiProfileStore.encode(normalized)).apply()
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

    /**
     * 写档案列表（**唯一**写 `ai_profiles` 的入口）。
     *
     * 损坏状态下写入 = 用户显式覆盖：先把原串完整转存到 [CORRUPT_PROFILES_BACKUP_KEY]
     * （独立键，永不解析、永不被后续写入碰到），再覆盖 —— 这样"配置损坏"这条路上
     * 不会出现任何静默丢弃；想找回原文的人仍能在 prefs 里找到。
     */
    private fun persistAiProfiles(list: List<AiProfile>) {
        if (aiProfilesCorrupt.value) {
            secretPrefs.edit().putString(CORRUPT_PROFILES_BACKUP_KEY, rawAiProfiles).apply()
            aiProfilesCorrupt.value = false
        }
        secretPrefs.edit().putString("ai_profiles", AiProfileStore.encode(list)).apply()
    }

    /**
     * 用户显式清除损坏的档案数据（设置页那张提示卡上的按钮）。
     * 同样先备份原串再删键：清除的是"启动器的可用配置"，不是用户的数据。
     */
    fun clearCorruptAiProfiles() {
        if (rawAiProfiles.isNotEmpty()) {
            secretPrefs.edit().putString(CORRUPT_PROFILES_BACKUP_KEY, rawAiProfiles).apply()
        }
        secretPrefs.edit().remove("ai_profiles").apply()
        aiProfiles.value = emptyList()
        aiProfilesCorrupt.value = false
    }

    /** 新增或更新档案（按 id 覆盖，编辑保持原位置）；首个档案自动成为对话配置 */
    fun saveAiProfile(profile: AiProfile) {
        val list = if (aiProfiles.value.any { it.id == profile.id }) {
            aiProfiles.value.map { if (it.id == profile.id) profile else it }
        } else {
            aiProfiles.value + profile
        }
        aiProfiles.value = list
        persistAiProfiles(list)
        if (aiChatProfileId.value.isBlank() || aiProfiles.value.none { it.id == aiChatProfileId.value }) {
            setChatAiProfile(profile.id)
        }
    }

    /** 删除档案；删的是当前对话配置时回退到剩余第一个 */
    fun deleteAiProfile(id: String) {
        val list = aiProfiles.value.filterNot { it.id == id }
        aiProfiles.value = list
        persistAiProfiles(list)
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
     * 保存联网搜索配置（源 + 该源的 Key）。
     *
     * 只写**这个源自己的槽**：切源不会顺手把上一个源的 Key 复制过去
     * （那正是"A 家 Key 发给 B 家"的来源）。
     */
    fun setAiSearch(providerId: String, key: String) {
        val p = AiSearch.Provider.byId(providerId.trim())
        val k = key.trim()
        aiSearchProviderId.value = p.id
        searchKeys[p.id] = k
        prefs.edit().putString("ai_search_provider", p.id).apply()
        secretPrefs.edit().putString(AiSearch.Provider.keySlot(p.id), k).apply()
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
        /** 背景图保存后的最长边：显示端只做 Crop，超过这个尺寸纯属浪费内存与磁盘 */
        private const val BG_MAX_DIM = 1600

        /** 只装 AI 密钥的 prefs 文件名（备份规则按这个文件名排除，改它必须同步改两个 xml） */
        internal const val SECRET_PREFS_NAME = "kaze_ai_secrets"

        /**
         * 损坏档案原文的备份键。
         *
         * 用户明确覆盖/清除损坏数据时，原文先转存到这里：启动器不再解析它（也永远不会
         * 被后续写入碰到），但内容仍然留在设备上，需要时能人工找回。
         */
        internal const val CORRUPT_PROFILES_BACKUP_KEY = "ai_profiles_corrupt_raw"

        /**
         * 把 AI 密钥从旧的 `kaze_ui_settings` 搬进独立文件（一次性，构造 SettingsPrefs 时执行）。
         *
         * 判据是"旧文件里还有这些键"：搬完就从旧文件删掉，之后不再触发。新文件里已经有值
         *（全新安装、或从新版设备恢复）时**不覆盖** —— 恢复出来的 Key 比旧文件里的更可信。
         *
         * 搜索 Key 迁到**当前选中源自己的槽**：旧实现里它是一份被所有源共用的 Key，
         * 只能认为它是"给当时选中的那个源填的"，不能复制给其它源。
         */
        internal fun migrateAiSecrets(secret: android.content.SharedPreferences, legacy: android.content.SharedPreferences) {
            if (!legacy.contains("ai_profiles") && !legacy.contains("ai_search_key")) return
            val editor = secret.edit()
            if (!secret.contains("ai_profiles") && legacy.contains("ai_profiles")) {
                legacy.getString("ai_profiles", null)?.let { editor.putString("ai_profiles", it) }
            }
            if (legacy.contains("ai_search_key")) {
                val provider = AiSearch.Provider.byId(legacy.getString("ai_search_provider", "") ?: "")
                val slot = AiSearch.Provider.keySlot(provider.id)
                val old = legacy.getString("ai_search_key", "").orEmpty()
                if (!secret.contains(slot) && old.isNotEmpty()) editor.putString(slot, old)
            }
            editor.apply()
            // 旧文件里的这两个键必须清掉：它们正是"会被云备份带走"的那份明文密钥
            legacy.edit().remove("ai_profiles").remove("ai_search_key").apply()
        }

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
