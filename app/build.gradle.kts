plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.skymusic"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.skymusic"
        minSdk = 24
        targetSdk = 34
        versionCode = 6
        versionName = "0.0.6"

        // 【体积压缩】只保留中英文
        resourceConfigurations += listOf("zh", "en")
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            
            // 【体积压缩】直接清空 NDK 架构（纯 Java/Kotlin 项目不需要）
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
    
    // 【体积压缩】关闭 ViewBinding
    buildFeatures {
        viewBinding = false
    }

    // 【体积压缩】剔除打包时的无用占位文件
    packaging {
        resources {
            excludes += setOf(
                "META-INF/*.kotlin_module", 
                "META-INF/*.version", 
                "DebugProbesKt.bin", 
                "META-INF/DEPENDENCIES", 
                "META-INF/LICENSE*", 
                "META-INF/NOTICE*"
            )
        }
    }
}

dependencies {
    // WebView 壳子必须的核心库
    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.appcompat:appcompat:1.6.1")
    
    // 🚨 注意：你的 MainActivity 是纯代码写的 FrameLayout，没有用到 XML 布局
    // 所以 constraintlayout 完全不需要，删掉可以再省几百 KB
    // implementation("androidx.constraintlayout:constraintlayout:2.1.4")
    
    // 🚨 注意：你的 MainActivity 没有使用 lifecycleScope 之类的协程，这个也可以删掉
    // implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.7.0")
    
    // 测试依赖（打包时不会进 APK，保留着就行）
    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test.ext:junit:1.1.5")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.5.1")
}