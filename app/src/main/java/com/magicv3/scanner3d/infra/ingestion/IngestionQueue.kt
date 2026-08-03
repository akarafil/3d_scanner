package com.magicv3.scanner3d.infra.ingestion

import android.content.Context
import android.net.Uri
import com.magicv3.scanner3d.domain.model.ScanSession
import com.magicv3.scanner3d.infra.storage.CacheCleaner
import com.magicv3.scanner3d.infra.storage.MeshRepository
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
import java.util.concurrent.ConcurrentHashMap

/**
 * Faz 3.2, 3.3 & 4.1 - Ingestion Durum Makinesi
 */
sealed interface IngestionState {
    data object Idle : IngestionState
    data class Queued(val sessionId: String) : IngestionState
    data class Validating(val sessionId: String, val currentFrame: Int, val totalFrames: Int) : IngestionState
    data class Packaging(val sessionId: String, val progress: Int, val total: Int) : IngestionState
    data class Transferring(val sessionId: String, val mnpFile: File) : IngestionState
    data class Delivered(val sessionId: String, val mnpFile: File) : IngestionState
    data class Reconstructing(val sessionId: String, val progress: Int) : IngestionState
    data class Reconstructed(val sessionId: String, val meshFile: File) : IngestionState
    data class Failed(val sessionId: String, val reason: String) : IngestionState
}

data class IngestionItem(
    val session: ScanSession,
    val enqueuedAtMs: Long = System.currentTimeMillis()
)

/**
 * AlgorDroid Ingestion kuyruk yöneticisi (Thread-Safe Singleton).
 *
 * Testability (kalite ekibi refactorü): Bağımlılıklar constructor'dan enjekte
 * edilebilir hale getirildi — üretimde [getInstance] singleton'ı korunur, testler
 * ise fake bağımlılıklarla yeni bir [IngestionQueue] örneği üretebilir.
 */
class IngestionQueue private constructor(
    private val context: Context,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
    private val exifValidator: ExifValidator = ExifValidator(),
    private val manifestGenerator: ManifestGenerator = ManifestGenerator(context),
    private val mnpExporter: MnpExporter = MnpExporter(context),
    private val transferAdapter: AlgorDroidTransferAdapter = AlgorDroidTransferAdapter(context),
    private val meshRepository: MeshRepository = MeshRepository(context),
    private val cacheCleaner: CacheCleaner = CacheCleaner(context),
) {

    /**
     * H-5c: Kuyruğa eklenmiş bilinen-session seti.
     *
     * AlgorDroidResultReceiver, yalnızca bu setteki sessionId'leri kabul eder —
     * bilinmeyen/uygunsuz broadcast'ler en başta reddedilir.
     */
    private val knownSessions = ConcurrentHashMap.newKeySet<String>()

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
        // B14: aynı sessionId zaten kuyrukta/biliniyorsa yeniden enqueue etme (idempotence).
        // knownSessions.add() false döndürürse session zaten mevcut → çift işleme engeli.
        if (!knownSessions.add(sId)) {
            android.util.Log.w(TAG, "Duplicate enqueue ignored: $sId")
            return
        }
        _queueState.update { IngestionState.Queued(sId) }
        channel.trySend(IngestionItem(session))
        android.util.Log.i(TAG, "Enqueued session: $sId")
    }

    /**
     * H-5c: [sessionId] kuyruğa eklenmiş bilinen bir session mı?
     * AlgorDroidResultReceiver katman 4 doğrulaması bu metodu kullanır.
     */
    fun isKnownSession(sessionId: String): Boolean = sessionId in knownSessions

    /** AlgorDroid 3D Engine kurulu mu? (transferAdapter üzerinden dürüst ön-kontrol). */
    fun isRenderEngineInstalled(): Boolean = transferAdapter.isEngineInstalled()

    fun resetToIdle() {
        knownSessions.clear()
        _queueState.value = IngestionState.Idle
    }

    /**
     * Faz 4.1 — AlgorDroidResultReceiver tarafından tetiklenen ilerleme güncellemesi.
     */
    fun updateProgress(sessionId: String, progress: Int) {
        _queueState.value = IngestionState.Reconstructing(sessionId, progress)
    }

    /**
     * Faz 4.1 & 4.2 — Rekonstrüksiyon başarıyla tamamlandığında mesh dosyasını içeri aktarır.
     */
    fun markComplete(sessionId: String, meshUri: Uri) {
        scope.launch {
            _queueState.value = IngestionState.Reconstructing(sessionId, 100)
            // B13: mesh.glb zaten içeri aktarılmışsa yeniden import edilmez (idempotence) —
            // aynı complete broadcast'i iki kez gelirse dosya tekrar kopyalanmaz.
            val meshFile = if (meshRepository.hasMeshForSession(sessionId)) {
                android.util.Log.i(TAG, "markComplete: mesh zaten mevcut, yeniden import yok: $sessionId")
                meshRepository.getMeshFileForSession(sessionId)
            } else {
                meshRepository.importMesh(sessionId, meshUri)
            }
            if (meshFile != null) {
                _queueState.value = IngestionState.Reconstructed(sessionId, meshFile)
                // Cache temizliği tetikle
                cacheCleaner.cleanExpiredCache()
            } else {
                _queueState.value = IngestionState.Failed(sessionId, "3D model kopyalanamadı.")
            }
            // H-5c: session tamamlandı → bilinen-session setinden temizle.
            knownSessions.remove(sessionId)
        }
    }

    /**
     * Faz 4.1 — Motor hatası durumunda durumu Failed olarak ayarlar.
     */
    fun markError(sessionId: String, error: String) {
        _queueState.value = IngestionState.Failed(sessionId, error)
        // H-5c: hatalı session da bilinen-session setinden temizlenir.
        knownSessions.remove(sessionId)
    }

    /**
     * Kuyruk işleme adımı (testlerin doğrudan çağırabilmesi için internal).
     */
    internal suspend fun processIngestion(item: IngestionItem) {
        val sId = item.session.sessionId.toString()
        runCatching {
            // 1. VALIDATING
            _queueState.value = IngestionState.Validating(sId, 0, item.session.frameCount)
            val validation = exifValidator.validateFrames(item.session.frames)
            if (!validation.isValid) {
                val errorMsg = validation.issues.firstOrNull()?.message ?: "EXIF validation failed"
                _queueState.value = IngestionState.Failed(sId, "Doğrulama Hatası: $errorMsg")
                knownSessions.remove(sId)
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
            val transfer = transferAdapter.dispatchPackage(sId, mnpResult.file, mnpResult.uri)

            if (transfer.success) {
                // 4. DELIVERED
                _queueState.value = IngestionState.Delivered(sId, mnpResult.file)
                android.util.Log.i(TAG, "Successfully delivered MNP for $sId")
            } else {
                _queueState.value = IngestionState.Failed(sId, transfer.message)
                knownSessions.remove(sId)
            }
        }.onFailure { e ->
            android.util.Log.e(TAG, "Ingestion failed for $sId", e)
            _queueState.value = IngestionState.Failed(sId, e.message ?: "Bilinmeyen kuyruk hatası")
            knownSessions.remove(sId)
        }
    }

    companion object {
        private const val TAG = "IngestionQueue"

        @Volatile
        private var INSTANCE: IngestionQueue? = null

        fun getInstance(context: Context): IngestionQueue {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: IngestionQueue(context.applicationContext).also { INSTANCE = it }
            }
        }
    }
}
