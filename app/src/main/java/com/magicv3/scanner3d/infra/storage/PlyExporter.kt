package com.magicv3.scanner3d.infra.storage

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import com.magicv3.scanner3d.domain.usecase.Point3D
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.PrintWriter

class PlyExporter(private val context: Context) {

    /**
     * Writes accumulated 3D points to an ASCII PLY file.
     *
     * H-1: Ağır ASCII PLY serileştirmesi [Dispatchers.IO] üzerinde çalışır
     * ([withContext] ile) — çağıran ViewModel/UI main thread'ini asla bloklamaz.
     * [PrintWriter] + [bufferedWriter] kullanıldığından nokta başına string
     * concatenation/allocation oluşmaz (FileWriter'a kıyasla).
     *
     * Suspend: bu metot yalnızca coroutine context'ten çağrılabilir.
     */
    suspend fun export(projectName: String, points: List<Point3D>): File =
        withContext(Dispatchers.IO) {
            val safeName = projectName.replace(Regex("[^A-Za-z0-9_-]"), "_")
            val exportDir = File(context.cacheDir, "shared_ply").apply { mkdirs() }
            val plyFile = File(exportDir, "${safeName}_model.ply")

            // Delete old PLY exports older than 24h
            exportDir.listFiles()?.forEach { old ->
                if (old.isFile && old.lastModified() < System.currentTimeMillis() - 24 * 3600 * 1000L) {
                    old.delete()
                }
            }

            // BufferedWriter üzerine kurulu PrintWriter — her satır yazımı tek println
            // çağrısıdır; use {} kapattığında tampon flus edilir.
            PrintWriter(plyFile.bufferedWriter()).use { writer ->
                writer.println("ply")
                writer.println("format ascii 1.0")
                writer.println("element vertex ${points.size}")
                writer.println("property float x")
                writer.println("property float y")
                writer.println("property float z")
                writer.println("property uchar red")
                writer.println("property uchar green")
                writer.println("property uchar blue")
                writer.println("end_header")

                for (p in points) {
                    writer.println("${p.x} ${p.y} ${p.z} ${p.r} ${p.g} ${p.b}")
                }
                writer.flush()
            }
            plyFile
        }

    /**
     * Launches sharesheet chooser for PLY model.
     */
    fun launchShareSheet(plyFile: File, projectName: String) {
        val uri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            plyFile
        )

        val shareIntent = Intent(Intent.ACTION_SEND).apply {
            type = "application/octet-stream"
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_TITLE, "${projectName} (3D PLY Model)")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        val chooser = Intent.createChooser(shareIntent, "3D PLY Modelini Paylaş").apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(chooser)
    }
}
