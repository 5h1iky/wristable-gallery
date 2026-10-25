package com.wanlin.gallery

import android.app.Application
import android.content.ContentValues
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import com.wanlin.gallery.ui.AccentManager
import com.wanlin.gallery.ui.ScaleManager
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** 应用入口：初始化全局 UI 缩放与点缀色主题，并安装崩溃日志兜底 */
class App : Application() {
    override fun onCreate() {
        super.onCreate()
        installCrashLog()
        ScaleManager.init(this)
        AccentManager.init(this)
    }

    /**
     * 崩溃兜底日志：把未捕获异常的完整堆栈写到用户可取回的位置
     * （优先 互传目录 Download/腕能图库，兼容外部私有目录），然后交还原处理器。
     * 手表等精简 ROM 出问题时，直接用互传网页下载日志即可定位。
     */
    private fun installCrashLog() {
        val default = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { t, e ->
            runCatching { writeCrashLog(t, e) }
            default?.uncaughtException(t, e)
        }
    }

    private fun writeCrashLog(t: Thread, e: Throwable) {
        val sw = StringWriter()
        e.printStackTrace(PrintWriter(sw))
        val stamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())
        val text = "time: $stamp\nthread: ${t.name}\nmodel: ${Build.MANUFACTURER} ${Build.MODEL} sdk=${Build.VERSION.SDK_INT}\n\n$sw"
        val name = "crash-${System.currentTimeMillis()}.log"
        // 1) 外部私有目录（免权限，一定能写）
        runCatching {
            File(getExternalFilesDir(null) ?: filesDir, name).writeText(text)
        }
        // 2) 互传目录直写（≤28 或已有存储权限时成功）
        runCatching {
            val dir = File(
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
                "腕能图库"
            )
            dir.mkdirs()
            File(dir, name).writeText(text)
        }
        // 3) API 29+：MediaStore 写 Downloads（免存储权限，runCatching 防怪 ROM 缺 API）
        runCatching {
            if (Build.VERSION.SDK_INT >= 29) {
                val values = ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, name)
                    put(MediaStore.MediaColumns.MIME_TYPE, "text/plain")
                    put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/腕能图库")
                }
                contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)?.let { uri ->
                    contentResolver.openOutputStream(uri)?.use { it.write(text.toByteArray()) }
                }
            }
        }
    }
}
