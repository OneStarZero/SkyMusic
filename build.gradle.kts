android {
    // ... 其他配置 ...

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
            
            // 开启极致打包配置 (移除无用语言资源等)
            ndk {
                abiFilters.clear() // 如果没有 native 库，直接清空；如果有，只保留 "arm64-v8a"
            }
        }
    }
    
    // 剥离无用语言资源，进一步减小安装包
    defaultConfig {
        resourceConfigurations += listOf("zh", "en")
    }
}
