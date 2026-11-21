package com.example.hello

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay

private const val RELEASE_URL =
    "https://github.com/cqj-2026/chat-app/releases/latest/download/app-release.apk"

/**
 * 开发者信息页：自检之后短暂展示，点击可跳过。
 * 纯文字，零额外依赖与体积。
 */
@Composable
fun AboutScreen(onDone: () -> Unit) {
    var alpha by remember { mutableStateOf(0f) }
    var leaving by remember { mutableStateOf(false) }
    val uriHandler = LocalUriHandler.current

    LaunchedEffect(Unit) {
        repeat(12) {
            alpha += 0.085f
            delay(28)
        }
        delay(2400)
        leaving = true
        onDone()
    }

    if (leaving) return

    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black)
            .clickable { onDone() },
        contentAlignment = Alignment.Center
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 28.dp)
                .alpha(alpha.coerceIn(0f, 1f)),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Text(
                "本应用由 蔡启俊 独立开发",
                color = Color(0xFFE8EEF5),
                fontSize = 16.sp,
                textAlign = TextAlign.Center
            )

            Spacer(Modifier.height(6.dp))

            Text(
                "下载链接",
                color = Color(0xFF5B6472),
                fontSize = 11.sp,
                fontFamily = FontFamily.Monospace
            )
            Text(
                "github.com/cqj-2026/chat-app",
                color = Color(0xFF4D8DFF),
                fontSize = 12.sp,
                fontFamily = FontFamily.Monospace,
                textAlign = TextAlign.Center,
                modifier = Modifier.clickable {
                    runCatching { uriHandler.openUri(RELEASE_URL) }
                }
            )

            Spacer(Modifier.height(6.dp))

            Text(
                "技术支持：DeepSeek",
                color = Color(0xFF5B6472),
                fontSize = 12.sp,
                fontFamily = FontFamily.Monospace
            )
        }
    }
}
