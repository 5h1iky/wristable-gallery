package com.wanlin.gallery.ui

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Typeface
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.view.View
import android.widget.EditText
import android.widget.Toast
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.GridLayoutManager
import com.wanlin.gallery.R
import com.wanlin.gallery.base.BaseActivity
import com.wanlin.gallery.data.MediaItem
import com.wanlin.gallery.databinding.ActivityGalleryBinding
import com.wanlin.gallery.gallery.MediaActions
import com.wanlin.gallery.gallery.MediaGridAdapter
import com.wanlin.gallery.gallery.MediaRepository
import com.wanlin.gallery.util.AppDialogs
import com.wanlin.gallery.util.IconMenu
import com.wanlin.gallery.util.ScreenUtils

/**
 * 相册页：3 列网格 + 日期分组 + 全部/图片/视频筛选。
 * 点图片 → 查看器；点视频 → 视频播放器；
 * 长按 → 详情/复制副本/复制到剪贴板/分享/重命名/删除。
 */
class GalleryActivity : BaseActivity() {

    private lateinit var binding: ActivityGalleryBinding
    private lateinit var adapter: MediaGridAdapter

    private var allItems = ArrayList<MediaItem>()   // 全量（已排序，含分组信息在 section 中）
    private var mode = MediaRepository.Mode.ALL

    // ── 长按菜单：系统删除（回收站）确认回调 / ≤29 写权限回调 ──
    private var pendingTrashIds: LongArray? = null
    private var pendingWriteAction: (() -> Unit)? = null

    private val permLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { grants ->
            if (grants.values.any { it }) load() else showDenied()
        }

    private val trashLauncher = registerForActivityResult(
        ActivityResultContracts.StartIntentSenderForResult()
    ) { res ->
        val ids = pendingTrashIds
        pendingTrashIds = null
        if (res.resultCode == RESULT_OK) {
            ids?.forEach { clearResumeOf(it) }
            Toast.makeText(this, R.string.delete_ok, Toast.LENGTH_SHORT).show()
            load()
        }
    }

    private val writePermLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { grants ->
            val act = pendingWriteAction
            pendingWriteAction = null
            if (grants[Manifest.permission.WRITE_EXTERNAL_STORAGE] == true) act?.invoke()
            else Toast.makeText(this, R.string.op_failed, Toast.LENGTH_SHORT).show()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityGalleryBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val inset = ScreenUtils.safeInsetPx(this)
        binding.root.setPadding(inset, binding.root.paddingTop, inset, binding.root.paddingBottom)

        mode = when (intent.getStringExtra(EXTRA_MODE)) {
            "VIDEOS" -> MediaRepository.Mode.VIDEOS
            "IMAGES" -> MediaRepository.Mode.IMAGES
            else -> MediaRepository.Mode.ALL
        }
        // 标题动态化：视频入口显示"视频"，其余显示"相册"（P2 反馈）
        binding.tvTitle.text = getString(
            if (mode == MediaRepository.Mode.VIDEOS) R.string.videos else R.string.gallery
        )

        binding.btnBack.setOnClickListener { finish() }

        adapter = MediaGridAdapter(onClick = { onItemClicked(it) }, onLongClick = { showItemMenu(it) })
        binding.recycler.layoutManager = GridLayoutManager(this, 3).apply {
            spanSizeLookup = object : GridLayoutManager.SpanSizeLookup() {
                override fun getSpanSize(position: Int): Int =
                    if (adapter.getItemViewType(position) == 0) 3 else 1
            }
        }
        binding.recycler.adapter = adapter

        setupFilters()
        ensurePermissionThenLoad()
    }

    private fun setupFilters() {
        val chips = mapOf<android.widget.TextView, MediaRepository.Mode>(
            binding.chipAll to MediaRepository.Mode.ALL,
            binding.chipImages to MediaRepository.Mode.IMAGES,
            binding.chipVideos to MediaRepository.Mode.VIDEOS
        )
        chips.forEach { (chip, m) ->
            chip.setOnClickListener {
                mode = m
                applyFilterHighlight(chips)
                render()
            }
        }
        applyFilterHighlight(chips)
    }

    private fun applyFilterHighlight(chips: Map<android.widget.TextView, MediaRepository.Mode>) {
        chips.forEach { (chip, m) ->
            val on = m == mode
            chip.setTextColor(if (on) AccentManager.color() else ContextCompat.getColor(this, R.color.text_secondary))
            chip.typeface = if (on) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
        }
    }

    private fun ensurePermissionThenLoad() {
        val needed = if (Build.VERSION.SDK_INT >= 33) {
            arrayOf(Manifest.permission.READ_MEDIA_IMAGES, Manifest.permission.READ_MEDIA_VIDEO)
        } else {
            arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
        }
        if (needed.all {
                ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
            }) {
            load()
        } else {
            permLauncher.launch(needed)
        }
    }

    private fun load() {
        binding.tvEmpty.visibility = View.GONE
        Thread {
            val items = ArrayList<MediaItem>()
            val sections = MediaRepository.load(this, MediaRepository.Mode.ALL)
            sections.forEach { s -> items.addAll(s.items) }
            runOnUiThread {
                allItems = items
                render()
            }
        }.start()
    }

    /** 用内存数据按当前筛选重渲染（分组标签由数据层保证顺序） */
    private fun render() {
        val filtered = when (mode) {
            MediaRepository.Mode.ALL -> allItems
            MediaRepository.Mode.IMAGES -> allItems.filter { !it.isVideo }
            MediaRepository.Mode.VIDEOS -> allItems.filter { it.isVideo }
        }
        // 重建分组（保持日期顺序）
        val sections = LinkedHashMap<String, MutableList<MediaItem>>()
        for (it in filtered) {
            val label = MediaRepository.labelOf(it.date)
            sections.getOrPut(label) { ArrayList() }.add(it)
        }
        adapter.submit(sections.map { com.wanlin.gallery.data.MediaSection(it.key, it.value) })
        binding.tvEmpty.visibility = if (filtered.isEmpty()) View.VISIBLE else View.GONE
    }

