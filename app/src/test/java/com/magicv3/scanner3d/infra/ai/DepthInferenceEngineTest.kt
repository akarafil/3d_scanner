package com.magicv3.scanner3d.infra.ai

import android.content.Context
import android.graphics.Bitmap
import androidx.test.core.app.ApplicationProvider
import io.mockk.every
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.tensorflow.lite.Interpreter
import org.tensorflow.lite.Tensor

/**
 * DepthInferenceEngine birim testleri.
 *
 * Bölüm 1 — Dürüst davranış (model assets'te YOK → sahte/mock çıktı üretilmez):
 *  - isNpuOrGpuAccelerated = false
 *  - isModelLoaded = false
 *  - infer(bitmap) → boş FloatArray (sahte depth yok)
 *  - close() iki kez çağrılabilir
 *
 * Bölüm 2 — Batch-6 isim dürüstlüğü + imza doğrulama:
 *  - MODEL_NAME, Qualcomm'un sağladığı Small varyantıyla eşleşir
 *    (depth_anything_v2_small.tflite — ViT-Base değil).
 *  - isSignatureExpected: saf fonksiyon — [1,518,518,3] giriş / [1,518,518,1] çıkış
 *    imzalarına uyum/uyumsuzluk durumlarını Robolectric'te test eder.
 *  - validateModelSignature: mock Interpreter üzerinden okuma + karşılaştırma +
 *    tensor okuma hatasında crash'siz false dönüşü.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class DepthInferenceEngineTest {

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
    }

    // ── Bölüm 1: Model yokken dürüst davranış ──────────────────────────────

    @Test
    fun `modelAssetsYoksa_modelYukluDegilVeHizlandirmaYok`() {
        val engine = DepthInferenceEngine(context)

        assertFalse("GPU/NPU hızlandırma olmamalı", engine.isNpuOrGpuAccelerated)
        assertFalse("Model yüklü olmamalı (dürüst isModelLoaded)", engine.isModelLoaded)
    }

    @Test
    fun `infer_modelYokkenBosDiziDoner`() {
        val engine = DepthInferenceEngine(context)
        val bitmap = Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888)

        val depth = engine.infer(bitmap)

        assertTrue("Model yokken boş depth dönmeli (sahte depth üretilmez)", depth.isEmpty())
    }

    @Test
    fun `close_ikiKezCagrilabilir`() {
        val engine = DepthInferenceEngine(context)

        engine.close()
        engine.close() // hata fırlatmamalı
    }

    // ── Bölüm 2: Batch-6 isim dürüstlüğü ───────────────────────────────────

    @Test
    fun `modelAdiSmallVaryantiKullanir`() {
        // Qualcomm'un sağladığı model Depth-Anything-V2 **Small** varyantıdır.
        // Kod dürüstlüğü: sabit, assets'teki gerçek dosya adıyla birebir eşleşmeli.
        assertEquals(
            "depth_anything_v2_small.tflite",
            DepthInferenceEngine.MODEL_NAME
        )
    }

    @Test
    fun `beklenenImzaSabitleriSmallVaryantiIcinDogru`() {
        assertTrue(
            "Giriş imzası [1,518,518,3] olmalı",
            DepthInferenceEngine.EXPECTED_INPUT_SHAPE.contentEquals(intArrayOf(1, 518, 518, 3))
        )
        assertTrue(
            "Çıkış imzası [1,518,518,1] olmalı",
            DepthInferenceEngine.EXPECTED_OUTPUT_SHAPE.contentEquals(intArrayOf(1, 518, 518, 1))
        )
    }

    // ── Bölüm 2: isSignatureExpected (saf fonksiyon) ───────────────────────

    @Test
    fun `isSignatureExpected_uyumluImzadaTrueDoner`() {
        val engine = DepthInferenceEngine(context)

        val ok = engine.isSignatureExpected(
            intArrayOf(1, 518, 518, 3),
            intArrayOf(1, 518, 518, 1)
        )

        assertTrue("Birebir eşleşen imzalar kabul edilmeli", ok)
    }

    @Test
    fun `isSignatureExpected_girisBoyutuUyumsuzIseFalseDoner`() {
        val engine = DepthInferenceEngine(context)

        val ok = engine.isSignatureExpected(
            intArrayOf(1, 512, 512, 3), // Small girişi 518x518 — 512 yanlış
            intArrayOf(1, 518, 518, 1)
        )

        assertFalse("Giriş çözünürlüğü uymuyorsa reddedilmeli", ok)
    }

    @Test
    fun `isSignatureExpected_cikisBoyutuUyumsuzIseFalseDoner`() {
        val engine = DepthInferenceEngine(context)

        val ok = engine.isSignatureExpected(
            intArrayOf(1, 518, 518, 3),
            intArrayOf(1, 518, 518, 3) // çıkış 1 kanal depth olmalı, 3 değil
        )

        assertFalse("Çıkış kanal sayısı uymuyorsa reddedilmeli", ok)
    }

    @Test
    fun `isSignatureExpected_girisKanalSayisiUyumsuzIseFalseDoner`() {
        val engine = DepthInferenceEngine(context)

        val ok = engine.isSignatureExpected(
            intArrayOf(1, 518, 518, 1), // giriş RGB (3 kanal) olmalı
            intArrayOf(1, 518, 518, 1)
        )

        assertFalse("Giriş kanal sayısı uymuyorsa reddedilmeli", ok)
    }

    @Test
    fun `isSignatureExpected_batchBoyutuUyumsuzIseFalseDoner`() {
        val engine = DepthInferenceEngine(context)

        val ok = engine.isSignatureExpected(
            intArrayOf(2, 518, 518, 3), // batch 1 bekleniyor
            intArrayOf(1, 518, 518, 1)
        )

        assertFalse("Batch boyutu uymuyorsa reddedilmeli", ok)
    }

    @Test
    fun `isSignatureExpected_bosSekillerdeFalseDoner`() {
        val engine = DepthInferenceEngine(context)

        val ok = engine.isSignatureExpected(IntArray(0), IntArray(0))

        assertFalse("Boş şekiller uyumsuz sayılmalı", ok)
    }

    // ── Bölüm 2: validateModelSignature (mock Interpreter ile) ─────────────

    @Test
    fun `validateModelSignature_uyumluImzadaTrueDoner`() {
        val engine = DepthInferenceEngine(context)
        val interpreter = mockInterpreter(
            intArrayOf(1, 518, 518, 3),
            intArrayOf(1, 518, 518, 1)
        )

        val ok = engine.validateModelSignature(interpreter)

        assertTrue("Birebir uyumlu imzalar true döndürmeli", ok)
    }

    @Test
    fun `validateModelSignature_uyumsuzImzadaFalseDoner`() {
        val engine = DepthInferenceEngine(context)
        val interpreter = mockInterpreter(
            intArrayOf(1, 640, 640, 3), // yanlış: YOLOv8 giriş boyutu karışmış
            intArrayOf(1, 518, 518, 1)
        )

        val ok = engine.validateModelSignature(interpreter)

        assertFalse("Uyumsuz imzalar false döndürmeli (model düşürülmez, uyarı loglanır)", ok)
    }

    @Test
    fun `validateModelSignature_tensorOkunamazsaFalseDonerVeCrashEtmez`() {
        val engine = DepthInferenceEngine(context)
        val interpreter = mockk<Interpreter>()
        every { interpreter.getInputTensor(0) } throws RuntimeException("tensor read failed")

        val ok = engine.validateModelSignature(interpreter)

        assertFalse("Tensor okuma hatasında crash yerine false dönmeli", ok)
    }

    // ── Yardımcılar ────────────────────────────────────────────────────────

    private fun mockTensor(shape: IntArray): Tensor {
        val tensor = mockk<Tensor>()
        every { tensor.shape() } returns shape
        return tensor
    }

    private fun mockInterpreter(inputShape: IntArray, outputShape: IntArray): Interpreter {
        val interpreter = mockk<Interpreter>()
        every { interpreter.getInputTensor(0) } returns mockTensor(inputShape)
        every { interpreter.getOutputTensor(0) } returns mockTensor(outputShape)
        return interpreter
    }
}
