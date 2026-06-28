// =========================================================================================
// 🚀 核心组件: FuzzyMatch (模糊匹配与后处理引擎)
// 🛡️ 架构代号: Titan 0712 (Precision 6-Tier Logic)
//
// 【开源作者声明】：
// 本文件所包含的 "Rank 1/2.5/3/4 分级容错" 以及 "6 档精细化分级 (isFuzzyMatch)" 算法，
// 是我在商业化落地过程中，为解决 Whisper 引擎“幻觉”、“同音异义词”及“热词识别率低”等
// 痛点而自主设计的工程解法。
// 它在我的生产环境中表现出了极高的稳健性，但这仅仅代表我个人的算法思路与解决方案。
// 开源的魅力在于进化，欢迎任何形式的 Fork 与 PR，期待社区用更优雅、更高效的算法来替代或优化它！
// =========================================================================================

#include "fuzzy_match.h"
#include <sstream>
#include <algorithm>
#include <cctype>
#include <cmath>
#include <regex>

namespace AegisCore {

    // ==========================================
    // 🛠️ 基础工具集
    // ==========================================
    std::string FuzzyMatch::toLower(const std::string& s) {
        std::string data = s;
        std::transform(data.begin(), data.end(), data.begin(),
                       [](unsigned char c){ return std::tolower(c); });
        return data;
    }

    // Levenshtein 编辑距离 (动态规划标准实现)
    int FuzzyMatch::levenshteinDistance(const std::string& s1, const std::string& s2) {
        const std::size_t len1 = s1.size(), len2 = s2.size();
        std::vector<int> col(len2 + 1), prevCol(len2 + 1);
        for (int i = 0; i < prevCol.size(); i++) prevCol[i] = i;
        for (int i = 0; i < len1; i++) {
            col[0] = i + 1;
            for (int j = 0; j < len2; j++) {
                col[j + 1] = std::min({ prevCol[1 + j] + 1, col[j] + 1, prevCol[j] + (s1[i] == s2[j] ? 0 : 1) });
            }
            col.swap(prevCol);
        }
        return prevCol[len2];
    }

    // 分词流水线：将纯文本拆解为独立的单词片段与标点符号片段
    std::vector<std::string> FuzzyMatch::splitToTokens(const std::string& text) {
        std::vector<std::string> tokens;
        std::string currentToken;
        bool inWord = false;

        for (char c : text) {
            bool isAlphaNum = isalnum(c) || c == '\'';

            if (isAlphaNum) {
                if (!inWord && !currentToken.empty()) {
                    tokens.push_back(currentToken); // 归档标点缓冲区
                    currentToken.clear();
                }
                inWord = true;
                currentToken += c;
            } else {
                if (inWord) {
                    tokens.push_back(currentToken); // 归档单词缓冲区
                    currentToken.clear();
                }
                inWord = false;
                currentToken += c; // 累积标点符号
            }
        }
        if (!currentToken.empty()) tokens.push_back(currentToken);
        return tokens;
    }

    // N-Gram 探索器 (滑动窗口获取复合词组)
    std::pair<std::string, int> FuzzyMatch::getPhraseFromTokens(const std::vector<std::string>& tokens, int startIndex, int wordCount) {
        std::string phrase;
        int wordsFound = 0;
        int steps = 0;
        for (int k = startIndex; k < tokens.size(); ++k) {
            phrase += tokens[k];
            steps++;
            if (!tokens[k].empty() && isalnum(tokens[k][0])) {
                wordsFound++;
            }
            if (wordsFound == wordCount) break;
        }
        if (wordsFound < wordCount && wordCount > 1) {
            return {tokens[startIndex], 1}; // 无法构成目标长度，回退为单词
        }
        return {phrase, steps};
    }

