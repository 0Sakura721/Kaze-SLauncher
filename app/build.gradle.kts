import java.net.HttpURLConnection
import java.net.URL
import java.io.FileOutputStream
import java.io.File
import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

// ═══════════════════════════════════════════════════════════════
//  内置资源任务 — CI 自动运行，首次启动零下载
//
//  说明：
//  - proot-{aarch64,armhf}.tar.gz（含 proot 二进制 + loader + libtalloc +
//    libandroid-shmem）已 commit 到 git（共约 228 KB），无需 task 下载
//  - Ubuntu 24.04 rootfs（arm64 + armhf，约 55 MB）太大，由本 task 在 CI
//    上下载到 assets/bundled/，避免 git 仓库膨胀
// ═══════════════════════════════════════════════════════════════
val bundledAssetsDir = layout.projectDirectory.dir("src/main/assets/bundled")

val downloadBundledAssets by tasks.registering {
    group = "bundled"
    description = "下载 Ubuntu 24.04 rootfs 到 assets/bundled/（proot 已内置 commit）"

    val ubuntuVersion = "24.04.4"
    // 内置 Java 全部版本（8/11/17/21）+ JDK/JRE + aarch64/armhf
    // 命名约定：java-{version}-{jdk|jre}-{arch}.tar.gz
    val javaVersions = listOf(21, 17, 11, 8)
    val javaFiles = linkedMapOf<String, List<String>>()
    javaVersions.forEach { ver ->
        val adoptiumArch = { arch: String ->
            when (arch) {
                "aarch64" -> "aarch64"
                "armhf" -> "arm"
                else -> arch
            }
        }
        listOf("jdk" to "jdk", "jre" to "jre").forEach { (pkg, apiPkg) ->
            listOf("aarch64", "armhf").forEach { arch ->
                val adoptiumArchName = adoptiumArch(arch)
                javaFiles["java-$ver-$pkg-$arch.tar.gz"] = listOf(
                    // 主源：Adoptium API，自动返回最新 GA 版本
                    "https://api.adoptium.net/v3/binary/latest/$ver/ga/linux/$adoptiumArchName/$apiPkg/hotspot/normal/eclipse",
                    // 备用：阿里云镜像（固定版本，可能滞后）
                    "https://mirrors.aliyun.com/adoptium/$ver/$pkg/$adoptiumArchName/linux/${ver}u-latest_${pkg}_linux-${adoptiumArchName}_bin.tar.gz"
                )
            }
        }
    }

    val files = linkedMapOf(
        "ubuntu-base-24.04-arm64.tar.gz" to listOf(
            "https://cdimage.ubuntu.com/ubuntu-base/releases/24.04/release/ubuntu-base-$ubuntuVersion-base-arm64.tar.gz"
        ),
        "ubuntu-base-24.04-armhf.tar.gz" to listOf(
            "https://cdimage.ubuntu.com/ubuntu-base/releases/24.04/release/ubuntu-base-$ubuntuVersion-base-armhf.tar.gz"
        ),
    )
    // 加入所有 Java 版本
    files.putAll(javaFiles)

    doLast {
        val destDir = bundledAssetsDir.asFile
        destDir.mkdirs()

        fun downloadOne(urlStr: String, dest: File): Boolean {
            if (dest.exists() && dest.length() > 0) { println("  ⏭ ${dest.name}"); return true }
            try {
                println("  ⬇ ${dest.name} (via ${urlStr.take(60)}...)")
                val conn = URL(urlStr).openConnection() as HttpURLConnection
                conn.connectTimeout = 60000; conn.readTimeout = 300000
                conn.instanceFollowRedirects = true
                var c = conn
                for (i in 1..5) {
                    val code = c.responseCode
                    if (code in listOf(301, 302, 307, 308)) {
                        val loc = c.getHeaderField("Location") ?: break
                        c.disconnect()
                        c = URL(loc).openConnection() as HttpURLConnection
                        c.connectTimeout = 60000; c.readTimeout = 300000
                    } else break
                }
                check(c.responseCode == 200) { "HTTP ${c.responseCode}" }
                val total = c.contentLengthLong
                FileOutputStream(dest).use { out ->
                    c.inputStream.use { inp ->
                        val buf = ByteArray(8192); var read: Int; var d = 0L
                        while (inp.read(buf).also { read = it } != -1) {
                            out.write(buf, 0, read); d += read
                            if (total > 0 && d % (5 * 1024 * 1024) == 0L)
                                print("\r    ${d * 100 / total}%")
                        }
                    }
                }
                c.disconnect()
                println("\r  ✓ ${dest.name} (${dest.length() / 1024 / 1024} MB)")
                return true
            } catch (e: Exception) {
                System.err.println("  ✗ ${dest.name}: ${e.message}")
                dest.delete(); return false
            }
        }

        fun download(urls: List<String>, dest: File): Boolean {
            for (url in urls) {
                if (downloadOne(url, dest)) return true
            }
            return false
        }

        println("═══ 下载 Ubuntu rootfs ═══")
        var ok = true
        files.forEach { (name, urls) ->
            if (!download(urls, File(destDir, name))) ok = false
        }
        // 验证 proot tarball 已 commit
        listOf("proot-aarch64.tar.gz", "proot-armhf.tar.gz").forEach { name ->
            val f = File(destDir, name)
            if (!f.exists() || f.length() == 0L) {
                System.err.println("  ✗ 缺少内置资源 $name（应已 commit 到 git）")
                ok = false
            } else {
                println("  ✓ $name (${f.length() / 1024} KB, 内置)")
            }
        }
        // 验证 Java 资源
        val javaAssetNames = javaFiles.keys.toList()
        javaAssetNames.forEach { name ->
            val f = File(destDir, name)
            if (!f.exists() || f.length() == 0L) {
                System.err.println("  ✗ 缺少 Java 资源 $name")
                ok = false
            } else {
                println("  ✓ $name (${f.length() / 1024 / 1024} MB)")
            }
        }
        println("═══ 完成 ${if (ok) "✓" else "(有失败项)"} ═══")
    }
}

