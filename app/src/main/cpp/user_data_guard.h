#ifndef AEGIS_USER_DATA_GUARD_H
#define AEGIS_USER_DATA_GUARD_H

#include <vector>
#include <string>

namespace AegisCore {

    class UserDataGuard {
    public:
        // 1. [读] 加载并解密用户热词 (用于 Whisper 识别)
        static std::vector<std::string> loadHotwords(const std::string& filePath);

        // 🆕 2. [写] 加密并保存用户热词 (用于 Java 层保存)
        // Java 只需要传入明文 content，C++ 负责加密写入 filePath
        static bool saveHotwords(const std::string& filePath, const std::string& content);
    };

}

#endif // AEGIS_USER_DATA_GUARD_H