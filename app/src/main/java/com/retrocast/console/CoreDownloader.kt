package com.retrocast.console

import android.os.Build
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL

object CoreDownloader {
    private const val BASE_URL = "https://buildbot.libretro.com/nightly/android/latest"

    private fun getArchitecture(): String {
        val abi = Build.SUPPORTED_ABIS[0]
        return when {
            abi.contains("arm64") -> "arm64-v8a"
            abi.contains("armeabi") -> "armeabi-v7a"
            abi.contains("x86_64") -> "x86_64"
            abi.contains("x86") -> "x86"
            else -> "arm64-v8a"
        }
    }

    suspend fun downloadCore(coreName: String, destinationDir: File): Boolean = withContext(Dispatchers.IO) {
        val arch = getArchitecture()
        val url = "$BASE_URL/$arch/$coreName.zip"
        val tempZip = File(destinationDir, "$coreName.zip")

        try {
            val connection = URL(url).openConnection() as HttpURLConnection
            connection.connectTimeout = 15000
            connection.readTimeout = 30000
            connection.instanceFollowRedirects = true

            if (connection.responseCode !in 200..299) {
                connection.disconnect()
                return@withContext false
            }

            connection.inputStream.use { input ->
                FileOutputStream(tempZip).use { output ->
                    input.copyTo(output)
                }
            }
            connection.disconnect()

            java.util.zip.ZipInputStream(tempZip.inputStream()).use { zis ->
                var entry = zis.nextEntry
                while (entry != null) {
                    if (entry.name.endsWith(".so")) {
                        val outFile = File(destinationDir, coreName)
                        FileOutputStream(outFile).use { zis.copyTo(it) }
                        zis.closeEntry()
                        break
                    }
                    entry = zis.nextEntry
                }
            }
            tempZip.delete()
            return@withContext true
        } catch (e: Exception) {
            android.util.Log.e("CoreDownloader", "Failed to download core: $coreName", e)
            tempZip.delete()
            return@withContext false
        }
    }
}
