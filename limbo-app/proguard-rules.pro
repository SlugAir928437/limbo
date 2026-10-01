# Add project specific ProGuard rules here.
# By default, the flags in this file are appended to flags specified
# in C:\tools\adt-bundle-windows-x86_64-20131030\sdk/tools/proguard/proguard-android.txt
# You can edit the include path and order by changing the proguardFiles
# directive in build.gradle.
#
# For more details, see
#   http://developer.android.com/guide/developing/tools/proguard.html

# Add any project specific keep options here:

# If your project uses WebView with JS, uncomment the following
# and specify the fully qualified class name to the JavaScript interface
# class:
#-keepclassmembers class fqcn.of.javascript.interface.for.webview {
#   public *;
#}

#如果你想开启下面某项规则 把#删除即可 不过下面的只是例子 具体规则还得你自己根据项目写

# ==============================
# 1. 字典文件配置（增强混淆强度）默认使用ConfusionDictionary.txt字典混淆
# ==============================
# 需要提前准备一个文本文件（如 ConfusionDictionary.txt），每行一个单词
-packageobfuscationdictionary ConfusionDictionary.txt  # 包名混淆字典
-classobfuscationdictionary ConfusionDictionary.txt    # 类名混淆字典
-obfuscationdictionary ConfusionDictionary.txt         # 方法/字段名混淆字典

# ==============================
# 2. 基础配置
# ==============================
-optimizationpasses 5                  # 优化迭代次数（默认1次）
#-dontusemixedcaseclassnames            # 不使用大小写混合类名（兼容性）
#-dontskipnonpubliclibraryclasses       # 不跳过非公共库类
#-dontpreverify                         # Android不需要预校验
#-verbose                               # 输出详细日志

# ==============================
# 3. 第三方库专用规则
# ==============================
#Bugly
-dontwarn com.tencent.bugly.**
-keep public class com.tencent.bugly.**{*;}

# ==============================
# 4. BuildConfig 反射保护
# ==============================
# com.limbo.emu.local_properties 通过反射按字段名读取 BuildConfig（字段由
# build.gradle 在构建期从 local.properties 注入，如 bugly_appId）。这些常量在
# Java 代码里没有直接引用，R8 会把它们内联/删除，反射随即抛 NoSuchFieldException，
# Bugly 初始化被静默跳过，所以必须保留 BuildConfig 的类与字段。
-keep class com.limbo.emu.BuildConfig { *; }

# ==============================
# 5. JNI 反射查找保护（仅被 native 调用的 Java 成员会被 R8 移除/重命名）
# ==============================
# SDL2: libSDL2.so 在 nativeSetupJNI 中按名称查找 SDLActivity / SDLAudioManager /
# SDLControllerManager 的静态方法与字段（getNativeSurface、setActivityTitle、mSeparateMouseAndTouch 等），
# 一旦被移除/重命名，启动时即抛 NoSuchMethodError 并 abort。
-keep class org.libsdl.app.** { *; }

# GTK4 android 胶水层: libgtk-4.so 的 gdk_android_initialize() 用应用 ClassLoader
# 按“字符串类名”解析 org.gtk.android.* (GlibContext、ToplevelActivity 及其内部类、
# ClipboardProvider$*、ImContext、SystemFilesystem、RuntimeApplication)，随后
# RegisterNatives()/GetMethodID()/GetFieldID() 按名字绑定原生方法。这些类在 Java 侧
# 几乎没有引用（只被 native 代码按名字查找），R8 会把它们整个删除或改名：release
# 包里 ClipboardProvider 及其全部内部类已被裁掉、ToplevelActivity$ToplevelView 被
# 改名为 mnnmnmnmmnm。loadClass() 返回 null 后 RegisterNatives(NULL) 直接 abort：
#   JNI DETECTED ERROR IN APPLICATION: java_class == null
#   in call to RegisterNatives
#   from void com.limbo.emu.jni.LimboGtk.nativeInit(...)
# 因此必须整包保留类名与成员名（含私有 native 方法名）。
-keep class org.gtk.android.** { *; }

# Limbo 兼容层: libqemu-system-*.so 通过 GetMethodID 调用 VMExecutor.close_fd(int)
-keepclassmembers class com.limbo.emu.jni.VMExecutor {
    public int close_fd(int);
}