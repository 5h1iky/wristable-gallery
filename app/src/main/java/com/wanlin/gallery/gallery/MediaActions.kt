package com.wanlin.gallery.gallery

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.media.MediaScannerConnection
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import com.wanlin.gallery.R
import com.wanlin.gallery.data.MediaItem
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 相册长按菜单的媒体操作：
 * 复制副本 / 复制到剪贴板 / 分享 / 重命名 / 直删 / 详情。
 * [copy] 是 I/O，调用方放工作线程；其余方法可主线程调用。
 */
object MediaActions {

    /** 详情文本：文件名/类型/尺寸/大小/时长/时间/路径 */
    fun infoText(c: Context, it: MediaItem): String {
        val sb = StringBuilder()
        sb.append(c.getString(R.string.label_name, it.name)).append('\n')
        sb.append(c.getString(R.string.label_type, it.mime)).append('\n')
        if (it.width > 0 && it.height > 0) {
            sb.append(c.getString(R.string.label_dim, it.width, it.height)).append('\n')
        }
        sb.append(c.getString(R.string.label_size, fmtSize(it.size))).append('\n')
        if (it.isVideo && it.duration > 0) {
            sb.append(c.getString(R.string.label_duration, fmtDuration(it.duration))).append('\n')
        }
        sb.append(c.getString(R.string.label_time, fmtTime(it.date))).append('\n')
        val path = it.path.ifBlank { it.uri.toString() }
        sb.append(c.getString(R.string.label_path, path))
        return sb.toString()
    }

    /**
     * 复制副本到原目录，返回新文件名（失败 null）。
     * API 29+：MediaStore insert + RELATIVE_PATH（29 需 WRITE，30+ 免）；≤28：直写 + 媒体扫描。
     */
    fun copy(c: Context, it: MediaItem): String? = try {
        if (Build.VERSION.SDK_INT >= 29) copyByMediaStore(c, it) else copyLegacy(c, it)
    } catch (_: Exception) {
        null
    }

    private fun copyByMediaStore(c: Context, it: MediaItem): String? {
        var rel: String
        var parent: File? = null
        val src = it.path
        if (src.isNotBlank()) {
            val dir = File(src).parent ?: return null
            parent = File(dir)
            val root = Environment.getExternalStorageDirectory().absolutePath
            rel = dir.removePrefix(root).trimStart('/')
            if (rel.isNotEmpty() && !rel.endsWith("/")) rel += "/"
        } else {
            rel = ""
        }
        if (rel.isEmpty()) rel = if (it.isVideo) "Movies/" else "Pictures/"

        val name = uniqueName(parent, it.name)
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, it.mime)
            put(MediaStore.MediaColumns.RELATIVE_PATH, rel)
        }
        val col = if (it.isVideo) MediaStore.Video.Media.EXTERNAL_CONTENT_URI
        else MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        val dest = c.contentResolver.insert(col, values) ?: return null
        val out = try {
            c.contentResolver.openOutputStream(dest)
        } catch (_: Exception) {
            null
        }
        if (out == null) {
            runCatching { c.contentResolver.delete(dest, null, null) }
            return null
        }
        val inp = c.contentResolver.openInputStream(it.uri)
        if (inp == null) {
            out.close()
            runCatching { c.contentResolver.delete(dest, null, null) }
            return null
        }
        inp.use { i -> out.use { o -> i.copyTo(o) } }
        return name
    }

    private fun copyLegacy(c: Context, it: MediaItem): String? {
        if (it.path.isBlank()) return null
        val src = File(it.path)
        if (!src.exists()) return null
        val name = uniqueName(src.parentFile, src.name)
        val dst = File(src.parentFile, name)
        src.copyTo(dst, overwrite = false)
        MediaScannerConnection.scanFile(c, arrayOf(dst.absolutePath), arrayOf(it.mime), null)
        return name
    }

    /** 同目录重名时自动加 _1/_2… */
    private fun uniqueName(parent: File?, name: String): String {
        if (parent == null) return name
        val dot = name.lastIndexOf('.')
        val base = if (dot > 0) name.substring(0, dot) else name
        val ext = if (dot > 0) name.substring(dot) else ""
        var candidate = name
        var i = 1
        while (File(parent, candidate).exists() && i < 1000) {
            candidate = "${base}_$i$ext"
            i++
        }
        return candidate
    }

    /** 复制到剪贴板：主项为文件 URI（可粘贴图片），附带路径文本 */
    fun copyToClipboard(c: Context, it: MediaItem) {
        val cm = c.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val text = it.path.ifBlank { it.uri.toString() }
        val item = ClipData.Item(text, null, it.uri)
        cm.setPrimaryClip(ClipData("腕能图库", arrayOf(it.mime), item))
    }

    /** 系统分享面板（EXTRA_STREAM + 读授权） */
    fun share(a: Activity, it: MediaItem) {
        val send = Intent(Intent.ACTION_SEND).apply {
            type = it.mime
            putExtra(Intent.EXTRA_STREAM, it.uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        runCatching { a.startActivity(Intent.createChooser(send, null)) }
    }

    /** 重命名（改 MediaStore DISPLAY_NAME；30+ 对非自有文件可能抛 SecurityException） */
    fun rename(c: Context, it: MediaItem, newName: String): Boolean = try {
        val v = ContentValues().apply { put(MediaStore.MediaColumns.DISPLAY_NAME, newName) }
        c.contentResolver.update(it.uri, v, null, null) > 0
    } catch (_: Exception) {
        false
    }

    /** 直接删除：本应用自建文件、或 ≤29 已有写权限时可用 */
    fun deleteDirect(c: Context, it: MediaItem): Boolean = try {
        c.contentResolver.delete(it.uri, null, null) > 0
    } catch (_: Exception) {
        false
    }

    fun fmtSize(n: Long): String {
        var v = n.toFloat()
        val u = arrayOf("B", "KB", "MB", "GB")
        var i = 0
        while (v > 1024 && i < 3) {
            v /= 1024
            i++
        }
        return "%.1f%s".format(v, u[i])
    }

    fun fmtDuration(ms: Long): String {
        val s = ms / 1000
        return if (s >= 3600) "%d:%02d:%02d".format(s / 3600, s % 3600 / 60, s % 60)
        else "%d:%02d".format(s / 60, s % 60)
    }

    private fun fmtTime(t: Long): String =
        SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date(t))
}
