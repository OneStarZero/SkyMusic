plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.skymusic"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.skymusic"
        minSdk = 23
        targetSdk = 34
        versionCode = 3 // 更新版本号
        versionName = "0.0.3"

        // 只保留中文和英文，剔除多余的国际化资源
        resourceConfigurations += listOf("zh", "en")
    }

    buildTypes {
        release {
            // 🚀 性能优化：开启代码混淆和压缩，极小化 APK 体积
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            signingConfig = signingConfigs.getByName("release")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    androidResources {
        noCompress("html")
    }
    
    signingConfigs {
        create("release") {
            storeFile = file(System.getenv("KEYSTORE_PATH") ?: "../mykey.jks")
            storePassword = System.getenv("KEYSTORE_PASSWORD")
            keyAlias = System.getenv("KEY_ALIAS")
            keyPassword = System.getenv("KEY_PASSWORD")
        }
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.appcompat:appcompat:1.6.1")
    // 移除了未使用的 material 库以减少体积
}
