package com.magicv3.scanner3d.infra.ai

import android.content.Context
import android.graphics.Bitmap
import android.graphics.RectF
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * YoloInferenceEngine birim testleri (model yok + saf parse).
 *
 * Kapsam:
 *  - Assets'ta model yok → model yüklü değil, isNpuOrGpuAccelerated = false
 *  - infer(bitmap) → model yokken boş liste
 *  - close() iki kez güvenli
 *  - parseDetections: confidence 0.35 eşiği, NMS 0.45
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class YoloInferenceEngineTest {

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
    }

    @Test
    fun `modelAssetsYoksa_modelYukluDegilVeHizlandirmaYok`() {
        val engine = YoloInferenceEngine(context)

        assertFalse("GPU/NPU hızlandırma olmamalı", engine.isNpuOrGpuAccelerated)
        assertFalse("Model yüklü olmamalı", engine.isModelLoaded)
    }

    @Test
    fun `infer_modelYoksaBosListeDoner`() {
        val engine = YoloInferenceEngine(context)
        val bitmap = Bitmap.createBitmap(32, 32, Bitmap.Config.ARGB_8888)

        val detections = engine.infer(bitmap)

        assertTrue("Model yokken sahte tespit üretilmemeli", detections.isEmpty())
        assertFalse(engine.isModelLoaded)
    }

    @Test
    fun `close_ikiKezCagrilabilir`() {
        val engine = YoloInferenceEngine(context)

        engine.close()
        engine.close() // hata fırlatmamalı
    }

    @Test
    fun `parseDetections_confidenceEsiginiUygular`() {
        val engine = YoloInferenceEngine(context)
        val output = Array(84) { FloatArray(8400) }

        // Sütun 0: box (cx=100, cy=100, w=100, h=100), class0 conf=0.8 → eşik üstü
        output[0][0] = 100f
        output[1][0] = 100f
        output[2][0] = 100f
        output[3][0] = 100f
        output[4][0] = 0.8f

        // Sütun 1: conf=0.2 → eşiğin altında, detection üretilmemeli
        output[0][1] = 200f
        output[1][1] = 200f
        output[2][1] = 50f
        output[3][1] = 50f
        output[4][1] = 0.2f

        val detections = engine.parseDetections(output)

        assertEquals(1, detections.size)
        assertEquals(0, detections[0].classIndex)
        assertEquals(0.8f, detections[0].confidence, 0.001f)
    }

    @Test
    fun `parseDetections_nmsCakisanKutuSuppressEdilir`() {
        val engine = YoloInferenceEngine(context)
        val output = Array(84) { FloatArray(8400) }

        // Sütun 0: class0 conf=0.9, box (0.1, 0.1, 0.5, 0.5)
        output[0][0] = 0.3f * 640f
        output[1][0] = 0.3f * 640f
        output[2][0] = 0.4f * 640f
        output[3][0] = 0.4f * 640f
        output[4][0] = 0.9f

        // Sütun 1: aynı class conf=0.7, box (0.15, 0.15, 0.45, 0.45) → IoU > 0.45 → eleme
        output[0][1] = 0.3f * 640f
        output[1][1] = 0.3f * 640f
        output[2][1] = 0.3f * 640f
        output[3][1] = 0.3f * 640f
        output[4][1] = 0.7f

        val detections = engine.parseDetections(output)

        assertEquals("NMS sonrası yalnızca en yüksek confidence kalmalı", 1, detections.size)
        assertEquals(0.9f, detections[0].confidence, 0.001f)
    }

    @Test
    fun `parseDetections_farkliClassNMSdenEtkilenmez`() {
        val engine = YoloInferenceEngine(context)
        val detections = listOf(
            YoloInferenceEngine.Detection(0, 0.9f, RectF(0.1f, 0.1f, 0.5f, 0.5f)),
            YoloInferenceEngine.Detection(0, 0.7f, RectF(0.15f, 0.15f, 0.55f, 0.55f)),
            YoloInferenceEngine.Detection(1, 0.8f, RectF(0.1f, 0.1f, 0.5f, 0.5f)),
        )

        val result = engine.applyNms(detections)

        assertEquals(2, result.size)
        assertTrue(result.any { it.classIndex == 0 && it.confidence == 0.9f })
        assertTrue(result.any { it.classIndex == 1 })
    }
}
