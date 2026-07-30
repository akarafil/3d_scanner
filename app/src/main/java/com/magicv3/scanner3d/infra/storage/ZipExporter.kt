package com.magicv3.scanner3d.infra.storage

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import com.magicv3.scanner3d.domain.model.ScanFrame
import com.magicv3.scanner3d.domain.model.ScanSession
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Phase 2.6 — Bir ScanSession'ın tüm içeriğini (meta.json + frames/) tek bir
 * ZIP'e paketler ve Android Share Sheet'iyle kullanıcıya sunar.
 *
 * Zip yapısı:
 *   {projectName}__{YYYYMMDD_HHMMSS}.zip
 *   ├─ meta.json
 *   └─ frames/
 *       ├─ frame_001_TELE_*.jpg
 *       ├─ frame_002_TELE_*.jpg
 *       └─ ...
 *
 * Üretim yeri: context.cacheDir/shared_zips/ (FileProvider cache-path).
 *
 * Storage permission gerekmez — içerikten okuyoruz (app-private) ve FileProvider
 * geçici read-uri'si share sheet'e grant ediliyor.
 */
class ZipExporter(private val context: Context) {

    data class ZipResult(val zipFile: File, val uri: Uri, val sizeBytes: Long)

    /**
     * Session'ı ZIP'e paketler — Dispatchers.IO'da.
     * Döndürülen ZipResult, share sheet'i çağıracak UI'a verilir.
     */
    suspend fun export(
        session: ScanSession,
        progress: ((current: Int, total: Int) -> Unit)? = null
    ): ZipResult = withContext(Dispatchers.IO) {
        val safeName = session.projectName
            .replace(Regex("[^A-Za-z0-9_-]"), "_")
            .take(48)  // DOSYA ADI uzunluk limiti
        val stamp = java.text.SimpleDateFormat(
            "yyyyMMdd_HHmmss", java.util.Locale.US
        ).format(java.util.Date(session.createdAtMs))
        val zipBaseName = "${safeName}__$stamp"

        val sharedDir = File(context.cacheDir, "shared_zips").apply { mkdirs() }
        val zipFile = File(sharedDir, "$zipBaseName.zip")

        // Eski ZIP'ları temizle (>50MB cache birikmesini engelle)
        sharedDir.listFiles()?.forEach { old ->
            if (old.isFile && old.lastModified() < System.currentTimeMillis() - 24 * 3600_000L) {
                old.delete()
            }
        }

        // ZIP üretimi — deflater BEST_SPEED ( Honor RAM Turbo var, CPU hızlı)
        ZipOutputStream(zipFile.outputStream().buffered()).use { zos ->
            zos.setLevel(java.util.zip.Deflater.BEST_SPEED)
            // 1) meta.json
            val meta = File(session.folder, "meta.json")
            if (meta.exists()) {
                zos.putNextEntry(ZipEntry("meta.json"))
                meta.inputStream().use { it.copyTo(zos) }
                zos.closeEntry()
            }
            // 2) frames/*
            val total = session.frames.size
            session.frames.forEachIndexed { i, frame ->
                if (frame.file.exists()) {
                    zos.putNextEntry(ZipEntry("frames/${frame.file.name}"))
                    frame.file.inputStream().use { it.copyTo(zos) }
                    zos.closeEntry()
                }
                progress?.invoke(i + 1, total)
            }
        }

        val uri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            zipFile
        )
        android.util.Log.i(TAG,
            "ZIP exported: ${zipFile.name} (${zipFile.length()} B, ${session.frameCount} frame)")

        ZipResult(zipFile, uri, zipFile.length())
    }

    /**
     * Share Sheet'i aç. UI'da startActivity çağrılmalı.
     */
    fun launchShareSheet(result: ZipResult, projectName: String) {
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "application/zip"
            putExtra(Intent.EXTRA_STREAM, result.uri)
            putExtra(Intent.EXTRA_TITLE, "$projectName.zip")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(
            Intent.createChooser(intent, "Projeyi ZIP olarak paylaş")
        )
    }

    /**
     * Detail screen'de tek kareyi ACTION_VIEW ile galeride açarken kullanılır.
     */
    companion object {
        private const val TAG = "ZipExporter"

        fun frameUri(context: Context, frameFile: File): Uri =
            FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                frameFile
            )
    }
}
