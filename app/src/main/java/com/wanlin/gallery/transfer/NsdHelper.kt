package com.wanlin.gallery.transfer

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo

/** 局域网服务发现（mDNS/NSD，系统能力零依赖）：手机/电脑可发现"腕能图库" */
class NsdHelper(private val context: Context) {

    private var nsd: NsdManager? = null
    private var registered = false

    private val listener = object : NsdManager.RegistrationListener {
        override fun onServiceRegistered(info: NsdServiceInfo) { registered = true }
        override fun onRegistrationFailed(info: NsdServiceInfo, errorCode: Int) { registered = false }
        override fun onServiceUnregistered(info: NsdServiceInfo) { registered = false }
        override fun onUnregistrationFailed(info: NsdServiceInfo, errorCode: Int) {}
    }

    fun register(port: Int) {
        runCatching {
            nsd = context.getSystemService(Context.NSD_SERVICE) as NsdManager
            val info = NsdServiceInfo().apply {
                serviceName = "WanLinGallery"
                serviceType = "_wanlin._tcp."
                setPort(port)
            }
            nsd?.registerService(info, NsdManager.PROTOCOL_DNS_SD, listener)
        }
    }

    fun unregister() {
        runCatching { nsd?.unregisterService(listener) }
        registered = false
    }
}
