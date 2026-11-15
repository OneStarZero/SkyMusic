plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.skymusic"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.skymusic.mp3"
        minSdk = 24
        targetSdk = 34
        versionCode = 7
        versionName = "0.0.7"

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
        viewBinding = false
    }

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
    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.appcompat:appcompat:1.6.1")
    // 🌟 Google LiteRT (原 TensorFlow Lite) 推理引擎
    implementation("com.google.ai.edge.litert:litert:1.0.1")
    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test.ext:junit:1.1.5")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.5.1")
}
