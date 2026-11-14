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

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        
        // 【体积压缩】只保留中英文，剔除其他所有语言包
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
            
            // 【体积压缩】如果没有 C++ 库，直接清空 NDK 架构，减小 apk
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
    
    // 【体积压缩】关闭 ViewBinding（如果你代码里没用到的话）
    buildFeatures {
        viewBinding = false
    }

    // 【体积压缩】剔除打包时的无用占位文件
    packaging {
        resources {
            excludes += setOf("META-INF/*.kotlin_module", "META-INF/*.version", "DebugProbesKt.bin", "META-INF/DEPENDENCIES", "META-INF/LICENSE*", "META-INF/NOTICE*")
        }
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.appcompat:appcompat:1.6.1")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")
    
    // 如果你的代码里没有大量使用 Material 组件，可以注释掉下面这行，能省几百 KB
    // implementation("com.google.android.material:material:1.11.0")
    
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.7.0")
    
    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test.ext:junit:1.1.5")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.5.1")
}
