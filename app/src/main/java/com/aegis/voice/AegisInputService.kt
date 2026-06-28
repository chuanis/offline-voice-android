package com.aegis.voice

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.inputmethodservice.InputMethodService
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.Button
import android.widget.TextView
import android.widget.ImageView
import android.widget.PopupMenu
import android.util.Log
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import androidx.core.content.ContextCompat
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.Executors
import android.os.Handler
import android.os.Looper
import android.os.CountDownTimer
import android.view.animation.Animation
import android.view.animation.ScaleAnimation
import org.json.JSONObject
import kotlin.math.abs
import android.text.SpannableString
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.text.style.StyleSpan
import android.graphics.Typeface
import android.graphics.Color
import java.util.Locale
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicBoolean

import kotlinx.coroutines.launch
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

import org.vosk.Model
import org.vosk.Recognizer
// 🚀 引入我们刚刚解耦的新管家
import com.aegis.voice.keyboard.KeyboardUIHelper

/**
 * =========================================================================================
 * 🏭 Aegis Input Service V13 [The Resurrection] - 核心中央调度台
 *
 * 📢 【开源作者架构揭秘：端侧 AI 的极限压榨】
 * * 1. ⏱️ 500 分钟不崩溃的奥秘：动态音频切片 (Dynamic Audio Slicing)
 * 传统的语音识别往往会被超长音频撑爆内存。本 APP 只要手机有电，就能稳定录音 500 分钟以上不崩溃！
 * 核心秘密在于：我们并非一次性录制整段音频，而是通过 VAD（语音活动检测）和超时强制中断机制，
 * 将你的滔滔不绝自动切分为 5-8 秒的无缝音频碎片（Chunk），再逐个喂给 AI 引擎。
 *
 * 2. 🎭 双核视觉欺骗战术 (Dual-Engine Illusion)
 * 本地大模型（Whisper）计算需要耗费时间，会让用户觉得“卡顿”。
 * 为此，我们引入了轻量级小模型（Vosk）作为“前锋”。小模型瞬间把灰色预览字打在屏幕上，
 * 牢牢稳住人类用户的视线与耐心；与此同时，大模型在后台疯狂计算，最终用高精度的结果替换预览字。
 *
 * 3. 🎛️ 极客级算力仪表盘 (Compute Dashboard)
 * 低端手机的算力无法实现“说出即转写”。为了让用户直观掌握手机性能，我们设计了算力仪表盘：
 * - 【拥堵警告】：录音时，如果后台堆积等待处理的切片大于等于 5 个，屏幕中部会亮起 "Queue: X" 警告，
 * 说明你的手机算力已经被击穿，无法实时跟上你的语速。
 * - 【结算等待】：当你松开麦克风结束录音时，如果右上角显示 "Refining: X"（红色），
 * 意味着录音虽停，但算力仍在后台拼命处理历史切片。
 * - 【完美闭环】：直到右上角出现绿色的 "Done"！这代表音频缓冲区彻底清空，所有文字转写完毕！
 * =========================================================================================
 */
class AegisInputService : InputMethodService() {

    companion object {
        init {
            try {
                // 🚀 唤醒 C++ 兵工厂
                System.loadLibrary("native-lib")
            } catch (e: Throwable) {
                Log.e("Aegis", "Native Lib Error", e)
            }
        }

        // 🎛️ [音频切片核心参数]
        private const val BASE_PEAK_THRESHOLD = 120.0f      // VAD 停顿音量阈值
        private const val STARTUP_SLICE_MS = 5000L          // 启动期强制切片时间 (尽快上字)
        private const val CRUISE_SLICE_MS = 8000L           // 巡航期强制切片时间 (保障连贯性)
        private const val MAX_SILENCE_DURATION_MS = 2500L   // 判定为停顿的最大静音时长
        private const val MIN_CHUNK_DURATION_MS = 2000L     // 切片的最小安全长度

        // 🎙️ [音频流底层设置]
        private const val SAMPLE_RATE = 16000
        private const val OVERLAP_DURATION_MS = 500L        // 切片首尾咬合的重叠时间 (防丢字)
        private const val OVERLAP_SAMPLES = (SAMPLE_RATE * OVERLAP_DURATION_MS / 1000).toInt()
        private const val MAX_BUFFER_SAMPLES = 16000 * 30
        private const val MAX_BUFFER_BYTES = MAX_BUFFER_SAMPLES * 4

        private const val FALLBACK_KEYWORDS = "Hello, punctuation, grammar, distinct speaking, [Silence], [Music], [Applause], English text."
        private val SCENARIO_NAMES = mapOf("GEN" to "General", "LAW" to "Legal", "BIZ" to "Business", "TEC" to "Tech")
        private val SCENARIO_PROMPTS = mapOf(
            "General" to "Hello, punctuation, and standard English grammar.",
            "Legal" to "Plaintiff, Defendant, Affidavit, Jurisdiction, Litigation, Verdict, Objection.",
            "Business" to "Revenue, EBITDA, Q1, Fiscal Year, Stakeholders, Acquisition, Merger, Strategy.",
            "Tech" to "Artificial Intelligence, Machine Learning, SaaS, Blockchain, Cybersecurity, Cloud Computing."
        )
    }

    // --- 录音机与内存缓冲区 ---
    private var recorder: AudioRecord? = null
    private val isRecording = AtomicBoolean(false)
    private val recordingBuffer: ByteBuffer = ByteBuffer.allocateDirect(MAX_BUFFER_BYTES).order(ByteOrder.nativeOrder())
    private var bufferSampleCount = 0
    private var sliceCount = 0

