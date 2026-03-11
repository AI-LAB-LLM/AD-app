package com.example.ad_app.env

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.wifi.WifiManager
import android.annotation.SuppressLint


@SuppressLint("MissingPermission")
fun getCurrentWifiSsid(context: Context): String? {
    val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    val network = cm.activeNetwork ?: return null
    val caps = cm.getNetworkCapabilities(network) ?: return null
    if (!caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) return null

    val wm = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
    val info = wm.connectionInfo ?: return null

    val raw = info.ssid ?: return null
    return sanitizeSsid(raw)
}

// SSID 문자열 정리
fun sanitizeSsid(raw: String?): String? {
    val s = raw?.trim()?.trim('"') ?: return null
    if (s.isBlank()) return null
    if (s == WifiManager.UNKNOWN_SSID) return null
    if (s.equals("<unknown ssid>", ignoreCase = true)) return null
    return s
}

// SSID -> 장소 라벨 매핑
fun resolvePlaceLabelFromWifi(ssid: String?): String {
    val s = sanitizeSsid(ssid)?.lowercase() ?: return "unknown"

    return when (s) {
        "blue" -> "lab"
        "sk_wifigigabe54_5g" -> "home"
        // "companywifi" -> "office"
        else -> "other"
    }
}
