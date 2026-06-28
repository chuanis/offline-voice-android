// =========================================================================================
// 🚀 核心中枢: native-lib.cpp (JNI Bridge & Central Command)
// 🛡️ 架构代号: Titan V13
//
// 【模块说明】
// 本文件是 Java 应用层与 C++ 底层 AI 引擎（Whisper）、安全核心以及算法库通信的唯一网关。
// 它负责统筹管理模型的生命周期、内存的安全擦除、算力的动态降级（NPU -> GPU -> CPU），
// 并对外暴露高频安全的 JNI 接口。
// =========================================================================================

#include <jni.h>
#include <string>
#include <vector>
#include <set>
#include <map>
#include <algorithm>
#include <android/log.h>
#include <android/asset_manager.h>
#include <android/asset_manager_jni.h>
#include "whisper.h"
#include "fuzzy_match.h"
#include "model_crypto.h"
#include "security_check.h"
#include "assets_loader.h"
#include "user_data_guard.h"

// ========================================================
// 🔇 [Aegis Release Protocol] 运行期全局日志静音
// ========================================================
#if defined(AEGIS_DEBUG_MODE) && AEGIS_DEBUG_MODE == 1
#define TAG "AegisNative"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)
#define LOGD(...) __android_log_print(ANDROID_LOG_DEBUG, TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, TAG, __VA_ARGS__)
#else
#define LOGI(...) ((void)0)
    #define LOGD(...) ((void)0)
    #define LOGE(...) ((void)0)
    #define LOGW(...) ((void)0)
#endif

// 🌍 引擎全局上下文 (Global Engine Context)
static whisper_context *g_ctx = nullptr;

// 🚀 核心纠错语料库 (常驻内存，提升查表极速)
static std::set<std::string> g_commonWords;                   // Tier 0 (高频词白名单)
static std::vector<std::string> g_blacklist;                  // 幻觉黑名单 (Hallucination Blacklist)
static std::map<int, std::vector<std::string>> g_systemTiers; // Tier 1-5 (系统分级语料)
static std::vector<std::string> g_vipHotwords;                // 用户专属 VIP 热词

// ==========================================
// 🛠️ 内部辅助管道 (Internal Helpers)
// ==========================================
std::vector<char> loadModelBytes(JNIEnv *env, jobject assetManager, const char *filename) {
    AAssetManager *mgr = AAssetManager_fromJava(env, assetManager);
    if (!mgr) { return {}; }
    AAsset *asset = AAssetManager_open(mgr, filename, AASSET_MODE_BUFFER);
    if (!asset) { return {}; }
    off_t length = AAsset_getLength(asset);
    std::vector<char> buffer(length);
    AAsset_read(asset, buffer.data(), length);
    AAsset_close(asset);
    return buffer;
}

