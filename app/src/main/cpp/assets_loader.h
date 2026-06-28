#ifndef AEGIS_ASSETS_LOADER_H
#define AEGIS_ASSETS_LOADER_H

#include <jni.h>
#include <android/asset_manager.h>
#include <android/asset_manager_jni.h>
#include <vector>
#include <string>
#include <set>
#include <map>

namespace AegisCore {

    class AssetsLoader {
    public:
        // 1. 加载白名单 (tier0.dat)
        static std::set<std::string> loadCommonWhitelist(AAssetManager* mgr);

        // ❌ [已删除] loadTierBase
        // 理由：0902 CPP 中没有实现此函数，必须在头文件中移除，否则编译报错。

        // 2. 加载黑名单 (hallucinations.dat)
        static std::vector<std::string> loadBlacklist(AAssetManager* mgr);

        // 3. 加载分级库 (只加载 tier1 - tier5)
        static std::map<int, std::vector<std::string>> loadSystemTiers(AAssetManager* mgr);

        // 4. 通用加载接口 (供 Java 层按需读取 context.dat 或 tier_base.dat)
        static std::vector<std::string> loadGenericList(AAssetManager* mgr, const char* filename);

    private:
        // 内部工具：读取并解密
        static std::vector<char> readAndDecrypt(AAssetManager* mgr, const char* filename);

        // 解析工具
        static void parseLinesToSet(const std::vector<char>& data, std::set<std::string>& outSet);
        static void parseLinesToVector(const std::vector<char>& data, std::vector<std::string>& outVec);
    };
}

#endif // AEGIS_ASSETS_LOADER_H