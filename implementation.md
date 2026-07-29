# Magic 3D Scanner v2 — Implementation Charter

> **Belge Türü:** Mühendislik Charter & Yol Haritası
> **Oluşturma Tarihi:** 2026-07-29
> **Hedef Cihaz:** Honor Magic V3 (Snapdragon 8 Gen 3)
> **Geliştirme Ortamı:** Android Studio
> **Metod:** Her gün 1 adım ilerleme, doğrulayarak ilerleme
> **Versiyon:** v2.0 (Sıfırdan re-build)

---

## 1. Proje Manifestosu

Bu proje ticari bir mağaza rafı değil; **mühendisin kendi atölyesinde kullandığı bir araçtır.** Kullanıcı 3 boyutlu yazıcı ile çalışan biridir ve

- Açılış-tara-ihracat döngüsü **saniyeler** içinde olgunlaşmış olmalı
- Telefon tam bağımsız çalışmalı; bilgisayar gerekmez
- Çıktı doğrudan Blender / Fusion 360 / MeshLab'de açılabilir olmalı (`.ply`)
- Hedefiniz: hızlı toparlama + ölçeklendirme tabanlı çıktı
- Tasarım felsefesi: **doğrulayarak, basitten karmaşığa, her gün 1 adım**

---

## 2. Gereksinimler Özeti

| #  | İhtiyaç                                        | Tür              | Faz          |
|----|------------------------------------------------|------------------|--------------|
| R1 | 3B yazıcı için hızlı toparlama tarama         | İşlevsel         | 4, 6, 8      |
| R2 | Telefondan tam bağımsız çalışma              | İşlevsel         | 0-9 (全程)  |
| R3 | Açılışta tüm kameraların CANLI görüntüsü     | UX (kayıt öncesi)| 2            |
| R4 | Kayıt sırasında birleşmiş nokta önizlemesi   | UX (kayıt sırası) | 4, 6        |
| R5 | "Neyi kaçırdım" geri bildirimi              | UX (Doğrulama)  | 6            |
| R6 | Windows Görev Yöneticisi benzeri HUD         | Sistem İzleme   | 1            |
| R7 | 3 kamera + farklı odak → depth (Trinoküler)  | Algoritma (refinement) | 7   |
| R8 | NPU ile segmentasyon / arka plan silme       | AI              | 5            |
| R9 | NPU ile nokta temizleme / iyileştirme        | AI              | 3, 5         |
| R10| .ply formatında dışa aktarım                | Çıktı           | 8            |
| R11| Gerçek 3D önizleme (hata tespiti)          | UX              | 8            |
| R12| Basitten başla, her gün 1 adım              | Metod           | Tüm fazlar   |

---

## 3. Hedef Donanım Profili — Honor Magic V3

### 3.1 SoC Spec

| Bileşen            | Spek                              | Notlar                              |
|--------------------|-----------------------------------|-------------------------------------|
| SoC                | Qualcomm Snapdragon 8 Gen 3       | TSMC N4P, 1+3+2+2 yapı               |
| Prime CPU          | Cortex-X4 @ 3.30 GHz             | Ağır iş (depth + tj)               |
| Performance CPU    | 3× Cortex-A720 @ 3.2 GHz         | Multi-frame processing              |
| Efficiency CPU     | 2× Cortex-A520 @ 2.27 GHz        | Background (monitor, UI)            |
| L3 Cache           | 12 MB                            | Tüm çekirdekler paylaşımlı          |
| GPU                | Adreno 750 @ ~1 GHz             | Vulkan 1.3, FP16 hızlandırılmış     |
| NPU (Hexagon HTP)  | ~17 INT8 TOPS / ~30 TOPS hybrid | NNAPI delegasyonu - kritik AI       |
| ISP                | Spectra ISP, 3×18-bit            | 3 kamera senkron desteği            |
| RAM                | LPDDR5X 12 GB @ 4800 MT/s         | Uygulama için ~8 GB erişilebilir    |
| RAM Turbo          | +12 GB (storage swap)            | Bu **swap bellektir**, NPU inference için **DOĞRUDAN kullanılmaz** |
| Depolama           | UFS 4.0, 256/512 GB              | Model + .ply için bolluk            |

### 3.2 RAM Politikası (Önemli)

```
12 GB Fiziksel (LPDDR5X) — Uygulama bütçesi:
  ┌─────────────────────────────────────────────────────────┐
  │ OS + Background Services     ≈ 3.5 GB                  │
  │ Compose UI + Render Pipeline  ≈ 1.5 GB                 │
  │ Kamera Frame Buffers (3x~9MB) ≈ 0.1 GB                 │
  │ ───────────────────────────────────────────────────    │
  │ AI Modeller (TFLite)         ≈ 1 GB hedef             │
  │ Nokta Bulutu (in-memory)     ≈ 2-4 GB (1M nokta=12MB×N)│
  │ JNI Buffers + Vulkan         ≈ 0.5 GB                  │
  │ ───────────────────────────────────────────────────    │
  │ Toplam kullanım hedefi      ≈ 8.5-10 GB                │
  └─────────────────────────────────────────────────────────┘

12 GB Honor RAM Turbo (swap):
  • Swap olduğu için YAVAŞ
  • Sadece "tasfiye/limit-üstü" durumlar için
  • AI inference için KESİNLİKLE uygun DEĞİL
  • Nokta bulutu depolama için "overflow" olarak izin verilecek
```

### 3.3 Kamera Setup (Honor Magic V3)

| Lens              | MP  | FOV       | Odak (eon)            | Kullanım                 |
|-------------------|-----|-----------|------------------------|--------------------------|
| Ultrawide         | 48  | ~120°     | 13mm eon              | Geniş sahne + close depth |
| Main (wide)       | 50  | ~90°      | 23mm eon, f/1.6, OIS  | Birincil: depth + scan   |
| Periscope telephoto | 50  | ~20-30°  | 90mm eon, f/2.4, OIS  | Uzak obje refinement      |
| Front             | 20  | ~90°      | 22mm eon              | Selfie (kullanılmayacak) |

> **Not:** Magic V3 faz 2'de Camera2 multi-camera API ile 3 arka kamerayı **aynı anda** işleyeceğiz; Honor'da bu spec olarak desteklenir (Spectra ISP).

---

## 4. Kararlar ve Gerekçeleri

### 4.1 Onaylanan Kararlar (Q-A → Q-E)

| Soru | Karar                                        | Verildi   |
|------|----------------------------------------------|-----------|
| Q-A  | Mevcut master'ı sil, sıfırdan yaz            | 2026-07-29|
| Q-B  | Mevcut MainActivity.kt komponent'leri reddi | 2026-07-29|
| Q-C  | Android Studio + fiziksel Honor Magic V3    | 2026-07-29|
| Q-D  | 12 GB fiziksel + 12 GB Honor RAM Turbo     | 2026-07-29|
| Q-E  | Her gün 1 adım, doğrulayarak                | 2026-07-29|

