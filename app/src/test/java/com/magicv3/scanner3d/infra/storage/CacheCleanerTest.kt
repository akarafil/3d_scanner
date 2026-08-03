package com.magicv3.scanner3d.infra.storage

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * CacheCleaner birim testleri.
 *
 * Kapsam:
 *  - 24 saatten eski geçici dosyalar silinir
 *  - Taze dosyalar korunur
 *  - Klasör yoksa hata fırlatılmaz
 *
 * Context + gerçek dosya sistemi kullandığı için Robolectric altında çalışır.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class CacheCleanerTest {

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
    }

    @Test
    fun `eskiDosyalar_silinir`() = runBlocking {
        val dir = File(context.cacheDir, "shared_mnp").apply { mkdirs() }
        val oldFile = File(dir, "old_export.mnp")
        oldFile.writeBytes(byteArrayOf(0x01, 0x02))
        // 25 saat önce → süre dolmuş
        assertTrue(oldFile.setLastModified(System.currentTimeMillis() - 25 * 60 * 60 * 1000L))

        CacheCleaner(context).cleanExpiredCache()

        assertFalse("Eski dosya silinmeli", oldFile.exists())
    }

    @Test
    fun `tazeDosyalar_kalir`() = runBlocking {
        val dir = File(context.cacheDir, "shared_zips").apply { mkdirs() }
        val freshFile = File(dir, "fresh_export.zip")
        freshFile.writeBytes(byteArrayOf(0x50, 0x4B))
        // 1 saat önce → taze
        assertTrue(freshFile.setLastModified(System.currentTimeMillis() - 60 * 60 * 1000L))

        CacheCleaner(context).cleanExpiredCache()

        assertTrue("Taze dosya kalmalı", freshFile.exists())
    }

    @Test
    fun `klasorYoksa_hataFirlatmaz`() = runBlocking {
        // Hiçbir cache klasörü oluşturulmadı
        CacheCleaner(context).cleanExpiredCache()

        assertTrue("Hata fırlatılmadan tamamlanmalı", true)
    }
}
