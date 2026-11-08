# 保留 WebView 的 JS 接口不被混淆
-keepclassmembers class com.skymusic.MainActivity$WebAppInterface {
    public *;
}
-keepattributes JavascriptInterface
-keepclassmembers class * {
    @android.webkit.JavascriptInterface <methods>;
}

# 保留无障碍服务的类名
-keep class com.skymusic.SkyAccessibilityService { *; }

# 保留悬浮窗服务
-keep class com.skymusic.FloatingWindowService { *; }

# 保留实体类（如果有序列化需求）
-keepclassmembers class * implements java.io.Serializable {
    static final long serialVersionUID;
    private static final java.io.ObjectStreamField[] serialPersistentFields;
    private void writeObject(java.io.ObjectOutputStream);
    private void readObject(java.io.ObjectInputStream);
    java.lang.Object writeReplace();
    java.lang.Object readResolve();
}

# 保留基础组件不混淆
-keep public class * extends android.app.Service
-keep public class * extends android.app.Activity