    // --- 线程池集群 (四线程高并发架构) ---
    private val recordingExecutor = Executors.newSingleThreadExecutor()  // 专职抽水 (录音)
    private val inferenceExecutor = Executors.newSingleThreadExecutor()  // 专职算力推理 (Whisper)
    private val refinementExecutor = Executors.newSingleThreadExecutor() // 专职文本整形 (Fuzz 后处理)
    private val copyExecutor = Executors.newSingleThreadExecutor()       // 专职资产装载

    private var isWhisperLoaded = false
    private var isVoskLoaded = false

    // 🚀 【权限解封】：为了让外部的新 KT 管家能访问，移除了 private
    var isCapsLock = false
    private var isDarkMode = false

    // --- 小模型 (Vosk) 句柄 ---
    private var voskModel: Model? = null
    private var voskRecognizer: Recognizer? = null
    private val voskLock = Any()

    // --- 语料库缓存 ---
    private var lastLoadedScenario: String? = null
    private var lastLoadedUserWordsHash: Int = 0
    private var cachedUserHotwords: List<String> = ArrayList()
    private var cachedSystemTiers: Map<Int, List<String>> = emptyMap()
    private var cachedCommonWords: Set<String> = emptySet()
    private var cachedContextWords: List<String> = emptyList()
    private var cachedBlacklist: List<String> = emptyList()

    // --- UI 控件 ---
    private lateinit var tvStatus: TextView
    private lateinit var tvListening: TextView
    private lateinit var tvCenterInfo: TextView
    private lateinit var tvCountdown: TextView
    private lateinit var imgPulseWave: ImageView
    private lateinit var imgMicIcon: ImageView
    private lateinit var tvMicHint: TextView
    private lateinit var btnScenario: View
    private lateinit var tvScenarioText: TextView
    private lateinit var rootView: View

    // 🚀 【权限解封】：供 UI 管家直接控制页面切换
    lateinit var layoutVoiceMain: View
    lateinit var layoutQwerty: View
    lateinit var layoutSymbols: View
    lateinit var layoutNumpad: View

    // --- 生命周期与算力追踪锁 ---
    private var silenceStartTime: Long = 0
    private var isWhisperWorking = false
    private val whisperLock = Any()
    private val whisperTaskCount = AtomicInteger(0) // 核心：切片排队计数器

    private var visualLoopTimer: CountDownTimer? = null

    // 🚀 【权限解封】：键盘管家需要读写最后一个输入的字符，用于自动补空格等逻辑
    var lastChar: Char = ' '

    private var micPressStartTime: Long = 0
    private var isMicLocked = false

    private var currentPromptKey: String = "GEN"
    private var currentPrompt: String = SCENARIO_PROMPTS["General"] ?: ""
    private var previousContext: String = ""
    private var lastCommittedTextFragment: String = ""
    private var totalRecordingSeconds: Long = 0

    private val uiHandler = Handler(Looper.getMainLooper())

    // 🚀 核心纽带：声明打字键盘 UI 管家
    private lateinit var keyboardUIHelper: KeyboardUIHelper

    override fun onCreate() {
        super.onCreate()
        // 唤醒 JNI 安全通道并预加载词库
        val userWordsPath = File(filesDir, "tier1010.dat").absolutePath
        NativeLib.initStaticData(assets, userWordsPath)
        AegisGrammarHelper.preloadAllDictionaries(this)
    }

    override fun onCreateInputView(): View {
        val view = layoutInflater.inflate(R.layout.input_view, null)
        rootView = view

        layoutVoiceMain = view.findViewById(R.id.layoutVoiceMain)
        layoutQwerty = view.findViewById(R.id.layoutQwerty)
        layoutSymbols = view.findViewById(R.id.layoutSymbols)
        layoutNumpad = view.findViewById(R.id.layoutNumpad)

        tvStatus = view.findViewById(R.id.tvStatus)
        tvListening = view.findViewById(R.id.tvListening)
        tvCenterInfo = view.findViewById(R.id.tvCenterInfo)
        tvCountdown = view.findViewById(R.id.tvCountdown)
        imgPulseWave = view.findViewById(R.id.imgPulseWave)
        imgMicIcon = view.findViewById(R.id.imgMicIcon)
        tvMicHint = view.findViewById(R.id.tvMicHint)
        btnScenario = view.findViewById(R.id.btnScenario)
        tvScenarioText = view.findViewById(R.id.tvScenarioText)

        // 🚀 核心挂载：将繁琐的键盘 UI 和按键事件外包给专门的管家
        keyboardUIHelper = KeyboardUIHelper(this, view)
        keyboardUIHelper.setupAllKeys()
        keyboardUIHelper.setupFunctionKeys()

        // 麦克风按键依然由总调度台直接控制，因为涉及引擎点火
        setupVoiceMicKey(view)

        setupScenarioSelector()
        setupDarkModeToggle()

        // 双核引擎同时点火
        initWhisperModel()
        initVoskModel()
        return view
    }

    // ==========================================
    // 🎨 UI 与主题控制模块
    // ==========================================
    private fun setupDarkModeToggle() {
        tvStatus.setOnClickListener {
            toggleTheme()
            it.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
        }
    }

