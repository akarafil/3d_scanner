import java.io.File
import java.util.Properties
import java.util.jar.JarFile

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

// ── Windows Robolectric conscrypt native fix ───────────────────────────
// conscrypt-openjdk-uber jar'ından Windows x86_64 DLL'ini build dizinine
// çıkarır. Test JVM'i bu dizini java.library.path'te arar (bkz. testOptions).
val conscryptNativeDir = layout.buildDirectory.dir("conscrypt-native").get().asFile

val extractConscryptNative = tasks.register("extractConscryptNative") {
    doLast {
        val uberJar = configurations
            .filter { it.name.endsWith("UnitTestRuntimeClasspath") }
            .flatMap { runCatching { it.files }.getOrElse { emptySet() } }
            .firstOrNull { it.name.startsWith("conscrypt-openjdk-uber") }
        if (uberJar == null || !uberJar.exists()) {
            logger.warn("conscrypt-openjdk-uber bulunamadı — conscrypt native DLL extract edilemedi.")
            return@doLast
        }
        JarFile(uberJar).use { jar ->
            val dllName = "conscrypt_openjdk_jni-windows-x86_64.dll"
            val entry = jar.getEntry("META-INF/native/$dllName")
            if (entry == null) {
                logger.warn("conscrypt DLL $dllName jar içinde bulunamadı.")
                return@doLast
            }
            conscryptNativeDir.mkdirs()
            jar.getInputStream(entry).use { input ->
                File(conscryptNativeDir, dllName).outputStream().use { output ->
                    input.copyTo(output)
                }
            }
            logger.lifecycle("conscrypt native DLL extract edildi: ${File(conscryptNativeDir, dllName)}")
        }
    }
}

// ── Release keystore yapılandırması (B5) ───────────────────────────────
// Bilinçli CI/doğrulama kararı: projede gerçek release keystore yokken
// `assembleRelease` debug imzasıyla üretilir (doğrulama/CI imza hatası vermesin).
//
// Mağaza yayını için proje köküne `keystore.properties` eklenmelidir:
//   storeFile=../keystore/release.keystore
//   storePassword=***
//   keyAlias=release
//   keyPassword=***
//
// Dosya yoksa / eksikse yapılandırma güvenli fallback ile DEBUG imzasına düşer.
val keystorePropertiesFile = rootProject.file("keystore.properties")
val keystoreProperties = Properties().apply {
    if (keystorePropertiesFile.exists()) {
        keystorePropertiesFile.inputStream().use { load(it) }
    }
}
// Gerçek keystore tanımlı mı? storeFile dolu VE dosya mevcutsa release imzası kullanılır.
val hasReleaseKeystore = keystorePropertiesFile.exists() &&
    keystoreProperties.getProperty("storeFile") != null &&
    file(keystoreProperties.getProperty("storeFile")).exists()

