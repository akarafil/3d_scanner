# implementation.md — Honor Magic V3 On-Device 3D Scanner

[cite_start]Bu doküman, Honor Magic V3 donanımı ve Android NDK mimarisi üzerinde çalışan, bulut bağımsız (on-device) 3B tarama uygulamasının teknik kurulumunu, kütüphane bağımlılıklarını, NDK/C++ mimarisini ve aşamalı kodlama talimatlarını içerir[cite: 93, 115].

---

## 1. Donanım Özellikleri ve Sistem Mimarisi Özeti

* [cite_start]**Hedef Cihaz:** Honor Magic V3 (Model: FCP-AN10 / FCP-N49)[cite: 115, 161].
* [cite_start]**Gövde:** Katlanabilir yapı, açık halde **4.35 mm** gövde kalınlığı[cite: 132].
* [cite_start]**SoC:** Qualcomm SM8650-AB Snapdragon 8 Gen 3 (4 nm)[cite: 142, 221].
  * [cite_start]**CPU:** Kryo CPU (1x 3.3 GHz Cortex-X4 Prime Core, 5x 3.2 GHz Cortex-A720 Performance Cores, 2x 2.3 GHz Cortex-A520 Efficiency Cores)[cite: 143, 216, 217].
  * [cite_start]**GPU:** Adreno 750 (Vulkan 1.3 desteği)[cite: 144, 217, 218].
  * [cite_start]**NPU:** Hexagon NPU (INT4, INT8, INT16, FP16 destekli karma mimari, Hexagon Direct Link ve Micro Tile Inferencing)[cite: 60, 66, 222].
* [cite_start]**Bellek & Depolama:** 12GB/16GB LPDDR5x RAM (4800 MHz) [cite: 146, 220][cite_start], UFS 4.0[cite: 146, 221].
* [cite_start]**Batarya:** 5150 mAh Silicon/Carbon Li-Ion[cite: 158].
* **Kamera Sistemi (Donanımsal ToF Yoktur):**
  * [cite_start]**Geniş (Wide):** 50 MP, f/1.6, 23mm, OIS[cite: 147].
  * [cite_start]**Ultra Geniş (Ultrawide):** 40 MP, f/2.2, 16mm, 112˚ FOV[cite: 147].
  * [cite_start]**Periskop Telefoto:** 50 MP, f/3.0, 90mm, 3.5x optik zoom, OIS[cite: 147].

---

## 2. Geliştirme Ortamı ve Bilgisayar Kurulum Gereksinimleri

### Host İşletim Sistemi
* [cite_start]**Desteklenen:** Linux (Ubuntu 22.04 LTS / 24.04 LTS) [cite: 94][cite_start], macOS Sonoma (Apple Silicon) [cite: 94] [cite_start]veya Windows 11 (WSL2 aktif)[cite: 94].

### Masaüstü Yazılım & SDK Kurulumları

1. **Android Studio & Build Tools:**
   * [cite_start]**Android Studio:** Jellyfish (2023.3.1+) / Koala / Ladybug[cite: 95].
   * [cite_start]**Android SDK Build-Tools:** 34.0.0+ / 35.0.0[cite: 96].
   * [cite_start]**Target / Compile SDK:** API Level 34 (Android 14) veya API Level 35 (Android 15)[cite: 96, 141].
   * [cite_start]**NDK (Native Development Kit):** NDK `r26b` veya `r27` (LLVM Clang 17+ araç zinciri)[cite: 97].
   * [cite_start]**CMake & Ninja:** CMake `3.22.1+`, Ninja `1.10.2+`[cite: 98].

2. **Qualcomm AI Engine Direct (QNN) SDK:**
   * [cite_start]**Sürüm:** Qualcomm QNN SDK v2.20+ (Snapdragon 8 Gen 3 / SM8650 için INT4/INT8/INT16 mixed-precision ve Hexagon Direct Link desteği)[cite: 98, 221, 222].
   * **Çevre Değişkenleri:**
     ```bash
     export QNN_SDK_ROOT=/opt/qualcomm/qnn-sdk-v2.20
     export PATH=$QNN_SDK_ROOT/bin/x86_64-linux-clang:$PATH
     ```

3. **C++ Native Kütüphaneler (NDK Cross-Compiled ARM64-v8a):**
   * [cite_start]**OpenCV C++ Android Pack (v4.9.0+):** `contrib` modülü dahil (ORB, AKAZE, RANSAC, Calib3d, Bundle Adjustment işlemleri için)[cite: 99].
   * [cite_start]**Google Filament Render Engine (v1.50.0+):** Vulkan backend destekli C++ statik kütüphaneleri (`libfilament.a`, `libgltfio.a`)[cite: 100].
   * [cite_start]**Eigen3 (v3.4.0):** Header-only C++ matris ve lineer cebir kütüphanesi[cite: 101].
   * [cite_start]**Open3D Mobile Engine (Custom NDK Port):** Point cloud filtreleme, Voxel Grid Downsampling ve Fast Ball-Pivoting Mesh üretimi için[cite: 102, 118].

4. **Python Model Dönüştürme Ortamı (Host PC):**
   * **Python Sürümü:** 3.10+
   * [cite_start]**Paketler:** `torch>=2.2.0`, `onnx>=1.15.0`, `onnxruntime`, `qnn-tools` (Qualcomm Model Convertor / Quantizer)[cite: 103].

---

## 3. Yapılandırma Dosyaları

### 3.1 Android Gradle Yapılandırması (`app/build.gradle.kts`)

```kotlin
plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "com.magicv3.scanner3d"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.magicv3.scanner3d"
        minSdk = 30 // Android 11 (API 30+) - Concurrent Camera & AHardwareBuffer desteği için
        targetSdk = 34
        versionCode = 1
        versionName = "1.0.0"

        externalNativeBuild {
            cmake {
                cppFlags("-std=c++20", "-O3", "-fopenmp", "-ffast-math")
                arguments(
                    "-DANDROID_STL=c++_shared",
                    "-DANDROID_ARM_NEON=ON",
                    "-DANDROID_ABI=arm64-v8a"
                )
            }
        }

        ndk {
            abiFilters.add("arm64-v8a")
        }
    }

    buildFeatures {
        compose = true
        prefab = true
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

dependencies {
    // ARCore Depth API
    implementation("com.google.ar:core:1.43.0")

    // Jetpack Compose & CameraX/Camera2
    implementation(platform(libs.androidx.compose.bom))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.camera:camera-camera2:1.3.3")
    implementation("androidx.camera:camera-lifecycle:1.3.3")

    // Coroutines & Lifecycle
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.7.0")
}