### 4.2 Mimari Kararlar

| #  | Karar                              | Gerekçe                                   |
|----|------------------------------------|-------------------------------------------|
| D1 | Trinoküler depth'i Phase 7'ye bırak| FOV çakışması dar + kalibrasyon zor → ilk çalışan tarayıcı geçiter |
| D2 | Birincil depth: AI monoküler (Depth Anything) | NPU hızlandırması, hemen başlar     |
| D3 | NPU erişimi: TFLite + NNAPI delegate | Qualcomm QNN SDK karmaşıklığından kaçın  |
| D4 | Single-Activity + modular packages | Compose pure, nullable state azalt         |
| D5 | Material3 + Compose                | Legacy Theme.Material reddi               |
| D6 | CameraX ile başla, sonra Camera2   | Basitten karmaşığa sıralı onboard         |
| D7 | Canvas 2D önce, sonra GLSurfaceView | Performans optimizasyonu kademeli        |
| D8 | .ply binary formatında export      | Blender / MeshLab uyumluluğu             |
| D9 | Manuel DI (Hilt yok)               | Karmaşıklığı azalt, az dep.               |
| D10| OpenMP + Vulkan opsiyonel (Phase 7+)| NPU yetersiz kaldığında yedek             |

---

## 5. Mimarİ — Modül Yapısı

```
com.magicv3.scanner3d/
│
├── app/                                   ← Android Application modülü
│   ├── MainActivity.kt                    ← Tek Compose host (~150 satır)
│   ├── MagicScannerApplication.kt         ← App-level setup
│   ├── AndroidManifest.xml
│   └── build.gradle.kts
│
├── ui/                                    ← Tüm Composable'lar
│   ├── theme/
│   │   ├── Color.kt
│   │   ├── Theme.kt
│   │   └── Type.kt
│   ├── scan/
│   │   ├── ScanScreen.kt                  ← Ana tarama ekranı
│   │   ├── CameraPreviewSurface.kt        ← CameraX PreviewView host
│   │   └── MultiCamPreview.kt             ← 3 kamera thumbnail'lari (Phase 2)
│   ├── preview/
│   │   ├── PointCloudViewer.kt            ← Canvas preview (Phase 4)
│   │   ├── GlPointCloudViewer.kt          ← GLSurfaceView (Phase 8)
│   │   └── ViewerGestures.kt              ← awaitEachGesture
│   ├── monitor/
│   │   ├── SystemHud.kt                   ← RAM/CPU/Thermal paneli (Phase 1)
│   │   └── ThermalWarning.kt              ← Otomatik duraklatma modali
│   └── common/
│
├── data/
│   ├── camera/
│   │   ├── CameraController.kt            ← CameraX wrapper
│   │   ├── MultiCameraController.kt       ← 3 kamera senkron (Phase 2)
│   │   └── FrameCollector.kt              ← ImageProxy → stream
│   ├── scan/
│   │   ├── ScanSession.kt                 ← Tek tarama oturumu
│   │   └── PointCloudRepository.kt        ← Nokta biriktirme
│   └── export/
│       └── PlyExporter.kt                 ← .ply yazıcı (Phase 8)
│
├── domain/
│   ├── model/
│   │   ├── Point3D.kt
│   │   ├── CameraFrame.kt
│   │   └── ScanResult.kt
│   └── usecase/
│       ├── DepthToPointsUseCase.kt
│       └── AccumulateCloudUseCase.kt
│
├── infra/
│   ├── ai/
│   │   ├── DepthInferenceEngine.kt        ← TFLite + NNAPI (Phase 3)
│   │   ├── SegmentationEngine.kt          ← FastSAM (Phase 5)
│   │   └── assets/
│   │       ├── depth_anything_small.tflite
│   │       └── fastsam_mobile.tflite
│   ├── jni/
│   │   ├── NativeBridge.kt                ← Kotlin ↔ C++ sarmalayıcı
│   │   └── cpp/
│   │       ├── native_bridge.cpp
│   │       └── CMakeLists.txt
│   └── system/
│       ├── SystemMonitor.kt               ← RAM/CPU okuma
│       ├── ThermalMonitor.kt              ← /sys/class/thermal
│       └── PerformanceMonitor.kt          ← FPS / Tarama hızı
```

### 5.1 Modül Sorumluluk Modularitesi

```
┌─────────────────────────────────────────────────────────────────┐
│  UI Layer (Compose)                                              │
│  ──────────────────────                                          │
│  Sorumlu: Kullanıcıya görünme, etkileşim, state display         │
│  Sorumsuz: İş mantığı, JNI, I/O, model interpret                │
│                                                                  │
│  ─ İzin verilen dep.: Compose, Material3, Coroutines view model │
│  ─ Yasak: NDK, TFLite, CameraX internal, JNI doğrudan çağrı    │
└────────────────────┬────────────────────────────────────────────┘
                     │ (state / event)
┌────────────────────▼────────────────────────────────────────────┐
│  Data + Domain Layer                                             │
│  ──────────────────────                                          │
│  Sorumlu: Kamera akışı, scan state, açube, repository           │
│  Sorumsuz: Compose UI                                            │
│                                                                  │
│  ─ İzin verilen dep.: CameraX, Coroutines, Flow, Room(opt)      │
│  ─ Yasak: Compose                                                │
└────────────────────┬────────────────────────────────────────────┘
                     │ (inference toplanınca)
┌────────────────────▼────────────────────────────────────────────┐
│  Infra Layer (AI + JNI + System)                                │
│  ──────────────────────                                          │
│  Sorumlu: NPU inference via NNAPI, JNI köprü, sistem metrikleri│
│  Sorumsuz: UI state, scan state                                 │
│                                                                  │
│  ─ İzin verilen dep.: TFLite, ONNX Runtime(opt), NDK, OpenMP   │
│  ─ Yasak: Compose, CameraX                                      │
└─────────────────────────────────────────────────────────────────┘
```

---

## 6. Teknoloji Yığını

| Katman             | Seçim                         | Versiyon Hedefi  |
|--------------------|-----------------------------|------------------|
| Dil (UI)           | Kotlin                       | 2.0+             |
| Dil (Native)       | C++                          | C++17            |
| Build              | Gradle Kotlin DSL            | 8.5+             |
| Min SDK            | API 28                       | —                |
| Target SDK         | API 34                       | —                |
| Compile SDK        | API 34                       | —                |
| UI                 | Jetpack Compose              | BOM 2024.06+     |
| Tema               | Material3                    | —                |
| Kamera             | CameraX (Phase 1) → Camera2 (Phase 2) | 1.3+ / 5.2+ |
| AI                 | TensorFlow Lite + NNAPI     | 2.16+            |
| GPU Renderer       | OpenGL ES 3.0                | —                |
| Coroutines         | kotlinx.coroutines          | 1.8+             |
| Lifecycle          | Lifecycle-Compose            | 2.8+             |
| Native (opsiyonel)  | OpenMP + Vulkan              | —                |
| DI                 | Manuel (constructor injection) | —              |
| Export format      | PLY binary                   | —                |

