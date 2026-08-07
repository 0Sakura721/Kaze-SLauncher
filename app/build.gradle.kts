plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

import java.io.File
import java.util.Properties

// ═══════════════════════════════════════════════════════════════
//  内置资源校验任务 — 全部资源已 commit 到 git，无需网络下载
//
//  布局（按 flavor 分架构，APK 只含对应架构的 rootfs）：
//  - src/main/assets/bundled/          proot 三架构（通用，共约 0.4MB）
//  - src/arm64v8/assets/bundled/       ubuntu arm64 rootfs（28.5MB）
//  - src/armv7/assets/bundled/         ubuntu armhf rootfs（25.8MB）
//  - src/universal/assets/bundled/     三个 rootfs（arm64+armhf+amd64）
//  注：Java 不再内置（按需本地导入/在线下载，JreInstaller 负责）
// ═══════════════════════════════════════════════════════════════
val downloadBundledAssets by tasks.registering {
    group = "bundled"
    description = "校验内置资源完整性（proot/rootfs 均已 commit，无需下载）"
    doLast {
        val missing = mutableListOf<String>()
        fun check(dir: File, name: String) {
            val f = File(dir, name)
            if (!f.exists() || f.length() == 0L) missing += "$dir/$name"
        }
        listOf("proot-aarch64.tar.gz", "proot-armhf.tar.gz", "proot-x86_64.tar.gz")
            .forEach { check(file("src/main/assets/bundled"), it) }
        check(file("src/arm64v8/assets/bundled"), "ubuntu-base-24.04-arm64.tar.gz")
        check(file("src/armv7/assets/bundled"), "ubuntu-base-24.04-armhf.tar.gz")
        listOf(
            "ubuntu-base-24.04-arm64.tar.gz",
            "ubuntu-base-24.04-armhf.tar.gz",
            "ubuntu-base-24.04-amd64.tar.gz"
        ).forEach { check(file("src/universal/assets/bundled"), it) }
        if (missing.isNotEmpty()) {
            throw GradleException("缺少内置资源: ${missing.joinToString()}")
        }
        println("✓ 内置资源完整（proot 三架构 + rootfs 按 flavor 就位）")
    }
}

android {
    namespace = "com.mcserver.launcher"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.mcserver.launcher"
        minSdk = 26; targetSdk = 35; versionCode = 100; versionName = "1.0.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        ndk { abiFilters += listOf("arm64-v8a", "armeabi-v7a") }
        buildConfigField("String", "BUILD_TIME", "\"${System.currentTimeMillis()}\"")
        buildConfigField("String", "GIT_COMMIT", "\"${try {
            ProcessBuilder("git", "rev-parse", "--short", "HEAD").start().inputStream.bufferedReader().readText().trim()
        } catch (_: Exception) { "unknown" }}\"")
    }

    // 架构维度：arm64v8 / armv7 / universal
    // assets 按 flavor 目录拆分（见 src/<flavor>/assets/bundled），
    // 每个 APK 只打包对应架构的 rootfs，显著减小体积
    flavorDimensions += "arch"
    productFlavors {
        create("arm64v8") {
            dimension = "arch"
            versionNameSuffix = "-arm64"
        }
        create("armv7") {
            dimension = "arch"
            versionNameSuffix = "-armv7"
        }
        create("universal") {
            dimension = "arch"
            versionNameSuffix = "-universal"
        }
    }

    val localProperties = Properties()
    val localPropertiesFile = rootProject.file("local.properties")
    if (localPropertiesFile.exists()) {
        localPropertiesFile.inputStream().use { localProperties.load(it) }
    }

    signingConfigs {
        // 优先用项目内置的 release keystore（app/release-keystore.jks，已 commit 到 git）
        // 这样本地和 CI 用同一份签名，覆盖安装不会因签名不一致而失败
        // 可通过 local.properties 的 storeFile/storePassword/keyAlias/keyPassword 覆盖
        val projectKeystore = file("release-keystore.jks")
        val overrideStoreFile = localProperties.getProperty("storeFile")
        val hasOverride = overrideStoreFile != null &&
            localProperties.getProperty("storePassword") != null &&
            localProperties.getProperty("keyAlias") != null &&
            localProperties.getProperty("keyPassword") != null
        if (hasOverride) {
            create("release") {
                storeFile = rootProject.file(overrideStoreFile)
                storePassword = localProperties.getProperty("storePassword")
                keyAlias = localProperties.getProperty("keyAlias")
                keyPassword = localProperties.getProperty("keyPassword")
            }
        } else if (projectKeystore.exists()) {
            create("release") {
                storeFile = projectKeystore
                storePassword = System.getenv("KEYSTORE_PASSWORD")
                    ?: localProperties.getProperty("storePassword")
                    ?: "kaze_slauncher_2026"
                keyAlias = System.getenv("KEY_ALIAS")
                    ?: localProperties.getProperty("keyAlias")
                    ?: "kaze_slauncher"
                keyPassword = System.getenv("KEY_PASSWORD")
                    ?: localProperties.getProperty("keyPassword")
                    ?: "kaze_slauncher_2026"
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            if (signingConfigs.findByName("release") != null) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
        debug {
            isMinifyEnabled = false
            isDebuggable = true
        }
    }

    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    kotlinOptions {
        jvmTarget = "17"
        // 全局声明 Material3 实验性 API，减少各文件 @OptIn 样板代码
        freeCompilerArgs += listOf("-opt-in=androidx.compose.material3.ExperimentalMaterial3Api")
    }
    buildFeatures { compose = true; buildConfig = true }
    lint {
        abortOnError = true
        checkReleaseBuilds = true
        // AGP 8.7.3 内置检查器与 Kotlin 2.1.0 不兼容，跳过此检查
        disable += "NullSafeMutableLiveData"
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2025.02.00")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.activity:activity-compose:1.10.1")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.9.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.9.0")
    implementation("androidx.navigation:navigation-compose:2.8.8")
    implementation("androidx.datastore:datastore-preferences:1.1.3")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.1")
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.documentfile:documentfile:1.0.1")
    implementation("io.coil-kt.coil3:coil-compose:3.1.0")
    implementation("io.coil-kt.coil3:coil-core:3.1.0")
    debugImplementation("androidx.compose.ui:ui-tooling")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}
