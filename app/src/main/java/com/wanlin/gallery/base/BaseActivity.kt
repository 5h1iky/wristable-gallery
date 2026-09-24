package com.wanlin.gallery.base

import android.content.Context
import android.content.res.Resources
import android.os.Bundle
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import com.wanlin.gallery.ui.ScaleManager

/** 全局缩放基类：所有 Activity 继承（含系统栏 inset 处理） */
open class BaseActivity : AppCompatActivity() {

    override fun attachBaseContext(newBase: Context?) {
        super.attachBaseContext(newBase?.let { ScaleManager.wrap(it) })
    }

    override fun getResources(): Resources {
        val res = super.getResources()
        ScaleManager.applyTo(res)
        return res
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // 系统栏 inset：子类 setContentView 完成后统一给内容区加 padding
        window.decorView.post {
            applySystemBarPadding(findViewById(android.R.id.content))
        }
    }

    /**
     * 根视图避开系统栏：读【真实 WindowInsets】而非 ROM 资源值（手表 ROM 的
     * navigation_bar_height 可能是巨大的错误值，曾导致下半屏被 padding 吃掉全黑）。
     * 并对 inset 钳制 ≤ 屏高 10%，双保险。
     */
    private fun applySystemBarPadding(root: View) {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        val l = root.paddingLeft
        val t = root.paddingTop
        val r = root.paddingRight
        val b = root.paddingBottom
        ViewCompat.setOnApplyWindowInsetsListener(root) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            val maxInset = (resources.displayMetrics.heightPixels * 0.10f).toInt()
            v.setPadding(
                l,
                t + bars.top.coerceAtMost(maxInset),
                r,
                b + bars.bottom.coerceAtMost(maxInset)
            )
            insets
        }
        root.requestApplyInsets()
    }
}
