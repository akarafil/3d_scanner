package com.magicv3.scanner3d.infra.ingestion

import android.content.Context
import android.content.Intent
import android.net.Uri
import java.io.File

/**
 * Faz 3.3 - MNP Paketlerini AlgorDroid 3D Engine'e ileten IPC Transfer Adaptörü.
 */
class AlgorDroidTransferAdapter(private val context: Context) {

    companion object {
        private const val TAG = "AlgorDroidAdapter"
        const val ACTION_INGEST_PACKAGE = "com.algordroid.engine.ACTION_INGEST_PACKAGE"
        const val EXTRA_PACKAGE_URI = "com.algordroid.engine.EXTRA_PACKAGE_URI"
        const val EXTRA_SESSION_ID = "com.algordroid.engine.EXTRA_SESSION_ID"
        const val MNP_MIME_TYPE = "application/vnd.magic3dscanner.package"
    }

    data class TransferResult(
        val success: Boolean,
        val message: String
    )

    /**
     * MNP paketini doğrular ve AlgorDroid Engine'e Intent Broadcast ile fırlatır.
     */
    fun dispatchPackage(sessionId: String, mnpFile: File, uri: Uri): TransferResult {
        // 1. M3SP Header Güvenlik Kontrolü
        if (!MnpExporter.isMnpFile(mnpFile)) {
            android.util.Log.e(TAG, "Transfer Rejected: File $mnpFile is not a valid M3SP MNP package!")
            return TransferResult(false, "Geçersiz MNP paket başlığı (M3SP Magic Header eksik).")
        }

        return runCatching {
            val intent = Intent(ACTION_INGEST_PACKAGE).apply {
                type = MNP_MIME_TYPE
                putExtra(EXTRA_PACKAGE_URI, uri)
                putExtra(EXTRA_SESSION_ID, sessionId)
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                // Cihazda AlgorDroid kurulu değilse genel Share Sheet fallback'i
                `package` = "com.algordroid.engine"
            }

            context.sendBroadcast(intent)
            android.util.Log.i(TAG, "Dispatched MNP package to AlgorDroid: ${mnpFile.name}")
            TransferResult(true, "Paket AlgorDroid motoruna fırlatıldı.")
        }.getOrElse { e ->
            // AlgorDroid kapalı veya kurulu değilse Intent Chooser fallback
            launchChooserFallback(mnpFile, uri)
            TransferResult(true, "Share Sheet Fallback açıldı.")
        }
    }

    private fun launchChooserFallback(mnpFile: File, uri: Uri) {
        val shareIntent = Intent(Intent.ACTION_SEND).apply {
            type = MNP_MIME_TYPE
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_TITLE, "${mnpFile.name} (AlgorDroid Package)")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        val chooser = Intent.createChooser(shareIntent, "AlgorDroid'e Aktar").apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(chooser)
    }
}
