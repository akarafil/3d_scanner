# Magic3D Scanner — Teknik Dokümantasyon

Bu doküman, **Honor Magic V3** (Snapdragon 8 Gen 3) donanımı üzerinde çalışan yüksek performanslı on-device (cihaz üstü) 3B Tarayıcı uygulamasının mimarisini, veri işleme boru hattını ve algoritmalarını açıklamaktadır.

---

## 1. Sistem Mimarisi Genel Bakış

Uygulama iki temel katmandan oluşur:
1. **Kotlin / Jetpack Compose HUD**: Kullanıcı arayüzünü yönetir, ARCore oturum kontrolünü sağlar ve OpenGL kamera önizlemesini render eder.
2. **C++ NDK Native Motoru (`magic3d_engine`)**: OpenCV, OpenMP ve SIMD NEON yönergelerini kullanarak gerçek zamanlı derinlik füzyonu, gürültü temizleme (NPU) ve 3B mesh rekonstrüksiyonu yapar.

```
[ARCore Kamera & Derinlik] ──┐
                             ├──→ [JNI Köprüsü] ──→ [NPU Denoise & Fusion Pipeline] ──→ [TSDF & Surface Nets] ──→ [.PLY Çıktısı]
[Sensör Verileri (VIO)] ─────┘
```

---

## 2. Derinlik Algılama & Adaptif Füzyon Boru Hattı

Uygulama, ARCore'un ürettiği derinlik haritalarını ve stereo derinlik tahminlerini birleştirerek yüksek hassasiyetli bir derinlik verisi üretir:

- **Çözünürlük ve Hizalama**: Ham ARCore derinlik tamponu (16-bit) Little Endian formatında okunur.
- **Kamera Görüntü Oranı Düzeltmesi (CENTER_CROP)**: Ekran ile kamera en-boy oranlarının uyumsuzluğunu gidermek için `CameraRenderer` içinde texture koordinatları dinamik olarak kırpılır.

---

## 3. NPU Parazit Temizleme Motoru (`NpuDenoiseEngine`)

Snapdragon 8 Gen 3 Hexagon Tensor Processor (HTP) vektör birimi (HVX) mimarisi hedeflenerek OpenMP + NEON SIMD hızlandırmalı 3 aşamalı bir gürültü filtreleme boru hattı kurulmuştur:

### Aşama 1: Temporal EWM (Üst Kararlılık Filtresi)
Titreşimleri (flickering) önlemek için ardışık kareler piksel düzeyinde birleştirilir:
$$\text{Output}_t = \alpha \cdot \text{Input}_t + (1 - \alpha) \cdot \text{Output}_{t-1}$$
- **$\alpha$ (Ağırlık)**: Varsayılan `0.65`.
- **Hareket Eşiği (Motion Threshold)**: Ani kamera hareketlerinde motion blur oluşmaması için $|\text{Input}_t - \text{Output}_{t-1}| > 0.08m$ ise geçmiş sıfırlanır ($\alpha = 1.0$).

### Aşama 2: 9x9 RGB-Guided Joint Bilateral Filter
Derinlik haritasındaki kenarları korurken düz yüzeylerdeki gürültüyü temizler. RGB görüntünün 3-kanal (R,G,B) renk farklılıklarını rehber olarak kullanır:
- **Kernel Boyutu**: $9\times9$ piksel (`RADIUS = 4`).
- **Uzamsal Sigma ($\sigma_s$)**: `3.0`.
- **Renk Sigma ($\sigma_r$)**: `20.0`.

### Aşama 3: İstatistiksel Aykırı Nokta Temizleyici (NPU-SOR)
Nokta bulutu biriktirildikten sonra (kaydetme aşamasından hemen önce) çalışır. Her nokta için k-NN (k-En Yakın Komşu) analizi yapar:
- **Komşu Sayısı ($k$)**: `8`.
- **Eşik**: Küresel ortalama mesafe + $1.5\sigma$ (standart sapma). Eşiği aşan noktalar parazit kabul edilerek silinir.

---

## 4. 3B Rekonstrüksiyon (TSDF & Surface Nets)

- **TSDF (Truncated Signed Distance Function)**: Hacimsel (voxel grid) alan oluşturarak derinlik haritalarını tek bir 3B modelde birleştirir.
- **Surface Nets**: poisson_deferred modülünde TSDF hacminden hızlı bir şekilde solid mesh (yüzey ağ yapısı) çıkartır.

---

## 5. Kullanıcı Arayüzü (HUD) & 3B Önizleme

Jetpack Compose tabanlı arayüz, profesyonel tarayıcı (Revo Scan vb.) standartlarına göre optimize edilmiştir:
- **Dikey Mesafe Kılavuzu**: Kameranın objeye olan mesafesini dinamik bar ve renklerle (Mavi: Uzak, Yeşil: Mükemmel, Kırmızı: Çok Yakın) gösterir.
- **Tam Ekran 3D Önizleme**: AlertDialog yerine tam ekran modal kullanılarak kullanıcının parmak hareketleriyle modeli 360 derece döndürmesi ve incelemesi sağlanmıştır.
