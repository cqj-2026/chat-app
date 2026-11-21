package com.example.hello

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import android.speech.tts.TextToSpeech
import android.util.Base64
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
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
import java.io.ByteArrayOutputStream
import java.util.Locale
import java.util.concurrent.TimeUnit

// ---------------- 配置 ----------------

data class Cfg(
    val base: String,
    val key: String,
    val model: String,
    val persona: String = "",
    val imgBase: String = "",
    val imgKey: String = "",
    val imgModel: String = "",
    val seg50: Boolean = false
)

class Store(ctx: Context) {
    private val sp = ctx.getSharedPreferences("cfg", Context.MODE_PRIVATE)
    fun load() = Cfg(
        sp.getString("base", "") ?: "",
        sp.getString("key", "") ?: "",
        sp.getString("model", "") ?: "",
        sp.getString("persona", "") ?: "",
        sp.getString("imgBase", "") ?: "",
        sp.getString("imgKey", "") ?: "",
        sp.getString("imgModel", "") ?: "",
        sp.getBoolean("seg50", false)
    )
    fun save(c: Cfg) {
        sp.edit()
            .putString("base", c.base)
            .putString("key", c.key)
            .putString("model", c.model)
            .putString("persona", c.persona)
            .putString("imgBase", c.imgBase)
            .putString("imgKey", c.imgKey)
            .putString("imgModel", c.imgModel)
            .putBoolean("seg50", c.seg50)
            .apply()
    }
    fun ready(c: Cfg) = c.base.isNotBlank() && c.key.isNotBlank() && c.model.isNotBlank()
}

data class Msg(
    val role: String,
    val text: String,
    val imageUrl: String? = null,
    val thumb: Bitmap? = null,
    val imgFile: String? = null,
    val isErr: Boolean = false,
    val fileName: String? = null,
    val fileText: String? = null
)

// ---------------- 网络 ----------------

private val http = OkHttpClient.Builder()
    .connectTimeout(30, TimeUnit.SECONDS)
    .writeTimeout(60, TimeUnit.SECONDS)
    .readTimeout(0, TimeUnit.SECONDS)
    .build()

/** 历史里只有最后一张图会带 base64，更早的图用文字占位，省流量省 token */
private fun buildPayloadMessages(persona: String, history: List<Msg>): JSONArray {
    val lastUser = history.indexOfLast { it.role == "user" }
    val arr = JSONArray()
    if (persona.isNotBlank()) {
        arr.put(JSONObject().put("role", "system").put("content", persona))
    }
    history.forEachIndexed { i, m ->
        val o = JSONObject().put("role", m.role)
        if (m.imageUrl != null && i == lastUser) {
            val parts = JSONArray()
            parts.put(
                JSONObject().put("type", "image_url")
                    .put("image_url", JSONObject().put("url", m.imageUrl))
            )
            if (m.text.isNotBlank()) {
                parts.put(JSONObject().put("type", "text").put("text", m.text))
            }
            o.put("content", parts)
        } else if (m.imageUrl != null) {
            o.put("content", if (m.text.isBlank()) "[图片]" else "[图片] " + m.text)
        } else if (m.fileText != null) {
            o.put("content", "【文件 " + (m.fileName ?: "file") + "】\n" + m.fileText + "\n\n" + m.text)
        } else {
            o.put("content", m.text)
        }
        arr.put(o)
    }
    return arr
}

private suspend fun streamChat(
    cfg: Cfg,
    history: List<Msg>,
    onUpdate: suspend (String) -> Unit
) {
    val payload = JSONObject()
        .put("model", cfg.model)
        .put("messages", buildPayloadMessages(cfg.persona, history))
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
                val obj = JSONObject(data).getJSONArray("choices")
                    .getJSONObject(0).getJSONObject("delta")
                // JSON null 会被 optString 变成字符串 "null"，必须排除
                if (obj.isNull("content")) "" else obj.optString("content")
            }.getOrDefault("")
            if (delta.isNotEmpty()) {
                sb.append(delta)
                onUpdate(sb.toString())
            }
        }
        if (sb.isEmpty()) throw IllegalStateException("模型没有返回内容")
    }
}

