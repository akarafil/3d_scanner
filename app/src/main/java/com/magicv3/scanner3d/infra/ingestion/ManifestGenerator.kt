package com.magicv3.scanner3d.infra.ingestion

import android.content.Context
import android.os.Build
import androidx.exifinterface.media.ExifInterface
import com.magicv3.scanner3d.domain.model.ScanSession
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Faz 3.0 - ScanSession klasörüne AlgorDroid uyumlu manifest.json üretir.
 */
class ManifestGenerator(private val context: Context) {

    companion object {
        private const val INGESTION_PROTOCOL_VERSION = "1.0"
        private const val MANIFEST_FILE_NAME = "manifest.json"
    }

    /**
     * Proje klasörüne manifest.json dosyasını yazar.
     */
    suspend fun generateManifest(session: ScanSession): File = withContext(Dispatchers.IO) {
        val rootJson = JSONObject().apply {
            put("protocolVersion", INGESTION_PROTOCOL_VERSION)
            put("sessionId", session.sessionId.toString())
            put("projectName", session.projectName)
            put("createdAtMs", session.createdAtMs)
            put("frameCount", session.frameCount)
            put("totalBytes", session.totalBytes)

            // Device Telemetry
            put("device", JSONObject().apply {
                put("make", Build.MANUFACTURER)
                put("model", Build.MODEL)
                put("brand", Build.BRAND)
                put("sdkVersion", Build.VERSION.SDK_INT)
            })

            // Frame Manifest Array
            val framesArray = JSONArray()
            session.frames.forEach { frame ->
                val frameObj = JSONObject().apply {
                    put("filename", "frames/${frame.file.name}")
                    put("lensId", frame.lensId)
                    put("lensType", frame.lensType)
                    put("focalLengthMm", frame.focalMm.toDouble())
                    put("bytes", frame.bytes)
                    put("capturedAtMs", frame.capturedAtMs)

                    if (frame.translation != null && frame.rotation != null) {
                        put("pose", JSONObject().apply {
                            put("translation", JSONArray(frame.translation))
                            put("rotation_quaternion", JSONArray(frame.rotation))
                        })
                    }

                    // EXIF metadata ekstraksiyonu
                    runCatching {
                        val exif = ExifInterface(frame.file.absolutePath)
                        val focal35 = exif.getAttributeInt(ExifInterface.TAG_FOCAL_LENGTH_IN_35MM_FILM, 0)
                        val width = exif.getAttributeInt(ExifInterface.TAG_IMAGE_WIDTH, 0)
                        val height = exif.getAttributeInt(ExifInterface.TAG_IMAGE_LENGTH, 0)
                        val orient = exif.getAttributeInt(ExifInterface.TAG_ORIENTATION, 1)
                        val userComment = exif.getAttribute(ExifInterface.TAG_USER_COMMENT) ?: ""

                        put("focalLength35mmEquiv", focal35)
                        put("width", width)
                        put("height", height)
                        put("orientation", orient)
                        put("userComment", userComment)
                    }
                }
                framesArray.put(frameObj)
            }
            put("frames", framesArray)
        }

        val manifestFile = File(session.folder, MANIFEST_FILE_NAME)
        manifestFile.writeText(rootJson.toString(2))
        android.util.Log.i("ManifestGenerator", "Generated manifest.json for ${session.projectName} (${manifestFile.length()} B)")
        manifestFile
    }
}
