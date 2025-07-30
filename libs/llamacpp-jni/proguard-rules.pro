# Add project specific ProGuard rules here.
# You can control the set of applied configuration files using the
# proguardFiles setting in build.gradle.
#
# For more details, see
#   http://developer.android.com/guide/developing/tools/proguard.html

# If your project uses WebView with JS, uncomment the following
# and specify the fully qualified class name to the JavaScript interface
# class:
#-keepclassmembers class fqcn.of.javascript.interface.for.webview {
#   public *;
#}

# Uncomment this to preserve the line number information for
# debugging stack traces.
#-keepattributes SourceFile,LineNumberTable

# If you keep the line number information, uncomment this to
# hide the original source file name.
#-renamesourcefileattribute SourceFile

# ===== 开源项目专用配置 =====
# 开源项目禁止混淆，保持所有类名、方法名、字段名不变
# 只启用压缩和性能优化，便于开源协作、调试和代码追踪

# 保持所有LlamaCpp JNI相关类完全不混淆
-keep class com.starlocalrag.llamacpp.** { *; }
-keepnames class com.starlocalrag.llamacpp.**
-keepclassmembernames class com.starlocalrag.llamacpp.** {
    *;
}

# 保持所有内部类和匿名类不混淆
-keepnames class com.starlocalrag.llamacpp.**$*
-keepclassmembernames class com.starlocalrag.llamacpp.**$* {
    *;
}

# 保持JNI方法
-keepclasseswithmembernames class * {
    native <methods>;
}

# 保持序列化相关
-keepclassmembers class * implements java.io.Serializable {
    static final long serialVersionUID;
    private static final java.io.ObjectStreamField[] serialPersistentFields;
    private void writeObject(java.io.ObjectOutputStream);
    private void readObject(java.io.ObjectInputStream);
    java.lang.Object writeReplace();
    java.lang.Object readResolve();
}