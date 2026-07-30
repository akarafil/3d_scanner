package com.magicv3.scanner3d.infra.camera

import android.content.Context
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.util.Log
import android.util.Size
import android.util.SizeF
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Camera2 `CameraManager` üzerinden tüm logical + physical camera sensörlerini enumerate eden
 * suspend catalog builder.
 *
 * Kullanım:
 *   val lenses = CameraLensCatalog(context).enumerateLenses()
 *
 * Bu sınıf stream kurmaz, sadece metadata okur — Honor Magic V3'te
 * `getPhysicalCameraIds()` çağrısının `illegalAccessAuxCamera` ile reject edilip
 * edilmektedir olmadığını test etmenin tek güvenli yolu budur.
 */
class CameraLensCatalog(private val context: Context) {

    companion object {
        private const val TAG = "LensCatalog"
        // 35mm equivalence için diagonal tabanlı crop factor (full-frame diagonal ≈ 43.27mm)
        private const val FULL_FRAME_DIAGONAL_MM = 43.27f
        // Main lens'e göre zoom oranı eşikleri — Honor Magic V3 spesifikasyonu temelinde:
        private const val ZOOM_RATIO_ULTRAWIDE_MAX = 0.7f   // main'den önemli ölçüde daha geniş
        private const val ZOOM_RATIO_TELE_MIN = 1.5f        // 1.5x+ → telephoto
        private const val ZOOM_RATIO_PERISCOPE_MIN = 4.5f   // ~5x+ → periscope
    }

    /**
     * Sistemdeki tüm kamera sensörlerini enumerate eder.
     * Her logical camera için:
     *  - LOGICAL_MULTI_CAMERA capability flag kontrolü
     *  - physicalCameraIds set'i çekme
     *  - her physical sensörün focal length / sensor size / pixel array okuma
     *  - lens tipi sınıflandırma
     *
     * Hata yönetimi: ID başına bağımsız try-catch — tek physical ID başarısız olursa
     * diğerleri yine de enumerate edilir (örn. Honor bazı aux ID'leri OK ama bazıları reject).
     *
     * @return Sınıflandırılmış CameraLens listesi (logical+physical hepsi)
     */
    suspend fun enumerateLenses(): List<CameraLens> = withContext(Dispatchers.IO) {
        val cameraManager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        val logicalIds = runCatching { cameraManager.cameraIdList }
            .onFailure { Log.e(TAG, "cameraIdList failed: ${it.message}", it) }
            .getOrDefault(emptyArray())

        if (logicalIds.isEmpty()) {
            Log.w(TAG, "Logical camera ID list boş — SystemPrivileged veya vendor restriction olabilir")
            return@withContext emptyList()
        }

        Log.i(TAG, "Logical camera IDs: ${logicalIds.toList()}")

        val lenses = mutableListOf<CameraLens>()
        // Back lens focal length'leri — sınıflandırma için gerekli context
        val backFocalLengths = mutableListOf<Float>()

        // 1) Pass: logical'ların özelliklerini oku, LOGICAL_MULTI_CAMERA olup olmadığını belirle
        data class LogicalSnapshot(
            val id: String,
            val characteristics: CameraCharacteristics,
            val facing: Int,
            val isLogicalMultiCamera: Boolean,
            val physicalIds: Set<String>,
            val mainFocalLengthMm: Float?
        )

        val snapshots = mutableListOf<LogicalSnapshot>()
        for (logicalId in logicalIds) {
            val chars = runCatching { cameraManager.getCameraCharacteristics(logicalId) }
                .onFailure { Log.w(TAG, "Logical id=$logicalId characteristics failed: ${it.message}") }
                .getOrNull() ?: continue

            val facing = chars.get(CameraCharacteristics.LENS_FACING)
                ?: CameraCharacteristics.LENS_FACING_EXTERNAL

            val capabilities = chars.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES)
                ?: intArrayOf()

            val isLogicalMultiCamera = capabilities.any {
                it == CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_LOGICAL_MULTI_CAMERA
            }

            // physicalCameraIds API 28+ — boş dönebilir (honor hide aux policy)
            val physicalIds: Set<String> = if (isLogicalMultiCamera) {
                runCatching { chars.physicalCameraIds }
                    .onFailure { Log.w(TAG, "getPhysicalCameraIds(id=$logicalId) threw: ${it.message}") }
                    .getOrDefault(emptySet())
            } else {
                emptySet()
            }

            val mainFocal = readFocalLength(chars)
            if (facing == CameraCharacteristics.LENS_FACING_BACK && mainFocal != null) {
                backFocalLengths.add(mainFocal)
            }

            Log.i(TAG, "Logical id=$logicalId facing=$facing " +
                "isLogicalMultiCamera=$isLogicalMultiCamera " +
                "physicalIds=${physicalIds.sorted().toList()} " +
                "mainFocal=$mainFocal")

            snapshots.add(LogicalSnapshot(logicalId, chars, facing, isLogicalMultiCamera, physicalIds, mainFocal))
        }

