package com.aegis.voice

import android.Manifest
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.LayoutInflater
import android.view.View
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat

/**
 * =========================================================================================
 * 🛡️ Aegis 迎宾大厅与特权核验网关 (Onboarding Command Center)
 * * 【架构说明】：
 * 开发 Android 独立输入法 (IME) 受到系统底层沙盒的严格监管。
 * 本界面设计了一个高鲁棒性的“四步状态机 (4-Step State Machine)”，
 * 通过 500ms 的高频心跳轮询，引导用户无缝击穿系统限制，完成输入法的激活与默认挂载。
 * =========================================================================================
 */
class MainActivity : AppCompatActivity() {

    // ==========================================
    // 🎨 UI 组件引用 (UI Components)
    // ==========================================

    // Step 1: 系统激活开关
    private lateinit var statusDotEnable: View
    private lateinit var btnStepEnable: Button

    // Step 2: 默认输入法劫持
    private lateinit var statusDotSelect: View
    private lateinit var btnStepSelect: Button

    // Step 3: 物理硬件授权 (麦克风)
    private lateinit var statusDotMic: View
    private lateinit var btnStepMic: Button

    // Step 4: 语言边界协议 (模型适用性免责声明)
    private lateinit var statusDotLanguage: View
    private lateinit var btnStepLanguage: Button
    private lateinit var tvLabelLanguage: TextView

    // 🚀 [V2.0 专属拓展] 开发者后台/极客控制台入口
    private lateinit var btnOpenStudio: Button

    // ==========================================
    // ⚙️ 系统级常量配置
    // ==========================================
    private val PERMISSION_REQUEST_CODE = 1001
    private val PREFS_NAME = "AegisSetupPrefs"
    private val KEY_LANG_CONFIRMED = "language_protocol_confirmed_v1"

    // 🛡️ [架构优化] 全局心跳探针，防止内存泄漏
    private val uiHandler = Handler(Looper.getMainLooper())
    private lateinit var statusCheckerRunnable: Runnable

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // 1. 视图节点绑定
        bindViews()

        // 2. 交互事件路由挂载
        setupClickListeners()

