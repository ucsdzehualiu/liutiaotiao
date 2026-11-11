plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.liutiaotiao"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.liutiaotiao"
        minSdk = 24
        targetSdk = 34
        versionCode = 17
        versionName = "1.16"
    }

    buildTypes {
        release {
            // 不混淆、不压缩：保证代码可审计，源码与你看到的完全一致，
            // 也能最大限度避免被安全软件误判为“加壳/混淆”的病毒。
            isMinifyEnabled = false
            isShrinkResources = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
        debug {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    implementation("androidx.appcompat:appcompat:1.6.1")

    // v1.14：本地 OCR（ML Kit 中文文字识别 **bundled 离线版**）。
    // 用于 SurfaceView/自绘广告的"跳过"按钮识别（真机实锤：ivy 等 App 的
    // 开屏广告整屏不在控件树里，无障碍物理读不到）。模型打包进 APK，
    // 推理全程本地，**不需要网络权限**（play-services-base 只是库基础设施，
    // 不引入 INTERNET 权限；装完后用 aapt 验证 merged manifest）。
    implementation("com.google.mlkit:text-recognition-chinese:16.0.1")

    // 纯逻辑单元测试（JVM，无需真机）
    testImplementation("junit:junit:4.13.2")
}
