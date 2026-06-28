#ifndef AEGIS_SECURITY_CHECK_H
#define AEGIS_SECURITY_CHECK_H

#include <jni.h>
#include <string>

namespace AegisSecurity {

    class SecurityGate {
    public:
        // ✅ 对外只暴露这一个接口，接收合法的 context
        static bool verifyIdentity(JNIEnv *env, jobject context);

    private:
        // 🎭 [隐身术] 字符数组声明
        static const char EXPECTED_PACKAGE_NAME[];

        // 🔒 内部辅助函数 (已彻底清理危险的反射函数声明)
        static std::string getPackageName(JNIEnv *env, jobject context);
        static int getSignatureHash(JNIEnv *env, jobject context);
    };

}

#endif // AEGIS_SECURITY_CHECK_H