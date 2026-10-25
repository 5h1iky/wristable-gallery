package com.wanlin.gallery.widget

import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.graphics.Matrix
import android.graphics.RectF
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.TextureView
import android.view.ViewConfiguration
import android.view.animation.DecelerateInterpolator
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.min

/**
 * 视频手势引擎（与图片查看器同款逻辑，作用于 TextureView 矩阵）：
 * - 双指无极缩放（1x~4x 平滑，围绕焦点）
 * - 双击放大 2.5x / 再双击复原（220ms 缓动动画）
 * - 放大后单指平移（边界回弹限制）
 * - 未放大时纵向滑动回调（音量/亮度用）
 * - 90° 步进旋转（300ms 动画），矩阵含旋转分量
 *
 * 矩阵管线：W = User × Translate(viewCenter) × Rotate(r) × Fit × Translate(-contentCenter)
 * （User 在视图坐标系最后套，捏合焦点才是视图点位，画面绕手指缩放不滑走）
 * TextureView.setTransform(T) 中 T = W × Default⁻¹
 */
class VideoZoomController(private val view: TextureView) {

    var onSingleTap: (() -> Unit)? = null
    var onVerticalDragStart: (() -> Unit)? = null
    var onVerticalDrag: ((Float) -> Unit)? = null   // frac = 纵向拖动量/视图高（手指上滑为正）

    /** 锁定后禁用缩放/双击/平移/音量亮度手势（单击仍回调，用于呼出解锁按钮） */
    var gestureLocked = false

    private var contentW = 0
    private var contentH = 0
    private val userMatrix = Matrix()
    private val tmp = Matrix()
    private var rotation = 0f

    private val touchSlop = ViewConfiguration.get(view.context).scaledTouchSlop
    private var dragAccum = 0f
    private var draggingVert = false

    private var animator: ValueAnimator? = null

