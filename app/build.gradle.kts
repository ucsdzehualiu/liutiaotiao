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
        versionCode = 13
        versionName = "1.12"
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

    // 纯逻辑单元测试（JVM，无需真机）
    testImplementation("junit:junit:4.13.2")
}
