package com.magicv3.scanner3d.infra.storage

import android.content.Context
import android.net.Uri
import com.magicv3.scanner3d.domain.model.ScanSession
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Faz 4.2 — AlgorDroid tarafından üretilen 3D model dosyalarını (.glb)
 * ilgili ScanSession dizinine kopyalar ve varlığını denetler.
 */
class MeshRepository(private val context: Context) {

    private val rootDir: File = File(context.filesDir, "scan_projects").apply { mkdirs() }

    /**
     * AlgorDroid motorundan dönen FileProvider URI içeriğini
     * session dizinine mesh.glb adıyla kaydeder.
     */
    suspend fun importMesh(sessionId: String, uri: Uri): File? = withContext(Dispatchers.IO) {
        val sessionFolder = File(rootDir, "session_$sessionId")
        if (!sessionFolder.exists()) {
            android.util.Log.e("MeshRepository", "Session folder not found for $sessionId")
            return@withContext null
        }

        val targetFile = File(sessionFolder, "mesh.glb")
        runCatching {
            context.contentResolver.openInputStream(uri).use { input ->
                if (input == null) return@withContext null
                targetFile.outputStream().use { output ->
                    input.copyTo(output)
                }
            }
            android.util.Log.i("MeshRepository", "Successfully imported mesh to ${targetFile.absolutePath} (${targetFile.length()} B)")
            targetFile
        }.getOrNull()
    }

    /**
     * Session klasöründe mesh.glb dosyasının mevcut olup olmadığını denetler.
     */
    fun hasMesh(session: ScanSession): Boolean {
        val meshFile = File(session.folder, "mesh.glb")
        return meshFile.exists() && meshFile.length() > 0
    }

    /**
     * Mesh dosya referansını döndürür.
     */
    fun getMeshFile(session: ScanSession): File {
        return File(session.folder, "mesh.glb")
    }
}
