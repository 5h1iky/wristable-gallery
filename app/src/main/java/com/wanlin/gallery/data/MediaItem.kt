package com.wanlin.gallery.data

import android.net.Uri

/** 媒体条目（图片或视频） */
data class MediaItem(
    val id: Long,
    val uri: Uri,
    val name: String,
    val date: Long,
    val size: Long,
    val mime: String,
    val isVideo: Boolean,
    val duration: Long = 0L,   // ms，仅视频
    val width: Int = 0,
    val height: Int = 0,
    val path: String = ""      // _data 绝对路径（详情显示用，可能为空）
)

/** 按日期分组的一节 */
data class MediaSection(
    val label: String,
    val items: List<MediaItem>
)