---

## 7. Yol Haritası — 9 Faz, Günlük Adımlar

### Faz Özeti

| Faz | Hedef                              | Tahmini Süre  | Tamamlanma Kriteri                       |
|-----|------------------------------------|---------------|------------------------------------------|
| 0   | İskelet + build doğrulama          | ~3 gün        | App açılır, splash görünü, modüller oluşturulur    |
| 1   | Tek kamera + HUD                   | ~5 gün        | Kamera canlı, RAM/CPU/sıcaklık HUD    |
| 2   | Üç kamera multi-preview             | ~4 gün        | 3 kamera thumbnail canlı, capture同步|
| 3   | AI Depth (Depth Anything NPU)     | ~6 gün        | Kameraya depth overlay, ~15 FPS NPU    |
| 4   | Depth → Point cloud + Canvas       | ~5 gün        | Nokta bulutu overlay, orbit/pan/zoom  |
| 5   | NPU Segmentasyon (FastSAM)         | ~5 gün        | Arka plan silme modu, sadece obje noktaları |
| 6   | Multi-frame accumulation (VO)      | ~6 gün        | Yürürken global cloud büyür           |
| 7   | Trinoküler refinement              | ~7 gün        | 3 kamera disparity → depth精度 +      |
| 8   | .ply export + GLSurfaceView        | ~5 gün        | Blender'da açılır, orbit tam çalışır   |
| 9   | Termal koruma + UX parlatma        | ~4 gün        | ANR yok, otomatik duraklatma, mat.3 restore |

**Toplam tahmini süre:** ~50 gün (her gün 1 adım)

---

### Phase 0 — İskelet İnşası (~3 gün)

| Adım | Görev                                        | Doğrulama                                  |
|------|----------------------------------------------|--------------------------------------------|
| 0.1  | Android Studio'da empty "Compose Activity"  | Project açılır                             |
| 0.2  | Decimal package structure oluştur            | Folder tree `magicv3.scanner3d/` görünür  |
| 0.3  | `app/build.gradle.kts` configure et (SDK, Compose BOM, dependencies) | Build successes |
| 0.4  | Material3 tema iskeleti (`Color.kt`, `Theme.kt`, `Type.kt`) | Compile OK, dark/light toggle |
| 0.5  | `MainActivity.kt` → sadece `MagicScannerApp()` composable | Splash "Magic 3D Scanner v2" görünü |
| 0.6  | `AndroidManifest.xml` (temel permissions, single activity, theme) | Install OK重启          |

---

### Phase 1 — Tek Kamera + Görev Yöneticisi HUD (~5 gün)

| Adım | Görev                                        | Doğrulama                                  |
|------|----------------------------------------------|--------------------------------------------|
| 1.1  | CameraX dependency + permission runtime flow | Kamera izni dialog                         |
| 1.2  | `CameraPreviewSurface.kt` (PreviewView host) | Ana kamera canlı ekranda                   |
| 1.3  | `CameraController.kt` (CameraX ProcessCameraProvider) | Kamera aç/kapat lifecycle |
| 1.4  | `SystemMonitor.kt`: ActivityManager → RAM  | HUD'da "RAM: 4.2 / 12 GB"                  |
| 1.5  | `SystemMonitor.kt`: `/proc/stat` → CPU %  | HUD'da "CPU: 32%"                          |
| 1.6  | `ThermalMonitor.kt`: `/sys/class/thermal/thermal_zone*` | HUD'da "SoC: 38°C" |
| 1.7  | `SystemHud.kt` Compose paneli (overlap)    | Sağ üstte cam-like panel, 60 FPS           |
| 1.8  | Capture butonu (JPEG direk → file, no scanning) | Butona basınca `scan_001.jpg` kaydedilir  |

---

### Phase 2 — Üç Kamera Multi-Preview (~4 gün)

| Adım | Görev                                        | Doğrulama                                  |
|------|----------------------------------------------|--------------------------------------------|
| 2.1  | `MultiCameraController.kt`: 3 Camera2 capture session | Log: "Cam 0/1/2 ready"          |
| 2.2  | Honor Magic V3'te Logical Multi-Camera ID'leri keşfet | CameraManager ids → list                   |
| 2.3  | 3 kamera ayrı ImageReader (YUV_420_888)     | 3 frame stream log'u                       |
| 2.4  | `MultiCamPreview.kt`: 3 küçük SurfaceView thumbnail | Ekranda 3 kutu, her birinde kamera canlı |
| 2.5  | Capture butonu → 3 frame de aynı anda al    | 3 dosya: `frame_main.jpg`, `_wide.jpg`, `_tele.jpg` |

---

### Phase 3 — AI Depth (Depth Anything via NPU) (~6 gün)

| Adım | Görev                                        | Doğrulama                                  |
|------|----------------------------------------------|--------------------------------------------|
| 3.1  | `depth_anything_small.tflite` model assets'e koy | assets klasöründe görünü         |
| 3.2  | `DepthInferenceEngine.kt`: TFLite Interpreter init | Model loaded log: "Depth model hazır" |
| 3.3  | NNAPI Delegate (@Nnapi-delegate) → NPU     | Profiler: HTP (Hexagon) aktif              |
| 3.4  | ImageProxy → RGB float preprocessing (224×224) | Input tensor boyut doğru                  |
| 3.5  | Inference → depth heatmap (grayscale 0-255) | Matplotlib-style overlay'da derinlik       |
| 3.6  | HUD'da inference süresi + NPU/W CPU tag     | "Depth: 45ms, NPU: ✓" görünü |
| 3.7  | Real-time overlay (NPU mod, ~15 FPS)        | Ekranda depth canlı                        |

---

### Phase 4 — Depth → Nokta Bulutu + Canvas Viewer (~5 gün)

| Adım | Görev                                        | Doğrulama                                  |
|------|----------------------------------------------|--------------------------------------------|
| 4.1  | `DepthToPointsUseCase.kt`: back-projection (intrinsics) | FloatArray[3N] output |
| 4.2  | `PointCloudViewer.kt` (Canvas 2D projeksiyon) | Ekranda kalın noktalar (renk=depth)        |
| 4.3  | `ViewerGestures.kt`: awaitEachGesture with 1F pan + 2F rotate/zoom | Jest sağlıklı, çakışma yok |
| 4.4  | Renk kodu: kırmızı = yakın, mavi = uzak (depth jet color) | Renkli depth cloud görünü |
| 4.5  | HUD'da nokta sayısı + render süresi          | "Points: 75,210 | 12 FPS"                  |

---

