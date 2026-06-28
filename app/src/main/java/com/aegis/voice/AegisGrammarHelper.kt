package com.aegis.voice

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.io.File
import com.google.gson.Gson

/**
 * =========================================================================================
 * 🧠 Aegis 语料物流分配中心 (Grammar Helper V13)
 * * 【架构说明】：
 * 本模块是连接 Android 应用层与 C++ 底层加密词库的桥梁。
 * 1. 安全性：所有核心词库（白名单、黑名单、底座词汇）均通过 JNI 黑盒通道读取解密，Java 层不接触任何密钥。
 * 2. 算力隔离：严格区分 Vosk（小模型）与 Whisper（大模型）的词汇供给。
 * - Vosk 仅分配最基础的口语底座，保证实时响应速度。
 * - 用户私人热词与上下文词库专供大模型，防止干扰日常口语识别。
 * =========================================================================================
 */
object AegisGrammarHelper {

    private const val TAG = "AegisGrammar"

    // 🛡️ [架构优化] 抛弃危险的 GlobalScope，创建专属的、安全的后台 IO 协程作用域
    private val helperScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    // ==========================================
    // 🚀 引擎点火前的预加载校验
    // ==========================================
    fun preloadAllDictionaries(context: Context) {
        helperScope.launch {
            Log.i(TAG, "🚀 [Aegis V13] 启动底层 JNI 加密通道，执行词库完整性校验...")

            // 1. 探针校验：底层 C++ 白名单 (Tier 0)
            val tier0 = getCommonWhitelist(context)
            if (tier0.isNotEmpty()) {
                Log.i(TAG, "✅ [安全物流] 白名单矩阵 (Tier 0) 已解密并就绪，共装载 ${tier0.size} 条指令")
            } else {
                Log.e(TAG, "❌ [致命错误] 白名单矩阵 (Tier 0) 缺失或底层解密算法拦截！")
            }

            // 2. 探针校验：轻量级引擎 Vosk 专属底座
            val baseWords = loadFileLines(context, "lexicons/tier_base.dat")
            if (baseWords.isNotEmpty()) {
                Log.i(TAG, "✅ [安全物流] Vosk 基础语料库已解密就绪，共装载 ${baseWords.size} 条指令")
            } else {
                Log.e(TAG, "❌ [致命错误] Vosk 基础语料库缺失或底层解密算法拦截！")
            }
        }
    }

    // ==========================================
    // 🧠 核心数据供给接口 (供给 C++ 纠错引擎与 Whisper)
    // ==========================================

    /**
     * 📁 提取用户专属定制热词 (Tier 1010)
     * 【隔离策略】：此数据仅供给 Whisper 的 Prompt 和 C++ 的模糊纠错矩阵。
     * 【绝对禁止】：绝对不允许喂给 Vosk，否则用户录入的专业名词会严重污染日常拼写。
     */
    fun getUserCustomWords(context: Context): List<String> {
        val userFile = File(context.filesDir, "tier1010.dat")
        if (userFile.exists()) {
            try {
                // ⚡ 通过 JNI 安全通道读取，C++ 在内存中就地解密后返回明文数组
                val rawArray = NativeLib.getUserWords(userFile.absolutePath)

                // 🛑 [核心防御机制]：清洗隐藏字符
                // 剔除可能因不同操作系统编辑器引入的 \r 回车符，防止 C++ 底层正则匹配崩溃
                return rawArray.map { it.trim() }.filter { it.isNotEmpty() }
            } catch (e: Exception) {
                Log.e(TAG, "❌ 提取用户专属隐私热词失败: ${e.message}")
            }
        }
        return emptyList()
    }

    /**
     * 📁 提取场景上下文提示词 (Context)
     * 用于在特定场景（如法律、医疗）下，引导 Whisper 引擎偏向专业词汇。
     */
    fun getContextWords(context: Context, scenarioCode: String): List<String> {
        val folderLower = "lexicons/${scenarioCode.lowercase()}"
        val path = findCaseInsensitivePath(context, folderLower, "context.dat") ?: return emptyList()
        return loadFileLines(context, path)
    }

    /**
     * 📁 提取通用白名单 (Tier 0)
     * 直接移交 C++ 层作为终极防御盾。
     */
    fun getCommonWhitelist(context: Context): Set<String> {
        return loadFileLines(context, "lexicons/tier0.dat").toSet()
    }

    /**
     * 📁 提取幻觉黑名单 (Hallucinations)
     * 包含 Whisper 常见无意义的幻觉词汇，移交 C++ 执行物理级斩杀。
     */
    fun getHallucinationBlacklist(context: Context): List<String> {
        return loadFileLines(context, "lexicons/hallucinations.dat")
    }

    // ==========================================
    // 🚀 [Vosk 专用] 动态语法糖生成器 (Grammar Builder)
    // ==========================================

    /**
     * 为轻量级识别引擎 Vosk 动态构建 JSON 语法限制。
     * 【核心策略】：只给底座（~2000个高频口语），不给专业词！
     * 这使得 Vosk 能够极速处理“你好”、“是”、“结束”等控制流词汇，且不会出现内存溢出。
     */
    fun buildGrammarJson(context: Context, scenarioCode: String): String {
        val finalWordSet = HashSet<String>()

        // 1. 通过 JNI 安全通道，加载 Vosk 专属底座
        val basePath = "lexicons/tier_base.dat"
        val baseWords = loadFileLines(context, basePath)

        if (baseWords.isNotEmpty()) {
            finalWordSet.addAll(baseWords)
        } else {
            Log.e(TAG, "❌ CRITICAL: 'tier_base.dat' (底座词库) 缺失，Vosk 可能瘫痪！")
        }

        // 2. 注入未知词标记，确保遇到不在字典里的声音时，Vosk 也能输出 [unk] 而非崩溃
        finalWordSet.add("[unk]")

        Log.i(TAG, "⚡ Vosk 轻量级语法树构建完成: 包含 ${finalWordSet.size} 个高频锚点词")
        return Gson().toJson(finalWordSet)
    }

    // ==========================================
    // 🛠️ 内部底层 I/O 通道
    // ==========================================

    /**
     * 🔐 底层安全读取管道
     * 调用 C++ 的 `getDecryptedAsset`，在底层将流密码解密后传递回 Java。
     */
    private fun loadFileLines(context: Context, path: String): List<String> {
        return try {
            // 1. 向 C++ 兵工厂索要解密后的资产
            val decryptedArray = NativeLib.getDecryptedAsset(context.assets, path)

            // 2. 数据清洗：抹除所有由于文件系统不同导致的隐形换行符 (CRLF/LF)
            decryptedArray.map { it.trim() }.filter { it.isNotEmpty() }

        } catch (e: Exception) {
            // 容错降级：当解密失败或文件损坏时，优雅返回空列表，防止引发应用级 Crash
            Log.w(TAG, "⚠️ JNI 底层解密通道警告 [$path]: ${e.message}")
            emptyList()
        }
    }

    /**
     * 🔍 忽略大小写的路径探测器
     * Android 的 AssetManager 对大小写极其敏感。此方法用于保证即使文件夹命名
     * 大小写不规范，也能安全寻址到对应的相对路径。
     */
    private fun findCaseInsensitivePath(context: Context, folder: String, targetName: String): String? {
        try {
            val files = context.assets.list(folder) ?: return null
            for (f in files) {
                if (f.equals(targetName, ignoreCase = true)) return "$folder/$f"
            }
        } catch (e: Exception) {
            return null
        }
        return null
    }
}