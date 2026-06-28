package com.aegis.voice

import android.content.Context
import android.content.res.AssetManager
import java.nio.ByteBuffer
import org.vosk.LibVosk
import org.vosk.LogLevel

/**
 * =========================================================================================
 * 🌉 Aegis Native JNI Protocol [The Diplomat]
 * * 职责说明：
 * 本对象是 Java/Kotlin 应用层与 C++ 底层 AI 兵工厂（native-lib.cpp）通信的唯一合法网关。
 * 它严格遵守底层定义的方法签名，负责传递上下文、管理内存生命周期以及建立加密物流通道。
 * =========================================================================================
 */
object NativeLib {

    init {
        try {
            // 1. 🚀 装载 C++ 核心防御与推理引擎
            System.loadLibrary("native-lib")

            // 2. 🔇 运行期静音控制
            // 强制将 Vosk 的底层日志级别压至 WARNINGS，防止其冗余日志击穿控制台性能
            LibVosk.setLogLevel(LogLevel.WARNINGS)
        } catch (e: Throwable) {
            e.printStackTrace()
        }
    }

    // ==========================================
    // 🧠 核心引擎生命周期管理 (Engine Lifecycle)
    // ==========================================

    /**
     * 触发底层模型装载与算力分配 (NPU/GPU/CPU)
     * @param context 喂给底层安全校验门 (SecurityGate) 的凭证，用于提取包名与签名 Hash
     */
    external fun loadModel(
        context: Context,
        assetManager: AssetManager,
        modelPath: String,
        device: Int
    ): Boolean

    /**
     * 释放引擎句柄，触发底层执行内存擦除协议
     */
    external fun freeModel()

    /**
     * 将 PCM 音频流送入底层 Whisper 引擎进行推理
     */
    external fun transcribeAudio(
        audioBuffer: ByteBuffer,
        len: Int,
        prompt: String
    ): String

    /**
     * 预留的高级调参接口
     */
    external fun setWhisperParams(beamSize: Int, entropyThold: Float)

    // ==========================================
    // 🚀 静态语料库注入通道 (Data Injection)
    // ==========================================

    /**
     * 唤醒底层 AssetsLoader，将 AssetManager 的指针交接给 C++，
     * 由底层自行完成高密语料的读取、流密码解密与内存装载。
     */
    external fun initStaticData(
        assetManager: AssetManager,
        userHotwordsPath: String
    )

    /**
     * 强制清空底层常驻内存中的语料库
     */
    external fun resetStaticData()

    // ==========================================
    // ⚔️ 算法桥接通道 (Algorithm Bridge)
    // ==========================================

    /**
     * 调用底层 Titan 架构的 FuzzyMatch 引擎，执行幻觉清理与精度纠错
     */
    external fun nativeFuzzyCorrect(raw: String, hotwords: Array<String>): String

    // ==========================================
    // 🔒 黑盒加解密通道 (Crypto I/O Pipeline)
    // ==========================================

    /**
     * 💾 [写] 保存用户专属热词
     * 工作流：Java 层传入明文 -> C++ 底层就地加密 -> 物理写入本地磁盘
     * 目的：防止 Root 后的设备遭遇恶意应用窃取用户自定义隐私数据
     */
    external fun saveUserHotwords(path: String, content: String): Boolean

    /**
     * 📖 [读] 读取用户专属热词
     * 工作流：C++ 读取本地密文 -> 内存就地解密 -> 返回 Java 层明文数组
     */
    external fun getUserWords(path: String): Array<String>

    /**
     * 📦 [读] 提取加密资产文件
     * 工作流：供上层 Vosk 组件安全读取其必要的配置或模型文件（如 tier_base.dat）。
     * 底层完成读取与解密后，以字符串数组形式安全交接。
     */
    external fun getDecryptedAsset(assetManager: AssetManager, path: String): Array<String>
}