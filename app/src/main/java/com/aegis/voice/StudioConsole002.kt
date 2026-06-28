package com.aegis.voice

import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Locale
import kotlin.math.ceil
import kotlin.math.min

/**
 * =========================================================================================
 * 🚀 Aegis Studio V2.0 - 002 重火力指挥中心 (Offline Inference Console)
 * * 🛡️ 【架构核心揭秘】：
 * 1. 永不 OOM 的流式切片：利用 RandomAccessFile 游标技术，将超大 PCM 文件视为磁带，
 * 每次仅提取 2 分钟的真实字节片段加载至内存，内存曲线永远呈水平直线。
 * 2. 磁场隔离与污染防御：只在底层大模型 (Whisper) 中注入 Context 场景提示词，建立潜意识磁场；
 * 将最高优先级的 VIP 用户热词移交 C++ Fuzz 层进行后置硬覆盖，避免大模型语境崩坏。
 * 3. 终极护盾 [动态回溯防爆算法]：
 * 解决行业内 Whisper 处理尾部短音频时因补 0 导致 log(0) 频谱崩溃的死穴。
 * 策略：当最后一段音频不足时，从文件绝对末尾向左“借用”真实音频凑齐算力窗口，
 * 产生重叠的转写文本后，利用上层的 FuzzyMatch.smartMerge (重影消除算法) 无痕切除。
 * =========================================================================================
 */
object StudioConsole002 {

    private const val TAG = "AegisStudio002"

    // --- 音频物理常量 ---
    private const val SAMPLE_RATE = 16000
    private const val SLICE_DURATION_SEC = 120                           // 标准切片窗口：2分钟
    private const val SAMPLES_PER_SLICE = SAMPLE_RATE * SLICE_DURATION_SEC // 1,920,000 采样点
    private const val BYTES_PER_SLICE = SAMPLES_PER_SLICE * 2L             // 3,840,000 字节

    // --- 排版美学常量 ---
    // 最佳阅读视觉切片大小，用于 Markdown 段落内部的智能换行
    private const val CHUNK_MAX_LENGTH = 600

    private var isEngineLoaded = false
    private var lastLoadedScenario = ""

    // --- 内存资产缓存库 (与 AegisGrammarHelper 物理对齐) ---
    private var cachedUserHotwords: List<String> = emptyList()         // VIP 热词 (最高权限，仅供 C++ 纠错)
    private var cachedContextWords: List<String> = emptyList()         // 场景提示词 (供 Whisper 建立磁场)
    private var cachedSystemTiers: Map<Int, List<String>> = emptyMap() // 场景专业分级词库 (供 C++ 纠错)
    private var cachedCommonWords: Set<String> = emptySet()            // 通用白名单 (供 C++ 纠错)
    private var cachedBlacklist: List<String> = emptyList()            // 幻觉黑名单 (供 C++ 物理斩杀)

    // ==========================================
    // ⚙️ 引擎点火与资产挂载协议
    // ==========================================
    suspend fun initialize(context: Context, scenarioCode: String): Boolean = withContext(Dispatchers.IO) {
        if (!isEngineLoaded) {
            val userWordsPath = File(context.filesDir, "tier1010.dat").absolutePath
            // 唤醒 C++ 物流中心
            NativeLib.initStaticData(context.assets, userWordsPath)

            // 引擎装载优先级：NPU(1) 绝对优先，失败则降级为 CPU(0)
            val success = NativeLib.loadModel(context.applicationContext, context.assets, "aegis.core.engine", 1)
            if (!success) {
                NativeLib.loadModel(context.applicationContext, context.assets, "aegis.core.engine", 0)
            }
            NativeLib.setWhisperParams(5, 2.8f)
            isEngineLoaded = true
        }

        // 场景切换侦测：一旦变更，立刻从 Helper 索要并覆写全套词库
        if (lastLoadedScenario != scenarioCode) {
            Log.i(TAG, "📡 正在挂载专属领域资产矩阵: $scenarioCode")

            // 🚀 1. 获取 VIP 专属词 (最高覆盖权限)
            cachedUserHotwords = AegisGrammarHelper.getUserCustomWords(context)

            // 🚀 2. 获取场景 Context (构建大模型推理潜意识)
            cachedContextWords = AegisGrammarHelper.getContextWords(context, scenarioCode)

            // 3. 获取 C++ 兜底纠错阵列
            cachedCommonWords = AegisGrammarHelper.getCommonWhitelist(context)
            cachedBlacklist = AegisGrammarHelper.getHallucinationBlacklist(context)
            loadSystemTiers(context, scenarioCode)

            lastLoadedScenario = scenarioCode

            // 通知 C++ 层清理历史状态，准备迎接新战役
            FuzzyMatchUtils.init(context)
        }
        return@withContext true
    }

