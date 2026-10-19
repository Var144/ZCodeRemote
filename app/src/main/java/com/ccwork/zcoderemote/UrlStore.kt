package com.ccwork.zcoderemote

import android.content.Context

/**
 * 远程链接的持久化存储（SharedPreferences 单例）。
 * 保存扫码/手动填写的 ZCode Remote URL。
 */
object UrlStore {
    private const val PREFS_NAME = "zcode_remote_prefs"
    private const val KEY_URL = "zcode_remote_url"

    fun read(context: Context): String =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(KEY_URL, "") ?: ""

    fun write(context: Context, url: String) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit().putString(KEY_URL, url).apply()
    }
}
