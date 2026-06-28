# =========================================================================================
# 🛡️ 【Aegis 开源安全声明：商业级代码混淆与防逆向装甲】
# 状态：SILENT & STEALTH (全局静音 + 逻辑隐身)
#
# 致开源社区极客：
# 本混淆规则保留了 AegisVoice 售价 299$ 商业版最原始的防御策略。
# 它是为了对抗反编译、内存 Dump 和源码逆向而设计的。
# 如果您在构建 Release 版本时发现没有任何 Log 输出，且崩溃堆栈全为 "Unknown Source"，
# 请不要惊慌，这是以下防御规则在生效。
# =========================================================================================

# --------------------------------------------------------
# 1. 🔇 日志“物理核爆” (The Absolute Silencer)
# --------------------------------------------------------
# 核心原理：利用 R8/ProGuard 的 assumenosideeffects 机制，在编译期直接将所有 Log 代码
# 从 AST (抽象语法树) 中物理抹除。既减小了包体积，又防止了逆向者通过日志明文猜测代码逻辑。
-assumenosideeffects class android.util.Log {
    public static boolean isLoggable(java.lang.String, int);
    public static int v(...);
    public static int d(...);
    public static int i(...);
    public static int w(...);
    public static int e(...); # 💥 连 Error 级报错也物理切除，彻底封口
}

# 物理清除控制台打印
-assumenosideeffects class java.io.PrintStream {
    public void println(...);
    public void print(...);
}

# --------------------------------------------------------
# 2. 🌉 核心资产保护 (JNI Bridge Shield)
# --------------------------------------------------------
# ⚠️ 警告：NativeLib 绝对不能混淆！
# 原因：底层 C++ 引擎通过特定的 JNI 签名寻找此 Java 类。若混淆改名，应用点火即刻崩溃。
-keep class com.aegis.voice.NativeLib { *; }

# [架构注记]：FuzzyMatchUtils (Java 层后处理中枢) 的 keep 规则已被故意移除！
# 目的：使其类名、方法名被完全碾碎为 "a.b.c"，增加黑客逆向核心拼接与纠错算法的难度。

# --------------------------------------------------------
# 3. 📦 第三方库防崩保护 (Dependencies Guard)
# --------------------------------------------------------

# 🚨 [JNA 修复] - Vosk 底层引擎依赖，保持不动
-keep class com.sun.jna.** { *; }
-keep class * implements com.sun.jna.** { *; }
-dontwarn java.awt.**
-dontwarn java.beans.**
-dontwarn com.sun.jna.**

# 🚨 [Vosk 修复] - 轻量级语音引擎接口，保持不动
-keep class org.vosk.** { *; }
-keep interface org.vosk.** { *; }
-dontwarn org.vosk.**

# 🚨 [Gson 修复] - 动态语法树 JSON 解析器
-dontwarn sun.misc.Unsafe

# --------------------------------------------------------
# 4. 👻 堆栈致盲与基础属性混淆 (Stacktrace Blinding)
# --------------------------------------------------------
# 🔨 [黑客防御补丁]：彻底剥离 SourceFile 和 LineNumberTable。
# 这意味着反编译出来的代码将没有原始文件名和行号，崩溃堆栈将呈现无意义的 "Unknown Source"。
# 仅保留注解、签名和内部类结构，以维持正常运行期的反射调用。
-keepattributes *Annotation*
-keepattributes Signature
-keepattributes InnerClasses
-keepattributes EnclosingMethod

# 将源文件属性重命名为空，执行终极堆栈致盲
-renamesourcefileattribute ""

# --------------------------------------------------------
# 5. 🧵 协程保护 (Kotlin Coroutines)
# --------------------------------------------------------
# Aegis 大量使用了 Kotlin 协程进行异步流媒体处理和算力调度。
# 以下规则防止 R8 优化器误杀协程底层的挂起函数与调度器。
-keepnames class kotlinx.coroutines.internal.MainDispatcherFactory {}
-keepnames class kotlinx.coroutines.CoroutineExceptionHandler {}
-keepclassmembers class kotlinx.coroutines.android.AndroidExceptionPreHandler {
    <init>();
}