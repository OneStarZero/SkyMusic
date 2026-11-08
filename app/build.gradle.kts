plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.skymusic"
    compileSdk = 34

    // 1. 签名配置必须放在最前面，且用 create() 显式创建
    signingConfigs {
        create("release") {
            // 指向 GitHub Actions 在根目录解压出的 mykey.jks
            storeFile = file(System.getenv("KEYSTORE_PATH") ?: "../mykey.jks")
            storePassword = System.getenv("KEYSTORE_PASSWORD")
            keyAlias = System.getenv("KEY_ALIAS")
            keyPassword = System.getenv("KEY_PASSWORD")
        }
    }

    defaultConfig {
        applicationId = "com.skymusic"
        minSdk = 23
        targetSdk = 34
        versionCode = 3
        versionName = "0.0.3"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            // 2. 现在这里引用就不会找不到 release 了
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
        noCompress.add("html")
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.appcompat:appcompat:1.6.1")
    implementation("com.google.android.material:material:1.11.0")
}