    private fun toggleTheme() {
        isDarkMode = !isDarkMode
        val bgColor = if (isDarkMode) 0xFF121212.toInt() else 0xFFF2F3F5.toInt()
        val keyBgRes = if (isDarkMode) R.drawable.bg_key_dark else R.drawable.bg_key_white
        val textColor = if (isDarkMode) 0xFFEEEEEE.toInt() else 0xFF000000.toInt()
        val hintColor = if (isDarkMode) 0xFFAAAAAA.toInt() else 0xFF888888.toInt()
        val qwertyBgColor = if (isDarkMode) 0xFF1E1E1E.toInt() else 0xFFF2F3F5.toInt()

        layoutVoiceMain.setBackgroundColor(bgColor)
        layoutQwerty.setBackgroundColor(qwertyBgColor)
        layoutSymbols.setBackgroundColor(qwertyBgColor)
        layoutNumpad.setBackgroundColor(qwertyBgColor)

        updateChildViews(rootView as ViewGroup, keyBgRes, textColor)
        tvStatus.setTextColor(if(isDarkMode) 0xFFAAAAAA.toInt() else 0xFF6200EE.toInt())
        tvMicHint.setTextColor(hintColor)
        tvScenarioText.setTextColor(textColor)
        tvListening.setTextColor(0xFFFF4444.toInt())
        tvCenterInfo.setTextColor(0xFFFF8C00.toInt())
        tvCountdown.setTextColor(if(isDarkMode) 0xFFBB86FC.toInt() else 0xFF6200EE.toInt())

        val iconColor = if (isDarkMode) 0xFFFFFFFF.toInt() else 0xFF000000.toInt()
        applyIconTint(rootView, iconColor)
        imgMicIcon.setColorFilter(if(isRecording.get()) 0xFFFF4444.toInt() else 0xFF6200EE.toInt())

        if (::imgPulseWave.isInitialized) {
            imgPulseWave.setColorFilter(if(isDarkMode) 0xFFFFFFFF.toInt() else 0xFF6200EE.toInt())
        }
    }

    private fun updateChildViews(viewGroup: ViewGroup, bgRes: Int, textColor: Int) {
        for (i in 0 until viewGroup.childCount) {
            val child = viewGroup.getChildAt(i)
            if (child is Button) {
                child.setBackgroundResource(bgRes)
                child.setTextColor(textColor)
            } else if (child is TextView && child.id != R.id.tvStatus && child.id != R.id.tvMicHint && child.id != R.id.tvListening && child.id != R.id.tvCenterInfo) {
                if (child.parent is ViewGroup && (child.parent as View).isClickable) {
                    child.setTextColor(textColor)
                    (child.parent as View).setBackgroundResource(bgRes)
                }
            } else if (child is ImageView && child.id != R.id.imgPulseWave) {
                if (child.parent is ViewGroup && (child.parent as View).isClickable) {
                    (child.parent as View).setBackgroundResource(bgRes)
                }
            } else if (child is ViewGroup) {
                updateChildViews(child, bgRes, textColor)
            }
        }
    }

    private fun applyIconTint(view: View, color: Int) {
        if (view is ImageView && view.id != R.id.imgMicIcon && view.id != R.id.imgPulseWave) {
            view.setColorFilter(color)
        } else if (view is ViewGroup) {
            for (i in 0 until view.childCount) {
                applyIconTint(view.getChildAt(i), color)
            }
        }
    }

    // ==========================================
    // ⚙️ 业务场景控制模块 (词库热切换)
    // ==========================================
    private fun setupScenarioSelector() {
        btnScenario.setOnClickListener { view ->
            val popup = PopupMenu(this, view)
            SCENARIO_NAMES.forEach { (_, fullName) ->
                popup.menu.add(fullName)
            }
            popup.menu.add("________________")
            popup.menu.add("⚙️ Manage Hotwords")

            popup.setOnMenuItemClickListener { item ->
                val title = item.title.toString()
                if (title.contains("Manage Hotwords")) {
                    val intent = Intent(this, UserDictionaryActivity::class.java)
                    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    startActivity(intent)
                    return@setOnMenuItemClickListener true
                }
                if (title.contains("___")) return@setOnMenuItemClickListener false

                val selectedCode = SCENARIO_NAMES.entries.find { it.value == title }?.key ?: "GEN"
                tvScenarioText.text = selectedCode
                currentPromptKey = selectedCode
                previousContext = ""

                FuzzyMatchUtils.reset()
                updateVoskGrammar(selectedCode, force = true)
                view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                true
            }
            popup.show()
        }
    }

