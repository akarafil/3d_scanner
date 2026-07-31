package com.magicv3.scanner3d.infra.ingestion

import android.content.Context
import com.magicv3.scanner3d.domain.model.ScanSession
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File

/**
 * Faz 3.2 - Ingestion Durum Makinesi
 */
sealed interface IngestionState {
    data object Idle : IngestionState
    data class Queued(val sessionId: String) : IngestionState
    data class Validating(val sessionId: String, val currentFrame: Int, val totalFrames: Int) : IngestionState
    data class Packaging(val sessionId: String, val progress: Int, val total: Int) : IngestionState
    data class Transferring(val sessionId: String, val mnpFile: File) : IngestionState
    data class Delivered(val sessionId: String, val mnpFile: File) : IngestionState
    data class Failed(val sessionId: String, val reason: String) : IngestionState
}

data class IngestionItem(
    val session: ScanSession,
    val enqueuedAtMs: Long = System.currentTimeMillis()
)

/**
 * AlgorDroid ingestion kuyruk yöneticisi.
 */
class IngestionQueue(
    private val context: Context,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
) {

    private val exifValidator = ExifValidator()
    private val manifestGenerator = ManifestGenerator(context)
    private val mnpExporter = MnpExporter(context)

    private val _queueState = MutableStateFlow<IngestionState>(IngestionState.Idle)
    val queueState: StateFlow<IngestionState> = _queueState.asStateFlow()

    private val channel = Channel<IngestionItem>(Channel.UNLIMITED)

    init {
        scope.launch {
            for (item in channel) {
                processIngestion(item)
            }
        }
    }

    fun enqueue(session: ScanSession) {
        val sId = session.sessionId.toString()
        _queueState.update { IngestionState.Queued(sId) }
        channel.trySend(IngestionItem(session))
        android.util.Log.i("IngestionQueue", "Enqueued session: $sId")
    }

    private suspend fun processIngestion(item: IngestionItem) {
        val sId = item.session.sessionId.toString()
        runCatching {
            // 1. VALIDATING
            _queueState.value = IngestionState.Validating(sId, 0, item.session.frameCount)
            val validation = exifValidator.validateFrames(item.session.frames)
            if (!validation.isValid) {
                val errorMsg = validation.issues.firstOrNull()?.message ?: "EXIF validation failed"
                _queueState.value = IngestionState.Failed(sId, "Doğrulama Hatası: $errorMsg")
                return
            }

            // 2. PACKAGING (manifest.json + .mnp)
            _queueState.value = IngestionState.Packaging(sId, 0, item.session.frameCount)
            manifestGenerator.generateManifest(item.session)

            val mnpResult = mnpExporter.exportMnp(item.session) { current, total ->
                _queueState.value = IngestionState.Packaging(sId, current, total)
            }

            // 3. TRANSFERRING
            _queueState.value = IngestionState.Transferring(sId, mnpResult.file)

            // 4. DELIVERED
            _queueState.value = IngestionState.Delivered(sId, mnpResult.file)
            android.util.Log.i("IngestionQueue", "Successfully delivered MNP for $sId")
        }.onFailure { e ->
            android.util.Log.e("IngestionQueue", "Ingestion failed for $sId", e)
            _queueState.value = IngestionState.Failed(sId, e.message ?: "Bilinmeyen kuyruk hatası")
        }
    }
}
