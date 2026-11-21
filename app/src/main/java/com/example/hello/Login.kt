package com.example.hello

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay

private const val PREFS_LOGIN = "login"

/**
 * 本地登录闸门：保存用户名与密码，第二次启动自动提交。
 * 纯本地校验（只要求非空），不做任何网络请求。
 */
@Composable
fun LoginScreen(onDone: (String) -> Unit) {
    val ctx = LocalContext.current
    val prefs = remember { ctx.getSharedPreferences(PREFS_LOGIN, Context.MODE_PRIVATE) }

    var user by remember { mutableStateOf("") }
    var pass by remember { mutableStateOf("") }
    var passed by remember { mutableStateOf(false) }
    var finalName by remember { mutableStateOf("") }
    var autoTried by remember { mutableStateOf(false) }

    // 第二次启动：读取已保存账号，自动提交
    LaunchedEffect(Unit) {
        if (autoTried) return@LaunchedEffect
        autoTried = true
        val su = prefs.getString("user", "").orEmpty()
        val sp = prefs.getString("pass", "").orEmpty()
        if (su.isNotBlank() && sp.isNotBlank()) {
            user = su
            pass = sp
            delay(300)
            finalName = su
            passed = true
        }
    }

    // 通过后 0.5 秒进入下一屏
    LaunchedEffect(passed) {
        if (passed) {
            if (finalName.isBlank()) finalName = user
            delay(500)
            onDone(finalName)
        }
    }

    fun submit() {
        if (user.isBlank() || pass.isBlank()) return
        prefs.edit().putString("user", user.trim()).putString("pass", pass).apply()
        finalName = user.trim()
        passed = true
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black),
        contentAlignment = Alignment.Center
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Text(
                if (passed && finalName.isNotBlank()) "欢迎回来，$finalName" else "欢迎回来",
                color = Color(0xFFE8EEF5),
                fontSize = 26.sp
            )

            if (!passed) {
                OutlinedTextField(
                    value = user,
                    onValueChange = { user = it },
                    label = { Text("用户名") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )

                if (user.isNotEmpty()) {
                    OutlinedTextField(
                        value = pass,
                        onValueChange = { pass = it },
                        label = { Text("密码") },
                        singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(
                            keyboardType = KeyboardType.Password,
                            imeAction = ImeAction.Done
                        ),
                        keyboardActions = KeyboardActions(onDone = { submit() }),
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            } else {
                Text("验证通过", color = Color(0xFF4ADE80), fontSize = 13.sp)
            }
        }
    }
}
