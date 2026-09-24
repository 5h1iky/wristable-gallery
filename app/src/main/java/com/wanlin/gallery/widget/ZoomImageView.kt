package com.wanlin.gallery.widget

import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Matrix
import android.graphics.RectF
import android.util.AttributeSet
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.ViewConfiguration
import android.view.animation.DecelerateInterpolator
import androidx.appcompat.widget.AppCompatImageView
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/**
 * 手表小屏图片查看手势引擎：
 * - 双指缩放（1x~4x，围绕焦点）
 * - 双击放大 2.5x / 再双击还原（带动画）
 * - 放大后单指拖动平移（带边界回弹限制）
 * - 未缩放时下拉触发 onPullDown(进度) 回调（查看器做退出手势）
 * - 缩放中拦截 ViewPager2 的横向翻页
 */
class ZoomImageView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : AppCompatImageView(context, attrs) {

    /** 下拉进度回调（0..1），用于查看器下拉退出 */
    var onPullDown: ((Float) -> Unit)? = null
    /** 下拉松手退出回调 */
    var onPullDownRelease: (() -> Unit)? = null
    /** 单击回调（切换控件显隐） */
    var onSingleTap: (() -> Unit)? = null

    private val baseMatrix = Matrix()
    private val userMatrix = Matrix()
    private val tmp = Matrix()

    private var minScale = 1f
    private var maxScale = 4f
    private val animMatrix = Matrix()
    private var animator: ValueAnimator? = null

    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop
    private var pullAccum = 0f
    private var pulling = false

    private val scaleDetector = ScaleGestureDetector(context,
        object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScale(det: ScaleGestureDetector): Boolean {
                val f = det.scaleFactor
                val cur = currentScale()
                val next = (cur * f).coerceIn(minScale, maxScale)
                val real = if (cur == 0f) 1f else next / cur
                tmp.reset()
                tmp.postScale(real, real, det.focusX, det.focusY)
                userMatrix.postConcat(tmp)
                applyMatrix()
                return true
            }
        })

    private val gesture = GestureDetector(context,
        object : GestureDetector.SimpleOnGestureListener() {
            override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
                onSingleTap?.invoke()
                return true
            }

            override fun onDoubleTap(e: MotionEvent): Boolean {
                val cur = currentScale()
                val target = if (cur > 1.05f) 1f else 2.5f
                animateScale(cur, target, e.x, e.y)
                return true
            }

            override fun onScroll(
                e1: MotionEvent?, e2: MotionEvent, dx: Float, dy: Float
            ): Boolean {
                if (scaleDetector.isInProgress) return true
                if (currentScale() > 1.05f) {
                    // 放大态：平移
                    tmp.reset()
                    tmp.postTranslate(-dx, -dy)
                    userMatrix.postConcat(tmp)
                    fixTranslation()
                    applyMatrix()
                    return true
                }
                // 未放大：纵向下滑收集为退出手势
                if (pulling || (dy < -touchSlop && Math.abs(dy) > Math.abs(dx))) {
                    return false   // 向上滑不管
                } else if (dy > touchSlop || pulling) {
                    pulling = true
                    pullAccum += dy
                    onPullDown?.invoke((pullAccum / height).coerceIn(0f, 1f))
                    return true
                }
                return false
            }
        })

    init { scaleType = ScaleType.MATRIX }

    override fun setImageDrawable(d: android.graphics.drawable.Drawable?) {
        super.setImageDrawable(d)
        post { resetBase() }
    }

    override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) {
        super.onSizeChanged(w, h, ow, oh)
        resetBase()
    }

    /** 计算 fit-center 基准矩阵并复位用户变换 */
    private fun resetBase() {
        val d = drawable ?: return
        if (width == 0 || height == 0) return
        baseMatrix.reset()
        val dw = d.intrinsicWidth.toFloat()
        val dh = d.intrinsicHeight.toFloat()
        if (dw <= 0 || dh <= 0) return
        val s = min(width / dw, height / dh)
        val dx = (width - dw * s) / 2f
        val dy = (height - dh * s) / 2f
        baseMatrix.postScale(s, s)
        baseMatrix.postTranslate(dx, dy)
        userMatrix.reset()
        minScale = 1f
        applyMatrix()
    }

    private fun applyMatrix() {
        tmp.reset()
        tmp.setConcat(baseMatrix, userMatrix)
        imageMatrix = tmp
    }

    /** 当前总缩放 = 基准缩放 × 用户缩放（以 1 为单位） */
    private fun currentScale(): Float {
        val v = FloatArray(9)
        userMatrix.getValues(v)
        return hypot(v[Matrix.MSCALE_X], v[Matrix.MSKEW_Y])
    }

    /** 限制平移不出界（留 0 弹性） */
    private fun fixTranslation() {
        val rect = imageRect() ?: return
        var dx = 0f
        var dy = 0f
        if (rect.width() <= width) dx = width / 2f - rect.centerX()
        else {
            if (rect.left > 0) dx = -rect.left
            if (rect.right < width) dx = width - rect.right
        }
        if (rect.height() <= height) dy = height / 2f - rect.centerY()
        else {
            if (rect.top > 0) dy = -rect.top
            if (rect.bottom < height) dy = height - rect.bottom
        }
        tmp.reset()
        tmp.postTranslate(dx, dy)
        userMatrix.postConcat(tmp)
    }

    private fun imageRect(): RectF? {
        val d = drawable ?: return null
        tmp.reset()
        tmp.setConcat(baseMatrix, userMatrix)
        val r = RectF(0f, 0f, d.intrinsicWidth.toFloat(), d.intrinsicHeight.toFloat())
        tmp.mapRect(r)
        return r
    }

    /** 双击缩放动画 */
    private fun animateScale(from: Float, to: Float, fx: Float, fy: Float) {
        animator?.cancel()
        val startM = Matrix(userMatrix)
        animator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 220
            interpolator = DecelerateInterpolator()
            addUpdateListener { av ->
                val t = av.animatedValue as Float
                val s = from + (to - from) * t
                val cur = currentScale()
                val f = if (cur == 0f) 1f else s / cur
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
    override fun onTouchEvent(ev: MotionEvent): Boolean {
        scaleDetector.onTouchEvent(ev)
        gesture.onTouchEvent(ev)
        when (ev.actionMasked) {
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (pulling) {
                    pulling = false
                    if (pullAccum > height * 0.12f) onPullDownRelease?.invoke()
                    else onPullDown?.invoke(0f)
                    pullAccum = 0f
                }
                if (currentScale() < 1f) animateScale(currentScale(), 1f, width / 2f, height / 2f)
                parent?.requestDisallowInterceptTouchEvent(false)
            }
            MotionEvent.ACTION_DOWN -> {
                pullAccum = 0f
                parent?.requestDisallowInterceptTouchEvent(currentScale() > 1.05f)
            }
            MotionEvent.ACTION_MOVE -> {
                parent?.requestDisallowInterceptTouchEvent(currentScale() > 1.05f)
            }
        }
        return true
    }
}