    private fun updateVoskGrammar(scenarioCode: String, force: Boolean = true) {
        if (!isVoskLoaded || voskRecognizer == null || isRecording.get()) {
            if (isRecording.get()) Log.w("Aegis", "Skipped grammar update: Recording in progress")
            return
        }

        CoroutineScope(Dispatchers.Main).launch {
            try {
                // 安全获取解密后的词库
                val userWords = withContext(Dispatchers.IO) {
                    AegisGrammarHelper.getUserCustomWords(this@AegisInputService)
                }
                cachedUserHotwords = userWords

                if (cachedSystemTiers.isEmpty() || lastLoadedScenario != scenarioCode) {
                    withContext(Dispatchers.IO) {
                        loadSystemTiers(scenarioCode)
                        cachedCommonWords = AegisGrammarHelper.getCommonWhitelist(this@AegisInputService)
                        cachedBlacklist = AegisGrammarHelper.getHallucinationBlacklist(this@AegisInputService)
                    }
                }

                // 强制同步本地热词到内存
                withContext(Dispatchers.IO) {
                    try {
                        val content = userWords.joinToString("\n")
                        val path = File(filesDir, "tier1010.dat").absolutePath
                        NativeLib.saveUserHotwords(path, content)
                    } catch (e: Exception) {
                        Log.e("Aegis", "Failed to save tier1010.dat", e)
                    }
                }

                val currentHash = userWords.hashCode()
                if (!force && lastLoadedScenario == scenarioCode && lastLoadedUserWordsHash == currentHash) {
                    return@launch
                }

                if (isRecording.get()) return@launch

                tvStatus.text = "Loading..."

                // 仅给 Vosk 生成轻量级基础词法糖
                val grammarJson = AegisGrammarHelper.buildGrammarJson(this@AegisInputService, scenarioCode)
                val basePrompt = SCENARIO_PROMPTS[SCENARIO_NAMES[scenarioCode]] ?: ""
                currentPrompt = basePrompt

                if (!isRecording.get()) {
                    synchronized(voskLock) {
                        if (!isRecording.get()) {
                            voskRecognizer?.setGrammar(grammarJson)
                            lastLoadedScenario = scenarioCode
                            lastLoadedUserWordsHash = currentHash
                        }
                    }
                    if (!isRecording.get()) {
                        val statusSuffix = if (userWords.isNotEmpty()) " + CUS" else ""
                        tvStatus.text = "Aegis AI ($scenarioCode$statusSuffix)"
                    }
                }
            } catch (e: Exception) {
                Log.e("Aegis", "Grammar Update Failed", e)
                if (!isRecording.get()) tvStatus.text = "Error"
            }
        }
    }

    private fun loadSystemTiers(scenarioCode: String) {
        val tierMap = HashMap<Int, List<String>>()
        val scenarioFolder = scenarioCode.lowercase(Locale.getDefault())
        for (i in 1..5) {
            try {
                val path = "lexicons/$scenarioFolder/tier$i.dat"
                val wordsArray = NativeLib.getDecryptedAsset(assets, path)
                val validWords = wordsArray.map { it.trim() }.filter { it.isNotBlank() }.distinct()
                tierMap[i] = validWords
            } catch (e: Exception) {
                Log.w("Aegis", "Tier $i not found or empty for $scenarioFolder: ${e.message}")
            }
        }
        cachedSystemTiers = tierMap

        try {
            val contextPath = "lexicons/$scenarioFolder/context.dat"
            val cWordsArray = NativeLib.getDecryptedAsset(assets, contextPath)
            cachedContextWords = cWordsArray.map { it.trim() }.filter { it.isNotBlank() }
        } catch (e: Exception) {
            cachedContextWords = emptyList()
        }
    }

    // ==========================================
    // 🧠 引擎点火挂载模块 (Model Loading)
    // ==========================================
    private fun initWhisperModel() {
        inferenceExecutor.execute {
            synchronized(whisperLock) {
                // 优先挂载 NPU/GPU (device = 1)
                var success = NativeLib.loadModel(this.applicationContext, assets, "aegis.core.engine", 1)
                if (!success) {
                    // 降级兜底挂载 CPU (device = 0)
                    success = NativeLib.loadModel(this.applicationContext, assets, "aegis.core.engine", 0)
                }
                isWhisperLoaded = success
                if (success) {
                    NativeLib.setWhisperParams(5, 2.8f)
                }
            }
            updateStatusUI()
        }
    }

    private fun initVoskModel() {
        copyExecutor.execute {
            try {
                val voskDestDir = File(filesDir, "aegis_matrix")
                if (!voskDestDir.exists() || (voskDestDir.list()?.isEmpty() ?: true)) {
                    Handler(Looper.getMainLooper()).post { tvStatus.text = "Initializing Matrix..." }
                    copyAssetFolder("aegis_matrix", voskDestDir.absolutePath)
                }
                voskModel = Model(voskDestDir.absolutePath)
                voskRecognizer = Recognizer(voskModel, 16000.0f)
                isVoskLoaded = true
                Handler(Looper.getMainLooper()).post { updateVoskGrammar("GEN", force = true) }
                updateStatusUI()
            } catch (e: Throwable) {
                Log.e("Aegis", "Matrix Init Failed", e)
            }
        }
    }

    private fun copyAssetFolder(srcName: String, dstPath: String) {
        try {
            val fileList = assets.list(srcName) ?: return
            if (fileList.isEmpty()) {
                val file = File(dstPath)
                file.parentFile?.mkdirs()
                copyAssetFile(srcName, file)
            } else {
                val file = File(dstPath)
                file.mkdirs()
                for (filename in fileList) {
                    if (filename.contains("ggml")) continue
                    copyAssetFolder("$srcName/$filename", "$dstPath/$filename")
                }
            }
        } catch (e: Exception) {}
    }

    private fun copyAssetFile(srcName: String, dstFile: File) {
        try {
            assets.open(srcName).use { input -> FileOutputStream(dstFile).use { output -> input.copyTo(output) } }
        } catch (e: Exception) {}
    }

    private fun updateStatusUI() {
        Handler(Looper.getMainLooper()).post {
            if (isWhisperLoaded && isVoskLoaded) {
                val userWords = cachedUserHotwords
                val statusSuffix = if (userWords.isNotEmpty()) " + CUS" else ""
                tvStatus.text = "Aegis AI ($currentPromptKey$statusSuffix)"
                tvStatus.setTextColor(if(isDarkMode) 0xFFAAAAAA.toInt() else 0xFF6200EE.toInt())
            } else {
                tvStatus.text = "Init Error"
            }
        }
    }

