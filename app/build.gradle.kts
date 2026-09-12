import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.roborazzi)
}

// ── 发布签名凭据 ──
// 优先取环境变量（CI Secret），其次取 local.properties（已被 .gitignore 忽略）。
// 绝不硬编码口令，也绝不用 debug.keystore 签正式包：该密钥连同口令都在公开仓库里，
// 任何人都能伪造一个「签名匹配、versionCode 更高」的 APK 被系统当作合法升级安装。
// 详见 docs/RELEASE-SIGNING.md
// 注意：这里必须用顶部 import 进来的 Properties，不能写 `java.util.Properties()` ——
// 在 Gradle Kotlin DSL 里裸写 `java` 会被解析成 java 插件扩展而不是包名。
val localProps = Properties().apply {
    val f = rootProject.file("local.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}
fun secret(envKey: String, propKey: String): String? =
    (System.getenv(envKey) ?: localProps.getProperty(propKey))?.takeIf { it.isNotBlank() }

val releaseStorePath = secret("KAZE_KEYSTORE", "kaze.keystore")
val releaseStorePass = secret("KAZE_STORE_PASS", "kaze.storePassword")
val releaseKeyAlias = secret("KAZE_KEY_ALIAS", "kaze.keyAlias")
val releaseKeyPass = secret("KAZE_KEY_PASS", "kaze.keyPassword")
val hasReleaseKey = listOf(releaseStorePath, releaseStorePass, releaseKeyAlias, releaseKeyPass)
    .all { it != null }

android {
    namespace = "com.kaze.newage"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.kaze.newage"
        minSdk = 27
        targetSdk = 35
        versionCode = 4
        versionName = "0.2.0"
    }

    // ABI flavor：每包只带本架构的 native 库与 rootfs 资产；universal 全量（分发用）
    flavorDimensions += "abi"
    productFlavors {
        create("arm64") {
            dimension = "abi"
            ndk { abiFilters += "arm64-v8a" }
        }
        create("armhf") {
            dimension = "abi"
            ndk { abiFilters += "armeabi-v7a" }
        }
        create("universal") {
            dimension = "abi"
            ndk {
                abiFilters += "arm64-v8a"
                abiFilters += "armeabi-v7a"
            }
        }
    }

    // 注意：signingConfigs 必须写在 buildTypes **之前** —— Kotlin DSL 顺序执行，
    // 否则 buildTypes 里 getByName("release") 会因配置尚未创建而失败。
    signingConfigs {
        getByName("debug") {
            storeFile = rootProject.file("debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
        if (hasReleaseKey) {
            create("release") {
                storeFile = rootProject.file(releaseStorePath!!)
                storePassword = releaseStorePass
                keyAlias = releaseKeyAlias
                keyPassword = releaseKeyPass
            }
        }
    }
    buildTypes {
        release {
            // 开启 R8：未使用的 Material 图标此前会被整包打进 APK
            // （实测 dex 里有 5.7 万处 material/icons/ 引用，而代码只用到 28 个图标）
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            // 拿不到发布密钥时不签名（产出 unsigned 包），而不是回退到 debug 密钥
            signingConfig = if (hasReleaseKey) signingConfigs.getByName("release") else null
        }
        debug {
            // 工作区内 keystore（沙箱环境无法写 ~/.android）
            signingConfig = signingConfigs.getByName("debug")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    buildFeatures {
        compose = true
        // 设置页要显示真实版本号（此前硬编码 "v0.1.0"，与 versionName 长期不一致）
        buildConfig = true
    }
    testOptions {
        // 单测只覆盖纯逻辑（版本比较 / server.properties 读写 / 控制台解析），不触碰 Android API
        unitTests.isReturnDefaultValues = true
        // Roborazzi 截图测试（Robolectric）需要真实 Android 资源
        unitTests.isIncludeAndroidResources = true
    }

    // universal 变体：assets 直接复用 arm64 + armhf 两套（避免复制实体文件导致仓库膨胀）
    sourceSets {
        getByName("universal") {
            assets.srcDirs("src/arm64/assets", "src/armhf/assets")
        }
    }
}

dependencies {
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    // collectAsStateWithLifecycle：界面退到后台后停止收集流（本应用常驻前台服务，界面常在后台）
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)
    implementation(libs.androidx.material.icons.extended)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.haze)
    debugImplementation(libs.androidx.ui.tooling)
    testImplementation(libs.junit)
    // ── Roborazzi 截图测试（纯 JVM，无需模拟器/安装 APK）──
    testImplementation(platform(libs.androidx.compose.bom))
    testImplementation(libs.robolectric)
    testImplementation(libs.roborazzi)
    testImplementation(libs.roborazzi.compose)
    testImplementation(libs.androidx.ui.test.junit4)
    debugImplementation(libs.androidx.ui.test.manifest)
}

/**
 * Roborazzi 的 PNG 是**测试进程的副作用产物**，不是测试任务声明的输出，
 * 因此不会随 Gradle 构建缓存一起恢复。
 *
 * 后果：连续两次 `recordRoborazzi*` 而代码没变时，`testArm64DebugUnitTest` 会命中缓存
 * （FROM-CACHE），`recordRoborazzi*` 显示 UP-TO-DATE 并"构建成功"，但 `build/screenshots/`
 * 里**一张图都不会有** —— 看起来像功能坏了，实际只是没跑。
 *
 * 这里让单元测试不参与缓存/最新性判断，保证 `record` 每次都真的重新渲染。
 * （测试规模很小，重跑成本可接受；换取的是"跑截图一定出图"。）
 */
tasks.withType<Test>().configureEach {
    outputs.upToDateWhen { false }
    outputs.cacheIf { false }
}