### Phase 5 — NPU Segmentasyon (FastSAM) (~5 gün)

| Adılar | Görev                                        | Doğrulama                                  |
|------|----------------------------------------------|--------------------------------------------|
| 5.1  | `fastsam_mobile.tflite` assets               | Model dosyası hazır                        |
| 5.2  | `SegmentationEngine.kt`: segmentasyon        | Output: 0/1 mask                           |
| 5.3  | "Sadece Obje" mode toggle (UI)               | Toggle ekranda                             |
| 5.4  | Mask → RGB frame multiply → sadece obje kalsın | Background siyah                            |
| 5.5  | Mask → depth cloud filter                    | Noktalardan objeye-ait-olmayanlar atılır   |

---

### Phase 6 — Multi-Frame Accumulation (Visual Odometry) (~6 gün)

| Adım | Görev                                        | Doğrulama                                  |
|------|----------------------------------------------|--------------------------------------------|
| 6.1  | Frame-to-frame feature matching (ORB-lite)   | 2 frame arası transform hesaplanır         |
| 6.2  | `AccumulateCloudUseCase.kt`: pose graph      | 2 frame cloud birleşir                     |
| 6.3  | `PointCloudRepository.kt` (in-memory list)   | Cloud büyür, HUD: "Total: 200k points"     |
| 6.4  | Real-time cloud preview overlay (alpha blended) | Ekranda tarama büyür |
| 6.5  | Kalman-like smoothing (no GT, öznel doğruluğu) | Dalgalanma azalır                          |
| 6.6  | "Reset scan" butonu                          | Cloud temizlenir, yeni tarama başlar       |

---

### Phase 7 — Trinoküler Refinement (~7 gün)

| Adım | Görev                                        | Doğrulama                                  |
|------|----------------------------------------------|--------------------------------------------|
| 7.1  | 3 kamera intrinsics + extrinsics kalibrasyon planı | Honor fabrika datası yoksa offline chess-board |
| 7.2  | 3 frame düşük FOV telephoto → wide FOV'ya warp | Image transform doğrulandı
| 7.3  | Wide ↔ Main disparity claim (dıramik disparity) | Disparity heatmap miımı               |
| 7.4  | Main ↔ Tele disparity (tele FOV dar)         | Sadece dar bölgede çalışır                 |
| 7.5  | Combine: AI depth + trinocular disparity → weighted | Daha keskin edges                    |
| 7.6  | OpenMP ile 3-camera feature matching parallel | İşlemci çıtaları bölünür                  |
| 7.7  | Doğruluk ölçümü: referans objeye kıyasla    | Ref. sapma: SNR analizi                    |

---

### Phase 8 — .ply Export + GLSurfaceView Renderer (~5 gün)

| Adım | Görev                                        | Doğrulama                                  |
|------|----------------------------------------------|--------------------------------------------|
| 8.1  | `PlyExporter.kt`: PLY binary header + body   | .ply dosyası yazılır                       |
| 8.2  | MediaStore save (.ply) BLEND et amacı       | `scan_001.ply` dosylar listesinde         |
| 8.3  | `GlPointCloudViewer.kt`: GL_POINTS shader   | Renderer queue ездер |
| 8.4  | GLSurfaceView + ViewerGestures integration | OGL井下 pan/zoom dnünMOOTH     |
| 8.5  | GLPoints transparent + depth testing        | Cull between points  |

---

### Phase 9 — Thermal + UX Polish (~4 gün)

| Adım | Görev                                        | Doğrulama                                  |
|------|----------------------------------------------|--------------------------------------------|
| 9.1  | `ThermalWarning.kt`: SoC ≥ 50°C'de modal    | Modal görü |
| 9.2  | Otomatik duraklatma + resume               | Tarama durur ve bir pause      |
| 9.3  | Material3 restore (tüm CustomComposable'ları)| Visual consistency |
| 9.4  | ANR testi (uzun tarama ~10 dB)              | ANR yok + smooth |
| 9.5  | Onboarding / ilk açılış tutorial            | ]|

---

## 8. Risk Analizi ve Mitigasyon

| # | Risk                              | Olasılık | Etken           | Mitigasyon                          |
|---|------------------------------------|----------|-----------------|-------------------------------------|
|RK1|TFLite NNAPI delegate deprecated   | Orta     | Hız kaybı       | ONNX Runtime fallback (Phase 3)    |
|RK2|Honor RAM Turbo yavaş → OOM        | Düşük    | Crash           | Native heap limit + küçük model     |
|RK3|Multi-camera frame sync fsrkrları   | Orta     | JIT render errors| Timestamp-based sync kontrol        |
|RK4|Trinoküler kalibrasyon data yok    | Yüksek   | Yanlış depth    | 7.1'de offline chess-board required |
|RK5|NPU kota / throttling              | Orta     | FPS drop        | Phase 9'da dynamic batching         |
|RK6|ANR                                  | Orta     | UI donması      | Phase 6'da CoroutineScope + Dispatchers.IO |
|RK7|GC pressure → lag                  | Düşük    | Stutter         | ByteArray pool, FrameCollector ring |
|RK8|Adreno driver bug                  | Düşük    | Crash           | GLES 2.0 fallback, sürüm log        |

---

## 9. Git Stratejisi

```
Mevcut master: SILINECEK
Yeni master: temiz, faz bazına commit'ler

Branch stratejisi:
  master        ← stabil, her fazın sonunda tag
  feature/phase-X-Y ← her adım için ayrı branch
  tag v2.0.0-phase0 ← her fazın sonunda tag
```

### 9.1 İlk Operasyon

```bash
# GitHub'da mevcut master ı sil, sıfırdan başla
# (User tarafından yapılacak — Android Studio'da yeni project aç)
```

### 9.2 Commit Formatı

```
[Phase X.Y] <adım açıklaması>

- <kullanılan dosya>(
- <doğrulama sonucu>

Doğrulama: ✅ / ❌
```

---

## 10. Doğrulama Ritüeli (Her Adım Sonra)

1. Build başarıyla tamamlandı (Debug variant)
2. Honor Magic V3 fiziksel cihaza yüklendi
3. Doğrulama kriteri bu dokümanadaki o adımın sağındaki ölçüt — gerçekleştirildi
4. Eğer fail → Root cause + düzeltme → tekrar doğrula
5. Commit → tag → sonraki gün Phase X.Y+1

---

## 11. Önemli İlk Kararlar

- **Trinoküler depth → Phase 7 (refinement)**: İlk çalışan tarayıcı için AI mono depth (Phase 3) yeterli, hatta zorunlu — Honor Magic V3'te çalışırken kalibrasyon zor. AI daha geri dönüş.
- **NPU kullanımı TFLite + NNAPI**: Qualcomm QNN SDK daha hızlı ama geçişi zor. NNAPI morfolojik optimal seçim.
- **Honor RAM Turbo**: Bellek olarak sayma—swap. Sadece overflow.
- **Yüksek yükseklikten indirme sırası**: RU'su OnboardtLongrightarrow Core 0
- **Pilot avantajı**: Onaylı doBI'miz mükemmel engineering challenge.

