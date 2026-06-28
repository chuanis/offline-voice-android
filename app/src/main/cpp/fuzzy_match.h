#ifndef AEGIS_VOICE_FUZZY_MATCH_H
#define AEGIS_VOICE_FUZZY_MATCH_H

#include <string>
#include <vector>
#include <set>
#include <map>

namespace AegisCore {

    class FuzzyMatch {
    public:
        /**
         * 🚀 核心修正入口：全参数支持 (完美复刻 Java 逻辑)
         * @param text 原始文本 (Whisper 输出)
         * @param hallucinationBlacklist Tier 1: 黑名单 (用于绞杀幻觉)
         * @param commonWords Tier 0: 白名单 (用于防御误杀)
         * @param vipHotwords Tier 1010: 用户热词 (最高优先级)
         * @param systemTiers Tier 1-5: 本地分级库 (兜底修正)
         */
        static std::string correct(
                const std::string& text,
                const std::vector<std::string>& hallucinationBlacklist,
                const std::set<std::string>& commonWords,
                const std::vector<std::string>& vipHotwords,
                const std::map<int, std::vector<std::string>>& systemTiers
        );

    private:
        // 核心算法
        static int levenshteinDistance(const std::string& s1, const std::string& s2);
        static std::string toLower(const std::string& s);

        // 分词与重组
        static std::vector<std::string> splitToTokens(const std::string& text);
        static std::pair<std::string, int> getPhraseFromTokens(const std::vector<std::string>& tokens, int startIndex, int wordCount);

        // 判决逻辑
        static bool isFuzzyMatch(const std::string& source, const std::string& target, bool isVip);

        // 后处理 (复刻 Java 的 fixStutter, Ruthless Pruner 和标点清洗)
        static std::string postProcess(const std::string& text);
    };

} // namespace AegisCore

#endif //AEGIS_VOICE_FUZZY_MATCH_H