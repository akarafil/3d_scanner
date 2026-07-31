package com.magicv3.scanner3d.infra.storage

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Faz 4.2 — Depolama alanını korumak için 24 saatten eski
 * geçici .zip ve .mnp dosyalarını temizler.
 */
class CacheCleaner(private val context: Context) {

    companion object {
        private const val TAG = "CacheCleaner"
        private const val EXPIRATION_TIME_MS = 24 * 60 * 60 * 1000L // 24 Saat
    }

    /**
     * shared_zips ve shared_mnp klasörlerindeki eski dosyaları asenkron temizler.
     */
    suspend fun cleanExpiredCache() = withContext(Dispatchers.IO) {
        val cacheDirs = listOf(
            File(context.cacheDir, "shared_zips"),
            File(context.cacheDir, "shared_mnp")
        )

        val now = System.currentTimeMillis()
        var deletedCount = 0
        var freedBytes = 0L

        cacheDirs.forEach { dir ->
            if (dir.exists() && dir.isDirectory) {
                dir.listFiles()?.forEach { file ->
                    if (file.isFile && now - file.lastModified() > EXPIRATION_TIME_MS) {
                        val size = file.length()
                        if (file.delete()) {
                            deletedCount++
                            freedBytes += size
                        }
                    }
                }
            }
        }

        if (deletedCount > 0) {
            android.util.Log.i(TAG, "Cleaned $deletedCount expired cache files. Freed ${freedBytes / 1024} KB.")
        }
    }
}
