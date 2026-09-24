package com.wanlin.gallery.widget

import android.content.Context
import android.util.AttributeSet
import android.widget.FrameLayout

/** 高=宽 的方形容器（相册网格单元） */
class SquareFrameLayout @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : FrameLayout(context, attrs) {
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec)
        super.onMeasure(widthMeasureSpec, MeasureSpec.makeMeasureSpec(w, MeasureSpec.EXACTLY))
    }
}
