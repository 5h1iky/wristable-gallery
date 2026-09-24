package com.wanlin.gallery.ui

import android.content.Context

/**
 * 点缀色主题（四色，仅作用于"可交互/进行中"元素：按钮、进度、选中、开关）。
 * 保存在 SharedPreferences，运行时即时切换。
 */
object AccentManager {

    enum class Accent(val argb: Int, val display: String) {
        ICE_BLUE(0xFF4FC3F7.toInt(), "冰蓝"),
        MINT(0xFF69F0AE.toInt(), "薄荷绿"),
        AMBER(0xFFFFB300.toInt(), "琥珀"),
        NEON_PURPLE(0xFFB388FF.toInt(), "霓虹紫")
    }

    private const val PREFS = "ui_accent"
    private const val KEY = "accent"

    @Volatile
    var current: Accent = Accent.ICE_BLUE
        private set

    fun init(context: Context) {
        val name = prefs(context).getString(KEY, Accent.ICE_BLUE.name)
        current = runCatching { Accent.valueOf(name!!) }.getOrDefault(Accent.ICE_BLUE)
    }

    fun set(accent: Accent, context: Context) {
        current = accent
        prefs(context).edit().putString(KEY, accent.name).apply()
    }

    fun color(): Int = current.argb

    /** 同色微光晕（用于主按钮提升"可按感"），约 8% 透明度 */
    fun glow(): Int = (current.argb and 0x00FFFFFF) or 0x14000000

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
