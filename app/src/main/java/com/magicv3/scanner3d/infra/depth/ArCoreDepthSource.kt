package com.magicv3.scanner3d.infra.depth

import android.media.Image
import android.util.Log
import com.google.ar.core.Frame
import com.google.ar.core.exceptions.NotYetAvailableException
import com.google.ar.core.exceptions.UnavailableException
import com.magicv3.scanner3d.domain.depth.CameraIntrinsics
import com.magicv3.scanner3d.domain.depth.DepthMap
import com.magicv3.scanner3d.domain.depth.DepthSource
import java.nio.ByteOrder

/**
 * Faz 4 / Strateji C — ARCore Depth API tabanlı metrik depth kaynağı.
 *
 * `Frame.acquireDepthImage16Bits()` (DEPTH16) 16-bit **milimetre** cinsinden
 * depth üretir; bu kaynak değeri `÷ 1000` yaparak metreye çevirir ve
 * [DepthMap.isMetric] = true olarak döndürür. Metrik olduğu için
 * [DepthMap.metersPerUnit] her zaman `1f`'dir.
 *
 * Depth görüntüsü hazır değilse (ARCore henüz tahmin üretmedi ya da cihaz bu
 * karede desteklemiyor) [NotYetAvailableException]/[UnavailableException] fırlar;
 * bu kaynak istisnaları yakalayıp `null` döner — çağıran fallback kaynağa geçer.
 *
 * F2 güvencesi: Honor Magic V3 gibi cihazlarda ARCore native motion-stereo depth
 * boru hattı `spherical_rectifier.cc` RET_CHECK hatasıyla (`kUnrectifiedPinhole` vs
 * `kUnrectifiedOriginal`) güvenilir depth üretemez. [acquireDepth] bu durumların
 * tamamını (istisna, bozuk görüntü, null) `null`'a çevirir; fırlatmaz. Böylece
 * TFLite fallback ([TfliteDepthSource]) bu cihazda tek güvenilir metrik depth
 * kaynağı olarak garanti altındadır.
 *
 * Not: Depth verisi ARCore tarafından poz ile hizalı ve metrik olduğundan
 * (3D geri yansıtma için ideal), Strateji C'de ana kameranın birincil kaynağıdır.
 */
class ArCoreDepthSource : DepthSource {

    override fun acquireDepth(frame: Frame): DepthMap? {
        // F2 güvencesi (Honor Magic V3 cihaz logu): ARCore motion-stereo depth boru
        // hattı bazı cihazlarda native `spherical_rectifier.cc` RET_CHECK hatasıyla
        // (`kUnrectifiedPinhole` vs `kUnrectifiedOriginal`) güvenilir depth üretemez.
        // `acquireDepthImage16Bits` NotYetAvailable fırlatabilir, native hata bozuk
        // görüntü döndürebilir ya da (savunmacı) null dönebilir. Bu kaynak HİÇBİR
        // durumda fırlatmaz: her yol null'a çevrilir ve üst katman
        // (ScanViewModel.onFrameAvailable) TfliteDepthSource'a düşer — TFLite
        // bu cihazda tek güvenilir metrik depth kaynağıdır.
        return try {
            val depthImage = frame.acquireDepthImage16Bits()
            try {
                produceDepthMap(frame, depthImage)
            } finally {
                // ARCore Image her zaman release edilmelidir (buffer sızıntısı olmasın).
                runCatching { depthImage.close() }
            }
        } catch (e: NotYetAvailableException) {
            // Depth henüz tahmin edilmedi — bu karede atlanır (TFLite fallback).
            Log.w(TAG, "ARCore depth henüz hazır değil: ${e.message}")
            null
        } catch (e: UnavailableException) {
            // Cihaz/bu oturum depth üretmiyor — bu karede atlanır (TFLite fallback).
            Log.w(TAG, "ARCore depth kullanılamıyor: ${e.message}")
            null
        } catch (e: Exception) {
            // Beklenmedik hata (F2: RET_CHECK dahil) — asla crash etme, null dön.
            Log.w(TAG, "ARCore depth alınamadı: ${e.message}")
            null
        }
    }

