package com.magicv3.scanner3d

import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.opengl.GLSurfaceView
import com.google.ar.core.Session
import com.google.ar.core.Frame
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10
import kotlin.math.max

/**
 * CameraRenderer — ARCore OES Texture renderer
 *
 * Düzeltmeler:
 *  1. CENTER_CROP: Kamera en-boy oranı ekrandan farklı olduğunda görüntü gerilmez,
 *     merkeze hizalanarak kırpılır.
 *  2. Dinamik Rotasyon: displayRotation dışarıdan iletilir, session.setDisplayGeometry
 *     doğru rotasyon değeriyle çağrılır.
 *  3. GL_LINEAR filtreleme: pikselleşme engellenir.
 */
class CameraRenderer(
    private val session: Session,
    private val onFrameAvailable: (Frame) -> Unit
) : GLSurfaceView.Renderer {

    private var textureId = -1

    // Dışarıdan ayarlanabilir — GLSurfaceView oluşturulduğunda set et
    var displayRotation: Int = 0

    // Surface boyutları (ekran px)
    private var surfaceWidth  = 1
    private var surfaceHeight = 1

    // Kamera görüntü boyutları (ARCore config'den)
    private var cameraWidth  = 4
    private var cameraHeight = 3

    // ─── Vertex Shader ───────────────────────────────────────
    private val vertexShaderCode = """
        attribute vec4 position;
        attribute vec2 inputTextureCoordinate;
        varying vec2 textureCoordinate;
        void main() {
            gl_Position = position;
            textureCoordinate = inputTextureCoordinate;
        }
    """.trimIndent()

    // ─── Fragment Shader (OES external texture) ───────────────
    private val fragmentShaderCode = """
        #extension GL_OES_EGL_image_external : require
        precision mediump float;
        varying vec2 textureCoordinate;
        uniform samplerExternalOES videoTex;
        void main() {
            gl_FragColor = texture2D(videoTex, textureCoordinate);
        }
    """.trimIndent()

    private var program = 0
    private lateinit var vertexBuffer: FloatBuffer
    private lateinit var textureBuffer: FloatBuffer

    // Tam ekran quad (NDC)
    private val vertices = floatArrayOf(
        -1f, -1f,
         1f, -1f,
        -1f,  1f,
         1f,  1f
    )

    // ─── Texture koordinatları CENTER_CROP hesabı ─────────────
    /**
     * CENTER_CROP modu:
     *   Kamera ve ekran en-boy oranları karşılaştırılır.
     *   Daha büyük eksen normalize edilir → kırpma ofsetleri hesaplanır.
     *   Sonuç: görüntü asla gerilmez, ortadan kırpılır.
     */
    private fun buildCropTextureCoords(
        surfW: Int, surfH: Int,
        camW: Int,  camH: Int
    ): FloatArray {
        val screenAspect = surfW.toFloat() / surfH.toFloat()
        val cameraAspect = camW.toFloat()  / camH.toFloat()

        var u0 = 0f; var u1 = 1f
        var v0 = 0f; var v1 = 1f

        if (cameraAspect > screenAspect) {
            // Kamera daha geniş → yatay kırp
            val scale = screenAspect / cameraAspect
            val offset = (1f - scale) / 2f
            u0 = offset; u1 = 1f - offset
        } else {
            // Kamera daha uzun → dikey kırp
            val scale = cameraAspect / screenAspect
            val offset = (1f - scale) / 2f
            v0 = offset; v1 = 1f - offset
        }

        // Quad sırası: BL, BR, TL, TR
        return floatArrayOf(
            u0, v1,
            u1, v1,
            u0, v0,
            u1, v0
        )
    }

    private fun updateTextureBuffer() {
        val coords = buildCropTextureCoords(surfaceWidth, surfaceHeight, cameraWidth, cameraHeight)
        textureBuffer = ByteBuffer.allocateDirect(coords.size * 4).run {
            order(ByteOrder.nativeOrder())
            asFloatBuffer().apply { put(coords); position(0) }
        }
    }

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        GLES20.glClearColor(0f, 0f, 0f, 1f)

        // OES Texture oluştur
        val textures = IntArray(1)
        GLES20.glGenTextures(1, textures, 0)
        textureId = textures[0]
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, textureId)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
        // GL_LINEAR → pikselleşme engellendi
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)

        session.setCameraTextureName(textureId)

        // ARCore kamera config'den gerçek kamera boyutlarını al
        val camConfig = session.cameraConfig
        cameraWidth  = camConfig.imageSize.width
        cameraHeight = camConfig.imageSize.height

        // Vertex buffer
        vertexBuffer = ByteBuffer.allocateDirect(vertices.size * 4).run {
            order(ByteOrder.nativeOrder())
            asFloatBuffer().apply { put(vertices); position(0) }
        }

        // Başlangıç texture buffer (yüzey bilinmeden, 4:3 varsayım)
        updateTextureBuffer()

        // Shader programı
        val vs = loadShader(GLES20.GL_VERTEX_SHADER,   vertexShaderCode)
        val fs = loadShader(GLES20.GL_FRAGMENT_SHADER, fragmentShaderCode)
        program = GLES20.glCreateProgram().also {
            GLES20.glAttachShader(it, vs)
            GLES20.glAttachShader(it, fs)
            GLES20.glLinkProgram(it)
        }
    }

    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
        GLES20.glViewport(0, 0, width, height)
        surfaceWidth  = max(width,  1)
        surfaceHeight = max(height, 1)

        // Doğru rotasyon ile geometry set et
        session.setDisplayGeometry(displayRotation, width, height)

        // Texture crop koordinatlarını ekrana göre yeniden hesapla
        updateTextureBuffer()
    }

    override fun onDrawFrame(gl: GL10?) {
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT or GLES20.GL_DEPTH_BUFFER_BIT)
        if (program == 0) return

        val frame = try {
            session.update()
        } catch (e: Exception) {
            return
        }

        GLES20.glUseProgram(program)

        val posHandle = GLES20.glGetAttribLocation(program, "position")
        GLES20.glEnableVertexAttribArray(posHandle)
        GLES20.glVertexAttribPointer(posHandle, 2, GLES20.GL_FLOAT, false, 0, vertexBuffer)

        val texHandle = GLES20.glGetAttribLocation(program, "inputTextureCoordinate")
        GLES20.glEnableVertexAttribArray(texHandle)
        GLES20.glVertexAttribPointer(texHandle, 2, GLES20.GL_FLOAT, false, 0, textureBuffer)

        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, textureId)
        GLES20.glUniform1i(GLES20.glGetUniformLocation(program, "videoTex"), 0)

        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)

        GLES20.glDisableVertexAttribArray(posHandle)
        GLES20.glDisableVertexAttribArray(texHandle)

        onFrameAvailable(frame)
    }

    private fun loadShader(type: Int, code: String): Int =
        GLES20.glCreateShader(type).also {
            GLES20.glShaderSource(it, code)
            GLES20.glCompileShader(it)
        }
}