    // ==========================================
    // 🎛️ 算力仪表盘与波形监控 UI (Dashboard)
    // ==========================================
    private fun startRecordingTimer() {
        Handler(Looper.getMainLooper()).post {
            tvStatus.visibility = View.GONE
            tvListening.visibility = View.VISIBLE
            tvCountdown.visibility = View.VISIBLE
            tvCountdown.setTextColor(0xFF6200EE.toInt())

            visualLoopTimer?.cancel()
            visualLoopTimer = object : CountDownTimer(5000, 1000) {
                override fun onTick(millisUntilFinished: Long) {
                    if (!isRecording.get()) return

                    totalRecordingSeconds++
                    val currentQueue = whisperTaskCount.get()

                    val cycleTime = totalRecordingSeconds % 120
                    // 🔴 [算力拥堵警告]：如果后台处理切片堆积超过 5 个，亮起黄色警告灯
                    if (cycleTime < 5 && currentQueue > 5) {
                        tvCenterInfo.text = "Queue: $currentQueue"
                        tvCenterInfo.visibility = View.VISIBLE
                    } else {
                        tvCenterInfo.visibility = View.GONE
                    }

                    val sec = (millisUntilFinished / 1000) + 1
                    tvCountdown.text = "Slice: ${sec}s"

                    if (totalRecordingSeconds == 60L) {
                        switchToUpperLowPowerMode()
                    }
                }
                override fun onFinish() {
                    if (isRecording.get()) this.start()
                }
            }.start()
        }
    }

    private val lowPowerPulseRunnable = object : Runnable {
        private var scale = 0.6f
        private var direction = 1
        override fun run() {
            if (!isRecording.get()) return
            scale += (0.01f * direction)
            if (scale >= 1.0f) { scale = 1.0f; direction = -1 }
            if (scale <= 0.6f) { scale = 0.6f; direction = 1 }

            if (::imgPulseWave.isInitialized) {
                imgPulseWave.scaleX = scale
                imgPulseWave.scaleY = scale
            }
            uiHandler.postDelayed(this, 100)
        }
    }

    private fun switchToUpperLowPowerMode() {
        Handler(Looper.getMainLooper()).post {
            if (::imgPulseWave.isInitialized) {
                imgPulseWave.clearAnimation()
                uiHandler.removeCallbacks(lowPowerPulseRunnable)
                uiHandler.post(lowPowerPulseRunnable)
            }
        }
    }

    private fun updateQueueUI() {
        if (isRecording.get()) return
        Handler(Looper.getMainLooper()).post {
            val count = whisperTaskCount.get()
            // 🔴 [结算等待状态]：录音已结束，但后台切片未处理完
            if (count > 0) {
                tvStatus.visibility = View.VISIBLE
                tvListening.visibility = View.GONE
                tvCenterInfo.visibility = View.GONE
                tvCountdown.visibility = View.VISIBLE
                tvCountdown.text = "Refining: $count"
                tvCountdown.setTextColor(0xFFFF4444.toInt())
            } else {
                // ✅ [完美闭环]：所有切片出字完毕，算力释放
                tvCountdown.text = "Done"
                tvCountdown.setTextColor(0xFF00C853.toInt())
                visualLoopTimer?.cancel()
                Handler(Looper.getMainLooper()).postDelayed({
                    if (whisperTaskCount.get() == 0 && !isRecording.get()) {
                        tvCountdown.visibility = View.GONE
                    }
                }, 1000)
            }
        }
    }