        // Back logical camerasın ana (en kısa ~24mm equiv) focal length'ini referans al
        val referenceBackFocalMm = backFocalLengths.minOrNull()

        // 2) Pass: her sensörden CameraLens inşa et (logical single + physical subcameras)
        for (snap in snapshots) {
            if (snap.physicalIds.isEmpty()) {
                // Single-sensor logical camera — örn. ön kamera id=1
                val lens = buildLens(
                    logicalId = snap.id,
                    physicalId = snap.id,
                    chars = snap.characteristics,
                    facing = snap.facing,
                    isPhysical = false,
                    hasLogicalMultiCamera = false,
                    referenceBackFocalMm = referenceBackFocalMm
                )
                if (lens != null) lenses.add(lens)
            } else {
                // LOGICAL_MULTI_CAMERA → her physical sensörü çek
                for (physicalId in snap.physicalIds.sorted()) {
                    val physChars = runCatching { cameraManager.getCameraCharacteristics(physicalId) }
                        .onFailure {
                            Log.w(TAG, "Physical id=$physicalId (parent=${snap.id}) " +
                                "characteristics FAILED: ${it.message}")
                        }
                        .getOrNull() ?: continue

                    val lens = buildLens(
                        logicalId = snap.id,
                        physicalId = physicalId,
                        chars = physChars,
                        facing = snap.facing,
                        isPhysical = true,
                        hasLogicalMultiCamera = true,
                        referenceBackFocalMm = referenceBackFocalMm
                    )
                    if (lens != null) lenses.add(lens)
                }
            }
        }

        Log.i(TAG, "Catalog Done — toplam ${lenses.size} sensör bulundu:")
        lenses.forEach {
            val tag = if (it.isPhysical) "PHYSICAL(parent=${it.logicalId})" else "LOGICAL_SINGLE"
            Log.i(TAG, "  [${tag}] ${it.toLogString()}")
        }

