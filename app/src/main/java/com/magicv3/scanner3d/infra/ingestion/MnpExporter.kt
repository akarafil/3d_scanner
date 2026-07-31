package com.magicv3.scanner3d.infra.ingestion

import android.content.Context
import android.net.Uri
import androidx.core.content.FileProvider
import com.magicv3.scanner3d.domain.model.ScanSession
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.util.zip.Deflater
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Faz 3.1 - ScanSession'ı M3SP Magic Header'lı .mnp paketine aktarır.
 */
class MnpExporter(private val context: Context) {

    companion object {
        private const val TAG = "MnpExporter"
        
        // "M3SP" -> 0x4D 0x33 0x53 0x50
        val MAGIC_HEADER = byteArrayOf(0x4D.toByte(), 0x33.toByte(), 0x53.toByte(), 0x50.toByte())

        /**
         * Verilen dosyanın ilk 4 baytını kontrol ederek M3SP header doğrulaması yapar.
         */
        fun isMnpFile(file: File): Boolean {
            if (!file.exists() || file.length() < 4) return false
            return runCatching {
                file.inputStream().use { stream ->
                    val header = ByteArray(4)
                    val read = stream.read(header)
                    read == 4 && header.contentEquals(MAGIC_HEADER)
                }
            }.getOrDefault(false)
        }
    }

    data class MnpResult(
        val file: File,
        val uri: Uri,
        val sizeBytes: Long
    )

    /**
     * ScanSession içeriğini (meta.json + manifest.json + frames/) .mnp formatında paketler.
     */
    suspend fun exportMnp(
        session: ScanSession,
        progress: ((current: Int, total: Int) -> Unit)? = null
    ): MnpResult = withContext(Dispatchers.IO) {
        val safeName = session.projectName
            .replace(Regex("[^A-Za-z0-9_-]"), "_")
            .take(48)
        val stamp = java.text.SimpleDateFormat(
            "yyyyMMdd_HHmmss", java.util.Locale.US
        ).format(java.util.Date(session.createdAtMs))

        val exportDir = File(context.cacheDir, "shared_mnp").apply { mkdirs() }
        val mnpFile = File(exportDir, "${safeName}_$stamp.mnp")

        // 24 saatten eski paketleri temizle
        exportDir.listFiles()?.forEach { old ->
            if (old.isFile && old.lastModified() < System.currentTimeMillis() - 24 * 3600 * 1000L) {
                old.delete()
            }
        }

        FileOutputStream(mnpFile).buffered().use { fos ->
            // 1. M3SP Magic Header
            fos.write(MAGIC_HEADER)

            // 2. ZIP Akışı
            ZipOutputStream(fos).use { zos ->
                zos.setLevel(Deflater.BEST_SPEED)

                // meta.json
                val metaFile = File(session.folder, "meta.json")
                if (metaFile.exists()) {
                    zos.putNextEntry(ZipEntry("meta.json"))
                    metaFile.inputStream().use { it.copyTo(zos) }
                    zos.closeEntry()
                }

                // manifest.json
                val manifestFile = File(session.folder, "manifest.json")
                if (manifestFile.exists()) {
                    zos.putNextEntry(ZipEntry("manifest.json"))
                    manifestFile.inputStream().use { it.copyTo(zos) }
                    zos.closeEntry()
                }

                // frames/*
                val total = session.frames.size
                session.frames.forEachIndexed { i, frame ->
                    if (frame.file.exists()) {
                        zos.putNextEntry(ZipEntry("frames/${frame.file.name}"))
                        frame.file.inputStream().use { it.copyTo(zos) }
                        zos.closeEntry()
                        progress?.invoke(i + 1, total)
                    }
                }
            }
        }

        val uri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            mnpFile
        )

        android.util.Log.i(TAG, "MNP Created: ${mnpFile.name} (${mnpFile.length()} B)")
        MnpResult(mnpFile, uri, mnpFile.length())
    }
}
