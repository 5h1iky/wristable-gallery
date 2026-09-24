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
 * - GET  /            → 内置网页端（拖拽上传 / 文件列表 / 下载）
 * - GET  /api/list    → JSON 文件列表（图片/视频/互传下载）
 * - GET  /api/file    → 流式下载（?kind=image|video|download&id=&name=）
 * - POST /api/upload  → 原始流上传（?name=，网页 fetch/XHR 直接传 File）
 *
 * 保存策略（版本分支）：
 * - API 29+：MediaStore.Downloads 插入 /Downloads/腕能图库
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
        Thread({
            try {
                server = ServerSocket().apply {
                    reuseAddress = true
                    bind(InetSocketAddress(port))
                }
                running = true
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
            val uri = URI(parts[1])
            val path = uri.path ?: "/"
            val query = parseQuery(uri.rawQuery)

            try {
                when {
                    method == "GET" && (path == "/" || path == "/index.html") -> sendRaw(output, 200, "OK", "text/html; charset=utf-8", HTML.toByteArray())
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

    // ─────────── 列表 ───────────

    private fun sendList(output: OutputStream) {
        val arr = JSONArray()
        if (Build.VERSION.SDK_INT >= 29) {
            // 图片
            collectMedia(output = null, arr = arr, collection = MediaStore.Images.Media.EXTERNAL_CONTENT_URI, kind = "image",
                cols = arrayOf(MediaStore.Images.Media._ID, MediaStore.Images.Media.DISPLAY_NAME, MediaStore.Images.Media.SIZE, MediaStore.Images.Media.DATE_ADDED))
            collectMedia(null, arr, MediaStore.Video.Media.EXTERNAL_CONTENT_URI, "video",
                arrayOf(MediaStore.Video.Media._ID, MediaStore.Video.Media.DISPLAY_NAME, MediaStore.Video.Media.SIZE, MediaStore.Video.Media.DATE_ADDED))
            collectMedia(null, arr, MediaStore.Downloads.EXTERNAL_CONTENT_URI, "download",
                arrayOf(MediaStore.Downloads._ID, MediaStore.Downloads.DISPLAY_NAME, MediaStore.Downloads.SIZE, MediaStore.Downloads.DATE_ADDED))
        } else {
            collectMedia(null, arr, MediaStore.Images.Media.EXTERNAL_CONTENT_URI, "image",
                arrayOf(MediaStore.Images.Media._ID, MediaStore.Images.Media.DISPLAY_NAME, MediaStore.Images.Media.SIZE, MediaStore.Images.Media.DATE_ADDED))
            collectMedia(null, arr, MediaStore.Video.Media.EXTERNAL_CONTENT_URI, "video",
                arrayOf(MediaStore.Video.Media._ID, MediaStore.Video.Media.DISPLAY_NAME, MediaStore.Video.Media.SIZE, MediaStore.Video.Media.DATE_ADDED))
            // ≤28：互传文件夹按文件列
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

    private fun collectMedia(
        output: OutputStream?, arr: JSONArray,
        collection: android.net.Uri, kind: String, cols: Array<String>
    ) {
        try {
            // 注意：sortOrder 不能拼 LIMIT（新 MediaProvider 会拒绝），上限在循环里控制
            context.contentResolver.query(collection, cols, null, null, "${cols[3]} DESC")?.use { c ->
                val iId = c.getColumnIndexOrThrow(cols[0])
                val iName = c.getColumnIndexOrThrow(cols[1])
                val iSize = c.getColumnIndexOrThrow(cols[2])
                val iDate = c.getColumnIndexOrThrow(cols[3])
                var n = 0
                while (c.moveToNext() && n < 300) {
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
        } catch (_: Exception) {
        }
    }

    // ─────────── 下载 ───────────

    private fun sendFile(output: OutputStream, query: Map<String, String>) {
        val kind = query["kind"] ?: "image"
        when (kind) {
            "download" -> {
                if (Build.VERSION.SDK_INT >= 29) {
                    val id = query["id"]?.toLongOrNull() ?: return sendRaw(output, 400, "Bad", "text/plain", "bad id".toByteArray())
                    streamUri(output, MediaStore.Downloads.EXTERNAL_CONTENT_URI, id, query["name"] ?: "file")
                } else {
                    val name = query["name"] ?: return sendRaw(output, 400, "Bad", "text/plain", "bad name".toByteArray())
                    val f = File(sharedDir(), sanitize(name))
                    if (!f.exists()) return sendRaw(output, 404, "NF", "text/plain", "no file".toByteArray())
                    sendRawHead(output, 200, "OK", mimeOf(f.name), f.length(), f.name)
                    f.inputStream().use { it.copyTo(output) }
                }
            }
            "image" -> streamUri(output, MediaStore.Images.Media.EXTERNAL_CONTENT_URI, query["id"]?.toLongOrNull() ?: -1, query["name"] ?: "img")
            "video" -> streamUri(output, MediaStore.Video.Media.EXTERNAL_CONTENT_URI, query["id"]?.toLongOrNull() ?: -1, query["name"] ?: "vid")
            else -> sendRaw(output, 404, "NF", "text/plain", "bad kind".toByteArray())
        }
    }

    private fun streamUri(output: OutputStream, base: android.net.Uri, id: Long, name: String) {
        if (id < 0) return sendRaw(output, 400, "Bad", "text/plain", "bad id".toByteArray())
        val uri = android.content.ContentUris.withAppendedId(base, id)
        val fd = context.contentResolver.openAssetFileDescriptor(uri, "r")
            ?: return sendRaw(output, 404, "NF", "text/plain", "no file".toByteArray())
        val len = fd.length
        sendRawHead(output, 200, "OK", mimeOf(name), if (len >= 0) len else -1, name)
        fd.createInputStream().use { it.copyTo(output) }
    }

    // ─────────── 上传 ───────────

    private fun recvFile(input: InputStream, output: OutputStream, query: Map<String, String>, length: Int) {
        val name = sanitize(URLDecoder.decode(query["name"] ?: "file_${System.currentTimeMillis()}", "UTF-8"))
        if (Build.VERSION.SDK_INT >= 29) {
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, name)
                put(MediaStore.MediaColumns.MIME_TYPE, mimeOf(name))
                put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/腕能图库")
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
            val uri = context.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                ?: throw IOException("MediaStore 插入失败")
            context.contentResolver.openOutputStream(uri)?.use { out -> copyLimited(input, out, length) }
                ?: throw IOException("无法打开输出流")
            values.clear()
            values.put(MediaStore.MediaColumns.IS_PENDING, 0)
            context.contentResolver.update(uri, values, null, null)
        } else {
            val f = File(sharedDir(), name)
            f.outputStream().use { out -> copyLimited(input, out, length) }
            MediaScannerConnection.scanFile(context, arrayOf(f.absolutePath), null, null)
        }
        onLog("已接收：$name")
        sendRaw(output, 200, "OK", "application/json; charset=utf-8", """{"ok":true,"name":"$name"}""".toByteArray())
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

    private fun readLine(input: InputStream): String? {
        val sb = StringBuilder()
        while (true) {
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
        /** 内置网页端：深色主题，拖拽上传 + 列表下载，手机/电脑浏览器零安装 */
        val HTML: String = """
<!DOCTYPE html><html lang="zh"><head><meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1">
<title>腕能图库 · 互传</title><style>
body{background:#000;color:#fff;font-family:system-ui;margin:0;padding:16px}
h1{font-size:20px;margin:4px 0 14px}
.card{background:#1c1c1e;border-radius:16px;padding:16px;margin-bottom:12px}
#drop{border:2px dashed #4a4a4c;border-radius:12px;padding:28px;text-align:center;color:#9e9e9e}
#drop.over{border-color:#4FC3F7;color:#4FC3F7}
.row{display:flex;align-items:center;padding:10px 0;border-bottom:1px solid #2a2a2a}
.row:last-child{border-bottom:none}
.nm{flex:1;overflow:hidden;text-overflow:ellipsis;white-space:nowrap;font-size:14px}
.sz{color:#9e9e9e;font-size:12px;margin-right:10px}
a{color:#4FC3F7;text-decoration:none;font-size:14px}
#prog{color:#69F0AE;font-size:13px;min-height:18px}
</style></head><body>
<h1>腕能图库 · 互传</h1>
<div class="card"><div id="drop">拖文件到这里，或点击选择（传入手表）</div>
<input id="file" type="file" multiple style="display:none"><div id="prog"></div></div>
<div class="card"><b style="font-size:14px">手表文件</b><div id="list"></div></div>
<script>
var drop=document.getElementById('drop'),file=document.getElementById('file'),prog=document.getElementById('prog');
drop.onclick=function(){file.click()};
file.onchange=function(){up(file.files)};
['dragover','dragenter'].forEach(function(e){drop.addEventListener(e,function(ev){ev.preventDefault();drop.classList.add('over')})});
['dragleave','drop'].forEach(function(e){drop.addEventListener(e,function(ev){ev.preventDefault();drop.classList.remove('over')})});
drop.addEventListener('drop',function(ev){up(ev.dataTransfer.files)});
function up(files){for(var i=0;i<files.length;i++){one(files[i])}}
function one(f){var x=new XMLHttpRequest();x.open('POST','/api/upload?name='+encodeURIComponent(f.name));
x.upload.onprogress=function(ev){if(ev.lengthComputable)prog.textContent=f.name+' '+Math.round(ev.loaded/ev.total*100)+'%'};
x.onload=function(){prog.textContent=f.name+' 上传完成';list()};
x.onerror=function(){prog.textContent=f.name+' 上传失败'};
x.send(f)}
function fmt(n){var u=['B','KB','MB','GB'],i=0;while(n>1024&&i<3){n/=1024;i++}return n.toFixed(i?1:0)+u[i]}
function list(){fetch('/api/list').then(function(r){return r.json()}).then(function(a){
var h='';a.forEach(function(it){
h+='<div class="row"><span class="nm">'+it.name+'</span><span class="sz">'+fmt(it.size)+'</span>'+
'<a href="/api/file?kind='+it.kind+'&id='+(it.id||'')+'&name='+encodeURIComponent(it.name)+'">下载</a></div>'});
document.getElementById('list').innerHTML=h||'<div style="color:#9e9e9e;padding:10px 0">（暂无文件）</div>'})}
list();setInterval(list,5000);
</script></body></html>
""".trimIndent()
    }
}
