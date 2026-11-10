package com.chronocapsule.net

import android.content.Context

/**
 * 云端地址配置。
 *
 * 为什么必须可配置：模拟器用 10.0.2.2 / 127.0.0.1，真机必须用电脑的局域网 IP
 * （比如 http://192.168.1.5:8765），而这个 IP 换个网络就变。写死在代码里
 * 意味着每次联调都要重新打包，队友也没法自己改。
 *
 * 存储用 SharedPreferences：一个字段，不需要数据库。
 */
object CloudConfig {

    private const val PREFS = "chrono_config"
    private const val KEY_BASE_URL = "base_url"

    /** 界面上展示的默认值（模拟器地址） */
    val DEFAULT_BASE_URL: String = CloudClient.DEFAULT_BASE_URLS.first()

    /**
     * 实际要尝试的地址列表。
     * 用户配置过就只用配置的（真机上 10.0.2.2 根本连不通，多试两个只会多等 10 秒）；
     * 没配置过才退回模拟器默认列表。
     */
    fun baseUrls(context: Context): List<String> {
        val saved = prefs(context).getString(KEY_BASE_URL, null)?.trim().orEmpty()
        return if (saved.isEmpty()) CloudClient.DEFAULT_BASE_URLS else listOf(saved)
    }

    /** 保存地址；允许用户只填 IP:端口，自动补 http:// */
    fun setBaseUrl(context: Context, url: String) {
        val normalized = normalize(url)
        prefs(context).edit().putString(KEY_BASE_URL, normalized).apply()
    }

    fun normalize(raw: String): String {
        val trimmed = raw.trim().trimEnd('/')
        if (trimmed.isEmpty()) return ""
        return if (trimmed.startsWith("http://") || trimmed.startsWith("https://")) {
            trimmed
        } else {
            "http://$trimmed"
        }
    }

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
