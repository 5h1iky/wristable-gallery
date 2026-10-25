package com.wanlin.gallery.ui

import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.Settings
import android.view.View
import android.widget.EditText
import android.widget.Toast
import androidx.core.content.FileProvider
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.wanlin.gallery.R
import com.wanlin.gallery.base.BaseActivity
import com.wanlin.gallery.data.FileNode
import com.wanlin.gallery.databinding.ActivityFileManagerBinding
import com.wanlin.gallery.util.AppDialogs
import com.wanlin.gallery.util.IconMenu
import com.wanlin.gallery.util.ScreenUtils
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 全后缀文件浏览器（P2 重做）：
 * /sdcard 起目录树浏览，列出任意后缀文件/文件夹，
 * 点击按 MIME 调系统打开，长按删除/重命名/详情。
 */
class FileManagerActivity : BaseActivity() {

    private lateinit var binding: ActivityFileManagerBinding
    private lateinit var adapter: FileAdapter
    private lateinit var rootDir: File
    private var currentDir: File? = null
    private var loadedOnce = false
    private var pausedOnce = false
    private var navSeq = 0

    private val permLauncher =
        registerForActivityResult(androidx.activity.result.contract.ActivityResultContracts.RequestMultiplePermissions()) {
            if (hasStoragePermission()) load() else showDenied()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityFileManagerBinding.inflate(layoutInflater)
        setContentView(binding.root)
        val inset = ScreenUtils.safeInsetPx(this)
        binding.root.setPadding(inset, binding.root.paddingTop, inset, binding.root.paddingBottom)

        rootDir = Environment.getExternalStorageDirectory()
        adapter = FileAdapter()
        binding.recycler.layoutManager = LinearLayoutManager(this)
        binding.recycler.adapter = adapter

        // 返回：先上一级，到根再退出
        binding.btnBack.setOnClickListener {
            val cur = currentDir
            if (cur != null && cur.absolutePath != rootDir.absolutePath) {
                navigateTo(cur.parentFile ?: rootDir)
            } else finish()
        }
        ensurePermissionThenLoad()
    }

    override fun onResume() {
        super.onResume()
        if (loadedOnce) return
        if (hasStoragePermission()) {
            load()
        } else if (pausedOnce && Build.VERSION.SDK_INT >= 30) {
            // 已去过授权页又回来了仍未授权（或 ROM 压根没打开授权页）→ 页面写提示，让用户手动去授权
            showDenied(R.string.all_files_unavailable)
        }
    }

    override fun onPause() {
        super.onPause()
        pausedOnce = true
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        val cur = currentDir
        if (cur != null && cur.absolutePath != rootDir.absolutePath) navigateTo(cur.parentFile ?: rootDir)
        else super.onBackPressed()
    }

    // ─────────── 权限 ───────────

    private fun hasStoragePermission(): Boolean =
        if (Build.VERSION.SDK_INT >= 30) isAllFilesGranted() else hasLegacyPermission()

    /**
     * Android 11+ 所有文件访问状态。
     * ⚠️ 手表 ROM 常见「标称安卓11、实际框架更旧」：直接调用 Environment.isExternalStorageManager()
     *（API 30 新增）会抛 NoSuchMethodError —— 它是 Error 不是 Exception，普通 try/catch 拦不住，
     * 表现就是点进本页直接闪退（手机正常）。这里改用反射 + Throwable 兜底，
     * 取不到该 API 就退回旧版存储权限判断，绝不因缺 API 崩溃。
     */
    private fun isAllFilesGranted(): Boolean = try {
        val m = Environment::class.java.getMethod("isExternalStorageManager")
        m.invoke(null) == true
    } catch (_: Throwable) {
        hasLegacyPermission()
    }

    private fun hasLegacyPermission(): Boolean =
        checkSelfPermission(android.Manifest.permission.READ_EXTERNAL_STORAGE) ==
                android.content.pm.PackageManager.PERMISSION_GRANTED

