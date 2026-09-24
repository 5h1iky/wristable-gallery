package com.wanlin.gallery.gallery

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.wanlin.gallery.R
import com.wanlin.gallery.data.MediaItem
import com.wanlin.gallery.data.MediaSection

/**
 * 相册网格适配器：3 列缩略图 + 日期分组标题。
 * 缩略图三级加载：占位色 → 缩略图 → （查看器里）原图。
 */
class MediaGridAdapter(
    private val onClick: (MediaItem) -> Unit,
    private val onLongClick: (MediaItem) -> Unit
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    private sealed class Row {
        data class Head(val label: String) : Row()
        data class Thumb(val item: MediaItem) : Row()
    }

    private val rows = ArrayList<Row>()

    fun submit(sections: List<MediaSection>) {
        rows.clear()
        for (s in sections) {
            rows += Row.Head(s.label)
            s.items.forEach { rows += Row.Thumb(it) }
        }
        notifyDataSetChanged()
    }

    override fun getItemViewType(position: Int) =
        if (rows[position] is Row.Head) TYPE_HEAD else TYPE_THUMB

    override fun getItemCount() = rows.size

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return if (viewType == TYPE_HEAD) {
            HeadVH(inflater.inflate(R.layout.item_gallery_header, parent, false))
        } else {
            ThumbVH(inflater.inflate(R.layout.item_gallery_thumb, parent, false))
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (val row = rows[position]) {
            is Row.Head -> (holder as HeadVH).bind(row.label)
            is Row.Thumb -> (holder as ThumbVH).bind(row.item)
        }
    }

    inner class HeadVH(v: View) : RecyclerView.ViewHolder(v) {
        private val tv: TextView = v.findViewById(R.id.tvHeader)
        fun bind(label: String) { tv.text = label }
    }

    inner class ThumbVH(v: View) : RecyclerView.ViewHolder(v) {
        private val iv: ImageView = v.findViewById(R.id.ivThumb)
        private val tvDur: TextView = v.findViewById(R.id.tvDuration)

        fun bind(item: MediaItem) {
            Glide.with(iv).load(item.uri).centerCrop().into(iv)
            if (item.isVideo) {
                tvDur.visibility = View.VISIBLE
                tvDur.text = formatDuration(item.duration)
            } else {
                tvDur.visibility = View.GONE
            }
            iv.setOnClickListener { onClick(item) }
            iv.setOnLongClickListener { v ->
                // 0 = HapticFeedbackConstants.LONG_PRESS（SDK36 起该类迁出 View 成顶层类，
                // 直接引用在旧设备有类缺失风险；此常量自 API1 恒为 0）
                v.performHapticFeedback(0)
                onLongClick(item)
                true
            }
        }

        private fun formatDuration(ms: Long): String {
            val total = ms / 1000
            val m = total / 60
            val s = total % 60
            return "%d:%02d".format(m, s)
        }
    }

    private companion object {
        const val TYPE_HEAD = 0
        const val TYPE_THUMB = 1
    }
}
