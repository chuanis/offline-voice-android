package com.aegis.voice

import android.app.AlertDialog
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.ProgressBar
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.io.File

/**
 * =========================================================================================
 * 🎙️ Aegis Studio V2.0 - 离线大算力转写工作站 (Offline Transcription Studio)
 * * 【架构声明】：
 * 本组件旨在解决“超长会议录音/视频提取音频的本地离线转写”难题。
 * 1. 跨维打击：不仅支持常规录音，还能直接解剖 MP4 视频，抽出音频轨进行处理。
 * 2. 算力保活机制：由于各大手机厂商极其残暴的杀后台省电策略，本组件在任务点火期间，
 * 强制激活系统级屏幕常亮锁 (FLAG_KEEP_SCREEN_ON)，防止 NPU/GPU 运算中途被物理断电。
 * 3. 异步流水线：通过协程驱动 (Init -> Demux -> Inference)，全程保持 UI 线程流畅响应。
 * =========================================================================================
 */
class StudioActivity : AppCompatActivity() {

    private val TAG = "AegisStudio"

    // --- UI 控制面板 ---
    private lateinit var btnSelectFile: Button
    private lateinit var tvEmptyState: TextView
    private lateinit var layoutPipeline: View

    private lateinit var tvStep2: TextView
    private lateinit var progressDecode: ProgressBar
    private lateinit var tvStep3: TextView
    private lateinit var tvCountdown: TextView

    // 🛡️ [架构优化] 异步任务安全锁：管理底层推理的生命周期
    private var transcriptionJob: Job? = null