// ---------------- 图片处理 ----------------

/** 读取相册图片，长边压到 maxSide，转成 JPEG base64 data URL */
private fun uriToImage(ctx: Context, uri: Uri, maxSide: Int = 1024): Pair<Bitmap, String>? {
    return runCatching {
        val cr = ctx.contentResolver
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        runCatching {
            cr.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        }
        val srcW = bounds.outWidth
        val srcH = bounds.outHeight
        if (srcW <= 0 || srcH <= 0) return null
        var sample = 1
        while (maxOf(srcW, srcH) / (sample * 2) >= maxSide) sample *= 2
        val opts = BitmapFactory.Options().apply { inSampleSize = sample }
        val bmp = runCatching {
            cr.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, opts) }
        }.getOrNull() ?: return null
        val scale = minOf(1f, maxSide.toFloat() / maxOf(bmp.width, bmp.height))
        val w = (bmp.width * scale).toInt().coerceAtLeast(1)
        val h = (bmp.height * scale).toInt().coerceAtLeast(1)
        val scaled = if (scale < 1f) Bitmap.createScaledBitmap(bmp, w, h, true) else bmp
        val bos = ByteArrayOutputStream()
        scaled.compress(Bitmap.CompressFormat.JPEG, 80, bos)
        val b64 = Base64.encodeToString(bos.toByteArray(), Base64.NO_WRAP)
        scaled to ("data:image/jpeg;base64,$b64")
    }.getOrNull()
}

// ---------------- 入口 ----------------

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
    var stage by remember { mutableStateOf(0) }

    MaterialTheme(colorScheme = darkColorScheme()) {
        Surface(Modifier.fillMaxSize(), color = Color(0xFF101216)) {
            Box(Modifier.fillMaxSize()) {
                when (stage) {
                    0 -> BootScreen(cfg = cfg) { stage = 1 }
                    1 -> LoginScreen { _ -> stage = 2 }
                    2 -> AboutScreen { stage = 3 }
                    else -> if (showCfg) {
                        CfgScreen(
                            init = cfg,
                            canCancel = store.ready(cfg),
                            onSave = { c -> store.save(c); cfg = c; showCfg = false },
                            onCancel = { showCfg = false }
                        )
                    } else {
                        ChatScreen(cfg, onSettings = { showCfg = true })
                    }
                }
            }
        }
    }
}
// ---------------- 设置页 ----------------

