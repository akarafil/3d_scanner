package com.magicv3.scanner3d.infra.storage

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import com.magicv3.scanner3d.domain.usecase.Point3D
import java.io.File
import java.io.FileWriter

class PlyExporter(private val context: Context) {

    /**
     * Writes accumulated 3D points to an ASCII PLY file.
     */
    fun export(projectName: String, points: List<Point3D>): File {
        val safeName = projectName.replace(Regex("[^A-Za-z0-9_-]"), "_")
        val exportDir = File(context.cacheDir, "shared_ply").apply { mkdirs() }
        val plyFile = File(exportDir, "${safeName}_model.ply")

        // Delete old PLY exports older than 24h
        exportDir.listFiles()?.forEach { old ->
            if (old.isFile && old.lastModified() < System.currentTimeMillis() - 24 * 3600 * 1000L) {
                old.delete()
            }
        }

        FileWriter(plyFile).use { writer ->
            writer.write("ply\n")
            writer.write("format ascii 1.0\n")
            writer.write("element vertex ${points.size}\n")
            writer.write("property float x\n")
            writer.write("property float y\n")
            writer.write("property float z\n")
            writer.write("property uchar red\n")
            writer.write("property uchar green\n")
            writer.write("property uchar blue\n")
            writer.write("end_header\n")

            for (p in points) {
                writer.write("${p.x} ${p.y} ${p.z} ${p.r} ${p.g} ${p.b}\n")
            }
        }
        return plyFile
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
