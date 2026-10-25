package com.wanlin.gallery.transfer

import android.content.ContentValues
import android.content.Context
import android.media.MediaScannerConnection
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.webkit.MimeTypeMap
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URI
import java.net.URLDecoder
import kotlin.math.min

/**
 * 零依赖迷你 HTTP 文件服务（同一 WiFi 手机/电脑浏览器互传）：
 * - GET  /            → 网页端（assets/web/index.html：拖拽上传 / 分类列表 / 下载）
 * - GET  /api/list    → JSON 文件列表（图片/视频/音乐/互传文件夹全部类型）
 * - GET  /api/file    → 流式下载（?kind=image|video|audio|download&id=&name=）
 * - POST /api/upload  → 原始流上传（?name=，任意文件类型）
 *
 * 列表分类：
 * - image / video / audio → MediaStore 对应集合
 * - download → 互传目录 Download/腕能图库（任意后缀：pdf/txt/zip/音乐…全收）
 *   · API 29+：MediaStore.Downloads 查询
 *   · API ≤28：直接文件列目录
 *
 * 保存策略（版本分支）：
 * - API 29+：MediaStore.Downloads 插入 /Downloads/腕能图库（写入期 IS_PENDING 保护）
 * - API 24~28：直写公共 Downloads/腕能图库（需 WRITE_EXTERNAL_STORAGE，manifest 已加 maxSdk28）
 */