@Composable
fun CfgScreen(init: Cfg, canCancel: Boolean, onSave: (Cfg) -> Unit, onCancel: () -> Unit) {
    var base by remember { mutableStateOf(init.base) }
    var key by remember { mutableStateOf(init.key) }
    var model by remember { mutableStateOf(init.model) }
    var persona by remember { mutableStateOf(init.persona) }
    var imgBase by remember { mutableStateOf(init.imgBase) }
    var imgKey by remember { mutableStateOf(init.imgKey) }
    var imgModel by remember { mutableStateOf(init.imgModel) }
    var seg50 by remember { mutableStateOf(init.seg50) }
    var showModels by remember { mutableStateOf(false) }

    if (showModels) {
        ModelScreen(onBack = { showModels = false })
        return
    }

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
            "任意 OpenAI 兼容端点。发图片需要多模态模型。",
            fontSize = 13.sp, color = Color(0xFF8A93A0)
        )

        OutlinedTextField(
            value = base, onValueChange = { base = it },
            label = { Text("Base URL") },
            placeholder = { Text("https://api.deepseek.com/v1") },
            singleLine = true, modifier = Modifier.fillMaxWidth()
        )
        OutlinedTextField(
            value = key, onValueChange = { key = it },
            label = { Text("API Key") },
            placeholder = { Text("sk-...") },
            singleLine = true, modifier = Modifier.fillMaxWidth()
        )
        OutlinedTextField(
            value = model, onValueChange = { model = it },
            label = { Text("模型名") },
            placeholder = { Text("deepseek-flash") },
            singleLine = true, modifier = Modifier.fillMaxWidth()
        )

        OutlinedTextField(
            value = persona, onValueChange = { persona = it },
            label = { Text("人格设定（系统提示词，可选）") },
            placeholder = { Text("你叫小满，22 岁，说话短…") },
            minLines = 3, maxLines = 8,
            modifier = Modifier.fillMaxWidth()
        )

        Text("画图（可选，需要单独申请 key）", fontSize = 15.sp, color = Color(0xFFE8EEF5))
        OutlinedTextField(
            value = imgBase, onValueChange = { imgBase = it },
            label = { Text("画图 Base URL") },
            placeholder = { Text("https://dashscope.aliyuncs.com/compatible-mode/v1") },
            singleLine = true, modifier = Modifier.fillMaxWidth()
        )
        OutlinedTextField(
            value = imgKey, onValueChange = { imgKey = it },
            label = { Text("画图 API Key") },
            singleLine = true, modifier = Modifier.fillMaxWidth()
        )
        OutlinedTextField(
            value = imgModel, onValueChange = { imgModel = it },
            label = { Text("画图模型名") },
            placeholder = { Text("wanx-v1 / cogview-4 / flux.1-schnell") },
            singleLine = true, modifier = Modifier.fillMaxWidth()
        )

        OutlinedButton(
            onClick = { showModels = true },
            modifier = Modifier.fillMaxWidth()
        ) { Text("语音模型管理") }

        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("长回答按 50 字分段", fontSize = 14.sp, color = Color(0xFFE8EEF5))
                Text("开启后长回答会拆成多条气泡", fontSize = 11.sp, color = Color(0xFF8A93A0))
            }
            Switch(checked = seg50, onCheckedChange = { seg50 = it })
        }

        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Button(
                onClick = {
                    onSave(Cfg(base.trim().trimEnd('/'), key.trim(), model.trim(), persona.trim(), imgBase.trim().trimEnd('/'), imgKey.trim(), imgModel.trim(), seg50))
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
            "参考配置\n" +
                "DeepSeek（纯文字）\n" +
                "  https://api.deepseek.com/v1\n" +
                "  deepseek-flash / deepseek-v4-pro\n\n" +
                "阿里百炼（图文都行）\n" +
                "  https://dashscope.aliyuncs.com/compatible-mode/v1\n" +
                "  qwen-vl-max\n\n" +
                "智谱（图文都行）\n" +
                "  https://open.bigmodel.cn/api/paas/v4\n" +
                "  glm-4v",
            fontSize = 12.sp, color = Color(0xFF6E7681)
        )
    }
}

// ---------------- 聊天页 ----------------

