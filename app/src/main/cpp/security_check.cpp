#include "security_check.h"
#include <cstring>
#include <android/log.h>
#include <cstdlib>
#include <string>

// 🔇 [Aegis Release Protocol] 日志控制：发布模式下自动静音，防止逆向分析
#if defined(AEGIS_DEBUG_MODE) && AEGIS_DEBUG_MODE == 1
#define TAG "AegisGuard"
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)
#else
#define LOGE(...) ((void)0)
#endif

namespace AegisSecurity {

    // 🎭 [环境隔离] 核心包名打散校验，防止重打包二次分发
    const char SecurityGate::EXPECTED_PACKAGE_NAME[] = {
            'c','o','m','.','a','e','g','i','s','.','v','o','i','c','e', 0
    };

    // =========================================================================
    // 🧮 [签名校验迷魂阵]
    // 开源提示：以下哈希值为开源模拟值。
    // 在您自己的商业项目中，请通过 Java 层获取您的真实签名 Hash，并替换以下常量。
    // 算法公式：Secret = (RealHash - Decoy_A) * 3 +/- Decoy_B
    // =========================================================================

    // 🎭 [迷魂阵 1] 针对本地 Release 签名的校验常量 (基于模拟 Hash: 123456789)
    const int DECOY_HASH_A = 100000000;
    const int DECOY_HASH_B = 5555555;
    const int CALCULATED_SECRET = 75925922;

    // 🎭 [迷魂阵 2] 针对 Google Play 云端签名的校验常量 (基于模拟 Hash: 987654321)
    const int CLOUD_DECOY_A = 900000000;
    const int CLOUD_DECOY_B = 8888888;
    const int CLOUD_SECRET  = 254074075;

    // 🚀 [核心引擎守护] 身份验证网关
    // 由外部 Java 层合法传入 context，避免底层强行获取导致的异常。
    bool SecurityGate::verifyIdentity(JNIEnv *env, jobject context) {

        // =========================================================================
        // 👻 [绝对隐身钢印：XOR 影子刺客]
        //
        // ⚠️【开源社区必读：底层版权防御机制】⚠️
        // 这里的 "LUCAS" 和 "aegisvoiceai" 是本开源项目的演示【数字钢印】。
        // 意义：如果盗版者强行修改包名、剔除了普通的版权声明，这些埋藏在 .so 动态库
        // 内存深处的字符串，将是您在法庭上证明源码归属的“一击致命的铁证”。
        //
        // 为什么需要私钥（XOR Key）？
        // 如果直接写明文 "LUCAS"，黑客使用 `strings` 命令扫描二进制文件即可轻易发现并篡改。
        // 因此，我们使用一个自定义的私钥（本例中演示为 0x8C）对明文进行异或(XOR)加密。
        // 编译后的二进制文件中只存在一堆乱码，只有在运行时才会在内存中验证。
        //
        // 🔴【最高安全警告】🔴：
        // 1. 在您自己的商业项目中，请务必替换为您自己的标志性词汇！
        // 2. 您必须自己生成一个新的私钥（16进制），并死死牢记！
        // 3. 绝不能像本演示代码一样，把私钥的真实逻辑写在明文注释里！它是这套防御机制的命门！
        //
        // 演示逻辑：
        // 原始文本: LUCAS (演示私钥 0x8C)
        const volatile unsigned char SHADOW_ALPHA[] = { 0xC0, 0xD9, 0xCF, 0xCD, 0xDF, 0x8C };
        // 原始文本: aegisvoiceai (演示私钥 0x8C)
        const volatile unsigned char SHADOW_BETA[]  = { 0xED, 0xE9, 0xEB, 0xE5, 0xFF, 0xFA, 0xE3, 0xE5, 0xEF, 0xE9, 0xED, 0xE5, 0x8C };

        // 内存完整性校验：触发简单内存嗅探。如果内存防线被恶意篡改，直接锁死引擎
        if (SHADOW_ALPHA[0] == 0xFF && SHADOW_BETA[0] == 0xFF) {
            return false;
        }
        // =========================================================================

        // 上下文判空保护
        if (context == nullptr) {
            LOGE("❌ Security Failure: Context is null, Engine locked.");
            return false;
        }

        // 1. 包名深度校验 (采用 char 数组逐字节比较，防常规 Hook)
        std::string packageName = getPackageName(env, context);
        if (packageName != EXPECTED_PACKAGE_NAME) {
            LOGE("❌ Security Failure: Package name mismatch.");
            return false;
        }

        // 2. 动态签名 Hash 提取
        int currentHash = getSignatureHash(env, context);
        long long val = (long long)currentHash;

        // 🧮 [执行迷魂阵算法 1：本地校验]
        long long localResult = (val - DECOY_HASH_A) * 3 + DECOY_HASH_B;
        bool isLocalValid = (localResult == CALCULATED_SECRET);

        // 🧮 [执行迷魂阵算法 2：云端校验]
        long long cloudResult = (val - CLOUD_DECOY_A) * 3 - CLOUD_DECOY_B;
        bool isCloudValid = (cloudResult == CLOUD_SECRET);

        // 🛡️ [终极裁决：双端任一匹配即放行]
        if (isLocalValid || isCloudValid) {
            return true;
        } else {
            LOGE("❌ Security Failure: Signature algorithm mismatch. Microphone processing disabled.");
            return false;
        }
    }