android {
    namespace = "com.mcserver.launcher"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.mcserver.launcher"
        minSdk = 26; targetSdk = 35; versionCode = 21; versionName = "0.13.5-pre"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        ndk { abiFilters += listOf("arm64-v8a", "armeabi-v7a") }
        buildConfigField("String", "BUILD_TIME", "\"${System.currentTimeMillis()}\"")
        buildConfigField("String", "GIT_COMMIT", "\"${try {
            ProcessBuilder("git", "rev-parse", "--short", "HEAD").start().inputStream.bufferedReader().readText().trim()
        } catch (_: Exception) { "unknown" }}\"")
    }

    val localProperties = Properties()
    val localPropertiesFile = rootProject.file("local.properties")
    if (localPropertiesFile.exists()) {
        localPropertiesFile.inputStream().use { localProperties.load(it) }
    }

    signingConfigs {
        val hasReleaseSigning = localProperties.getProperty("storeFile") != null &&
            localProperties.getProperty("storePassword") != null &&
            localProperties.getProperty("keyAlias") != null &&
            localProperties.getProperty("keyPassword") != null
        if (hasReleaseSigning) {
            create("release") {
                storeFile = rootProject.file(localProperties.getProperty("storeFile"))
                storePassword = localProperties.getProperty("storePassword")
                keyAlias = localProperties.getProperty("keyAlias")
                keyPassword = localProperties.getProperty("keyPassword")
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
    kotlinOptions { jvmTarget = "17" }
    buildFeatures { compose = true; buildConfig = true }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.06.00")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.activity:activity-compose:1.9.0")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.2")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.2")
    implementation("androidx.navigation:navigation-compose:2.7.7")
    implementation("androidx.datastore:datastore-preferences:1.1.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("io.coil-kt.coil3:coil-compose:3.0.4")
    implementation("io.coil-kt.coil3:coil-core:3.0.4")
    debugImplementation("androidx.compose.ui:ui-tooling")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}