@Composable
fun ChatScreen(cfg: Cfg, onSettings: () -> Unit) {
    val ctx = LocalContext.current
    val hist = remember { History(ctx) }
    val msgs = remember {
        mutableStateListOf<Msg>().apply {
            val saved = hist.load()
            if (saved.isEmpty()) add(Msg("assistant", "已连接：${cfg.model}")) else addAll(saved)
        }
    }
    var input by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var pending by remember { mutableStateOf<Pair<Bitmap, String>?>(null) }
    var ttsOn by remember { mutableStateOf(false) }
    var ttsReady by remember { mutableStateOf(false) }
    val sherpaTts = remember { SherpaTts(ctx) }
    val sherpaTtsReady = remember { SherpaTts.isReady(ctx) }
    DisposableEffect(Unit) { onDispose { sherpaTts.release() } }

    val scope = rememberCoroutineScope()
    val listState = rememberLazyListState()

    // 系统 TTS
    val tts = remember {
        TextToSpeech(ctx) { st -> ttsReady = st == TextToSpeech.SUCCESS }
    }
    LaunchedEffect(ttsReady) {
        if (ttsReady) {
            tts.setLanguage(Locale.SIMPLIFIED_CHINESE)
            tts.setSpeechRate(1.05f)
        }
    }
    DisposableEffect(Unit) {
        onDispose { tts.stop(); tts.shutdown() }
    }

    var attachName by remember { mutableStateOf<String?>(null) }
    var attachText by remember { mutableStateOf("") }

    val filePicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri != null) {
            scope.launch {
                try {
                    val r = withContext(Dispatchers.IO) { FileRead.read(ctx, uri) }
                    attachName = r.name
                    attachText = r.text
                } catch (e: Exception) {
                    attachName = null
                    attachText = ""
                    Toast.makeText(ctx, e.message ?: "读取失败", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    val picker = rememberLauncherForActivityResult(
        ActivityResultContracts.PickVisualMedia()
    ) { uri: Uri? ->
        if (uri != null) {
            scope.launch {
                val r = withContext(Dispatchers.IO) { uriToImage(ctx, uri) }
                if (r != null) pending = r
            }
        }
    }

    LaunchedEffect(msgs.size) {
        if (msgs.isNotEmpty()) listState.animateScrollToItem(msgs.lastIndex)
    }

    fun draw() {
        val prompt = input.trim()
        if (prompt.isEmpty() || busy) return
        if (!ImageGen.ready(cfg)) {
            msgs.add(Msg("assistant", "还没配置画图。请到设置里填 Base URL / Key / 模型名。", isErr = true))
            return
        }
        input = ""
        busy = true
        msgs.add(Msg("user", "画一张：" + prompt))
        val di = msgs.size
        msgs.add(Msg("assistant", "画图中…"))
        scope.launch {
            try {
                val bmp = ImageGen.generate(cfg, prompt)
                withContext(Dispatchers.Main) {
                    if (di < msgs.size) msgs[di] = Msg("assistant", prompt, thumb = bmp)
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    if (di < msgs.size) msgs[di] = Msg("assistant", "画图失败：" + (e.message ?: ""), isErr = true)
                }
            } finally {
                withContext(Dispatchers.Main) { busy = false }
            }
        }
    }

    fun send() {
        val t = input.trim()
        val img = pending
        if ((t.isEmpty() && img == null) || busy) return

        input = ""
        pending = null
        busy = true

        val imgFile = img?.let { hist.saveImage(it.second) }
        val userMsg = Msg("user", t, imageUrl = img?.second, thumb = img?.first, imgFile = imgFile, fileName = attachName, fileText = attachText.ifBlank { null })
        attachName = null
        attachText = ""
        val history = msgs.filter { !it.isErr }.toList() + userMsg

        msgs.add(userMsg)
        val idx = msgs.size
        msgs.add(Msg("assistant", ""))

        scope.launch {
            var finalText = ""
            try {
                withContext(Dispatchers.IO) {
                    streamChat(cfg, history) { partial ->
                        withContext(Dispatchers.Main) {
                            if (idx < msgs.size) msgs[idx] = Msg("assistant", partial)
                        }
                    }
                }
                finalText = msgs.getOrNull(idx)?.text.orEmpty()
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    if (idx < msgs.size) {
                        msgs[idx] = Msg("assistant", e.message ?: e.toString(), isErr = true)
                    }
                }
            } finally {
                withContext(Dispatchers.Main) {
                    busy = false
                    val savedMsg = msgs.getOrNull(idx)
                    if (savedMsg != null && !savedMsg.isErr) {
                        hist.append(userMsg.copy(imageUrl = null, thumb = null))
                        hist.append(Msg("assistant", finalText))
                        if (cfg.seg50 && finalText.length > 50) {
                            val parts = split50(finalText)
                            msgs[idx] = Msg("assistant", parts[0])
                            for (k in 1 until parts.size) {
                                msgs.add(idx + k, Msg("assistant", parts[k]))
                            }
                        }
                    }
                    if (ttsOn && finalText.isNotBlank()) {
                        val toRead = cleanForTts(finalText)
                        val useSherpa = sherpaTtsReady
                        val canSystem = ttsReady
                        scope.launch {
                            val spoken = try {
                                if (useSherpa) withContext(Dispatchers.IO) {
                                    sherpaTts.speak(toRead)
                                } else false
                            } catch (e: Throwable) {
                                false
                            }
                            if (!spoken && canSystem) {
                                runCatching {
                                    tts.speak(toRead, TextToSpeech.QUEUE_FLUSH, null, "ans")
                                }
                            }
                        }
                    }
                }
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
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f)) {
                Text("Chat", fontSize = 16.sp, color = Color(0xFFE8EEF5))
                Text(cfg.model, fontSize = 11.sp, color = Color(0xFF6E7681))
            }
            TextButton(onClick = {
                ttsOn = !ttsOn
                if (!ttsOn) tts.stop()
            }) {
                Text(if (ttsOn) "🔊 开" else "🔈 关", fontSize = 13.sp)
            }
            TextButton(onClick = onSettings) { Text("设置", fontSize = 13.sp) }
        }

        LazyColumn(
            state = listState,
            modifier = Modifier.weight(1f).fillMaxWidth(),
            contentPadding = PaddingValues(12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            itemsIndexed(msgs) { _, m -> Bubble(m) }
        }

        val an = attachName
        if (an != null) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .background(Color(0xFF15181E))
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("📎 " + an, color = Color(0xFF8A93A0), fontSize = 13.sp,
                    modifier = Modifier.weight(1f))
                TextButton(onClick = { attachName = null; attachText = "" }) { Text("移除") }
            }
        }

        // 待发送图片预览
        val p = pending
        if (p != null) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .background(Color(0xFF15181E))
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Image(
                    bitmap = p.first.asImageBitmap(),
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.size(56.dp).clip(RoundedCornerShape(8.dp))
                )
                Spacer(Modifier.width(10.dp))
                Text("图片已就绪", color = Color(0xFF8A93A0), fontSize = 13.sp,
                    modifier = Modifier.weight(1f))
                TextButton(onClick = { pending = null }) { Text("移除") }
            }
        }

        Row(
            Modifier
                .fillMaxWidth()
                .background(Color(0xFF181B21))
                .padding(8.dp),
            verticalAlignment = Alignment.Bottom
        ) {
            TextButton(
                onClick = { draw() },
                enabled = !busy,
                modifier = Modifier.height(52.dp)
            ) { Text("🎨", fontSize = 20.sp) }
            TextButton(
                onClick = { filePicker.launch(arrayOf("*/*")) },
                enabled = !busy,
                modifier = Modifier.height(52.dp)
            ) { Text("📎", fontSize = 20.sp) }
            MicButton(onText = { input = it })
            TextButton(
                onClick = {
                    picker.launch(
                        PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                    )
                },
                modifier = Modifier.height(52.dp)
            ) { Text("📷", fontSize = 20.sp) }

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
                enabled = !busy && (input.isNotBlank() || pending != null),
                modifier = Modifier.height(52.dp)
            ) { Text(if (busy) "…" else "发送") }
        }
    }
}

