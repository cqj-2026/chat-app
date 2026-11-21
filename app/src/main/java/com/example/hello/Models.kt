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
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.concurrent.TimeUnit
import java.util.zip.GZIPInputStream

/**
 * 模型下载与解包。与 sherpa-onnx 的 Java API 无关，可独立验证。
 * 支持断点续传：下载中断后再次点击会从已下载位置继续。
 */
object ModelIO {

    private val http = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    fun dirOf(ctx: Context, name: String): File =
        File(ctx.filesDir, "models/$name").apply { mkdirs() }

    private fun extOf(url: String): String = when {
        url.contains(".tar.bz2", true) -> ".tar.bz2"
        url.contains(".tar.gz", true) -> ".tar.gz"
        url.contains(".tgz", true) -> ".tgz"
        url.contains(".onnx", true) -> ".onnx"
        else -> ""
    }

    /** 目录里是否有模型文件 */
    fun isReady(ctx: Context, name: String): Boolean = onnxCount(ctx, name) > 0

    fun onnxCount(ctx: Context, name: String): Int =
        dirOf(ctx, name).walkTopDown().count { it.isFile && it.extension.equals("onnx", true) }

    fun sizeOf(ctx: Context, name: String): Long {
        var s = 0L
        dirOf(ctx, name).walkTopDown().forEach { if (it.isFile) s += it.length() }
        return s
    }