    /**
     * DEPTH16 görüntüsünü metrik [DepthMap]'e çevirir; anlamlı depth yoksa `null` döner.
     *
     * DEPTH16 spesifikasyonunda `0` değeri **"bilinmeyen / veri yok"** anlamına gelir.
     * ARCore bazen depth üretemediği halde non-null **boş** görüntü döndürebilir (F2 —
     * Honor Magic V3 `spherical_rectifier.cc` RET_CHECK senaryosu). Böyle bir görüntüden
     * [DepthMap] üretmek üst katmanın `AR_CORE` durumunu yanlış raporlamasına ve
     * [DepthToPointsUseCase]'in tüm noktaları filtrelemesine yol açar (MEDIUM-3).
     *
     * Veri doğrulaması iki aşamada yapılır:
     *  1. `availableShorts == 0` → buffer'da okunacak hiçbir 16-bit değer yok → `null`.
     *  2. Okunan tüm değerler `0` ise → ARCore depth üretemedi (boş/bozuk görüntü) → `null`.
     *
     * İki durumda da [acquireDepth] `null` döndürür ve üst katman TFLite fallback'e düşer.
     * Dönüşüm içindeki diğer beklenmedik durumlar (plane erişimi, buffer okuma, intrinsic
     * okuma vb.) yine [acquireDepth] tarafından yakalanıp null'a çevrilir — kaynak hiçbir
     * yoldan fırlatmaz, böylece TFLite fallback garanti altındadır.
     *
     * @param frame intrinsiklerin okunduğu ARCore karesi.
     * @param depthImage `acquireDepthImage16Bits()` sonucu DEPTH16 görüntüsü.
     * @return Anlamlı depth içeren [DepthMap] ya da veri yoksa `null`.
     */
    private fun produceDepthMap(frame: Frame, depthImage: Image): DepthMap? {
        // DEPTH16: tek plane, 16-bit little-endian milimetre değerleri.
        val plane = depthImage.planes[0]
        val buffer = plane.buffer
        val rowStride = plane.rowStride
        val pixelStride = plane.pixelStride
        val width = depthImage.width
        val height = depthImage.height
        val totalShorts = width * height

        buffer.order(ByteOrder.LITTLE_ENDIAN)
        val mm = ShortArray(totalShorts)

        val packed = rowStride == width * pixelStride
        val start = buffer.position()
        val availableShorts = ((buffer.limit() - start) / 2).coerceAtMost(totalShorts)

        // MEDIUM-3: DEPTH16'da 0 "veri yok"tur. Buffer'da okunacak hiçbir 16-bit değer
        // yoksa (boş buffer) anlamlı depth üretilemez — null dön (TFLite fallback).
        if (availableShorts == 0) {
            Log.w(TAG, "DEPTH16 buffer boş (availableShorts=0) — depth üretilemedi, null dönülüyor")
            return null
        }

        if (packed) {
            // Sıkı paketlenmiş → tek seferde kopyala (hızlı yol).
            val shortBuffer = buffer.asShortBuffer()
            shortBuffer.get(mm, 0, shortBuffer.remaining().coerceAtMost(totalShorts))
        } else {
            // Row padding varsa satır bazında oku; doldurulmayan hücreler 0
            // kalır ve [DepthToPointsUseCase] metrik filtresi tarafından elenir.
            var count = 0
            outer@ for (y in 0 until height) {
                for (x in 0 until width) {
                    if (count >= availableShorts) break@outer
                    val pos = start + y * rowStride + x * pixelStride
                    if (pos + 1 >= buffer.limit()) break@outer
                    mm[count] = buffer.getShort(pos)
                    count++
                }
            }
        }

        // MEDIUM-3: ARCore bazen depth üretemediği halde non-null boş görüntü döndürür
        // (F2 — Honor Magic V3 RET_CHECK senaryosu). Okunan tüm değerler 0 ise
        // "bilinmeyen/veri yok"tur — anlamlı depth yok, null dön (TFLite fallback).
        if (hasNoValidDepth(mm)) {
            Log.w(TAG, "DEPTH16 tamamı sıfır (${totalShorts} hücre) — ARCore depth üretemedi, null dönülüyor")
            return null
        }

        val meters = mmToMeters(mm)
        val intrinsics = readImageIntrinsics(frame, width, height)

        return DepthMap(
            depths = meters,
            width = width,
            height = height,
            isMetric = true,
            metersPerUnit = 1f,
            intrinsics = intrinsics,
            sourceName = SOURCE_NAME_ARCORE,
            // İntrinsikler depth grid'ine ölçeklenmiş döner; kaynak uzayı depth
            // çözünürlüğü olduğundan usecase içindeki ikinci ölçekleme no-op olur.
            sourceWidth = width,
            sourceHeight = height,
        )
    }

