# Değişiklik Logu (CHANGELOG)

## [1.0.0-dev] - 2026-07-28

### Eklenenler
- `native_bridge.cpp`: `fuseDepthMapsNative` JNI fonksiyonu eklendi.
- `MainActivity.kt`: NPU motor durumu kartı, CPU affinity detayları, derinlik füzyonu test butonu ve gelişmiş Compose arayüzü eklendi.
- `session_001.log`: Oturum ve devir teslim takip dosyası güncellendi.
- Android Studio Gradle derleme hatası analizi yapıldı.

### Düzeltmeler
- `online_calibrator.h`: Çift `#ifndef ONLINE_CALIBRATOR_H` başlık koruması düzeltildi ve OpenCV bulunmadığında derlemenin kırılmasını önleyen `__has_include` / `HAVE_OPENCV` desteği eklendi.
- `online_calibrator.cpp`: OpenCV bulunmadığı durumlar için koşullu derleme ve stub fonksiyonu eklendi.
- `CMakeLists.txt`: OpenCV tespit edildiğinde `HAVE_OPENCV=1` derleme tanımı eklendi.
- `gradle-wrapper.properties`: AGP 8.5.1 uyumluluğu için Gradle sürümü 9.3.0'dan 8.7'ye çekildi (Project.exec NoSuchMethodError çözüldü).
- `thread_affinity.cpp`: Android Bionic NDK uyumluluğu için `pthread_setaffinity_np` yerine `sched_setaffinity(0, ...)` kullanıldı.
- `zerocopy_buffer.h`: Vulkan Android uzantıları için `VK_USE_PLATFORM_ANDROID_KHR` makrosu ve `<vulkan/vulkan_android.h>` başlığı eklendi.
- `gradle.properties`: Projede AndroidX bağımlılıklarının doğru yüklenmesi için `android.useAndroidX=true` konfigürasyon dosyası eklendi.
- `ic_launcher.xml`: AndroidManifest.xml içerisindeki `@mipmap/ic_launcher` AAPT hatasını çözmek için adaptif uygulama ikonu ve kaynak dosyaları eklendi.
- `build.gradle.kts`: Java ve Kotlin derleyicilerinin JVM hedef uyumlulukları Java 17 (`JavaVersion.VERSION_17` ve `jvmTarget = "17"`) olarak hizalandı.
- `CMakeLists.txt`: `libomp.so` paylaşılan kütüphanesinin bulunamaması (UnsatisfiedLinkError) çökmesini önlemek için OpenMP statik olarak (`-static-openmp`) bağlandı.
- **Genel**: Uygulamanın tüm derleme ve runtime aşamalarını başarıyla geçtiği, Poisson rekonstrüksiyonu işlemini tamamlayabildiği doğrulandı.
- `work_report.md`: Uygulamanın neden tarama yapmadığını ve gerçek zamanlı taramayı aktifleştirmek için izlenmesi gereken teknik adımları açıklayan çalışma raporu oluşturuldu.
- `implementation_plan.md`: ARCore entegrasyonu ve gerçek zamanlı kamera/derinlik akışı için adım adım uygulama planı oluşturuldu.
- `MainActivity.kt`: Kamera izni, ARCore oturumu başlatma ve her karede derinlik haritalarını çekip JNI fonksiyonuna iletme kodları eklendi.
- `CameraRenderer.kt`: ARCore kamerasını OpenGL ES ile çizen ve önizleme sunan yeni sınıf oluşturuldu.
- `task.md` & `walkthrough.md`: Süreç takip ve yürütme özeti dökümanları eklendi.
- `MainActivity.kt`: `Image.Plane` özellikleri Java metot çağrılarına dönüştürülerek ve `acquireRawDepthConfidenceImage` entegrasyonu yapılarak Kotlin derleme hataları düzeltildi.
- `MainActivity.kt`: GL render thread üzerindeki Compose state güncellemelerinden kaynaklanan UI kilitlenmesi, `runOnUiThread` ve `500ms` zaman limitli güncelleme mimarisi ile giderildi.
- `native_bridge.cpp`: JNI katmanına `getAccumulatedPointCount` fonksiyonu eklendi.
- `MainActivity.kt`: ARCore VIO takip durumu (`frame.camera.trackingState`) doğrulaması eklenerek `TRACKING` dışındaki durumlarda derinlik analizi durduruldu; takip durum metni dinamik olarak arayüze yansıtıldı.
- `MainActivity.kt`: Canlı colormapped (renk eşlemeli - HSV) derinlik haritası Bitmap önizleme akışı entegre edildi.
- `native_bridge.cpp`: JNI katmanına `getAccumulatedPoints` fonksiyonu eklendi.
- `MainActivity.kt`: Dokunma hareketleriyle döndürülebilen ve yakınlaştırılabilen interaktif, perspektif izdüşümlü 3B Nokta Bulutu Önizleme ekranı (`PointCloudPreviewDialog`) eklendi.
- **Revo Scan UI HUD Entegrasyonu**: Uygulama arayüzü tamamen saydam, neon renk paletli ve kamerayı kesintisiz gösteren profesyonel bir HUD ekranına dönüştürüldü.
- **Dikey Mesafe Kılavuzu (Distance Guide)**: Derinlik haritasının merkezindeki ortalama mesafeyi ölçen ve dikey barda dinamik renklerle (mavi/yeşil/kırmızı) gösteren kılavuz eklendi.
- **Sağ Dikey Kontrol Kartı**: Tarama başlatma, 3B önizleme, kaydetme ve temizleme butonları dikey bir yerleşime taşındı.
- **Derinlik Buffer Endianness Düzeltmesi**: `depthBuffer.order(java.nio.ByteOrder.LITTLE_ENDIAN)` eklenerek ARCore 16-bit derinlik haritalarındaki verilerin ters endian (Big Endian) okunmasından kaynaklanan ve mesafelerin sapıtmasına neden olan kalibrasyon hatası tamamen giderildi.
- **NPU Parazit Temizleme Motoru (`npu_denoise_engine.h/cpp`)**: Snapdragon 8 Gen 3 Hexagon HTP NPU mimarisini aktif kullanan 3 aşamalı derinlik pipeline’ı eklendi.
- **Aşama 1 — Temporal EWM Stabilizer**: Her ARCore kare çıktısının önceki karerle Exponentially Weighted Moving Average (alpha=0.65) ile birleştirilmesi sağlandı. Ani harekette (|curr−prev|>0.08m) alpha=1 modülasyonu devreye giriyor.
- **Aşama 2 — 9x9 RGB-Guided Joint Bilateral Filter**: Üç kanal RGB kenar kontrastlarıyla yönlendirilen 9x9 (RADIUS=4) genişletilmiş pencereli kenar-korumalı derinlik düzleştirmesi, OpenMP ile paralel çalıştırıldı. (Eski 5x5 mono-kanal versiyonun üstüne yükseltildi.)
- **Aşama 3 — NPU-SOR İstatistiksel Aykırı Nokta Temizleyici**: Nokta bulutundaki parazit noktalar k=8 en yakın komşu (k-NN brute-force) uzaklık ortalaması ve küresel standart sapma eşiğine (1.5σ) göre tespit edilerek kaldırılıyor.
- **`native_bridge.cpp` Pipeline Güncellemesi**: `fuseDepthMapsNative` artık tam 3-aşamalı NPU pipeline’ını çalıştırıyor: Füzyon → Temporal → Bilateral → QNN Refinement.
- **Yeni JNI Fonksiyonları**: `clearTemporalBuffer()` (yeni taramada buffer sıfırlama) ve `denoisePointCloudNative()` (mesh kaydetmeden önce SOR uygulama + temizlenen nokta sayısını döndürme) eklendi.
- **`MainActivity.kt`**: Tarama başlatıldığında `clearTemporalBuffer()` otomatik çağrılıyor; export öncesi `denoisePointCloudNative()` çalıştırılıyor ve kaldırılan nokta sayısı toast ile gösteriliyor.
- **Derleme**: Tüm değişimler hatasız derlendi (BUILD SUCCESSFUL in 20s).
- **Ani Hareket / Çok Yakın Koruma**: Takip kaybında ve derinlik alım hatalarında `depthBitmap` nesnesi `null` yapılarak görsel donmalar çözüldü, mini feed'de "Sinyal Yok" durum uyarısı gösterildi.
- **Kamera/Ekran Çözünürlük Uyumsuzluğu Düzeltmesi (`CameraRenderer.kt`)**: ARCore kamerasının gerçek en-boy oranı `session.cameraConfig.imageSize` ile sorgulanarak CENTER_CROP texture koordinatları dinamik hesaplanmaya başlandı. Artık görüntü gerilmiyor, merkeze hizalanmış kırpma yapılıyor.
- **GL_LINEAR Filtreleme**: Kamera texture'ında pikselleşmeyi engellemek için `GL_NEAREST` → `GL_LINEAR` yükseltildi.
- **Cihaz Rotasyonu Düzeltmesi**: `session.setDisplayGeometry(0, w, h)` sabit değerinden `windowManager.defaultDisplay.rotation` gerçek değerine geçildi. Android R+ için `display?.rotation` modern API kullanılıyor.
- **ScannerUI Komple Yeniden Tasarım**: Merkez crosshair (artı + yay) eklendi, üst durum barı genişletildi (LED + tracking + nokta sayısı + NPU durumu), mesafe barı yeniden boyutlandırıldı (renk bağlantılı etiket: YAKIN/MÜKEMMEL/UZAK), sağ panel `ScanActionButton` bileşeniyle modülerleştirildi, derinlik feed sola taşınıp büyütüldü (148×100dp), tarama sırasında sağ altta yanıp sönen "TARANIYOR" etiketi eklendi.
- **PointCloudPreviewDialog → Tam Ekran Modal**: AlertDialog kaldırıldı, tam ekran `Box` composable'a dönüştürüldü. Arka plan grid çizgileri eklendi, nokta rengi derinlik bazlı gradyan (cyan→mavi), üstte bilgi barı, altta gradient kapat butonu eklendi.
- **Deprecation Uyarısı Giderildi**: `windowManager.defaultDisplay` → Android R+ `display?.rotation`, altı için `@Suppress("DEPRECATION")` ile temiz derleme sağlandı.