    private val scaleDetector = ScaleGestureDetector(view.context,
        object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScale(det: ScaleGestureDetector): Boolean {
                if (gestureLocked) return true
                val f = det.scaleFactor
                val cur = currentScale()
                val next = (cur * f).coerceIn(1f, 4f)
                val real = if (cur == 0f) 1f else next / cur
                tmp.reset()
                tmp.postScale(real, real, det.focusX, det.focusY)
                userMatrix.postConcat(tmp)
                fixTranslation()   // 边界约束：画面不允许被捏出屏幕
                applyMatrix()
                return true
            }
        })

    private val gesture = GestureDetector(view.context,
        object : GestureDetector.SimpleOnGestureListener() {
            override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
                onSingleTap?.invoke()
                return true
            }

            override fun onDoubleTap(e: MotionEvent): Boolean {
                if (gestureLocked) return true
                val cur = currentScale()
                val target = if (cur > 1.05f) 1f else 2.5f
                animateScale(cur, target, e.x, e.y)
                return true
            }

            override fun onScroll(
                e1: MotionEvent?, e2: MotionEvent, dx: Float, dy: Float
            ): Boolean {
                if (gestureLocked) return true
                if (scaleDetector.isInProgress) return true
                if (currentScale() > 1.05f) {
                    tmp.reset()
                    tmp.postTranslate(-dx, -dy)
                    userMatrix.postConcat(tmp)
                    fixTranslation()
                    applyMatrix()
                    return true
                }
                // 未放大：纵向拖动 → 音量/亮度。
                // 注意：GestureDetector 的 dy = 上次Y − 当前Y，手指【上滑】dy > 0 → frac > 0。
                if (!draggingVert && abs(dy) > touchSlop && abs(dy) > abs(dx)) {
                    draggingVert = true
                    onVerticalDragStart?.invoke()
                }
                if (draggingVert) {
                    dragAccum += dy
                    onVerticalDrag?.invoke(dragAccum / view.height.coerceAtLeast(1))
                    return true
                }
                return false
            }
        })

    fun setContentSize(w: Int, h: Int) {
        contentW = w
        contentH = h
        userMatrix.reset()
        applyMatrix()
    }

    /** 旋转到指定角度（度），带动画 */
    fun animateRotateTo(deg: Float) {
        animator?.cancel()
        val from = rotation
        var to = deg
        while (to - from > 180) to -= 360
        while (to - from < -180) to += 360
        animator = ValueAnimator.ofFloat(from, to).apply {
            duration = 300
            interpolator = DecelerateInterpolator()
            addUpdateListener { rotation = it.animatedValue as Float; applyMatrix() }
            start()
        }
    }

    fun currentScale(): Float {
        val v = FloatArray(9)
        userMatrix.getValues(v)
        return hypot(v[Matrix.MSCALE_X], v[Matrix.MSKEW_Y])
    }

    /** 内容→视图 的目标矩阵 W。
     *  关键：居中平移必须在 userMatrix 之前完成，userMatrix 才工作在视图坐标系，
     *  捏合焦点(focusX,focusY)即视图点位——否则支点半屏偏移导致画面滑走。 */
    private fun buildW(): Matrix {
        val vw = view.width.toFloat()
        val vh = view.height.toFloat()
        val w = Matrix()
        if (contentW == 0 || vw == 0f) return w
        val rotated = rotation % 180 != 0f
        val cw = if (rotated) contentH else contentW
        val ch = if (rotated) contentW else contentH
        val k = min(vw / cw, vh / ch)
        w.postTranslate(-contentW / 2f, -contentH / 2f)
        w.postScale(k, k)
        w.postRotate(rotation)
        w.postTranslate(vw / 2f, vh / 2f)   // 先进视图坐标系
        w.postConcat(userMatrix)            // 再套用户缩放/平移（焦点即视图坐标）✓
        return w
    }

    private fun applyMatrix() {
        val vw = view.width.toFloat()
        val vh = view.height.toFloat()
        if (contentW == 0 || vw == 0f) return
        // T = W × Default⁻¹（Default: 内容拉伸铺满视图）
        val dInv = Matrix()
        dInv.setScale(contentW / vw, contentH / vh)
        val t = Matrix()
        t.setConcat(buildW(), dInv)
        view.setTransform(t)
    }

    private fun fixTranslation() {
        val vw = view.width.toFloat()
        val vh = view.height.toFloat()
        if (contentW == 0 || vw == 0f) return
        // 内容经 W 映射后的矩形，限制平移不出界
        val rect = RectF(0f, 0f, contentW.toFloat(), contentH.toFloat())
        buildW().mapRect(rect)
        var dx = 0f
        var dy = 0f
        if (rect.width() <= vw) dx = vw / 2f - rect.centerX()
        else {
            if (rect.left > 0) dx = -rect.left
            if (rect.right < vw) dx = vw - rect.right
        }
        if (rect.height() <= vh) dy = vh / 2f - rect.centerY()
        else {
            if (rect.top > 0) dy = -rect.top
            if (rect.bottom < vh) dy = vh - rect.bottom
        }
        tmp.reset()
        tmp.postTranslate(dx, dy)
        userMatrix.postConcat(tmp)
    }

    private fun animateScale(from: Float, to: Float, fx: Float, fy: Float) {
        animator?.cancel()
        val startM = Matrix(userMatrix)
        animator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 220
            interpolator = DecelerateInterpolator()
            addUpdateListener { av ->
                val t = av.animatedValue as Float
                val s = from + (to - from) * t
                // 每帧都从 startM 出发，缩放因子必须是「目标/起点」而非「目标/当前」，
                // 否则矩阵被重置后按增量相乘，结果震荡且最终停不到目标值。
                val f = if (from <= 0f) s else s / from
                userMatrix.set(startM)
                tmp.reset()
                tmp.postScale(f, f, fx, fy)
                userMatrix.postConcat(tmp)
                fixTranslation()
                applyMatrix()
            }
            start()
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    fun onTouchEvent(ev: MotionEvent): Boolean {
        scaleDetector.onTouchEvent(ev)
        gesture.onTouchEvent(ev)
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                dragAccum = 0f
                view.parent?.requestDisallowInterceptTouchEvent(currentScale() > 1.05f)
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                draggingVert = false
                if (currentScale() < 1f) animateScale(currentScale(), 1f, view.width / 2f, view.height / 2f)
                view.parent?.requestDisallowInterceptTouchEvent(false)
            }
            MotionEvent.ACTION_MOVE ->
                view.parent?.requestDisallowInterceptTouchEvent(currentScale() > 1.05f)
        }
        return true
    }
}