    /**
     * ARCore `CameraIntrinsics` → [CameraIntrinsics] eşlemesi.
     *
     * ARCore imageIntrinsics RGB kamera piksel uzayında (örn. 640x480) ifade edilir;
     * depth haritası ise daha düşük çözünürlüktedir (örn. 160x120). İntrinsikler
     * burada depthImage çözünürlüğüne ölçeklenir ve [CameraIntrinsics.sourceWidth]/
     * [CameraIntrinsics.sourceHeight] depth çözünürlüğüne eşit set edilir — böylece
     * DepthToPointsUseCase içindeki ölçekleme doğru oranı bulur (ve bu değerler
     * zaten depth grid'inde olduğundan oran 1 olur). Okunamazsa null döner; çağıran
     * fallback intrinsik kullanır.
     */
    private fun readImageIntrinsics(frame: Frame, depthWidth: Int, depthHeight: Int): CameraIntrinsics? =
        runCatching {
            val ii = frame.camera.imageIntrinsics
            // ARCore 1.41'de boyutlar getImageDimensions() ile döner (int[2]: width, height).
            val dims = ii.imageDimensions
            // imageIntrinsics'in ifade edildiği RGB kamera çözünürlüğü.
            val srcW = dims.getOrNull(0)?.takeIf { it > 0 } ?: 0
            val srcH = dims.getOrNull(1)?.takeIf { it > 0 } ?: 0
            val scaleX = if (srcW > 0) depthWidth.toFloat() / srcW.toFloat() else 1f
            val scaleY = if (srcH > 0) depthHeight.toFloat() / srcH.toFloat() else 1f
            CameraIntrinsics(
                fx = ii.focalLength[0] * scaleX,
                fy = ii.focalLength[1] * scaleY,
                cx = ii.principalPoint[0] * scaleX,
                cy = ii.principalPoint[1] * scaleY,
                // İntrinsikler depth grid'ine normalize edilir — fx_scaled = fx * depthW / sourceW.
                sourceWidth = depthWidth,
                sourceHeight = depthHeight,
            )
        }.onFailure { e ->
            Log.w(TAG, "imageIntrinsics okunamadı: ${e.message}")
        }.getOrNull()

    /**
     * 16-bit (DEPTH16) milimetre değerlerini metreye çevirir.
     *
     * Testability: birim testler saf fonksiyonu doğrudan çağırabilsin diye `internal`.
     */
    internal fun mmToMeters(mm: ShortArray): FloatArray {
        val meters = FloatArray(mm.size)
        for (i in mm.indices) {
            meters[i] = mm[i] / MILLIMETERS_PER_METER
        }
        return meters
    }

    /**
     * DEPTH16 değerlerinde anlamlı depth var mı?
     *
     * DEPTH16 spesifikasyonuna göre `0` "bilinmeyen / veri yok" anlamına gelir. Tüm
     * değerler 0 ise ARCore depth üretememiş demektir (non-null ama boş/bozuk görüntü) —
     * böyle bir haritadan [DepthMap] üretmek `AR_CORE` durumunu dürüstçe raporlamaz
     * (MEDIUM-3). En az bir sıfırdan farklı değer varsa anlamlı veri kabul edilir.
     *
     * Testability: saf fonksiyon, birim testler doğrudan çağırabilsin diye `internal`.
     *
     * @param mm DEPTH16'dan okunan 16-bit milimetre değerleri.
     * @return En az bir sıfırdan farklı değer varsa `false`, tümü 0 ise `true`.
     */
    internal fun hasNoValidDepth(mm: ShortArray): Boolean {
        for (value in mm) {
            if (value != 0.toShort()) return false
        }
        return true
    }

    companion object {
        private const val TAG = "ArCoreDepthSource"

        /** 1 metre = 1000 milimetre. */
        private const val MILLIMETERS_PER_METER = 1000f

        /** DepthMap.sourceName — teşhis/stat için. */
        const val SOURCE_NAME_ARCORE = "arcore"
    }
}