    private fun onItemClicked(item: MediaItem) {
        if (item.isVideo) {
            VideoPlayerActivity.start(this, item.id, item.name)
        } else {
            val images = allItems.filter { !it.isVideo }
            val index = images.indexOfFirst { it.id == item.id }.coerceAtLeast(0)
            ImageViewerActivity.start(this, images.map { it.id }.toLongArray(), index)
        }
    }

    // ─────────── 长按菜单 ───────────

    private fun showItemMenu(item: MediaItem) {
        IconMenu.show(this, item.name, listOf(
            IconMenu.Cell(R.drawable.ic_info, R.string.file_info) { showDetails(item) },
            IconMenu.Cell(R.drawable.ic_copy, R.string.copy_file) { doCopy(item) },
            IconMenu.Cell(R.drawable.ic_clipboard, R.string.copy_clip) {
                MediaActions.copyToClipboard(this, item)
                Toast.makeText(this, R.string.clip_ok, Toast.LENGTH_SHORT).show()
            },
            IconMenu.Cell(R.drawable.ic_share, R.string.share) { MediaActions.share(this, item) },
            IconMenu.Cell(R.drawable.ic_edit, R.string.rename) { showRenameDialog(item) },
            IconMenu.Cell(R.drawable.ic_delete, R.string.delete, danger = true) { confirmDelete(item) }
        ))
    }

    private fun showDetails(item: MediaItem) {
        AppDialogs.info(this, getString(R.string.file_info), MediaActions.infoText(this, item))
    }

    private fun doCopy(item: MediaItem) {
        withWritePermission {
            Toast.makeText(this, R.string.copying, Toast.LENGTH_SHORT).show()
            Thread {
                val newName = MediaActions.copy(applicationContext, item)
                runOnUiThread {
                    if (newName != null) {
                        Toast.makeText(this, getString(R.string.copy_ok, newName), Toast.LENGTH_SHORT).show()
                        load()
                    } else {
                        Toast.makeText(this, R.string.op_failed, Toast.LENGTH_SHORT).show()
                    }
                }
            }.start()
        }
    }

    private fun showRenameDialog(item: MediaItem) {
        AppDialogs.input(this, getString(R.string.rename), item.name) { name ->
            withWritePermission {
                if (MediaActions.rename(this, item, name)) {
                    Toast.makeText(this, R.string.rename_ok, Toast.LENGTH_SHORT).show()
                    load()
                } else {
                    Toast.makeText(this, R.string.op_failed, Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun confirmDelete(item: MediaItem) {
        AppDialogs.confirm(
            this, getString(R.string.delete), item.name,
            getString(R.string.delete), danger = true
        ) { deleteItem(item) }
    }

    /**
     * 删除分级策略：
     * API 30+：先试直删（本应用自建文件可直删）→ 失败走系统回收站 createTrashRequest
     *          → ROM 不支持再降级 createDeleteRequest（系统确认框）；
     * API ≤29：拿 WRITE 权限后 ContentResolver 直删。
     */
    private fun deleteItem(item: MediaItem) {
        if (Build.VERSION.SDK_INT >= 30) {
            if (MediaActions.deleteDirect(this, item)) {
                afterDeleted(item)
                return
            }
            try {
                val pi = MediaStore.createTrashRequest(contentResolver, listOf(item.uri), true)
                pendingTrashIds = longArrayOf(item.id)
                trashLauncher.launch(IntentSenderRequest.Builder(pi.intentSender).build())
                return
            } catch (_: Exception) {
                // ROM 不支持回收站，继续降级
            }
            try {
                val pi = MediaStore.createDeleteRequest(contentResolver, listOf(item.uri))
                pendingTrashIds = longArrayOf(item.id)
                trashLauncher.launch(IntentSenderRequest.Builder(pi.intentSender).build())
            } catch (_: Exception) {
                Toast.makeText(this, R.string.op_failed, Toast.LENGTH_SHORT).show()
            }
        } else {
            withWritePermission {
                if (MediaActions.deleteDirect(this, item)) afterDeleted(item)
                else Toast.makeText(this, R.string.op_failed, Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun afterDeleted(item: MediaItem) {
        if (item.isVideo) clearResumeOf(item.id)
        Toast.makeText(this, R.string.delete_ok, Toast.LENGTH_SHORT).show()
        load()
    }

    private fun clearResumeOf(id: Long) =
        getSharedPreferences("video_player", MODE_PRIVATE).edit().remove("vpos_$id").apply()

    /** ≤29 复制/删除/重命名前先拿写权限；30+ 直接执行 */
    private fun withWritePermission(action: () -> Unit) {
        if (Build.VERSION.SDK_INT >= 30) {
            action()
            return
        }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE)
            == PackageManager.PERMISSION_GRANTED
        ) {
            action()
        } else {
            pendingWriteAction = action
            writePermLauncher.launch(arrayOf(Manifest.permission.WRITE_EXTERNAL_STORAGE))
        }
    }

    private fun showDenied() {
        binding.tvEmpty.visibility = View.VISIBLE
        binding.tvEmpty.text = getString(R.string.need_permission)
        binding.tvEmpty.setOnClickListener { ensurePermissionThenLoad() }
    }

    companion object {
        const val EXTRA_MODE = "mode"   // ALL | IMAGES | VIDEOS
    }
}
