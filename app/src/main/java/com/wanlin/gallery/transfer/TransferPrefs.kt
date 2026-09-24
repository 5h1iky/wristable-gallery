package com.wanlin.gallery.transfer

import android.content.Context

/** 互传配置（端口等） */
object TransferPrefs {
    private const val PREFS = "transfer"
    private const val KEY_PORT = "port"
    const val DEFAULT_PORT = 8899

    fun port(context: Context): Int =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getInt(KEY_PORT, DEFAULT_PORT)

    fun setPort(context: Context, port: Int) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putInt(KEY_PORT, port.coerceIn(1024, 65535)).apply()
    }
}
