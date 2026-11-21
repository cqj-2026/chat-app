package com.example.hello

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.OfflineModelConfig
import com.k2fsa.sherpa.onnx.OfflineParaformerModelConfig
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig
import com.k2fsa.sherpa.onnx.OfflineTts
import com.k2fsa.sherpa.onnx.OfflineTtsConfig
import com.k2fsa.sherpa.onnx.OfflineTtsModelConfig
import com.k2fsa.sherpa.onnx.OfflineTtsVitsModelConfig
import com.k2fsa.sherpa.onnx.OfflineZipformerCtcModelConfig
import java.io.ByteArrayOutputStream
import java.io.File

/** sherpa-onnx 的公共工具：在模型目录里定位文件 */
object SherpaFiles {
    fun firstOnnx(dir: File): String =
        dir.walkTopDown().firstOrNull { it.isFile && it.extension.equals("onnx", true) }
            ?.absolutePath ?: ""

    fun named(dir: File, name: String): String =
        dir.walkTopDown().firstOrNull { it.isFile && it.name.equals(name, true) }
            ?.absolutePath ?: ""

    fun asrDir(ctx: Context): File = ModelIO.dirOf(ctx, "asr")
    fun ttsDir(ctx: Context): File = ModelIO.dirOf(ctx, "tts")
}

/** 采集麦克风：16kHz 单声道 PCM → FloatArray */
class AudioCapture(private val sampleRate: Int = 16000) {

    private var record: AudioRecord? = null
    @Volatile private var running = false
    private val pcm = ByteArrayOutputStream()

    fun start(): Boolean {
        if (running) return true
        val minBuf = AudioRecord.getMinBufferSize(
            sampleRate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT
        )
        if (minBuf <= 0) return false
        val rec = runCatching {
            AudioRecord(
                MediaRecorder.AudioSource.MIC,
                sampleRate,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                maxOf(minBuf, sampleRate * 2)
            )
        }.getOrNull() ?: return false
        if (rec.state != AudioRecord.STATE_INITIALIZED) {
            rec.release()
            return false
        }
        synchronized(pcm) { pcm.reset() }
        running = true
        rec.startRecording()
        record = rec

        Thread {
            val buf = ShortArray(2048)
            while (running) {
                val n = runCatching { rec.read(buf, 0, buf.size) }.getOrDefault(-1)
                if (n > 0) {
                    synchronized(pcm) {
                        for (i in 0 until n) {
                            val v = buf[i].toInt()
                            pcm.write(v and 0xFF)
                            pcm.write((v shr 8) and 0xFF)
                        }
                    }
                }
            }
        }.apply { isDaemon = true }.start()
        return true
    }

    /** 停止并返回归一化的浮点采样 */
    fun stop(): FloatArray {
        running = false
        record?.let {
            runCatching { it.stop() }
            runCatching { it.release() }
        }
        record = null
        val bytes = synchronized(pcm) { pcm.toByteArray() }
        val count = bytes.size / 2
        val out = FloatArray(count)
        for (i in 0 until count) {
            val lo = bytes[i * 2].toInt() and 0xFF
            val hi = bytes[i * 2 + 1].toInt()
            out[i] = (((hi shl 8) or lo).toShort()).toFloat() / 32768.0f
        }
        return out
    }

    fun isRunning(): Boolean = running
}

/** 离线语音识别（sherpa-onnx OfflineRecognizer） */
class SherpaAsr(private val ctx: Context) {

    private var recognizer: OfflineRecognizer? = null

    companion object {
        /** 模型是否已下载完毕 */
        fun isReady(ctx: Context): Boolean {
            val dir = SherpaFiles.asrDir(ctx)
            return SherpaFiles.firstOnnx(dir).isNotEmpty() &&
                SherpaFiles.named(dir, "tokens.txt").isNotEmpty()
        }
    }