---

## 12. Kapanış

Bu doküman **muteber bir mühendislik charter'ıdır**. Tüm kararlar onaylandı (Q-A → Q-E). Tüm fazlar günlük adımlara bölündü. Yaklaşık 50 günlük bir program — her gün 1 adım —

**Sonraki Aksiyon:** Phase 0.1 — Android Studio'da empty Compose Activity project oluşturmak.

---

*Bu doküman living document olarak her faz sonunda güncellenecektir.*
*İmza: Magic 3D Scanner v2 — Engineering Charter — 2026-07-29*


---
## 13. FAZ 1 — Detaylı Implementation Plan
> **Başlangıç:** Phase 0 kapandıktan hemen sonra  
> **Hedef Cihaz:** Honor Magic V3 (Snapdragon 8 Gen 3)  
> **Metod:** Her gün 1 adım, doğrulayarak, faz sonunda tag  

### 13.1 Faz 1 Hedefleri
1. **Tek kamerayı canlı ekrana al** (CameraX, sadece Main lens)
2. **Windows Görev Yöneticisi benzeri HUD** — RAM / CPU / SoC sıcaklık
3. **Capture butonu** — JPEG → dosya (henüz tarama yok)
4. **İlk tema rafinerisi** — Cyber/dark-first palette (dynamic color off)

### 13.2 Faz 1 Mimari — Dosya Değişiklikleri
```
app/
├── build.gradle.kts                   [1.1] MODIFY ← CameraX deps
└── src/main/java/com/magicv3/scanner3d/
    ├── MainActivity.kt                [1.1] MODIFY ← Permission router
    ├── ui/
    │   ├── theme/
    │   │   ├── Color.kt               [1.0] MODIFY ← Cyber palette
    │   │   └── Theme.kt               [1.0] MODIFY ← dynamicColor=false
    │   ├── scan/
    │   │   ├── ScanScreen.kt          [1.2] NEW    ← Tarama host
    │   │   └── CameraPreviewSurface.kt [1.2] NEW    ← PreviewView host
    │   └── monitor/
    │       └── SystemHud.kt           [1.7] NEW    ← HUD Compose panel
    ├── data/
    │   └── camera/
    │       └── CameraController.kt    [1.3] NEW    ← CameraX wrapper
    └── infra/
        ├── permission/
        │   └── CameraPermission.kt    [1.1] NEW    ← Permission state
        └── system/
            ├── SystemMonitor.kt       [1.4] NEW    ← RAM/CPU reader
            └── ThermalMonitor.kt      [1.6] NEW    ← /sys/class/thermal
```

### 13.3 Faz 1.0 — Tema Rafinesi (Cyber Palette)
**Ön koşul:** Phase 0 tamamlanmış  
**Hedef:** Dark-first, "task manager" estetiği — yüksek kontrast, neon accent  

**Değişiklikler:**
- `Color.kt` → Material3 default (Purple/Pink) yerine cyber dark paleti
- `Theme.kt` → `dynamicColor = false` (duvar kağıdı rengi HUD okunabilirliğini bozar)

**Color.kt Blueprint:**
```kotlin
// Cyber dark-first
val CyberBackground = Color(0xFF0A0E14)         // anthracite
val CyberSurface    = Color(0xFF121821)         // panel zemin
val CyberPrimary    = Color(0xFF00E5FF)         // cyan neon (accent)
val CyberSecondary  = Color(0xFF7C4DFF)         // violet
val CyberTertiary   = Color(0xFF69F0AE)         // mint green (ok/positive)
val CyberError      = Color(0xFFFF5252)         // red (thermal alarm)
val CyberOnBg       = Color(0xFFE3E8EF)         // primary text
val CyberOnSurface  = Color(0xFFB0BEC5)         // secondary text

// HUD için essential renkler:
val HudGood = Color(0xFF69F0AE)   // CPU<40% / RAM<60%
val HudWarn = Color(0xFFFFD740)   // CPU 40-70% / RAM 60-80%
val HudCrit = Color(0xFFFF5252)   // CPU>70% / RAM>80% / SoC>50°C
```

**Theme.kt Blueprint:**
```kotlin
@Composable
fun MagicScannerTheme(
    darkTheme: Boolean = true,                  // hard-coded dark
    dynamicColor: Boolean = false,              // CRITICAL: kapalı
    content: @Composable () -> Unit
) {
    val colorScheme = darkColorScheme(
        primary = CyberPrimary,
        secondary = CyberSecondary,
        tertiary = CyberTertiary,
        background = CyberBackground,
        surface = CyberSurface,
        error = CyberError,
        onBackground = CyberOnBg,
        onSurface = CyberOnSurface
    )
    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography,
        content = content
    )
}
```

**Doğrulama:**
| # | Kontrol | Kriter |
|---|---|---|
| 1.0-V1 | Color.kt | Cyber* renkleri tanımlı, Purple/Pink silinmiş |
| 1.0-V2 | Theme.kt | dynamicColor=false hard-coded |
| 1.0-V3 | Theme.kt | darkColorScheme(...) cyber palette ile |
| 1.0-V4 | Ekran | Splash ekranı artık koyu zemin + cyan text |

---
### 13.4 Faz 1.1 — CameraX Dep + Runtime Permission
**Ön koşul:** 1.0 tam  
**Hedef:** Kamera iznini 4 state'li state machine ile yönet (henüz Preview yok)  

**Değişiklikler:**
- `[MODIFY] app/build.gradle.kts` → CameraX 4 artifact'ı  
- `[NEW] infra/permission/CameraPermission.kt`  
- `[MODIFY] MainActivity.kt` → Permission router  

**build.gradle.kts dependency ekleri:**
```kotlin
val cameraxVersion = "1.3.4"
implementation("androidx.camera:camera-core:$cameraxVersion")
implementation("androidx.camera:camera-camera2:$cameraxVersion")
implementation("androidx.camera:camera-lifecycle:$cameraxVersion")
implementation("androidx.camera:camera-view:$cameraxVersion")
```

**CameraPermission.kt — API:**
```kotlin
enum class CameraPermissionState {
    NOT_REQUESTED, GRANTED, DENIED, PERMANENTLY_DENIED
}

class CameraPermissionStateHolder(
    val state: CameraPermissionState,
    val requestPermission: () -> Unit
)

@Composable
fun rememberCameraPermissionState(context: Context): CameraPermissionStateHolder
```