android {
    namespace = "com.magicv3.scanner3d"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.magicv3.scanner3d"
        minSdk = 28
        targetSdk = 34
        versionCode = 1
        versionName = "2.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables {
            useSupportLibrary = true
        }
    }

    signingConfigs {
        // B5: Gerçek release keystore şablonu — keystore.properties üzerinden okunur.
        // Dosya yoksa bu config boş kalır ve release buildType debug imzasına düşer.
        create("release") {
            if (hasReleaseKeystore) {
                storeFile = file(keystoreProperties.getProperty("storeFile"))
                storePassword = keystoreProperties.getProperty("storePassword")
                keyAlias = keystoreProperties.getProperty("keyAlias")
                keyPassword = keystoreProperties.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            // O-1: R8 minify + resource shrinking.
            // proguard-rules.pro'da TFLite / ARCore / lifecycle / coil / exifinterface
            // keep kuralları tanımlı (runtime crash riskini önler).
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            // B5: Gerçek keystore varsa release imzası, yoksa DEBUG imzası kullanılır.
            // Mağaza yayınından önce keystore.properties doldurulmalıdır (üstteki şablon).
            signingConfig = if (hasReleaseKeystore) {
                signingConfigs.getByName("release")
            } else {
                signingConfigs.getByName("debug")
            }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }
    kotlinOptions {
        jvmTarget = "1.8"
    }
    buildFeatures {
        compose = true
    }
    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
    aaptOptions {
        noCompress("tflite", "lite")
    }

    // Birim testleri için Robolectric + Android kaynakları desteği
    testOptions {
        unitTests {
            isIncludeAndroidResources = true
            isReturnDefaultValues = true
            all { test ->
                // Windows'ta Robolectric android-all, konscrypt sınıflarını gölgeler;
                // uber jar'ın META-INF/native DLL'i otomatik bulunamaz. DLL'i extract
                // edip Windows PATH ortamına + java.library.path'e ekliyoruz.
                test.dependsOn(extractConscryptNative)
                // Windows 8.3 short-name sorunu (ör. GAMEGA~1 vs GameGaraj):
                // Robolectric temp dataDir'i java.io.tmpdir'den türetir; context.cacheDir
                // kısa ad içerebilirken File.canonicalPath uzun ad döner. FileProvider'ın
                // kök eşleşmesi (startsWith) o zaman başarısız olur. Test JVM'in tmpdir'ini
                // kanonik (uzun) yola sabitle.
                test.systemProperty(
                    "java.io.tmpdir",
                    file(System.getProperty("java.io.tmpdir")).canonicalPath
                )
                if (System.getProperty("os.name").startsWith("Windows")) {
                    val sep = File.pathSeparator
                    val existingPath = System.getenv("PATH") ?: ""
                    // Windows'ta System.loadLibrary, java.library.path (PATH türevli)
                    // üzerinden arar; konscrypt DLL dizinini PATH'e ekle.
                    test.environment(
                        "PATH",
                        conscryptNativeDir.absolutePath + sep + existingPath
                    )
                    test.systemProperty("java.library.path", conscryptNativeDir.absolutePath)
                }
            }
        }
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.7.0")
    implementation("androidx.activity:activity-compose:1.8.2")

    // Compose BOM
    val composeBom = platform("androidx.compose:compose-bom:2024.02.00")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("io.coil-kt:coil-compose:2.5.0")

    // Material Design XML (Required for Material3 parent XML themes)
    implementation("com.google.android.material:material:1.12.0")

    // ── O-2: CameraX bağımlılıkları KALDIRILDI ─────────────────────────
    // Tüm kamera akışı Camera2 (RawAuxCaptureSession) + ARCore (ArGlRenderer)
    // üzerinden; androidx.camera import'u hiçbir kod dosyasında yok.

    // Lifecycle Compose — collectAsStateWithLifecycle için
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.7.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.7.0")

    // ExifInterface for JPEG metadata stamping (SfM photogrammetry)
    implementation("androidx.exifinterface:exifinterface:1.3.7")

    // ARCore SDK for Live Point Cloud and camera pose tracking
    implementation("com.google.ar:core:1.41.0")

    // TensorFlow Lite (LiteRT) dependencies for On-Device AI models
    val tfliteVersion = "2.14.0"
    implementation("org.tensorflow:tensorflow-lite:$tfliteVersion")
    implementation("org.tensorflow:tensorflow-lite-gpu:$tfliteVersion")
    implementation("org.tensorflow:tensorflow-lite-support:0.4.4")

    // ── Testing ──────────────────────────────────────────────────────
    // Unit test (JVM) — Robolectric, MockK, coroutines-test, Turbine
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.robolectric:robolectric:4.11.1")
    testImplementation("androidx.test:core-ktx:1.5.0")
    testImplementation("androidx.test.ext:junit-ktx:1.1.5")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.8.1")
    testImplementation("app.cash.turbine:turbine:1.1.0")
    testImplementation("io.mockk:mockk:1.13.10")
    // Android jar'daki org.json stub yerine gerçek implementasyon (plain JUnit için)
    testImplementation("org.json:json:20240303")
    // Windows'ta Robolectric android-all içindeki conscrypt, host native lib'i
    // (conscrypt_openjdk_jni-windows-x86_64) bulamıyor → UnsatisfiedLinkError.
    // konscrypt-openjdk-uber jar'ı native lib'i barındırır; extractConscryptNative
    // görevi DLL'i java.library.path'e ekler. 2.6.1: 2.5.2 DLL'i bu JVM/Windows
    // kombinasyonunda JVM crash'i (STATUS_STACK_BUFFER_OVERRUN) veriyordu.
    testImplementation("org.conscrypt:conscrypt-openjdk-uber:2.6.1")

    // Instrumented test (androidTest)
    androidTestImplementation("androidx.test:runner:1.5.2")
    androidTestImplementation("androidx.test:rules:1.5.0")
    androidTestImplementation("androidx.test:core-ktx:1.5.0")
    androidTestImplementation("androidx.test.ext:junit-ktx:1.1.5")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.5.1")
    androidTestImplementation(platform("androidx.compose:compose-bom:2024.02.00"))
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
    androidTestImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.8.1")
    debugImplementation("androidx.compose.ui:ui-tooling")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}
