package com.wanlin.gallery.util

import android.app.Activity
import android.app.Dialog
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.text.InputType
import android.view.LayoutInflater
import android.widget.EditText
import android.widget.TextView
import com.wanlin.gallery.R

/**
 * 品牌化对话框（替代系统 AlertDialog）：
 * 该 ROM 上系统对话框的 positive 按钮不渲染（只有取消），且样式与 App 不统一；
 * 这里全部自建：圆角卡片 bg_about + 强调色/危险红按钮，与 IconMenu、关于页同语言。
 * 用法与 AlertDialog.Builder 等价：info / input / confirm。
 * 窗口样式统一走 [DialogStyle]：不占满表屏、点空白即关（第 10 轮）。
 */
object AppDialogs {

    private fun baseDialog(a: Activity, layout: Int): Pair<Dialog, android.view.View> {
        val d = Dialog(a)
        val v = LayoutInflater.from(a).inflate(layout, null)
        d.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        d.window?.setDimAmount(0.6f)
        return d to v
    }

    /** 信息对话框：标题 + 多行内容 + 全宽"关闭" */
    fun info(a: Activity, title: CharSequence, message: CharSequence) {
        val (d, v) = baseDialog(a, R.layout.dialog_info)
        v.findViewById<TextView>(R.id.tvTitle).text = title
        v.findViewById<TextView>(R.id.tvMessage).text = message
        v.findViewById<TextView>(R.id.btnClose).apply {
            setText(R.string.close)
            setOnClickListener { d.dismiss() }
        }
        DialogStyle.show(a, d, v)
    }

    /** 输入对话框：标题 + 预填内容 + [取消][确定]，确定返回非空文本；[inputType] 默认纯文本 */
    fun input(
        a: Activity,
        title: CharSequence,
        initial: String,
        inputType: Int = InputType.TYPE_CLASS_TEXT,
        onOk: (String) -> Unit
    ) {
        val (d, v) = baseDialog(a, R.layout.dialog_input)
        v.findViewById<TextView>(R.id.tvTitle).text = title
        val et = v.findViewById<EditText>(R.id.etInput)
        et.inputType = inputType
        et.setText(initial)
        et.setSelection(initial.length)
        v.findViewById<TextView>(R.id.btnCancel).apply {
            setText(android.R.string.cancel)
            setOnClickListener { d.dismiss() }
        }
        v.findViewById<TextView>(R.id.btnOk).apply {
            setText(android.R.string.ok)
            setOnClickListener {
                val s = et.text.toString().trim()
                if (s.isEmpty()) return@setOnClickListener
                d.dismiss()
                onOk(s)
            }
        }
        DialogStyle.show(a, d, v)
    }

    /** 确认对话框：标题 + 信息 + [取消][okText]（danger 时确认键为警示红） */
    fun confirm(
        a: Activity,
        title: CharSequence,
        message: CharSequence,
        okText: CharSequence,
        danger: Boolean = false,
        onOk: () -> Unit
    ) {
        val (d, v) = baseDialog(a, R.layout.dialog_confirm)
        v.findViewById<TextView>(R.id.tvTitle).text = title
        v.findViewById<TextView>(R.id.tvMessage).text = message
        v.findViewById<TextView>(R.id.btnCancel).apply {
            setText(android.R.string.cancel)
            setOnClickListener { d.dismiss() }
        }
        v.findViewById<TextView>(R.id.btnOk).apply {
            text = okText
            setOnClickListener {
                d.dismiss()
                onOk()
            }
        }
        // danger=false 时确认键改用强调色底（当前仅删除用 confirm，红底即常态）
        if (!danger) {
            v.findViewById<TextView>(R.id.btnOk).setBackgroundResource(R.drawable.bg_button)
            v.findViewById<TextView>(R.id.btnOk).setTextColor(
                androidx.core.content.ContextCompat.getColor(a, R.color.bg)
            )
        }
        DialogStyle.show(a, d, v)
    }
}
