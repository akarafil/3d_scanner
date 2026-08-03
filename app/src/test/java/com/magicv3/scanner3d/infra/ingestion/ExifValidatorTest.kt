package com.magicv3.scanner3d.infra.ingestion

import android.util.Base64
import androidx.exifinterface.media.ExifInterface
import com.magicv3.scanner3d.domain.model.ScanFrame
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * ExifValidator birim testleri.
 *
 * Kapsam:
 *  - Tam mühürlü JPEG → geçerli
 *  - Focal length eksik / MAKE-MODEL boş / userComment'te lens_id yok → issue
 *  - Dosya yok → FILE_EXISTS issue
 *  - validatedFramesCount hesabı
 *
 * Fixture JPEG'ler test içinde ExifInterface ile üretilir.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ExifValidatorTest {

    private val minimalJpegBase64 =
        "/9j/4AAQSkZJRgABAQEAYABgAAD/2wBDAAgGBgcGBQgHBwcJCQgKDBQNDAsLDBkSEw8UHRofHh0aHBwgJC4nICIsIxwcKDcpLDAxNDQ0Hyc5PTgyPC4zNDL/wAALCAABAAEBAREA/8QAFAABAAAAAAAAAAAAAAAAAAAACf/EABQQAQAAAAAAAAAAAAAAAAAAAAD/2gAIAQEAAD8AVN//2Q=="

    @get:Rule
    val tempFolder = TemporaryFolder()

    private val validator = ExifValidator()

    private fun createJpeg(applyAttributes: (ExifInterface) -> Unit): File {
        val file = tempFolder.newFile("frame_${System.nanoTime()}.jpg")
        file.writeBytes(Base64.decode(minimalJpegBase64, Base64.DEFAULT))
        val exif = ExifInterface(file.absolutePath)
        applyAttributes(exif)
        exif.saveAttributes()
        return file
    }

    private fun scanFrame(file: File): ScanFrame = ScanFrame(
        file = file,
        lensId = "main",
        lensType = "WIDE",
        focalMm = 4.7f,
        bytes = file.length(),
        capturedAtMs = 1700000000000L,
    )

    private fun sealFully(exif: ExifInterface) {
        exif.setAttribute(ExifInterface.TAG_MAKE, "HONOR")
        exif.setAttribute(ExifInterface.TAG_MODEL, "Magic V3")
        exif.setAttribute(ExifInterface.TAG_FOCAL_LENGTH, "47/10")
        exif.setAttribute(ExifInterface.TAG_USER_COMMENT, "lens_id=ultra;focal=4.7mm")
        exif.setAttribute(ExifInterface.TAG_ORIENTATION, "1")
    }

    @Test
    fun `tamMuhrluJpeg_gecerli`() {
        val file = createJpeg { sealFully(it) }

        val result = validator.validateFrames(listOf(scanFrame(file)))

        assertTrue("Tam mühür geçerli olmalı", result.isValid)
        assertEquals(1, result.validatedFramesCount)
        assertEquals(0, result.issues.size)
    }

    @Test
    fun `focalLengthYok_issueUretir`() {
        val file = createJpeg { exif ->
            exif.setAttribute(ExifInterface.TAG_MAKE, "HONOR")
            exif.setAttribute(ExifInterface.TAG_MODEL, "Magic V3")
            exif.setAttribute(ExifInterface.TAG_USER_COMMENT, "lens_id=ultra")
            exif.setAttribute(ExifInterface.TAG_ORIENTATION, "1")
            // TAG_FOCAL_LENGTH kasıtlı bırakılmadı
        }

        val result = validator.validateFrames(listOf(scanFrame(file)))

        assertFalse(result.isValid)
        assertTrue(result.issues.any { it.field == "TAG_FOCAL_LENGTH" })
    }

    @Test
    fun `makeVeModelBos_issueUretir`() {
        val file = createJpeg { exif ->
            exif.setAttribute(ExifInterface.TAG_MAKE, "")
            exif.setAttribute(ExifInterface.TAG_MODEL, "")
            exif.setAttribute(ExifInterface.TAG_FOCAL_LENGTH, "47/10")
            exif.setAttribute(ExifInterface.TAG_USER_COMMENT, "lens_id=ultra")
            exif.setAttribute(ExifInterface.TAG_ORIENTATION, "1")
        }

        val result = validator.validateFrames(listOf(scanFrame(file)))

        assertFalse(result.isValid)
        assertTrue(result.issues.any { it.field == "TAG_MAKE" })
        assertTrue(result.issues.any { it.field == "TAG_MODEL" })
    }

    @Test
    fun `userCommentLensIdYok_issueUretir`() {
        val file = createJpeg { exif ->
            exif.setAttribute(ExifInterface.TAG_MAKE, "HONOR")
            exif.setAttribute(ExifInterface.TAG_MODEL, "Magic V3")
            exif.setAttribute(ExifInterface.TAG_FOCAL_LENGTH, "47/10")
            exif.setAttribute(ExifInterface.TAG_USER_COMMENT, "no-lens-tag-here")
            exif.setAttribute(ExifInterface.TAG_ORIENTATION, "1")
        }

        val result = validator.validateFrames(listOf(scanFrame(file)))

        assertFalse(result.isValid)
        assertTrue(result.issues.any { it.field == "TAG_USER_COMMENT" })
    }

    @Test
    fun `dosyaYok_fileExistsIssueUretir`() {
        val missing = File(tempFolder.root, "missing.jpg")

        val result = validator.validateFrames(listOf(scanFrame(missing)))

        assertFalse(result.isValid)
        assertEquals("FILE_EXISTS", result.issues.single().field)
    }

    @Test
    fun `validatedFramesCount_dogruHesaplanir`() {
        val validFile = createJpeg { sealFully(it) }
        val missing = File(tempFolder.root, "missing2.jpg")

        val result = validator.validateFrames(listOf(scanFrame(validFile), scanFrame(missing)))

        // 2 kare, 1 issue → frames.size - issues.size = 1
        assertEquals(1, result.issues.size)
        assertEquals(1, result.validatedFramesCount)
        assertFalse(result.isValid)
    }
}