        lenses
    }

    // ---------- Helpers ----------

    private fun buildLens(
        logicalId: String,
        physicalId: String,
        chars: CameraCharacteristics,
        facing: Int,
        isPhysical: Boolean,
        hasLogicalMultiCamera: Boolean,
        referenceBackFocalMm: Float?
    ): CameraLens? {
        val focalMm = readFocalLength(chars) ?: run {
            Log.w(TAG, "buildLens id=$physicalId — focal length null, skip")
            return null
        }
        val sensorSize = chars.get(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE)
            ?: SizeF(0f, 0f)
        val pixelSize = chars.get(CameraCharacteristics.SENSOR_INFO_PIXEL_ARRAY_SIZE)
            ?: Size(0, 0)

        // Megapixels = (width × height) / 1_000_000 — imperfect binned sensor'da yaklaşık doğrudur
        val megapixels = (pixelSize.width.toLong() * pixelSize.height.toLong()) / 1_000_000f

        // 35mm equivalence — diagonal crop factor (sensor diagonal / full-frame diagonal)
        val focal35Equiv = computeFocal35mmEquiv(focalMm, sensorSize)

        // Main'e göre zoom oranı — referans alınamıyorsa 1.0 varsayalım
        val zoomRatio = if (referenceBackFocalMm != null && referenceBackFocalMm > 0f) {
            focalMm / referenceBackFocalMm
        } else { 1.0f }

        val lensType = classifyLensType(focalMm, zoomRatio, facing, megapixels)
        val sensorOrientation = chars.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 90

        return CameraLens(
            logicalId = logicalId,
            physicalId = physicalId,
            lensType = lensType,
            facing = facing,
            focalLengthMm = focalMm,
            focalLength35mmEquiv = focal35Equiv,
            sensorPhysicalSizeMm = sensorSize,
            pixelArraySize = pixelSize,
            megapixels = megapixels,
            zoomRatioVsMain = zoomRatio,
            isPhysical = isPhysical,
            hasLogicalMultiCamera = hasLogicalMultiCamera,
            sensorOrientationDegrees = sensorOrientation
        )
    }

    private fun readFocalLength(chars: CameraCharacteristics): Float? {
        // LENS_INFO_AVAILABLE_FOCAL_LENGTHS, prime lensler için tek elementtir;
        // multi-aperture sensörlerde birden fazla olabilir, ilkini alıyoruz
        val focalLengths = chars.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)
        return focalLengths?.firstOrNull()
    }

    /**
     * Diagonal crop factor ile ham focal length'i 35mm formatına normalize eder.
     * `SENSOR_INFO_PHYSICAL_SIZE` SizeF cinsinden genişlik ve yüksekliği direkt milimetre olarak döner.
     */
    private fun computeFocal35mmEquiv(focalMm: Float, sensorSize: SizeF): Float {
        val sensorWmm = sensorSize.width
        val sensorHmm = sensorSize.height
        if (sensorWmm <= 0f || sensorHmm <= 0f) return focalMm * 6.0f // ampirik fallback
        val sensorDiagonal = kotlin.math.sqrt(sensorWmm * sensorWmm + sensorHmm * sensorHmm)
        val cropFactor = FULL_FRAME_DIAGONAL_MM / sensorDiagonal
        return focalMm * cropFactor
    }

    /**
     * Fiziksel sensör tipini sınıflandırır. Two-signal heuristic:
     *   1) facing: FRONT → SELFIE (kesin)
     *   2) zoom oranı (main'e göre): BACK sensörlerde ana sinyal
     *
     * Eşikler Honor Magic V3 sensör konfigürasyonuna göre:
     *   - ultrawide  ~13-16mm eqv → oran ~0.55-0.65
     *   - main       ~23mm eqv    → oran 1.0
     *   - tele       ~70mm eqv    → oran ~3.0
     *   - periscope  ~100-180mm eqv → oran ~5+ (Magic V3'ün folded telephoto prime'ı)
     */
    private fun classifyLensType(
        focalMm: Float,
        zoomRatio: Float,
        facing: Int,
        megapixels: Float
    ): CameraLensType {
        if (facing == CameraCharacteristics.LENS_FACING_FRONT) {
            return CameraLensType.SELFIE
        }

        // Megapixels ek sinyali: çok düşük MP + çok kısa focal = ultrawide
        val ultraWideMpHint = megapixels in 8f..16f

        return when {
            zoomRatio < ZOOM_RATIO_ULTRAWIDE_MAX -> {
                if (ultraWideMpHint) CameraLensType.ULTRAWIDE
                else CameraLensType.ULTRAWIDE  // safest classification
            }
            zoomRatio < ZOOM_RATIO_TELE_MIN -> {
                // ~1.0x aralığı — main veya main'den önceki yakın sensör
                CameraLensType.MAIN
            }
            zoomRatio < ZOOM_RATIO_PERISCOPE_MIN -> {
                CameraLensType.TELEPHOTO
            }
            else -> {
                CameraLensType.PERISCOPE
            }
        }
    }
}