    // ==========================================
    // 📂 系统级文件选择器 (SAF 协议)
    // 拦截返回的 URI 后直接进入场景挂载流程
    // ==========================================
    private val filePickerLauncher = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri != null) {
            showScenarioSelectionDialog(uri)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_studio)

        btnSelectFile = findViewById(R.id.btn_select_file)
        tvEmptyState = findViewById(R.id.tv_empty_state)
        layoutPipeline = findViewById(R.id.layout_pipeline)

        tvStep2 = findViewById(R.id.tv_step2_status)
        progressDecode = findViewById(R.id.progress_decoding)
        tvStep3 = findViewById(R.id.tv_step3_status)
        tvCountdown = findViewById(R.id.tv_slices_countdown)

        // 绑定点火按钮，开启文件探测
        btnSelectFile.setOnClickListener {
            // 兼容模式全开：允许吞噬主流音视频格式
            filePickerLauncher.launch(arrayOf("audio/*", "video/mp4", "audio/mp4", "audio/x-m4a", "audio/wav"))
        }
    }

    /**
     * 🛡️ [核心保命机制] 彻底清理战场
     * 防止用户在疯狂计算中途直接划掉后台，导致底层 C++ 引擎内存溢出或变成僵尸进程。
     */
    override fun onDestroy() {
        super.onDestroy()
        transcriptionJob?.cancel() // 物理掐断协程生命流
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) // 撤销常亮强制令
        Log.i(TAG, "🛡️ Studio Destruct Protocol Executed. Hardware resources freed.")
    }

    // ==========================================
    // 🎛️ 场景锁定与词库挂载网关
    // ==========================================
    private fun showScenarioSelectionDialog(uri: Uri) {
        val scenarios = arrayOf("🌍 通用语境 (General)", "⚖️ 法律文书 (Legal)", "💼 商业会议 (Business)", "💻 科技极客 (Tech)")
        val scenarioCodes = arrayOf("GEN", "LAW", "BIZ", "TEC")

        AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle("锁定目标专业词库")
            .setItems(scenarios) { _, which ->
                // 没有废话，直接启动硬核推理流水线！
                startOfflineTranscriptionTask(uri, scenarioCodes[which])
            }
            .setCancelable(false) // 物理锁死弹窗，必须做出选择
            .show()
    }

    // ==========================================
    // 🚀 三段式推理流水线 (The Titan Pipeline)
    // ==========================================
    private fun startOfflineTranscriptionTask(uri: Uri, scenarioCode: String) {

        // 🚀 终极杀招：申请屏幕常亮免死金牌，死保底层算力引擎不被系统断电
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        // UI 状态机：切入战斗模式
        btnSelectFile.isEnabled = false
        btnSelectFile.text = "算力全开处理中..."
        btnSelectFile.setBackgroundColor(android.graphics.Color.DKGRAY)

        tvEmptyState.visibility = View.GONE
        layoutPipeline.visibility = View.VISIBLE

        // 🛡️ [架构优化] 使用 lifecycleScope 绑定生命周期，页面一旦销毁，任务绝对安全中止
        transcriptionJob = lifecycleScope.launch(Dispatchers.Main) {
            try {
                // ==================================
                // 阶段 0: 唤醒 C++ 与加密兵工厂
                // ==================================
                val initSuccess = StudioConsole002.initialize(this@StudioActivity, scenarioCode)
                if (!initSuccess) throw Exception("底层 AI 引擎点火失败 (Engine Ignition Failed)")

                // ==================================
                // 阶段 2: 音视频流物理重构 (解复用与解压缩)
                // ==================================
                tvStep2.text = "[2/3] 正在解剖重组音频数据流..."
                tvStep2.setTextColor(android.graphics.Color.parseColor("#BB86FC"))
                progressDecode.progress = 0

                // 挂起协程，等待解剖刀完全拆解完毕
                val pcmFile = AudioDecoderUtils.decodeUriToPcmFile(this@StudioActivity, uri) { progress ->
                    progressDecode.progress = progress
                }

                if (pcmFile == null || !pcmFile.exists()) throw Exception("流媒体解构重采样失败 (Audio Decoding Failed)")

                tvStep2.text = "[2/3] 数据流解剖完毕，格式锁定为 16kHz PCM."
                tvStep2.setTextColor(android.graphics.Color.parseColor("#00C853"))

                // ==================================
                // 阶段 3: NPU/GPU 狂暴推理阶段
                // ==================================
                tvStep3.text = "[3/3] 神经网络深度推理中 (Inference Active)"
                tvStep3.setTextColor(android.graphics.Color.parseColor("#BB86FC"))
                tvCountdown.text = "正在唤醒算力核心..."

                // 🚀 解开封印：调用中控台执行底层大模型硬算
                val mdFile = StudioConsole002.processAudioData(this@StudioActivity, pcmFile) { remaining, total ->
                    // 确保 UI 刷新回调绝对运行在主线程
                    lifecycleScope.launch(Dispatchers.Main) {
                        tvCountdown.text = "算力进度: 还剩 $remaining 块音频切片 (总计: $total)"
                    }
                }

                // ==================================
                // 终局: 验证并产出资产
                // ==================================
                if (mdFile != null && mdFile.exists()) {
                    showShareSuccessUI(mdFile)
                } else {
                    throw Exception("Markdown 文档生成中断")
                }

            } catch (e: Exception) {
                Log.e(TAG, "❌ Pipeline 严重故障: ${e.message}")
                resetUIOnError(e.message ?: "未知系统异常")
            }
        }
    }

    /**
     * 🎨 UI 状态机：渲染终局胜利界面并打扫战场
     */
    private fun showShareSuccessUI(mdFile: File) {
        // 算力任务已完成，撤销免死金牌，允许屏幕自然休眠
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        tvStep3.text = "[3/3] 深度推理执行完毕！"
        tvStep3.setTextColor(android.graphics.Color.parseColor("#00C853"))
        tvCountdown.text = "文档封存成功，准备就绪。"

        btnSelectFile.text = "导出 MD 会议记录"
        btnSelectFile.setBackgroundColor(android.graphics.Color.parseColor("#00C853"))
        btnSelectFile.isEnabled = true

        btnSelectFile.setOnClickListener {
            // 呼叫中控台唤起系统级分享管道
            StudioConsole002.shareMarkdownFile(this@StudioActivity, mdFile)
        }
    }

    /**
     * 🎨 UI 状态机：系统重置与报错展示
     */
    private fun resetUIOnError(errorMessage: String) {
        // 无论如何，发生错误时立刻释放常亮锁
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        tvStep3.text = "❌ 任务终止: $errorMessage"
        tvStep3.setTextColor(android.graphics.Color.parseColor("#FF4444"))

        btnSelectFile.isEnabled = true
        btnSelectFile.text = "重新选择文件"
        btnSelectFile.setBackgroundColor(android.graphics.Color.parseColor("#6200EE"))
        btnSelectFile.setOnClickListener {
            filePickerLauncher.launch(arrayOf("audio/*", "video/mp4", "audio/mp4", "audio/x-m4a", "audio/wav"))
        }
    }
}