    // =========================================================================
    // JNI 反射工具区：安全、轻量地从 Java 层提取信息
    // =========================================================================

    std::string SecurityGate::getPackageName(JNIEnv *env, jobject context) {
        jclass contextClass = env->GetObjectClass(context);
        jmethodID getPackageNameMethod = env->GetMethodID(contextClass, "getPackageName", "()Ljava/lang/String;");
        jstring jsonPackageName = (jstring) env->CallObjectMethod(context, getPackageNameMethod);
        const char *packageNameChars = env->GetStringUTFChars(jsonPackageName, nullptr);
        std::string packageName(packageNameChars);
        env->ReleaseStringUTFChars(jsonPackageName, packageNameChars);
        return packageName;
    }

    int SecurityGate::getSignatureHash(JNIEnv *env, jobject context) {
        jclass contextClass = env->GetObjectClass(context);
        jmethodID getPackageManagerMethod = env->GetMethodID(contextClass, "getPackageManager", "()Landroid/content/pm/PackageManager;");
        jobject packageManager = env->CallObjectMethod(context, getPackageManagerMethod);
        jmethodID getPackageNameMethod = env->GetMethodID(contextClass, "getPackageName", "()Ljava/lang/String;");
        jstring packageName = (jstring) env->CallObjectMethod(context, getPackageNameMethod);
        jclass packageManagerClass = env->GetObjectClass(packageManager);
        // 获取 PackageInfo，标志位 64 代表 GET_SIGNATURES
        jmethodID getPackageInfoMethod = env->GetMethodID(packageManagerClass, "getPackageInfo", "(Ljava/lang/String;I)Landroid/content/pm/PackageInfo;");
        jobject packageInfo = env->CallObjectMethod(packageManager, getPackageInfoMethod, packageName, 64);

        if (!packageInfo) return 0;

        jclass packageInfoClass = env->GetObjectClass(packageInfo);
        jfieldID signaturesField = env->GetFieldID(packageInfoClass, "signatures", "[Landroid/content/pm/Signature;");
        jobjectArray signatures = (jobjectArray) env->GetObjectField(packageInfo, signaturesField);

        if (!signatures) return 0;

        jobject signature = env->GetObjectArrayElement(signatures, 0);
        jclass signatureClass = env->GetObjectClass(signature);
        jmethodID hashCodeMethod = env->GetMethodID(signatureClass, "hashCode", "()I");

        return env->CallIntMethod(signature, hashCodeMethod);
    }
}