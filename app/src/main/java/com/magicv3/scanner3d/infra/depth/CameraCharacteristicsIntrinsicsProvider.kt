package com.magicv3.scanner3d.infra.depth

import android.content.Context
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.util.Log
import com.magicv3.scanner3d.domain.depth.CameraIntrinsics
import com.magicv3.scanner3d.domain.depth.CameraIntrinsicsProvider

/**
 * CameraCharacteristics tabanlı intrinsik sağlayıcı.
 *
 * `LENS_INTRINSIC_CALIBRATION` API 29+ olduğundan minSdk 28 cihazlarda
 * güvenilir şekilde kullanamayız; bu yüzden fiziksel sensör boyutu ve
 * piksel dizisi üzerinden pinhole intrinsikleri yaklaşık olarak hesaplarız:
 *
 *   fx = focalMm * pixelWidth  / sensorWidthMm
 *   fy = focalMm * pixelHeight / sensorHeightMm
 *   cx = pixelWidth  / 2   (principal point merkez varsayılır)
 *   cy = pixelHeight / 2
 *
 * Karakteristikler okunamazsa null döner; çağıran
 * [CameraIntrinsics.SAFE_DEFAULT]'a düşer.
 *
 * Dönen intrinsikler full-sensor piksel uzayında ifade edildiğinden
 * [CameraIntrinsics.sourceWidth]/[CameraIntrinsics.sourceHeight] sensor array
 * boyutuna set edilir. DepthToPointsUseCase, intrinsikleri depth grid'ine
 * bu oranla ölçekler (örn. 4096x3072 sensor → 518x518 depth).
 */
class CameraCharacteristicsIntrinsicsProvider(
    private val context: Context,
    private val cameraId: String = DEFAULT_CAMERA_ID,
) : CameraIntrinsicsProvider {

    override fun getIntrinsics(): CameraIntrinsics? {
        return runCatching {
            val manager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
            val chars = manager.getCameraCharacteristics(cameraId)

            val focal = chars.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)
                ?.firstOrNull() ?: return null
            val physical = chars.get(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE)
                ?: return null
            val pixelArray = chars.get(CameraCharacteristics.SENSOR_INFO_PIXEL_ARRAY_SIZE)
                ?: return null

            if (physical.width <= 0f || physical.height <= 0f ||
                pixelArray.width <= 0 || pixelArray.height <= 0
            ) {
                return null
            }

            CameraIntrinsics(
                fx = focal * pixelArray.width / physical.width,
                fy = focal * pixelArray.height / physical.height,
                cx = pixelArray.width / 2f,
                cy = pixelArray.height / 2f,
                // Full-sensor piksel uzayı — usecase depth grid'ine bu oranla ölçekler.
                sourceWidth = pixelArray.width,
                sourceHeight = pixelArray.height,
            )
        }.onFailure { e ->
            Log.w(TAG, "Failed to read intrinsics from CameraCharacteristics: ${e.message}")
        }.getOrNull()
    }

    companion object {
        private const val TAG = "CamCharIntrinsics"

        /** Ana kamera (logical id "0") için varsayılan kamera ID'si. */
        private const val DEFAULT_CAMERA_ID = "0"
    }
}
