package com.magicv3.scanner3d.infra.ingestion

import android.content.Context
import android.util.Base64
import androidx.exifinterface.media.ExifInterface
import androidx.test.core.app.ApplicationProvider
import com.magicv3.scanner3d.domain.model.ScanFrame
import com.magicv3.scanner3d.domain.model.ScanSession
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.util.UUID

/**
 * ManifestGenerator birim testleri.
 *
 * Kapsam:
 *  - protocolVersion "1.0" ve kök yapı
 *  - frames dizisi + pose (Double array — API 28/29 uyumlu, JSONArray(FloatArray) değil)
 *  - device telemetry (make/model/brand/sdkVersion)
 *  - JPEG EXIF etiketi varsa manifest'e eklenir
 *  - EXIF okunamazsa (geçersiz dosya) çökmez
 *
 * ExifInterface dosyadan okuduğu için Robolectric + fixture JPEG kullanılır.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ManifestGeneratorTest {

    /** 1x1 geçerli minimal JPEG (fixture'ların temeli). */
    private val minimalJpegBase64 =
        "/9j/4AAQSkZJRgABAQEAYABgAAD/2wBDAAgGBgcGBQgHBwcJCQgKDBQNDAsLDBkSEw8UHRofHh0aHBwgJC4nICIsIxwcKDcpLDAxNDQ0Hyc5PTgyPC4zNDL/wAALCAABAAEBAREA/8QAFAABAAAAAAAAAAAAAAAAAAAACf/EABQQAQAAAAAAAAAAAAAAAAAAAAD/2gAIAQEAAD8AVN//2Q=="

    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
    }

    /** Minimal JPEG yazıp EXIF attribute'larını uygular. */
    private fun createJpegWithExif(path: File, applyAttributes: (ExifInterface) -> Unit) {
        path.writeBytes(Base64.decode(minimalJpegBase64, Base64.DEFAULT))
        val exif = ExifInterface(path.absolutePath)
        applyAttributes(exif)
        exif.saveAttributes()
    }

    private fun createSession(frames: List<ScanFrame>): ScanSession {
        val folder = tempFolder.newFolder("session")
        return ScanSession(
            sessionId = UUID.randomUUID(),
            projectName = "Manifest Projesi",
            createdAtMs = 1700000000000L,
            frames = frames,
            totalBytes = frames.sumOf { it.bytes },
            folder = folder,
        )
    }

    private fun frame(
        name: String,
        contents: ByteArray = byteArrayOf(0x01, 0x02),
        translation: FloatArray? = null,
        rotation: FloatArray? = null,
    ): ScanFrame {
        val folder = tempFolder.newFolder("frames-dir")
        val framesDir = File(folder, "frames").apply { mkdirs() }
        val file = File(framesDir, name).apply { writeBytes(contents) }
        return ScanFrame(
            file = file,
            lensId = "main",
            lensType = "WIDE",
            focalMm = 4.7f,
            bytes = file.length(),
            capturedAtMs = 1700000000100L,
            translation = translation,
            rotation = rotation,
        )
    }

    @Test
    fun `generateManifest_protokolVeKokYapi`() = runBlocking {
        val session = createSession(listOf(frame("a.jpg")))
        val manifest = ManifestGenerator(context).generateManifest(session)

        val root = JSONObject(manifest.readText())
        assertEquals("1.0", root.getString("protocolVersion"))
        assertEquals(session.sessionId.toString(), root.getString("sessionId"))
        assertEquals(session.projectName, root.getString("projectName"))
        assertEquals(1, root.getInt("frameCount"))
        assertEquals(session.totalBytes, root.getLong("totalBytes"))
        assertTrue(root.has("frames"))
    }

    @Test
    fun `generateManifest_poseDoubleArrayApi28Uyumlu`() = runBlocking {
        val session = createSession(
            listOf(
                frame(
                    "a.jpg",
                    translation = floatArrayOf(1f, 2f, 3f),
                    rotation = floatArrayOf(0f, 0f, 0f, 1f),
                )
            )
        )
        val manifest = ManifestGenerator(context).generateManifest(session)

        val frame0 = JSONObject(manifest.readText()).getJSONArray("frames").getJSONObject(0)
        val pose = frame0.getJSONObject("pose")

        // API 28/29 uyumlu Double array — getDouble başarıyla okunmalı
        val translation = pose.getJSONArray("translation")
        assertEquals(1.0, translation.getDouble(0), 0.0)
        assertEquals(2.0, translation.getDouble(1), 0.0)
        assertEquals(3.0, translation.getDouble(2), 0.0)

        val rotation = pose.getJSONArray("rotation_quaternion")
        assertEquals(0.0, rotation.getDouble(0), 0.0)
        assertEquals(1.0, rotation.getDouble(3), 0.0)
    }

    @Test
    fun `generateManifest_deviceTelemetryEklenir`() = runBlocking {
        val session = createSession(listOf(frame("a.jpg")))
        val manifest = ManifestGenerator(context).generateManifest(session)

        val device = JSONObject(manifest.readText()).getJSONObject("device")
        assertTrue("make mevcut", device.has("make"))
        assertTrue("model mevcut", device.has("model"))
        assertTrue("brand mevcut", device.has("brand"))
        assertTrue("sdkVersion mevcut", device.has("sdkVersion"))
    }

    @Test
    fun `generateManifest_exifEtiketiVarsaEklenir`() = runBlocking {
        val folder = tempFolder.newFolder("exif-dir")
        val framesDir = File(folder, "frames").apply { mkdirs() }
        val jpegFile = File(framesDir, "exif.jpg")
        createJpegWithExif(jpegFile) { exif ->
            exif.setAttribute(ExifInterface.TAG_FOCAL_LENGTH_IN_35MM_FILM, "26")
            exif.setAttribute(ExifInterface.TAG_ORIENTATION, "1")
            exif.setAttribute(ExifInterface.TAG_USER_COMMENT, "lens_id=ultra")
        }
        val session = createSession(
            listOf(
                ScanFrame(
                    file = jpegFile,
                    lensId = "aux",
                    lensType = "ULTRA_WIDE",
                    focalMm = 2.4f,
                    bytes = jpegFile.length(),
                    capturedAtMs = 1700000000100L,
                )
            )
        )

        val manifest = ManifestGenerator(context).generateManifest(session)

        val frame0 = JSONObject(manifest.readText()).getJSONArray("frames").getJSONObject(0)
        assertEquals(26, frame0.getInt("focalLength35mmEquiv"))
        // ExifInterface, JPEG'te ImageWidth/ImageLength'i EXIF etiketinden değil SOF
        // segmentindeki GERÇEK görsel boyutlarından okur → 1x1 fixture için 1 beklenir.
        assertEquals(1, frame0.getInt("width"))
        assertEquals(1, frame0.getInt("height"))
        assertEquals(1, frame0.getInt("orientation"))
        assertTrue("userComment içermeli", frame0.getString("userComment").contains("lens_id="))
    }

    @Test
    fun `generateManifest_exifOkunamazsaCokmez`() = runBlocking {
        // Geçersiz içerik (JPEG olmayan) → ExifInterface fırlatır → runCatching yakalar
        val session = createSession(listOf(frame("not_image.jpg", contents = "not a jpeg".toByteArray())))
        val manifest = ManifestGenerator(context).generateManifest(session)

        assertTrue("Manifest yine üretilmeli", manifest.exists())
        val frame0 = JSONObject(manifest.readText()).getJSONArray("frames").getJSONObject(0)
        assertFalse("EXIF alanı eklenmemeli", frame0.has("focalLength35mmEquiv"))
        assertFalse("EXIF alanı eklenmemeli", frame0.has("userComment"))
    }
}
