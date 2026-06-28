#include "assets_loader.h"
#include "model_crypto.h"
#include <sstream>
#include <android/log.h>

// ==========================================
// 🔇 [Aegis Release Protocol] 运行期静音控制
// ==========================================
// 防止在 Release 环境下泄露数据流信息
#if defined(AEGIS_DEBUG_MODE) && AEGIS_DEBUG_MODE == 1
#define TAG "AegisLoader"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)
#else
#define LOGI(...) ((void)0)
    #define LOGW(...) ((void)0)
    #define LOGE(...) ((void)0)
#endif

namespace AegisCore {

    // ==========================================
    // 🛡️ 安全 I/O 核心：读取并解密 (Read & Decrypt)
    // ==========================================
    std::vector<char> AssetsLoader::readAndDecrypt(AAssetManager* mgr, const char* filename) {
        // 1. 尝试开启资产文件流
        AAsset* asset = AAssetManager_open(mgr, filename, AASSET_MODE_BUFFER);

        if (!asset) {
            LOGW("❌ Asset Pipeline: File NOT FOUND -> %s", filename);
            return {};
        }

        // 2. 完整读取二进制缓冲区
        off_t length = AAsset_getLength(asset);
        std::vector<char> buffer(length);
        AAsset_read(asset, buffer.data(), length);
        AAsset_close(asset);

        // 3. 调用底层安全引擎执行就地解密
        AegisSecurity::ModelCrypto::decryptInPlace(buffer);

        return buffer;
    }

    // ==========================================
    // 📦 业务词库装载管道
    // ==========================================

    std::set<std::string> AssetsLoader::loadCommonWhitelist(AAssetManager* mgr) {
        LOGI("📥 Loading Core Lexicon Tier 0 (Whitelist)...");

        auto data = readAndDecrypt(mgr, "lexicons/tier0.dat");

        std::set<std::string> result;
        parseLinesToSet(data, result);

        // 🛡️ [安全规范] 阅后即焚 (Memory Zeroing)
        // 解析完毕后立即擦除明文缓冲区，阻断运行期内存快照攻击
        std::fill(data.begin(), data.end(), 0);

        return result;
    }

    std::vector<std::string> AssetsLoader::loadBlacklist(AAssetManager* mgr) {
        LOGI("📥 Loading Hallucination Blacklist...");

        auto data = readAndDecrypt(mgr, "lexicons/hallucinations.dat");

        std::vector<std::string> result;
        parseLinesToVector(data, result);

        // 🛡️ [安全规范] 阅后即焚
        std::fill(data.begin(), data.end(), 0);

        return result;
    }

    std::map<int, std::vector<std::string>> AssetsLoader::loadSystemTiers(AAssetManager* mgr) {
        std::map<int, std::vector<std::string>> tiersMap;

        // 架构字典桶分类定义
        const char* categories[] = {"gen", "law", "tec", "biz"};

        LOGI("📥 Initializing Multi-Tier System (gen, law, tec, biz)...");

        for (const char* cat : categories) {
            // 加载 1-5 级词库分层结构
            for (int i = 1; i <= 5; ++i) {
                char path[128];
                snprintf(path, sizeof(path), "lexicons/%s/tier%d.dat", cat, i);

                auto data = readAndDecrypt(mgr, path);
                parseLinesToVector(data, tiersMap[i]);

                // 🛡️ [安全规范] 阅后即焚
                std::fill(data.begin(), data.end(), 0);
            }
        }

        for(auto const& [tier, list] : tiersMap) {
            LOGI("📚 Tier %d Loaded Total: %zu words", tier, list.size());
        }

        return tiersMap;
    }

    std::vector<std::string> AssetsLoader::loadGenericList(AAssetManager* mgr, const char* filename) {
        auto data = readAndDecrypt(mgr, filename);

        std::vector<std::string> result;
        parseLinesToVector(data, result);

        // 🛡️ [安全规范] 阅后即焚
        std::fill(data.begin(), data.end(), 0);

        return result;
    }

    // ==========================================
    // 🛠️ 字节流通用解析工具
    // ==========================================

    void AssetsLoader::parseLinesToSet(const std::vector<char>& data, std::set<std::string>& outSet) {
        if (data.empty()) return;
        std::stringstream ss(std::string(data.begin(), data.end()));
        std::string line;
        while (std::getline(ss, line)) {
            // 处理 Windows 风格的 CRLF 换行符
            if (!line.empty() && line.back() == '\r') line.pop_back();
            if (!line.empty()) outSet.insert(line);
        }
    }

    void AssetsLoader::parseLinesToVector(const std::vector<char>& data, std::vector<std::string>& outVec) {
        if (data.empty()) return;
        std::stringstream ss(std::string(data.begin(), data.end()));
        std::string line;
        while (std::getline(ss, line)) {
            if (!line.empty() && line.back() == '\r') line.pop_back();
            if (!line.empty()) outVec.push_back(line);
        }
    }
}