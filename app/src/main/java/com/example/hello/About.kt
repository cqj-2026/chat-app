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
 * 开发者信息页：淡入后停留，点击任意位置才进入下一步。
 */
@Composable
fun AboutScreen(onDone: () -> Unit) {
    var alpha by remember { mutableStateOf(0f) }
    var ready by remember { mutableStateOf(false) }
    val uriHandler = LocalUriHandler.current

    LaunchedEffect(Unit) {
        repeat(12) {
            alpha += 0.085f
            delay(28)
        }
        ready = true
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black)
            .clickable { if (ready) onDone() }
    ) {
        Column(
            Modifier
                .fillMaxSize()
                .safeDrawingPadding()
                .padding(horizontal = 28.dp, vertical = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Spacer(Modifier.weight(1f))

            Column(
                Modifier
                    .fillMaxWidth()
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

                Spacer(Modifier.height(4.dp))

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

                Spacer(Modifier.height(4.dp))

                Text(
                    "技术支持：DeepSeek",
                    color = Color(0xFF5B6472),
                    fontSize = 12.sp,
                    fontFamily = FontFamily.Monospace
                )
            }

            Spacer(Modifier.weight(1f))

            Text(
                if (ready) "点击任意位置继续" else "",
                color = Color(0xFF3F4A57),
                fontSize = 11.sp,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(6.dp))
        }
    }
}
