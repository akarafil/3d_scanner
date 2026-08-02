package com.magicv3.scanner3d.domain.ar

import android.content.Context
import android.util.Log
import com.google.ar.core.*
import com.google.ar.core.exceptions.UnavailableException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.nio.FloatBuffer

data class Point3D(val x: Float, val y: Float, val z: Float, val confidence: Float)
data class CameraPose(val translation: FloatArray, val rotationQuaternion: FloatArray)

class ArCoreTracker(private val context: Context) {

    private var arSession: Session? = null
    
    // Canlı biriken 3B noktalar kümesi (Kullanıcı ekranda hamurun şekillendiğini bu veriden görür)
    private val _accumulatedPoints = MutableStateFlow<List<Point3D>>(emptyList())
    val accumulatedPoints: StateFlow<List<Point3D>> = _accumulatedPoints

    // Anlık Kamera Pozisyonu (6-DoF)
    var currentPose: CameraPose? = null
        private set

    fun setupSession() {
        try {
            if (arSession == null) {
                arSession = Session(context).apply {
                    val config = Config(this).apply {
                        updateMode = Config.UpdateMode.LATEST_CAMERA_IMAGE
                        focusMode = Config.FocusMode.AUTO
                    }
                    configure(config)
                }
            }
            arSession?.resume()
            Log.i("ArCoreTracker", "ARCore Session başarıyla başlatıldı.")
        } catch (e: UnavailableException) {
            Log.e("ArCoreTracker", "ARCore cihazda desteklenmiyor veya izin eksik: ${e.message}")
        }
    }

    /**
     * Her kamera önizleme karesinde (Render Loop) çağrılır.
     */
    fun updateFrame() {
        val session = arSession ?: return
        try {
            val frame = session.update()
            val camera = frame.camera

            if (camera.trackingState == TrackingState.TRACKING) {
                // 1. Kamera Pose Verisini Al (Translation + Quaternion)
                val pose = camera.pose
                currentPose = CameraPose(
                    translation = pose.translation,
                    rotationQuaternion = pose.rotationQuaternion
                )

                // 2. Nokta Bulutunu Çek ve Tampona Ekle
                val pointCloud = frame.acquirePointCloud()
                extractAndAccumulatePoints(pointCloud)
                pointCloud.release()
            }
        } catch (e: Exception) {
            Log.w("ArCoreTracker", "Frame update hatası: ${e.message}")
        }
    }

    private fun extractAndAccumulatePoints(pointCloud: PointCloud) {
        val pointsBuffer: FloatBuffer = pointCloud.points ?: return
        val newPoints = mutableListOf<Point3D>()
        
        // PointCloud formatı: [X, Y, Z, Confidence]
        while (pointsBuffer.hasRemaining() && pointsBuffer.remaining() >= 4) {
            val x = pointsBuffer.get()
            val y = pointsBuffer.get()
            val z = pointsBuffer.get()
            val confidence = pointsBuffer.get()

            // Sadece güvenilir noktaları al
            if (confidence > 0.3f) {
                newPoints.add(Point3D(x, y, z, confidence))
            }
        }

        // Mevcut noktalarla birleştir (Max 15.000 nokta ile performans koruması)
        val updatedList = (_accumulatedPoints.value + newPoints).takeLast(15000)
        _accumulatedPoints.value = updatedList
    }

    fun pauseSession() {
        arSession?.pause()
    }

    fun stopSession() {
        arSession?.pause()
        arSession?.close()
        arSession = null
    }
}
