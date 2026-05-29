package com.sharescreen.console

import android.content.Context
import android.net.wifi.WifiManager
import android.util.Log
import java.math.BigInteger
import java.net.InetAddress
import java.net.NetworkInterface
import java.nio.ByteOrder

object NetworkUtils {
    private const val TAG = "NetworkUtils"

    fun getLocalIpAddress(context: Context): String? {
        try {
            val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            val info = wifiManager.connectionInfo
            if (info != null) {
                var ipAddress = info.ipAddress
                if (ipAddress != 0) {
                    if (ByteOrder.nativeOrder() == ByteOrder.LITTLE_ENDIAN) {
                        ipAddress = Integer.reverseBytes(ipAddress)
                    }
                    val ipByteArray = BigInteger.valueOf(ipAddress.toLong()).toByteArray()
                    val ip = try {
                        InetAddress.getByAddress(ipByteArray).hostAddress
                    } catch (ex: Exception) {
                        Log.w(TAG, "Failed to parse WifiManager IP", ex)
                        null
                    }
                    if (ip != null && ip != "0.0.0.0" && ip != "127.0.0.1") {
                        Log.i(TAG, "IP: $ip (WifiManager)")
                        return ip
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "WifiManager failed", e)
        }

        try {
            val interfaces = NetworkInterface.getNetworkInterfaces()
            while (interfaces.hasMoreElements()) {
                val networkInterface = interfaces.nextElement()
                val addresses = networkInterface.inetAddresses
                while (addresses.hasMoreElements()) {
                    val address = addresses.nextElement()
                    if (!address.isLoopbackAddress && !address.isLinkLocalAddress) {
                        val host = address.hostAddress ?: continue
                        if (host.contains(".") && (host.startsWith("192.168.") || host.startsWith("10.") || host.startsWith("172."))) {
                            Log.i(TAG, "IP: $host on ${networkInterface.name}")
                            return host
                        }
                    }
                }
            }
        } catch (ex: Exception) {
            Log.w(TAG, "Fallback failed", ex)
        }
        return null
    }
}
