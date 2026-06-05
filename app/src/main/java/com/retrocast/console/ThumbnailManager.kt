package com.retrocast.console

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

object ThumbnailManager {
    private const val BOXART_BASE = "https://thumbnails.libretro.com/Nintendo%20-%20Game%20Boy%20Advance/Named_Boxarts"
    private const val NETWORK_TIMEOUT_MS = 4000
    private const val TAG = "ThumbnailManager"

    private var cacheDir: File? = null

    fun init(context: Context) {
        cacheDir = File(context.filesDir, "thumbnails").apply { mkdirs() }
    }

    fun deleteCache(romName: String) {
        val dir = cacheDir ?: return
        File(dir, "$romName.png").delete()
    }

    suspend fun load(romName: String): Bitmap? {
        val dir = cacheDir ?: return null

        val cacheFile = File(dir, "$romName.png")
        if (cacheFile.exists()) {
            return BitmapFactory.decodeFile(cacheFile.absolutePath)
        }

        val cleaned = romName.replace(Regex("\\(.*?\\)"), "").replace(Regex("\\[.*?\\]"), "").trim()
        val baseNames = listOf(romName, cleaned).distinct()
        val regionSuffixes = listOf("", " (USA)", " (Japan)", " (Europe)", " (USA, Europe)", " (World)")

        return withContext(Dispatchers.IO) {
            for (base in baseNames) {
                for (suffix in regionSuffixes) {
                    val target = base + suffix
                    val encoded = URLEncoder.encode(target, "UTF-8").replace("+", "%20")
                    val url = "$BOXART_BASE/$encoded.png"
                    try {
                        val conn = URL(url).openConnection() as HttpURLConnection
                        conn.connectTimeout = NETWORK_TIMEOUT_MS
                        conn.readTimeout = NETWORK_TIMEOUT_MS
                        conn.connect()
                        if (conn.responseCode == 200) {
                            val bytes = conn.inputStream.use { it.readBytes() }
                            cacheFile.outputStream().use { it.write(bytes) }
                            Log.i(TAG, "Cached thumbnail for $romName")
                            return@withContext BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                        }
                        conn.disconnect()
                    } catch (e: Exception) {
                        Log.w(TAG, "Failed to load $url: ${e.message}")
                    }
                }
            }
            null
        }
    }
}
