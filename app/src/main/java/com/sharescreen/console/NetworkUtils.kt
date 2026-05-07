package com.sharescreen.console

import android.content.Context
import android.net.wifi.WifiManager
import java.math.BigInteger
import java.net.InetAddress
import java.net.NetworkInterface
import java.nio.ByteOrder

object NetworkUtils {
    /**
     * Get the local IP address assigned to the WiFi interface.
     */
    @Suppress("DEPRECATION")
    fun getLocalIpAddress(context: Context): String? {
        try {
            // Primary method: WifiManager (Most reliable for WiFi assigned IP)
            val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            val info = wifiManager.connectionInfo
            if (info != null) {
                var ipAddress = info.ipAddress
                if (ipAddress != 0) {
                    // Convert little-endian to big-endian if needed
                    if (ByteOrder.nativeOrder().equals(ByteOrder.LITTLE_ENDIAN)) {
                        ipAddress = Integer.reverseBytes(ipAddress)
                    }

                    val ipByteArray = BigInteger.valueOf(ipAddress.toLong()).toByteArray()
                    val ipAddressString = try {
                        InetAddress.getByAddress(ipByteArray).hostAddress
                    } catch (ex: Exception) {
                        null
                    }

                    if (ipAddressString != null && ipAddressString != "0.0.0.0" && ipAddressString != "127.0.0.1") {
                        return ipAddressString
                    }
                }
            }

            // Fallback: Robust iteration of all network interfaces
            val interfaces = NetworkInterface.getNetworkInterfaces()
            while (interfaces.hasMoreElements()) {
                val networkInterface = interfaces.nextElement()
                val addresses = networkInterface.inetAddresses
                while (addresses.hasMoreElements()) {
                    val address = addresses.nextElement()
                    if (!address.isLoopbackAddress && !address.isLinkLocalAddress) {
                        val host = address.hostAddress ?: continue
                        // Prioritize IPv4 and common local private ranges
                        if (host.contains(".") && (host.startsWith("192.168.") || host.startsWith("10.") || host.startsWith("172."))) {
                            return host
                        }
                    }
                }
            }
        } catch (ex: Exception) {
            ex.printStackTrace()
        }
        return null
    }
}
