package com.wanlin.gallery.transfer

import java.net.NetworkInterface

/** 网络工具：取本机 WiFi IPv4（同一 WiFi 下互传用） */
object NetUtils {

    /** 遍历网卡取局域网 IPv4（site-local），拿不到回 127.0.0.1 */
    fun localIp(): String {
        try {
            val interfaces = NetworkInterface.getNetworkInterfaces()?.toList().orEmpty()
            for (ni in interfaces) {
                if (!ni.isUp || ni.isLoopback) continue
                val addrs = ni.inetAddresses?.toList().orEmpty()
                for (a in addrs) {
                    if (!a.isLoopbackAddress && a is java.net.Inet4Address && a.isSiteLocalAddress) {
                        return a.hostAddress ?: continue
                    }
                }
            }
        } catch (_: Exception) {
        }
        return "127.0.0.1"
    }
}
