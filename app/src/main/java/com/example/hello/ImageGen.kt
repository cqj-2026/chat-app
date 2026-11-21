package com.example.hello

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * 文生图：调用 OpenAI 兼容的 /images/generations。
 * 支持两种返回：b64_json（主流）和 url（需要再下一次）。
 */
object ImageGen {

    private val http = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(180, TimeUnit.SECONDS)
        .build()

    fun ready(cfg: Cfg): Boolean =
        cfg.imgBase.isNotBlank() && cfg.imgKey.isNotBlank() && cfg.imgModel.isNotBlank()

    suspend fun generate(cfg: Cfg, prompt: String): Bitmap = withContext(Dispatchers.IO) {
        val payload = JSONObject()
            .put("model", cfg.imgModel)
            .put("prompt", prompt)
            .put("n", 1)
            .put("size", "1024x1024")
            .put("response_format", "b64_json")
            .toString()

        val req = Request.Builder()
            .url(cfg.imgBase.trimEnd('/') + "/images/generations")
            .addHeader("Authorization", "Bearer ${cfg.imgKey}")
            .addHeader("Content-Type", "application/json")
            .post(payload.toRequestBody("application/json".toMediaType()))
            .build()

        http.newCall(req).execute().use { resp ->
            val text = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) {
                throw IllegalStateException("HTTP ${resp.code}\n${text.take(300)}")
            }
            val data = JSONObject(text).getJSONArray("data").getJSONObject(0)
            if (!data.isNull("b64_json")) {
                val bytes = Base64.decode(data.getString("b64_json"), Base64.DEFAULT)
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                    ?: throw IllegalStateException("图片解码失败")
            } else {
                val url = data.optString("url")
                if (url.isEmpty()) throw IllegalStateException("响应里没有图片数据")
                val get = Request.Builder().url(url).build()
                http.newCall(get).execute().use { ir ->
                    val bs = ir.body?.bytes() ?: throw IllegalStateException("图片下载失败")
                    BitmapFactory.decodeByteArray(bs, 0, bs.size)
                        ?: throw IllegalStateException("图片解码失败")
                }
            }
        }
    }
}