    private fun loadSystemTiers(context: Context, scenarioCode: String) {
        val tierMap = HashMap<Int, List<String>>()
        val folder = scenarioCode.lowercase(Locale.getDefault())
        for (i in 1..5) {
            try {
                // 通过 JNI 黑盒安全解密
                val words = NativeLib.getDecryptedAsset(context.assets, "lexicons/$folder/tier$i.dat")
                tierMap[i] = words.map { it.trim() }.filter { it.isNotBlank() }.distinct()
            } catch (e: Exception) {
                /* 宽容处理：允许某些层级词库为空 */
            }
        }
        cachedSystemTiers = tierMap
    }

    // ==========================================
    // 🧠 核心推理流水线：游标切片与重组
    // ==========================================
    suspend fun processAudioData(
        context: Context,
        pcmFile: File,
        onProgressUpdate: (Int, Int) -> Unit
    ): File? = withContext(Dispatchers.IO) {

        if (!pcmFile.exists() || pcmFile.length() == 0L) return@withContext null

        val totalBytes = pcmFile.length()
        val totalSlices = ceil(totalBytes.toDouble() / BYTES_PER_SLICE).toInt()

        Log.i(TAG, "💿 物理音频挂载成功. 总算力量: $totalBytes bytes. 拆分切片: $totalSlices.")

        // 构建目标 Markdown 文件
        val sdf = java.text.SimpleDateFormat("yyyyMMdd_HHmm", Locale.getDefault())
        val timeStr = sdf.format(java.util.Date())
        val mdFile = File(context.getExternalFilesDir(null), "Aegis_Transcript_${timeStr}.md")

        // 🛡️ [架构优化] 使用双重 .use 安全作用域，确保文件锁在任何极端异常下都会被绝对释放
        FileOutputStream(mdFile, true).use { fileStream ->
            RandomAccessFile(pcmFile, "r").use { raf ->

                // 🚀 注入极客风 Markdown 文件头部声明
                val header = """
                    # Aegis Studio Transcription
                    
                    > Generated entirely on-device via Aegis V2.0 Core for absolute privacy.
                    > Applied Lexicon Domain: **$lastLoadedScenario**
                    
                    ---
                    
                """.trimIndent()
                fileStream.write(header.toByteArray())

                var lastCommittedText = ""
                var previousContext = ""
                var currentSliceIndex = 0

                while (currentSliceIndex < totalSlices) {
                    val remainingQueue = totalSlices - currentSliceIndex
                    onProgressUpdate(remainingQueue, totalSlices)

                    // 🔪 游标定位，精准切下当前的 2 分钟物理字节块
                    var startByteIndex = currentSliceIndex * BYTES_PER_SLICE
                    var bytesToRead = min(BYTES_PER_SLICE, totalBytes - startByteIndex).toInt()

                    // ==========================================
                    // 🚀 终极防爆算法：动态音频回溯 (Dynamic Audio Backtracking)
                    // ==========================================
                    if (currentSliceIndex == totalSlices - 1) {
                        // Whisper 推理引擎物理底线：最低要求凑齐 30 秒的整数倍
                        val bytesPer30Sec = 16000 * 30 * 2L // 960,000 字节
                        val blocksNeeded = ceil(bytesToRead.toDouble() / bytesPer30Sec).toInt()
                        val optimalBytes = (blocksNeeded * bytesPer30Sec).toInt()

                        if (optimalBytes <= totalBytes) {
                            // 核心动作：从文件绝对末尾“向左真实借用”一段音频，彻底消灭末尾补 0 导致的模型崩溃
                            startByteIndex = totalBytes - optimalBytes
                            bytesToRead = optimalBytes
                        } else {
                            // 极端情况：整个文件连 30 秒都不够，直接梭哈全量送入
                            startByteIndex = 0
                            bytesToRead = totalBytes.toInt()
                        }
                    }

                    raf.seek(startByteIndex)
                    if (bytesToRead <= 0) break

                    val byteArray = ByteArray(bytesToRead)
                    raf.readFully(byteArray)

                    // 将裸字节映射为 NPU 所需的 Float 矩阵 (-1.0 ~ 1.0)
                    val shortBuffer = ByteBuffer.wrap(byteArray).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
                    val currentSliceLength = shortBuffer.remaining()

                    // 按实际真实长度开辟直接内存，绝不分配冗余的 0
                    val processingBuffer = ByteBuffer.allocateDirect(currentSliceLength * 4).order(ByteOrder.nativeOrder())
                    while (shortBuffer.hasRemaining()) {
                        processingBuffer.putFloat(shortBuffer.get() / 32768.0f)
                    }
                    processingBuffer.flip()

                    // ==========================================
                    // 🧠 步骤 1：大模型推理 (注入场景磁场)
                    // ==========================================
                    val contextPrompt = cachedContextWords.joinToString(", ")
                    var dynamicPrompt = contextPrompt
                    if (previousContext.isNotEmpty()) {
                        dynamicPrompt += " Previous context: $previousContext"
                    }

                    // 启动 C++ Whisper 引擎进行算力压榨
                    val rawText = NativeLib.transcribeAudio(processingBuffer, currentSliceLength, dynamicPrompt)

                    // ==========================================
                    // 🧠 步骤 2：C++ 底层武力接管 (执行精准纠错)
                    // ==========================================
                    var cleanText = FuzzyMatchUtils.correct(
                        rawText,
                        cachedUserHotwords,  // <- VIP 词库作为太上皇行使一票否决与强行替换权
                        cachedCommonWords,
                        cachedContextWords,
                        cachedSystemTiers,
                        cachedBlacklist
                    )

                    // ==========================================
                    // 🧠 步骤 3：Java 缝合手术与重影消除
                    // ==========================================
                    var textToCommit = cleanText
                    if (lastCommittedText.isNotEmpty() && cleanText.isNotEmpty()) {
                        // 此处完美闭环了前面的“动态回溯借用声音”：
                        // smartMerge 会精准识别出重复的转写内容，像拉链一样瞬间将重叠部分消除
                        textToCommit = FuzzyMatchUtils.smartMerge(lastCommittedText, cleanText)
                    }
                    textToCommit = FuzzyMatchUtils.fixStutterAndCamelCase(textToCommit)

                    // ==========================================
                    // 📝 步骤 4：出版级 Markdown 排版写入
                    // ==========================================
                    if (textToCommit.isNotBlank()) {
                        // 注入时段标签
                        val startMin = (currentSliceIndex * 2)
                        val endMin = startMin + 2
                        val timeStamp = "### [%02d:00 - %02d:00]\n\n".format(startMin, endMin)
                        fileStream.write(timeStamp.toByteArray())

                        // 🚀 启动滑动窗口智能断句排版算法
                        val totalChars = textToCommit.length
                        var startIndex = 0

                        while (startIndex < totalChars) {
                            var endIndex = startIndex + CHUNK_MAX_LENGTH
                            if (endIndex >= totalChars) {
                                endIndex = totalChars
                            } else {
                                // 寻找最优安全换行点：优先寻找中后段的句号
                                val lastPeriodIndex = textToCommit.lastIndexOf('.', endIndex)
                                if (lastPeriodIndex > startIndex + (CHUNK_MAX_LENGTH / 2)) {
                                    endIndex = lastPeriodIndex + 1
                                } else {
                                    // 退而求其次，寻找空格断点，绝不腰斩单词
                                    val lastSpaceIndex = textToCommit.lastIndexOf(' ', endIndex)
                                    if (lastSpaceIndex > startIndex) endIndex = lastSpaceIndex
                                }
                            }

                            val formatChunk = textToCommit.substring(startIndex, endIndex).trim()
                            if (formatChunk.isNotEmpty()) {
                                fileStream.write((formatChunk + "\n\n").toByteArray())
                            }
                            startIndex = endIndex
                        }

                        // 提取末尾 100 字符作为下一切片的先验上下文
                        lastCommittedText = cleanText
                        previousContext = if (textToCommit.length > 100) textToCommit.takeLast(100) else textToCommit
                    }

                    // 释放切片 Direct 内存，迎接下一轮循环
                    processingBuffer.clear()
                    currentSliceIndex++
                }
            } // RAF 自动关闭
        } // FileOutputStream 自动关闭

        // ♻️ 毁尸灭迹：物理销毁体积庞大的临时裸 PCM 文件，保护用户存储空间
        if (pcmFile.exists()) pcmFile.delete()

        // 任务闭环，上报 UI
        onProgressUpdate(0, totalSlices)
        return@withContext mdFile
    }

    // ==========================================
    // 📤 系统级文件分享管道
    // ==========================================
    fun shareMarkdownFile(context: Context, mdFile: File) {
        try {
            val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", mdFile)
            val shareIntent = Intent(Intent.ACTION_SEND).apply {
                type = "text/markdown"
                putExtra(Intent.EXTRA_STREAM, uri)
                putExtra(Intent.EXTRA_SUBJECT, "Aegis Studio Transcription Document")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            context.startActivity(Intent.createChooser(shareIntent, "分享会议记录 (Share Document)"))
        } catch (e: Exception) {
            Log.e(TAG, "❌ 文件分享管道唤起失败: ${e.message}")
        }
    }
}