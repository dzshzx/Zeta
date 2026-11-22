# 正式版（release）的 R8 规则。清单里的组件、native 方法和各依赖自带的规则由 AGP 处理，这里只登记 R8 从代码里找不到的入口：
# 运行时按类名找的类。新增这类入口（app_process 起的辅助进程、按配置字符串反射的库）时在这里加。

# 只去掉用不到的代码，不改类名和方法名：崩溃堆栈、报给 Claude 的异常类名照原样可读，不用留每个版本的 mapping。
# 改名只能再省约 0.6 MB（13.5 → 12.9 MB）
-dontobfuscate
-keepattributes SourceFile,LineNumberTable

# root 辅助进程（VdHelper、ProviderHelper 等）：经 su 用 app_process 按类名起（DeviceShell.appProcessCommand），没有代码调它们的 main
-keepclasseswithmembers class com.dzshzx.zeta.** {
    public static void main(java.lang.String[]);
}

# LSPosed 按 resources/META-INF/xposed/java_init.list 里的类名加载模块
-keep class com.dzshzx.zeta.xposed.ZetaModule { <init>(); }

# 界面线程、本包包名、图片解码、字体、公式引擎和聊天页平台部分的实现由 ServiceLoader 按 resources/META-INF/services 里的类名加载（core/MainThread.kt、core/AppVariant.kt、core/ImageCodec.kt、ui/components/MonoFont.kt、ui/markdown/MathDisplay.kt、ui/chat/ChatPlatform.kt）
-keep class com.dzshzx.zeta.core.AndroidMainLoop { <init>(); }
-keep class com.dzshzx.zeta.core.AndroidAppPackage { <init>(); }
-keep class com.dzshzx.zeta.core.AndroidImageCodec { <init>(); }
-keep class com.dzshzx.zeta.ui.components.AndroidFonts { <init>(); }
-keep class com.dzshzx.zeta.ui.markdown.RaTeXMathEngine { <init>(); }
-keep class com.dzshzx.zeta.ui.chat.AndroidChatPlatform { <init>(); }

# JSch 按配置里的类名反射创建每个算法（它自己的默认配置和 SshLinks 指定的 BouncyCastle 实现）
-keep class com.jcraft.jsch.** { *; }
# 它的可选依赖（Windows 的 Pageant、日志库、Kerberos、Unix socket）Zeta 都不带，用不到这几样
-dontwarn com.sun.jna.**
-dontwarn org.apache.logging.log4j.**
-dontwarn org.ietf.jgss.**
-dontwarn org.newsclub.net.unix.**
-dontwarn org.slf4j.**
