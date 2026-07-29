package com.magicv3.scanner3d.infra.permission

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat

/**
 * Kamera izni durumlarını temsil eden sealed benzeri enum.
 *
 * State machine (4 durum, Android permission modelinin tamamı):
 *  NOT_REQUESTED       → Açılış, dialog henüz gösterilmedi
 *  GRANTED             → İzin verilmiş, kameraya geçilebilir
 *  DENIED              → Reddedildi ama tekrar sorulabilir (rationale göster)
 *  PERMANENTLY_DENIED  → "Don't ask again" seçildi → Ayarlara yönlendir
 */
enum class CameraPermissionState {
    NOT_REQUESTED,
    GRANTED,
    DENIED,
    PERMANENTLY_DENIED
}

/**
 * Compose-friendly holder — UI bu iki field'ı kullanır.
 *
 *  state              → Mevcut izin durumu (UI buna göre route olur)
 *  requestPermission  → ActivityResultContracts launcher'ı tetikler
 */
class CameraPermissionStateHolder(
    val state: CameraPermissionState,
    val requestPermission: () -> Unit
)

/**
 * Compose tarafında kamera iznini yöneten state holder.
 *
 * Mimari notu — infra paketinde çünkü framework API'leriyle (ActivityResultContracts,
 * ContextCompat, shouldShowRequestPermissionRationale) doğrudan konuşur. Domain
 * veya UI işi DEĞİL. Phase 2'de ek sensor/izin'ler de buraya gelecek.
 *
 * Lifecycle-aware: Composable composition'a bağlı olarak launcher oluşturulur.
 * Initial state, mevcut izni ContextCompat'ten okur — uygulama yeniden açıldığında
 * zaten verilmişse tekrar dialog göstermez.
 *
 * @param context LocalContext.current — Activity olmalı (shouldShowRequestPermissionRationale için)
 */
@Composable
fun rememberCameraPermissionState(
    context: Context
): CameraPermissionStateHolder {

    // Açılışta "en az bir kez sorduk mu" flag'i — PERMANENTLY_DENIED tespiti için
    var hasRequestedBefore by remember { mutableStateOf(false) }

    // Initial state — uygulama açılışında izin zaten verilmiş olabilir
    var state by remember {
        mutableStateOf(
            if (ContextCompat.checkSelfPermission(
                    context,
                    Manifest.permission.CAMERA
                ) == PackageManager.PERMISSION_GRANTED
            ) {
                CameraPermissionState.GRANTED
            } else {
                CameraPermissionState.NOT_REQUESTED
            }
        )
    }

    // ActivityResultContracts → Lifecycle-aware permission launcher
    // Manual Intent/requestPermissions KULLANMIYORUZ — ActivityResult API Modern Android'de tek doğru yol.
    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        hasRequestedBefore = true
        if (isGranted) {
            state = CameraPermissionState.GRANTED
        } else {
            // shouldShowRequestPermissionRationale false'un iki anlamı:
            //  1) "Don't ask again" seçildi → PERMANENTLY_DENIED
            //  2) Hiç sorulmadı (ama hasRequestedBefore=true artık) → DENIED
            // Aksi (rationale gösterilebilir) → DENIED (tekrar dene)
            val activity = context as? Activity
            val canShowRationale = activity?.shouldShowRequestPermissionRationale(
                Manifest.permission.CAMERA
            ) ?: false
            state = when {
                canShowRationale -> CameraPermissionState.DENIED
                else -> CameraPermissionState.PERMANENTLY_DENIED
            }
        }
    }

    return CameraPermissionStateHolder(
        state = state,
        requestPermission = { launcher.launch(Manifest.permission.CAMERA) }
    )
}
