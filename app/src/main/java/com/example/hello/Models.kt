package com.example.hello

import android.content.Context
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream
import java.io.BufferedInputStream
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.TimeUnit
import java.util.zip.GZIPInputStream

/**
 * 模型下载与解包。与 sherpa-onnx 的 Java API 无关，可独立验证。
 * 支持断点续传：下载中断后再次点击会从已下载位置继续。
 */
object ModelIO {

    private val http = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    fun dirOf(ctx: Context, name: String): File =
        File(ctx.filesDir, "models/$name").apply { mkdirs() }

    /** 判断模型目录是否已就绪：至少有一个 .onnx 文件 */
    fun isReady(ctx: Context, name: String): Boolean {
        val d = dirOf(ctx, name)
        return d.listFiles()?.any { it.extension.equals("onnx", true) } == true
    }

    fun sizeOf(ctx: Context, name: String): Long {
        val d = dirOf(ctx, name)
        var s = 0L
        d.listFiles()?.forEach { s += it.length() }
        return s
    }

    /**
     * 下载到 dest。已存在部分则用 Range 续传。
     * onProgress(已下载字节, 总字节或 -1)
     */
    fun download(
        url: String,
        dest: File,
        onProgress: (Long, Long) -> Unit
    ) {
        val already = if (dest.exists()) dest.length() else 0L
        val builder = Request.Builder().url(url)
        if (already > 0) builder.addHeader("Range", "bytes=$already-")

        http.newCall(builder.build()).execute().use { resp ->
            if (!resp.isSuccessful) {
                throw IllegalStateException("HTTP ${resp.code}")
            }
            val body = resp.body ?: throw IllegalStateException("空响应")
            val total = if (resp.code == 206) {
                // Content-Range: bytes 100-999/1000
                resp.header("Content-Range")
                    ?.substringAfterLast('/')?.toLongOrNull() ?: -1L
            } else body.contentLength()

            val out = FileOutputStream(dest, already > 0 && resp.code == 206)
            body.byteStream().use { input ->
                val buf = ByteArray(64 * 1024)
                var written = if (resp.code == 206) already else 0L
                if (resp.code != 206 && already > 0) {
                    // 服务端不支持续传，重新写
                    out.channel.truncate(0)
                    written = 0L
                }
                while (true) {
                    val n = input.read(buf)
                    if (n <= 0) break
                    out.write(buf, 0, n)
                    written += n
                    onProgress(written, total)
                }
                out.flush()
            }
            out.close()
        }
    }

    /** 解包 .tar.bz2 / .tar.gz / 单文件。返回解出的文件数 */
    fun extract(archive: File, outDir: File): Int {
        outDir.mkdirs()
        val name = archive.name.lowercase()
        if (!name.endsWith(".tar.bz2") && !name.endsWith(".tar.gz") && !name.endsWith(".tgz")) {
            archive.copyTo(File(outDir, archive.name), overwrite = true)
            return 1
        }
        var count = 0
        BufferedInputStream(archive.inputStream()).use { bin ->
            val dec = if (name.endsWith(".tar.bz2")) {
                BZip2CompressorInputStream(bin, true)
            } else {
                GZIPInputStream(bin)
            }
            TarArchiveInputStream(dec).use { tar ->
                var entry = tar.nextTarEntry
                while (entry != null) {
                    val target = File(outDir, entry.name)
                    if (entry.isDirectory) {
                        target.mkdirs()
                    } else {
                        target.parentFile?.mkdirs()
                        FileOutputStream(target).use { fos ->
                            val buf = ByteArray(64 * 1024)
                            while (true) {
                                val n = tar.read(buf)
                                if (n <= 0) break
                                fos.write(buf, 0, n)
                            }
                        }
                        count++
                    }
                    entry = tar.nextTarEntry
                }
            }
        }
        return count
    }

    /** 下载 + 解包，一步到位 */
    fun fetchAndExtract(
        ctx: Context,
        name: String,
        url: String,
        onProgress: (Long, Long) -> Unit
    ): String {
        val dir = dirOf(ctx, name)
        val tmp = File(ctx.cacheDir, "$name.pkg")
        download(url, tmp, onProgress)
        val n = extract(tmp, dir)
        tmp.delete()
        return "解出 $n 个文件，共 ${sizeOf(ctx, name) / 1024 / 1024} MB"
    }
}

