package com.example.ad_app

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.wifi.WifiManager

/**
 * 현재 "연결된" Wi-Fi SSID를 가져옴.
 * - Wi-Fi가 아니면 null
 * - 권한/설정(Location OFF 등) 때문에 <unknown ssid>면 null
 */
fun getCurrentWifiSsid(context: Context): String? {
    val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    val network = cm.activeNetwork ?: return null
    val caps = cm.getNetworkCapabilities(network) ?: return null
    if (!caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) return null

    val wm = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
    val info = wm.connectionInfo ?: return null

    val raw = info.ssid ?: return null
    if (raw == WifiManager.UNKNOWN_SSID || raw == "<unknown ssid>") return null

    // "\"blue\"" 처럼 따옴표가 붙는 경우가 있어 제거
    return raw.trim().trim('"')
}

/**
 * SSID -> 장소 라벨로 매핑
 * - 여기서 "blue" = lab(연구실)
 */
fun resolvePlaceLabelFromWifi(ssid: String?): String {
    val s = ssid?.trim()?.lowercase() ?: return "unknown"
    return when (s) {
        "blue" -> "lab"
        // 필요하면 계속 추가
        // "myhomewifi" -> "home"
        // "companywifi" -> "office"
        else -> "other"
    }
}