**Implementasyon detayı:**
- Initial state `ContextCompat.checkSelfPermission` ile başlatılır
- `ActivityResultContracts.RequestPermission()` ile launcher açılır
- Reddedildiğinde: `shouldShowRequestPermissionRationale` ile `DENIED` vs `PERMANENTLY_DENIED` ayrımı
- `PERMANENTLY_DENIED` → Settings'e yönlendirme UI tarafında yapılır

**MainActivity.kt — Router:**
```kotlin
when (permission.state) {
    NOT_REQUESTED        -> PermissionRequestScreen(onRequest)
    GRANTED              -> CameraReadyPlaceholderScreen()  // 1.2'de ScanScreen olur
    DENIED               -> PermissionDeniedScreen(onRetry)
    PERMANENTLY_DENIED   -> PermissionPermanentlyDeniedScreen()  // → Settings intent
}
```

**Doğrulama:**
| # | Kontrol | Kriter |
|---|---|---|
| 1.1-V1 | build.gradle.kts | 4 CameraX artifact tanımlı, v1.3.4 |
| 1.1-V2 | infra/permission | Yeni paket mevcut |
| 1.1-V3 | CameraPermission.kt | 4 state'li enum |
| 1.1-V4 | CameraPermission.kt | rememberCameraPermissionState Composable |
| 1.1-V5 | CameraPermission.kt | Initial state ContextCompat'ten |
| 1.1-V6 | CameraPermission.kt | ActivityResultContracts (manual Intent DEĞİL) |
| 1.1-V7 | CameraPermission.kt | PERMANENTLY_DENIED detection: !canShowRationale && hasRequestedBefore |
| 1.1-V8 | MainActivity.kt | when(permission.state) 4 durum kapsamlı |
| 1.1-V9 | MainActivity.kt | ACTION_APPLICATION_DETAILS_SETTINGS intent |
| 1.1-V10 | MainActivity.kt | SplashGreeting tamamen kaldırılmış |
| 1.1-V11 | Build | ./gradlew assembleDebug BUILD SUCCESSFUL |

> **Donanım Notu:** Honor Magic V3'te Android 14 (MagicOS) permission dialog'u sistem-level; bizim state machine OS'in gösterdiği sistem dialog'unu doğru interpret etmeli. Emülatörde "Don't ask again" davranışı eksik olduğundan fiziksel cihaz testi şart.

---
### 13.5 Faz 1.2 — CameraPreviewSurface (PreviewView Host)
**Ön koşul:** 1.1 tam (izin verildi)  
**Hedef:** Compose içine PreviewView göm, kamera CANLI görünsün (henüz Config yok — bu 1.3)  

**Değişiklikler:**
- `[NEW] ui/scan/CameraPreviewSurface.kt`  
- `[NEW] ui/scan/ScanScreen.kt`  
- `[MODIFY] MainActivity.kt` → `GRANTED` state artık `ScanScreen()` gösterir  

**CameraPreviewSurface.kt — Blueprint:**
```kotlin
@Composable
fun CameraPreviewSurface(
    modifier: Modifier = Modifier,
    onPreviewViewReady: (PreviewView) -> Unit
) {
    // AndroidView factory → PreviewView(context)
    // ScaleType.FILL_CENTER
    // implementationMode = COMPATIBLE
    // onPreviewViewReady(previewView) ile geri ver
}
```

**ScanScreen.kt — Blueprint:**
```kotlin
@Composable
fun ScanScreen() {
    Box(modifier = Modifier.fillMaxSize()) {
        CameraPreviewSurface(
            modifier = Modifier.fillMaxSize(),
            onPreviewViewReady = { previewView ->
                // 1.3'te: cameraController.bindPreview(previewView)
            }
        )
        // 1.7'de burada HUD overlayı olacak
        // 1.8'de burada capture button olacak
    }
}
```

**MainActivity.kt — Güncelleme:**
```kotlin
CameraPermissionState.GRANTED -> ScanScreen()   // eski placeholder KALDIR
```

