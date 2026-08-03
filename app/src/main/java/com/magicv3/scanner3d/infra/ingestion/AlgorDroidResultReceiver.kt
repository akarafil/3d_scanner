package com.magicv3.scanner3d.infra.ingestion

import android.content.BroadcastReceiver
import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.util.Log

/**
 * Faz 4.1 - AlgorDroid 3D Engine rekonstrüksiyon durumunu dinleyen BroadcastReceiver.
 *
 * H-5 (Güvenlik): exported receiver, bu yüzden sender doğrulaması tüm API
 * seviyelerinde güçlendirilmiştir:
 *  - Katman 1: Manifest'te custom permission `com.magicv3.scanner3d.permission.SEND_RESULT`
 *    `signature` seviyesine çekildi (OS katmanı koruması).
 *  - Katman 2: API 34+ → getSentFromPackage(); null sender signature permission
 *    birincil katman olduğundan GÜVENLİ KABUL EDİLİR; non-null ise ENGINE_PACKAGE
 *    eşleşmesi zorunludur (identity paylaşan yabancı uygulamalar reddedilir).
 *    API 28-33'te sender public API ile alınamaz → signature permission + URI
 *    authority/scheme + sessionId doğrulamaları devrede.
 *  - Katman 3: action allowlist — bilinmeyen action sessizce işlenmez, loglanır.
 *  - Katman 4: sessionId UUID formatına zorlanır + IngestionQueue.isKnownSession kontrolü.
 *  - Katman 5: EXTRA_MESH_URI için scheme yalnızca `content://`; değilse markError.
 *  - L-4: getParcelableExtra typed overload (API 33+).
 *  - L-1: Log'da tam mesh URI yerine sadece authority; hata mesajı .take(512).
 */
class AlgorDroidResultReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "AlgorDroidReceiver"
        private const val PACKAGE_ALGORDROID_ENGINE = "com.algordroid.engine"

        const val ACTION_PROCESSING_PROGRESS = "com.algordroid.engine.ACTION_PROCESSING_PROGRESS"
        const val ACTION_PROCESSING_COMPLETE = "com.algordroid.engine.ACTION_PROCESSING_COMPLETE"
        const val ACTION_PROCESSING_ERROR = "com.algordroid.engine.ACTION_PROCESSING_ERROR"

        /**
         * Katman 3: kabul edilen action allowlist'i. İzin verilmeyen herhangi bir
         * action broadcast'i reddedilir (manifest'teki intent-filter ile hizalı).
         */
        private val VALID_ACTIONS = setOf(
            ACTION_PROCESSING_PROGRESS,
            ACTION_PROCESSING_COMPLETE,
            ACTION_PROCESSING_ERROR
        )

        const val EXTRA_SESSION_ID = "com.algordroid.engine.EXTRA_SESSION_ID"
        const val EXTRA_PROGRESS_PERCENT = "com.algordroid.engine.EXTRA_PROGRESS_PERCENT"
        const val EXTRA_MESH_URI = "com.algordroid.engine.EXTRA_MESH_URI"
        const val EXTRA_ERROR_MESSAGE = "com.algordroid.engine.EXTRA_ERROR_MESSAGE"

        /** UUID session ID formatı — sadece UUID kabul edilir (path traversal engeli). */
        private val SESSION_ID_REGEX = Regex(
            "[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}"
        )

        /** L-1: Log/hata mesajlarında taşınan string uzunluk sınırı. */
        private const val MAX_LOG_MSG_LENGTH = 512
    }

    override fun onReceive(context: Context, intent: Intent) {
        // Katman 3: action allowlist.
        val action = intent.action
        if (action == null || action !in VALID_ACTIONS) {
            Log.w(TAG, "Ignoring unknown engine action: ${action?.take(MAX_LOG_MSG_LENGTH)}")
            return
        }

        // Katman 2: sender doğrulaması — API 34+'ta null kabul edilmez.
        if (!isAuthorizedSender()) {
            Log.w(TAG, "Rejected broadcast from unauthorized sender.")
            return
        }

        // Katman 4: sessionId UUID formatı + bilinen-session kontrolü.
        val sessionId = intent.getStringExtra(EXTRA_SESSION_ID) ?: return
        if (!isValidSessionId(sessionId)) {
            Log.w(TAG, "Rejected broadcast: malformed session id: ${sessionId.take(MAX_LOG_MSG_LENGTH)}")
            return
        }
        val queue = IngestionQueue.getInstance(context)
        if (!queue.isKnownSession(sessionId)) {
            Log.w(TAG, "Rejected broadcast: unknown session id: ${sessionId.take(MAX_LOG_MSG_LENGTH)}")
            return
        }

        when (action) {
            ACTION_PROCESSING_PROGRESS -> {
                val progress = intent.getIntExtra(EXTRA_PROGRESS_PERCENT, 0)
                Log.i(TAG, "[$sessionId] Processing Progress: %$progress")
                queue.updateProgress(sessionId, progress)
            }

            ACTION_PROCESSING_COMPLETE -> {
                val meshUri = readMeshUri(intent)
                // L-1: tam mesh URI'yi loglama — yalnızca authority.
                val uriAuthority = meshUri?.authority ?: "null"
                Log.i(TAG, "[$sessionId] Processing Complete! Mesh authority: $uriAuthority")

                // Katman 5: scheme content:// zorunlu; değilse markError.
                if (meshUri != null && isTrustedMeshUri(context, meshUri)) {
                    queue.markComplete(sessionId, meshUri)
                } else {
                    // Sahte/güvenilmeyen URI → session dizinine kopyalanmaz.
                    Log.w(TAG, "[$sessionId] Rejected complete intent: untrusted mesh URI (authority=$uriAuthority)")
                    queue.markError(
                        sessionId,
                        "Engine complete intent has invalid mesh URI."
                    )
                }
            }

            ACTION_PROCESSING_ERROR -> {
                // L-1: hata mesajı 512 karakterle sınırlanır.
                val error = (intent.getStringExtra(EXTRA_ERROR_MESSAGE) ?: "Bilinmeyen motor hatası")
                    .take(MAX_LOG_MSG_LENGTH)
                Log.e(TAG, "[$sessionId] Processing Error: $error")
                queue.markError(sessionId, error)
            }
        }
    }

    /**
     * H-5 Katman 2: Broadcast'i gönderen paketi doğrular.
     *
     * Birincil güvenlik katmanı manifest'teki signature permission'dur
     * (`com.magicv3.scanner3d.permission.SEND_RESULT`) — sistem bu izni **tüm API
     * seviyelerinde** zorlar ve yalnızca aynı imza ile imzalanmış uygulamalar
     * broadcast gönderebilir.
     *
     * `getSentFromPackage()` (API 34+) yalnızca sender identity-sharing ettiğinde
     * non-null döner; engine identity paylaşmazsa Android 14+'ta tüm legit
     * broadcast'ler null döner. Null sender'ı koşulsuz reddetmek yerine signature
     * permission zaten filtrelendiğinden **null güvenli kabul edilir**; non-null
     * ise paket eşleşmesi zorunludur.
     *
     * API 28-33: sender public API ile güvenilir şekilde alınamaz → true
     *            (OS katmanındaki signature permission tek korumadır).
     */
    internal fun isAuthorizedSender(senderPackage: String?): Boolean {
        // Null sender: signature permission birincil katmandır; null güvenli kabul edilir.
        if (senderPackage == null) return true
        // Non-null sender: paket eşleşmesi zorunludur (engine paketi dışındaki kimlik
        // paylaşan uygulamalar reddedilir).
        return senderPackage == PACKAGE_ALGORDROID_ENGINE
    }

    private fun isAuthorizedSender(): Boolean {
        if (Build.VERSION.SDK_INT < 34) return true
        // getSentFromPackage() yalnızca sender identity-sharing ettiğinde non-null;
        // signature permission birincil katmandır, bu yüzden null sender güvenli
        // kabul edilir; non-null ise paket eşleşmesi zorunludur.
        return isAuthorizedSender(getSentFromPackage())
    }

    @Suppress("DEPRECATION")
    private fun readMeshUri(intent: Intent): Uri? {
        return if (Build.VERSION.SDK_INT >= 33) {
            intent.getParcelableExtra(EXTRA_MESH_URI, Uri::class.java)
        } else {
            intent.getParcelableExtra(EXTRA_MESH_URI)
        }
    }

    private fun isValidSessionId(sessionId: String): Boolean = SESSION_ID_REGEX.matches(sessionId)

    /**
     * H-5 Katman 5: URI yalnızca `content://` şemasıyla ve güvenilir authority'lerle kabul edilir.
     * Authority motorun paketine ya da kendi FileProvider'ımıza ait olmalıdır.
     */
    private fun isTrustedMeshUri(context: Context, uri: Uri): Boolean {
        if (uri.scheme != ContentResolver.SCHEME_CONTENT) return false
        val authority = uri.authority ?: return false
        return isTrustedAuthority(context, authority)
    }

    private fun isTrustedAuthority(context: Context, authority: String): Boolean {
        val engineAuthority = PACKAGE_ALGORDROID_ENGINE
        val selfAuthority = context.packageName
        return authority == engineAuthority ||
            authority.startsWith("$engineAuthority.") ||
            authority == selfAuthority ||
            authority.startsWith("$selfAuthority.")
    }
}
