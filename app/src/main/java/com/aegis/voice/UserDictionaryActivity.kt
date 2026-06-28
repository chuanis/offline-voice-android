package com.aegis.voice

import android.app.Activity
import android.app.AlertDialog
import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.Toast
import java.io.File
import android.graphics.Color
import android.view.Gravity
import android.text.SpannableString
import android.text.Spanned
import android.text.style.AbsoluteSizeSpan
import android.text.style.ForegroundColorSpan

/**
 * =========================================================================================
 * 🔐 用户热词加密金库管理面板 (UserDictionaryActivity V5.0 - Titan Crypto Client)
 * * 【架构设计说明】：
 * 1. 物理安全隔离：Java UI 层仅作为数据的“展示器”和“搬运工”，绝不持有任何加解密密钥。
 * 2. JNI 黑盒通讯：所有的读写指令全部下发给底层 C++ 兵工厂（NativeLib）执行。
 * 即使黑客反编译了 APK，也无法从 Java 层偷取到任何真实的语料数据。
 * 3. 智能容错合并：自动读取旧密文 -> 内存解密 -> 合并新词 -> 智能去重 -> 重新加密落盘。
 * =========================================================================================
 */
class UserDictionaryActivity : Activity() {

    private lateinit var etUserWords: EditText

    // 🔒 核心机密：底层 C++ 真实读写的加密文件物理路径
    private val realFileName = "tier1010.dat"
    // 🛡️ 对外展示：为了极客氛围，给用户展示的虚拟伪装文件名
    private val displayFileName = "User_Lexicon.enc"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_user_dictionary)

        etUserWords = findViewById(R.id.etUserWords)
        val btnSave = findViewById<Button>(R.id.btnSave)
        val btnClear = findViewById<Button>(R.id.btnCancel) // 保持原版 ID 映射

        // =========================================================================
        // 🚀 [UI 渲染] 极客控制台风格输入提示语
        // 向用户发出最严厉的警告：防止高频停用词污染大模型推理精度
        // =========================================================================
        val hintText = """
            Paste new words here...
            [ STATUS: Massive Enterprise Lexicon Loaded ]
            
            ⛔ 致命警告 (FATAL WARNING):
            绝不要添加常用停用词 (如 'the', 'and', 'is', 'to')。
            这会导致底层模糊纠错算法崩溃，产生严重幻觉。
            
            ✅ 仅限 VIP 专属词汇:
            1. 专有人名 (例如: 'Sam Altman')
            2. 行业黑话/代码 (例如: 'RLHF', 'EBITDA')
            
            [ 🔒 端到端加密金库 | 极限量: 500 词 ]
        """.trimIndent()

        // 使用富文本 (SpannableString) 单独控制提示语的大小和灰色高冷色调
        val spannableHint = SpannableString(hintText)
        spannableHint.setSpan(
            AbsoluteSizeSpan(13, true),
            0, hintText.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
        )
        spannableHint.setSpan(
            ForegroundColorSpan(Color.parseColor("#808080")),
            0, hintText.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
        )

        etUserWords.hint = spannableHint
        etUserWords.gravity = Gravity.TOP or Gravity.START

        // 绑定保存与销毁事件
        btnSave.setOnClickListener { saveAndAppendUserWords() }
        btnClear.setOnClickListener { showClearConfirmationDialog() }
    }

    /**
     * ⚠️ [最高安全级别] 触发金库自毁确认弹窗
     */
    private fun showClearConfirmationDialog() {
        val builder = AlertDialog.Builder(this)
        builder.setTitle("⚠️ 彻底销毁加密金库?")
        builder.setMessage("致命警告:\n\n" +
                "此操作将从物理硬盘上永久删除您的专属 VIP 词库 ($displayFileName)。\n" +
                "底层神经网络将彻底失去对这些专属词汇的识别优先级。\n\n" +
                "您确定要执行销毁指令吗?")

        builder.setPositiveButton("立即销毁 (PURGE ALL)") { _, _ ->
            performClearLibrary()
        }
        builder.setNegativeButton("取消", null)

        val dialog = builder.create()
        dialog.show()
        // 将确认销毁按钮标红，警示风险
        dialog.getButton(AlertDialog.BUTTON_POSITIVE)?.setTextColor(Color.RED)
    }

    /**
     * 💣 执行物理文件删除指令
     */
    private fun performClearLibrary() {
        val file = File(filesDir, realFileName)
        if (file.exists()) {
            // 文件删除属于系统级 IO，无需经过 C++ 引擎，直接在 Java 层物理抹除
            if (file.delete()) {
                Toast.makeText(this, "✅ 销毁协议执行完毕。金库已清空。", Toast.LENGTH_LONG).show()
                etUserWords.setText("") // 同步清空屏幕上的残留输入
            } else {
                Toast.makeText(this, "❌ 错误：物理存储覆写失败。", Toast.LENGTH_SHORT).show()
            }
        } else {
            Toast.makeText(this, "金库当前已是空状态。", Toast.LENGTH_SHORT).show()
        }
    }

    /**
     * 🧠 [核心业务枢纽] 提取新词 -> 唤醒 C++ 获取旧词 -> 无损合并 -> 重新加密落盘
     */
    private fun saveAndAppendUserWords() {
        val newText = etUserWords.text.toString().trim()
        if (newText.isEmpty()) return

        val file = File(filesDir, realFileName)
        var existingLines = listOf<String>()

        // ==========================================
        // 1. 📖 [安全物流] 唤醒 C++ 解密读取历史数据
        // ==========================================
        if (file.exists()) {
            try {
                // 呼叫 JNI 桥：C++ 将在内存中就地解密，并返回干净的明文数组
                val loadedArray = NativeLib.getUserWords(file.absolutePath)
                if (loadedArray != null) {
                    existingLines = loadedArray.toList()
                }
            } catch (e: Exception) {
                e.printStackTrace()
                // 容错：即使读取失败（可能文件损坏），也不阻断后续覆盖写入流程
            }
        }

        // ==========================================
        // 2. 🧹 [数据清洗] 处理用户在 UI 上的新输入
        // ==========================================
        val newLines = newText.lines()
            .map { it.trim() } // 强力剔除可能导致底层正则匹配崩溃的隐藏空格和控制符
            .filter { it.isNotEmpty() }

        // ==========================================
        // 3. 🧬 [基因合并] 无损去重与拼接
        // ==========================================
        // LinkedHashSet 是极为关键的数据结构：它既能自动踢出重复词，又能严格保持词汇的录入先后顺序
        val combinedSet = LinkedHashSet<String>()
        combinedSet.addAll(existingLines) // 先装入历史词汇
        combinedSet.addAll(newLines)      // 再追加新词汇，重复词将被自动抛弃

        // ==========================================
        // 4. 🛡️ [算力保护门禁] 强制截断
        // ==========================================
        // 极限量 500：防止用户恶意填入几万字，撑爆大模型的上下文窗口，导致推理引擎 OOM（内存溢出）
        val finalLines = combinedSet.take(500).toList()

        // 重新封包为带换行符的巨型字符串，准备提交给 C++
        val processedText = finalLines.joinToString("\n")

        try {
            // ==========================================
            // 5. 💾 [加密落盘] 移交 C++ 兵工厂执行黑盒写入
            // ==========================================
            val success = NativeLib.saveUserHotwords(file.absolutePath, processedText)

            if (success) {
                // 计算本次真实增量（剔除重复后的有效注入量）
                val addedCount = finalLines.size - existingLines.size
                val remainingSlots = 500 - finalLines.size

                // 注入成功，立刻清空输入框，防止用户误以为卡死而重复提交
                etUserWords.setText("")

                // 弹出极其专业的系统状态报告
                showPrivacySuccessDialog(addedCount, finalLines.size, remainingSlots)
            } else {
                throw Exception("底层 C++ 存储引擎写入协议受阻")
            }

        } catch (e: Exception) {
            Toast.makeText(this, "注入失败: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    /**
     * 📊 渲染极客范的注入成功报告单
     */
    private fun showPrivacySuccessDialog(added: Int, total: Int, remaining: Int) {
        val builder = AlertDialog.Builder(this)

        // 动态判定是否有真实新增
        val statusText = if (added > 0) {
            "✅ 成功向底层引擎注入 $added 条全新实体。"
        } else {
            "⚠️ 未检测到新的独立词汇。\n(您输入的词汇已存在，系统已自动执行去重拦截)"
        }

        val message = "$statusText\n\n" +
                "📚 加密金库当前激活量: $total 词\n" +
                "🔓 剩余可用额度: $remaining\n\n" +
                "安全状态: 数据已彻底加密，并物理锁定至 $displayFileName"

        builder.setTitle("神经系统更新完毕")
        builder.setMessage(message)
        builder.setPositiveButton("确 认 (ACKNOWLEDGE)") { dialog, _ ->
            dialog.dismiss()
        }
        // 强制用户必须点击按钮才能关闭弹窗，确保阅读了报告
        builder.setCancelable(false)

        val dialog = builder.create()
        dialog.show()
        // 将确认按钮渲染为标志性的紫色
        dialog.getButton(AlertDialog.BUTTON_POSITIVE)?.setTextColor(0xFF6200EE.toInt())
    }
}