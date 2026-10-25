package com.wanlin.gallery.ui

import android.app.Activity
import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.graphics.SurfaceTexture
import android.media.AudioManager
import android.media.MediaPlayer
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.view.Gravity
import android.view.Surface
import android.view.TextureView
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import com.wanlin.gallery.R
import com.wanlin.gallery.base.BaseActivity
import com.wanlin.gallery.databinding.ActivityVideoPlayerBinding
import com.wanlin.gallery.widget.VideoZoomController
import kotlin.math.abs

/**
 * 视频播放器（P5 TextureView 重写）：
 * 双指无极缩放(1x~4x) · 双击放大 2.5x 带动画 · 放大后平移 ·
 * ↻ 90° 步进旋转（带动画，会话记忆）· 上下滑音量(右)/亮度(左) ·
 * 加粗进度条 · 控件 3 秒自动隐藏 · 🔒锁定 · 续播记忆。
 */
class VideoPlayerActivity : BaseActivity() {

    private lateinit var binding: ActivityVideoPlayerBinding
    private lateinit var controller: VideoZoomController

    private var mp: MediaPlayer? = null
    private var videoId = 0L
    private var videoName = ""
    private var userPaused = false
    private var locked = false
    private var scrubbing = false
    private var rotationDeg = 0f          // 旋转记忆（会话内）
    private var savedPosition = 0

