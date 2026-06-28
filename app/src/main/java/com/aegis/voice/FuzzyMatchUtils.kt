package com.aegis.voice

import android.content.Context
import android.util.Log
import java.io.File
import java.util.Locale
import kotlin.math.min
import kotlin.math.abs
import kotlin.math.max

/**
 * =========================================================================================
 * 🧠 Aegis 逻辑中枢 (NLP 后处理与无缝拼接引擎)
 * 🛡️ 架构代号：Titan 1010 [The Resurrection - Echo Eraser Pro]
 * * 【模块职责】：
 * 1. JNI 通信路由：负责唤醒底层 C++ 的加密字典加载与释放。
 * 2. 智能流式拼接 (SmartMerge)：解决端侧 AI 分块识别时，首尾文本重叠（重影）的行业难题。
 * 3. 幻觉与结巴消除 (Echo Eraser)：利用编辑距离与高阶正则，执行微秒级的文本“外科手术”，
 * 强制切除 AI 生成的重复词、断句和驼峰粘连。
 * =========================================================================================
 */
object FuzzyMatchUtils {

    private const val TAG = "AegisFuzzy"

    // 状态锁：标记 C++ 底层是否已经完成加密语料的加载
    private var isNativeInitialized = false

    // =========================================================================
    // 🚀 [生命周期] C++ 底层引擎唤醒接口 (JNI Crypto 初始化)
    // =========================================================================
    fun init(context: Context) {
        if (isNativeInitialized) return

        Log.i(TAG, "🚀 [FuzzyMatch] 正在通过 JNI 安全通道初始化底层静态语料库...")
        try {
            // 锁定用户专属热词文件路径
            val hotwordsFile = File(context.filesDir, "tier1010.dat")
            val hotwordsPath = hotwordsFile.absolutePath

            // 唤醒底层 C++ 的 AssetsLoader，交接读取权限
            NativeLib.initStaticData(context.assets, hotwordsPath)

            isNativeInitialized = true
            Log.i(TAG, "✅ [安全物流] 底层 C++ 初始化指令发送完毕。")
        } catch (e: Exception) {
            Log.e(TAG, "❌ [致命错误] 底层 C++ 语料库初始化失败: ${e.message}")
        }
    }

    /**
     * 🔄 场景切换重置
     * 当用户切换应用场景（如从“通用”切到“医疗”）时，强制清空底层的常驻内存，防止语料污染。
     */
    fun reset() {
        Log.i(TAG, "🔄 [状态重置] 正在清空底层 FuzzyMatch 内存状态，准备场景切换。")
        isNativeInitialized = false
        NativeLib.resetStaticData()
    }

    // =========================================================================
    // 🚀 [核心路由] JNI 模糊纠错入口
    // =========================================================================
    fun correct(
        text: String,
        vipHotwords: List<String>,
        commonWords: Set<String>,
        contextWords: List<String>,
        systemTiers: Map<Int, List<String>>,
        hallucinationBlacklist: List<String>
    ): String {
        if (text.isBlank()) return ""
        // 将明文文本和热词数组通过 JNI 扔给 C++ 的 Titan 纠错引擎进行降维打击
        return NativeLib.nativeFuzzyCorrect(text, vipHotwords.toTypedArray())
    }

