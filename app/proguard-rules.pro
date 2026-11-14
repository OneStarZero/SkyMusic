# ==========================================
# 🛡️ WebView 与 JS 交互防混淆
# ==========================================
-keepclassmembers class * {
    @android.webkit.JavascriptInterface <methods>;
}
-keep class com.skymusic.MainActivity$AndroidBridge { *; }
-keep class com.skymusic.MainActivity { *; }

# ==========================================
# 🛡️ 无障碍服务保活 (防止被 R8 裁剪)
# ==========================================
-keep class com.skymusic.SkyAccessibilityService { *; }
-keep public class * extends android.accessibilityservice.AccessibilityService

# ==========================================
# 🛡️ 数据类防混淆 (传输 notes/songData)
# ==========================================
-keep class kotlin.Pair { *; }
-keep class com.skymusic.FloatingWindowService { *; }
# 保留 Serializable 的所有内容
-keepclassmembers class * implements java.io.Serializable {
    static final long serialVersionUID;
    private static final java.io.ObjectStreamField[] serialPersistentFields;
    !static !transient <fields>;
    private void writeObject(java.io.ObjectOutputStream);
    private void readObject(java.io.ObjectInputStream);
    java.lang.Object writeReplace();
    java.lang.Object readResolve();
}
