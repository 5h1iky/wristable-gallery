package com.wanlin.gallery.ui

import android.app.Activity
import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.provider.MediaStore
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.viewpager2.widget.ViewPager2
import com.bumptech.glide.Glide
import com.wanlin.gallery.R
import com.wanlin.gallery.base.BaseActivity
import com.wanlin.gallery.databinding.ActivityImageViewerBinding
import com.wanlin.gallery.widget.ZoomImageView
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** 查看器页面 ViewHolder（独立顶层类，避免内嵌类循环引用） */
private class PageVH(val iv: ZoomImageView) :
    androidx.recyclerview.widget.RecyclerView.ViewHolder(iv)

/**
 * 图片查看器（核心页面，重点打磨）：
 * 全屏沉浸 · 左右滑切换 · 双指缩放/双击放大 · 下拉退出 ·
 * 上下信息条 3 秒自动淡出 · 位置圆点 · 共享元素进入过渡。
 */
class ImageViewerActivity : BaseActivity() {

    private lateinit var binding: ActivityImageViewerBinding
    private var ids = longArrayOf()
    private var names = HashMap<Long, String>()
    private var dates = HashMap<Long, Long>()
    private val hideRunnable = Runnable { toggleChrome(false) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityImageViewerBinding.inflate(layoutInflater)
        setContentView(binding.root)

        ids = intent.getLongArrayExtra(EXTRA_IDS) ?: longArrayOf()
        val start = intent.getIntExtra(EXTRA_INDEX, 0)
        loadMeta()

        binding.pager.adapter = PageAdapter()
        binding.pager.setCurrentItem(start, false)
        binding.pager.registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
            override fun onPageSelected(position: Int) {
                updateInfo(position)
                scheduleHide()
            }
        })

        binding.btnBack.setOnClickListener { finish() }
        binding.root.setOnClickListener { toggleChrome(binding.topBar.alpha < 0.5f); scheduleHide() }

        updateInfo(start)
        scheduleHide()
    }

    private fun loadMeta() {
        if (ids.isEmpty()) return
        val cols = arrayOf(MediaStore.Images.Media._ID, MediaStore.Images.Media.DISPLAY_NAME,
            MediaStore.Images.Media.DATE_TAKEN, MediaStore.Images.Media.DATE_ADDED)
        runCatching {
            contentResolver.query(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, cols,
                "${MediaStore.Images.Media._ID} IN (${ids.joinToString(",")})", null, null
            )?.use { c ->
                val iId = c.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
                val iName = c.getColumnIndexOrThrow(MediaStore.Images.Media.DISPLAY_NAME)
                val iTaken = c.getColumnIndexOrThrow(MediaStore.Images.Media.DATE_TAKEN)
                val iAdded = c.getColumnIndexOrThrow(MediaStore.Images.Media.DATE_ADDED)
                while (c.moveToNext()) {
                    val id = c.getLong(iId)
                    val taken = c.getLong(iTaken)
                    names[id] = c.getString(iName) ?: "IMG_$id"
                    dates[id] = if (taken > 0) taken else c.getLong(iAdded) * 1000
                }
            }
        }
    }

    private fun updateInfo(pos: Int) {
        val id = ids.getOrNull(pos) ?: return
        binding.tvName.text = names[id] ?: "IMG_$id"
        val date = dates[id] ?: 0L
        val s = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date(date))
        binding.tvInfo.text = "${pos + 1}/${ids.size} · $s"
    }

    private fun scheduleHide() {
        binding.root.removeCallbacks(hideRunnable)
        binding.root.postDelayed(hideRunnable, 3000)
    }

    private fun toggleChrome(show: Boolean) {
        val a = if (show) 1f else 0f
        for (v in listOf(binding.topBar, binding.bottomBar)) {
            if (show) v.visibility = View.VISIBLE
            // 淡出后切 INVISIBLE：alpha=0 的 View 仍会拦截点击（返回键等变成隐形热区）
            v.animate().alpha(a).setDuration(200).withEndAction {
                if (!show) v.visibility = View.INVISIBLE
            }.start()
        }
    }

    /** 页面适配器：每页一张可缩放大图（私有，避免暴露私有 VH 类型） */
    private inner class PageAdapter : androidx.recyclerview.widget.RecyclerView.Adapter<PageVH>() {
        override fun getItemCount() = ids.size

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): PageVH {
            val iv = ZoomImageView(parent.context).apply {
                layoutParams = ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
                )
                setBackgroundColor(Color.BLACK)
                onPullDown = { p ->
                    binding.root.translationY = p * height * 0.35f
                    binding.root.alpha = 1f - p * 0.6f
                }
                onPullDownRelease = { finish() }
                onSingleTap = {
                    toggleChrome(binding.topBar.alpha < 0.5f); scheduleHide()
                }
            }
            return PageVH(iv)
        }

        override fun onBindViewHolder(holder: PageVH, position: Int) {
            Glide.with(holder.iv).load(uriOf(ids[position]))
                .placeholder(R.color.bg)
                .into(holder.iv)
        }
    }

    private fun uriOf(id: Long): Uri =
        ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, id)

    companion object {
        private const val EXTRA_IDS = "ids"
        private const val EXTRA_INDEX = "index"

        fun start(activity: Activity, ids: LongArray, index: Int) {
            val i = Intent(activity, ImageViewerActivity::class.java)
                .putExtra(EXTRA_IDS, ids)
                .putExtra(EXTRA_INDEX, index)
            activity.startActivity(i)
            activity.overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out)
        }
    }
}
