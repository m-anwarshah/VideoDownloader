package com.manwar.videodownloader

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities

object Net {
    /** true if current network is WiFi/Ethernet, or mobile data is allowed */
    fun downloadAllowed(context: Context, allowMobileData: Boolean): Boolean {
        val cm = context.getSystemService(ConnectivityManager::class.java)
        val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return false
        val isWifi = caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
                caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)
        return isWifi || allowMobileData
    }
}