    private fun ensureLoaded(): OfflineRecognizer? {
        recognizer?.let { return it }
        val dir = SherpaFiles.asrDir(ctx)
        val model = SherpaFiles.firstOnnx(dir)
        val tokens = SherpaFiles.named(dir, "tokens.txt")
        if (model.isEmpty() || tokens.isEmpty()) return null

        val isCtc = model.contains("ctc", true) && model.contains("zipformer", true)
        val modelCfg = if (isCtc) {
            OfflineModelConfig(
                zipformerCtc = OfflineZipformerCtcModelConfig(model = model),
                tokens = tokens,
                numThreads = 2,
                debug = false,
                provider = "cpu"
            )
        } else {
            OfflineModelConfig(
                paraformer = OfflineParaformerModelConfig(model = model),
                tokens = tokens,
                numThreads = 2,
                debug = false,
                provider = "cpu"
            )
        }
        val cfg = OfflineRecognizerConfig(
            featConfig = FeatureConfig(sampleRate = 16000, featureDim = 80),
            modelConfig = modelCfg,
            decodingMethod = "greedy_search"
        )
        val r = runCatching { OfflineRecognizer(ctx.assets, cfg) }.getOrNull() ?: return null
        recognizer = r
        return r
    }

    fun recognize(samples: FloatArray, sampleRate: Int = 16000): String {
        val r = ensureLoaded() ?: return ""
        val stream = r.createStream()
        return try {
            stream.acceptWaveform(samples, sampleRate)
            r.decode(stream)
            r.getResult(stream).text.trim()
        } finally {
            runCatching { stream.release() }
        }
    }

    fun release() {
        runCatching { recognizer?.release() }
        recognizer = null
    }
}

/** 离线语音合成（sherpa-onnx OfflineTts） */
class SherpaTts(private val ctx: Context) {

    private var tts: OfflineTts? = null

    companion object {
        fun isReady(ctx: Context): Boolean {
            val dir = SherpaFiles.ttsDir(ctx)
            return SherpaFiles.firstOnnx(dir).isNotEmpty() &&
                SherpaFiles.named(dir, "tokens.txt").isNotEmpty()
        }
    }

    private fun ensureLoaded(): OfflineTts? {
        tts?.let { return it }
        val dir = SherpaFiles.ttsDir(ctx)
        val model = SherpaFiles.firstOnnx(dir)
        if (model.isEmpty()) return null
        val tokens = SherpaFiles.named(dir, "tokens.txt")
        val lexicon = SherpaFiles.named(dir, "lexicon.txt")

        val vits = OfflineTtsVitsModelConfig(
            model = model,
            tokens = tokens,
            lexicon = lexicon,
            dataDir = dir.absolutePath
        )
        val modelCfg = OfflineTtsModelConfig(vits = vits, numThreads = 2, debug = false)
        val cfg = OfflineTtsConfig(model = modelCfg)
        val t = runCatching { OfflineTts(ctx.assets, cfg) }.getOrNull() ?: return null
        tts = t
        return t
    }

    /** 合成并播放；返回是否成功 */
    fun speak(text: String): Boolean {
        val t = ensureLoaded() ?: return false
        val audio = runCatching { t.generate(text, 0, 1.0f) }.getOrNull() ?: return false
        if (audio.samples.isEmpty()) return false
        AudioSink.play(audio.samples, audio.sampleRate)
        return true
    }

    fun release() {
        runCatching { tts?.release() }
        tts = null
    }
}

/** 播放 PCM 浮点采样 */
object AudioSink {

    fun play(samples: FloatArray, sampleRate: Int) {
        val pcm = ShortArray(samples.size) { i ->
            (samples[i].coerceIn(-1.0f, 1.0f) * 32767.0f).toInt().toShort()
        }
        val track = runCatching {
            AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build()
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(sampleRate)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .build()
                )
                .setBufferSizeInBytes(pcm.size * 2)
                .setTransferMode(AudioTrack.MODE_STATIC)
                .build()
        }.getOrNull() ?: return

        runCatching {
            track.write(pcm, 0, pcm.size)
            track.play()
        }
        val ms = (pcm.size * 1000L / sampleRate) + 300
        Thread {
            try {
                Thread.sleep(ms)
            } catch (_: InterruptedException) {
            }
            runCatching { track.stop() }
            runCatching { track.release() }
        }.apply { isDaemon = true }.start()
    }
}