    // ==========================================
    // 🎙️ 麦克风核心控制器 (录音点火/熄火)
    // ==========================================
    private fun setupVoiceMicKey(view: View) {
        val btnMic = view.findViewById<View>(R.id.btnMic)
        btnMic.setOnTouchListener { v, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    v.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                    if (!isRecording.get()) {
                        micPressStartTime = System.currentTimeMillis()
                        if (checkPermission()) {
                            if (isWhisperLoaded) startRecording() else tvStatus.text = "Engine Error"
                        } else {
                            tvStatus.text = "Permission Needed"
                            tvStatus.setTextColor(0xFFFF0000.toInt())
                        }
                    }
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    val pressDuration = System.currentTimeMillis() - micPressStartTime
                    if (isRecording.get()) {
                        if (isMicLocked) {
                            if (event.action == MotionEvent.ACTION_UP) {
                                stopRecordingAndTranscribe()
                                isMicLocked = false
                            }
                        } else {
                            // 短按进入锁定模式，长按松手结束
                            if (pressDuration < 400) {
                                isMicLocked = true
                                updateMicUIState(recording = true, locked = true)
                            } else {
                                stopRecordingAndTranscribe()
                            }
                        }
                    }
                    true
                }
                else -> false
            }
        }
    }

    private fun updateMicUIState(recording: Boolean = true, locked: Boolean = false) {
        Handler(Looper.getMainLooper()).post {
            if (recording) {
                imgMicIcon.setColorFilter(0xFFFF4444.toInt())
                if (locked) {
                    tvMicHint.text = "Tap to Stop"
                    tvMicHint.setTextColor(0xFFFF4444.toInt())
                } else {
                    tvMicHint.text = "Release / Tap"
                    tvMicHint.setTextColor(if(isDarkMode) 0xFFAAAAAA.toInt() else 0xFF888888.toInt())
                }
            } else {
                tvStatus.visibility = View.VISIBLE
                tvListening.visibility = View.GONE
                tvCenterInfo.visibility = View.GONE

                val userWords = cachedUserHotwords
                val statusSuffix = if (userWords.isNotEmpty()) " + CUS" else ""
                tvStatus.text = "Aegis AI ($currentPromptKey$statusSuffix)"
                tvStatus.setTextColor(if(isDarkMode) 0xFFAAAAAA.toInt() else 0xFF6200EE.toInt())

                imgMicIcon.setColorFilter(0xFF6200EE.toInt())
                tvMicHint.text = "Hold / Tap"
                tvMicHint.setTextColor(if(isDarkMode) 0xFFAAAAAA.toInt() else 0xFF888888.toInt())
            }
        }
    }

    // ==========================================
    // ✂️ 音频抽水机与切片算法 (Audio Slicer)
    // ==========================================
    private fun startRecording() {
        if (!isWhisperLoaded || !isVoskLoaded) return
        try {
            val minBufferSize = AudioRecord.getMinBufferSize(16000, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
            recorder = AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION, 16000, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, minBufferSize * 2)
            if (recorder?.state != AudioRecord.STATE_INITIALIZED) return

            isRecording.set(true)
            recordingBuffer.clear()
            bufferSampleCount = 0
            sliceCount = 0

            synchronized(voskLock) { voskRecognizer?.reset() }

            silenceStartTime = 0
            isWhisperWorking = false
            whisperTaskCount.set(0)
            lastChar = ' '
            previousContext = ""
            lastCommittedTextFragment = ""
            totalRecordingSeconds = 0

            Handler(Looper.getMainLooper()).post {
                if (::imgPulseWave.isInitialized) {
                    imgPulseWave.visibility = View.VISIBLE
                    val pulseAnim = ScaleAnimation(
                        0.6f, 1.0f, 0.6f, 1.0f,
                        Animation.RELATIVE_TO_SELF, 0.5f,
                        Animation.RELATIVE_TO_SELF, 0.5f
                    )
                    pulseAnim.duration = 1000
                    pulseAnim.repeatMode = Animation.REVERSE
                    pulseAnim.repeatCount = Animation.INFINITE
                    imgPulseWave.startAnimation(pulseAnim)
                }
            }

            startRecordingTimer()
            updateMicUIState(recording = true, locked = false)
            recorder?.startRecording()

            // 专门跑在 IO 线程的“抽水机”
            recordingExecutor.execute {
                val buffer = ShortArray(1024)
                val recordingStartTime = System.currentTimeMillis()

                while (isRecording.get()) {
                    val read = recorder?.read(buffer, 0, buffer.size) ?: 0
                    if (read > 0) {
                        // 屏蔽前 500ms 的爆音
                        if (System.currentTimeMillis() - recordingStartTime < 500) {
                            continue
                        }

                        val peak = calculatePeakAmplitude(buffer, read)

                        // 🔍 语音活动检测 (VAD)
                        if (peak < BASE_PEAK_THRESHOLD) {
                            if (silenceStartTime == 0L) silenceStartTime = System.currentTimeMillis()
                        } else {
                            silenceStartTime = 0
                        }

                        val currentSegmentDuration = (bufferSampleCount.toFloat() / 16000.0f) * 1000
                        val silenceDuration = if (silenceStartTime > 0) System.currentTimeMillis() - silenceStartTime else 0

                        // 动态设置超时上限
                        val dynamicMaxDuration = if (sliceCount < 2) STARTUP_SLICE_MS else CRUISE_SLICE_MS

                        // 🔪 核心切片判定：满足静音时长 OR 强制超时
                        val isVADTrigger = (silenceDuration > MAX_SILENCE_DURATION_MS && currentSegmentDuration > MIN_CHUNK_DURATION_MS)
                        val isTimeoutTrigger = (currentSegmentDuration >= dynamicMaxDuration)

                        if (isVADTrigger || isTimeoutTrigger) {
                            processCurrentChunk() // 提交当前切片
                            silenceStartTime = 0
                            sliceCount++
                        }

                        // 🎭 [双核视觉欺骗] 小模型实时出字，稳住人类视线
                        if (isVoskLoaded) {
                            var textToShow = ""
                            synchronized(voskLock) {
                                if (voskRecognizer != null) {
                                    if (voskRecognizer!!.acceptWaveForm(buffer, read)) {
                                        val json = voskRecognizer!!.result
                                        textToShow = parseVoskResult(json, false)
                                    } else {
                                        val json = voskRecognizer!!.partialResult
                                        textToShow = parseVoskResult(json, true)
                                    }
                                }
                            }

                            if (textToShow.isNotEmpty()) {
                                Handler(Looper.getMainLooper()).post {
                                    val styledText = SpannableString(textToShow)
                                    val previewColor = if(isDarkMode) Color.DKGRAY else Color.LTGRAY
                                    // 灰色斜体字预览，告诉用户正在听
                                    styledText.setSpan(ForegroundColorSpan(previewColor), 0, styledText.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                                    styledText.setSpan(StyleSpan(Typeface.ITALIC), 0, styledText.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                                    currentInputConnection?.setComposingText(styledText, 1)
                                }
                            }
                        }

                        // 把数据压入缓冲区
                        for (i in 0 until read) {
                            if (bufferSampleCount < MAX_BUFFER_SAMPLES) {
                                recordingBuffer.putFloat(buffer[i] / 32768.0f)
                                bufferSampleCount++
                            }
                        }
                    }
                }
            }
        } catch (e: Exception) { Log.e("Aegis", "Rec Error", e) }
    }

    private fun calculatePeakAmplitude(buffer: ShortArray, read: Int): Float {
        var maxAmp = 0
        for (i in 0 until read) {
            val amp = kotlin.math.abs(buffer[i].toInt())
            if (amp > maxAmp) maxAmp = amp
        }
        return maxAmp.toFloat()
    }

    private fun mergeText(previous: String, current: String): Pair<Int, String> {
        if (previous.isBlank()) return 0 to current
        if (current.isBlank()) return 0 to ""

        val smartMerged = FuzzyMatchUtils.smartMerge(previous, current)
        if (smartMerged != current) {
            return 0 to smartMerged
        }

        val checkLen = kotlin.math.min(50, kotlin.math.min(previous.length, current.length))
        val prevTail = previous.takeLast(checkLen)
        val currHead = current.take(checkLen)

        for (i in checkLen downTo 3) {
            val pSub = prevTail.takeLast(i).lowercase().trim()
            val cSub = currHead.take(i).lowercase().trim()
            if (pSub == cSub) {
                return 0 to current.substring(i).trimStart()
            }
        }

        val probeLen = kotlin.math.min(12, current.length)
        if (probeLen > 3) {
            val probe = current.take(probeLen).lowercase()
            val searchWindowSize = kotlin.math.min(40, previous.length)
            val searchWindow = previous.takeLast(searchWindowSize).lowercase()

            val matchIndex = searchWindow.lastIndexOf(probe)
            if (matchIndex != -1) {
                val charsToDelete = searchWindow.length - matchIndex
                return charsToDelete to current
            }
        }
        return 0 to current
    }

    // ==========================================
    // 🧠 推理与流水线工厂 (Whisper Backend)
    // ==========================================
    private fun processCurrentChunk() {
        if (bufferSampleCount == 0) return
        if (bufferSampleCount < 16000 * 0.5) {
            // 太短的杂音直接丢弃
            recordingBuffer.clear()
            bufferSampleCount = 0
            synchronized(voskLock) { voskRecognizer?.reset() }
            return
        }

        isWhisperWorking = true
        whisperTaskCount.incrementAndGet() // 加入任务队列

        val samplesToProcess = bufferSampleCount
        val processingBuffer = ByteBuffer.allocateDirect(samplesToProcess * 4).order(ByteOrder.nativeOrder())

        recordingBuffer.flip()
        processingBuffer.put(recordingBuffer)

        recordingBuffer.clear()
        bufferSampleCount = 0

        // 🔗 [防丢字重叠补丁] 将当前切片的最后 0.5 秒，保留给下一次切片做开头
        if (samplesToProcess >= OVERLAP_SAMPLES) {
            val tailSizeInBytes = OVERLAP_SAMPLES * 4
            val tailData = ByteArray(tailSizeInBytes)
            val currentPos = processingBuffer.position()
            processingBuffer.position(currentPos - tailSizeInBytes)
            processingBuffer.get(tailData)
            processingBuffer.position(currentPos)
            recordingBuffer.put(tailData)
            bufferSampleCount = OVERLAP_SAMPLES
        }

        processingBuffer.flip()

        var voskHint = ""
        synchronized(voskLock) {
            val json = voskRecognizer?.finalResult ?: ""
            voskHint = parseVoskResult(json, false)
            voskRecognizer?.reset()
        }

        inferenceExecutor.execute {
            var rawText = ""
            try {
                synchronized(whisperLock) {
                    try {
                        if (isWhisperLoaded) {
                            // 动态组装 Context，结合小模型的识别结果，提前调取大模型神经元
                            val dynamicHotwords = generateDynamicPrompt(voskHint)
                            val finalPrompt = if (previousContext.isNotEmpty()) {
                                "$currentPrompt Key terms: $dynamicHotwords. Previous context: $previousContext"
                            } else {
                                "$currentPrompt Key terms: $dynamicHotwords"
                            }
                            // 🚀 [终极算力] Whisper C++ 引擎推理
                            rawText = NativeLib.transcribeAudio(processingBuffer, samplesToProcess, finalPrompt)
                        }
                    } catch (e: Exception) {}
                }
            } catch (e: Exception) {
                whisperTaskCount.decrementAndGet()
                updateQueueUI()
                return@execute
            }

            // [文本后处理流水线]
            refinementExecutor.execute {
                try {
                    var cleanText = cleanWhisperOutput(rawText)

                    // 1. 调用 Titan 纠错引警
                    cleanText = FuzzyMatchUtils.correct(
                        text = cleanText,
                        vipHotwords = cachedUserHotwords,
                        commonWords = cachedCommonWords,
                        contextWords = cachedContextWords,
                        systemTiers = cachedSystemTiers,
                        hallucinationBlacklist = cachedBlacklist
                    )

                    var deleteCount = 0
                    var textToCommit = cleanText

                    // 2. 跨切片重影拉链拼接
                    if (lastCommittedTextFragment.isNotEmpty() && cleanText.isNotEmpty()) {
                        val mergeResult = mergeText(lastCommittedTextFragment, cleanText)
                        deleteCount = mergeResult.first
                        textToCommit = mergeResult.second
                    }

                    // 3. 切片内消除结巴
                    textToCommit = FuzzyMatchUtils.fixStutterAndCamelCase(textToCommit)

                    // 4. UI 线程上屏结算
                    Handler(Looper.getMainLooper()).post {
                        if (textToCommit.isNotEmpty()) {
                            var finalText = textToCommit
                            val isPunctuation = "^[.,?!;:]".toRegex().containsMatchIn(finalText)

                            // 智能补空格
                            if (lastChar != ' ' && lastChar != '\n' && finalText.isNotEmpty() && Character.isLetterOrDigit(finalText[0]) && !isPunctuation && deleteCount == 0) {
                                finalText = " " + finalText
                            }

                            if (finalText.isNotBlank()) {
                                if (deleteCount > 0) {
                                    currentInputConnection?.deleteSurroundingText(deleteCount, 0)
                                }
                                currentInputConnection?.commitText(finalText, 1) // 正式打字上屏

                                lastChar = finalText.last()
                                previousContext = if (finalText.length > 100) finalText.takeLast(100) else finalText
                                lastCommittedTextFragment = cleanText
                            }
                        } else {
                            currentInputConnection?.setComposingText("", 1)
                        }
                        isWhisperWorking = false
                    }
                } finally {
                    whisperTaskCount.decrementAndGet() // 任务完成，出列
                    updateQueueUI()
                }
            }
        }
    }

    private fun generateDynamicPrompt(voskHint: String): String {
        val maxLen = 400
        val sb = StringBuilder()
        val hitWords = ArrayList<String>()

        if (voskHint.isNotEmpty()) {
            val lowerHint = voskHint.lowercase()
            for (word in cachedUserHotwords) {
                if (lowerHint.contains(word.lowercase())) {
                    hitWords.add(word)
                }
            }
        }

        for (word in hitWords) {
            if (sb.length + word.length + 2 > maxLen) break
            if (sb.isNotEmpty()) sb.append(", ")
            sb.append(word)
        }

        if (sb.length < maxLen) {
            val recentWords = cachedUserHotwords.reversed()
            for (word in recentWords) {
                if (hitWords.contains(word)) continue
                if (sb.length + word.length + 2 > maxLen) break
                if (sb.isNotEmpty()) sb.append(", ")
                sb.append(word)
            }
        }

        if (sb.length < maxLen) {
            for (word in cachedContextWords) {
                if (sb.length + word.length + 2 > maxLen) break
                if (sb.isNotEmpty()) sb.append(", ")
                sb.append(word)
            }
        }

        if (sb.isEmpty()) return FALLBACK_KEYWORDS
        return sb.toString()
    }

    // ==========================================
    // 🛑 结算与停机逻辑
    // ==========================================
    private fun stopRecordingAndTranscribe() {
        isRecording.set(false)
        try { recorder?.stop(); recorder?.release() } catch (e: Exception) {}
        recorder = null
        visualLoopTimer?.cancel()
        uiHandler.removeCallbacks(lowPowerPulseRunnable)
        updateMicUIState(recording = false)

        Handler(Looper.getMainLooper()).post {
            if (::imgPulseWave.isInitialized) {
                imgPulseWave.clearAnimation()
                imgPulseWave.visibility = View.INVISIBLE
            }
        }

        // 把缓冲区剩下的最后一段音频送去转写
        if (bufferSampleCount > 16000 * 0.5) {
            processCurrentChunk()
        } else {
            currentInputConnection?.setComposingText("", 0)
            recordingBuffer.clear()
            bufferSampleCount = 0
            isWhisperWorking = false
        }
        updateQueueUI()
    }

    private fun parseVoskResult(json: String, isPartial: Boolean): String {
        try {
            val jsonObj = JSONObject(json)
            return if (isPartial) jsonObj.optString("partial", "") else jsonObj.optString("text", "")
        } catch (e: Exception) { return "" }
    }

    private fun cleanWhisperOutput(text: String): String {
        if (text.isBlank()) return ""
        var clean = text.trim()
        clean = clean.replace(Regex("\\(.*?\\)"), "").replace(Regex("\\[.*?\\]"), "").replace(Regex("\\*.*?\\*"), "")
        clean = clean.replace(Regex(">>+"), "").replace(Regex("--+"), "")

        val trash = listOf("[BLANK_AUDIO]", "Subtitle", "Amara.org", "Whisper")
        for (h in trash) {
            if (clean.contains(h, ignoreCase = true)) clean = clean.replace(h, "", ignoreCase = true)
        }

        clean = clean.replace(" .", ".").replace(" ,", ",").replace(" ?", "?")
        clean = clean.trim()

        if (clean.isBlank()) return ""

        if (lastChar == '.' || lastChar == '?' || lastChar == '!') {
            clean = capitalizeFirstLetter(clean)
        }

        return clean.trim()
    }

    private fun capitalizeFirstLetter(text: String): String {
        if (text.isEmpty()) return text
        return text.substring(0, 1).uppercase(Locale.getDefault()) + text.substring(1)
    }

    // ==========================================
    // 🔌 Android 生命周期管理
    // ==========================================
    override fun onStartInputView(info: EditorInfo?, restarting: Boolean) {
        super.onStartInputView(info, restarting)
        // 🚀 交给 UI 管家去处理默认展示的页面
        if (this::layoutVoiceMain.isInitialized) keyboardUIHelper.showPage(1)
        isCapsLock = false
        lastChar = ' '
        updateVoskGrammar(currentPromptKey, force = false)
        if (isDarkMode) {
            isDarkMode = !isDarkMode
            toggleTheme()
        }
    }

    override fun onEvaluateFullscreenMode(): Boolean = false

    override fun onComputeInsets(outInsets: InputMethodService.Insets) {
        super.onComputeInsets(outInsets)
        if (::rootView.isInitialized) {
            outInsets.contentTopInsets = outInsets.visibleTopInsets
            outInsets.touchableInsets = InputMethodService.Insets.TOUCHABLE_INSETS_VISIBLE
        }
    }

    private fun checkPermission(): Boolean = ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    override fun onDestroy() {
        super.onDestroy()
        NativeLib.freeModel()
        voskModel?.close()
    }
}