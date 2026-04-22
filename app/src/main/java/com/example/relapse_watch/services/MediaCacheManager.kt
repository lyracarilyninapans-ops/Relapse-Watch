package com.example.relapse_watch.services

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.ResponseBody
import java.io.File
import java.io.FileOutputStream
import java.net.URL
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton
import com.google.firebase.storage.FirebaseStorage
import kotlinx.coroutines.tasks.await

@Singleton
class MediaCacheManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val firebaseStorage: FirebaseStorage
) {

    private val httpClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(NETWORK_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .readTimeout(NETWORK_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .callTimeout(NETWORK_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .build()

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

            val validatedUri = MediaUrlValidator
                .validate(resolvedUrl)
                .getOrElse { return@withContext Result.failure(it) }
            val validatedUrl = validatedUri.toString()

            val targetFileName = resolveCacheFileName(fileName, validatedUrl)
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
                        AppLogger.trace(TAG, "[R_TRACE][CACHE] File already cached: ${existing.name}")
                        return@withLock Result.success(existing)
                    }

                    AppLogger.trace(
                        TAG,
                        "[R_TRACE][CACHE] Legacy cache detected for $fileName; refreshing typed cache as $targetFileName"
                    )
                }

                if (targetFile.exists() && targetFile.length() > 0) {
                    AppLogger.trace(TAG, "[R_TRACE][CACHE] File already cached: $targetFileName")
                    return@withLock Result.success(targetFile)
                }

                val partialFile = File(cacheDir, "$targetFileName$PARTIAL_SUFFIX")
                if (partialFile.exists()) {
                    runCatching { partialFile.delete() }
                }

                enforceCacheLimit()

                AppLogger.trace(TAG, "[R_TRACE][CACHE] Starting download for: $targetFileName")
                val request = Request.Builder().url(validatedUrl).get().build()
                httpClient.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) {
                        return@withLock Result.failure(Exception("Server returned HTTP ${response.code}"))
                    }

                    val responseBody = response.body
                        ?: return@withLock Result.failure(Exception("Response body is empty"))

                    val declaredLength = responseBody.contentLength()
                    if (declaredLength > MAX_DOWNLOAD_SIZE_BYTES) {
                        return@withLock Result.failure(Exception("Media file too large: $declaredLength bytes"))
                    }

                    writeResponseBody(partialFile, responseBody)
                        .getOrElse { return@withLock Result.failure(it) }
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

                AppLogger.trace(TAG, "[R_TRACE][CACHE] Download complete: $targetFileName")
                Result.success(targetFile)
            }
        } catch (e: Exception) {
            AppLogger.error(TAG, "Failed to download media: $remoteUrl", e)
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

    private fun writeResponseBody(partialFile: File, responseBody: ResponseBody): Result<Unit> {
        return runCatching {
            responseBody.byteStream().use { inputStream ->
                FileOutputStream(partialFile).use { outputStream ->
                    val buffer = ByteArray(BUFFER_SIZE_BYTES)
                    var totalRead = 0L
                    while (true) {
                        val bytesRead = inputStream.read(buffer)
                        if (bytesRead == -1) break

                        totalRead += bytesRead
                        require(totalRead <= MAX_DOWNLOAD_SIZE_BYTES) {
                            "Media file exceeded max allowed size of $MAX_DOWNLOAD_SIZE_BYTES bytes"
                        }

                        outputStream.write(buffer, 0, bytesRead)
                    }
                    outputStream.flush()
                    runCatching { outputStream.fd.sync() }
                }
            }
        }
    }

    fun getCacheSize(): Long {
        return cacheDir.listFiles()?.sumOf { it.length() } ?: 0L
    }
    
    fun clearCache() {
        cacheDir.listFiles()?.forEach { it.delete() }
        AppLogger.trace(TAG, "Cache cleared")
    }

    /**
     * Enforces the MAX_CACHE_SIZE limit by deleting the oldest files until we are below the limit.
     */
    private fun enforceCacheLimit() {
        var currentSize = getCacheSize()
        if (currentSize <= MAX_CACHE_SIZE_BYTES) return

        AppLogger.trace(TAG, "Cache limit exceeded. Current size: $currentSize bytes. Evicting old files...")

        val files = cacheDir.listFiles()?.toMutableList() ?: return
        // Sort by last modified ascending (oldest first)
        files.sortBy { it.lastModified() }

        for (file in files) {
            val length = file.length()
            if (file.delete()) {
                currentSize -= length
                AppLogger.trace(TAG, "Evicted file: ${file.name}")
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
        private const val NETWORK_TIMEOUT_SECONDS = 15L
        private const val BUFFER_SIZE_BYTES = 8 * 1024
        private const val MAX_DOWNLOAD_SIZE_BYTES = 50L * 1024L * 1024L
        // 200 MB limit
        private const val MAX_CACHE_SIZE_BYTES = 200L * 1024L * 1024L
    }
}
