# Honor Magic V3 — Teknik Özellikler Referans Dosyası

> Kaynak: [GSMArena](https://www.gsmarena.com/honor_magic_v3-13202.php)
> Amaç: 3D tarama uygulaması mimarisi ve ComfyUI karakter üretim pipeline'ı için donanım/yazılım referansı.

---

## 1. Genel Bilgi

| Özellik | Değer |
|---|---|
| Duyuru | 2024, Temmuz 12 |
| Piyasa çıkışı | 2024, Temmuz 19 |
| Modeller | FCP-AN10, FCP-N49 |
| Ağırlık | 226 g / 230 g |
| Kalınlık | 4.35 mm / 4.4 mm (açık) |
| Boyutlar (açık) | 156.6 x 145.3 x 4.35/4.4 mm |
| Boyutlar (kapalı) | 156.6 x 74.0 x 9.2/9.3 mm |
| Gövde | Cam ön, cam-fiber takviyeli plastik arka, alüminyum çerçeve |
| Su/toz direnci | IPX8 (2.5m'ye kadar 30 dk) |
| Stylus desteği | Var |
| Renkler | Velvet Black, Snow, Tundra Green, Red |

---

## 2. İşlemci & Bellek (Capture/Processing Pipeline için Kritik)

| Özellik | Değer |
|---|---|
| Chipset | Qualcomm SM8650-AB Snapdragon 8 Gen 3 (4nm) |
| CPU | Octa-core: 1x3.3GHz Cortex-X4 + 3x3.2GHz Cortex-A720 + 2x3.0GHz Cortex-A720 + 2x2.3GHz Cortex-A520 |
| GPU | Adreno 750 |
| RAM seçenekleri | 12GB / 16GB |
| Depolama | 256GB / 512GB / 1TB, UFS 4.0, kart yuvası yok |
| AnTuTu (v10) | 1,589,991 |
| GeekBench (v5) | 6,461 |
| 3DMark (Wild Life Extreme) | 4,759 |

**Mimari notu:** Adreno 750, TFLite GPU delegate ve NNAPI için iyi bir hedef. 12/16GB RAM, on-device mesh reconstruction modelleri (örn. hafifletilmiş NeRF/Gaussian Splatting varyantları) için yeterli bütçe sağlıyor.

---

## 3. Kamera Sistemi (Capture Pipeline için Kritik)

### Arka Kameralar (Üçlü)
| Kamera | Özellik |
|---|---|
| Ana (Wide) | 50MP, f/1.6, 23mm, 1/1.56", 1.0µm piksel, PDAF, OIS |
| Telefoto (Periskop) | 50MP, f/3.0, 90mm, 1/2.51", 0.7µm piksel, PDAF, OIS, 3.5x optik zoom |
| Ultra-geniş | 40MP, f/2.2, 16mm, 112° FOV, AF (autofocus var — derinlik/stereo potansiyeli) |

**Ek özellikler:** Laser AF, LED flaş, HDR, panorama
**Video:** 4K@30/60fps (10-bit HDR), 1080p@30/60fps, gyro-EIS, OIS

### Ön Kameralar
| Kamera | Özellik |
|---|---|
| İç ekran selfie | 20MP, f/2.2, 90° FOV, 21mm, 0.61µm piksel |
| Kapak ekran selfie | 20MP, f/2.2, 90° FOV, 21mm, 0.61µm piksel |

**Video (selfie):** 4K@30fps, 1080p@30fps, gyro-EIS

### ⚠️ Kritik Kısıt: Donanımsal Derinlik Sensörü YOK
Sensör listesinde ToF/LiDAR bulunmuyor. Bu, **ARCore Depth API'nin "Depth from Motion" (yazılımsal, tek-RGB-kameradan hareket bazlı derinlik çıkarımı) modunda çalışacağı** anlamına gelir:
- Gerçek ToF'lu cihazlara (Pixel Pro serisi, bazı Galaxy modelleri) göre point cloud çok daha gürültülü olur.
- Derinlik haritası convergence süresi daha uzundur; kullanıcının cihazı yavaş ve düzenli hareket ettirmesi gerekir.
- Laser AF autofocus içindir, genel derinlik haritalama için kullanılamaz.
- Ultra-geniş kameranın autofocus'lu olması, ana+ultrawide arası stereo baseline ile ek derinlik ipucu çıkarma ihtimalini araştırmaya değer kılıyor (deneysel, resmi API desteği yok).

---

## 4. Ekran

| Özellik | İç Ekran | Kapak Ekranı |
|---|---|---|
| Tip | Foldable LTPO AMOLED | LTPO OLED |
| Boyut | 7.92" (201.6 cm²) | 6.43" |
| Çözünürlük | 2156 x 2344 px (~402 ppi) | 1060 x 2376 px (~402 ppi) |
| Yenileme hızı | 120Hz | 120Hz |
| Parlaklık (peak) | 1800 nit (ölçülen: 1076 nit) | 5000 nit |
| Renk derinliği | 1B renk, HDR10+, Dolby Vision | 1B renk, Dolby Vision |
| Koruma | King Kong Rhinoceros | Nanocrystal Glass 2.0 |
| Ekran-gövde oranı | ~%88.6 | — |

**Mimari notu:** Katlanır form faktörü nedeniyle capture UI iki ayrı akışta tasarlanmalı: kapak ekranda hızlı/tek elle tarama modu, iç ekranda geniş önizleme + mesh review deneyimi.

---

## 5. İşletim Sistemi

| Özellik | Değer |
|---|---|
| Çıkış OS | Android 14 |
| Güncel (kullanıcı yorumlarına göre) | Android 15/16'ya yükseltilebilir |
| Özel katman | MagicOS 9 (yorumlara göre cihazlar MagicOS 10'a güncellenmiş) |

**Mimari notu:** MagicOS/Honor'un Camera2 API üzerinde kısıtlayıcı davranışları (bazı Huawei/Honor cihazlarında bilinen bir sorun) — concurrent multi-camera stream erişimi ve manuel sensör kontrolü (exposure/focus lock) gerçek cihazda test edilmeden mimariye kesin karar verilmemeli.

---

## 6. Bağlantı & Sensörler

| Kategori | Değer |
|---|---|
| Wi-Fi | 802.11 a/b/g/n/ac/6e/7, dual-band, Wi-Fi Direct |
| Bluetooth | 5.3, A2DP, LE, aptX HD, LDAC |
| USB | USB Type-C 3.1, OTG, DisplayPort 1.2 |
| NFC | Var |
| Kızılötesi | Var |
| GNSS | GPS (L1+L5), GLONASS (L1), BDS, GALILEO |
| Sensörler | Parmak izi (yan), ivmeölçer, jiroskop, yakınlık, pusula, barometre |

**Not:** IMU (ivmeölçer + jiroskop) mevcut — ARCore'un motion tracking (VIO - Visual-Inertial Odometry) için gerekli temel donanım sağlanıyor.

---

## 7. Batarya

| Özellik | Değer |
|---|---|
| Kapasite | 5150 mAh (Si/C Li-Ion) |
| Kablolu şarj | 66W |
| Kablosuz şarj | 50W |
| Ters kablolu şarj | 5W |
| Ölçülen kullanım süresi | 10:05h (aktif kullanım skoru) |

**Mimari notu:** Sürekli kamera + GPU-yoğun on-device inference senaryosunda ısınma/throttling ve batarya tüketimi test edilmeli; uzun tarama oturumları için termal bütçe planı gerekebilir.

---

## 8. Ses

| Özellik | Değer |
|---|---|
| Hoparlör | Stereo |
| 3.5mm jak | Yok |
| Hi-Res ses | 24-bit/192kHz |

---

## 9. Özet: 3D Tarama Uygulaması için Mimari Kısıtlar

1. **Donanımsal derinlik sensörü yok** → ARCore Depth API "Depth from Motion" moduna bağımlı, kalite ToF'lu cihazlara göre düşük.
2. **Adreno 750 GPU** güçlü → on-device TFLite/NNAPI inference için avantaj.
3. **12-16GB RAM** → model boyutu kısıtı düşük öncelikli.
4. **Katlanır ekran** → çift ekran UX tasarımı gerekli (capture vs. review).
5. **MagicOS/Camera2 kısıtları** → concurrent multi-cam stream desteği gerçek cihazda doğrulanmalı.
6. **UFS 4.0 depolama** → ham point cloud/mesh export I/O darboğaz değil.
7. **IMU mevcut** → VIO tabanlı motion tracking için temel donanım sağlanıyor.
