package com.magicv3.scanner3d.ui.scan

import android.content.Context
import android.graphics.PixelFormat
import android.opengl.GLSurfaceView
import com.magicv3.scanner3d.domain.ar.ArGlRenderer
import com.magicv3.scanner3d.domain.ar.CameraPose

class ArPointCloudSurfaceView(
    context: Context,
    onPoseUpdated: (CameraPose) -> Unit
) : GLSurfaceView(context) {

    val renderer = ArGlRenderer(context, onPoseUpdated)

    init {
        // OpenGL ES 2.0 Context Talebi
        setEGLContextClientVersion(2)
        
        // Kamera Önizlemesi Üzerinde Şeffaf Katman (Arka plan görünsün diye)
        setEGLConfigChooser(8, 8, 8, 8, 16, 0)
        holder.setFormat(PixelFormat.TRANSLUCENT)
        setZOrderMediaOverlay(true)

        setRenderer(renderer)

        // O-5: RENDERMODE_CONTINUOUSLY yerine WHEN_DIRTY + requestRender.
        // Renderer her kare çiziminin sonunda bir sonraki kareyi kendisi talep
        // eder (ARCore update akışı kesintisiz); paused durumda döngü durur →
        // pil tasarrufu.
        renderMode = RENDERMODE_WHEN_DIRTY
        renderer.requestRender = { requestRender() }
    }

    override fun onResume() {
        super.onResume()
        renderer.onResume()
        // WHEN_DIRTY modunda ilk kareyi manuel tetikle; sonrası renderer içinden devam eder.
        requestRender()
    }

    override fun onPause() {
        renderer.onPause()
        super.onPause()
    }

    fun onDestroy() {
        renderer.onDestroy()
    }
}
