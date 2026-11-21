package com.example.hello

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import java.io.ByteArrayOutputStream
import java.util.zip.ZipInputStream

/**
 * 读取用户选中的文件并转成纯文本。
 * 支持：文本类（txt/md/json/csv/代码等）、Office 2007+（docx/xlsx/pptx）。
 * 不依赖任何第三方库（Office 本质是 zip + XML）。
 */
object FileRead {

    const val MAX_CHARS = 20000
    private const val MAX_BYTES = 8 * 1024 * 1024

    data class Result(val name: String, val text: String)

    fun read(ctx: Context, uri: Uri): Result {
        val name = queryName(ctx, uri) ?: "file"
        val bytes = readBytes(ctx, uri)
        val lower = name.lowercase()

        val raw = when {
            lower.endsWith(".docx") || lower.endsWith(".xlsx") || lower.endsWith(".pptx") -> fromOfficeZip(bytes)
            lower.endsWith(".zip") -> "（暂不支持 zip 压缩包，请解压后单独发送）"
            else -> tryText(bytes)
        }

        val text = raw.trim()
        if (text.isEmpty()) throw IllegalStateException("文件里没有可读文本")
        val clipped = if (text.length > MAX_CHARS) text.take(MAX_CHARS) + "\n…（内容过长已截断）" else text
        return Result(name, clipped)
    }

    private fun readBytes(ctx: Context, uri: Uri): ByteArray {
        ctx.contentResolver.openInputStream(uri)?.use { input ->
            val out = ByteArrayOutputStream()
            val buf = ByteArray(32 * 1024)
            var total = 0
            while (true) {
                val n = input.read(buf)
                if (n <= 0) break
                out.write(buf, 0, n)
                total += n
                if (total > MAX_BYTES) break
            }
            return out.toByteArray()
        }
        throw IllegalStateException("无法打开文件")
    }

    private fun queryName(ctx: Context, uri: Uri): String? = runCatching {
        ctx.contentResolver.query(uri, null, null, null, null)?.use { c ->
            val idx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (idx >= 0 && c.moveToFirst()) c.getString(idx) else null
        }
    }.getOrNull()

    /** docx/xlsx/pptx：解 zip，把 XML 里的文字剥出来 */
    private fun fromOfficeZip(bytes: ByteArray): String {
        val sb = StringBuilder()
        ZipInputStream(bytes.inputStream()).use { zip ->
            var entry = zip.nextEntry
            while (entry != null) {
                val n = entry.name
                val interesting = n.endsWith(".xml") &&
                    (n.contains("document") || n.contains("sharedStrings") ||
                        n.contains("sheet") || n.contains("slide") ||
                        n.startsWith("word/") || n.startsWith("xl/") || n.startsWith("ppt/"))
                if (interesting && !n.contains("styles") && !n.contains("theme")) {
                    val xml = zip.readBytes().toString(Charsets.UTF_8)
                    sb.append(stripXml(xml)).append('\n')
                }
                zip.closeEntry()
                entry = zip.nextEntry
                if (sb.length > MAX_CHARS * 2) break
            }
        }
        return sb.toString()
    }

    /** 去掉标签，保留文字；w:p / a:p 视为换行 */
    private fun stripXml(xml: String): String {
        var s = xml
        s = s.replace(Regex("<w:p[ >]"), "\n<w:p ")
        s = s.replace(Regex("<a:p[ >]"), "\n<a:p ")
        s = s.replace(Regex("<w:br\\s*/>"), "\n")
        s = s.replace(Regex("<[^>]+>"), "")
        s = s.replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">")
        s = s.replace("&quot;", "\"").replace("&apos;", "'")
        return s.replace(Regex("\n{3,}"), "\n\n").trim()
    }

    /** 普通文件：按 UTF-8 解，替换符太多则认为是二进制 */
    private fun tryText(bytes: ByteArray): String {
        val text = bytes.toString(Charsets.UTF_8)
        val bad = text.count { it == '\uFFFD' }
        if (text.isNotEmpty() && bad * 20 > text.length) {
            throw IllegalStateException("这是二进制文件（图片请用 📷，PDF 暂不支持）")
        }
        return text.filter { it == '\n' || it == '\t' || it >= ' ' }
    }
}
