package com.martin.ruby.briefing

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities

/** Whether the phone has an internet connection right now (not in airplane mode, Wi-Fi or data up). */
object Connection {
    fun isOnline(context: Context): Boolean {
        val manager = context.getSystemService(ConnectivityManager::class.java) ?: return false
        val capabilities = manager.getNetworkCapabilities(manager.activeNetwork) ?: return false
        return capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }
}