        // 3. 🚀 启动高频心跳探针 (Polling Engine)
        // 监控系统权限状态，实现 UI 的实时“红绿灯”反馈
        statusCheckerRunnable = object : Runnable {
            override fun run() {
                updateStatus()
                uiHandler.postDelayed(this, 500) // 500ms 黄金侦测周期
            }
        }
        uiHandler.post(statusCheckerRunnable)
    }

    private fun bindViews() {
        statusDotEnable = findViewById(R.id.status_dot_enable)
        btnStepEnable = findViewById(R.id.btn_step_enable)

        statusDotSelect = findViewById(R.id.status_dot_select)
        btnStepSelect = findViewById(R.id.btn_step_select)

        statusDotMic = findViewById(R.id.status_dot_mic)
        btnStepMic = findViewById(R.id.btn_step_mic)

        statusDotLanguage = findViewById(R.id.status_dot_language)
        btnStepLanguage = findViewById(R.id.btn_step_language)
        tvLabelLanguage = findViewById(R.id.tv_label_language)

        btnOpenStudio = findViewById(R.id.btn_open_studio)
    }

    private fun setupClickListeners() {
        // ⚡ Step 1: 强行拉起系统输入法底层设置面板
        btnStepEnable.setOnClickListener {
            startActivity(Intent(Settings.ACTION_INPUT_METHOD_SETTINGS))
            Toast.makeText(this, "Please enable 'Aegis Voice' switch", Toast.LENGTH_LONG).show()
        }

        // ⚡ Step 2: 拉起悬浮选择器，夺取系统默认键盘控制权
        btnStepSelect.setOnClickListener {
            val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
            imm.showInputMethodPicker()
        }

        // ⚡ Step 3: 索要录音物理权限
        btnStepMic.setOnClickListener {
            requestMicPermission()
        }

        // ⚡ Step 4: 弹出硬核协议，确保用户了解大模型语言边界
        btnStepLanguage.setOnClickListener {
            showCustomLanguageDialog()
        }

        // 🚀 跳转至 Titan V2.0 极客调试工作站
        btnOpenStudio.setOnClickListener {
            val intent = Intent(this, StudioActivity::class.java)
            startActivity(intent)
        }
    }

    /**
     * 🧠 [核心状态机] 心跳侦测判定逻辑
     * 自动校验四大门禁，实时接管 UI 的可用状态与视觉反馈
     */
    private fun updateStatus() {
        val isEnabled = isKeyboardEnabled()
        val isSelected = isKeyboardSelected()
        val hasMic = hasMicPermission()
        val isLangConfirmed = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
            .getBoolean(KEY_LANG_CONFIRMED, false)

        // 门禁 1: 键盘是否在系统底层激活
        if (isEnabled) {
            statusDotEnable.setBackgroundColor(Color.parseColor("#00C853")) // 绿灯通过
            btnStepEnable.text = "DONE"
            btnStepEnable.isEnabled = false
            btnStepEnable.background.setTint(Color.DKGRAY)
        } else {
            statusDotEnable.setBackgroundColor(Color.parseColor("#FF4444")) // 红灯拦截
            btnStepEnable.text = "ENABLE"
            btnStepEnable.isEnabled = true
            btnStepEnable.background.setTint(Color.parseColor("#6200EE"))
        }

        // 门禁 2: 是否已成为系统首选键盘
        if (isSelected) {
            statusDotSelect.setBackgroundColor(Color.parseColor("#00C853"))
            btnStepSelect.text = "DONE"
            btnStepSelect.isEnabled = false
            btnStepSelect.background.setTint(Color.DKGRAY)
        } else {
            statusDotSelect.setBackgroundColor(Color.parseColor("#FF4444"))
            btnStepSelect.text = "SELECT"
            btnStepSelect.isEnabled = true
            btnStepSelect.background.setTint(Color.parseColor("#6200EE"))
        }

        // 门禁 3: 麦克风硬件访问权
        if (hasMic) {
            statusDotMic.setBackgroundColor(Color.parseColor("#00C853"))
            btnStepMic.text = "DONE"
            btnStepMic.isEnabled = false
            btnStepMic.background.setTint(Color.DKGRAY)
        } else {
            statusDotMic.setBackgroundColor(Color.parseColor("#FF4444"))
            btnStepMic.text = "ALLOW"
            btnStepMic.isEnabled = true
            btnStepMic.background.setTint(Color.parseColor("#6200EE"))
        }

        // 门禁 4: 语言边界协议确认
        if (isLangConfirmed) {
            statusDotLanguage.setBackgroundColor(Color.parseColor("#00C853"))
            btnStepLanguage.text = "CONFIRMED"
            btnStepLanguage.isEnabled = false
            btnStepLanguage.background.setTint(Color.DKGRAY)

            tvLabelLanguage.text = "Protocol Active: English"
            tvLabelLanguage.setTextColor(Color.parseColor("#00C853"))
        } else {
            statusDotLanguage.setBackgroundColor(Color.parseColor("#FF4444"))
            btnStepLanguage.text = "VERIFY"
            btnStepLanguage.isEnabled = true
            btnStepLanguage.background.setTint(Color.parseColor("#6200EE"))

            tvLabelLanguage.text = "Language Protocol"
            tvLabelLanguage.setTextColor(Color.parseColor("#EEEEEE"))
        }
    }

    /**
     * 📜 语言协议弹窗 (强制确认模式)
     * 设计初衷：强制告知用户当前端侧模型的最佳语言兼容域，避免使用期望落差。
     * 无 Cancel 按钮，点击弹窗外部无效，必须物理确认。
     */
    private fun showCustomLanguageDialog() {
        val inflater = LayoutInflater.from(this)
        val dialogView = inflater.inflate(R.layout.dialog_language_protocol, null)

        val builder = AlertDialog.Builder(this)
        builder.setView(dialogView)
        builder.setCancelable(false) // 物理锁死，防止绕过
        val dialog = builder.create()

        val btnConfirm = dialogView.findViewById<Button>(R.id.btn_dialog_confirm)

        btnConfirm.setOnClickListener {
            // 将协议确认状态固化至本地存储
            getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
                .edit()
                .putBoolean(KEY_LANG_CONFIRMED, true)
                .apply()

            dialog.dismiss()
            updateStatus() // 立即触发探针刷新 UI
        }

        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)
        dialog.show()
    }

    // ==========================================
    // 🔍 底层系统探针工具集 (System Probes)
    // ==========================================

    /** 探针 1：轮询系统已激活的输入法列表，核对当前包名 */
    private fun isKeyboardEnabled(): Boolean {
        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        val enabledMethods = imm.enabledInputMethodList
        for (method in enabledMethods) {
            if (method.packageName == packageName) {
                return true
            }
        }
        return false
    }

    /** 探针 2：读取系统安全设置，核对默认输入法指纹 */
    private fun isKeyboardSelected(): Boolean {
        val currentId = Settings.Secure.getString(contentResolver, Settings.Secure.DEFAULT_INPUT_METHOD)
        return currentId != null && currentId.startsWith(packageName)
    }

    /** 探针 3：核查麦克风物理调用权限 */
    private fun hasMicPermission(): Boolean {
        return ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
    }

    /** 唤起系统底层权限申请管道 */
    private fun requestMicPermission() {
        if (!hasMicPermission()) {
            ActivityCompat.requestPermissions(
                this,
                arrayOf(Manifest.permission.RECORD_AUDIO),
                PERMISSION_REQUEST_CODE
            )
        }
    }

    // ==========================================
    // 🛡️ [架构优化] 生命周期安保与资源回收
    // ==========================================
    override fun onDestroy() {
        super.onDestroy()
        // 关键安全补丁：界面销毁时必须切断高频心跳探针，防止主线程内存泄漏
        uiHandler.removeCallbacks(statusCheckerRunnable)
    }
}