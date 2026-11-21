package com.example.hello

import android.os.Build
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay

private data class BootLine(val idx: Int, val label: String, val value: String, val ok: Boolean)

private val B_GREEN = Color(0xFF4ADE80)
private val B_AMBER = Color(0xFFFBBF24)
private val B_DIM = Color(0xFF3F4A57)
private val B_FG = Color(0xFF9CA3AF)
private val B_BLUE = Color(0xFF4D8DFF)

/**
 * 开屏自检。扫描完成后底部出现 "Please touch to start"，
 * 点击才进入下一步；扫描过程中点击可立即完成。
 */
@Composable
fun BootScreen(cfg: Cfg, onDone: () -> Unit) {
    val ctx = LocalContext.current
    val lines = remember { mutableStateListOf<BootLine>() }
    var finished by remember { mutableStateOf(false) }
    var aborted by remember { mutableStateOf(false) }
    val hist = remember { History(ctx) }

    LaunchedEffect(Unit) {
        var i = 0
        fun add(label: String, value: String, ok: Boolean) {
            if (aborted) return
            i += 1
            lines.add(BootLine(i, label, value, ok))
        }
        fun step(label: String, value: String, ok: Boolean) {
            add(label, value, ok)
        }

        val ver = runCatching {
            ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName
        }.getOrNull() ?: "?"

        step("APP VERSION", ver, true); delay(90)
        step("ANDROID SDK", Build.VERSION.SDK_INT.toString(), true); delay(90)
        step("DEVICE", Build.MODEL ?: "unknown", true); delay(90)
        step("ABI", Build.SUPPORTED_ABIS.firstOrNull() ?: "?", true); delay(90)
        step("HEAP LIMIT", (Runtime.getRuntime().maxMemory() / 1024 / 1024).toString() + " MB", true); delay(90)
        step("API ENDPOINT", if (cfg.base.isBlank()) "not set" else shortBootUrl(cfg.base), cfg.base.isNotBlank()); delay(90)
        step("API KEY", if (cfg.key.isBlank()) "not set" else "****" + cfg.key.takeLast(4), cfg.key.isNotBlank()); delay(90)
        step("CHAT MODEL", if (cfg.model.isBlank()) "not set" else cfg.model, cfg.model.isBlank().not()); delay(90)
        val histCount = runCatching { hist.load().size }.getOrDefault(0)
        step("HISTORY", histCount.toString() + " msgs", true); delay(90)
        val asrOk = runCatching { SherpaAsr.isReady(ctx) }.getOrDefault(false)
        val asrSize = runCatching { ModelIO.sizeOf(ctx, "asr") / 1024 / 1024 }.getOrDefault(0L)
        step("ASR MODEL", if (asrOk) asrSize.toString() + " MB" else "missing", asrOk); delay(90)
        val ttsOk = runCatching { SherpaTts.isReady(ctx) }.getOrDefault(false)
        val ttsSize = runCatching { ModelIO.sizeOf(ctx, "tts") / 1024 / 1024 }.getOrDefault(0L)
        step("TTS MODEL", if (ttsOk) ttsSize.toString() + " MB" else "missing", ttsOk); delay(90)
        val imgOk = ImageGen.ready(cfg)
        step("IMAGE GEN", if (imgOk) "ready" else "not set", imgOk); delay(90)
        step("PERSONA", if (cfg.persona.isBlank()) "default" else "custom", true); delay(90)
        step("SPLIT 50", if (cfg.seg50) "on" else "off", true); delay(90)
        step("FILE READ", "enabled", true); delay(90)
        step("TTS CHUNK", "120 chars", true); delay(160)
        finished = true
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(Color(0xFF0B0D10))
            .clickable {
                if (finished) {
                    onDone()
                } else {
                    aborted = true
                    finished = true
                }
            }
    ) {
        Column(
            Modifier
                .fillMaxSize()
                .safeDrawingPadding()
                .padding(horizontal = 18.dp, vertical = 22.dp)
        ) {
            Text(
                "chat-app // boot",
                fontFamily = FontFamily.Monospace,
                fontSize = 13.sp,
                color = B_GREEN
            )
            Spacer(Modifier.height(3.dp))
            Text(
                if (finished) "scan complete" else "self-check...",
                fontFamily = FontFamily.Monospace,
                fontSize = 11.sp,
                color = B_DIM
            )
            Spacer(Modifier.height(12.dp))

            Column(
                Modifier
                    .weight(1f)
                    .verticalScroll(rememberScrollState())
            ) {
                lines.forEach { l ->
                    Row(
                        Modifier.fillMaxWidth().padding(vertical = 2.dp),
                        horizontalArrangement = Arrangement.spacedBy(5.dp)
                    ) {
                        Text(
                            "[" + l.idx.toString().padStart(2, '0') + "]",
                            fontFamily = FontFamily.Monospace, fontSize = 11.sp, color = B_DIM
                        )
                        Text(
                            l.label,
                            fontFamily = FontFamily.Monospace, fontSize = 11.sp,
                            color = B_FG, modifier = Modifier.width(96.dp)
                        )
                        Text(
                            if (l.ok) "OK" else "--",
                            fontFamily = FontFamily.Monospace, fontSize = 11.sp,
                            color = if (l.ok) B_GREEN else B_AMBER
                        )
                        Text(
                            l.value,
                            fontFamily = FontFamily.Monospace, fontSize = 11.sp,
                            color = if (l.ok) B_FG else B_AMBER
                        )
                    }
                }
            }

            if (finished) {
                Spacer(Modifier.height(10.dp))
                Text(
                    "Please touch to start",
                    fontSize = 24.sp,
                    color = B_BLUE,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(8.dp))
            }
        }
    }
}

private fun shortBootUrl(u: String): String =
    u.removePrefix("https://").removePrefix("http://").trimEnd('/').take(26)
