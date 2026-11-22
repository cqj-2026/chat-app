package com.example.hello

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** 可展开操作栏里的单个按钮：图标 + 文字标签 */
@Composable
fun ActionTile(
    icon: ImageVector,
    label: String,
    enabled: Boolean = true,
    active: Boolean = false,
    onClick: () -> Unit
) {
    val tint = when {
        !enabled -> Color(0xFF3F4A57)
        active -> Color(0xFF4D8DFF)
        else -> Color(0xFFB8C0CC)
    }
    Column(
        Modifier
            .width(72.dp)
            .clip(RoundedCornerShape(10.dp))
            .clickable(enabled = enabled) { onClick() }
            .padding(vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Icon(
            imageVector = icon,
            contentDescription = label,
            tint = tint,
            modifier = Modifier.size(24.dp)
        )
        Spacer(Modifier.height(4.dp))
        Text(
            label,
            fontSize = 10.sp,
            color = if (enabled) Color(0xFF8A93A0) else Color(0xFF3F4A57)
        )
    }
}
