#include "user_data_guard.h"
#include "model_crypto.h" // 引用核心流密码引擎
#include <fstream>
#include <sstream>
#include <android/log.h>

// ==========================================
// 🔇 [Aegis Release Protocol] 运行期静音控制
// ==========================================
// 遵循生产环境保护原则，禁止在终端打印涉及用户隐私的日志
#if defined(AEGIS_DEBUG_MODE) && AEGIS_DEBUG_MODE == 1
#define TAG "AegisUserGuard"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)
#else
#define LOGI(...) ((void)0)
#define LOGE(...) ((void)0)
#endif

namespace AegisCore {

    // ==========================================
    // 📖 用户资产装载管道 (Load & Decrypt)
    // 💡 开源提示：为什么我们要加密用户的本地热词？
    // 在 Root 过的 Android 设备上，应用的私有目录可以被第三方轻易读取。
    // 将用户自定义的语音识别热词（可能包含人名、公司机密、专业术语）加密存储，
    // 是商业级应用保护用户隐私的强制底线。
    // ==========================================
    std::vector<std::string> UserDataGuard::loadHotwords(const std::string& filePath) {
        std::vector<std::string> result;

        std::ifstream file(filePath, std::ios::binary);
        // 若初次运行或未设置热词，静默返回空状态
        if (!file.is_open()) return result;

        // 全量读入二进制缓冲区
        std::vector<char> buffer((std::istreambuf_iterator<char>(file)), std::istreambuf_iterator<char>());
        file.close();

        if (buffer.empty()) return result;

        // 🔓 调用底层安全引擎执行就地解密
        AegisSecurity::ModelCrypto::decryptInPlace(buffer);

        // 结构化解析
        std::stringstream ss(std::string(buffer.begin(), buffer.end()));
        std::string line;
        while (std::getline(ss, line)) {
            // 兼容跨平台换行符 (CRLF -> LF)
            if (!line.empty() && line.back() == '\r') line.pop_back();
            if (!line.empty()) result.push_back(line);
        }

        // 🛡️ [安全规范] 阅后即焚 (Memory Zeroing)
        // 解析完成后，立即销毁明文缓冲区，阻截运行期内存快照对用户隐私的窥探
        std::fill(buffer.begin(), buffer.end(), 0);

        return result;
    }

    // ==========================================
    // 💾 用户资产持久化管道 (Encrypt & Save)
    // 💡 开源提示：整个加解密过程完全在 C++ 内存空间封闭完成，
    // 密钥绝不暴露给 Java 层，极大提高了逆向门槛。
    // ==========================================
    bool UserDataGuard::saveHotwords(const std::string& filePath, const std::string& content) {
        if (content.empty()) return false;

        // 1. 构建内存操作缓冲区
        std::vector<char> buffer(content.begin(), content.end());

        // 2. 🔒 调用底层安全引擎执行就地加密
        AegisSecurity::ModelCrypto::encryptInPlace(buffer);

        // 3. 执行磁盘 I/O (截断并覆写)
        std::ofstream file(filePath, std::ios::binary | std::ios::trunc);
        if (!file.is_open()) {
            LOGE("❌ Security I/O Alert: Failed to write protected user data.");
            return false;
        }

        file.write(buffer.data(), buffer.size());
        file.close();

        // 🛡️ [安全规范] 缓冲区净化
        // 尽管当前 buffer 内容已是密文，但遵循严格的安全工程规范，操作完毕后统一清零
        std::fill(buffer.begin(), buffer.end(), 0);

        return true;
    }
}