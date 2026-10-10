# ==========================================================
#  云视远程输入法 —— R8 / ProGuard 混淆规则
#  说明：本项目由 AGP 内置的 R8 编译器处理，故仅保留 R8 支持的指令。
#        原规则中的 -dontpreverify、-ignorewarning、-printmapping、
#        -optimizations 等属于 ProGuard 专有指令，R8 无法解析会导致
#        打包阶段 (minifyReleaseWithR8) 直接失败，此处已全部移除。
# ==========================================================

# ---------- 1. 属性保留 ----------
-keepattributes *Annotation*,InnerClasses
-keepattributes Signature
-keepattributes SourceFile,LineNumberTable
-keepattributes EnclosingMethod

# ---------- 2. 基础组件与 View ----------
-keep public class * extends android.app.Activity
-keep public class * extends android.app.Application
-keep public class * extends android.app.Service
-keep public class * extends android.content.BroadcastReceiver
-keep public class * extends android.content.ContentProvider
-keep public class * extends android.app.backup.BackupAgentHelper
-keep public class * extends android.preference.Preference
-keep public class * extends android.view.View

-keepclasseswithmembernames class * {
    native <methods>;
}
-keepclassmembers class * extends android.app.Activity {
    public void *(android.view.View);
}
-keepclassmembers enum * {
    public static **[] values();
    public static ** valueOf(java.lang.String);
}
-keep public class * extends android.view.View {
    *** get*();
    void set*(***);
    public <init>(android.content.Context);
    public <init>(android.content.Context, android.util.AttributeSet);
    public <init>(android.content.Context, android.util.AttributeSet, int);
}
-keepclasseswithmembers class * {
    public <init>(android.content.Context, android.util.AttributeSet);
    public <init>(android.content.Context, android.util.AttributeSet, int);
}
-keep class * implements android.os.Parcelable {
    public static final android.os.Parcelable$Creator *;
}
-keepclassmembers class * implements java.io.Serializable {
    static final long serialVersionUID;
    private static final java.io.ObjectStreamField[] serialPersistentFields;
    private void writeObject(java.io.ObjectOutputStream);
    private void readObject(java.io.ObjectInputStream);
    java.lang.Object writeReplace();
    java.lang.Object readResolve();
}
-keep class **.R$* {
    *;
}
-keepclassmembers class * {
    void *(**On*Event);
}

# ---------- 3. 内嵌 WebView 前端与 JS 交互 ----------
-keepclassmembers class * extends android.webkit.WebViewClient {
    public void *(android.webkit.WebView, java.lang.String, android.graphics.Bitmap);
    public boolean *(android.webkit.WebView, java.lang.String);
}

# ---------- 4. 第三方依赖 ----------
# 内置 HTTP 服务
-dontwarn fi.iki.elonen.**
-keep class fi.iki.elonen.** { *; }

# 二维码生成
-dontwarn com.google.zxing.**
-keep class com.google.zxing.** { *; }

# 迅雷下载内核（Legacy 播放链路）
-dontwarn com.xunlei.downloadlib.**
-keep class com.xunlei.downloadlib.** { *; }

# ijkplayer 播放内核 Java 层（以本地 jar 内置）
-dontwarn tv.danmaku.ijk.**
-keep class tv.danmaku.ijk.** { *; }

# DLNA 投屏
-keep class org.fourthline.cling.** { *; }
-keep class com.zxt.dlna.** { *; }

# 反射 / 可选依赖缺失类静默
-dontwarn org.fourthline.**
-dontwarn javax.**
-dontwarn okhttp3.**
-dontwarn okio.**
-dontwarn org.eclipse.jetty.**
-dontwarn org.slf4j.**
-dontwarn org.apache.**
-dontwarn com.google.**
-dontwarn org.joda.**

# ---------- 5. 自研代码 ----------
# 自研代码中反射与动态调用较多，整体保留，以极小体积换取运行稳定
-keep class cn.cloudhs.tvremoteime.** { *; }
-keep class xllib.** { *; }
-keep class player.** { *; }
