package com.kaze.newage.core.i18n

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import android.content.res.Configuration
import java.util.Locale

/**
 * 界面语言。
 *
 * ## 三种取值
 *  - [SYSTEM]：跟随系统语言 —— 系统是中文就显示中文，其他显示英文；
 *  - [ZH] / [EN]：强制指定，不看系统设置。
 *
 * ## 为什么不用 Android 原生的 `values-en/strings.xml`
 * 本项目的界面文案**过去全部硬编码在 Kotlin 里**（约 1800 处含中文的字符串字面量，
 * 分布在 66 个文件），没有 `strings.xml` 可用。把 1800 处逐一搬进 `strings.xml` 再引
 * `stringResource()` 是一次大规模重构，每处都要改调用点、还要处理 Compose 的 `@Composable`
 * 上下文与 `getString()` 的 `Context` 上下文两种取用方式。
 *
 * 这里改成**运行时字典**：把中文原文当 key 查表换成英文（见 [Strings]）。好处是
 *  - 不需要改任何调用点的上下文（普通函数里也能查）；
 *  - 未翻译的条目自动回退显示中文原文，**不会出现空白或 key**；
 *  - 翻译表集中在一个文件里，便于校对与新增语言。
 *
 * 代价是拿不到 Android 的自动语言回退与 `values-xx` 资源合并，所以 [SYSTEM] 的判定
 * 由本类自己做。
 */
enum class AppLanguage(val id: String, val label: String) {
    SYSTEM("system", "跟随系统 / Follow system"),
    ZH("zh", "简体中文"),
    EN("en", "English"),
    ;

    companion object {
        fun fromId(id: String?): AppLanguage =
            entries.firstOrNull { it.id == id } ?: SYSTEM
    }
}

/**
 * 语言解析与切换。
 *
 * 当前语言存成一个全局变量而不是 CompositionLocal：文案在很多**非 Composable** 的地方
 * 也要取（ViewModel、核心层错误消息、通知），CompositionLocal 覆盖不到那些地方。
 * 界面层通过 [LanguageState.revision] 触发重组（见 AppRoot 的用法）。
 */
object LanguageState {

    /**
     * 默认值与设置项默认值保持一致（SYSTEM = 跟随系统）。
     *
     * 真实运行里 [apply] 在 `Application.onCreate` 就会覆盖它，所以这个默认值只在
     * **尚未初始化**时可见。测试里断言的是中文原文，靠 Robolectric 的 locale
     * 固定为 zh-rCN 来保证（见 robolectric.properties 与各测试的 @Config），
     * 而不是靠这里的默认值 —— 默认值跟随产品意图才对。
     */
    @Volatile
    private var current: AppLanguage = AppLanguage.SYSTEM

    /** 解析后的实际语言（SYSTEM 会被解析成 ZH 或 EN） */
    @Volatile
    var resolved: Locale = Locale.SIMPLIFIED_CHINESE
        private set

    /**
     * 每次切换自增，界面读它来触发重组。
     *
     * **必须是 `mutableStateOf`**：旧实现是 `@Volatile var Int`，Compose 完全观察不到
     * 普通字段的写入，于是 `setAppLanguage` + `apply` 之后界面一张像素都不会变 ——
     * 表现就是「点了 English 没反应，退出重进才生效」。文件头注释写着「见 AppRoot 的用法」，
     * 但当时根本没有任何地方读它，注释描述的是一个并不存在的机制。
     *
     * 读法：在需要跟随语言重组的 Composable 里 `LanguageState.revision` 一下即可
     * （见 AppRoot 顶部的 `val languageRevision = LanguageState.revision`）。
     */
    var revision by androidx.compose.runtime.mutableStateOf(0)
        private set

    /** 当前是否显示中文 */
    val isChinese: Boolean get() = resolved.language == "zh"

    /** 应用设置值（App 启动时与设置页切换时调用） */
    fun apply(mode: AppLanguage, systemLocale: Locale) {
        current = mode
        resolved = when (mode) {
            AppLanguage.ZH -> Locale.SIMPLIFIED_CHINESE
            AppLanguage.EN -> Locale.ENGLISH
            AppLanguage.SYSTEM -> if (systemLocale.language == "zh") {
                Locale.SIMPLIFIED_CHINESE
            } else {
                Locale.ENGLISH
            }
        }
        revision++
    }

    fun apply(mode: AppLanguage, context: Context) {
        apply(mode, systemLocaleOf(context))
    }

    /** 读系统语言（不跟随应用的 per-app language，避免自我循环） */
    fun systemLocaleOf(context: Context): Locale {
        val cfg: Configuration = context.resources.configuration
        // minSdk 是 27（Android 8.1），`Configuration.getLocales()` 从 API 24 就有，
        // 所以下面的 24 判断恒真、else 分支是永远走不到的死代码 ——
        // `cfg.locale` 那一支早已过时（lint: ObsoleteSdkInt 就是指着这条）。
        return cfg.locales.takeIf { !it.isEmpty }?.get(0) ?: Locale.getDefault()
    }

    /** 当前模式（设置页显示用） */
    fun mode(): AppLanguage = current
}