/** 模型管理页：两个模型的下载入口 */
@Composable
fun ModelScreen(onBack: () -> Unit) {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val asr = remember { CfgLocal.load(ctx, "asr_url") }
    val tts = remember { CfgLocal.load(ctx, "tts_url") }
    var asrUrl by remember { mutableStateOf(asr) }
    var ttsUrl by remember { mutableStateOf(tts) }
    var busy by remember { mutableStateOf("") }
    var msg by remember { mutableStateOf("") }
    var pct by remember { mutableStateOf(0) }
    val scope = rememberCoroutineScope()
    val mainHandler = remember { android.os.Handler(android.os.Looper.getMainLooper()) }

    fun run(name: String, url: String) {
        if (url.isBlank()) {
            msg = "先填 $name 的下载地址"
            return
        }
        busy = name
        msg = ""
        pct = 0
        scope.launch {
            try {
                val r = withContext(Dispatchers.IO) {
                    ModelIO.fetchAndExtract(ctx, name, url.trim()) { done, total ->
                        val v = if (total > 0) ((done * 100) / total).toInt() else 0
                        mainHandler.post { pct = v }
                    }
                }
                msg = "$name 完成：$r"
            } catch (e: Exception) {
                msg = "$name 失败：${e.message}"
            } finally {
                busy = ""
            }
        }
    }

    Column(
        Modifier
            .fillMaxSize()
            .safeDrawingPadding()
            .verticalScroll(rememberScrollState())
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
            TextButton(onClick = onBack) { Text("← 返回") }
            Text("语音模型", fontSize = 20.sp, color = Color(0xFFE8EEF5))
        }

        Text(
            "模型文件较大，建议接 WiFi。下载中断后再点一次会断点续传。",
            fontSize = 12.sp, color = Color(0xFF8A93A0)
        )

        // 语音识别
        Text("语音识别", fontSize = 15.sp, color = Color(0xFFE8EEF5))
        val asrReady = ModelIO.isReady(ctx, "asr")
        Text(
            if (asrReady) "已就绪 · ${ModelIO.sizeOf(ctx, "asr") / 1024 / 1024} MB" else "未下载",
            fontSize = 12.sp, color = if (asrReady) Color(0xFF6FD08C) else Color(0xFF8A93A0)
        )
        OutlinedTextField(
            value = asrUrl, onValueChange = { asrUrl = it; CfgLocal.save(ctx, "asr_url", it) },
            label = { Text("模型下载地址（.tar.bz2 或 .onnx）") },
            singleLine = false, maxLines = 3,
            modifier = Modifier.fillMaxWidth()
        )
        Button(
            onClick = { run("asr", asrUrl) },
            enabled = busy.isEmpty(),
            modifier = Modifier.fillMaxWidth()
        ) { Text(if (busy == "asr") "下载中 $pct%" else "下载语音识别模型") }

        HorizontalDivider()

        // 语音合成
        Text("语音合成", fontSize = 15.sp, color = Color(0xFFE8EEF5))
        val ttsReady = ModelIO.isReady(ctx, "tts")
        Text(
            if (ttsReady) "已就绪 · ${ModelIO.sizeOf(ctx, "tts") / 1024 / 1024} MB" else "未下载",
            fontSize = 12.sp, color = if (ttsReady) Color(0xFF6FD08C) else Color(0xFF8A93A0)
        )
        OutlinedTextField(
            value = ttsUrl, onValueChange = { ttsUrl = it; CfgLocal.save(ctx, "tts_url", it) },
            label = { Text("模型下载地址（.tar.bz2）") },
            singleLine = false, maxLines = 3,
            modifier = Modifier.fillMaxWidth()
        )
        Button(
            onClick = { run("tts", ttsUrl) },
            enabled = busy.isEmpty(),
            modifier = Modifier.fillMaxWidth()
        ) { Text(if (busy == "tts") "下载中 $pct%" else "下载语音合成模型") }

        if (msg.isNotEmpty()) {
            Spacer(Modifier.height(6.dp))
            Text(msg, fontSize = 12.sp, color = Color(0xFFFFC08A))
        }
    }
}

/** 极简键值存储，避免为两个字段再引一个依赖 */
object CfgLocal {
    fun load(ctx: Context, k: String): String =
        ctx.getSharedPreferences("modelcfg", Context.MODE_PRIVATE).getString(k, "") ?: ""
    fun save(ctx: Context, k: String, v: String) {
        ctx.getSharedPreferences("modelcfg", Context.MODE_PRIVATE).edit().putString(k, v).apply()
    }
}