    // =========================================================================
    // 🚀 [终极进化] 智能流式拼接引擎 (SmartMerge 5.1 - 词法级重影消除)
    // 解决流式语音识别中，Chunk A 结尾与 Chunk B 开头重复的行业痛点。
    // =========================================================================
    fun smartMerge(previous: String, current: String): String {
        if (previous.isEmpty()) return current
        if (current.isEmpty()) return ""

        // 绝对重合防御：如果前文已经完全包含了当前文本，直接抛弃当前文本
        if (previous.endsWith(current)) return ""

        // 文本归一化处理（转小写、去标点），缩小计算范围提高性能
        val prevNorm = normalize(previous)
        val currNorm = normalize(current)
        val searchWindow = if (prevNorm.length > 100) prevNorm.takeLast(100) else prevNorm

        // 🛡️ [逻辑 A] 降维包含去重
        if (searchWindow.endsWith(currNorm)) {
            return ""
        }

        // 🔥 [核心手术 1] 跨切片词法级滑动重影消除 (Word-Level Sliding Window)
        try {
            // 将文本切分为单词数组
            val prevWords = previous.trimEnd().split(Regex("[\\s.,?!]+")).filter { it.isNotEmpty() }
            val currWords = current.trimStart().split(Regex("[\\s.,?!]+")).filter { it.isNotEmpty() }

            // 动态判定最大重叠窗口（最多往回看 12 个单词）
            val maxWordOverlap = min(12, min(prevWords.size, currWords.size))

            // 倒序滑动窗口：从最大可能重叠处开始尝试匹配
            for (overlap in maxWordOverlap downTo 1) {
                val tailWords = prevWords.takeLast(overlap)
                val headWords = currWords.take(overlap)

                val tailStr = normalize(tailWords.joinToString(""))
                val headStr = normalize(headWords.joinToString(""))

                if (tailStr.length >= 4 || overlap == 1) {
                    // 核心算力：计算前后词组的 Levenshtein 相似度
                    val sim = calculateSimilarity(tailStr, headStr)

                    // 相似度阈值判定：>80% 视为发生重影，触发切除
                    if (sim > 0.80f || (sim > 0.75f && overlap >= 3)) {
                        var matchEndIndex = 0
                        var wordCount = 0

                        // 定位当前文本 (current) 中需要被切除的真实物理索引
                        val matcher = Regex("[a-zA-Z0-9]+").findAll(current)
                        for (match in matcher) {
                            wordCount++
                            if (wordCount == overlap) {
                                matchEndIndex = match.range.last + 1
                                break
                            }
                        }

                        if (matchEndIndex > 0 && matchEndIndex <= current.length) {
                            // 切除重叠部分
                            var remaining = current.substring(matchEndIndex)
                            remaining = remaining.trimStart(*charArrayOf(' ', '.', ',', '?', '!', ';', ':'))
                            Log.d(TAG, "🔥 [词法重影消除触发] 相似度: $sim. 抛弃重叠片段: ${current.substring(0, matchEndIndex)}")

                            // 🛠️ [微型手术 C] 跨切片语法自愈：如果前文是以句号等结束，强制将接上的第一个单词大写
                            val prevTrimmed = previous.trimEnd()
                            if (remaining.isNotEmpty() && prevTrimmed.isNotEmpty() && prevTrimmed.last() in listOf('.', '?', '!')) {
                                if (remaining[0].isLowerCase()) {
                                    remaining = remaining.substring(0, 1).uppercase(Locale.getDefault()) + remaining.substring(1)
                                }
                            }

                            // 补齐安全空格
                            if (remaining.isNotEmpty() && Character.isLetterOrDigit(remaining[0])) {
                                remaining = " $remaining"
                            }
                            return remaining
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "❌ [算法异常] 词法级合并失败", e)
        }

        // 🛡️ [逻辑 B] 字符级降维兜底拼接 (保留原版的暴力极速匹配)
        val prevLen = previous.length
        val currLen = current.length
        val maxOverlap = min(50, min(prevLen, currLen))
        val minOverlap = 3

        for (i in maxOverlap downTo minOverlap) {
            val tail = previous.takeLast(i)
            val head = current.take(i)
            val tailClean = normalize(tail)
            val headClean = normalize(head)

            if (tailClean == headClean) {
                return current.substring(i)
            }
            if (tailClean.length > 5) {
                if (calculateSimilarity(tailClean, headClean) > 0.85f) {
                    Log.d(TAG, "⚡ [字符级模糊合并触发]: [$tailClean] 咬合 [$headClean]")
                    return current.substring(i)
                }
            }
        }

        // 🛡️ [逻辑 C] 智能补齐边缘空格，防止单词粘连 (如 "Hello""world" 变 "Helloworld")
        if (!previous.endsWith(" ") && !current.startsWith(" ")) {
            if (current.isNotEmpty() && Character.isLetterOrDigit(current[0])) {
                return " $current"
            }
        }

        return current
    }

    /**
     * 辅助工具：纯净归一化 (剔除所有非字母数字，转小写)
     */
    private fun normalize(input: String): String {
        return input.replace(Regex("[^a-zA-Z0-9]"), "").lowercase(Locale.getDefault())
    }

    /**
     * 数学引擎：基于 Levenshtein 距离计算两个字符串的百分比相似度 (0.0 ~ 1.0)
     */
    private fun calculateSimilarity(s1: String, s2: String): Float {
        if (s1 == s2) return 1.0f
        val len1 = s1.length
        val len2 = s2.length
        if (len1 == 0 || len2 == 0) return 0.0f

        // 长度悬殊过大，直接判定不相似，节约算力
        if (abs(len1 - len2) > 4) return 0.0f

        val dist = levenshtein(s1, s2)
        val maxLen = max(len1, len2)
        return 1.0f - (dist.toFloat() / maxLen)
    }

    /**
     * 核心算法：标准 Levenshtein (编辑距离) 动态规划实现
     */
    private fun levenshtein(s1: String, s2: String): Int {
        val m = s1.length
        val n = s2.length
        val dp = Array(m + 1) { IntArray(n + 1) }
        for (i in 0..m) dp[i][0] = i
        for (j in 0..n) dp[0][j] = j
        for (i in 1..m) {
            for (j in 1..n) {
                val cost = if (s1[i - 1] == s2[j - 1]) 0 else 1
                dp[i][j] = min(min(dp[i - 1][j] + 1, dp[i][j - 1] + 1), dp[i - 1][j - 1] + cost)
            }
        }
        return dp[m][n]
    }

    // =========================================================================
    // 🔥 [核心手术 A] 单切片内结巴与重影消除引擎 (In-Chunk Echo Eraser)
    // 专门对付 AI 模型由于音频底噪产生的幻觉结巴。
    // =========================================================================
    private fun applyInChunkEchoEraser(text: String): String {
        if (text.length < 5) return text
        // 以标点符号为界，将文本拆分为句子数组
        val sentences = text.split(Regex("(?<=[.,?!])\\s+"))
        if (sentences.size <= 1) return text

        val newSentences = mutableListOf<String>()
        var skipNext = false

        for (i in 0 until sentences.size - 1) {
            if (skipNext) {
                skipNext = false
                continue
            }
            val s1 = sentences[i]
            val s2 = sentences[i+1]

            val s1Clean = s1.replace(Regex("[^a-zA-Z0-9 ]"), "").trim().lowercase()
            val s2Clean = s2.replace(Regex("[^a-zA-Z0-9 ]"), "").trim().lowercase()

            val s1Words = s1Clean.split(Regex("\\s+")).filter { it.isNotEmpty() }
            val s2Words = s2Clean.split(Regex("\\s+")).filter { it.isNotEmpty() }

            // 🔪 场景 1: 前句是后句的开头结巴试探 (例: "The collectible. The collective intelligence")
            if (s1Words.isNotEmpty() && s1Words.size <= 6 && s2Words.size >= s1Words.size) {
                val s2Prefix = s2Words.take(s1Words.size).joinToString(" ")
                val s1Str = s1Words.joinToString(" ")
                if (s1Str.length > 3 && calculateSimilarity(s1Str, s2Prefix) > 0.88f) {
                    continue // 果断抛弃结巴的前句，保留完整的后句
                }
            }

            // 🔪 场景 2: 后句是前句的尾部结巴残留 (例: "It is a good day. A good day.")
            if (s2Words.isNotEmpty() && s2Words.size <= 6 && s1Words.size >= s2Words.size) {
                val s1Suffix = s1Words.takeLast(s2Words.size).joinToString(" ")
                val s2Str = s2Words.joinToString(" ")
                if (s2Str.length > 3 && calculateSimilarity(s1Suffix, s2Str) > 0.88f) {
                    newSentences.add(s1)
                    skipNext = true // 保留完整的前句，吃掉结巴的后句
                    continue
                }
            }

            newSentences.add(s1)
        }

        // 补回最后一句
        if (!skipNext) {
            newSentences.add(sentences.last())
        }
        return newSentences.joinToString(" ")
    }

    /**
     * =========================================================================
     * 🧼 [核心逻辑 2] 后处理终极清洗流水线 (Post-Processing Pipeline)
     * 解决模型断句错误、悬空单词、无意义循环等致命格式问题。
     * =========================================================================
     */
    fun fixStutterAndCamelCase(text: String): String {
        if (text.isBlank()) return ""
        var processed = text

        // 🛡️ 步骤 0. 调用切片内重影消除引擎
        try {
            processed = applyInChunkEchoEraser(processed)
        } catch (e: Exception) {}

        // 🛡️ 步骤 1. [死循环杀手]：强制阻断长文本崩溃级的无限重复 (如 "abc abc abc abc")
        try {
            val loopRegex = Regex("(?i)(.{15,})([\\W\\s]+?)\\1(?:\\2\\1)*")
            processed = processed.replace(loopRegex, "$1$2")
        } catch (e: Exception) {}

        // 🔥 [微型手术 B.1] 断头台碎片缝合：消除不合逻辑的被动句号截断 (例: "steel. an" -> "steel an")
        processed = processed.replace(Regex("([a-z])\\.\\s+([a-z])"), "$1 $2")

        // 🔥 [微型手术 B.2] 悬空冠词清除：消除句末被孤立的连词/冠词 (例: "like a. Understood" -> "like a Understood")
        processed = processed.replace(Regex("(?i)\\b(a|an|the|and|or|but|is|are|of|in|to)\\.\\s+([A-Z])"), "$1 $2")

        // 🛡️ 步骤 2. [驼峰拆分]：解决模型吐出连体词的问题 (例: "helloWorld" -> "hello World")
        processed = processed.replace(Regex("([a-z])([A-Z])"), "$1 $2")

        // 🛡️ 步骤 3. [标点粘连拆分]：(例: "hello.World" -> "hello. World")
        processed = processed.replace(Regex("([a-z][.,?!])([A-Z])"), "$1 $2")

        // 🛡️ 步骤 4. [强力单单词去重]：无视标点，删除连续重复单词 (例: "hello, hello" -> "hello")
        processed = processed.replace(Regex("(?i)\\b([a-z]+)(?:[\\s.,?!]+)\\1\\b"), "$1")

        // 🛡️ 步骤 5. [微创清洗]：清除极个别字母粘连
        processed = processed.replace(Regex("\\b[a-z]([A-Z][a-z]+)"), "$1")

        // 🛡️ 步骤 6. [拆分粘连]：强制隔开首字母大写的拼接词
        processed = processed.replace(Regex("([a-z]{2,})([A-Z][a-z]+)"), "$1 $2")

        // 🛡️ 步骤 7. [连续去重]：常规去重兜底
        processed = processed.replace(Regex("(?i)\\b([a-z]+)\\b(?:\\s+\\1\\b)+"), "$1")

        // 🛡️ 步骤 8. [标点标准化]：清理多余的空格前缀
        processed = processed.replace(" ,", ",").replace(" .", ".")

        return processed.trim()
    }
}