    // ==========================================
    // 🧠 核心评判枢纽 (The Judge)
    // ==========================================
    bool FuzzyMatch::isFuzzyMatch(const std::string& source, const std::string& target, bool isVip) {
        std::string s = toLower(source);
        std::string t = toLower(target);
        if (s == t) return true;

        int sLen = s.length();
        int tLen = t.length(); // 以目标热词(Target)的长度为基准
        int dist = levenshteinDistance(s, t);

        if (isVip) {
            // 🛑 [防御机制] 长度溢出惩罚：防止高频短词被强行拉伸匹配长词
            if (tLen > sLen * 1.5) return false;

            // ========================================================
            // 🚀 Titan 0712: 6-Tier Precision Logic (6档稳健型分级策略)
            // ========================================================

            // 【Tier 1】长度 <= 3：丢弃容错
            // 策略：高频短词禁区，防止引发链式误杀 (例: 'an' 不可匹配为 'AI')
            if (tLen <= 3) return false;

            // 【Tier 2】4 <= 长度 <= 5：绝对精确区 (Strict)
            // 策略：短词必须 100% 命中，遏制极相似词条碰撞 (例: Meta vs Meat)
            if (tLen <= 5) return dist == 0;

            // 【Tier 3】6 <= 长度 <= 7：微调区 (Semi-Strict)
            // 策略：仅允许 1 个字符的编辑距离差异 (例: Cloud -> Claude 拦截; ChatGP -> ChatGPT 放行)
            if (tLen <= 7) return dist <= 1;

            // 【Tier 4】8 <= 长度 <= 10：稳健容错区 (Standard)
            // 策略：主流词汇黄金标准，允许 2 个字符偏差 (涵盖 DeepMind, Microsoft 等)
            if (tLen <= 10) return dist <= 2;

            // 【Tier 5】11 <= 长度 <= 12：宽松容错区 (Loose)
            // 策略：针对长人名等词汇，允许 3 个字符的较大形变 (涵盖 Sam Altman 等)
            if (tLen <= 12) return dist <= 3;

            // 【Tier 6】长度 > 12：自适应深度容错 (Ultra Loose)
            // 策略：极限长词兜底，容错率拉高至 45%
            return dist <= (int)(tLen * 0.45);

        } else {
            // 🛡️ 常规系统词库 (System Tier) 保留保守型容错机制
            if (tLen < 9) return dist <= 1;
            return dist <= 2;
        }
    }

    // ==========================================
    // 🧹 后置清洗流水线 (Post-Processor)
    // ==========================================
    std::string FuzzyMatch::postProcess(const std::string& text) {
        std::string processed = text;
        try {
            // 1. 🔪 结巴过滤器 (Stutter Killer): 强力消除连续的重复词语，穿透标点
            std::regex stutterRegex(R"((?i)\b([a-z]+)(?:[\s,.\?!]+)\1\b)");
            processed = std::regex_replace(processed, stutterRegex, "$1");

            // 2. 标点标准化 (Punctuation Normalizer)
            std::regex spaceComma(R"( ,)");
            processed = std::regex_replace(processed, spaceComma, ",");
            std::regex spaceDot(R"( \.)");
            processed = std::regex_replace(processed, spaceDot, ".");

            // 3. 尾部截断器 (Ruthless Pruner): 剔除因语音停顿导致的无效连词尾巴
            std::regex ruthlessTailRegex(R"((.*)\b(?:And|But|So|Or|Then|Because)[^a-zA-Z0-9]*$)", std::regex::icase);
            processed = std::regex_replace(processed, ruthlessTailRegex, "$1");
        } catch (...) {}
        return processed;
    }