class HttpFileServer(
    private val context: Context,
    private val port: Int,
    private val onLog: (String) -> Unit = {}
) {
    @Volatile
    var running = false
        private set

    private var server: ServerSocket? = null

    fun start() {
        if (running) return
        running = true
        Thread({
            try {
                server = ServerSocket().apply {
                    reuseAddress = true
                    bind(InetSocketAddress(port))
                }
                // stop() 可能发生在 bind 之前：此时直接关掉，不留孤儿服务
                if (!running) {
                    runCatching { server?.close() }
                    return@Thread
                }
                onLog("服务已启动，端口 $port")
                while (running) {
                    val sock = server?.accept() ?: break
                    Thread({ runCatching { handle(sock) } }).start()
                }
            } catch (e: Exception) {
                if (running) onLog("服务异常：${e.message}")
                running = false
            }
        }, "wanlin-http").start()
    }

    fun stop() {
        running = false
        runCatching { server?.close() }
        onLog("服务已停止")
    }

    // ─────────── 请求分发 ───────────

    private fun handle(sock: Socket) {
        sock.use { s ->
            val input = s.getInputStream().buffered()
            val output = s.getOutputStream()
            val requestLine = readLine(input) ?: return
            val parts = requestLine.split(" ")
            if (parts.size < 2) return
            val method = parts[0]
            var contentLength = 0
            while (true) {
                val h = readLine(input) ?: break
                if (h.isEmpty()) break
                if (h.startsWith("Content-Length:", ignoreCase = true)) {
                    contentLength = h.substringAfter(":").trim().toIntOrNull() ?: 0
                }
            }
            try {
                val uri = URI(parts[1])
                val path = uri.path ?: "/"
                val query = parseQuery(uri.rawQuery)
                when {
                    method == "GET" && (path == "/" || path == "/index.html") ->
                        sendRaw(output, 200, "OK", "text/html; charset=utf-8", webPage.toByteArray())
                    method == "GET" && path == "/api/list" -> sendList(output)
                    method == "GET" && path == "/api/file" -> sendFile(output, query)
                    method == "POST" && path == "/api/upload" -> recvFile(input, output, query, contentLength)
                    else -> sendRaw(output, 404, "Not Found", "text/plain; charset=utf-8", "not found".toByteArray())
                }
            } catch (e: Exception) {
                runCatching {
                    sendRaw(output, 500, "Error", "text/plain; charset=utf-8", ("error: ${e.message}").toByteArray())
                }
            }
        }
    }

    /** 网页端：assets 里的完整版，读不到时降级到内置极简页 */
    private val webPage: String by lazy {
        runCatching {
            context.assets.open("web/index.html").bufferedReader().use { it.readText() }
        }.getOrDefault(FALLBACK_HTML)
    }

    // ─────────── 列表 ───────────

    private fun sendList(output: OutputStream) {
        val arr = JSONArray()
        collectMedia(arr, MediaStore.Images.Media.EXTERNAL_CONTENT_URI, "image",
            arrayOf(MediaStore.Images.Media._ID, MediaStore.Images.Media.DISPLAY_NAME,
                MediaStore.Images.Media.SIZE, MediaStore.Images.Media.DATE_ADDED))
        collectMedia(arr, MediaStore.Video.Media.EXTERNAL_CONTENT_URI, "video",
            arrayOf(MediaStore.Video.Media._ID, MediaStore.Video.Media.DISPLAY_NAME,
                MediaStore.Video.Media.SIZE, MediaStore.Video.Media.DATE_ADDED))
        collectMedia(arr, MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, "audio",
            arrayOf(MediaStore.Audio.Media._ID, MediaStore.Audio.Media.DISPLAY_NAME,
                MediaStore.Audio.Media.SIZE, MediaStore.Audio.Media.DATE_ADDED))
        if (Build.VERSION.SDK_INT >= 29) {
            // 互传目录（任意后缀）：pdf/txt/doc/zip/音乐文件……全类型都在 Downloads 集合里
            collectMedia(arr, MediaStore.Downloads.EXTERNAL_CONTENT_URI, "download",
                arrayOf(MediaStore.Downloads._ID, MediaStore.Downloads.DISPLAY_NAME,
                    MediaStore.Downloads.SIZE, MediaStore.Downloads.DATE_ADDED))
        } else {
            for (f in sharedDir().listFiles().orEmpty()) {
                if (!f.isFile) continue
                val o = JSONObject()
                o.put("kind", "download")
                o.put("name", f.name)
                o.put("size", f.length())
                o.put("date", f.lastModified() / 1000)
                arr.put(o)
            }
        }
        sendRaw(output, 200, "OK", "application/json; charset=utf-8", arr.toString().toByteArray())
    }

    private fun collectMedia(arr: JSONArray, collection: android.net.Uri, kind: String, cols: Array<String>) {
        try {
            // 注意：sortOrder 不能拼 LIMIT（新 MediaProvider 会拒绝），上限在循环里控制；
            // Throwable 兜底：部分授权 / 怪 ROM 缺 API（NoSuchFieldError）都只影响本类目
            context.contentResolver.query(collection, cols, null, null, "${cols[3]} DESC")?.use { c ->
                val iId = c.getColumnIndexOrThrow(cols[0])
                val iName = c.getColumnIndexOrThrow(cols[1])
                val iSize = c.getColumnIndexOrThrow(cols[2])
                val iDate = c.getColumnIndexOrThrow(cols[3])
                var n = 0
                while (c.moveToNext() && n < 500) {
                    val o = JSONObject()
                    o.put("kind", kind)
                    o.put("id", c.getLong(iId))
                    o.put("name", c.getString(iName) ?: "${kind}_${c.getLong(iId)}")
                    o.put("size", c.getLong(iSize))
                    o.put("date", c.getLong(iDate))
                    arr.put(o)
                    n++
                }
            }
        } catch (_: Throwable) {
        }
    }

    // ─────────── 下载 ───────────

    private fun sendFile(output: OutputStream, query: Map<String, String>) {
        val kind = query["kind"] ?: "image"
        when (kind) {
            "image" -> streamUri(output, MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                query["id"]?.toLongOrNull() ?: -1, query["name"] ?: "img")
            "video" -> streamUri(output, MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
                query["id"]?.toLongOrNull() ?: -1, query["name"] ?: "vid")
            "audio" -> streamUri(output, MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
                query["id"]?.toLongOrNull() ?: -1, query["name"] ?: "aud")
            "download" -> {
                if (Build.VERSION.SDK_INT >= 29) {
                    val id = query["id"]?.toLongOrNull()
                        ?: return sendRaw(output, 400, "Bad", "text/plain", "bad id".toByteArray())
                    streamUri(output, MediaStore.Downloads.EXTERNAL_CONTENT_URI, id, query["name"] ?: "file")
                } else {
                    val name = query["name"]
                        ?: return sendRaw(output, 400, "Bad", "text/plain", "bad name".toByteArray())
                    val f = File(sharedDir(), sanitize(name))
                    if (!f.exists()) return sendRaw(output, 404, "NF", "text/plain", "no file".toByteArray())
                    sendRawHead(output, 200, "OK", mimeOf(f.name), f.length(), f.name)
                    f.inputStream().use { it.copyTo(output) }
                }
            }
            else -> sendRaw(output, 404, "NF", "text/plain", "bad kind".toByteArray())
        }
    }

    private fun streamUri(output: OutputStream, base: android.net.Uri, id: Long, name: String) {
        if (id < 0) return sendRaw(output, 400, "Bad", "text/plain", "bad id".toByteArray())
        val uri = android.content.ContentUris.withAppendedId(base, id)
        val fd = try {
            context.contentResolver.openAssetFileDescriptor(uri, "r")
        } catch (_: Throwable) {
            null
        } ?: return sendRaw(output, 404, "NF", "text/plain", "no file".toByteArray())
        val len = fd.length
        sendRawHead(output, 200, "OK", mimeOf(name), if (len >= 0) len else -1, name)
        fd.createInputStream().use { it.copyTo(output) }
    }

    // ─────────── 上传 ───────────

    private fun recvFile(input: InputStream, output: OutputStream, query: Map<String, String>, length: Int) {
        // query 的值已在 parseQuery 解过一次码，这里不能再解（含 % 的文件名会直接抛异常）
        val name = sanitize(query["name"] ?: "file_${System.currentTimeMillis()}")
        if (Build.VERSION.SDK_INT >= 29) {
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, name)
                put(MediaStore.MediaColumns.MIME_TYPE, mimeOf(name))
                put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/腕能图库")
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
            val uri = context.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                ?: throw IOException("MediaStore 插入失败")
            try {
                val out = context.contentResolver.openOutputStream(uri)
                    ?: throw IOException("无法打开输出流")
                out.use { copyLimited(input, it, length) }
                values.clear()
                values.put(MediaStore.MediaColumns.IS_PENDING, 0)
                context.contentResolver.update(uri, values, null, null)
            } catch (e: Exception) {
                // 写入失败：删掉半截条目，别在相册/下载里留垃圾
                runCatching { context.contentResolver.delete(uri, null, null) }
                throw e
            }
        } else {
            val f = File(sharedDir(), name)
            f.outputStream().use { out -> copyLimited(input, out, length) }
            MediaScannerConnection.scanFile(context, arrayOf(f.absolutePath), null, null)
        }
        onLog("已接收：$name")
        val json = JSONObject().put("ok", true).put("name", name)
        sendRaw(output, 200, "OK", "application/json; charset=utf-8", json.toString().toByteArray())
    }

    private fun sharedDir(): File =
        File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "腕能图库").apply { mkdirs() }

    // ─────────── HTTP 输出 ───────────

    private fun sendRaw(output: OutputStream, code: Int, msg: String, ctype: String, body: ByteArray) {
        sendRawHead(output, code, msg, ctype, body.size.toLong(), null)
        output.write(body)
        output.flush()
    }

    private fun sendRawHead(output: OutputStream, code: Int, msg: String, ctype: String, len: Long, filename: String?) {
        val sb = StringBuilder()
        sb.append("HTTP/1.1 $code $msg\r\n")
        sb.append("Content-Type: $ctype\r\n")
        if (len >= 0) sb.append("Content-Length: $len\r\n")
        if (filename != null) {
            sb.append("Content-Disposition: attachment; filename*=UTF-8''")
                .append(java.net.URLEncoder.encode(filename, "UTF-8").replace("+", "%20")).append("\r\n")
        }
        sb.append("Connection: close\r\n\r\n")
        output.write(sb.toString().toByteArray())
        output.flush()
    }

    // ─────────── 工具 ───────────

    /** 读一行 HTTP 头（限长防恶意大行撑爆内存） */
    private fun readLine(input: InputStream): String? {
        val sb = StringBuilder()
        while (sb.length < 8192) {
            val b = input.read()
            if (b == -1) return if (sb.isEmpty()) null else sb.toString()
            if (b == '\n'.code) break
            if (b != '\r'.code) sb.append(b.toChar())
        }
        return sb.toString()
    }

    private fun parseQuery(raw: String?): Map<String, String> {
        if (raw.isNullOrEmpty()) return emptyMap()
        return raw.split("&").mapNotNull {
            val i = it.indexOf('=')
            if (i <= 0) null
            else it.substring(0, i) to URLDecoder.decode(it.substring(i + 1), "UTF-8")
        }.toMap()
    }

    private fun copyLimited(input: InputStream, out: OutputStream, limit: Int) {
        val buf = ByteArray(64 * 1024)
        var remaining = if (limit > 0) limit else Int.MAX_VALUE
        while (remaining > 0) {
            val n = input.read(buf, 0, min(buf.size, remaining))
            if (n == -1) break
            out.write(buf, 0, n)
            remaining -= n
        }
        out.flush()
    }

    private fun sanitize(name: String): String =
        name.replace(Regex("[\\\\/:*?\"<>|]"), "_").take(120).ifEmpty { "file" }

    private fun mimeOf(name: String): String {
        val ext = name.substringAfterLast('.', "").lowercase()
        return MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext) ?: "application/octet-stream"
    }

    companion object {
        /** assets 读不到网页时的降级页 */
        val FALLBACK_HTML: String = """
<!DOCTYPE html><html lang="zh"><head><meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1">
<title>腕能图库 · 互传</title></head>
<body style="background:#000;color:#fff;font-family:system-ui;padding:16px">
<h2>腕能图库 · 互传</h2>
<p>网页资源缺失（assets/web/index.html），请重新安装应用。</p>
<p><a style="color:#4FC3F7" href="/api/list">/api/list</a></p>
</body></html>
""".trimIndent()
    }
}
