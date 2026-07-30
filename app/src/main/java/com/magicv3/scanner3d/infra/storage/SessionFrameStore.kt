package com.magicv3.scanner3d.infra.storage

import android.content.Context
import com.magicv3.scanner3d.domain.model.ScanFrame
import com.magicv3.scanner3d.domain.model.ScanSession
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

/**
 * Phase 2.3 — Tarama session'larını (projeleri) disk üzerinde yönetir.
 *
 * Roller:
 *  - createSession() : yeni UUID + klasör + meta.json üretir
 *  - appendFrame()   : bir JPEG'i aktif session'ın frames/ altına taşır + meta'yı günceller
 *  - listSessions()  : tüm proje klasörlerini Oku + ScanSession listesine çevir
 *  - deleteSession() : bir projenin tüm klasörünü recursive sil
 *  - renameSession() : meta.json'daki projectName'i günceller
 *
 * Tüm I/O Dispatchers.IO'da; UI StateFlow üzerinden collectAsStateWithLifecycle ile izler.
 */
class SessionFrameStore(private val context: Context) {

    private val rootDir: File = File(context.filesDir, "scan_projects").apply { mkdirs() }

    private val _sessions = MutableStateFlow<List<ScanSession>>(emptyList())
    val sessions: StateFlow<List<ScanSession>> = _sessions.asStateFlow()

    /** UI açıldığında çağrılır; mevcut tüm projeleri yükler. */
    suspend fun refresh() = withContext(Dispatchers.IO) {
        val loaded = rootDir.listFiles { f -> f.isDirectory }
            ?.sortedByDescending { it.lastModified() }
            ?.mapNotNull { ScanSession.fromJson(it) }
            ?: emptyList()
        _sessions.value = loaded
        android.util.Log.i(TAG, "Loaded ${loaded.size} projects from $rootDir")
    }

    /**
     * Capture trigger anında çağrılır → yeni bir proje oluşturur ve döner.
     * projectName boşsa otomatik isim verilir (örn: "Tarama 2026-07-30 20:05").
     */
    suspend fun createSession(projectName: String? = null): ScanSession =
        withContext(Dispatchers.IO) {
            val id = UUID.randomUUID()
            val folder = File(rootDir, "session_$id").apply { mkdirs() }
            File(folder, "frames").mkdirs()
            val now = System.currentTimeMillis()
            val name = projectName?.takeIf { it.isNotBlank() }
                ?: "Tarama " + java.text.SimpleDateFormat(
                    "yyyy-MM-dd HH:mm", java.util.Locale("tr")
                ).format(java.util.Date(now))
            val session = ScanSession(
                sessionId = id,
                projectName = name,
                createdAtMs = now,
                frames = emptyList(),
                totalBytes = 0L,
                folder = folder,
            )
            writeMeta(session)
            android.util.Log.i(TAG, "Created session: $name (folder=$folder)")
            refresh()
            session
        }

    /**
     * Bir JPEG dosyasını aktif session'ın frames/ klasörüne taşır ve meta'yı günceller.
     * Orchestrator her successful frame için çağırır.
     */
    suspend fun appendFrame(
        session: ScanSession,
        sourceJpeg: File,
        lensId: String,
        lensType: String,
        focalMm: Float,
    ): ScanSession = withContext(Dispatchers.IO) {
        val target = File(File(session.folder, "frames"), sourceJpeg.name)
        sourceJpeg.copyTo(target, overwrite = true)
        sourceJpeg.delete()
        val frame = ScanFrame(
            file = target,
            lensId = lensId,
            lensType = lensType,
            focalMm = focalMm,
            bytes = target.length(),
            capturedAtMs = System.currentTimeMillis(),
        )
        // meta.json'a ekle → atomic rewrite
        val updated = session.copy(
            frames = session.frames + frame,
            totalBytes = session.totalBytes + frame.bytes,
        )
        writeMeta(updated)
        android.util.Log.i(TAG,
            "Appended frame to ${session.projectName}: ${frame.file.name} (${frame.bytes} B)")
        refresh()
        updated
    }

    suspend fun deleteSession(sessionId: UUID) = withContext(Dispatchers.IO) {
        val target = File(rootDir, "session_$sessionId")
        if (target.exists()) {
            target.deleteRecursively()
            android.util.Log.i(TAG, "Deleted session: $sessionId")
        }
        refresh()
    }

    suspend fun renameSession(sessionId: UUID, newName: String) = withContext(Dispatchers.IO) {
        val folder = File(rootDir, "session_$sessionId")
        val current = ScanSession.fromJson(folder) ?: return@withContext
        val updated = current.copy(projectName = newName.takeIf { it.isNotBlank() } ?: current.projectName)
        writeMeta(updated)
        refresh()
    }

    private fun writeMeta(session: ScanSession) {
        val framesArr = JSONArray()
        session.frames.forEach { f ->
            framesArr.put(JSONObject().apply {
                put("file", f.file.name)
                put("lensId", f.lensId)
                put("lensType", f.lensType)
                put("focalMm", f.focalMm.toDouble())
                put("bytes", f.bytes)
                put("capturedAtMs", f.capturedAtMs)
            })
        }
        val root = JSONObject().apply {
            put("sessionId", session.sessionId.toString())
            put("projectName", session.projectName)
            put("createdAtMs", session.createdAtMs)
            put("totalBytes", session.totalBytes)
            put("frames", framesArr)
        }
        File(session.folder, "meta.json").writeText(root.toString(2))
    }

    companion object {
        private const val TAG = "SessionFrameStore"
    }
}
