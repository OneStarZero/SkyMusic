# 保留 JS 桥接接口
-keepclassmembers class com.skymusic.MainActivity$AndroidBridge {
    public *;
}
-keep class com.skymusic.MainActivity$AndroidBridge { *; }

# 保留无障碍服务
-keep class com.skymusic.SkyAccessibilityService { *; }
-keep class com.skymusic.FloatingWindowService { *; }

# 保留 WebView JS 接口注解
-keepattributes JavascriptInterface
-keepclassmembers class * {
    @android.webkit.JavascriptInterface <methods>;
}

# Kotlin 元数据
-keep class kotlin.Metadata { *; }
