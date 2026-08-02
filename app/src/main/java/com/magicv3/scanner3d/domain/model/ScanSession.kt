package com.magicv3.scanner3d.domain.model

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

/**
 * Faz 1 — Tarama oturumlarının durum makinesi.
 */
enum class ScanStatus {
    DRAFT,
    CAPTURING,
    RENDERING,
    COMPLETED
}

/**
 * Phase 2.3 & Faz 1 — Tek bir tarama oturumunu (project) temsil eder.
 *
 * Her capture trigger → yeni bir ScanSession (UUID + klasör) oluşturur.
 * MyScansScreen bu modelin listesini gösterir.
 */
data class ScanSession(
    val sessionId: UUID,
    val projectName: String,
    val createdAtMs: Long,                // epoch millis
    val frames: List<ScanFrame>,
    val totalBytes: Long,
    val folder: File,                     // scan_projects/<sessionId>/
    val status: ScanStatus = ScanStatus.DRAFT, // [Faz 1]
) {
    val frameCount: Int get() = frames.size
    val lensesUsed: Set<String> get() = frames.map { it.lensType }.toSet()

    /** İnsan-okur dosya boyutu (MB). MyScansScreen'de gösterilir. */
    val sizeFormatted: String
        get() = String.format(java.util.Locale.US, "%.1f MB", totalBytes / 1_000_000.0)

    /** Tarama listesi için kısa tarih (örn: "30 Tem 20:05"). */
    val dateFormatted: String
        get() = java.text.SimpleDateFormat(
            "dd MMM HH:mm", java.util.Locale("tr")
        ).format(java.util.Date(createdAtMs))

    companion object {
        fun fromJson(folder: File): ScanSession? {
            val meta = File(folder, "meta.json")
            if (!meta.exists()) return null
            return runCatching {
                val root = JSONObject(meta.readText())
                val framesArr = root.optJSONArray("frames") ?: JSONArray()
                val frames = (0 until framesArr.length()).map { i ->
                    val f = framesArr.getJSONObject(i)
                    val transArr = f.optJSONArray("translation")
                    val rotArr = f.optJSONArray("rotation")
                    val translation = if (transArr != null) FloatArray(transArr.length()) { idx -> transArr.getDouble(idx).toFloat() } else null
                    val rotation = if (rotArr != null) FloatArray(rotArr.length()) { idx -> rotArr.getDouble(idx).toFloat() } else null
                    ScanFrame(
                        file = File(folder, "frames/${f.getString("file")}"),
                        lensId = f.getString("lensId"),
                        lensType = f.getString("lensType"),
                        focalMm = f.getDouble("focalMm").toFloat(),
                        bytes = f.getLong("bytes"),
                        capturedAtMs = f.getLong("capturedAtMs"),
                        translation = translation,
                        rotation = rotation,
                    )
                }

                // [Faz 1] Parse status
                val statusStr = root.optString("status", ScanStatus.DRAFT.name)
                val status = runCatching { ScanStatus.valueOf(statusStr) }.getOrDefault(ScanStatus.DRAFT)

                ScanSession(
                    sessionId = UUID.fromString(root.getString("sessionId")),
                    projectName = root.optString("projectName", "Tarama"),
                    createdAtMs = root.getLong("createdAtMs"),
                    frames = frames,
                    totalBytes = root.optLong("totalBytes", frames.sumOf { it.bytes }),
                    folder = folder,
                    status = status,
                )
            }.getOrNull()
        }
    }
}

data class ScanFrame(
    val file: File,
    val lensId: String,
    val lensType: String,
    val focalMm: Float,
    val bytes: Long,
    val capturedAtMs: Long,
    val translation: FloatArray? = null,
    val rotation: FloatArray? = null,
)
