package com.wanlin.gallery.ui

import android.os.Bundle
import android.widget.Toast
import com.wanlin.gallery.base.BaseActivity
import com.wanlin.gallery.databinding.ActivityMainBinding
import com.wanlin.gallery.util.ScreenUtils

/**
 * 首页：磁贴式入口。
 * 左大磁贴=相册（核心）｜右上=视频｜右下=文件管理｜底部=互传连接｜右上角=设置
 */
class MainActivity : BaseActivity() {

    private lateinit var binding: ActivityMainBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // 圆屏安全区：内容整体往内收
        val inset = ScreenUtils.safeInsetPx(this)
        binding.root.setPadding(inset, binding.root.paddingTop, inset, binding.root.paddingBottom)

        binding.tileGallery.setOnClickListener {
            startActivity(
                android.content.Intent(this, GalleryActivity::class.java)
                    .putExtra(GalleryActivity.EXTRA_MODE, "ALL")
            )
        }
        binding.tileVideo.setOnClickListener {
            startActivity(
                android.content.Intent(this, GalleryActivity::class.java)
                    .putExtra(GalleryActivity.EXTRA_MODE, "VIDEOS")
            )
        }
        binding.tileFiles.setOnClickListener {
            startActivity(android.content.Intent(this, FileManagerActivity::class.java))
        }
        binding.tileTransfer.setOnClickListener {
            startActivity(android.content.Intent(this, TransferActivity::class.java))
        }
        binding.btnSettings.setOnClickListener {
            startActivity(android.content.Intent(this, SettingsActivity::class.java))
        }
    }

    private fun dev(name: String) =
        Toast.makeText(this, "$name · 开发中，见 工作日志.md", Toast.LENGTH_SHORT).show()
}
