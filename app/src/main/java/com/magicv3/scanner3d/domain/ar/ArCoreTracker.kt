package com.magicv3.scanner3d.domain.ar

import android.content.Context
import android.opengl.GLES11Ext
import android.opengl.GLES20
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
    private var cameraTextureId: Int = -1

    var isPaused: Boolean = true
        private set

    private val _accumulatedPoints = MutableStateFlow<List<Point3D>>(emptyList())
    val accumulatedPoints: StateFlow<List<Point3D>> = _accumulatedPoints

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
                
                // GL Context hatasını çözmek için sahte OES Texture oluşturup ARCore'a bağlıyoruz
                generateGlTexture()
                arSession?.setCameraTextureName(cameraTextureId)
            }
            
            arSession?.resume()
            isPaused = false
            Log.i("ArCoreTracker", "ARCore Session başarıyla başlatıldı ve texture bağlandı.")
        } catch (e: Exception) {
            Log.e("ArCoreTracker", "ARCore başlatma hatası: ${e.message}")
        }
    }

    private fun generateGlTexture() {
        val textures = IntArray(1)
        GLES20.glGenTextures(1, textures, 0)
        cameraTextureId = textures[0]
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, cameraTextureId)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_NEAREST)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_NEAREST)
    }

    fun updateFrame() {
        // Oturum durdurulmuşsa veya yüklenmemişse update yapma (AR_ERROR_SESSION_PAUSED engeli)
        if (isPaused || arSession == null) return

        try {
            val frame = arSession?.update() ?: return
            val camera = frame.camera

            if (camera.trackingState == TrackingState.TRACKING) {
                val pose = camera.pose
                currentPose = CameraPose(
                    translation = pose.translation,
                    rotationQuaternion = pose.rotationQuaternion
                )

                val pointCloud = frame.acquirePointCloud()
                extractAndAccumulatePoints(pointCloud)
                pointCloud.release()
            }
        } catch (e: Exception) {
            // Log spam'ı önlemek için sadece beklenmedik hataları yazdırıyoruz
            Log.w("ArCoreTracker", "Frame update hatası: ${e.message}")
        }
    }

    private fun extractAndAccumulatePoints(pointCloud: PointCloud) {
        val pointsBuffer: FloatBuffer = pointCloud.points ?: return
        val newPoints = mutableListOf<Point3D>()

        while (pointsBuffer.hasRemaining() && pointsBuffer.remaining() >= 4) {
            val x = pointsBuffer.get()
            val y = pointsBuffer.get()
            val z = pointsBuffer.get()
            val confidence = pointsBuffer.get()

            if (confidence > 0.3f) {
                newPoints.add(Point3D(x, y, z, confidence))
            }
        }

        val updatedList = (_accumulatedPoints.value + newPoints).takeLast(15000)
        _accumulatedPoints.value = updatedList
    }

    fun pauseSession() {
        isPaused = true
        try {
            arSession?.pause()
        } catch (e: Exception) {
            Log.e("ArCoreTracker", "Pause hatası: ${e.message}")
        }
    }

    fun stopSession() {
        isPaused = true
        try {
            arSession?.pause()
            arSession?.close()
        } catch (e: Exception) {
            Log.e("ArCoreTracker", "Stop hatası: ${e.message}")
        } finally {
            arSession = null
        }
    }
}
