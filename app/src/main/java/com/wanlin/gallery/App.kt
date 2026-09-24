package com.wanlin.gallery

import android.app.Application
import com.wanlin.gallery.ui.AccentManager
import com.wanlin.gallery.ui.ScaleManager

/** 应用入口：初始化全局 UI 缩放与点缀色主题 */
class App : Application() {
    override fun onCreate() {
        super.onCreate()
        ScaleManager.init(this)
        AccentManager.init(this)
    }
}