**Doğrulama:**
| # | Kontrol | Kriter |
|---|---|---|
| 1.2-V1 | CameraPreviewSurface.kt | AndroidView factory ile PreviewView oluşturur |
| 1.2-V2 | CameraPreviewSurface.kt | implementationMode = COMPATIBLE |
| 1.2-V3 | ScanScreen.kt | Box layer, fillMaxSize() |
| 1.2-V4 | MainActivity | GRANTED → ScanScreen() router |
| 1.2-V5 | (Bu aşamada) ekran | Kamera henüz CANLI DEĞİL — siyah ekran normal (config 1.3'te) |

> **Teknik Not:** Bu adımda kamera siyah görünür — PreviewView yaratıldı ama ProcessCameraProvider bağlanmadı. Bu bilinçli bir stratejidir — UI host hazır.

---
### 13.6 Faz 1.3 — CameraController (ProcessCameraProvider)
**Ön koşul:** 1.2 tam (PreviewView host hazır)  
**Hedef:** CameraX ProcessCameraProvider ile Main lens'i bind et, kamera canlı aksın  

**Değişiklikler:**
- `[NEW] data/camera/CameraController.kt`  
- `[MODIFY] ScanScreen.kt` → onPreviewViewReady içinden `cameraController.bind()` çağrısı  

**CameraController.kt — Blueprint:**
```kotlin
class CameraController(
    private val context: Context,
    private val lifecycleOwner: LifecycleOwner
) {
    private var cameraProvider: ProcessCameraProvider? = null

    suspend fun initialize() {
        cameraProvider = ProcessCameraProvider.getInstance(context).await()
    }

    fun bindPreview(previewView: PreviewView) {
        val preview = Preview.Builder()
            .setTargetRotation(previewView.display?.rotation ?: Surface.ROTATION_0)
            .build()
            .also { it.setSurfaceProvider(previewView.surfaceProvider) }

        // BACK_0 → Magic V3'te Main 50MP lens
        val selector = CameraSelector.DEFAULT_BACK_CAMERA

        cameraProvider?.unbindAll()
        cameraProvider?.bindToLifecycle(
            lifecycleOwner, selector, preview
        )
    }

    fun unbind() {
        cameraProvider?.unbindAll()
    }
}
```

**Koroutin Entegrasyonu:**
- `ListenableFuture.await()` için kotlinx-coroutines-guava veya kendi suspend extension: `suspendCancellableCoroutine`
- Lifecycle-aware: `bindToLifecycle` otomatik olarak `onStop`'ta unbind eder

**Doğrulama:**
| # | Kontrol | Kriter |
|---|---|---|
| 1.3-V1 | CameraController.kt | ProcessCameraProvider.getInstance kullanılır |
| 1.3-V2 | CameraController.kt | bindToLifecycle (manually bind DEĞİL) |
| 1.3-V3 | CameraController.kt | unbindAll() önce bind |
| 1.3-V4 | ScanScreen.kt | onPreviewViewReady → controller bindPreview |
| 1.3-V5 | Runtime | Kamera CANLI — Main 50MP lens görüntüsü ekranda |
| 1.3-V6 | Lifecycle | Back button → onStop → kamera otomatik unbind (log'dan doğrula) |

> **Donanım Notu (Snapdragon 8 Gen 3):** Spectra ISP 3×18-bit pipeline. Bu adımda sadece 1 kamera (Main 50MP, f/1.6, OIS) kullanılıyor. ISP'in 3 senkron kanal kapasitesinin fazlası var — termal risk bu aşamada yok. Adreno 750 GPU henüz devrede değil; PreviewView'nun RENDER pipeline'ı CPU/GPU tüketimi ~5W civarı.

---
### 13.7 Faz 1.4 — SystemMonitor: RAM
**Ön koşul:** 1.3 tam (kamera canlı)  
**Hedef:** HUD için RAM bilgisini topla — ActivityManager.MemoryInfo  

**Değişiklikler:**
- `[NEW] infra/system/SystemMonitor.kt`  

**SystemMonitor.kt — Blueprint:**
```kotlin
data class SystemStats(
    val totalRamMb: Int,
    val availableRamMb: Int,
    val usedRamMb: Int,
    val ramPercent: Float,
    val cpuPercent: Float,           // 1.5'te doldurulur
    val socTempC: Float,             // 1.6'da doldurulur
    val timestampMs: Long
)

class SystemMonitor(private val context: Context) {
    fun readRam(): Pair<Int, Int> {   // (total, available)
        val am = context.getSystemService(ActivityManager::class.java)
        val memInfo = ActivityManager.MemoryInfo()
        am.getMemoryInfo(memInfo)
        val totalMb = (memInfo.totalMem / 1024 / 1024).toInt()
        val availMb = (memInfo.availMem / 1024 / 1024).toInt()
        return totalMb to availMb
    }
}
```

**Flow / Polling Stratejisi:**
- CoroutineScope + flow { ... } ile her 1s'de bir RAM oku
- Dispatchers.Default — async I/O
- Flow, ScanScreen'in lifecycle'ına bağlanır (repeatOnLifecycle)

**Doğrulama:**
| # | Kontrol | Kriter |
|---|---|---|
| 1.4-V1 | SystemMonitor.kt | ActivityManager.MemoryInfo kullanılır |
| 1.4-V2 | SystemMonitor.kt | totalMem, availMem → MB dönüşümü |
| 1.4-V3 | Test | Honor Magic V3'te totalRamMb ≈ 12288 (12 GB) report edilmeli |

> **Donanım Notu:** memInfo.totalMem LPDDR5X 12 GB rapor eder. Honor RAM Turbo (12 GB swap) buraya dahil değildir — getMemoryInfo() normal fiziksel bellek haritasını verir. RAM Turbo /proc/meminfo'da SwapTotal olarak ayrı satırdır.

---
### 13.8 Faz 1.5 — SystemMonitor: CPU %
**Ön koşul:** 1.4 tam  
**Hedef:** CPU kullanım yüzdesini `/proc/stat`'tan hesapla  

**Değişiklikler:**
- `[MODIFY] SystemMonitor.kt` → `readCpuPercent()` metodu  

**CPU Okuma Mantığı:**
```kotlin
// /proc/stat ilk satırı: aggregates all cores
// cpu user nice system idle iowait irq softirq steal guest guest_nice
// Δidle / Δtotal → idle% → CPU%=100-idle%

private fun readProcStat(): LongArray {
    // /proc/stat'dan "cpu" satırını parse et
    // 10 uzun değer döner (idle=4. değer)
}

fun readCpuPercent(): Float {
    val t0 = readProcStat()
    delay(100)                          // 100ms window
    val t1 = readProcStat()
    val totalDelta = t1.sum() - t0.sum()
    val idleDelta = t1[3] + t1[4]       // idle + iowait
    return 100f * (totalDelta - idleDelta) / totalDelta.coerceAtLeast(1)
}
```

**Doğrulama:**
| # | Kontrol | Kriter |
|---|---|---|
| 1.5-V1 | SystemMonitor.kt | /proc/stat "cpu" satırı parse |
| 1.5-V2 | SystemMonitor.kt | İki örnek arası delta ile % hesap |
| 1.5-V3 | SystemMonitor.kt | Sıfıra bölme koruması (coerceAtLeast(1)) |
| 1.5-V4 | Test | Kamera çalışırken CPU %15-40 arası rapor |

> **Donanım Notu (Snapdragon 8 Gen 3):** Bu yüzde tüm 8 çekirdek (X4 + 3×A720 + 2×A720 + 2×A520) için agregattır. CPU %40+ → performans çekirdeklerinin de yüklendiğini; %15 altı → daha çok efficiency core'lar.

---
### 13.9 Faz 1.6 — ThermalMonitor
**Ön koşul:** 1.5 tam  
**Hedef:** SoC sıcaklığını `/sys/class/thermal/thermal_zone*/temp`'ten oku  

**Değişiklikler:**
- `[NEW] infra/system/ThermalMonitor.kt`  

**ThermalMonitor.kt — Blueprint:**
```kotlin
class ThermalMonitor {
    // /sys/class/thermal dizinini tara
    // thermal_zoneX/type dosyasında "soc-therm" / "cpu-1-0-usr" gibi label
    // Aynı zone'un "temp" dosyasında milisantigrat (÷1000 = °C)

    fun readSoC(): Float {
        val zones = File("/sys/class/thermal").listFiles()
            ?.filter { it.isDirectory } ?: return -1f
        for (zone in zones) {
            val type = File(zone, "type").readText().trim()
            // Öncelik: "soc-therm" → Snapdragon SoC thermal
            if (type.contains("soc-therm", ignoreCase = true)) {
                val temp = File(zone, "temp").readText().trim().toIntOrNull() ?: continue
                return temp / 1000f
            }
        }
        // Fallback: ilk "cpu" tag'li zone
        return zones.firstOrNull { ... }?.let { ... } ?: -1f
    }
}
```

**Doğrulama:**
| # | Kontrol | Kriter |
|---|---|---|
| 1.6-V1 | ThermalMonitor.kt | /sys/class/thermal/thermal_zoneX tarama |
| 1.6-V2 | ThermalMonitor.kt | type dosyasından "soc-therm" eşleşmesi |
| 1.6-V3 | ThermalMonitor.kt | milisantigrat → °C dönüşümü |
| 1.6-V4 | Test | Idle SoC ≈ 35-42°C, çalışma sırasında 45-55°C |

> **Donanım Notu (Snapdragon 8 Gen 3):** TSMC N4P, termal bütçe ~5-8W mobil modda. Honor Magic V3 foldable form factor → ince chassis → SoC termal yolu dar. Sürekli ağır yükte 60°C'de throttling başlar. Bu dosyayı okumak root gerektirmez.

---
### 13.10 Faz 1.7 — SystemHud (Compose Panel)
**Ön koşul:** 1.4 + 1.5 + 1.6 tam (SystemMonitor + ThermalMonitor hazır)  
**Hedef:** Sağ üstte yarı saydam cam panel — kayan değerler, rengi stat'a göre değişir  

**Değişiklikler:**
- `[NEW] ui/monitor/SystemHud.kt`  
- `[MODIFY] ui/scan/ScanScreen.kt` → HUD overlay ekle, Flow collect  

**SystemHud.kt — Blueprint:**
```kotlin
@Composable
fun SystemHud(stats: SystemStats, modifier: Modifier = Modifier) {
    // Surface(color = CyberSurface.copy(alpha=0.85f), tonalElevation=2.dp)
    // Column { RAM row | CPU row | SoC row }
    // Her satırda:
    //   - Etiket (RAM, CPU, SoC)
    //   - Değer text
    //   - Mini progress bar (LinearProgressIndicator)
    //   - Renk: good/warn/crit → HudGood/HudWarn/HudCrit
    // RAM satırı: "RAM 6.2/12 GB"  (67% → HudWarn)
    // CPU satırı: "CPU 32%"         (32% → HudGood)
    // SoC satırı: "SoC 41°C"        (41°C → HudGood)
}

private fun ramColor(pct: Float): Color = when {
    pct < 60f -> HudGood
    pct < 80f -> HudWarn
    else -> HudCrit
}
private fun cpuColor(pct: Float): Color = when {
    pct < 40f -> HudGood
    pct < 70f -> HudWarn
    else -> HudCrit
}
private fun tempColor(tempC: Float): Color = when {
    tempC < 45f  -> HudGood
    tempC < 55f  -> HudWarn
    else -> HudCrit
}
```

**Threading modeli:**
- `ScanScreen` içinde `LaunchedEffect` → `SystemMonitor(context).stats()` flow'unu collect
- Flow callback → `SystemStats` state → recomposition
- Update rate: 1 Hz (CPU okuması yavaş)

**Doğrulama:**
| # | Kontrol | Kriter |
|---|---|---|
| 1.7-V1 | SystemHud.kt | 3 satır Composable (RAM/CPU/SoC) |
| 1.7-V2 | SystemHud.kt | Renk fonksiyonu relativ eşikleri kullanır |
| 1.7-V3 | SystemHud.kt | Surface(alpha=0.85f) yarı saydam |
| 1.7-V4 | ScanScreen.kt | LaunchedEffect + Flow collect |
| 1.7-V5 | ScanScreen.kt | HUD Box içinde align(TopEnd) |
| 1.7-V6 | Runtime | Sağ üstte HUD panel görünür, değerler her saniye güncellenir |
| 1.7-V7 | Runtime | Kamera çalışırken HUD 60 FPS'te akıcı kalmalı |

---
### 13.11 Faz 1.8 — Capture Butonu (JPEG → File)
**Ön koşul:** 1.3 + 1.7 tam  
**Hedef:** Alt ortada capture butonu, tıklandığında JPEG → dosya  

**Değişiklikler:**
- `[MODIFY] data/camera/CameraController.kt` → `takePicture()` metodu eklenir  
- `[MODIFY] ui/scan/ScanScreen.kt` → alt orta capture butonu, state overlay  

**CameraController.kt — Capture eklentisi:**
```kotlin
private var imageCapture: ImageCapture? = null

fun bindPreviewWithCapture(previewView: PreviewView) {
    // 1.3 ile aynı + ImageCapture.UseCase ekle:
    imageCapture = ImageCapture.Builder()
        .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
        .build()
        
    cameraProvider?.bindToLifecycle(
        lifecycleOwner, selector, preview, imageCapture
    )
}

fun takePicture(
    outputFile: File,
    onSaved: () -> Unit,
    onError: (Exception) -> Unit
) {
    val options = ImageCapture.OutputFileOptions.Builder(outputFile).build()
    imageCapture?.takePicture(
        options,
        ContextCompat.getMainExecutor(context),
        object : ImageCapture.OnImageSavedCallback {
            override fun onImageSaved(out: ImageCapture.OutputFileResults) = onSaved()
            override fun onError(exc: ImageCaptureException) = onError(exc)
        }
    )
}
```

**ScanScreen.kt — Capture Eklentisi:**
```kotlin
// Box içinde:
CaptureButton(
    modifier = Modifier.align(Alignment.BottomCenter).padding(bottom=48.dp),
    onClick = {
        val file = File(ctx.getExternalFilesDir(null), "scan_${System.currentTimeMillis()}.jpg")
        controller.takePicture(file, onSaved = { /* showToast */ }, onError = { ... })
    }
)

@Composable
fun CaptureButton(onClick: () -> Unit, modifier: Modifier) {
    FilledIconButton(onClick = onClick, modifier = modifier.size(72.dp)) {
        Icon(Icons.Default.CameraAlt, contentDescription = "Capture")
    }
}
```

**Doğrulama:**
| # | Kontrol | Kriter |
|---|---|---|
| 1.8-V1 | CameraController.kt | ImageCapture use-case bindToLifecycle'e ek |
| 1.8-V2 | CameraController.kt | takePicture async callback |
| 1.8-V3 | ScanScreen.kt | CaptureButton BottomCenter align |
| 1.8-V4 | ScanScreen.kt | Buton onClick → file path'ı getExternalFilesDir |
| 1.8-V5 | Runtime | Butona bas → ~500ms içinde scan_*.jpg getExternalFilesDir'de oluşur |
| 1.8-V6 | Runtime | Toast/Snackbar: "scan_1234.jpg saved" görünür |
| 1.8-V7 | ADB | adb shell ls /sdcard/Android/data/com.magicv3.scanner3d/files/ → .jpg mevcut |

> **Donanım Notu:** ImageCapture CAPTURE_MODE_MINIMIZE_LATENCY → ISP anlık ZSL (zero shutter lag) moduna geçer; Snapdragon 8 Gen 3'te ~150-300ms kayıt süresi verir.

---
### 13.12 Faz 1 Tamamlama Kriterleri (Faz Sonu)
- [ ] 1.0 — Cyber palette + dynamicColor=false
- [ ] 1.1 — Permission router çalışıyor (4 state)
- [ ] 1.2 — PreviewView host hazır
- [ ] 1.3 — Kamera canlı aksın
- [ ] 1.4 — RAM okuma
- [ ] 1.5 — CPU % okuma
- [ ] 1.6 — SoC sıcaklık okuma
- [ ] 1.7 — HUD panel sağ üstte, değerler canlı
- [ ] 1.8 — Capture butonu → JPEG kayıt

**Tag:** `v2.0.0-phase1`  
*Bu bölüm Phase 1 tamamlandığında dokümandaki "Tamamlanma" satırıyla işaretlenecektir.*

*İmza: Magic 3D Scanner v2 — Phase 1 Implementation Plan — 2026-07-29*

