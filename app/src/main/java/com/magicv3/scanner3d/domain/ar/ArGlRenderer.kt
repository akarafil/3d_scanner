package com.magicv3.scanner3d.domain.ar

import android.content.Context
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.opengl.GLSurfaceView
import android.util.Log
import com.google.ar.core.Config
import com.google.ar.core.PointCloud
import com.google.ar.core.Session
import com.google.ar.core.TrackingState
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10

data class CameraPose(val translation: FloatArray, val rotationQuaternion: FloatArray)

class ArGlRenderer(
    private val context: Context,
    private val onPoseUpdated: (CameraPose) -> Unit
) : GLSurfaceView.Renderer {

    var arSession: Session? = null
        private set

    var onFrameAvailable: ((com.google.ar.core.Frame) -> Unit)? = null

    private var cameraTextureId = -1
    private var pointCloudProgram = 0
    private var backgroundProgram = 0

    // Shader Handles (Point Cloud)
    private var positionHandle = 0
    private var mvpMatrixHandle = 0
    private var pointSizeHandle = 0

    // Shader Handles (Background)
    private var bgPositionHandle = 0
    private var bgTexCoordHandle = 0
    private var bgTextureHandle = 0

    // Matrix Buffers
    private val viewMatrix = FloatArray(16)
    private val projectionMatrix = FloatArray(16)
    private val mvpMatrix = FloatArray(16)

    // Background Quad Geometry
    private val QUAD_COORDS = floatArrayOf(
        -1.0f, -1.0f, 0.0f,
        -1.0f, +1.0f, 0.0f,
        +1.0f, -1.0f, 0.0f,
        +1.0f, +1.0f, 0.0f
    )
    private val TEX_COORDS = floatArrayOf(
        0.0f, 1.0f,
        0.0f, 0.0f,
        1.0f, 1.0f,
        1.0f, 0.0f
    )

    private val quadCoordsBuffer = ByteBuffer.allocateDirect(QUAD_COORDS.size * 4)
        .order(ByteOrder.nativeOrder()).asFloatBuffer().apply {
            put(QUAD_COORDS)
            position(0)
        }
    private val texCoordsBuffer = ByteBuffer.allocateDirect(TEX_COORDS.size * 4)
        .order(ByteOrder.nativeOrder()).asFloatBuffer().apply {
            put(TEX_COORDS)
            position(0)
        }
    private val transformedTexCoordsBuffer = ByteBuffer.allocateDirect(TEX_COORDS.size * 4)
        .order(ByteOrder.nativeOrder()).asFloatBuffer()

    // GL Thread Güvenli Durum Bayrağı
    @Volatile
    var isPaused = false

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        GLES20.glClearColor(0.0f, 0.0f, 0.0f, 0.0f) // Transparan arka plan

        // 1. ARCore Kamera Dokusunu OES Formatında Oluştur
        cameraTextureId = createOesTexture()

        // 2. Shader Programlarını Derle
        initShaders()

        // 3. ARCore Oturumunu GL Thread'i İçinde Başlat/Tekrar Bağla
        try {
            if (arSession == null) {
                arSession = Session(context).apply {
                    val arConfig = Config(this).apply {
                        updateMode = Config.UpdateMode.LATEST_CAMERA_IMAGE
                        focusMode = Config.FocusMode.AUTO
                    }
                    configure(arConfig)
                }
            }
            arSession?.setCameraTextureName(cameraTextureId)
            arSession?.resume()
            isPaused = false
            Log.i("ArGlRenderer", "GL Surface kuruldu. ARCore oturumu ve EGL Context aktif.")
        } catch (e: Exception) {
            Log.e("ArGlRenderer", "ARCore GL kurulum hatası: ${e.message}")
        }
    }

    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
        GLES20.glViewport(0, 0, width, height)
        arSession?.setDisplayGeometry(0, width, height)
    }

    override fun onDrawFrame(gl: GL10?) {
        // Ekranı Temizle
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT or GLES20.GL_DEPTH_BUFFER_BIT)

        if (isPaused || arSession == null) return

        try {
            // ARCore update() metodu ARTIK GÜVENLE GL THREAD'İ İÇİNDE ÇAĞRILIYOR
            val frame = arSession?.update() ?: return

            // 1. Kamera Feed'ini Arka Plana Çiz (CameraX Preview yerine)
            drawBackground(frame)

            onFrameAvailable?.invoke(frame)

            val camera = frame.camera
            if (camera.trackingState == TrackingState.TRACKING) {
                // 2. Anlık 6-DoF Pozisyonunu Al ve Üst Katmana Bildir
                val pose = camera.pose
                onPoseUpdated(
                    CameraPose(
                        translation = pose.translation,
                        rotationQuaternion = pose.rotationQuaternion
                    )
                )

                // 3. Kamera Matrislerini Çek (Projection * View)
                camera.getProjectionMatrix(projectionMatrix, 0, 0.1f, 100.0f)
                camera.getViewMatrix(viewMatrix, 0)
                android.opengl.Matrix.multiplyMM(mvpMatrix, 0, projectionMatrix, 0, viewMatrix, 0)

                // 4. Nokta Bulutunu (Point Cloud) GPU Üzerinde Çiz
                val pointCloud = frame.acquirePointCloud()
                drawPointCloud(pointCloud)
                pointCloud.release()
            }
        } catch (e: Exception) {
            Log.w("ArGlRenderer", "Draw Frame Hatası: ${e.message}")
        }
    }

    private fun drawBackground(frame: com.google.ar.core.Frame) {
        // Ekran geometrisi değiştikçe doku koordinatlarını dönüştür
        if (frame.hasDisplayGeometryChanged()) {
            texCoordsBuffer.position(0)
            transformedTexCoordsBuffer.position(0)
            frame.transformDisplayUvCoords(texCoordsBuffer, transformedTexCoordsBuffer)
        }

        GLES20.glUseProgram(backgroundProgram)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, cameraTextureId)
        GLES20.glUniform1i(bgTextureHandle, 0)

        // Pozisyonları ata
        quadCoordsBuffer.position(0)
        GLES20.glVertexAttribPointer(bgPositionHandle, 3, GLES20.GL_FLOAT, false, 0, quadCoordsBuffer)
        GLES20.glEnableVertexAttribArray(bgPositionHandle)

        // Doku koordinatlarını ata
        transformedTexCoordsBuffer.position(0)
        GLES20.glVertexAttribPointer(bgTexCoordHandle, 2, GLES20.GL_FLOAT, false, 0, transformedTexCoordsBuffer)
        GLES20.glEnableVertexAttribArray(bgTexCoordHandle)

        // Çiz
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)

        GLES20.glDisableVertexAttribArray(bgPositionHandle)
        GLES20.glDisableVertexAttribArray(bgTexCoordHandle)
    }

    private fun drawPointCloud(pointCloud: PointCloud) {
        val pointsBuffer = pointCloud.points ?: return
        val numPoints = pointsBuffer.remaining() / 4
        if (numPoints <= 0) return

        GLES20.glUseProgram(pointCloudProgram)

        // MVP Matrix Yükle
        GLES20.glUniformMatrix4fv(mvpMatrixHandle, 1, false, mvpMatrix, 0)
        GLES20.glUniform1f(pointSizeHandle, 8.0f) // Nokta boyutu (pixels)

        // Point VBO Verisini Bağla [X, Y, Z, Confidence]
        pointsBuffer.position(0)
        GLES20.glVertexAttribPointer(positionHandle, 4, GLES20.GL_FLOAT, false, 16, pointsBuffer)
        GLES20.glEnableVertexAttribArray(positionHandle)

        // GPU Üzerinde Nokta Bulutunu Fırlat
        GLES20.glDrawArrays(GLES20.GL_POINTS, 0, numPoints)

        GLES20.glDisableVertexAttribArray(positionHandle)
    }

    private fun createOesTexture(): Int {
        val textures = IntArray(1)
        GLES20.glGenTextures(1, textures, 0)
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, textures[0])
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_NEAREST)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_NEAREST)
        return textures[0]
    }

    private fun initShaders() {
        // --- 1. Point Cloud Shader Programı ---
        val pcVertexShaderCode = """
            uniform mat4 uMVPMatrix;
            uniform float uPointSize;
            attribute vec4 aPosition;
            varying float vConfidence;
            void main() {
               gl_Position = uMVPMatrix * vec4(aPosition.xyz, 1.0);
               gl_PointSize = uPointSize;
               vConfidence = aPosition.w; // Confidence verisini fragment shader'a taşı
            }
        """.trimIndent()

        val pcFragmentShaderCode = """
            precision mediump float;
            varying float vConfidence;
            void main() {
               // Cyberpunk Yeşil/Mavi Renk Paleti
               if (vConfidence < 0.2) discard;
               gl_FragColor = vec4(0.0, 1.0, 0.5, vConfidence);
            }
        """.trimIndent()

        val pcVShader = loadShader(GLES20.GL_VERTEX_SHADER, pcVertexShaderCode)
        val pcFShader = loadShader(GLES20.GL_FRAGMENT_SHADER, pcFragmentShaderCode)

        pointCloudProgram = GLES20.glCreateProgram().apply {
            GLES20.glAttachShader(this, pcVShader)
            GLES20.glAttachShader(this, pcFShader)
            GLES20.glLinkProgram(this)
        }

        positionHandle = GLES20.glGetAttribLocation(pointCloudProgram, "aPosition")
        mvpMatrixHandle = GLES20.glGetUniformLocation(pointCloudProgram, "uMVPMatrix")
        pointSizeHandle = GLES20.glGetUniformLocation(pointCloudProgram, "uPointSize")

        // --- 2. Kamera Arka Plan Shader Programı ---
        val bgVertexShaderCode = """
            attribute vec4 aPosition;
            attribute vec2 aTexCoord;
            varying vec2 vTexCoord;
            void main() {
               gl_Position = aPosition;
               vTexCoord = aTexCoord;
            }
        """.trimIndent()

        val bgFragmentShaderCode = """
            #extension GL_OES_EGL_image_external : require
            precision mediump float;
            varying vec2 vTexCoord;
            uniform samplerExternalOES uTexture;
            void main() {
               gl_FragColor = texture2D(uTexture, vTexCoord);
            }
        """.trimIndent()

        val bgVShader = loadShader(GLES20.GL_VERTEX_SHADER, bgVertexShaderCode)
        val bgFShader = loadShader(GLES20.GL_FRAGMENT_SHADER, bgFragmentShaderCode)

        backgroundProgram = GLES20.glCreateProgram().apply {
            GLES20.glAttachShader(this, bgVShader)
            GLES20.glAttachShader(this, bgFShader)
            GLES20.glLinkProgram(this)
        }

        bgPositionHandle = GLES20.glGetAttribLocation(backgroundProgram, "aPosition")
        bgTexCoordHandle = GLES20.glGetAttribLocation(backgroundProgram, "aTexCoord")
        bgTextureHandle = GLES20.glGetUniformLocation(backgroundProgram, "uTexture")
    }

    private fun loadShader(type: Int, shaderCode: String): Int {
        return GLES20.glCreateShader(type).also { shader ->
            GLES20.glShaderSource(shader, shaderCode)
            GLES20.glCompileShader(shader)
            val compiled = IntArray(1)
            GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, compiled, 0)
            if (compiled[0] == 0) {
                Log.e("ArGlRenderer", "Shader compilation error: " + GLES20.glGetShaderInfoLog(shader))
            }
        }
    }

    fun onPause() {
        isPaused = true
        arSession?.pause()
    }

    fun onResume() {
        if (arSession != null) {
            arSession?.resume()
            isPaused = false
        }
    }

    fun onDestroy() {
        isPaused = true
        arSession?.pause()
        arSession?.close()
        arSession = null
    }
}
