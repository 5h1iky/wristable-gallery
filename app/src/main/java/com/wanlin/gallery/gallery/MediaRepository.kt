package com.wanlin.gallery.gallery

import android.content.ContentUris
import android.content.Context
import android.provider.MediaStore
import com.wanlin.gallery.data.MediaItem
import com.wanlin.gallery.data.MediaSection
import java.util.Calendar

/**
 * MediaStore 媒体扫描：图片 + 视频，按日期倒序、分组（今天/昨天/本周/本月/更早）。
 * 权限（运行时申请后调用）：API33+ READ_MEDIA_IMAGES/VIDEO；否则 READ_EXTERNAL_STORAGE。
 */
object MediaRepository {

    enum class Mode { ALL, IMAGES, VIDEOS }

    /** 加载并分组（应在工作线程调用） */
    fun load(context: Context, mode: Mode): List<MediaSection> {
        val items = ArrayList<MediaItem>()
        if (mode != Mode.VIDEOS) items += queryImages(context)
        if (mode != Mode.IMAGES) items += queryVideos(context)
        items.sortByDescending { it.date }
        return groupByDate(items)
    }

    private fun queryImages(context: Context): List<MediaItem> {
        val out = ArrayList<MediaItem>()
        val cols = arrayOf(
            MediaStore.Images.Media._ID,
            MediaStore.Images.Media.DISPLAY_NAME,
            MediaStore.Images.Media.DATE_TAKEN,
            MediaStore.Images.Media.DATE_ADDED,
            MediaStore.Images.Media.SIZE,
            MediaStore.Images.Media.MIME_TYPE,
            MediaStore.Images.Media.WIDTH,
            MediaStore.Images.Media.HEIGHT,
            MediaStore.Images.Media.DATA
        )
        context.contentResolver.query(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI, cols, null, null, null
        )?.use { c ->
            val iId = c.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
            val iName = c.getColumnIndexOrThrow(MediaStore.Images.Media.DISPLAY_NAME)
            val iTaken = c.getColumnIndexOrThrow(MediaStore.Images.Media.DATE_TAKEN)
            val iAdded = c.getColumnIndexOrThrow(MediaStore.Images.Media.DATE_ADDED)
            val iSize = c.getColumnIndexOrThrow(MediaStore.Images.Media.SIZE)
            val iMime = c.getColumnIndexOrThrow(MediaStore.Images.Media.MIME_TYPE)
            val iW = c.getColumnIndexOrThrow(MediaStore.Images.Media.WIDTH)
            val iH = c.getColumnIndexOrThrow(MediaStore.Images.Media.HEIGHT)
            val iPath = c.getColumnIndex(MediaStore.Images.Media.DATA)
            while (c.moveToNext()) {
                val id = c.getLong(iId)
                val taken = c.getLong(iTaken)
                val date = if (taken > 0) taken else c.getLong(iAdded) * 1000
                out += MediaItem(
                    id = id,
                    uri = ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, id),
                    name = c.getString(iName) ?: "IMG_$id",
                    date = date,
                    size = c.getLong(iSize),
                    mime = c.getString(iMime) ?: "image/*",
                    isVideo = false,
                    width = c.getInt(iW), height = c.getInt(iH),
                    path = if (iPath >= 0) c.getString(iPath) ?: "" else ""
                )
            }
        }
        return out
    }

    private fun queryVideos(context: Context): List<MediaItem> {
        val out = ArrayList<MediaItem>()
        val cols = arrayOf(
            MediaStore.Video.Media._ID,
            MediaStore.Video.Media.DISPLAY_NAME,
            MediaStore.Video.Media.DATE_TAKEN,
            MediaStore.Video.Media.DATE_ADDED,
            MediaStore.Video.Media.SIZE,
            MediaStore.Video.Media.MIME_TYPE,
            MediaStore.Video.Media.WIDTH,
            MediaStore.Video.Media.HEIGHT,
            MediaStore.Video.Media.DURATION,
            MediaStore.Video.Media.DATA
        )
        context.contentResolver.query(
            MediaStore.Video.Media.EXTERNAL_CONTENT_URI, cols, null, null, null
        )?.use { c ->
            val iId = c.getColumnIndexOrThrow(MediaStore.Video.Media._ID)
            val iName = c.getColumnIndexOrThrow(MediaStore.Video.Media.DISPLAY_NAME)
            val iTaken = c.getColumnIndexOrThrow(MediaStore.Video.Media.DATE_TAKEN)
            val iAdded = c.getColumnIndexOrThrow(MediaStore.Video.Media.DATE_ADDED)
            val iSize = c.getColumnIndexOrThrow(MediaStore.Video.Media.SIZE)
            val iMime = c.getColumnIndexOrThrow(MediaStore.Video.Media.MIME_TYPE)
            val iW = c.getColumnIndexOrThrow(MediaStore.Video.Media.WIDTH)
            val iH = c.getColumnIndexOrThrow(MediaStore.Video.Media.HEIGHT)
            val iDur = c.getColumnIndexOrThrow(MediaStore.Video.Media.DURATION)
            val iPath = c.getColumnIndex(MediaStore.Video.Media.DATA)
            while (c.moveToNext()) {
                val id = c.getLong(iId)
                val taken = c.getLong(iTaken)
                val date = if (taken > 0) taken else c.getLong(iAdded) * 1000
                out += MediaItem(
                    id = id,
                    uri = ContentUris.withAppendedId(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, id),
                    name = c.getString(iName) ?: "VID_$id",
                    date = date,
                    size = c.getLong(iSize),
                    mime = c.getString(iMime) ?: "video/*",
                    isVideo = true,
                    duration = c.getLong(iDur),
                    width = c.getInt(iW), height = c.getInt(iH),
                    path = if (iPath >= 0) c.getString(iPath) ?: "" else ""
                )
            }
        }
        return out
    }

    /** 今天 / 昨天 / 本周 / 本月 / 更早 */
    fun labelOf(date: Long): String {
        val now = Calendar.getInstance()
        val todayStart = (now.clone() as Calendar).apply {
            set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
        }.timeInMillis
        val weekStart = (now.clone() as Calendar).apply {
            set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
            set(Calendar.DAY_OF_WEEK, firstDayOfWeek)
        }.timeInMillis
        val monthStart = (now.clone() as Calendar).apply {
            set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
            set(Calendar.DAY_OF_MONTH, 1)
        }.timeInMillis
        return when {
            date >= todayStart -> "今天"
            date >= todayStart - DAY -> "昨天"
            date >= weekStart -> "本周"
            date >= monthStart -> "本月"
            else -> "更早"
        }
    }

    private fun groupByDate(items: List<MediaItem>): List<MediaSection> {
        val sections = LinkedHashMap<String, MutableList<MediaItem>>()
        for (it in items) {
            sections.getOrPut(labelOf(it.date)) { ArrayList() }.add(it)
        }
        return sections.map { MediaSection(it.key, it.value) }
    }

    private const val DAY = 24L * 3600 * 1000
}