    /** 下载到 dest，支持 Range 续传 */
    fun download(url: String, dest: File, onProgress: (Long, Long) -> Unit) {
        val already = if (dest.exists()) dest.length() else 0L
        val builder = Request.Builder().url(url).header("User-Agent", "dsh-chat-app")
        if (already > 0) builder.addHeader("Range", "bytes=$already-")

        http.newCall(builder.build()).execute().use { resp ->
            if (!resp.isSuccessful) {
                throw IllegalStateException("HTTP ${resp.code}")
            }
            val body = resp.body ?: throw IllegalStateException("空响应")
            val resuming = resp.code == 206
            val total = if (resuming) {
                resp.header("Content-Range")?.substringAfterLast('/')?.toLongOrNull() ?: -1L
            } else {
                body.contentLength()
            }
            val out = FileOutputStream(dest, resuming)
            if (!resuming) runCatching { out.channel.truncate(0) }
            var written = if (resuming) already else 0L
            body.byteStream().use { input ->
                val buf = ByteArray(64 * 1024)
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

    /** 按文件头魔数判断格式并解包；不是压缩包就当作单文件拷贝 */
    fun extract(archive: File, outDir: File): Int {
        outDir.mkdirs()
        val head = ByteArray(3)
        runCatching { FileInputStream(archive).use { it.read(head) } }

        val isBz2 = head[0] == 'B'.code.toByte() &&
            head[1] == 'Z'.code.toByte() &&
            head[2] == 'h'.code.toByte()
        val isGz = head[0] == 0x1f.toByte() && head[1] == 0x8b.toByte()

        if (!isBz2 && !isGz) {
            val target = File(outDir, archive.name.removeSuffix(".pkg"))
            archive.copyTo(target, overwrite = true)
            return 1
        }

        var count = 0
        BufferedInputStream(FileInputStream(archive)).use { bin ->
            val dec = if (isBz2) BZip2CompressorInputStream(bin, true) else GZIPInputStream(bin)
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

    // GitHub 直连不通时依次尝试的镜像前缀（空串表示直连）
    private val MIRRORS = listOf(
        "",
        "https://ghfast.top/",
        "https://gh-proxy.com/",
        "https://ghproxy.net/"
    )

    /** 下载 + 解包。GitHub 不通时自动尝试镜像；会先清空旧目录 */
    fun fetchAndExtract(
        ctx: Context,
        name: String,
        url: String,
        onProgress: (Long, Long) -> Unit
    ): String {
        val dir = dirOf(ctx, name)
        dir.walkTopDown().filter { it.isFile }.forEach { runCatching { it.delete() } }

        val raw = url.trim()
        val alreadyProxied = raw.indexOf("http", 4) > 0
        val attempts: List<String> = when {
            alreadyProxied -> listOf(raw)
            raw.contains("github.com") -> MIRRORS.map { it + raw }
            else -> listOf(raw)
        }

        var lastErr: Exception? = null
        for (u in attempts) {
            val tmp = File(ctx.cacheDir, "$name.pkg" + extOf(raw))
            try {
                runCatching { tmp.delete() }
                download(u, tmp, onProgress)
                val n = extract(tmp, dir)
                runCatching { tmp.delete() }
                val onnx = onnxCount(ctx, name)
                if (onnx == 0) {
                    throw IllegalStateException("解包后没找到 .onnx 模型文件（解出 $n 个文件）")
                }
                return "解出 $n 个文件，模型 ${sizeOf(ctx, name) / 1024 / 1024} MB"
            } catch (e: Exception) {
                lastErr = e
            }
        }
        throw lastErr ?: IllegalStateException("下载失败")
    }
}

/** 模型管理页 */
@Composable
fun ModelScreen(onBack: () -> Unit) {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    var asrUrl by remember { mutableStateOf(CfgLocal.load(ctx, "asr_url")) }
    var ttsUrl by remember { mutableStateOf(CfgLocal.load(ctx, "tts_url")) }
    var busy by remember { mutableStateOf("") }
    var msg by remember { mutableStateOf("") }
    var pct by remember { mutableStateOf(0) }
    var tick by remember { mutableStateOf(0) }
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
                val why = when (e) {
                    is java.net.UnknownHostException -> "域名解析失败（网络或 DNS 问题），可以换网络或换镜像地址"
                    is java.net.SocketTimeoutException -> "连接超时，换个地址或稍后重试"
                    else -> e.message ?: e.toString()
                }
                msg = "$name 失败：$why"
            } finally {
                busy = ""
                tick++
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
            "模型较大，建议接 WiFi。中断后再点一次会断点续传。",
            fontSize = 12.sp, color = Color(0xFF8A93A0)
        )

        // ---------- 语音识别 ----------
        Text("语音识别", fontSize = 15.sp, color = Color(0xFFE8EEF5))
        run {
            val n = ModelIO.onnxCount(ctx, "asr")
            Text(
                if (n > 0) "已就绪 · ${ModelIO.sizeOf(ctx, "asr") / 1024 / 1024} MB" else "未下载",
                fontSize = 12.sp,
                color = if (n > 0) Color(0xFF6FD08C) else Color(0xFF8A93A0)
            )
        }
        OutlinedTextField(
            value = asrUrl,
            onValueChange = { asrUrl = it; CfgLocal.save(ctx, "asr_url", it) },
            label = { Text("模型下载地址（.tar.bz2）") },
            maxLines = 4,
            modifier = Modifier.fillMaxWidth()
        )
        Button(
            onClick = { run("asr", asrUrl) },
            enabled = busy.isEmpty(),
            modifier = Modifier.fillMaxWidth()
        ) { Text(if (busy == "asr") "下载中 $pct%" else "下载语音识别模型") }

        HorizontalDivider()

        // ---------- 语音合成 ----------
        Text("语音合成", fontSize = 15.sp, color = Color(0xFFE8EEF5))
        run {
            val n = ModelIO.onnxCount(ctx, "tts")
            Text(
                if (n > 0) "已就绪 · ${ModelIO.sizeOf(ctx, "tts") / 1024 / 1024} MB" else "未下载",
                fontSize = 12.sp,
                color = if (n > 0) Color(0xFF6FD08C) else Color(0xFF8A93A0)
            )
        }
        OutlinedTextField(
            value = ttsUrl,
            onValueChange = { ttsUrl = it; CfgLocal.save(ctx, "tts_url", it) },
            label = { Text("模型下载地址（.tar.bz2）") },
            maxLines = 4,
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
        Spacer(Modifier.height(4.dp))
        Text("tip：地址可填镜像，例如 https://ghfast.top/ 加在原地址前面",
            fontSize = 11.sp, color = Color(0xFF6E7681))
    }
}

/** 极简键值存储 */
object CfgLocal {
    fun load(ctx: Context, k: String): String =
        ctx.getSharedPreferences("modelcfg", Context.MODE_PRIVATE).getString(k, "") ?: ""
    fun save(ctx: Context, k: String, v: String) {
        ctx.getSharedPreferences("modelcfg", Context.MODE_PRIVATE).edit().putString(k, v).apply()
    }
}
