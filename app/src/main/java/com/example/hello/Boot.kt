package com.example.hello

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay

private data class BootLine(val idx: Int, val label: String, val value: String, val ok: Boolean)

private val GREEN = Color(0xFF4ADE80)
private val AMBER = Color(0xFFFBBF24)
private val DIM = Color(0xFF3F4A57)
private val FG = Color(0xFF9CA3AF)

/**
 * 开屏自检：逐行扫描各模块状态，像终端启动日志。
 * 点击任意位置可跳过。全部用本地状态判断，不联网、不申请权限。
 */
@Composable
fun BootScreen(cfg: Cfg, onDone: () -> Unit) {
    val ctx = LocalContext.current
    val lines = remember { mutableStateListOf<BootLine>() }
    var finished by remember { mutableStateOf(false) }
    var skipped by remember { mutableStateOf(false) }
    val hist = remember { History(ctx) }

    LaunchedEffect(Unit) {
        var i = 0
        fun add(label: String, value: String, ok: Boolean) {
            i += 1
            lines.add(BootLine(i, label, value, ok))
        }

        delay(120)
        add("API ENDPOINT", if (cfg.base.isBlank()) "not set" else shortUrl(cfg.base), cfg.base.isNotBlank())
        delay(110)
        add("API KEY", if (cfg.key.isBlank()) "not set" else "****" + cfg.key.takeLast(4), cfg.key.isNotBlank())
        delay(110)
        add("CHAT MODEL", if (cfg.model.isBlank()) "not set" else cfg.model, cfg.model.isNotBlank())
        delay(110)
        val histCount = runCatching { hist.load().size }.getOrDefault(0)
        add("HISTORY", histCount.toString() + " msgs", true)
        delay(110)
        val asrReady = runCatching { SherpaAsr.isReady(ctx) }.getOrDefault(false)
        add("ASR MODEL", if (asrReady) "ready" else "missing", asrReady)
        delay(110)
        val ttsReady = runCatching { SherpaTts.isReady(ctx) }.getOrDefault(false)
        add("TTS MODEL", if (ttsReady) "ready" else "missing", ttsReady)
        delay(110)
        val imgReady = ImageGen.ready(cfg)
        add("IMAGE GEN", if (imgReady) "ready" else "not set", imgReady)
        delay(110)
        add("PERSONA", if (cfg.persona.isBlank()) "default" else "custom", true)
        delay(110)
        add("FILE READ", "enabled", true)
        delay(110)
        add("SPLIT 50", if (cfg.seg50) "on" else "off", true)
        delay(320)
        finished = true
        delay(280)
        if (!skipped) onDone()
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(Color(0xFF0B0D10))
            .clickable {
                skipped = true
                onDone()
            }
    ) {
        Column(
            Modifier
                .fillMaxSize()
                .safeDrawingPadding()
                .padding(horizontal = 20.dp, vertical = 26.dp)
        ) {
            Text(
                "chat-app // boot",
                fontFamily = FontFamily.Monospace,
                fontSize = 13.sp,
                color = GREEN
            )
            Spacer(Modifier.height(4.dp))
            Text(
                "self-check...",
                fontFamily = FontFamily.Monospace,
                fontSize = 11.sp,
                color = DIM
            )
            Spacer(Modifier.height(14.dp))

            Column(
                Modifier
                    .weight(1f)
                    .verticalScroll(rememberScrollState())
            ) {
                lines.forEach { l ->
                    Row(
                        Modifier.fillMaxWidth().padding(vertical = 3.dp),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Text(
                            "[" + l.idx.toString().padStart(2, '0') + "]",
                            fontFamily = FontFamily.Monospace,
                            fontSize = 12.sp,
                            color = DIM
                        )
                        Text(
                            l.label,
                            fontFamily = FontFamily.Monospace,
                            fontSize = 12.sp,
                            color = FG,
                            modifier = Modifier.width(108.dp)
                        )
                        Text(
                            if (l.ok) "OK" else "--",
                            fontFamily = FontFamily.Monospace,
                            fontSize = 12.sp,
                            color = if (l.ok) GREEN else AMBER
                        )
                        Text(
                            l.value,
                            fontFamily = FontFamily.Monospace,
                            fontSize = 12.sp,
                            color = if (l.ok) FG else AMBER
                        )
                    }
                }
                if (finished) {
                    Spacer(Modifier.height(10.dp))
                    Text(
                        "> ready",
                        fontFamily = FontFamily.Monospace,
                        fontSize = 13.sp,
                        color = GREEN
                    )
                }
            }

            Text(
                "点击跳过",
                fontFamily = FontFamily.Monospace,
                fontSize = 10.sp,
                color = DIM
            )
        }
    }
}

private fun shortUrl(u: String): String =
    u.removePrefix("https://").removePrefix("http://").trimEnd('/').take(30)
