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
// ═══════════════════════════════════════════════════════════════
val bundledAssetsDir = layout.projectDirectory.dir("src/main/assets/bundled")

val downloadBundledAssets by tasks.registering {
    group = "bundled"
    description = "下载 proot + Ubuntu rootfs 到 assets/bundled/"

    val ubuntuVersion = "24.04.4"
    val files = linkedMapOf(
        "ubuntu-base-24.04-arm64.tar.gz" to
            "https://cdimage.ubuntu.com/ubuntu-base/releases/24.04/release/ubuntu-base-$ubuntuVersion-base-arm64.tar.gz",
        "ubuntu-base-24.04-armhf.tar.gz" to
            "https://cdimage.ubuntu.com/ubuntu-base/releases/24.04/release/ubuntu-base-$ubuntuVersion-base-armhf.tar.gz",
        "proot-aarch64.deb" to
            "https://packages.termux.dev/apt/termux-main/pool/main/p/proot/proot_5.1.107.85_aarch64.deb",
        "proot-armhf.deb" to
            "https://packages.termux.dev/apt/termux-main/pool/main/p/proot/proot_5.1.107.85_arm.deb",
    )

    doLast {
        val destDir = bundledAssetsDir.asFile
        destDir.mkdirs()

        fun download(urlStr: String, dest: File): Boolean {
            if (dest.exists() && dest.length() > 0) { println("  ⏭ ${dest.name}"); return true }
            try {
                println("  ⬇ ${dest.name} ...")
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

        // 辅助：ByteArray 中查找子数组
        fun indexOfBytes(haystack: ByteArray, needle: String): Int {
            val n = needle.toByteArray()
            for (i in 0..haystack.size - n.size) {
                var found = true
                for (j in n.indices) { if (haystack[i + j] != n[j]) { found = false; break } }
                if (found) return i
            }
            return -1
        }

        // 从 Termux .deb 提取 proot（优先 dpkg-deb，回退手动 ar/tar）
        fun extractProot(debFile: File, targetNameBase: String) {
            val target = File(debFile.parentFile, targetNameBase)
            try {
                val tmp = File(debFile.parentFile, "_x_$targetNameBase"); tmp.mkdirs()
                val p = ProcessBuilder("sh", "-c",
                    "dpkg-deb -x '${debFile.absolutePath}' '${tmp.absolutePath}' && " +
                    "cp '${tmp.absolutePath}/data/data/com.termux/files/usr/bin/proot' '${target.absolutePath}' && " +
                    "chmod 755 '${target.absolutePath}' && rm -rf '${tmp.absolutePath}' '${debFile.absolutePath}'"
                ).start()
                if (p.waitFor() == 0) {
                    println("  ✓ proot $targetNameBase (${target.length() / 1024} KB)")
                    return
                }
                // dpkg-deb 不可用，手动解析
                println("  ⚠ 手动解析 .deb ...")
                val b = debFile.readBytes()
                val di = indexOfBytes(b, "data.tar")
                if (di < 0) { System.err.println("  ✗ $targetNameBase 格式异常"); return }
                val arHeader = String(b, di - 48, 60, Charsets.ISO_8859_1)
                val m = Regex("(\\d+)").find(arHeader.substringAfterLast("data.tar"))
                val dataSize = m?.value?.toLongOrNull() ?: 0L
                if (dataSize <= 0) { System.err.println("  ✗ $targetNameBase 大小解析失败"); return }
                val tarStart = di - 48 + 60
                val tarData = b.copyOfRange(tarStart, (tarStart + dataSize).toInt())
                val fi = indexOfBytes(tarData, "data/data/com.termux/files/usr/bin/proot")
                if (fi < 0) { System.err.println("  ✗ $targetNameBase 找不到 proot"); return }
                val sizeStr = String(tarData, fi + 124, 12, Charsets.UTF_8).trim { it <= ' ' || it == '\u0000' }
                val fileSize = sizeStr.toLong(8)
                target.writeBytes(tarData.copyOfRange(fi + 512, (fi + 512 + fileSize).toInt()))
                target.setExecutable(true); debFile.delete()
                println("  ✓ proot $targetNameBase (${target.length() / 1024} KB)")
            } catch (e: Exception) {
                System.err.println("  ✗ $targetNameBase: ${e.message}")
            }
        }

        println("═══ 下载预置资源 ═══")
        var ok = true
        files.forEach { (name, url) ->
            val d = File(destDir, name)
            if (download(url, d)) {
                if (name.endsWith(".deb")) {
                    extractProot(d, name.removeSuffix(".deb"))
                }
            } else ok = false
        }
        println("═══ 完成 ${if (ok) "✓" else "(有失败项)"} ═══")
    }
}

android {
    namespace = "com.mcserver.launcher"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.mcserver.launcher"
        minSdk = 26; targetSdk = 35; versionCode = 13; versionName = "0.10.3"
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