package com.wanlin.gallery.ui

import android.os.Bundle
import android.text.InputType
import android.view.View
import android.widget.Toast
import com.wanlin.gallery.R
import com.wanlin.gallery.base.BaseActivity
import com.wanlin.gallery.databinding.ActivitySettingsBinding
import com.wanlin.gallery.transfer.TransferPrefs

/**
 * 设置页：UI 缩放（0.75~1.5，松手即时生效）、四色主题、互传端口、关于。
 */
class SettingsActivity : BaseActivity() {

    private lateinit var binding: ActivitySettingsBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.btnBack.setOnClickListener { finish() }
        setupScale()
        setupAccents()
        setupPort()
        setupAbout()
    }

    /** UI 缩放滑条（Material Slider）：拖动时实时显示数值，松手应用并重建界面 */
    private fun setupScale() {
        val s = binding.seekScale
        // 值域在代码设置（XML 的 value/valueFrom/valueTo 是 android 命名空间，且避免浮点步进校验坑）
        s.valueFrom = 0.75f
        s.valueTo = 1.5f
        s.value = ScaleManager.userScale
        binding.tvScaleVal.text = "%.2f×".format(ScaleManager.userScale)
        val accent = AccentManager.color()
        // 颜色 API 是 TintList 系（实测 AAR 确认），Kotlin 属性语法会撞内部私有字段，用显式方法
        s.setTrackActiveTintList(android.content.res.ColorStateList.valueOf(accent))
        s.setThumbTintList(android.content.res.ColorStateList.valueOf(accent))
        s.setHaloTintList(android.content.res.ColorStateList.valueOf((accent and 0x00FFFFFF) or 0x22000000))
        s.addOnChangeListener { _, v, fromUser ->
            if (fromUser) binding.tvScaleVal.text = "%.2f×".format(v)
        }
        s.addOnSliderTouchListener(object : com.google.android.material.slider.Slider.OnSliderTouchListener {
            override fun onStartTrackingTouch(slider: com.google.android.material.slider.Slider) {}
            override fun onStopTrackingTouch(slider: com.google.android.material.slider.Slider) {
                ScaleManager.setUserScale(slider.value, this@SettingsActivity)
                recreate()   // 即时生效：重建后新密度生效
            }
        })
    }

    /** 四色点缀主题 */
    private fun setupAccents() {
        val rows = listOf(
            binding.rowIceBlue to AccentManager.Accent.ICE_BLUE,
            binding.rowMint to AccentManager.Accent.MINT,
            binding.rowAmber to AccentManager.Accent.AMBER,
            binding.rowPurple to AccentManager.Accent.NEON_PURPLE
        )
        rows.forEach { (row, accent) ->
            (row.vDot.background as android.graphics.drawable.GradientDrawable).setColor(accent.argb)
            row.tvAccentName.text = accent.display
            row.tvCheck.visibility =
                if (accent == AccentManager.current) View.VISIBLE else View.INVISIBLE
            row.root.setOnClickListener {
                AccentManager.set(accent, this)
                recreate()
            }
        }
    }

    private fun setupPort() {
        binding.tvPortVal.text = TransferPrefs.port(this).toString()
        binding.rowPort.setOnClickListener {
            // 系统 AlertDialog 在本 ROM 上 positive 按钮不渲染，且手表上会撑满屏 → 统一走 AppDialogs
            com.wanlin.gallery.util.AppDialogs.input(
                this, getString(R.string.port), TransferPrefs.port(this).toString(),
                inputType = InputType.TYPE_CLASS_NUMBER
            ) { s ->
                val p = s.toIntOrNull()
                if (p == null || p !in 1024..65535) {
                    Toast.makeText(this, R.string.port_invalid, Toast.LENGTH_SHORT).show()
                } else {
                    TransferPrefs.setPort(this, p)
                    binding.tvPortVal.text = p.toString()
                }
            }
        }
    }

    private fun setupAbout() {
        binding.rowAbout.setOnClickListener { showAboutDialog() }
    }

    /** 关于页：自建圆角对话框（图标/名称/版本/作者 + 主页跳转） */
    private fun showAboutDialog() {
        val dlg = android.app.Dialog(this)
        dlg.window?.setBackgroundDrawableResource(android.R.color.transparent)
        val v = layoutInflater.inflate(R.layout.dialog_about, null)
        val ver = runCatching { packageManager.getPackageInfo(packageName, 0).versionName }.getOrNull() ?: "1.0.0"
        v.findViewById<android.widget.TextView>(R.id.tvVersion).text = "v$ver"
        (v.findViewById<View>(R.id.btnVisit).background as android.graphics.drawable.GradientDrawable)
            .setColor(AccentManager.color())
        v.findViewById<View>(R.id.btnVisit).setOnClickListener {
            runCatching {
                startActivity(
                    android.content.Intent(
                        android.content.Intent.ACTION_VIEW,
                        android.net.Uri.parse("https://5h1iky.github.io/portfolio/")
                    )
                )
            }.onFailure {
                Toast.makeText(this, R.string.no_app_open, Toast.LENGTH_SHORT).show()
            }
        }
        v.findViewById<View>(R.id.btnClose).setOnClickListener { dlg.dismiss() }
        com.wanlin.gallery.util.DialogStyle.show(this, dlg, v)   // 统一窗口样式：不占满表屏、点空白即关
    }
}
