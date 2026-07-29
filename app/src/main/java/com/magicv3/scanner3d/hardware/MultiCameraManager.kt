package com.magicv3.scanner3d.hardware

import android.content.Context
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.util.Log

enum class PhysicalLensType {
    WIDE,
    ULTRA_WIDE,
    TELEPHOTO,
    UNKNOWN
}

data class PhysicalCameraInfo(
    val id: String,
    val lensType: PhysicalLensType,
    val focalLengths: FloatArray,
    val sensorOrientation: Int
)

class MultiCameraManager(private val context: Context) {
    private val tag = "MultiCameraManager"
    private val cameraManager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager

    fun getAvailableRearCameras(): List<PhysicalCameraInfo> {
        val result = mutableListOf<PhysicalCameraInfo>()
        try {
            val cameraIds = cameraManager.cameraIdList
            for (id in cameraIds) {
                val chars = cameraManager.getCameraCharacteristics(id)
                val facing = chars.get(CameraCharacteristics.LENS_FACING)
                if (facing == CameraCharacteristics.LENS_FACING_BACK) {
                    val focals = chars.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS) ?: floatArrayOf(4.5f)
                    val orient = chars.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 90

                    val mainFocal = focals.firstOrNull() ?: 4.5f
                    val type = when {
                        mainFocal < 3.0f -> PhysicalLensType.ULTRA_WIDE
                        mainFocal > 8.0f -> PhysicalLensType.TELEPHOTO
                        else             -> PhysicalLensType.WIDE
                    }

                    result.add(PhysicalCameraInfo(id, type, focals, orient))
                    Log.i(tag, "Bulunan Fiziki Kamera ID: $id, Tip: $type, Odak Uzaklıkları: ${focals.joinToString()}")
                }
            }
        } catch (e: Exception) {
            Log.e(tag, "Kamera listesi sorgulanırken hata oluştu", e)
        }
        return result
    }
}
