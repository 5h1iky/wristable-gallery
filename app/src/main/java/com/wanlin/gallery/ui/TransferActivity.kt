package com.wanlin.gallery.ui

import android.graphics.Bitmap
import android.graphics.Color
import android.os.Bundle
import android.view.View
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.wanlin.gallery.R
import com.wanlin.gallery.base.BaseActivity
import com.wanlin.gallery.databinding.ActivityTransferBinding
import com.wanlin.gallery.transfer.HttpFileServer
import com.wanlin.gallery.transfer.NetUtils
import com.wanlin.gallery.transfer.NsdHelper
import com.wanlin.gallery.transfer.TransferPrefs

/**
 * 互传连接页：手表起 HTTP 服务，出示二维码给手机扫（或浏览器直输 IP），
 * 手机/电脑网页端可拖拽上传、下载手表文件。
 */
class TransferActivity : BaseActivity() {

    private lateinit var binding: ActivityTransferBinding
    private var server: HttpFileServer? = null
    private var nsd: NsdHelper? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityTransferBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.btnBack.setOnClickListener { finish() }
        binding.btnToggle.setOnClickListener { if (server?.running == true) stopServer() else startServer() }
        startServer()
    }

    private fun startServer() {
        val port = TransferPrefs.port(this)
        server = HttpFileServer(this, port) { msg -> runOnUiThread { binding.tvStatus.text = msg } }
        server?.start()
        nsd = NsdHelper(this).also { it.register(port) }

        val ip = NetUtils.localIp()
        val url = "http://$ip:$port/"
        binding.tvUrl.text = url
        binding.qrImage.setImageBitmap(qrBitmap(url))
        binding.qrImage.visibility = View.VISIBLE
        binding.tvStatus.text = getString(R.string.service_running)
        binding.btnToggle.text = getString(R.string.service_stop)
        (binding.btnToggle.background as android.graphics.drawable.GradientDrawable)
            .setColor(0xFF3A3A3C.toInt())
    }

    private fun stopServer() {
        server?.stop()
        nsd?.unregister()
        binding.qrImage.visibility = View.GONE
        binding.tvUrl.text = ""
        binding.tvStatus.text = getString(R.string.service_stopped)
        binding.btnToggle.text = getString(R.string.service_start)
        (binding.btnToggle.background as android.graphics.drawable.GradientDrawable)
            .setColor(AccentManager.color())
    }

    /** zxing 生成二维码（白底黑码，手机识别率最高） */
    private fun qrBitmap(text: String): Bitmap {
        val size = 512
        val matrix = QRCodeWriter().encode(
            text, BarcodeFormat.QR_CODE, size, size,
            mapOf(EncodeHintType.MARGIN to 1)
        )
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.RGB_565)
        for (x in 0 until size) {
            for (y in 0 until size) {
                bmp.setPixel(x, y, if (matrix.get(x, y)) Color.BLACK else Color.WHITE)
            }
        }
        return bmp
    }

    override fun onDestroy() {
        server?.stop()
        nsd?.unregister()
        super.onDestroy()
    }
}
