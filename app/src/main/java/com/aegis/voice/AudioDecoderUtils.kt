package com.aegis.voice

import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteOrder

/**
 * =========================================================================================
 * 🔪 Aegis Studio V2.0 - 极速音频解剖刀 (Audio Decoder Utils)
 * 🛡️ 架构特性 (终极防爆版)：
 * 1. 流式落盘 (Streaming I/O)：边解码边落盘，内存恒定，完美支持数小时超长音频，绝不 OOM。
 * 2. 纯整数位移 (Bitwise Resampling)：利用 `shr 1` 替代浮点运算进行混音，极度压榨 CPU 算力。
 * 3. 零 GC 缓冲池 (Zero-GC Pool)：动态复用字节数组，杜绝高频内存分配导致的 GC 停顿。
 * 4. 异步 EOS 拦截：严格分离输入端 (Input) 与输出端 (Output) 的生命周期，彻底解决尾部音频截断 Bug。
 * =========================================================================================
 */
object AudioDecoderUtils {

    private const val TAG = "AegisDecoder"

    // Whisper 与 Vosk 引擎的绝对物理标准
    private const val TARGET_SAMPLE_RATE = 16000
    private const val TARGET_CHANNELS = 1

    /**
     * 将任意格式的本地/网络音频 (MP3/M4A/WAV等) 极速解剖并重采样为 16kHz Mono 的裸 PCM 文件
     * @return 返回落盘成功的临时 PCM 文件句柄，失败返回 null
     */
    suspend fun decodeUriToPcmFile(context: Context, uri: Uri, onProgress: (Int) -> Unit): File? = withContext(Dispatchers.IO) {
        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(context, uri, null)
        } catch (e: Exception) {
            Log.e(TAG, "❌ 解复用器 (Extractor) 装载失败: ${e.message}")
            return@withContext null
        }

        var audioTrackIndex = -1
        var format: MediaFormat? = null
        var mime: String? = null

        // 🔍 嗅探音频轨道
        for (i in 0 until extractor.trackCount) {
            val trackFormat = extractor.getTrackFormat(i)
            val trackMime = trackFormat.getString(MediaFormat.KEY_MIME)
            if (trackMime?.startsWith("audio/") == true) {
                audioTrackIndex = i
                format = trackFormat
                mime = trackMime
                break
            }
        }

        if (audioTrackIndex < 0 || format == null || mime == null) {
            Log.e(TAG, "❌ 未检测到有效的音频轨道")
            extractor.release()
            return@withContext null
        }

        extractor.selectTrack(audioTrackIndex)

        // 提取源音频元数据 (用于重采样比例计算)
        val sourceSampleRate = if (format.containsKey(MediaFormat.KEY_SAMPLE_RATE)) format.getInteger(MediaFormat.KEY_SAMPLE_RATE) else 44100
        val sourceChannels = if (format.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) format.getInteger(MediaFormat.KEY_CHANNEL_COUNT) else 2
        val durationUs = if (format.containsKey(MediaFormat.KEY_DURATION)) format.getLong(MediaFormat.KEY_DURATION) else -1L

        // 🚀 启动硬件/软件解码器
        val decoder = MediaCodec.createDecoderByType(mime)
        decoder.configure(format, null, null, 0)
        decoder.start()

        val tempPcmFile = File(context.cacheDir, "aegis_temp_raw_${System.currentTimeMillis()}.pcm")
        val info = MediaCodec.BufferInfo()

        // 🚀 核心状态机：输入输出解耦标志位
        var isInputEOS = false
        var isOutputEOS = false
        var lastReportedProgress = -1

        // ========================================================
        // 🧪 动态对象复用池 (The Zero-GC Pool)
        // 预分配大块连续内存。日常循环 0 GC，仅在遇到变态极值时动态扩容
        // ========================================================
        var currentPoolSize = 65536
        var shortArrayPool = ShortArray(currentPoolSize)
        var monoArrayPool = ShortArray(currentPoolSize)
        var resampledArrayPool = ShortArray(currentPoolSize)
        var byteArrayPool = ByteArray(currentPoolSize * 2)

