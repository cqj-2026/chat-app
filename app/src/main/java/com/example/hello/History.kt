package com.example.hello

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Base64
import org.json.JSONObject
import java.io.File

/**
 * 极简对话历史：一行一条 JSON（chat.jsonl），图片单独存文件。
 * 不引入 Room / DataStore，避免多一个依赖和一个编译风险点。
 */
class History(private val ctx: Context) {

    private val imgDir: File = File(ctx.filesDir, "img").apply { mkdirs() }
    private val chatFile: File = File(ctx.filesDir, "chat.jsonl")

    /** 把 data URL 落盘，返回文件名 */
    fun saveImage(dataUrl: String): String? = runCatching {
        val b64 = dataUrl.substringAfter("base64,", "")
        if (b64.isEmpty()) return null
        val bytes = Base64.decode(b64, Base64.NO_WRAP)
        if (bytes.isEmpty()) return null
        val name = "img_" + System.currentTimeMillis() + ".jpg"
        File(imgDir, name).writeBytes(bytes)
        name
    }.getOrNull()

    /** 从文件重建 data URL（用于再次发送） */
    fun dataUrlOf(name: String): String? = runCatching {
        val f = File(imgDir, name)
        if (!f.exists()) return null
        "data:image/jpeg;base64," + Base64.encodeToString(f.readBytes(), Base64.NO_WRAP)
    }.getOrNull()

    /** 从文件取缩略图（用于气泡显示） */
    fun thumbOf(name: String): Bitmap? = runCatching {
        val f = File(imgDir, name)
        if (!f.exists()) return null
        BitmapFactory.decodeFile(f.absolutePath)
    }.getOrNull()

    fun load(): List<Msg> = runCatching {
        if (!chatFile.exists()) return emptyList()
        chatFile.readLines().mapNotNull { line ->
            if (line.isBlank()) return@mapNotNull null
            val o = runCatching { JSONObject(line) }.getOrNull() ?: return@mapNotNull null
            val name = o.optString("img", "")
            Msg(
                role = o.optString("role", "user"),
                text = o.optString("text", ""),
                imageUrl = if (name.isNotEmpty()) dataUrlOf(name) else null,
                thumb = if (name.isNotEmpty()) thumbOf(name) else null,
                imgFile = if (name.isNotEmpty()) name else null,
                isErr = o.optBoolean("err", false)
            )
        }
    }.getOrDefault(emptyList())

    fun append(m: Msg) {
        runCatching {
            val o = JSONObject()
                .put("role", m.role)
                .put("text", m.text)
                .put("err", m.isErr)
            if (!m.imgFile.isNullOrEmpty()) o.put("img", m.imgFile)
            chatFile.appendText(o.toString() + "\n")
        }
    }

    fun clear() {
        runCatching { if (chatFile.exists()) chatFile.delete() }
        runCatching { imgDir.listFiles()?.forEach { it.delete() } }
    }
}
