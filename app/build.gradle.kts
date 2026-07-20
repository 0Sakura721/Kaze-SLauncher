import java.net.HttpURLConnection
import java.net.URL
import java.io.FileOutputStream

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

// ═══════════════════════════════════════════════════════════════
//  内置资源辅助任务（CI 自动运行）
//
//  下载 proot + Ubuntu rootfs 到 assets/bundled/，
//  APK 内置这些文件后用户首次启动零下载。
// ═══════════════════════════════════════════════════════════════
val bundledAssetsDir = layout.projectDirectory.dir("src/main/assets/bundled")

val downloadBundledAssets by tasks.registering {
    group = "bundled"
    description = "下载 proot + Ubuntu 24.04 rootfs 到 assets/bundled/"

    // Ubuntu 24.04.4 base rootfs + proot（Termux 官方包）
    val ubuntuVersion = "24.04.4"
    val files = linkedMapOf(
        "ubuntu-base-24.04-arm64.tar.gz" to
            "https://cdimage.ubuntu.com/ubuntu-base/releases/24.04/release/ubuntu-base-$ubuntuVersion-base-arm64.tar.gz",
        "ubuntu-base-24.04-armhf.tar.gz" to
            "https://cdimage.ubuntu.com/ubuntu-base/releases/24.04/release/ubuntu-base-$ubuntuVersion-base-armhf.tar.gz",
        "proot-aarch64" to
            "https://packages.termux.dev/apt/termux-main/pool/main/p/proot/proot_5.1.107.85_aarch64.deb",
        "proot-armhf" to
            "https://packages.termux.dev/apt/termux-main/pool/main/p/proot/proot_5.1.107.85_arm.deb",
    )

    doLast {
        val destDir = bundledAssetsDir.asFile
        destDir.mkdirs()

        fun download(urlStr: String, dest: java.io.File): Boolean {
            if (dest.exists() && dest.length() > 0) {
                println("  ⏭ 跳过: ${dest.name}")
                return true
            }
            try {
                println("  ⬇ ${dest.name} ...")
                val conn = URL(urlStr).openConnection() as HttpURLConnection
                conn.connectTimeout = 60000
                conn.readTimeout = 300000
                conn.instanceFollowRedirects = true
                var c = conn
                for (i in 1..5) {
                    val code = c.responseCode
                    if (code in listOf(301, 302, 307, 308)) {
                        val loc = c.getHeaderField("Location") ?: break
                        c.disconnect(); c = URL(loc).openConnection() as HttpURLConnection
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

        // 从 Termux .deb 提取 proot
        fun extractProot(deb: java.io.File, name: String) {
            try {
                val target = java.io.File(deb.parentFile, name)
                val tmp = java.io.File(deb.parentFile, "_x_$name"); tmp.mkdirs()
                val p = ProcessBuilder("sh", "-c",
                    "dpkg-deb -x '${deb.absolutePath}' '${tmp.absolutePath}' && " +
                    "cp '${tmp.absolutePath}/data/data/com.termux/files/usr/bin/proot' '${target.absolutePath}' && " +
                    "chmod 755 '${target.absolutePath}' && rm -rf '${tmp.absolutePath}' '${deb.absolutePath}'"
                ).start()
                if (p.waitFor() == 0) {
                    println("  ✓ proot $name (${target.length() / 1024} KB)")
                    return
                }
                // dpkg-deb 不可用，手动解析 ar/tar
                println("  ⚠ 手动解析 .deb...")
                val b = deb.readBytes()
                val di = b.indexOf("data.tar".toByteArray())
                if (di < 0) { println("  ✗ $name 格式异常"); return }
                val hs = String(b, di - 48, 60, Charsets.ISO_8859_1)
                val ds = Regex("(\\d+)").find(hs.substringAfterLast("data.tar"))?.value?.toLongOrNull() ?: 0L
                if (ds <= 0) { println("  ✗ $name 大小解析失败"); return }
                val tb = b.copyOfRange(di - 48 + 60, (di - 48 + 60 + ds).toInt())
                val fi = tb.indexOf("data/data/com.termux/files/usr/bin/proot".toByteArray())
                if (fi < 0) { println("  ✗ $name 找不到 proot"); return }
                val fs = String(tb, fi + 124, 12, Charsets.UTF_8).trim { it <= ' ' || it == '\u0000' }.toLong(8)
                target.writeBytes(tb.copyOfRange(fi + 512, (fi + 512 + fs).toInt()))
                target.setExecutable(true); deb.delete()
                println("  ✓ proot $name (${target.length() / 1024} KB)")
            } catch (e: Exception) { System.err.println("  ✗ $name: ${e.message}") }
        }

        println("═══ 下载预置资源 ═══")
        var ok = true
        files.forEach { (name, url) ->
            val d = java.io.File(destDir, name)
            if (download(url, d)) { if (name.startsWith("proot-")) extractProot(d, name) }
            else ok = false
        }
        println("═══ 完成 ${if (ok) "✓" else "(有失败项)"} ═══")
    }
}

android {
    namespace = "com.mcserver.launcher"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.mcserver.launcher"
        minSdk = 26; targetSdk = 35; versionCode = 10; versionName = "0.10.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        ndk { abiFilters += listOf("arm64-v8a", "armeabi-v7a") }
        buildConfigField("String", "BUILD_TIME", "\"${System.currentTimeMillis()}\"")
        buildConfigField("String", "GIT_COMMIT", "\"${try {
            ProcessBuilder("git", "rev-parse", "--short", "HEAD").start().inputStream.bufferedReader().readText().trim()
        } catch (_: Exception) { "unknown" }}\"")
    }

    signingConfigs {
        create("release") { storeFile = rootProject.file("debug.keystore"); storePassword = "kaze123"; keyAlias = "kaze_debug"; keyPassword = "kaze123" }
        create("kazeDebug") { storeFile = rootProject.file("debug.keystore"); storePassword = "kaze123"; keyAlias = "kaze_debug"; keyPassword = "kaze123" }
    }

    buildTypes {
        release { isMinifyEnabled = true; isShrinkResources = true; signingConfig = signingConfigs.getByName("release"); proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro") }
        debug { isMinifyEnabled = false; signingConfig = signingConfigs.getByName("kazeDebug"); isDebuggable = true }
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