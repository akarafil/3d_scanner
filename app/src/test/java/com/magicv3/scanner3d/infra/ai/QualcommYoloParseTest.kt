package com.magicv3.scanner3d.infra.ai

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Qualcomm YOLOv8 3-output format parser testleri.
 *
 * parseQualcommDetections fonksiyonunun 5 farklı senaryosunu doğrular:
 *  1. Tüm skorlar eşiğin altında → boş liste
 *  2. Piksel koordinatlı tek tespit → normalize edilmiş rect
 *  3. Normalize koordinatlı tek tespit → rect değişmez
 *  4. Dejenere kutu (x2 <= x1) → atlanır
 *  5. Aralık dışı koordinatlar → [0, 1]'e kırpılır
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class QualcommYoloParseTest {

    private lateinit var engine: YoloInferenceEngine

    @Before
    fun setUp() {
        val context: Context = ApplicationProvider.getApplicationContext()
        engine = YoloInferenceEngine(context)
    }

    @Test
    fun `thresholdAllBelow_returnsEmpty`() {
        // 8400 anchor'ın tümünün skoru 0.35 eşiğinin altında
        val boxes = Array(8400) { FloatArray(4) { 100f } }
        val scores = FloatArray(8400) { 0.30f } // hepsi < 0.35
        val classIdx = FloatArray(8400) { 0f }

        val result = engine.parseQualcommDetections(boxes, scores, classIdx)

        assertTrue("Tüm skorlar eşiğin altındaysa sonuç boş olmalı", result.isEmpty())
    }

    @Test
    fun `singleDetection_pixelCoords`() {
        // Tek anchor: score=0.9, class=3, boxes=[100, 200, 300, 400] (piksel uzayı)
        // Beklenen normalize: [100/640, 200/640, 300/640, 400/640]
        //                   = [0.15625, 0.3125, 0.46875, 0.625]
        val boxes = Array(8400) { FloatArray(4) { 0f } }
        boxes[0] = floatArrayOf(100f, 200f, 300f, 400f)

        val scores = FloatArray(8400) { 0f }
        scores[0] = 0.9f

        val classIdx = FloatArray(8400) { 0f }
        classIdx[0] = 3f

        val result = engine.parseQualcommDetections(boxes, scores, classIdx)

        assertEquals(1, result.size)
        assertEquals(3, result[0].classIndex)
        assertEquals(0.9f, result[0].confidence, 0.001f)

        val rect = result[0].boundingBox
        assertEquals(0.15625f, rect.left, 0.001f)
        assertEquals(0.3125f, rect.top, 0.001f)
        assertEquals(0.46875f, rect.right, 0.001f)
        assertEquals(0.625f, rect.bottom, 0.001f)
    }

    @Test
    fun `singleDetection_normalizedCoords`() {
        // Tek anchor: score=0.9, class=5, boxes=[0.1, 0.2, 0.3, 0.4] (zaten normalize)
        // maxCoord = 0.4 <= 1.5 → scaleFactor = 1.0 → rect değişmez
        val boxes = Array(8400) { FloatArray(4) { 0f } }
        boxes[0] = floatArrayOf(0.1f, 0.2f, 0.3f, 0.4f)

        val scores = FloatArray(8400) { 0f }
        scores[0] = 0.9f

        val classIdx = FloatArray(8400) { 0f }
        classIdx[0] = 5f

        val result = engine.parseQualcommDetections(boxes, scores, classIdx)

        assertEquals(1, result.size)
        assertEquals(5, result[0].classIndex)

        val rect = result[0].boundingBox
        assertEquals(0.1f, rect.left, 0.001f)
        assertEquals(0.2f, rect.top, 0.001f)
        assertEquals(0.3f, rect.right, 0.001f)
        assertEquals(0.4f, rect.bottom, 0.001f)
    }

    @Test
    fun `degenerateBox_skipped`() {
        // x2 <= x1 olan kutu atlanmalı (dejenere)
        val boxes = Array(8400) { FloatArray(4) { 0f } }
        // x1=300, y1=200, x2=100, y2=400 → x2 < x1 → dejenere
        boxes[0] = floatArrayOf(300f, 200f, 100f, 400f)

        val scores = FloatArray(8400) { 0f }
        scores[0] = 0.9f

        val classIdx = FloatArray(8400) { 0f }
        classIdx[0] = 2f

        val result = engine.parseQualcommDetections(boxes, scores, classIdx)

        assertTrue("Dejenere kutu (x2 <= x1) atlanmalı", result.isEmpty())
    }

    @Test
    fun `clipping_outOfRange`() {
        // Kutu koordinatları [0, 640] aralığının dışında → [0, 1]'e kırpılmalı
        // boxes = [-10, -20, 650, 660] → normalize: [-10/640, -20/640, 650/640, 660/640]
        //       → coerceIn(0,1): [0, 0, 1, 1]
        val boxes = Array(8400) { FloatArray(4) { 0f } }
        boxes[0] = floatArrayOf(-10f, -20f, 650f, 660f)

        val scores = FloatArray(8400) { 0f }
        scores[0] = 0.9f

        val classIdx = FloatArray(8400) { 0f }
        classIdx[0] = 1f

        val result = engine.parseQualcommDetections(boxes, scores, classIdx)

        assertEquals(1, result.size)
        val rect = result[0].boundingBox
        assertEquals(0f, rect.left, 0.001f)
        assertEquals(0f, rect.top, 0.001f)
        assertEquals(1f, rect.right, 0.001f)
        assertEquals(1f, rect.bottom, 0.001f)
    }

    @Test
    fun degenerateBox_y2LeqY1_skipped() {
        // y2 (200) <= y1 (300) → degenerate box, should be skipped
        val boxes = Array(8400) { FloatArray(4) }
        val scores = FloatArray(8400) { 0f }
        val classIdx = FloatArray(8400) { 0f }

        boxes[0] = floatArrayOf(100f, 300f, 200f, 200f)  // x1=100, y1=300, x2=200, y2=200
        scores[0] = 0.9f
        classIdx[0] = 3f

        val result = engine.parseQualcommDetections(boxes, scores, classIdx)
        assertTrue("Degenerate box (y2 <= y1) should be skipped", result.isEmpty())
    }

    @Test
    fun clipping_normalizedCoords() {
        // Boxes in normalized space [-0.1, -0.2, 1.3, 1.5] → maxCoord=1.5 ≤ 1.5 → scaleFactor=1.0
        // After coerceIn(0,1): [0, 0, 1, 1]
        val boxes = Array(8400) { FloatArray(4) }
        val scores = FloatArray(8400) { 0f }
        val classIdx = FloatArray(8400) { 0f }

        boxes[0] = floatArrayOf(-0.1f, -0.2f, 1.3f, 1.5f)
        scores[0] = 0.85f
        classIdx[0] = 7f

        val result = engine.parseQualcommDetections(boxes, scores, classIdx)
        assertEquals(1, result.size)
        val det = result[0]
        assertEquals(7, det.classIndex)
        assertEquals(0.85f, det.confidence, 0.001f)
        assertEquals(0f, det.boundingBox.left, 0.001f)
        assertEquals(0f, det.boundingBox.top, 0.001f)
        assertEquals(1f, det.boundingBox.right, 0.001f)
        assertEquals(1f, det.boundingBox.bottom, 0.001f)
    }
}
