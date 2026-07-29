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