private fun cleanForTts(s: String): String {
    var t = s
    t = t.replace(Regex("```[\\s\\S]*?```"), "代码略。")
    t = t.replace(Regex("[`*#>_~|]"), "")
    return t.take(1200)
}

@Composable
fun Bubble(m: Msg) {
    val mine = m.role == "user"
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = if (mine) Arrangement.End else Arrangement.Start
    ) {
        Column(
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
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            val bmp = m.thumb
            if (bmp != null) {
                Image(
                    bitmap = bmp.asImageBitmap(),
                    contentDescription = null,
                    contentScale = ContentScale.FillWidth,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(10.dp))
                )
            }
            val fn = m.fileName
            if (fn != null) {
                Text("📎 " + fn, fontSize = 12.sp, color = Color(0xCCFFFFFF))
            }
            if (m.text.isNotEmpty() || bmp == null) {
                Text(
                    text = m.text.ifEmpty { "…" },
                    color = if (m.isErr) Color(0xFFFFA8A8) else Color(0xFFE8EEF5),
                    fontSize = 16.sp
                )
            }
        }
    }
}

/** 按 50 字切分，尽量在标点后断开 */
private fun split50(text: String, maxLen: Int = 50): List<String> {
    val out = ArrayList<String>()
    var i = 0
    while (i < text.length) {
        var end = minOf(i + maxLen, text.length)
        if (end < text.length) {
            for (j in end - 1 downTo i + 8) {
                val c = text[j]
                if (c == '。' || c == '！' || c == '？' || c == '，' || c == '\n' ||
                    c == '；' || c == '.' || c == '!' || c == '?' || c == ','
                ) {
                    end = j + 1
                    break
                }
            }
        }
        out.add(text.substring(i, end))
        i = end
    }
    return out
}
