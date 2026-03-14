package com.example.relapse_watch.services

import android.content.Context
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import javax.inject.Inject
import javax.inject.Singleton
import com.google.firebase.storage.FirebaseStorage
import kotlinx.coroutines.tasks.await

@Singleton
class MediaCacheManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val firebaseStorage: FirebaseStorage
) {

    private val cacheDir: File = File(context.cacheDir, CACHE_FOLDER_NAME).apply {
        if (!exists()) {
            mkdirs()
        }
    }

    /**
     * Downloads a file from the given HTTPS URL if it doesn't already exist or if the size differs.
     * Firebase gs:// URLs MUST be resolved to HTTPS download URLs before calling this function.
     */
    suspend fun downloadMedia(remoteUrl: String, fileName: String): Result<File> = withContext(Dispatchers.IO) {
        try {
            if (remoteUrl.isBlank()) return@withContext Result.failure(IllegalArgumentException("URL is blank"))

            val resolvedUrl = if (remoteUrl.startsWith("gs://")) {
                firebaseStorage.getReferenceFromUrl(remoteUrl).downloadUrl.await().toString()
            } else {
                remoteUrl
            }

            if (!resolvedUrl.startsWith("http")) return@withContext Result.failure(IllegalArgumentException("URL must be HTTP/HTTPS"))

            val targetFile = File(cacheDir, fileName)

            // Simplistic check: If it exists we assume it's valid for now.
            // Ideally we'd use ETags or Firebase File metadata changes.
            if (targetFile.exists() && targetFile.length() > 0) {
                Log.d(TAG, "File already cached: $fileName")
                return@withContext Result.success(targetFile)
            }

            enforceCacheLimit()

            Log.d(TAG, "Starting download for: $fileName")
            val url = URL(resolvedUrl)
            val connection = url.openConnection() as HttpURLConnection
            connection.connectTimeout = 15000
            connection.readTimeout = 15000
            connection.requestMethod = "GET"
            connection.connect()

            if (connection.responseCode != HttpURLConnection.HTTP_OK) {
                return@withContext Result.failure(Exception("Server returned HTTP ${connection.responseCode}"))
            }

            val inputStream = connection.inputStream
            val outputStream = FileOutputStream(targetFile)

            val buffer = ByteArray(4096)
            var bytesRead: Int
            while (inputStream.read(buffer).also { bytesRead = it } != -1) {
                outputStream.write(buffer, 0, bytesRead)
            }

            outputStream.flush()
            outputStream.close()
            inputStream.close()
            connection.disconnect()

            Log.d(TAG, "Download complete: $fileName")
            Result.success(targetFile)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to download media: $remoteUrl", e)
            Result.failure(e)
        }
    }

    fun getCachedFile(fileName: String): File? {
        val file = File(cacheDir, fileName)
        return if (file.exists()) file else null
    }

    fun getCacheSize(): Long {
        return cacheDir.listFiles()?.sumOf { it.length() } ?: 0L
    }
    
    fun clearCache() {
        cacheDir.listFiles()?.forEach { it.delete() }
        Log.d(TAG, "Cache cleared")
    }

    /**
     * Enforces the MAX_CACHE_SIZE limit by deleting the oldest files until we are below the limit.
     */
    private fun enforceCacheLimit() {
        var currentSize = getCacheSize()
        if (currentSize <= MAX_CACHE_SIZE_BYTES) return

        Log.d(TAG, "Cache limit exceeded. Current size: $currentSize bytes. Evicting old files...")

        val files = cacheDir.listFiles()?.toMutableList() ?: return
        // Sort by last modified ascending (oldest first)
        files.sortBy { it.lastModified() }

        for (file in files) {
            val length = file.length()
            if (file.delete()) {
                currentSize -= length
                Log.d(TAG, "Evicted file: ${file.name}")
            }
            if (currentSize <= MAX_CACHE_SIZE_BYTES) {
                break
            }
        }
    }

    companion object {
        private const val TAG = "MediaCacheManager"
        private const val CACHE_FOLDER_NAME = "geo_reminders_media"
        // 200 MB limit
        private const val MAX_CACHE_SIZE_BYTES = 200L * 1024L * 1024L
    }
}
