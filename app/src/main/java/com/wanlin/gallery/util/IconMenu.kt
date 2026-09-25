package com.wanlin.gallery.util

import android.app.Activity
import android.app.Dialog
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.view.LayoutInflater
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.core.content.ContextCompat
import com.wanlin.gallery.R
import com.wanlin.gallery.ui.AccentManager

/**
 * 图标格长按菜单：圆角卡片 + 2×N 磁贴网格（与首页磁贴设计语言一致），
 * 替代系统 AlertDialog 纯文字列表样式；danger 项（删除）用警示红。
 * 用法：IconMenu.show(activity, 标题, listOf(Cell(图标, 文案){ 点击动作 }, …))，最多 6 项。
 */
object IconMenu {

    class Cell(
        @DrawableRes val icon: Int,
        @StringRes val label: Int,
        val danger: Boolean = false,
        val onClick: () -> Unit
    )

    fun show(activity: Activity, title: CharSequence, cells: List<Cell>) {
        require(cells.size <= 6) { "IconMenu 最多 6 项" }
        val dialog = Dialog(activity)
        val view = LayoutInflater.from(activity).inflate(R.layout.dialog_media_menu, null)
        dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        dialog.window?.setDimAmount(0.6f)

        view.findViewById<TextView>(R.id.tvMenuTitle).text = title
        val rows = intArrayOf(R.id.menuRow0, R.id.menuRow1, R.id.menuRow2)
            .map { view.findViewById<LinearLayout>(it) }
        val danger = ContextCompat.getColor(activity, R.color.danger)
        val accent = AccentManager.color()
        val normal = ContextCompat.getColor(activity, R.color.text_primary)

        cells.forEachIndexed { i, cell ->
            val v = LayoutInflater.from(activity).inflate(R.layout.item_menu_cell, rows[i / 2], false)
            val iv = v.findViewById<ImageView>(R.id.ivIcon)
            val tv = v.findViewById<TextView>(R.id.tvLabel)
            iv.setImageResource(cell.icon)
            tv.setText(cell.label)
            iv.setColorFilter(if (cell.danger) danger else accent)
            tv.setTextColor(if (cell.danger) danger else normal)
            v.setOnClickListener {
                dialog.dismiss()
                cell.onClick()
            }
            rows[i / 2].addView(v)
        }
        DialogStyle.show(activity, dialog, view)   // 统一窗口样式：不占满表屏、点空白即关
    }
}