    // ==========================================
    // 🚀 主执行引擎: 校正器 (The Corrector)
    // ==========================================
    std::string FuzzyMatch::correct(
            const std::string& text,
            const std::vector<std::string>& hallucinationBlacklist,
            const std::set<std::string>& commonWords,
            const std::vector<std::string>& vipHotwords,
            const std::map<int, std::vector<std::string>>& systemTiers
    ) {
        if (text.empty()) return "";

        std::string processedText = text;

        // ========================================================
        // ⚔️ [Rank 1] 黑名单防御 (Blacklist - 一票否决)
        // 目标：发现严重幻觉词汇，直接阻断并返回空字符串
        // ========================================================
        if (!hallucinationBlacklist.empty()) {
            std::string lowerText = toLower(processedText);
            for (const auto& rule : hallucinationBlacklist) {
                if (toLower(rule).empty()) continue;
                if (lowerText.find(toLower(rule)) != std::string::npos) {
                    return "";
                }
            }
        }

        auto tokens = splitToTokens(processedText);
        std::stringstream resultBuilder;

        int i = 0;
        while (i < tokens.size()) {
            std::string token = tokens[i];

            // 标点符号直接透传
            if (token.empty() || !isalnum(token[0])) {
                resultBuilder << token;
                i++;
                continue;
            }

            bool matched = false;

            // ========================================================
            // 👑 [Rank 2] 专属 VIP 热词接管 (基础 6档 逻辑驱动)
            // ========================================================
            if (!vipHotwords.empty()) {
                for (const auto& vip : vipHotwords) {
                    int windowSize = (vip.find(' ') != std::string::npos) ? 2 : 1;
                    if (i + windowSize > tokens.size()) continue;

                    auto [phrase, steps] = getPhraseFromTokens(tokens, i, windowSize);

                    if (!phrase.empty() && isFuzzyMatch(phrase, vip, true)) {
                        resultBuilder << vip;
                        i += steps;
                        matched = true;
                        break;
                    }
                }
            }

            // ========================================================
            // 🔫 [Rank 2.5] 锚点+前缀强力狙击 (Titan 0707 保留策略)
            // 应对极端情况：当两段式长热词出现严重断层时的补救措施
            // ========================================================
            if (!matched && !vipHotwords.empty()) {
                for (const auto& vip : vipHotwords) {
                    size_t spacePos = vip.find(' ');
                    if (spacePos == std::string::npos) continue;

                    std::string part1 = vip.substr(0, spacePos);
                    std::string part2 = vip.substr(spacePos + 1);

                    // 1. 锚定前半部分
                    if (toLower(token) == toLower(part1)) {
                        int nextWordIndex = -1;
                        for (int k = i + 1; k < tokens.size(); ++k) {
                            if (!tokens[k].empty() && isalnum(tokens[k][0])) {
                                nextWordIndex = k;
                                break;
                            }
                            if (k > i + 2) break; // 超过搜索跨度限制
                        }

                        if (nextWordIndex != -1) {
                            std::string nextToken = tokens[nextWordIndex];
                            // 2. 探测后半部分前缀特征
                            if (part2.length() >= 3 && nextToken.length() >= 3) {
                                std::string targetPrefix = toLower(part2.substr(0, 3));
                                std::string sourcePrefix = toLower(nextToken.substr(0, 3));

                                if (targetPrefix == sourcePrefix) {
                                    resultBuilder << vip;
                                    i = nextWordIndex + 1;
                                    matched = true;
                                    break;
                                }
                            }
                        }
                    }
                }
            }

            if (matched) continue;

            // ========================================================
            // 🛡️ [Rank 3] 白名单护城河 (Tier 0)
            // ========================================================
            std::string lowerToken = toLower(token);
            if (commonWords.find(lowerToken) != commonWords.end()) {
                resultBuilder << token;
                i++;
                continue;
            }

            // ========================================================
            // 📚 [Rank 4] 本地分级语料库降维打击 (Tier 1-5)
            // ========================================================
            if (!systemTiers.empty()) {
                int len = token.length();
                int tierKey = 0;
                if (len <= 3) tierKey = 1;
                else if (len <= 6) tierKey = 2;
                else if (len <= 9) tierKey = 3;
                else if (len <= 12) tierKey = 4;
                else tierKey = 5;

                if (systemTiers.count(tierKey)) {
                    const auto& candidates = systemTiers.at(tierKey);
                    for (const auto& candidate : candidates) {
                        // 长度极差过滤，提升匹配速度
                        if (std::abs((int)candidate.length() - len) > 2) continue;
                        if (isFuzzyMatch(token, candidate, false)) {
                            resultBuilder << candidate;
                            matched = true;
                            i++;
                            break;
                        }
                    }
                }
            }

            // ========================================================
            // 🍂 [Default] 透传：无主词汇保留原貌
            // ========================================================
            if (!matched) {
                resultBuilder << token;
                i++;
            }
        }

        // 送入清洗流水线并输出最终文本
        return postProcess(resultBuilder.str());
    }

} // namespace AegisCore