plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.skymusic"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.skymusic"
        minSdk = 26
        targetSdk = 34
        versionCode = 6
        versionName = "0.0.6"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        
        // 剥离无用语言资源，进一步减小安装包
        resourceConfigurations += listOf("zh", "en")
    }

    buildTypes {
        release {
            // 开启代码混淆与压缩
            isMinifyEnabled = true
            // 开启资源压缩（剔除无用图片/XML）
            isShrinkResources = true
            proguardFiles(
                // 必须使用 optimize 版本
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            
            // 清除无用架构（如果你后续加了 native 库，这里可以改成只保留 "arm64-v8a"）
            ndk {
                abiFilters.clear()
            }
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
        viewBinding = true
    }
}

dependencies {
    // 基础 AndroidX 依赖
    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.appcompat:appcompat:1.6.1")
    implementation("com.google.android.material:material:1.11.0")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")
    
    // 如果你有使用协程或生命周期扩展，可以保留/添加这些
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.7.0")
    
    // 测试依赖（可选）
    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test.ext:junit:1.1.5")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.5.1")
}
