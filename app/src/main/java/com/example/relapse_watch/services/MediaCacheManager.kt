package com.example.relapse_watch.services

import android.content.Context
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton
import com.google.firebase.storage.FirebaseStorage
import kotlinx.coroutines.tasks.await

@Singleton
class MediaCacheManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val firebaseStorage: FirebaseStorage
) {

    private val downloadLocks = ConcurrentHashMap<String, Mutex>()

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

            val targetFileName = resolveCacheFileName(fileName, resolvedUrl)
            val targetFile = File(cacheDir, targetFileName)
            val lock = downloadLocks.getOrPut(targetFileName) { Mutex() }

            lock.withLock {
                // Reuse any already cached variant (including extension-aware files).
                val existing = getCachedFile(fileName)
                if (existing != null && existing.exists() && existing.length() > 0) {
                    val isLegacyBaseFile = existing.name == fileName && !fileName.contains('.')
                    val needsTypedMigration =
                        isLegacyBaseFile && targetFileName != fileName && !targetFile.exists()

                    if (!needsTypedMigration) {
                        Log.d(TAG, "[R_TRACE][CACHE] File already cached: ${existing.name}")
                        return@withLock Result.success(existing)
                    }

                    Log.d(
                        TAG,
                        "[R_TRACE][CACHE] Legacy cache detected for $fileName; refreshing typed cache as $targetFileName"
                    )
                }

                if (targetFile.exists() && targetFile.length() > 0) {
                    Log.d(TAG, "[R_TRACE][CACHE] File already cached: $targetFileName")
                    return@withLock Result.success(targetFile)
                }

                val partialFile = File(cacheDir, "$targetFileName$PARTIAL_SUFFIX")
                if (partialFile.exists()) {
                    runCatching { partialFile.delete() }
                }

                enforceCacheLimit()

                Log.d(TAG, "[R_TRACE][CACHE] Starting download for: $targetFileName")
                val connection = (URL(resolvedUrl).openConnection() as HttpURLConnection).apply {
                    connectTimeout = 15000
                    readTimeout = 15000
                    requestMethod = "GET"
                }

                try {
                    connection.connect()

                    if (connection.responseCode != HttpURLConnection.HTTP_OK) {
                        return@withLock Result.failure(Exception("Server returned HTTP ${connection.responseCode}"))
                    }

                    connection.inputStream.use { inputStream ->
                        FileOutputStream(partialFile).use { outputStream ->
                            val buffer = ByteArray(4096)
                            var bytesRead: Int
                            while (inputStream.read(buffer).also { bytesRead = it } != -1) {
                                outputStream.write(buffer, 0, bytesRead)
                            }
                            outputStream.flush()
                            runCatching { outputStream.fd.sync() }
                        }
                    }
                } finally {
                    connection.disconnect()
                }

                if (!partialFile.exists() || partialFile.length() <= 0L) {
                    runCatching { partialFile.delete() }
                    return@withLock Result.failure(Exception("Downloaded file is empty: $targetFileName"))
                }

                val committed = commitPartialFile(partialFile, targetFile)
                if (!committed) {
                    runCatching { partialFile.delete() }
                    return@withLock Result.failure(Exception("Failed to finalize downloaded file: $targetFileName"))
                }

                Log.d(TAG, "[R_TRACE][CACHE] Download complete: $targetFileName")
                Result.success(targetFile)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to download media: $remoteUrl", e)
            Result.failure(e)
        }
    }

    fun getCachedFile(fileName: String): File? {
        // Backward/forward compatibility: callers often use extension-less names
        // (e.g. "<id>_audio"), while ExoPlayer benefits from extension-aware files.
        val matches = cacheDir.listFiles()
            ?.filter { it.isFile && it.name.startsWith("$fileName.") && !it.name.endsWith(PARTIAL_SUFFIX) }
            ?.sortedByDescending { it.lastModified() }
            ?: emptyList()

        if (matches.isNotEmpty()) return matches.first()

        val file = File(cacheDir, fileName)
        if (file.exists()) return file

        return null
    }

    private fun resolveCacheFileName(baseFileName: String, resolvedUrl: String): String {
        // Keep existing explicit extension if caller provided one.
        if (baseFileName.contains('.')) return baseFileName

        val path = runCatching { URL(resolvedUrl).path }.getOrDefault("")
        val lastSegment = path.substringAfterLast('/', missingDelimiterValue = "")
        val extension = lastSegment.substringAfterLast('.', missingDelimiterValue = "")
            .lowercase()
            .takeIf { it.isNotBlank() && it.length <= 10 && it.all { ch -> ch.isLetterOrDigit() } }

        return if (extension != null) "$baseFileName.$extension" else baseFileName
    }

    private fun commitPartialFile(partialFile: File, targetFile: File): Boolean {
        runCatching {
            if (targetFile.exists()) targetFile.delete()
        }

        if (partialFile.renameTo(targetFile)) return true

        return runCatching {
            partialFile.inputStream().use { input ->
                FileOutputStream(targetFile).use { output ->
                    input.copyTo(output)
                    output.flush()
                    runCatching { output.fd.sync() }
                }
            }
            partialFile.delete()
            true
        }.getOrElse { false }
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
        private const val PARTIAL_SUFFIX = ".part"
        // 200 MB limit
        private const val MAX_CACHE_SIZE_BYTES = 200L * 1024L * 1024L
    }
}
