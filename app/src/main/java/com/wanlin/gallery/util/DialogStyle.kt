package com.wanlin.gallery.util

import android.app.Activity
import android.app.Dialog
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ScrollView

/**
 * 对话框统一窗口样式（手表适配，第 10 轮）：
 * 窗口固定为屏宽 86% × 屏高 84%，卡片四周永远留出空白可点关闭；
 * 内容过高时卡片内部滚动（ScrollView），不再占满整块表屏。
 * 点击空白（窗口内卡片外 / 窗口外）均 dismiss。
 */
object DialogStyle {

    private const val WIDTH_RATIO = 0.86f
    private const val HEIGHT_RATIO = 0.84f

    /** 套统一窗口样式并显示。[content] 为各对话框卡片根布局。 */
    fun show(a: Activity, d: Dialog, content: View) {
        val scroll = ScrollView(a).apply {
            isVerticalScrollBarEnabled = false
            clipToPadding = false
            // 空白区点击关闭（卡片子 View 会优先消费自己的点击）
            setOnClickListener { d.dismiss() }
        }
        scroll.addView(
            content,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.CENTER
            )
        )
        d.setContentView(scroll)
        d.setCancelable(true)
        d.setCanceledOnTouchOutside(true)
        d.show()
        val dm = a.resources.displayMetrics
        d.window?.setLayout(
            (dm.widthPixels * WIDTH_RATIO).toInt(),
            (dm.heightPixels * HEIGHT_RATIO).toInt()
        )
        // 双保险：窗口内未被 ScrollView 覆盖的区域（理论上没有）也点空白即关
        d.window?.decorView?.setOnClickListener { d.dismiss() }
    }
}
