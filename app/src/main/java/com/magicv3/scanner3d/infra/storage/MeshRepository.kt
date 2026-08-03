package com.magicv3.scanner3d.infra.storage

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import com.magicv3.scanner3d.domain.model.ScanSession
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.InputStream

/**
 * Faz 4.2 — AlgorDroid tarafından üretilen 3D model dosyalarını (.glb)
 * ilgili ScanSession dizinine kopyalar ve varlığını denetler.
 *
 * H-5 (Güvenlik): sahte/güvenilmeyen URI'lerin session dizinine kopyalanmasını
 * engellemek için:
 *  - sessionId UUID formatına zorlanır (path traversal).
 *  - URI `content://` şeması + güvenilir authority (motor/FileProvider) doğrulaması.
 *  - Hedef klasörün canonical path'i root altında olduğu doğrulanır (symlink koruması).
 *  - GLB magic header (`g l T F` + version 2) doğrulanır — rastgele dosyalar içeri alınmaz.
 *  - 512MB boyut limiti — aşan dosyalar hedefe yazılmaz ve silinir.
 *  - Yazılacak dosya adı sabittir (mesh.glb) — URI'den gelen dosya adı asla kullanılmaz.
 */
class MeshRepository(private val context: Context) {

    private val rootDir: File = File(context.filesDir, "scan_projects").apply { mkdirs() }

    companion object {
        private const val TAG = "MeshRepository"
        private const val MESH_FILE_NAME = "mesh.glb"
        private const val PACKAGE_ALGORDROID_ENGINE = "com.algordroid.engine"

        /** H-5d: GLB magic — ASCII "glTF" (0x67 0x6C 0x54 0x46). */
        private val GLB_MAGIC = byteArrayOf('g'.code.toByte(), 'l'.code.toByte(), 'T'.code.toByte(), 'F'.code.toByte())

        /** H-5d: Kabul edilen GLB versiyonu — spec v2.0. */
        private const val GLB_VERSION_2 = 2

        /** H-5d: 512MB boyut limiti (bayt). Aşan dosyalar reddedilir/silinir. */
        private const val MAX_MESH_SIZE_BYTES = 512L * 1024 * 1024

        /** UUID session ID formatı — sadece UUID kabul edilir. */
        private val SESSION_ID_REGEX = Regex(
            "[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}"
        )
    }

    /**
     * AlgorDroid motorundan dönen FileProvider URI içeriğini
     * session dizinine mesh.glb adıyla kaydeder.
     *
     * @return Kopyalama başarılıysa mesh dosyası, aksi halde null.
     */
    suspend fun importMesh(sessionId: String, uri: Uri): File? = withContext(Dispatchers.IO) {
        // H-5: sessionId format doğrulaması — path traversal vektörünü kapat.
        if (!SESSION_ID_REGEX.matches(sessionId)) {
            android.util.Log.e(TAG, "Rejected import: invalid session id: $sessionId")
            return@withContext null
        }

        // H-5: URI scheme/authority doğrulaması — yalnızca content:// + güvenilir provider.
        if (uri.scheme != ContentResolver.SCHEME_CONTENT || !isTrustedAuthority(uri.authority)) {
            // B3: tam URI'yi loglama — yalnızca scheme + authority (query/path sızıntısı yok).
            val uriSummary = "${uri.scheme ?: "?"}://${uri.authority ?: "?"}"
            android.util.Log.e(TAG, "Rejected import: untrusted mesh URI: $uriSummary")
            return@withContext null
        }

        val sessionFolder = File(rootDir, "session_$sessionId")

        // H-5: canonical path ile hedefin root altında olduğunu doğrula.
        val canonicalRoot = runCatching { rootDir.canonicalFile }.getOrNull()
            ?: return@withContext null
        val canonicalFolder = runCatching { sessionFolder.canonicalFile }.getOrNull()
            ?: return@withContext null
        if (!canonicalFolder.path.startsWith(canonicalRoot.path + File.separator)) {
            android.util.Log.e(TAG, "Rejected import: path traversal attempt: ${canonicalFolder.path}")
            return@withContext null
        }

        if (!sessionFolder.exists()) {
            android.util.Log.e(TAG, "Session folder not found for $sessionId")
            return@withContext null
        }

        // Sabit dosya adı: mesh.glb — URI'den gelen dosya adı kullanılmaz (arbitrary write koruması).
        val targetFile = File(sessionFolder, MESH_FILE_NAME)
        runCatching {
            context.contentResolver.openInputStream(uri).use { input ->
                if (input == null) return@withContext null

                // H-5d: GLB magic + version doğrulaması.
                if (!isValidGlb(input)) {
                    android.util.Log.e(TAG, "Rejected import: not a valid GLB v2 file.")
                    return@withContext null
                }

                var withinLimit = true
                targetFile.outputStream().use { output ->
                    withinLimit = copyWithLimit(input, output, MAX_MESH_SIZE_BYTES)
                }
                if (!withinLimit) {
                    // H-5d: limit aşımı — kısmi dosya hedefte bırakılmaz, silinir.
                    android.util.Log.e(TAG, "Rejected import: mesh exceeds size limit (${MAX_MESH_SIZE_BYTES} B). Deleting.")
                    targetFile.delete()
                    return@withContext null
                }
            }
            android.util.Log.i(TAG, "Successfully imported mesh to ${targetFile.absolutePath} (${targetFile.length()} B)")
            targetFile
        }.getOrNull()
    }

