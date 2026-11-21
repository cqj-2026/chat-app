package com.example.hello

import android.content.Context
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

// ---------- 配置 ----------

data class Cfg(val base: String, val key: String, val model: String)

class Store(ctx: Context) {
    private val sp = ctx.getSharedPreferences("cfg", Context.MODE_PRIVATE)
    fun load() = Cfg(
        sp.getString("base", "") ?: "",
        sp.getString("key", "") ?: "",
        sp.getString("model", "") ?: ""
    )
    fun save(c: Cfg) {
        sp.edit()
            .putString("base", c.base)
            .putString("key", c.key)
            .putString("model", c.model)
            .apply()
    }
    fun ready(c: Cfg) = c.base.isNotBlank() && c.key.isNotBlank() && c.model.isNotBlank()
}

data class Msg(val role: String, val text: String, val isErr: Boolean = false)

// ---------- 网络 ----------

private val http = OkHttpClient.Builder()
    .connectTimeout(30, TimeUnit.SECONDS)
    .writeTimeout(30, TimeUnit.SECONDS)
    .readTimeout(0, TimeUnit.SECONDS)   // 流式：绝不超时
    .build()

private suspend fun streamChat(
    cfg: Cfg,
    history: List<Msg>,
    onUpdate: suspend (String) -> Unit
) {
    val arr = JSONArray()
    history.forEach { arr.put(JSONObject().put("role", it.role).put("content", it.text)) }

    val payload = JSONObject()
        .put("model", cfg.model)
        .put("messages", arr)
        .put("stream", true)
        .toString()

    val req = Request.Builder()
        .url(cfg.base.trimEnd('/') + "/chat/completions")
        .addHeader("Authorization", "Bearer ${cfg.key}")
        .addHeader("Content-Type", "application/json")
        .post(payload.toRequestBody("application/json".toMediaType()))
        .build()

    http.newCall(req).execute().use { resp ->
        if (!resp.isSuccessful) {
            val body = resp.body?.string().orEmpty().take(400)
            throw IllegalStateException("HTTP ${resp.code}\n$body")
        }
        val src = resp.body?.source() ?: throw IllegalStateException("响应体为空")
        val sb = StringBuilder()
        while (true) {
            val line = src.readUtf8Line() ?: break
            if (!line.startsWith("data:")) continue
            val data = line.removePrefix("data:").trim()
            if (data == "[DONE]") break
            val delta = runCatching {
                JSONObject(data).getJSONArray("choices")
                    .getJSONObject(0).getJSONObject("delta").optString("content")
            }.getOrDefault("")
            if (delta.isNotEmpty()) {
                sb.append(delta)
                onUpdate(sb.toString())
            }
        }
        if (sb.isEmpty()) throw IllegalStateException("模型没有返回内容")
    }
}

// ---------- 入口 ----------

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent { Root() }
    }
}

@Composable
fun Root() {
    val ctx = LocalContext.current
    val store = remember { Store(ctx) }
    var cfg by remember { mutableStateOf(store.load()) }
    var showCfg by remember { mutableStateOf(!store.ready(cfg)) }

    MaterialTheme(colorScheme = darkColorScheme()) {
        Surface(Modifier.fillMaxSize(), color = Color(0xFF101216)) {
            if (showCfg) {
                CfgScreen(
                    init = cfg,
                    canCancel = store.ready(cfg),
                    onSave = { c ->
                        store.save(c); cfg = c; showCfg = false
                    },
                    onCancel = { showCfg = false }
                )
            } else {
                ChatScreen(cfg, onSettings = { showCfg = true })
            }
        }
    }
}

// ---------- 设置页 ----------