    private val hideRunnable = Runnable { if (!userPaused && !locked) toggleChrome(false) }
    private val handler = Handler(Looper.getMainLooper())
    private val ticker = object : Runnable {
        override fun run() {
            val p = mp
            if (p != null && p.duration > 0 && !scrubbing) {
                binding.seekBar.value = p.currentPosition.toFloat()
                binding.tvCurrent.text = fmt(p.currentPosition)
            }
            handler.postDelayed(this, 500)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        binding = ActivityVideoPlayerBinding.inflate(layoutInflater)
        setContentView(binding.root)

        videoId = intent.getLongExtra(EXTRA_ID, 0)
        videoName = intent.getStringExtra(EXTRA_NAME) ?: "视频"
        binding.tvName.text = videoName
        savedPosition = prefs().getInt("vpos_$videoId", 0)

        tintSeekbar(AccentManager.color())
        setupGestures()
        setupButtons()
        setupTexture()
    }

    // ─────────── 播放内核（TextureView + MediaPlayer） ───────────

    private fun setupTexture() {
        binding.videoView.surfaceTextureListener = object : TextureView.SurfaceTextureListener {
            override fun onSurfaceTextureAvailable(st: SurfaceTexture, w: Int, h: Int) {
                if (mp == null) startPlayback(Surface(st))
                else mp?.setSurface(Surface(st))
            }

            override fun onSurfaceTextureSizeChanged(st: SurfaceTexture, w: Int, h: Int) {}

            override fun onSurfaceTextureDestroyed(st: SurfaceTexture): Boolean {
                mp?.setSurface(null)
                return true
            }

            override fun onSurfaceTextureUpdated(st: SurfaceTexture) {}
        }
    }

    private fun startPlayback(surface: Surface) {
        val uri: Uri = ContentUris.withAppendedId(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, videoId)
        val p = MediaPlayer()
        mp = p
        try {
            p.setDataSource(this, uri)
            p.setSurface(surface)
            p.setOnVideoSizeChangedListener { _, w, h ->
                if (w > 0 && h > 0) controller.setContentSize(w, h)
            }
            p.setOnPreparedListener { prepared ->
                binding.seekBar.valueTo = prepared.duration.toFloat().coerceAtLeast(1f)
                binding.tvTotal.text = fmt(prepared.duration)
                if (savedPosition in 1 until prepared.duration - 1500) {
                    prepared.seekTo(savedPosition)
                }
                prepared.start()
                updatePlayIcon()
                handler.post(ticker)
                scheduleHide()
            }
            p.setOnCompletionListener {
                userPaused = true
                toggleChrome(true)
                prefs().edit().remove("vpos_$videoId").apply()
            }
            p.setOnErrorListener { _, _, _ ->
                binding.tvName.text = getString(R.string.video_error)
                true
            }
            p.prepareAsync()
        } catch (_: Exception) {
            binding.tvName.text = getString(R.string.video_error)
        }
    }

    // ─────────── 手势（与图片查看器同款） ───────────

    private fun setupGestures() {
        controller = VideoZoomController(binding.videoView).apply {
            onSingleTap = {
                if (!locked) {
                    toggleChrome(binding.topBar.alpha < 0.5f)
                    scheduleHide()
                } else {
                    showLockOnly()
                }
            }
            onVerticalDragStart = {
                val audio = getSystemService(Context.AUDIO_SERVICE) as AudioManager
                dragStartVol = audio.getStreamVolume(AudioManager.STREAM_MUSIC)
                dragStartBri = window.attributes.screenBrightness.let { if (it < 0) 0.5f else it }
            }
            onVerticalDrag = { frac ->
                val audio = getSystemService(Context.AUDIO_SERVICE) as AudioManager
                val maxVol = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
                // frac = 累计拖动量/视图高，手指上滑为正：左半屏亮度、右半屏音量，上滑调大
                if (lastTouchX < binding.root.width / 2) {
                    val attrs = window.attributes
                    attrs.screenBrightness = (dragStartBri + frac).coerceIn(0.05f, 1f)
                    window.attributes = attrs
                } else {
                    val v = (dragStartVol + (frac * maxVol).toInt()).coerceIn(0, maxVol)
                    audio.setStreamVolume(AudioManager.STREAM_MUSIC, v, 0)
                }
            }
        }
        binding.videoView.setOnTouchListener { _, ev ->
            lastTouchX = ev.x
            controller.onTouchEvent(ev)
        }
    }

    private var dragStartVol = 0
    private var dragStartBri = 0f
    private var lastTouchX = 0f

    // ─────────── 控件 ───────────

    private fun setupButtons() {
        binding.btnBack.setOnClickListener { finish() }
        binding.btnPlay.setOnClickListener {
            val p = mp ?: return@setOnClickListener
            if (p.isPlaying) {
                p.pause(); userPaused = true
            } else {
                p.start(); userPaused = false
            }
            updatePlayIcon()
        }
        binding.btnLock.setOnClickListener {
            if (locked) {
                locked = false
                controller.gestureLocked = false
                binding.btnLock.alpha = 1f
                toggleChrome(true)
                scheduleHide()
            } else {
                locked = true
                controller.gestureLocked = true
                toggleChrome(false)
            }
        }
        // 旋转：90° 步进，带动画，会话记忆
        binding.btnRotate.setOnClickListener {
            rotationDeg = (rotationDeg + 90f) % 360f
            controller.animateRotateTo(rotationDeg)
            scheduleHide()
        }
        binding.seekBar.addOnChangeListener { _, v, fromUser ->
            if (fromUser) {
                mp?.seekTo(v.toInt())
                binding.tvCurrent.text = fmt(v.toInt())
            }
        }
        binding.seekBar.addOnSliderTouchListener(object :
            com.google.android.material.slider.Slider.OnSliderTouchListener {
            override fun onStartTrackingTouch(slider: com.google.android.material.slider.Slider) {
                scrubbing = true
                handler.removeCallbacks(hideRunnable)
            }
            override fun onStopTrackingTouch(slider: com.google.android.material.slider.Slider) {
                scrubbing = false
                scheduleHide()
            }
        })
    }

    private fun tintSeekbar(accent: Int) {
        binding.seekBar.setTrackActiveTintList(android.content.res.ColorStateList.valueOf(accent))
        binding.seekBar.setThumbTintList(android.content.res.ColorStateList.valueOf(accent))
        binding.seekBar.setHaloTintList(android.content.res.ColorStateList.valueOf((accent and 0x00FFFFFF) or 0x22000000))
    }

    private fun updatePlayIcon() {
        binding.btnPlay.setImageResource(
            if (mp?.isPlaying == true) R.drawable.ic_pause else R.drawable.ic_play
        )
    }

    private fun scheduleHide() {
        binding.root.removeCallbacks(hideRunnable)
        binding.root.postDelayed(hideRunnable, 3000)
    }

    private fun toggleChrome(show: Boolean) {
        if (show && locked) {
            showLockOnly()
            return
        }
        fadeChrome(binding.topBar, show)
        fadeChrome(binding.bottomBar, show)
        fadeChrome(binding.btnPlay, show)
        fadeChrome(binding.btnRotate, show)
        fadeChrome(binding.btnLock, show)
    }

    /** 锁定态单击：只呼出解锁按钮（右上控件组独立于 topBar，父透明不受顶栏淡出影响） */
    private fun showLockOnly() {
        fadeChrome(binding.btnRotate, false)
        val v = binding.btnLock
        v.visibility = View.VISIBLE
        v.animate().alpha(0.6f).setDuration(200).start()
    }

    /** 淡入/淡出 + 末态切可见性：alpha=0 的 View 仍会拦截点击，必须 INVISIBLE */
    private fun fadeChrome(v: View, show: Boolean) {
        if (show) v.visibility = View.VISIBLE
        v.animate().alpha(if (show) 1f else 0f).setDuration(200).withEndAction {
            if (!show) v.visibility = View.INVISIBLE
        }.start()
    }

    // ─────────── 生命周期 ───────────

    override fun onPause() {
        super.onPause()
        mp?.let { prefs().edit().putInt("vpos_$videoId", it.currentPosition).apply() }
        mp?.let { if (it.isPlaying) { it.pause(); userPaused = true } }
        updatePlayIcon()
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        mp?.release()
        mp = null
        super.onDestroy()
    }

    private fun prefs() = getSharedPreferences("video_player", Context.MODE_PRIVATE)

    private fun fmt(ms: Int): String {
        val total = ms / 1000
        return "%02d:%02d".format(total / 60, total % 60)
    }

    companion object {
        private const val EXTRA_ID = "id"
        private const val EXTRA_NAME = "name"

        fun start(activity: Activity, id: Long, name: String) {
            activity.startActivity(
                Intent(activity, VideoPlayerActivity::class.java)
                    .putExtra(EXTRA_ID, id)
                    .putExtra(EXTRA_NAME, name)
            )
        }
    }
}