        try {
            // 🛡️ [架构优化] 使用 Kotlin `use` 语法糖，确保发生异常时安全关闭文件流
            BufferedOutputStream(FileOutputStream(tempPcmFile), 65536).use { fileOutputStream ->

                // 🔄 绞肉机大循环：只要输出端没有吐出 EOS 标志，就绝对不能停！
                while (!isOutputEOS) {

                    // --- 阶段 A：向解码器投喂压缩数据 (塞肉) ---
                    if (!isInputEOS) {
                        val inIndex = decoder.dequeueInputBuffer(2000L)
                        if (inIndex >= 0) {
                            val buffer = decoder.getInputBuffer(inIndex)
                            val sampleSize = if (buffer != null) extractor.readSampleData(buffer, 0) else -1

                            if (sampleSize < 0) {
                                // 源文件读取完毕，向解码器发送结束信号，但保持运行等待残余数据吐出
                                decoder.queueInputBuffer(inIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                                isInputEOS = true
                            } else {
                                decoder.queueInputBuffer(inIndex, 0, sampleSize, extractor.sampleTime, 0)

                                // 极速计算并上报进度条
                                if (durationUs > 0) {
                                    val currentProgress = ((extractor.sampleTime.toFloat() / durationUs.toFloat()) * 100).toInt()
                                    if (currentProgress != lastReportedProgress && currentProgress % 2 == 0) {
                                        lastReportedProgress = currentProgress
                                        withContext(Dispatchers.Main) { onProgress(currentProgress.coerceIn(0, 100)) }
                                    }
                                }
                                extractor.advance() // 游标推进一步
                            }
                        }
                    }

                    // --- 阶段 B：从解码器提取 PCM 裸流并整形 (接肉) ---
                    var outIndex = decoder.dequeueOutputBuffer(info, 2000L)
                    while (outIndex >= 0) {
                        val outBuffer = decoder.getOutputBuffer(outIndex)
                        if (outBuffer != null && info.size > 0) {
                            outBuffer.position(info.offset)
                            outBuffer.limit(info.offset + info.size)

                            val currentShorts = info.size / 2

                            // 🛡️ 防爆机制：如果某帧数据畸大，执行紧急动态扩容
                            if (currentShorts > currentPoolSize) {
                                currentPoolSize = currentShorts + 1024
                                shortArrayPool = ShortArray(currentPoolSize)
                                monoArrayPool = ShortArray(currentPoolSize)
                                resampledArrayPool = ShortArray(currentPoolSize)
                                byteArrayPool = ByteArray(currentPoolSize * 2)
                            }

                            // 从底层 DirectBuffer 将数据倒出至对象池
                            outBuffer.order(ByteOrder.nativeOrder()).asShortBuffer().get(shortArrayPool, 0, currentShorts)

                            // ⚡ 核心手术 1：极速转单声道 (Stereo to Mono)
                            var monoCount = currentShorts
                            if (sourceChannels == 2) {
                                monoCount = currentShorts / 2
                                for (i in 0 until monoCount) {
                                    // 抛弃除法，使用位移 shr 1 实现极速均值计算
                                    monoArrayPool[i] = ((shortArrayPool[i * 2].toInt() + shortArrayPool[i * 2 + 1].toInt()) shr 1).toShort()
                                }
                            } else {
                                System.arraycopy(shortArrayPool, 0, monoArrayPool, 0, currentShorts)
                            }

                            // ⚡ 核心手术 2：极速重采样至 16kHz (Nearest-Neighbor)
                            var resampledCount = monoCount
                            if (sourceSampleRate != TARGET_SAMPLE_RATE) {
                                resampledCount = (monoCount.toLong() * TARGET_SAMPLE_RATE / sourceSampleRate).toInt()
                                for (i in 0 until resampledCount) {
                                    val originalIndex = (i.toLong() * sourceSampleRate / TARGET_SAMPLE_RATE).toInt()
                                    resampledArrayPool[i] = monoArrayPool[originalIndex]
                                }
                            } else {
                                System.arraycopy(monoArrayPool, 0, resampledArrayPool, 0, monoCount)
                            }

                            // ⚡ 核心手术 3：Short 数组打碎为 Byte 数组并落盘
                            var byteIdx = 0
                            for (i in 0 until resampledCount) {
                                val s = resampledArrayPool[i].toInt()
                                byteArrayPool[byteIdx++] = (s and 0xFF).toByte()
                                byteArrayPool[byteIdx++] = ((s shr 8) and 0xFF).toByte()
                            }

                            fileOutputStream.write(byteArrayPool, 0, resampledCount * 2)
                        }

                        // 🔍 嗅探尾部标志：接到此牌，方可安全拉闸
                        if ((info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                            isOutputEOS = true
                        }

                        decoder.releaseOutputBuffer(outIndex, false)

                        if (isOutputEOS) break // 输出已枯竭，强行跳出排空循环

                        outIndex = decoder.dequeueOutputBuffer(info, 0L)
                    }
                }
            } // end of `use` block (文件流已在此处被绝对安全地自动 close)

        } catch (e: Exception) {
            Log.e(TAG, "❌ 解码流水线崩溃: ${e.message}")
            tempPcmFile.delete() // 异常时清理尸体
            return@withContext null
        } finally {
            // 安全释放底层编解码器与解复用器
            decoder.stop()
            decoder.release()
            extractor.release()
        }

        withContext(Dispatchers.Main) { onProgress(100) }
        Log.i(TAG, "✅ 音频解剖完毕! 纯净 PCM 暂存于: ${tempPcmFile.absolutePath}")
        return@withContext tempPcmFile
    }
}