    private fun ensurePermissionThenLoad() {
        if (hasStoragePermission()) {
            load()
        } else if (Build.VERSION.SDK_INT >= 30) {
            // Android 11+：所有文件访问授权页。
            // ⚠️ 手表精简 ROM 可能两个授权页都不存在（第 10 轮实测手表点进本页直接闪退），
            // 全部 try/catch 兜底：跳不了就显示可重试的空态，绝不抛 ActivityNotFoundException。
            Toast.makeText(this, R.string.need_all_files, Toast.LENGTH_LONG).show()
            val jumped = runCatching {
                startActivity(
                    Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION)
                        .setData(android.net.Uri.parse("package:$packageName"))
                )
                true
            }.recoverCatching {
                startActivity(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION))
                true
            }.getOrDefault(false)
            if (!jumped) {
                showDenied(R.string.all_files_unavailable)
            } else {
                // 有些手表 ROM 跳转"成功"（不抛异常）但授权页根本没打开：
                // 1.2s 后仍停留本页且未授权 = 发现没跳走 → 就地提示用户手动授权
                binding.root.postDelayed({
                    if (!isFinishing && hasWindowFocus() && !hasStoragePermission()) {
                        showDenied(R.string.all_files_unavailable)
                    }
                }, 1200)
            }
        } else {
            permLauncher.launch(
                arrayOf(
                    android.Manifest.permission.READ_EXTERNAL_STORAGE,
                    android.Manifest.permission.WRITE_EXTERNAL_STORAGE
                )
            )
        }
    }

    private fun showDenied(msgRes: Int = R.string.need_permission) {
        binding.tvEmpty.visibility = View.VISIBLE
        binding.tvEmpty.text = getString(msgRes)
        binding.tvEmpty.setOnClickListener { ensurePermissionThenLoad() }
    }

    // ─────────── 浏览 ───────────

    private fun load() {
        navigateTo(rootDir)
    }

    private fun navigateTo(dir: File) {
        currentDir = dir
        loadedOnce = true
        binding.tvPath.text = pathLabel(dir)
        val seq = ++navSeq
        Thread {
            val children = dir.listFiles()?.map {
                FileNode(it, it.isDirectory, if (it.isDirectory) it.listFiles()?.size ?: 0 else 0)
            }.orEmpty()
            val sorted = children.sortedWith(compareByDescending<FileNode> { it.isDir }.thenBy { it.file.name.lowercase() })
            runOnUiThread {
                // 连续进出目录时丢弃过期结果，避免后发先至显示错目录
                if (seq != navSeq) return@runOnUiThread
                adapter.submit(sorted)
                binding.tvEmpty.visibility = if (sorted.isEmpty()) View.VISIBLE else View.GONE
            }
        }.start()
    }

    private fun pathLabel(dir: File): String {
        val p = dir.absolutePath
        return if (p == rootDir.absolutePath) "/sdcard" else "/sdcard" + p.removePrefix(rootDir.absolutePath)
    }

    // ─────────── 打开 / 操作 ───────────

    private fun openNode(node: FileNode) {
        if (node.isDir) {
            navigateTo(node.file)
            return
        }
        try {
            val uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", node.file)
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, mimeOf(node.file.name))
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            runCatching { startActivity(intent) }.onFailure {
                Toast.makeText(this, R.string.no_app_open, Toast.LENGTH_LONG).show()
            }
        } catch (_: Exception) {
            Toast.makeText(this, R.string.op_failed, Toast.LENGTH_SHORT).show()
        }
    }

    private fun showMenu(node: FileNode) {
        IconMenu.show(this, node.file.name, listOf(
            IconMenu.Cell(R.drawable.ic_edit, R.string.rename) { renameNode(node) },
            IconMenu.Cell(R.drawable.ic_delete, R.string.delete, danger = true) { deleteNode(node) },
            IconMenu.Cell(R.drawable.ic_info, R.string.file_info) { showInfo(node) }
        ))
    }

    private fun renameNode(node: FileNode) {
        AppDialogs.input(this, getString(R.string.rename), node.file.name) { name ->
            val ok = if (name.any { it in "/\\:*?\"<>|" }) false
            else node.file.renameTo(File(node.file.parentFile, name))
            Toast.makeText(this, if (ok) R.string.rename_ok else R.string.op_failed, Toast.LENGTH_SHORT).show()
            refresh()
        }
    }

    private fun deleteNode(node: FileNode) {
        AppDialogs.confirm(
            this, getString(R.string.delete), node.file.name,
            getString(R.string.delete), danger = true
        ) {
            val ok = if (node.isDir) node.file.deleteRecursively() else node.file.delete()
            Toast.makeText(this, if (ok) R.string.delete_ok else R.string.op_failed, Toast.LENGTH_SHORT).show()
            refresh()
        }
    }

    private fun showInfo(node: FileNode) {
        val fmt = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
        val size = if (node.isDir) node.childCount else node.file.length()
        val msg = if (node.isDir) {
            getString(R.string.info_dir, node.file.absolutePath, size, fmt.format(Date(node.file.lastModified())))
        } else {
            getString(
                R.string.info_file, node.file.absolutePath, node.ext,
                fmtSize(node.file.length()), fmt.format(Date(node.file.lastModified()))
            )
        }
        AppDialogs.info(this, node.file.name, msg)
    }

    private fun refresh() {
        currentDir?.let { navigateTo(it) }
    }

    private fun mimeOf(name: String): String {
        val ext = name.substringAfterLast('.', "").lowercase()
        return android.webkit.MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext) ?: "*/*"
    }

    private fun fmtSize(n: Long): String = com.wanlin.gallery.gallery.MediaActions.fmtSize(n)

    // ─────────── 列表 ───────────

    inner class FileAdapter : RecyclerView.Adapter<FileVH>() {
        private val items = ArrayList<FileNode>()
        fun submit(list: List<FileNode>) {
            items.clear(); items.addAll(list); notifyDataSetChanged()
        }

        override fun getItemCount() = items.size

        override fun onCreateViewHolder(parent: android.view.ViewGroup, viewType: Int) =
            FileVH(layoutInflater.inflate(R.layout.item_file_row, parent, false))

        override fun onBindViewHolder(holder: FileVH, position: Int) = holder.bind(items[position])
    }

    inner class FileVH(v: View) : RecyclerView.ViewHolder(v) {
        private val icon: android.widget.ImageView = v.findViewById(R.id.ivIcon)
        private val name: android.widget.TextView = v.findViewById(R.id.tvName)
        private val meta: android.widget.TextView = v.findViewById(R.id.tvMeta)

        fun bind(node: FileNode) {
            name.text = node.file.name
            if (node.isDir) {
                icon.setImageResource(R.drawable.ic_folder)
                meta.text = getString(R.string.meta_dir, node.childCount)
            } else {
                icon.setImageResource(R.drawable.ic_file)
                val fmt = SimpleDateFormat("MM-dd", Locale.getDefault())
                meta.text = getString(
                    R.string.meta_file, node.ext, fmtSize(node.file.length()),
                    fmt.format(Date(node.file.lastModified()))
                )
            }
            icon.setColorFilter(com.wanlin.gallery.ui.AccentManager.color())
            itemView.setOnClickListener { openNode(node) }
            itemView.setOnLongClickListener { showMenu(node); true }
        }
    }
}
