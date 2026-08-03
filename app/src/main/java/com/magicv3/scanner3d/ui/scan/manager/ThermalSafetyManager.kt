package com.magicv3.scanner3d.ui.scan.manager

import android.util.Log
import com.magicv3.scanner3d.infra.system.SystemMonitor
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

class ThermalSafetyManager constructor(
    private val systemMonitor: SystemMonitor
) {
    private val _isThermalThrottled = MutableStateFlow(false)
    val isThermalThrottled: StateFlow<Boolean> = _isThermalThrottled.asStateFlow()

    private val _currentSocTemp = MutableStateFlow(0f)
    val currentSocTemp: StateFlow<Float> = _currentSocTemp.asStateFlow()

    private val _isThermalWarned = MutableStateFlow(false)
    val isThermalWarned: StateFlow<Boolean> = _isThermalWarned.asStateFlow()

    fun resetThermalThrottled() {
        _isThermalThrottled.value = false
    }

    fun startMonitoring(scope: CoroutineScope, onEmergencyAbort: () -> Unit) {
        scope.launch {
            systemMonitor.monitorThermal(2000).collect { metrics ->
                val temp = metrics.socTempC
                _currentSocTemp.value = temp

                // Sert abort: yalnızca gerçek EMERGENCY seviyesinde (85°C+) çekim durdurulur.
                val hardThrottled = temp >= THERMAL_ABORT_TEMP_C
                if (hardThrottled && !_isThermalThrottled.value) {
                    _isThermalThrottled.value = true
                    Log.w(TAG, "Thermal limit reached: $temp°C. Aborting capture actions.")
                    onEmergencyAbort()
                } else if (temp < THERMAL_HARD_RECOVERY_TEMP_C && _isThermalThrottled.value) {
                    _isThermalThrottled.value = false
                    Log.i(TAG, "Thermal recovery: $temp°C. Capture actions resumed.")
                }

                // Uyarı: 75°C+ AI frame-skip başlatılır; çekim engellenmez.
                val warned = temp >= THERMAL_WARNING_TEMP_C
                if (warned && !_isThermalWarned.value) {
                    _isThermalWarned.value = true
                    Log.w(TAG, "Thermal warning: $temp°C. Reducing AI processing rate.")
                } else if (temp < THERMAL_WARNING_RECOVERY_TEMP_C && _isThermalWarned.value) {
                    _isThermalWarned.value = false
                    Log.i(TAG, "Thermal warning cleared: $temp°C.")
                }
            }
        }
    }

    companion object {
        private const val TAG = "ThermalSafetyManager"
        const val THERMAL_WARNING_TEMP_C = 75f
        const val THERMAL_ABORT_TEMP_C = 85f
        const val THERMAL_WARNING_RECOVERY_TEMP_C = 70f
        const val THERMAL_HARD_RECOVERY_TEMP_C = 80f
    }
}
