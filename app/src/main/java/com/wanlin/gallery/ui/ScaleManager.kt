package com.wanlin.gallery.ui

import android.content.Context
import android.content.res.Resources
import android.util.DisplayMetrics
import kotlin.math.roundToInt

/**
 * 全局 UI 缩放引擎（核心适配需求）。
 *
 * 设计基准：400×400px @ 186dpi 1:1 屏 → 画布宽 344dp。
 * 运行时覆写 DisplayMetrics.density/densityDpi/scaledDensity，
 * 使任何分辨率的表都等比还原设计稿，userScale 再整体放大/缩小控件（0.75~1.5，即时生效）。
 */
object ScaleManager {

    private const val PREFS = "ui_scale"
    private const val KEY_SCALE = "scale"

    /** 设计画布宽（dp）= 400px / (186/160) ≈ 344 */
    private const val DESIGN_WIDTH_DP = 400f / 1.1625f

    /** 用户可调全局缩放，1.0 = 设计原稿 */
    @Volatile
    var userScale = 1.0f
        private set

    fun init(context: Context) {
        userScale = prefs(context).getFloat(KEY_SCALE, 1f).coerceIn(0.75f, 1.5f)
    }

    fun setUserScale(scale: Float, context: Context) {
        userScale = scale.coerceIn(0.75f, 1.5f)
        prefs(context).edit().putFloat(KEY_SCALE, userScale).apply()
    }

    /** 把缩放应用到任意 Resources（幂等，可反复调用） */
    fun applyTo(res: Resources) {
        val w = res.displayMetrics.widthPixels.toFloat()
        if (w <= 0f) return
        val density = w / DESIGN_WIDTH_DP * userScale
        res.displayMetrics.apply {
            this.density = density
            this.densityDpi = (density * 160f).roundToInt()
            this.scaledDensity = density * res.configuration.fontScale
        }
    }

    /** Activity.attachBaseContext 用：返回应用了缩放的 Context */
    fun wrap(base: Context): Context = object : android.content.ContextWrapper(base) {
        override fun getResources(): Resources = base.resources.also { applyTo(it) }
        override fun getAssets(): android.content.res.AssetManager = base.assets
    }

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
