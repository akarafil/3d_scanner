package com.magicv3.scanner3d.infra.ingestion

import androidx.exifinterface.media.ExifInterface
import com.magicv3.scanner3d.domain.model.ScanFrame
import java.io.File

/**
 * Faz 3.0 - AlgorDroid engine teslimatı öncesi JPEG EXIF mühür doğrulayıcısı.
 */
class ExifValidator {

    data class ValidationIssue(
        val file: File,
        val field: String,
        val message: String
    )

    data class ValidationResult(
        val isValid: Boolean,
        val validatedFramesCount: Int,
        val issues: List<ValidationIssue>
    )

    /**
     * Bir projedeki tüm karelerin EXIF alanlarını doğrular.
     */
    fun validateFrames(frames: List<ScanFrame>): ValidationResult {
        val issues = mutableListOf<ValidationIssue>()

        for (frame in frames) {
            if (!frame.file.exists() || frame.file.length() == 0L) {
                issues.add(ValidationIssue(frame.file, "FILE_EXISTS", "Dosya bulunamadı veya 0 byte."))
                continue
            }

            runCatching {
                val exif = ExifInterface(frame.file.absolutePath)

                // 1. Focal Length kontrolü (SfM kamera kalibrasyonu için şart)
                val focalLength = exif.getAttributeDouble(ExifInterface.TAG_FOCAL_LENGTH, 0.0)
                if (focalLength <= 0.0) {
                    issues.add(ValidationIssue(frame.file, "TAG_FOCAL_LENGTH", "Focal length 0 veya okunamadı."))
                }

                // 2. Make & Model (Sensor database matching için şart)
                val make = exif.getAttribute(ExifInterface.TAG_MAKE)
                val model = exif.getAttribute(ExifInterface.TAG_MODEL)
                if (make.isNullOrBlank()) {
                    issues.add(ValidationIssue(frame.file, "TAG_MAKE", "Cihaz üretici bilgisi eksik."))
                }
                if (model.isNullOrBlank()) {
                    issues.add(ValidationIssue(frame.file, "TAG_MODEL", "Cihaz model bilgisi eksik."))
                }

                // 3. UserComment (Aux metadata izlenebilirliği için şart)
                val userComment = exif.getAttribute(ExifInterface.TAG_USER_COMMENT)
                if (userComment.isNullOrBlank() || !userComment.contains("lens_id=")) {
                    issues.add(ValidationIssue(frame.file, "TAG_USER_COMMENT", "Aux lens izleme etiketi eksik."))
                }

                // 4. Orientation
                val orientation = exif.getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_UNDEFINED)
                if (orientation == ExifInterface.ORIENTATION_UNDEFINED) {
                    issues.add(ValidationIssue(frame.file, "TAG_ORIENTATION", "Görsel yönelim açısı tanımsız."))
                }
            }.onFailure { e ->
                issues.add(ValidationIssue(frame.file, "EXIF_PARSE_ERROR", e.message ?: "EXIF okunamadı."))
            }
        }

        return ValidationResult(
            isValid = issues.isEmpty(),
            validatedFramesCount = frames.size - issues.size,
            issues = issues
        )
    }
}
