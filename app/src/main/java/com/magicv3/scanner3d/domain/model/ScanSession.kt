package com.magicv3.scanner3d.domain.model

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

/**
 * Phase 2.3 — Tek bir tarama oturumunu (project) temsil eder.
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
                    ScanFrame(
                        file = File(folder, "frames/${f.getString("file")}"),
                        lensId = f.getString("lensId"),
                        lensType = f.getString("lensType"),
                        focalMm = f.getDouble("focalMm").toFloat(),
                        bytes = f.getLong("bytes"),
                        capturedAtMs = f.getLong("capturedAtMs"),
                    )
                }
                ScanSession(
                    sessionId = UUID.fromString(root.getString("sessionId")),
                    projectName = root.optString("projectName", "Tarama"),
                    createdAtMs = root.getLong("createdAtMs"),
                    frames = frames,
                    totalBytes = root.optLong("totalBytes", frames.sumOf { it.bytes }),
                    folder = folder,
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
)