extern "C" {

// =======================================================================
// 🚀 [核心接口 1] 引擎点火与装载 (Model Loading & Decryption)
// 包含安全阻断、流密码解密、以及 NPU/GPU 到 CPU 的动态算力降级策略。
// =======================================================================
JNIEXPORT jboolean JNICALL
Java_com_aegis_voice_NativeLib_loadModel(JNIEnv *env, jobject thiz, jobject context, jobject assetManager, jstring modelPath, jint device) {

    // 🛡️ [防线 1] 版权与完整性校验门禁
    // 只有合法的 Java Context 才能唤醒底层 AI 引擎
    if (!AegisSecurity::SecurityGate::verifyIdentity(env, context)) {
        LOGE("❌ Security Override: Unauthorized access detected. Engine ignition BLOCKED.");
        return false;
    }

    const char *path = env->GetStringUTFChars(modelPath, nullptr);

    // 📥 装载加密模型文件至内存
    std::vector<char> modelData = loadModelBytes(env, assetManager, path);
    env->ReleaseStringUTFChars(modelPath, path);
    if (modelData.empty()) return false;

    // 🔓 [防线 2] 触发底层流密码引擎进行就地解密
    AegisSecurity::ModelCrypto::decryptInPlace(modelData);

    // 屏蔽 Whisper 引擎内部的冗余日志，保证控制台干净
    whisper_log_set([](ggml_log_level level, const char * text, void * user_data) {}, nullptr);

    // 🔄 [算力调度 - 阶段 1] 尝试优先使用硬件加速 (GPU/NPU)
    struct whisper_context_params cparams = whisper_context_default_params();
    cparams.use_gpu = (device == 1);

    g_ctx = whisper_init_from_buffer_with_params(modelData.data(), modelData.size(), cparams);

    if (g_ctx) {
        LOGI("✅ Engine Online (Hardware Acceleration: %d)", cparams.use_gpu);
        // 🛡️ 阅后即焚：硬件装载成功，立即涂抹明文内存
        std::fill(modelData.begin(), modelData.end(), 0);
        return true;
    }

    // 🔄 [算力调度 - 阶段 2] 硬件加速失败，触发 CPU 自动兜底降级
    if (cparams.use_gpu) {
        LOGW("⚠️ Hardware Accel Failed. Executing CPU Fallback Protocol...");
        cparams.use_gpu = false;

        // 此时 modelData 尚未被擦除，供给 CPU 进行最后的尝试
        g_ctx = whisper_init_from_buffer_with_params(modelData.data(), modelData.size(), cparams);

        // 🛡️ 阅后即焚：无论 CPU 兜底是否成功，至此必须强行清空内存，防止 Dump 攻击
        std::fill(modelData.begin(), modelData.end(), 0);

        if (g_ctx) {
            LOGI("✅ Engine Online (CPU Fallback Mode)");
            return true;
        }
    }

    // 装载彻底失败
    return false;
}

// =======================================================================
// 🚀 [核心接口 2] 静态语料库注入 (Static Lexicon Injection)
// =======================================================================
JNIEXPORT void JNICALL
Java_com_aegis_voice_NativeLib_initStaticData(JNIEnv *env, jobject thiz, jobject assetManager, jstring userHotwordsPath) {
    LOGI("🚀 Triggering Data Injection Protocol V13...");

    AAssetManager *mgr = AAssetManager_fromJava(env, assetManager);
    const char *hotPath = env->GetStringUTFChars(userHotwordsPath, nullptr);

    // 通过安全物流通道装载各类解密语料
    g_commonWords = AegisCore::AssetsLoader::loadCommonWhitelist(mgr);
    g_blacklist   = AegisCore::AssetsLoader::loadBlacklist(mgr);
    g_systemTiers = AegisCore::AssetsLoader::loadSystemTiers(mgr);

    // 装载用户专属数据
    g_vipHotwords = AegisCore::UserDataGuard::loadHotwords(hotPath);

    env->ReleaseStringUTFChars(userHotwordsPath, hotPath);

    LOGI("✅ Data Injection Complete: Tier0=%zu, Blacklist=%zu, UserWords=%zu",
         g_commonWords.size(), g_blacklist.size(), g_vipHotwords.size());
}

// ♻️ 释放常驻内存
JNIEXPORT void JNICALL
Java_com_aegis_voice_NativeLib_resetStaticData(JNIEnv *env, jobject thiz) {
    g_commonWords.clear();
    g_blacklist.clear();
    g_systemTiers.clear();
    g_vipHotwords.clear();
}

// =======================================================================
// 🧠 [核心接口 3] 模糊纠错推理桥接 (Fuzzy Match Inference)
// =======================================================================
JNIEXPORT jstring JNICALL
Java_com_aegis_voice_NativeLib_nativeFuzzyCorrect(JNIEnv *env, jobject thiz, jstring rawText, jobjectArray ignoredHotwords) {
    if (rawText == nullptr) return env->NewStringUTF("");

    const char *cRaw = env->GetStringUTFChars(rawText, nullptr);
    std::string text(cRaw);
    env->ReleaseStringUTFChars(rawText, cRaw);

    // 触发 Titan 架构纠错算法
    std::string fixed = AegisCore::FuzzyMatch::correct(
            text,
            g_blacklist,
            g_commonWords,
            g_vipHotwords,
            g_systemTiers
    );

    return env->NewStringUTF(fixed.c_str());
}

// =======================================================================
// 💾 [用户隐私接口] 读写受保护的用户热词
// =======================================================================
JNIEXPORT jboolean JNICALL
Java_com_aegis_voice_NativeLib_saveUserHotwords(JNIEnv *env, jobject thiz, jstring path, jstring content) {
    const char *cPath = env->GetStringUTFChars(path, nullptr);
    const char *cContent = env->GetStringUTFChars(content, nullptr);

    bool success = AegisCore::UserDataGuard::saveHotwords(cPath, cContent);

    if (success) {
        // 覆写成功后立即刷新内存中的 VIP 列表
        g_vipHotwords = AegisCore::UserDataGuard::loadHotwords(cPath);
    }

    env->ReleaseStringUTFChars(path, cPath);
    env->ReleaseStringUTFChars(content, cContent);
    return success ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jobjectArray JNICALL
Java_com_aegis_voice_NativeLib_getUserWords(JNIEnv *env, jobject thiz, jstring path) {
    const char *cPath = env->GetStringUTFChars(path, nullptr);
    std::vector<std::string> lines = AegisCore::UserDataGuard::loadHotwords(cPath);
    env->ReleaseStringUTFChars(path, cPath);

    jclass stringClass = env->FindClass("java/lang/String");
    jobjectArray result = env->NewObjectArray(lines.size(), stringClass, env->NewStringUTF(""));
    for (size_t i = 0; i < lines.size(); ++i) {
        env->SetObjectArrayElement(result, i, env->NewStringUTF(lines[i].c_str()));
    }
    return result;
}

// =======================================================================
// 🛡️ [通用工具接口] 供 Java 层读取加密资产 (如 Vosk 依赖文件)
// =======================================================================
JNIEXPORT jobjectArray JNICALL
Java_com_aegis_voice_NativeLib_getDecryptedAsset(JNIEnv *env, jobject thiz, jobject assetManager, jstring path) {
    const char *cPath = env->GetStringUTFChars(path, nullptr);
    AAssetManager *mgr = AAssetManager_fromJava(env, assetManager);

    std::vector<std::string> lines = AegisCore::AssetsLoader::loadGenericList(mgr, cPath);

    env->ReleaseStringUTFChars(path, cPath);

    jclass stringClass = env->FindClass("java/lang/String");
    jobjectArray result = env->NewObjectArray(lines.size(), stringClass, env->NewStringUTF(""));
    for (size_t i = 0; i < lines.size(); ++i) {
        env->SetObjectArrayElement(result, i, env->NewStringUTF(lines[i].c_str()));
    }
    return result;
}

// =======================================================================
// 🎙️ [引擎标准生命周期接口] (Engine Standard Protocols)
// =======================================================================
JNIEXPORT void JNICALL Java_com_aegis_voice_NativeLib_freeModel(JNIEnv *env, jobject thiz) {
    if (g_ctx) {
        whisper_free(g_ctx);
        g_ctx = nullptr;
        g_commonWords.clear();
        g_blacklist.clear();
        g_systemTiers.clear();
        g_vipHotwords.clear();
    }
}

JNIEXPORT jstring JNICALL Java_com_aegis_voice_NativeLib_transcribeAudio(JNIEnv *env, jobject thiz, jobject audioBuffer, jint len, jstring prompt) {
    if (!g_ctx) return env->NewStringUTF("");
    float* pAudio = (float*)env->GetDirectBufferAddress(audioBuffer);
    const char *cPrompt = env->GetStringUTFChars(prompt, nullptr);

    whisper_full_params wparams = whisper_full_default_params(WHISPER_SAMPLING_GREEDY);
    wparams.print_progress = false;
    wparams.print_timestamps = false;
    wparams.n_threads = 4;
    wparams.initial_prompt = cPrompt;

    if (whisper_full(g_ctx, wparams, pAudio, len) != 0) {
        env->ReleaseStringUTFChars(prompt, cPrompt);
        return env->NewStringUTF("");
    }

    std::string result;
    int n_segments = whisper_full_n_segments(g_ctx);
    for (int i = 0; i < n_segments; ++i) {
        result += whisper_full_get_segment_text(g_ctx, i);
    }

    env->ReleaseStringUTFChars(prompt, cPrompt);
    return env->NewStringUTF(result.c_str());
}

JNIEXPORT void JNICALL Java_com_aegis_voice_NativeLib_setWhisperParams(JNIEnv *env, jobject thiz, jint beam, jfloat entropy) {
    // 留给未来的高级调参接口
}

} // extern "C"