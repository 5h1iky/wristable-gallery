package com.wanlin.gallery.util

import android.content.Context
import android.content.res.Resources

/** 手表屏幕形态工具：圆屏/方屏检测与安全区 */
object ScreenUtils {

    fun isRound(res: Resources): Boolean =
        res.configuration.isScreenRound

    /** 圆屏水平安全内边距（px）：约 11% 屏宽，避免内容被弧边吃掉 */
    fun safeInsetPx(context: Context): Int =
        if (isRound(context.resources)) {
            (context.resources.displayMetrics.widthPixels * 0.11f).toInt()
        } else 0

    /** 圆屏列表首尾弧形留白（px） */
    fun arcPaddingPx(context: Context): Int =
        if (isRound(context.resources)) {
            (context.resources.displayMetrics.heightPixels * 0.06f).toInt()
        } else 0

    /** 状态栏高度（px，无状态栏的手表上为 0） */
    fun statusBarPx(context: Context): Int {
        val id = context.resources.getIdentifier("status_bar_height", "dimen", "android")
        return if (id > 0) context.resources.getDimensionPixelSize(id) else 0
    }

    /** 底部导航条高度（px，全面屏手势区） */
    fun navBarPx(context: Context): Int {
        val id = context.resources.getIdentifier("navigation_bar_height", "dimen", "android")
        return if (id > 0) context.resources.getDimensionPixelSize(id) else 0
    }
}
