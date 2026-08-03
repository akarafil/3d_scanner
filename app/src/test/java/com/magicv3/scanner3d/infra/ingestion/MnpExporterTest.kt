package com.magicv3.scanner3d.infra.ingestion

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.magicv3.scanner3d.domain.model.ScanFrame
import com.magicv3.scanner3d.domain.model.ScanSession
import com.magicv3.scanner3d.test.ShadowFileProvider
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
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
import java.io.BufferedInputStream
import java.io.File
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream

/**
 * MnpExporter birim testleri.
 *
 * Kapsam:
 *  - isMnpFile: M3SP magic doğrulaması (doğru/kısa/yanlış/yok dosya)
 *  - exportMnp: M3SP header + ZIP yapısı (meta.json, manifest.json, frames/)
 *  - STORED method + CRC, progress callback, dosya adı sanitizasyonu
 *
 * Context + FileProvider gerektirdiği için Robolectric altında çalışır.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], shadows = [ShadowFileProvider::class])
class MnpExporterTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
    }

    // ── isMnpFile ────────────────────────────────────────────────────

    @Test
    fun `isMnpFile_dogruMagic_true`() {
        val file = tempFolder.newFile("valid.mnp")
        file.writeBytes(MnpExporter.MAGIC_HEADER + byteArrayOf(0x00, 0x01, 0x02))

        assertTrue(MnpExporter.isMnpFile(file))
    }

    @Test
    fun `isMnpFile_kisaDosya_false`() {
        val file = tempFolder.newFile("short.mnp")
        file.writeBytes(byteArrayOf(0x4D, 0x33, 0x53)) // 3 bayt

        assertFalse(MnpExporter.isMnpFile(file))
    }

    @Test
    fun `isMnpFile_yanlisMagic_false`() {
        val file = tempFolder.newFile("wrong.mnp")
        file.writeBytes(byteArrayOf(0x50, 0x4B, 0x03, 0x04)) // PK header değil M3SP

        assertFalse(MnpExporter.isMnpFile(file))
    }

    @Test
    fun `isMnpFile_yokDosya_false`() {
        assertFalse(MnpExporter.isMnpFile(File(tempFolder.root, "missing.mnp")))
    }

    // ── exportMnp ────────────────────────────────────────────────────

    private fun createSession(
        projectName: String = "Test Proje",
        frameContents: List<Pair<String, ByteArray>> = listOf(
            "frame_0.jpg" to byteArrayOf(0x10, 0x20, 0x30),
            "frame_1.jpg" to byteArrayOf(0x01, 0x02, 0x03, 0x04, 0x05),
        ),
    ): ScanSession {
        val folder = tempFolder.newFolder("session")
        File(folder, "meta.json").writeText("{\"name\":\"test\"}")
        File(folder, "manifest.json").writeText("{\"protocol\":\"1.0\"}")
        val framesDir = File(folder, "frames").apply { mkdirs() }

        val frames = frameContents.map { (name, bytes) ->
            val file = File(framesDir, name).apply { writeBytes(bytes) }
            ScanFrame(
                file = file,
                lensId = "main_$name",
                lensType = "WIDE",
                focalMm = 4.7f,
                bytes = bytes.size.toLong(),
                capturedAtMs = 1700000000000L,
                translation = null,
                rotation = null,
            )
        }
        return ScanSession(
            sessionId = UUID.randomUUID(),
            projectName = projectName,
            createdAtMs = 1700000000000L,
            frames = frames,
            totalBytes = frames.sumOf { it.bytes },
            folder = folder,
        )
    }

    private fun readZipEntries(mnpFile: File): List<ZipEntry> {
        val entries = mutableListOf<ZipEntry>()
        ZipInputStream(BufferedInputStream(mnpFile.inputStream().also { it.skip(4) })).use { zis ->
            var entry = zis.nextEntry
            while (entry != null) {
                entries.add(entry)
                entry = zis.nextEntry
            }
        }
        return entries
    }

    @Test
    fun `exportMnp_m3spHeaderVeZipYapisi`() = runBlocking {
        val exporter = MnpExporter(context)
        val session = createSession()

        val result = exporter.exportMnp(session)

        assertTrue("MNP dosyası oluşmalı", result.file.exists())

        // M3SP Magic header
        val header = result.file.inputStream().use { it.readNBytes(4) }
        assertArrayEquals(MnpExporter.MAGIC_HEADER, header)

        // ZIP içeriği
        val names = readZipEntries(result.file).map { it.name }
        assertTrue("meta.json bulunmalı", names.contains("meta.json"))
        assertTrue("manifest.json bulunmalı", names.contains("manifest.json"))
        assertTrue("frames/frame_0.jpg bulunmalı", names.contains("frames/frame_0.jpg"))
        assertTrue("frames/frame_1.jpg bulunmalı", names.contains("frames/frame_1.jpg"))
        assertEquals(4, names.size)
    }

    @Test
    fun `exportMnp_frameEntryStoredCrcIleYazilir`() = runBlocking {
        val exporter = MnpExporter(context)
        val frameBytes = byteArrayOf(0x01, 0x02, 0x03, 0x04, 0x05)
        val session = createSession(frameContents = listOf("frame_1.jpg" to frameBytes))

        val result = exporter.exportMnp(session)

        val frameEntry = readZipEntries(result.file).first { it.name == "frames/frame_1.jpg" }
        assertEquals("STORED method kullanılmalı", ZipEntry.STORED, frameEntry.method)
        val expectedCrc = java.util.zip.CRC32().apply { update(frameBytes) }.value
        assertEquals("CRC gerçek CRC32 checksum olmalı", expectedCrc, frameEntry.crc)
        assertEquals("Boyut korunmalı", frameBytes.size.toLong(), frameEntry.size)
    }

    @Test
    fun `exportMnp_progressCallbackHerFrameIcinCagrilir`() = runBlocking {
        val exporter = MnpExporter(context)
        val session = createSession()
        val progress = mutableListOf<Pair<Int, Int>>()

        exporter.exportMnp(session) { current, total -> progress.add(current to total) }

        assertEquals(listOf(1 to 2, 2 to 2), progress)
    }

    @Test
    fun `exportMnp_dosyaAdiSanitizeEdilir`() = runBlocking {
        val exporter = MnpExporter(context)
        val session = createSession(projectName = "My Scan/Proje*")

        val result = exporter.exportMnp(session)

        // Geçersiz karakterler "_" ile değiştirilir: "My_Scan_Proje_"
        assertTrue("Sanitize adla başlamalı: ${result.file.name}", result.file.name.startsWith("My_Scan_Proje_"))
        assertTrue("Uzantı .mnp olmalı", result.file.name.endsWith(".mnp"))
        assertFalse("Ad geçersiz karakter içermemeli", result.file.name.any { it == '/' || it == '*' })
    }
}
