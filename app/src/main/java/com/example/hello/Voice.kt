package com.example.hello

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.sp

/** 封装系统语音识别：优先用设备端离线模型，不联网、不增加 APK 体积 */
class VoiceAsr(private val ctx: Context) {

    private var rec: SpeechRecognizer? = null

    fun stop() {
        runCatching { rec?.stop() }
        runCatching { rec?.destroy() }
        rec = null
    }

    fun start(onText: (String) -> Unit, onFail: (String) -> Unit, onState: (Boolean) -> Unit) {
        stop()
        if (!SpeechRecognizer.isRecognitionAvailable(ctx)) {
            onFail("此设备没有可用的语音识别服务")
            return
        }

        // API 31+ 优先用设备端识别器（完全离线）
        val onDeviceOk = if (Build.VERSION.SDK_INT >= 31) {
            runCatching { SpeechRecognizer.isOnDeviceRecognitionAvailable(ctx) }.getOrDefault(false)
        } else false

        val created = if (onDeviceOk) {
            runCatching { SpeechRecognizer.createOnDeviceSpeechRecognizer(ctx) }.getOrNull()
        } else null

        val finalRec = created
            ?: runCatching { SpeechRecognizer.createSpeechRecognizer(ctx) }.getOrNull()

        if (finalRec == null) {
            onFail("无法创建语音识别器")
            return
        }
        rec = finalRec

        finalRec.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) = onState(true)
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(rmsdB: Float) {}
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() = onState(false)
            override fun onPartialResults(partialResults: Bundle?) {}
            override fun onEvent(eventType: Int, params: Bundle?) {}

            override fun onError(error: Int) {
                onState(false)
                onFail(errText(error))
            }

            override fun onResults(results: Bundle?) {
                onState(false)
                val list = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                val best = list?.firstOrNull().orEmpty().trim()
                if (best.isEmpty()) onFail("没听清，再说一次") else onText(best)
            }
        })

        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(
                RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                RecognizerIntent.LANGUAGE_MODEL_FREE_FORM
            )
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "zh-CN")
            putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, false)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
        }
        runCatching { finalRec.startListening(intent) }
            .onFailure { onFail("启动识别失败") }
    }

    private fun errText(code: Int): String = when (code) {
        SpeechRecognizer.ERROR_AUDIO -> "录音出错"
        SpeechRecognizer.ERROR_CLIENT -> "客户端错误，重试一次"
        SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "没有麦克风权限"
        SpeechRecognizer.ERROR_NETWORK -> "网络错误（可能没装离线语音包）"
        SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "网络超时"
        SpeechRecognizer.ERROR_NO_MATCH -> "没听清，再说一次"
        SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "识别器忙，稍后重试"
        SpeechRecognizer.ERROR_SERVER -> "服务端错误"
        SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "没检测到说话"
        else -> "识别失败 ($code)"
    }
}

/** 麦克风按钮：点一下开始听，再点一下停止 */
@Composable
fun MicButton(onText: (String) -> Unit) {
    val ctx = LocalContext.current
    var listening by remember { mutableStateOf(false) }
    val asr = remember { VoiceAsr(ctx) }

    DisposableEffect(Unit) {
        onDispose { asr.stop() }
    }

    fun begin() {
        asr.start(
            onText = { t ->
                listening = false
                onText(t)
            },
            onFail = { msg ->
                listening = false
                Toast.makeText(ctx, msg, Toast.LENGTH_SHORT).show()
            },
            onState = { st -> listening = st }
        )
    }

    val permLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) begin()
        else Toast.makeText(ctx, "没有麦克风权限", Toast.LENGTH_SHORT).show()
    }

    TextButton(onClick = {
        if (listening) {
            asr.stop()
            listening = false
        } else {
            val granted = ctx.checkSelfPermission(Manifest.permission.RECORD_AUDIO) ==
                PackageManager.PERMISSION_GRANTED
            if (granted) begin() else permLauncher.launch(Manifest.permission.RECORD_AUDIO)
        }
    }) {
        Text(if (listening) "🔴" else "🎤", fontSize = 20.sp)
    }
}
