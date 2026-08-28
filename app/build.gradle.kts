plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "com.kaze.newage"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.kaze.newage"
        minSdk = 27
        targetSdk = 35
        versionCode = 3
        versionName = "0.1.2"
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

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
        debug {
            // 工作区内 keystore（沙箱环境无法写 ~/.android）
            signingConfig = signingConfigs.getByName("debug")
        }
    }
    signingConfigs {
        getByName("debug") {
            storeFile = rootProject.file("debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
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
}
