package com.magicv3.scanner3d.domain.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.UUID

/**
 * ScanSession.fromJson + derived alan testleri.
 *
 * Kapsam:
 *  - meta.json round-trip (sessionId, status, frames)
 *  - Eksik alan fallback'leri: status → DRAFT, projectName → "Tarama", totalBytes → frames toplamı
 *  - Bozuk JSON → null
 *  - Derived alanlar: frameCount, lensesUsed, sizeFormatted, dateFormatted
 *
 * Saf domain modeli — Robolectric gerektirmez.
 */
class ScanSessionTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private fun writeMeta(folder: File, content: String) {
        folder.mkdirs()
        File(folder, "meta.json").writeText(content)
    }

    private fun sampleFrameJson(file: String = "frame_0.jpg") = """
        {
          "file": "$file",
          "lensId": "main",
          "lensType": "WIDE",
          "focalMm": 4.7,
          "bytes": 2000,
          "capturedAtMs": 1700000000100,
          "translation": [1.0, 2.0, 3.0],
          "rotation": [0.0, 0.0, 0.0, 1.0]
        }
    """.trimIndent()

    @Test
    fun `metaJsonRoundTrip_tumAlanlarKorunur`() {
        val folder = tempFolder.newFolder("session-roundtrip")
        val sessionId = UUID.randomUUID()
        writeMeta(
            folder,
            """
            {
              "sessionId": "$sessionId",
              "projectName": "Proje A",
              "createdAtMs": 1700000000000,
              "totalBytes": 5000,
              "status": "COMPLETED",
              "frames": [
                ${sampleFrameJson()}
              ]
            }
            """.trimIndent()
        )

        val session = ScanSession.fromJson(folder)

        assertNotNull(session)
        session!!
        assertEquals(sessionId, session.sessionId)
        assertEquals("Proje A", session.projectName)
        assertEquals(1700000000000L, session.createdAtMs)
        assertEquals(5000L, session.totalBytes)
        assertEquals(ScanStatus.COMPLETED, session.status)
        assertEquals(1, session.frameCount)
        assertEquals("WIDE", session.frames[0].lensType)
        assertNotNull(session.frames[0].translation)
        assertNotNull(session.frames[0].rotation)
    }

    @Test
    fun `metaJsonStatusYoksa_draftVarsayilir`() {
        val folder = tempFolder.newFolder("session-nostatus")
        writeMeta(
            folder,
            """
            {
              "sessionId": "${UUID.randomUUID()}",
              "projectName": "Proje B",
              "createdAtMs": 1700000000000,
              "frames": []
            }
            """.trimIndent()
        )

        val session = ScanSession.fromJson(folder)

        assertNotNull(session)
        assertEquals(ScanStatus.DRAFT, session!!.status)
    }

    @Test
    fun `metaJsonBozuksa_nullDoner`() {
        val folder = tempFolder.newFolder("session-corrupt")
        writeMeta(folder, "{ bu bir json degil!!!")

        assertNull(ScanSession.fromJson(folder))
    }

    @Test
    fun `metaJsonProjectNameYoksa_taramaVarsayilir`() {
        val folder = tempFolder.newFolder("session-noname")
        writeMeta(
            folder,
            """
            {
              "sessionId": "${UUID.randomUUID()}",
              "createdAtMs": 1700000000000,
              "frames": []
            }
            """.trimIndent()
        )

        val session = ScanSession.fromJson(folder)

        assertNotNull(session)
        assertEquals("Tarama", session!!.projectName)
    }

    @Test
    fun `metaJsonTotalBytesYoksa_frameToplaminaFallbackYapilir`() {
        val folder = tempFolder.newFolder("session-nototal")
        writeMeta(
            folder,
            """
            {
              "sessionId": "${UUID.randomUUID()}",
              "projectName": "Proje C",
              "createdAtMs": 1700000000000,
              "frames": [
                ${sampleFrameJson("a.jpg")},
                ${sampleFrameJson("b.jpg")}
              ]
            }
            """.trimIndent()
        )

        val session = ScanSession.fromJson(folder)

        assertNotNull(session)
        // bytes 2000 + 2000 → fallback toplam 4000
        assertEquals(4000L, session!!.totalBytes)
    }

    @Test
    fun `derivedAlanlar_dogruHesaplanir`() {
        val folder = tempFolder.newFolder("session-derived")
        writeMeta(
            folder,
            """
            {
              "sessionId": "${UUID.randomUUID()}",
              "projectName": "Proje D",
              "createdAtMs": 1700000000000,
              "totalBytes": 3145728,
              "frames": [
                ${sampleFrameJson("a.jpg")},
                ${sampleFrameJson("b.jpg")}
              ]
            }
            """.trimIndent()
        )

        val session = ScanSession.fromJson(folder)

        assertNotNull(session)
        session!!
        assertEquals(2, session.frameCount)
        assertEquals(setOf("WIDE"), session.lensesUsed)
        // 3145728 byte ≈ 3.1 MB (US locale'de yazılmıştır)
        assertTrue(session.sizeFormatted.startsWith("3.1"))
        assertTrue(session.sizeFormatted.endsWith("MB"))
        // "02 Oca 13:33" gibi "dd MMM HH:mm" formatı
        assertTrue(Regex("^\\d{2} .* \\d{2}:\\d{2}$").matches(session.dateFormatted))
    }

    @Test
    fun `metaJsonYoksa_nullDoner`() {
        val folder = tempFolder.newFolder("session-nometa")

        assertNull(ScanSession.fromJson(folder))
    }
}
