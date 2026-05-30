package com.retrocast.console

import android.os.Build
import io.ktor.client.*
import io.ktor.client.engine.okhttp.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream

object CoreDownloader {
    private val client = HttpClient(OkHttp)
    private const val BASE_URL = "https://buildbot.libretro.com/nightly/android/latest"

    /**
     * Detect the device architecture for Libretro buildbot.
     */
    private fun getArchitecture(): String {
        val abi = Build.SUPPORTED_ABIS[0]
        return when {
            abi.contains("arm64") -> "arm64-v8a"
            abi.contains("armeabi") -> "armeabi-v7a"
            abi.contains("x86_64") -> "x86_64"
            abi.contains("x86") -> "x86"
            else -> "arm64-v8a" // Default to arm64
        }
    }

    /**
     * Download a core from Libretro buildbot.
     * Example: mgba_libretro_android.so
     */
    suspend fun downloadCore(coreName: String, destinationDir: File): Boolean = withContext(Dispatchers.IO) {
        val arch = getArchitecture()
        // Buildbot usually provides cores unzipped or as .so.zip
        // We'll try the .so directly first
        val url = "$BASE_URL/$arch/$coreName.zip"
        val tempZip = File(destinationDir, "$coreName.zip")

        try {
            val response: HttpResponse = client.get(url)
            if (response.status.value !in 200..299) {
                return@withContext false
            }

            val body = response.readBytes()
            FileOutputStream(tempZip).use { it.write(body) }

            // Extract the .so from the zip
            val zis = java.util.zip.ZipInputStream(tempZip.inputStream())
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
            zis.close()
            tempZip.delete()
            return@withContext true
        } catch (e: Exception) {
            android.util.Log.e("CoreDownloader", "Failed to download core: $coreName", e)
            tempZip.delete()
            return@withContext false
        }
    }
}
