plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.skymusic" // 这就是包名
    compileSdk = 33 // 安卓13

    defaultConfig {
        applicationId = "com.skymusic"
        minSdk = 28 // 28 就是 Android 9.0！兼容安卓9及以上
        targetSdk = 33
        versionCode = 1 // 版本号 1
        versionName = "0.0.1" // 版本名称 0.0.1
    }

    buildTypes {
        release {
            isMinifyEnabled = false
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
    // 防止打包时把 assets 里的 html 压缩
    androidResources {
        noCompress("html")
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.10.1")
    implementation("androidx.appcompat:appcompat:1.6.1")
    implementation("com.google.android.material:material:1.9.0")
}
