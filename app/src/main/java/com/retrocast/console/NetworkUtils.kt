package com.retrocast.console

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.util.Log

object NetworkUtils {
    private const val TAG = "NetworkUtils"

    fun getLocalIpAddress(context: Context): String? {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

        for (network in cm.allNetworks) {
            val caps = cm.getNetworkCapabilities(network) ?: continue
            if (!caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) &&
                !caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)) continue
            val linkProps = cm.getLinkProperties(network) ?: continue
            for (addr in linkProps.linkAddresses) {
                val host = addr.address.hostAddress ?: continue
                if (host.contains(".")) {
                    Log.i(TAG, "IP: $host on WiFi/Ethernet")
                    return host
                }
            }
        }

        Log.w(TAG, "No WiFi or Ethernet network found")
        return null
    }
}