@Composable
fun CfgScreen(
    init: Cfg,
    canCancel: Boolean,
    onSave: (Cfg) -> Unit,
    onCancel: () -> Unit
) {
    var base by remember { mutableStateOf(init.base) }
    var key by remember { mutableStateOf(init.key) }
    var model by remember { mutableStateOf(init.model) }

    Column(
        Modifier
            .fillMaxSize()
            .safeDrawingPadding()
            .verticalScroll(rememberScrollState())
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Text("连接设置", fontSize = 22.sp, color = Color(0xFFE8EEF5))
        Text(
            "支持任意 OpenAI 兼容端点。要发图片，模型必须是多模态的。",
            fontSize = 13.sp, color = Color(0xFF8A93A0)
        )

        OutlinedTextField(
            value = base,
            onValueChange = { base = it },
            label = { Text("Base URL") },
            placeholder = { Text("https://api.deepseek.com/v1") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )
        OutlinedTextField(
            value = key,
            onValueChange = { key = it },
            label = { Text("API Key") },
            placeholder = { Text("sk-...") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )
        OutlinedTextField(
            value = model,
            onValueChange = { model = it },
            label = { Text("模型名") },
            placeholder = { Text("deepseek-chat") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )

        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Button(
                onClick = {
                    onSave(
                        Cfg(
                            base.trim().trimEnd('/'),
                            key.trim(),
                            model.trim()
                        )
                    )
                },
                enabled = base.isNotBlank() && key.isNotBlank() && model.isNotBlank(),
                modifier = Modifier.weight(1f)
            ) { Text("保存") }

            if (canCancel) {
                OutlinedButton(onClick = onCancel, modifier = Modifier.weight(1f)) {
                    Text("取消")
                }
            }
        }

        Spacer(Modifier.height(8.dp))
        Text(
            "常见配置\n" +
                "DeepSeek：https://api.deepseek.com/v1 ＋ deepseek-chat\n" +
                "阿里百炼：https://dashscope.aliyuncs.com/compatible-mode/v1 ＋ qwen-vl-max\n" +
                "智谱：https://open.bigmodel.cn/api/paas/v4 ＋ glm-4v",
            fontSize = 12.sp, color = Color(0xFF6E7681)
        )
    }
}

// ---------- 聊天页 ----------

@Composable
fun ChatScreen(cfg: Cfg, onSettings: () -> Unit) {
    val msgs = remember {
        mutableStateListOf(
            Msg("assistant", "已连接：${cfg.model}\n发条消息试试。")
        )
    }
    var input by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val listState = rememberLazyListState()

    LaunchedEffect(msgs.size) {
        if (msgs.isNotEmpty()) listState.animateScrollToItem(msgs.lastIndex)
    }

    fun send() {
        val t = input.trim()
        if (t.isEmpty() || busy) return
        input = ""
        busy = true

        val history = msgs.filter { !it.isErr }.toList() + Msg("user", t)
        msgs.add(Msg("user", t))
        val idx = msgs.size
        msgs.add(Msg("assistant", ""))

        scope.launch {
            try {
                withContext(Dispatchers.IO) {
                    streamChat(cfg, history) { partial ->
                        withContext(Dispatchers.Main) {
                            if (idx < msgs.size) msgs[idx] = Msg("assistant", partial)
                        }
                    }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    if (idx < msgs.size) {
                        msgs[idx] = Msg("assistant", e.message ?: e.toString(), isErr = true)
                    }
                }
            } finally {
                withContext(Dispatchers.Main) { busy = false }
            }
        }
    }

    Column(
        Modifier
            .fillMaxSize()
            .safeDrawingPadding()
            .imePadding()
    ) {
        // 顶部条
        Row(
            Modifier
                .fillMaxWidth()
                .background(Color(0xFF15181E))
                .padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f)) {
                Text("Chat", fontSize = 16.sp, color = Color(0xFFE8EEF5))
                Text(cfg.model, fontSize = 11.sp, color = Color(0xFF6E7681))
            }
            TextButton(onClick = onSettings) { Text("设置") }
        }

        LazyColumn(
            state = listState,
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
            contentPadding = PaddingValues(12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            itemsIndexed(msgs) { _, m -> Bubble(m) }
        }

        Row(
            Modifier
                .fillMaxWidth()
                .background(Color(0xFF181B21))
                .padding(8.dp),
            verticalAlignment = Alignment.Bottom
        ) {
            OutlinedTextField(
                value = input,
                onValueChange = { input = it },
                modifier = Modifier.weight(1f),
                placeholder = { Text("说点什么…") },
                maxLines = 5,
                shape = RoundedCornerShape(14.dp),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                keyboardActions = KeyboardActions(onSend = { send() })
            )
            Spacer(Modifier.width(8.dp))
            Button(
                onClick = { send() },
                enabled = !busy && input.isNotBlank(),
                modifier = Modifier.height(52.dp)
            ) { Text(if (busy) "…" else "发送") }
        }
    }
}

@Composable
fun Bubble(m: Msg) {
    val mine = m.role == "user"
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = if (mine) Arrangement.End else Arrangement.Start
    ) {
        Box(
            Modifier
                .widthIn(max = 300.dp)
                .background(
                    when {
                        m.isErr -> Color(0xFF4A1F26)
                        mine -> Color(0xFF2B6CFF)
                        else -> Color(0xFF242833)
                    },
                    RoundedCornerShape(16.dp)
                )
                .padding(horizontal = 14.dp, vertical = 10.dp)
        ) {
            Text(
                text = m.text.ifEmpty { "…" },
                color = if (m.isErr) Color(0xFFFFA8A8) else Color(0xFFE8EEF5),
                fontSize = 16.sp
            )
        }
    }
}