    /**
     * H-5d: GLB header doğrulaması — magic "glTF" + version 2.
     * 12 baytlık başlığın ilk 8 baytı okunur ve doğrulanır.
     */
    private fun isValidGlb(input: InputStream): Boolean {
        return try {
            val header = ByteArray(8)
            var read = 0
            while (read < header.size) {
                val n = input.read(header, read, header.size - read)
                if (n < 0) break
                read += n
            }
            if (read < 8) return false
            for (i in GLB_MAGIC.indices) {
                if (header[i] != GLB_MAGIC[i]) return false
            }
            // Version little-endian uint32 → byte[4..7]
            val version = (header[4].toInt() and 0xFF) or
                ((header[5].toInt() and 0xFF) shl 8) or
                ((header[6].toInt() and 0xFF) shl 16) or
                ((header[7].toInt() and 0xFF) shl 24)
            version == GLB_VERSION_2
        } catch (e: Exception) {
            android.util.Log.e(TAG, "GLB header read failed: ${e.message}")
            false
        }
    }

    /**
     * H-5d: Akışı boyut limitiyle kopyalar.
     *
     * @return true kopyalama limit dahilinde tamamlandı; false limit aşıldı (kısmi yazım olabilir).
     */
    private fun copyWithLimit(input: InputStream, output: java.io.OutputStream, limit: Long): Boolean {
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        var total = 0L
        while (true) {
            val read = input.read(buffer)
            if (read < 0) return true
            total += read
            if (total > limit) return false
            output.write(buffer, 0, read)
        }
    }

    /**
     * Session klasöründe mesh.glb dosyasının mevcut olup olmadığını denetler.
     */
    fun hasMesh(session: ScanSession): Boolean {
        val meshFile = File(session.folder, MESH_FILE_NAME)
        return meshFile.exists() && meshFile.length() > 0
    }

    /**
     * B13: sessionId bazında mesh.glb varlık denetimi.
     * IngestionQueue.markComplete idempotence kontrolü için kullanılır —
     * mesh zaten içeri aktarılmışsa yeniden import edilmez.
     */
    fun hasMeshForSession(sessionId: String): Boolean {
        val meshFile = getMeshFileForSession(sessionId)
        return meshFile.exists() && meshFile.length() > 0
    }

    /**
     * B13: sessionId bazında mesh dosya referansı döndürür.
     */
    fun getMeshFileForSession(sessionId: String): File {
        return File(File(rootDir, "session_$sessionId"), MESH_FILE_NAME)
    }

    /**
     * Mesh dosya referansını döndürür.
     */
    fun getMeshFile(session: ScanSession): File {
        return File(session.folder, MESH_FILE_NAME)
    }

    /**
     * H-5: Authority'nin güvenilir olduğunu doğrular (motor ya da kendi FileProvider).
     */
    private fun isTrustedAuthority(authority: String?): Boolean {
        if (authority == null) return false
        val engineAuthority = PACKAGE_ALGORDROID_ENGINE
        val selfAuthority = context.packageName
        return authority == engineAuthority ||
            authority.startsWith("$engineAuthority.") ||
            authority == selfAuthority ||
            authority.startsWith("$selfAuthority.")
    }
}
