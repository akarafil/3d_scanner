# Add project specific Proguard rules here.
# By default, the flags in this file are appended to flags specified
# in C:\Users\GameGaraj\AppData\Local\Android\Sdk/proguard/proguard-android-optimize.txt
# You can edit the include path and order by changing the proguardFiles
# directive in build.gradle.kts.
#
# For more details, see
#   http://developer.android.com/guide/developing/tools/proguard.html

# Add any custom rules here.

# ═══════════════════════════════════════════════════════════════════════
# O-1: R8 keep kuralları (isMinifyEnabled = true açıkken runtime crash
# riskini önler). Proje mevcut kütüphanelerine göre eklenmiştir.
# ═══════════════════════════════════════════════════════════════════════

# ── TensorFlow Lite (LiteRT) ─────────────────────────────────────────────
# Interpreter + GPU delegate native bağlar; model dosyaları assets'ten mmap
# ile yüklenir. Obfuscation native JNI symbol aramasını bozabilir.
-keep class org.tensorflow.lite.** { *; }
-keep class org.tensorflow.lite.gpu.** { *; }

# TFLite release R8: isteğe bağlı/eksik sınıflar (TensorAudio AutoValue +
# GpuDelegateFactory ctor yolu). Uygulama bunları hiç kullanmaz; GPU delegate
# zaten başarısız olursa CPU fallback'ine düşer. AGP missing_rules.txt önerisi.
-dontwarn com.google.auto.value.AutoValue
-dontwarn com.google.auto.value.AutoValue$Builder
-dontwarn org.tensorflow.lite.gpu.GpuDelegateFactory$Options
-dontwarn org.tensorflow.lite.gpu.GpuDelegateFactory$Options$GpuBackend

# ── ARCore ───────────────────────────────────────────────────────────────
# ARCore SDK JNI bindings + çalışma zamanı sınıfları (Session, Config,
# Frame, PointCloud, exceptions) — obfuscation GL thread'deki JNI
# çağrılarını bozabilir.
-keep class com.google.ar.core.** { *; }
-keep class com.google.ar.core.exceptions.** { *; }

# ── androidx.lifecycle / ViewModel ───────────────────────────────────────
# B15: Geniş `androidx.lifecycle.**` keep kuralı daraltıldı — yalnızca runtime
# reflection kullanan ViewModel alt sınıfları (ScanViewModel gibi) korunur.
# ViewModelProvider, ViewModel'i Application ctor'undan reflection ile kurar;
# androidx.lifecycle kütüphanesinin kendisi R8 ile daraltılabilir (crash riski yok).
-keep class * extends androidx.lifecycle.ViewModel { *; }
-keepclassmembers class * extends androidx.lifecycle.ViewModel {
    public <init>(android.app.Application);
}

# ── Coil (AsyncImage) ────────────────────────────────────────────────────
# Coil, Compose içinde image loading model'lerini reflect ile kurar.
-keep class coil.** { *; }

# ── ExifInterface ────────────────────────────────────────────────────────
# EXIF metadata okuma/yazma (SfM photogrammetry) — tag tablosu reflection.
-keep class androidx.exifinterface.** { *; }
