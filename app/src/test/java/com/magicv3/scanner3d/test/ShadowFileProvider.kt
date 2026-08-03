package com.magicv3.scanner3d.test

import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Environment
import androidx.core.content.FileProvider
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.xmlpull.v1.XmlPullParser
import java.io.File

/**
 * Windows'ta androidx.core FileProvider kök eşleme hatasını gideren test-only shadow.
 *
 * Neden gerekli:
 *  androidx.core 1.13.0+'daki `SimplePathStrategy.belongsToRoot`, dosya yolunun
 *  kök altında olup olmadığını `filePath.startsWith(rootPath + '/')` ile kontrol eder.
 *  Windows'ta `File.getCanonicalPath()` `\` ayracı döndürdüğü için bu karşılaştırma
 *  hiçbir zaman eşleşmez ve `IllegalArgumentException: Failed to find configured
 *  root that contains ...` fırlar (yalnızca Robolectric/Windows ortamında).
 *
 * Bu shadow YALNIZCA test JVM'inde devreye girer (MnpExporterTest üzerinde
 * `@Config(shadows = [ShadowFileProvider::class])` ile etkinleştirilir):
 *  - Üretim bağımlılık sürümleri DEĞİŞMEZ (androidx.core 1.13.0'daki güvenlik
 *    düzeltmesi `belongsToRoot` korunur).
 *  - getUriForFile yalnızca test için platform-agnostik kök eşlemesiyle yeniden
 *    uygulanır; kökleri ve URI biçimini androidx.core'un davranışıyla aynı üretir.
 */
@Implements(FileProvider::class)
class ShadowFileProvider {

    companion object {
        private const val META_DATA_FILE_PROVIDER_PATHS = "android.support.FILE_PROVIDER_PATHS"

        @JvmStatic
        @Implementation
        fun getUriForFile(context: Context, authority: String, file: File): Uri {
            val pm = context.packageManager
            val info = pm.resolveContentProvider(authority, PackageManager.GET_META_DATA)
                ?: throw IllegalArgumentException(
                    "Couldn't find meta-data for provider with authority $authority"
                )
            val parser = info.loadXmlMetaData(pm, META_DATA_FILE_PROVIDER_PATHS)
                ?: throw IllegalArgumentException("Missing $META_DATA_FILE_PROVIDER_PATHS meta-data")

            // Tag → hedef dizin eşlemesi (androidx.core FileProvider ile aynı).
            val roots = LinkedHashMap<String, File>()
            var type = parser.eventType
            while (type != XmlPullParser.END_DOCUMENT) {
                if (type == XmlPullParser.START_TAG) {
                    val tag = parser.name
                    val name = parser.getAttributeValue(null, "name")
                    val path = parser.getAttributeValue(null, "path")
                    val target: File? = when (tag) {
                        "root-path" -> File(File.separator)
                        "files-path" -> context.filesDir
                        "cache-path" -> context.cacheDir
                        "external-path" -> runCatching { Environment.getExternalStorageDirectory() }.getOrNull()
                        "external-files-path" -> runCatching { context.getExternalFilesDir(null) }.getOrNull()
                        "external-cache-path" -> runCatching { context.externalCacheDir }.getOrNull()
                        "external-media-path" -> runCatching { context.getExternalMediaDirs()?.firstOrNull() }.getOrNull()
                        else -> null
                    }
                    if (target != null && name != null) {
                        val root = if (path != null) File(target, path) else target
                        roots[name] = root.canonicalFile
                    }
                }
                type = parser.next()
            }

            val filePath = file.canonicalPath

            // En özgül kökü bul (Windows-agnostik eşleme).
            var mostSpecific: Map.Entry<String, File>? = null
            for (entry in roots.entries) {
                val rootPath = entry.value.path
                if (isUnderRoot(filePath, rootPath) &&
                    (mostSpecific == null || rootPath.length > mostSpecific!!.value.path.length)
                ) {
                    mostSpecific = entry
                }
            }
            val selected = mostSpecific
                ?: throw IllegalArgumentException(
                    "Failed to find configured root that contains $filePath"
                )

            val rootPath = selected.value.path
            val relative = if (rootPath.endsWith("/") || rootPath.endsWith(File.separator)) {
                filePath.substring(rootPath.length)
            } else {
                filePath.substring(rootPath.length + 1)
            }
            val encodedPath = Uri.encode(selected.key) + '/' + Uri.encode(relative, "/")
            return Uri.Builder()
                .scheme("content")
                .authority(authority)
                .encodedPath(encodedPath)
                .build()
        }

        /**
         * `filePath` verilen kökün altında mı? Hem `/` hem `\` ayracını kabul eder.
         */
        private fun isUnderRoot(filePath: String, rootPath: String): Boolean {
            val fp = filePath.trimEnd('/').trimEnd('\\')
            val rp = rootPath.trimEnd('/').trimEnd('\\')
            return fp == rp || fp.startsWith("$rp/") || fp.startsWith("$rp\\")
        }
    }
}
