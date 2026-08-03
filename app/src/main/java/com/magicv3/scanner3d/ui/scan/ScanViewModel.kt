package com.magicv3.scanner3d.ui.scan

import android.app.Application
import android.content.Context
import android.graphics.Bitmap
import android.hardware.camera2.CameraManager
import android.hardware.display.DisplayManager
import android.net.Uri
import android.util.Log
import android.view.Display
import android.view.Surface
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.google.ar.core.ArCoreApk
import com.magicv3.scanner3d.domain.ar.CameraPose
import com.magicv3.scanner3d.domain.ar.DepthSourceState
import com.magicv3.scanner3d.domain.depth.DepthMap
import com.magicv3.scanner3d.domain.model.ScanSession
import com.magicv3.scanner3d.domain.model.ScanStatus
import com.magicv3.scanner3d.domain.usecase.Point3D
import com.magicv3.scanner3d.domain.usecase.DepthToPointsUseCase
import com.magicv3.scanner3d.infra.ai.AiServiceLocator
import com.magicv3.scanner3d.infra.ai.YoloInferenceEngine
import com.magicv3.scanner3d.infra.camera.MultiLensCaptureOrchestrator
import com.magicv3.scanner3d.infra.camera.ProCameraCapabilities
import com.magicv3.scanner3d.infra.camera.RawAuxCaptureSession
import com.magicv3.scanner3d.infra.depth.ArCoreDepthSource
import com.magicv3.scanner3d.infra.depth.CameraCharacteristicsIntrinsicsProvider
import com.magicv3.scanner3d.infra.depth.DefaultDepthScaleEstimator
import com.magicv3.scanner3d.infra.depth.TfliteDepthSource
import com.magicv3.scanner3d.infra.ingestion.IngestionQueue
import com.magicv3.scanner3d.infra.storage.SessionFrameStore
import com.magicv3.scanner3d.infra.storage.ZipExporter
import com.magicv3.scanner3d.infra.storage.PlyExporter
import com.magicv3.scanner3d.infra.system.SystemMonitor
import com.magicv3.scanner3d.ui.capture.CaptureState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Collections

enum class AIPreviewMode {
    NONE,
    DEPTH,
    YOLO
}

sealed class PlyExportState {
    data object Idle : PlyExportState()
    data object Exporting : PlyExportState()
    data object Done : PlyExportState()
    data class Failed(val error: String) : PlyExportState()
}

/**
 * B-3: ARCore kullanılabilirlik durumu.
 *
 * ARCore isteğe bağlı (optional) olduğundan desteklenmeyen cihazlarda
 * uygulama kamera çekimine devam edebilir; yalnızca poz takibi/AR overlay
 * kapatılır ve kullanıcıya anlamlı mesaj gösterilir.
 */
sealed class ArCoreAvailabilityState {
    data object Checking : ArCoreAvailabilityState()
    data object Available : ArCoreAvailabilityState()
    data class Unavailable(val reason: String) : ArCoreAvailabilityState()
}

class ScanViewModel(
    application: Application,
    val sessionFrameStore: SessionFrameStore,
    private val activeSession: ScanSession
) : AndroidViewModel(application) {

    private val context = application.applicationContext
    val orchestrator = MultiLensCaptureOrchestrator(context, sessionFrameStore)
    val ingestionQueue = IngestionQueue.getInstance(context)
    private val zipExporter = ZipExporter(context)
    private val plyExporter = PlyExporter(context)
    private val systemMonitor = SystemMonitor(context)

    // H-2 + H-3: AI engine'leri app-scope singleton container'dan (AiServiceLocator)
    // alınır — her oturum ViewModel'i kendi modelini kurmaz (bellek büyümesi önlenir).
    // Model yükleme lazy'dir: ilk erişim onFrameAvailable → Dispatchers.Default içinde
    // olduğundan main thread asla bloklanmaz. Assets'te model yoksa engine'ler dürüst
    // boş/no-op çıktı üretir; SAHTE/mock AI çıktısı üretilmez.
    private val yoloEngine: YoloInferenceEngine by lazy { AiServiceLocator.yoloEngine }

    // H-8 (Strateji C): depth→metre kalibrasyon arayüzü + CameraCharacteristics intrinsikleri.
    // DepthAnything normalize depth üretir; ölçek DefaultDepthScaleEstimator'dan gelir.
    private val depthScaleEstimator = DefaultDepthScaleEstimator()

    // Strateji C: hibrit depth mimarisi.
    //  - ArCoreDepthSource: ana kamera için metrik (metre) depth — birincil kaynak.
    //  - TfliteDepthSource: aux/tele akışı + ARCore fallback için normalize depth (referans).
    private val arCoreDepthSource = ArCoreDepthSource()
    private val tfliteDepthSource: TfliteDepthSource by lazy {
        TfliteDepthSource(
            depthEngine = AiServiceLocator.depthEngine,
            yoloEngine = AiServiceLocator.yoloEngine,
            depthScaleEstimator = depthScaleEstimator,
        )
    }
    private val depthToPointsUseCase = DepthToPointsUseCase(
        depthScaleEstimator = depthScaleEstimator,
        intrinsicsProvider = CameraCharacteristicsIntrinsicsProvider(context),
    )

    // Thread-safe accumulated 3D point cloud list
    private val accumulatedPoints = Collections.synchronizedList(mutableListOf<Point3D>())

    // State flows
    private val _captureState = MutableStateFlow(CaptureState.IDLE)
    val captureState: StateFlow<CaptureState> = _captureState.asStateFlow()

    private val _lastCaptureLog = MutableStateFlow<String?>(null)
    val lastCaptureLog: StateFlow<String?> = _lastCaptureLog.asStateFlow()

    private val _triggerCounter = MutableStateFlow(0)
    val triggerCounter: StateFlow<Int> = _triggerCounter.asStateFlow()

    private val _proControlsEnabled = MutableStateFlow(false)
    val proControlsEnabled: StateFlow<Boolean> = _proControlsEnabled.asStateFlow()

    private val _isSettingsLocked = MutableStateFlow(false)
    val isSettingsLocked: StateFlow<Boolean> = _isSettingsLocked.asStateFlow()

    private val _isoValue = MutableStateFlow(400)
    val isoValue: StateFlow<Int> = _isoValue.asStateFlow()

    private val _shutterFraction = MutableStateFlow(250)
    val shutterFraction: StateFlow<Int> = _shutterFraction.asStateFlow()

    private val _focusDistanceValue = MutableStateFlow(0.0f)
    val focusDistanceValue: StateFlow<Float> = _focusDistanceValue.asStateFlow()

    // Batch-3: EV kompanzasyonu + renk sıcaklığı (WB) pro kontrolleri.
    private val _evValue = MutableStateFlow(0f)
    val evValue: StateFlow<Float> = _evValue.asStateFlow()

    private val _colorTempValue = MutableStateFlow(5500)
    val colorTempValue: StateFlow<Int> = _colorTempValue.asStateFlow()

    // Batch-3: Cihaz yeteneği aralıkları (kapalı kameradan okunur; init içinde IO'da doldurulur).
    private val _isoRange = MutableStateFlow(100..1600)
    val isoRange: StateFlow<IntRange> = _isoRange.asStateFlow()

    private val _evRange = MutableStateFlow(-12..12)
    val evRange: StateFlow<IntRange> = _evRange.asStateFlow()

    private val _evStep = MutableStateFlow(1f / 6f)
    val evStep: StateFlow<Float> = _evStep.asStateFlow()

    private val _multiLensMode = MutableStateFlow(false)
    val multiLensMode: StateFlow<Boolean> = _multiLensMode.asStateFlow()

    private val _showMyScans = MutableStateFlow(false)
    val showMyScans: StateFlow<Boolean> = _showMyScans.asStateFlow()

    private val _openedSession = MutableStateFlow<ScanSession?>(null)
    val openedSession: StateFlow<ScanSession?> = _openedSession.asStateFlow()

    private val _zipShareState = MutableStateFlow<ZipShareState>(ZipShareState.Idle)
    val zipShareState: StateFlow<ZipShareState> = _zipShareState.asStateFlow()

    // AI States
    private val _aiPreviewMode = MutableStateFlow(AIPreviewMode.NONE)
    val aiPreviewMode: StateFlow<AIPreviewMode> = _aiPreviewMode.asStateFlow()

    private val _aiStats = MutableStateFlow<String?>(null)
    val aiStats: StateFlow<String?> = _aiStats.asStateFlow()

    private val _depthHeatmap = MutableStateFlow<Bitmap?>(null)
    val depthHeatmap: StateFlow<Bitmap?> = _depthHeatmap.asStateFlow()

    private val _yoloDetections = MutableStateFlow<List<YoloInferenceEngine.Detection>>(emptyList())
    val yoloDetections: StateFlow<List<YoloInferenceEngine.Detection>> = _yoloDetections.asStateFlow()

    // Point cloud stats
    private val _pointCount = MutableStateFlow(0)
    val pointCount: StateFlow<Int> = _pointCount.asStateFlow()

    private val _plyExportState = MutableStateFlow<PlyExportState>(PlyExportState.Idle)
    val plyExportState: StateFlow<PlyExportState> = _plyExportState.asStateFlow()

    // B-3: ARCore kullanılabilirlik durumu (renderer oluşturma öncesi kontrol).
    private val _arCoreState = MutableStateFlow<ArCoreAvailabilityState>(ArCoreAvailabilityState.Checking)
    val arCoreState: StateFlow<ArCoreAvailabilityState> = _arCoreState.asStateFlow()

    // F2: aktif depth kaynağı durumu (dürüst izleme). ARCore RET_CHECK riski nedeniyle
    // bu cihazda TFLite fallback tek güvenilir depth kaynağıdır; hangi kaynağın gerçekten
    // ürettiği (AR_CORE/TFLITE/NONE) ScanScreen üzerinden ArGlRenderer'a bildirilir.
    private val _depthSourceState = MutableStateFlow<DepthSourceState>(DepthSourceState.NONE)
    val depthSourceState: StateFlow<DepthSourceState> = _depthSourceState.asStateFlow()

    // Thermal Throttling States
    private val _isThermalThrottled = MutableStateFlow(false)
    val isThermalThrottled: StateFlow<Boolean> = _isThermalThrottled.asStateFlow()

    private val _currentSocTemp = MutableStateFlow(0f)
    val currentSocTemp: StateFlow<Float> = _currentSocTemp.asStateFlow()

    private val _isThermalWarned = MutableStateFlow(false)
    val isThermalWarned: StateFlow<Boolean> = _isThermalWarned.asStateFlow()

    private val _yoloModelLoaded = MutableStateFlow(true)
    val yoloModelLoaded: StateFlow<Boolean> = _yoloModelLoaded.asStateFlow()

    @Volatile
    private var isAiProcessing = false

    private var aiFrameSkipCounter = 0

    init {
        viewModelScope.launch {
            orchestrator.bindSession(activeSession)
        }

        // B-3: ARCore kullanılabilirliğini renderer oluşturulmadan önce kontrol et.
        viewModelScope.launch {
            _arCoreState.value = checkArCoreAvailability()
        }

        // Batch-3: Cihaz yeteneği aralıklarını (ISO / EV range / EV step) arka planda oku.
        // getCameraCharacteristics kapalı kamerada da çalışır; bu yüzden tele sensörü
        // açmadan değerleri toplayıp UI'a sunuyoruz. Tüm okumalar runCatching ile
        // fallback'e düşer (100..1600, -12..12, 1/6).
        viewModelScope.launch(Dispatchers.IO) {
            val cameraManager = runCatching {
                context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
            }.getOrNull()
            if (cameraManager != null) {
                val teleId = RawAuxCaptureSession.AUX_TELEPHOTO_ID
                _isoRange.value = ProCameraCapabilities.readSensitivityRange(cameraManager, teleId)
                _evRange.value = ProCameraCapabilities.readAeCompensationRange(cameraManager, teleId)
                _evStep.value = ProCameraCapabilities.readAeCompensationStep(cameraManager, teleId)
                Log.i(TAG, "Pro capability ranges: iso=${_isoRange.value}, ev=${_evRange.value}, step=${_evStep.value}")
            } else {
                Log.w(TAG, "CameraManager alınamadı — pro aralıklar fallback değerlerde kalır.")
            }
        }

        // Periodically monitor temperature for hardware safety checks
        viewModelScope.launch {
            systemMonitor.monitorThermal(2000).collect { metrics ->
                val temp = metrics.socTempC
                _currentSocTemp.value = temp

                // Sert abort: yalnızca gerçek EMERGENCY seviyesinde (85°C+) çekim durdurulur.
                val hardThrottled = temp >= THERMAL_ABORT_TEMP_C
                if (hardThrottled && !_isThermalThrottled.value) {
                    _isThermalThrottled.value = true
                    Log.w(TAG, "Thermal limit reached: $temp°C. Aborting capture actions.")
                    if (_captureState.value == CaptureState.CAPTURING) {
                        _captureState.value = CaptureState.IDLE
                        _lastCaptureLog.value = "❌ Sıcaklık kritik (>=${THERMAL_ABORT_TEMP_C.toInt()}°C)! Çekim durduruldu."
                    }
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

    fun setProControlsEnabled(enabled: Boolean) {
        _proControlsEnabled.value = enabled
    }

    fun setSettingsLocked(locked: Boolean) {
        _isSettingsLocked.value = locked
    }

    fun setIsoValue(value: Int) {
        _isoValue.value = value
    }

    fun setShutterFraction(value: Int) {
        _shutterFraction.value = value
    }

    fun setFocusDistanceValue(value: Float) {
        _focusDistanceValue.value = value
    }

    fun setEvValue(value: Float) {
        _evValue.value = value
    }

    fun setColorTemperature(value: Int) {
        _colorTempValue.value = value
    }

    /**
     * Batch-3: Uygulanan pro ayarların özetini döndürür (durum çipi için).
     *  - Kilitliyken: "ISO {iso} • 1/{shutter}s • F {focus} • WB {kelvin}K"
     *  - Kilitli değilken: "EV {ev} • AF/AE AUTO"
     */
    fun appliedSettingsSummary(): String = if (_isSettingsLocked.value) {
        String.format(
            java.util.Locale.US,
            "ISO %d • 1/%ds • F %.1f • WB %dK",
            _isoValue.value,
            _shutterFraction.value,
            _focusDistanceValue.value,
            _colorTempValue.value
        )
    } else {
        String.format(java.util.Locale.US, "EV %+.1f • AF/AE AUTO", _evValue.value)
    }

    fun setMultiLensMode(enabled: Boolean) {
        _multiLensMode.value = enabled
    }

    fun setShowMyScans(show: Boolean) {
        _showMyScans.value = show
    }

    fun setOpenedSession(session: ScanSession?) {
        _openedSession.value = session
    }

    fun setAiPreviewMode(mode: AIPreviewMode) {
        _aiPreviewMode.value = mode
        if (mode == AIPreviewMode.NONE) {
            _depthHeatmap.value = null
            _yoloDetections.value = emptyList()
            _aiStats.value = null
        } else if (mode == AIPreviewMode.YOLO) {
            // YOLO motoru lazy ve ağırdır (assets mmap + Interpreter + NNAPI delegate);
            // main thread'i kilitlememek için isModelLoaded erişimi arka planda yapılır.
            viewModelScope.launch(Dispatchers.Default) {
                _yoloModelLoaded.value = yoloEngine.isModelLoaded
            }
        }
    }

    fun resetZipShareState() {
        _zipShareState.value = ZipShareState.Idle
    }

    fun resetPlyExportState() {
        _plyExportState.value = PlyExportState.Idle
    }

    fun resetThermalThrottled() {
        _isThermalThrottled.value = false
    }

    /**
     * B-3: ARCore kullanılabilirlik kontrolü.
     * ArCoreApk.checkAvailability() bloklayıcıdır; IO/Default üzerinde çağrılır.
     */
    private suspend fun checkArCoreAvailability(): ArCoreAvailabilityState =
        withContext(Dispatchers.Default) {
            runCatching {
                when (ArCoreApk.getInstance().checkAvailability(context)) {
                    ArCoreApk.Availability.SUPPORTED_INSTALLED,
                    ArCoreApk.Availability.SUPPORTED_APK_TOO_OLD -> ArCoreAvailabilityState.Available
                    ArCoreApk.Availability.SUPPORTED_NOT_INSTALLED ->
                        ArCoreAvailabilityState.Unavailable("ARCore kurulu değil. Poz takibi kapalı; kamera çekimi devam eder.")
                    ArCoreApk.Availability.UNSUPPORTED_DEVICE_NOT_CAPABLE ->
                        ArCoreAvailabilityState.Unavailable("Cihaz ARCore desteklemiyor. Poz takibi kapalı; kamera çekimi devam eder.")
                    else ->
                        ArCoreAvailabilityState.Unavailable("ARCore kullanılamıyor. Poz takibi kapalı.")
                }
            }.getOrElse { e ->
                Log.w(TAG, "ARCore availability check failed", e)
                ArCoreAvailabilityState.Unavailable(e.message ?: "ARCore kullanılamıyor.")
            }
        }

    /**
     * B-3: ArGlRenderer GL thread'inde Session(context) açılamazsa (UnavailableException)
     * UI'a anlamlı durum bildirir — sessiz yutma yok.
     */
    fun reportArCoreUnavailable(reason: String) {
        if (_arCoreState.value !is ArCoreAvailabilityState.Unavailable) {
            Log.w(TAG, "ARCore became unavailable at render time: $reason")
            _arCoreState.value = ArCoreAvailabilityState.Unavailable(reason)
        }
    }

    fun clearAccumulatedPoints() {
        accumulatedPoints.clear()
        _pointCount.value = 0
    }

    fun triggerZipShare(session: ScanSession) {
        viewModelScope.launch {
            _zipShareState.value = ZipShareState.Zipping(session.frameCount)
            runCatching {
                zipExporter.export(session)
            }.onSuccess { result ->
                _zipShareState.value = ZipShareState.Done(result.uri, String.format(java.util.Locale.US, "%.1f MB", result.sizeBytes / 1_000_000.0))
                zipExporter.launchShareSheet(result, session.projectName)
                delay(2000)
                _zipShareState.value = ZipShareState.Idle
            }.onFailure { e ->
                Log.e("ScanViewModel", "ZIP export failed", e)
                _zipShareState.value = ZipShareState.Failed(e.message ?: "Bilinmeyen hata")
                delay(2500)
                _zipShareState.value = ZipShareState.Idle
            }
        }
    }

    fun triggerPlyExport(session: ScanSession) {
        viewModelScope.launch {
            // B4: accumulatedPoints boşsa mock veri üretilip PLY olarak dışa aktarılmaz —
            // üretim build'inde gerçek dışı nokta bulutu dışa aktarmak yerine hata durumu
            // döndürülür (debug'da da aynı dürüst davranış).
            val pointsToExport = synchronized(accumulatedPoints) {
                accumulatedPoints.toList()
            }
            if (pointsToExport.isEmpty()) {
                _plyExportState.value = PlyExportState.Failed(
                    "Henüz nokta bulutu verisi yok. Önce DEPTH modunda tarama yapın."
                )
                delay(2500)
                _plyExportState.value = PlyExportState.Idle
                return@launch
            }

            _plyExportState.value = PlyExportState.Exporting
            runCatching {
                plyExporter.export(session.projectName, pointsToExport)
            }.onSuccess { file ->
                _plyExportState.value = PlyExportState.Done
                plyExporter.launchShareSheet(file, session.projectName)
                delay(2000)
                _plyExportState.value = PlyExportState.Idle
            }.onFailure { e ->
                Log.e("ScanViewModel", "PLY export failed", e)
                _plyExportState.value = PlyExportState.Failed(e.message ?: "PLY yazma hatası")
                delay(2000)
                _plyExportState.value = PlyExportState.Idle
            }
        }
    }

    fun enqueue3DRender(session: ScanSession) {
        // Dürüst ön-kontrol: AlgorDroid Engine kurulu değilse kullanıcıya net bildirim.
        // enqueue(session) yine de çağrılır — M3SP paketi üretilir ve paylaşım yedeğiyle
        // iletilir (fallback davranışı korunur, akış iptal edilmez).
        if (!ingestionQueue.isRenderEngineInstalled()) {
            _lastCaptureLog.value = "⚠ AlgorDroid Engine kurulu değil — M3SP paketi paylaşım yedeğiyle açılacak. Gerçek 3D render için motor kurulmalı."
            Log.w(TAG, "3D render: AlgorDroid Engine not installed — Share Sheet fallback.")
        }
        viewModelScope.launch {
            sessionFrameStore.updateStatus(session.sessionId, ScanStatus.RENDERING)
            ingestionQueue.enqueue(session)
            _openedSession.value = null
        }
    }

    fun resetIngestionToIdle() {
        ingestionQueue.resetToIdle()
    }

    /**
     * ARCore GL thread'inden her karede çağrılır; AI önizleme pipeline'ını
     * Dispatchers.Default üzerinde başlatır.
     *
     * Faz 4 / Strateji C (hibrit depth):
     *  - YUV→NV21→JPEG→Bitmap→rotate zinciri [TfliteDepthSource.bitmapFromImage]'e taşındı;
     *    burada tek kamera Image alımıyla üretilen rotated bitmap hem depth inference
     *    hem de RGB renk örneklemesi için paylaşılır.
     *  - DEPTH modunda kaynak yönlendirmesi: ARCore TRACKING ise metrik depth
     *    ([arCoreDepthSource.acquireDepth]) denenir; null dönerse TFLite (kalibre)
     *    fallback'e düşülür.
     *  - YOLO modu etkilenmez (yoloEngine aynı singleton üzerinden çalışır).
     *
     * O-3 ileri faz notu: TFLite input'u şu an Bitmap üzerinden gidiyor
     * (YUV_420_888 → NV21 → JPEG → Bitmap.decode → rotate → RGB). Asıl
     * verimli çözüm, YUV_420_888 plane'lerini doğrudan RGB float buffer'a
     * kopyalayıp (NV21/JPEG/Bitmap ara katmanlarını atlayarak) TFLite input
     * buffer'ı olarak vermektir — bu, model boyutunda (Depth 518×518, YOLO
     * 640×640) küçük bir çıktı üretir ama kamera çözünürlüğünde (~12MP)
     * büyük ara tahsisleri (NV21 ~18MB, JPEG + full-size Bitmap ~48MB)
     * ortadan kaldırır. Buffer reuse [TfliteDepthSource] içinde korunur;
     * ileri fazda Bitmap.inBitmap havuzu veya doğrudan YUV→RGB dönüşümü önerilir.
     */
    fun onFrameAvailable(frame: com.google.ar.core.Frame) {
        if (isAiProcessing || _aiPreviewMode.value == AIPreviewMode.NONE) return

        // Termal uyarı altında AI inference kare atlar (ısıyı düşürür, çekim engellenmez).
        if (_isThermalWarned.value || _isThermalThrottled.value) {
            aiFrameSkipCounter++
            if (aiFrameSkipCounter % AI_FRAME_SKIP_DIVISOR != 0) return
        }

        // Capture camera pose + tracking state safely on the calling (GL) thread
        // before frame updates — frame verisi asenkron thread'de okunmaz.
        val isTracking = frame.camera.trackingState == com.google.ar.core.TrackingState.TRACKING
        val currentPose = if (isTracking) {
            val pose = frame.camera.pose
            CameraPose(pose.translation, pose.rotationQuaternion)
        } else null

        // Strateji C: ARCore metrik depth GL thread'inde (güvenli) toplanır; hazır
        // değilse fallback kararı coroutine içinde TFLite'a düşer.
        val arDepthMap = if (_aiPreviewMode.value == AIPreviewMode.DEPTH && isTracking) {
            arCoreDepthSource.acquireDepth(frame)
        } else null

        val cameraImage = runCatching { frame.acquireCameraImage() }.getOrNull()
        if (cameraImage == null) {
            isAiProcessing = false
            return
        }
        isAiProcessing = true

        viewModelScope.launch(Dispatchers.Default) {
            try {
                val mode = _aiPreviewMode.value
                val startTime = System.currentTimeMillis()

                if (mode == AIPreviewMode.YOLO) {
                    if (!yoloEngine.isModelLoaded) {
                        _yoloDetections.value = emptyList()
                        _aiStats.value = "YOLOv8: model yüklü değil (assets'e yolov8s.tflite ekleyin)"
                        return@launch
                    }
                    val rotatedBitmap = tfliteDepthSource.bitmapFromImage(cameraImage, currentDepthRotationDegrees()) ?: return@launch
                    val detections = yoloEngine.infer(rotatedBitmap)
                    val inferenceTime = System.currentTimeMillis() - startTime
                    _aiStats.value = "YOLOv8: ${inferenceTime}ms, ACCEL: ${if (yoloEngine.isNpuOrGpuAccelerated) "✓" else "x"}"
                    _yoloDetections.value = detections
                } else if (mode == AIPreviewMode.DEPTH) {
                    val rotatedBitmap = tfliteDepthSource.bitmapFromImage(cameraImage, currentDepthRotationDegrees()) ?: return@launch

                    // Strateji C yönlendirici: ARCore metrik depth hazır değilse TFLite fallback.
                    // TFLite model assets'te yoksa dürüst boş sonuç döner (sahte depth yok); depthMap null ise kullanıcıya net mesaj gösterilir.
                    val depthMap = arDepthMap ?: tfliteDepthSource.depthFromBitmap(rotatedBitmap)

                    // F2: hangi kaynak gerçekten üretti? Dürüst durum — ArGlRenderer'ın
                    // izleme alanına yansır (loglama + callback, UI göstergesi şart değil).
                    _depthSourceState.value = when {
                        arDepthMap != null -> DepthSourceState.AR_CORE
                        depthMap != null -> DepthSourceState.TFLITE
                        else -> DepthSourceState.NONE
                    }

                    if (depthMap == null) {
                        _depthHeatmap.value = null
                        _pointCount.value = accumulatedPoints.size
                        _aiStats.value = "DEPTH: model yüklü değil (assets'e depth_anything_v2_small.tflite ekleyin)"
                        return@launch
                    }
                    val inferenceTime = System.currentTimeMillis() - startTime

                    // Generate world 3D points from depth map + pose (metrik veya normalize).
                    val newPoints = depthToPointsUseCase.execute(depthMap, currentPose, rotatedBitmap)
                    synchronized(accumulatedPoints) {
                        if (accumulatedPoints.size < MAX_ACCUMULATED_POINTS) {
                            accumulatedPoints.addAll(newPoints)
                        }
                    }
                    _pointCount.value = accumulatedPoints.size

                    val heatmap = tfliteDepthSource.depthToColormap(depthMap.depths, depthMap.width, depthMap.height)
                    _depthHeatmap.value = heatmap
                    _aiStats.value = buildDepthStats(depthMap, inferenceTime)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error in AI Frame Pipeline: ${e.message}", e)
            } finally {
                // Camera Image'in tek sahibi bu coroutine'dir (bitmapFromImage kapatmaz).
                // Başarılı/kancelli/hatalı her yolda kapatılır (çifte close runCatching ile yutulur).
                runCatching { cameraImage.close() }
                isAiProcessing = false
            }
        }
    }

    /**
     * DEPTH stat'ına kaynağı yansıtır:
     *  - `"DEPTH: ARCore (metrik)"` — metrik depth hazırsa.
     *  - `"DEPTH: AI (kalibre)"` — TFLite fallback kullanıldıysa.
     * Her iki duruma da ACCEL göstergesi eklenir.
     */
    private fun buildDepthStats(depthMap: DepthMap?, inferenceTimeMs: Long): String {
        val source = when {
            depthMap == null -> "DEPTH: yok"
            depthMap.isMetric -> "DEPTH: ARCore (metrik)"
            else -> "DEPTH: AI (kalibre)"
        }
        val accel = when {
            depthMap == null -> ""
            depthMap.isMetric -> "ACCEL: ✓"
            else -> "ACCEL: ${if (tfliteDepthSource.isAccelerated) "✓" else "x"}"
        }
        return "$source, Pts: ${accumulatedPoints.size}, $accel (${inferenceTimeMs}ms)"
    }

    /**
     * Kamera YUV karesini portre doğrultusuna çeviren dönüş açısı.
     * ARCore back kamera sensor oryantasyonu Honor Magic V3'te 90°'dir;
     * ekran (display) rotasyonuna göre kompanse edilir — telefon yatay
     * tutulduğunda depth/YOLO görüntüsü kaymaz (kayıklık düzeltmesi).
     */
    private fun currentDepthRotationDegrees(): Float {
        val displayManager = runCatching {
            context.getSystemService(DisplayManager::class.java)
        }.getOrNull() ?: return ROTATE_DEGREES_PORTRAIT
        val displayRotation = runCatching {
            displayManager.getDisplay(Display.DEFAULT_DISPLAY)?.rotation
        }.getOrNull() ?: Surface.ROTATION_0
        val displayRot = when (displayRotation) {
            Surface.ROTATION_90 -> 90
            Surface.ROTATION_180 -> 180
            Surface.ROTATION_270 -> 270
            else -> 0
        }
        // (sensorOrientation - displayRotation + 360) % 360 ; sensor 90° kabul (Honor rear).
        return ((90 - displayRot + 360) % 360).toFloat()
    }

    fun triggerCapture(
        pauseArCallback: () -> Unit,
        resumeArCallback: () -> Unit,
        latestCameraPose: CameraPose?
    ) {
        if (_isThermalThrottled.value) {
            _lastCaptureLog.value = "❌ Cihaz kritik ısındı (>=${THERMAL_ABORT_TEMP_C.toInt()}°C). Sıcaklık ${THERMAL_HARD_RECOVERY_TEMP_C.toInt()}°C altına düşene kadar çekim yapılamaz."
            Log.w("ScanViewModel", "Capture blocked due to thermal throttling.")
            return
        }

        if (_captureState.value != CaptureState.IDLE) return

        _triggerCounter.value += 1
        _captureState.value = CaptureState.CAPTURING

        val mode = _multiLensMode.value
        _lastCaptureLog.value = if (mode) "Multi-lens capture starting…" else "Tele burst ×3 starting…"
        Log.i("ScanViewModel", "Capture triggered (Phase 2.1.2 — Mode: ${if (mode) "multi-lens" else "burst"})")

        viewModelScope.launch {
            pauseArCallback()

            try {
                val trans = latestCameraPose?.translation
                val rot = latestCameraPose?.rotationQuaternion

                val mIso = if (_isSettingsLocked.value) _isoValue.value else null
                val mExp = if (_isSettingsLocked.value) (1_000_000_000L / _shutterFraction.value) else null
                val mFoc = if (_isSettingsLocked.value) _focusDistanceValue.value else null
                // EV yalnızca AE AUTO iken uygulanır (kilitliyken AE OFF olduğundan kamera katmanı EV'i yok sayar).
                val mEv = if (!_isSettingsLocked.value) _evValue.value else null
                // WB yalnızca manuel pozlama kilitliyken uygulanır.
                val mWb = if (_isSettingsLocked.value) _colorTempValue.value else null

                val filesOrMap: Any = if (mode) {
                    orchestrator.captureMultiLens(
                        lensIds = listOf(
                            RawAuxCaptureSession.AUX_TELEPHOTO_ID,
                            RawAuxCaptureSession.AUX_ULTRAWIDE_ID
                        ),
                        translation = trans,
                        rotation = rot,
                        manualIso = mIso,
                        manualExposureTimeNs = mExp,
                        manualFocusDistance = mFoc,
                        manualEv = mEv,
                        manualColorTemperature = mWb
                    )
                } else {
                    orchestrator.captureBurst(
                        lensId = RawAuxCaptureSession.AUX_TELEPHOTO_ID,
                        count = 3,
                        translation = trans,
                        rotation = rot,
                        manualIso = mIso,
                        manualExposureTimeNs = mExp,
                        manualFocusDistance = mFoc,
                        manualEv = mEv,
                        manualColorTemperature = mWb
                    )
                }

                val fileCount: Int = if (mode) {
                    @Suppress("UNCHECKED_CAST")
                    val map = filesOrMap as Map<String, File>
                    map.forEach { (k, v) ->
                        val savedFrame = orchestrator.activeSession?.frames?.lastOrNull { it.lensId == k }
                        val frameSize = savedFrame?.bytes ?: 0L
                        Log.d(TAG, "MultiLens[$k] frame saved: ${savedFrame?.file?.name} ($frameSize B)")
                    }
                    map.size
                } else {
                    @Suppress("UNCHECKED_CAST")
                    val files = filesOrMap as List<File>
                    files.forEachIndexed { idx, f ->
                        val savedFrame = orchestrator.activeSession?.frames?.getOrNull(idx)
                        val frameSize = savedFrame?.bytes ?: 0L
                        Log.d(TAG, "Burst[$idx] frame saved: ${savedFrame?.file?.name} ($frameSize B)")
                    }
                    files.size
                }

                _captureState.value = if (fileCount > 0) CaptureState.DONE else CaptureState.ERROR
                _lastCaptureLog.value = when {
                    mode && fileCount == 2 -> "✅ Tele + UW frames saved (multi-lens OK) — EXIF stamped"
                    mode && fileCount == 1 -> "⚠ Only one lens captured (EXIF partial)"
                    !mode && fileCount == 3 -> "✅ 3/3 Tele frames saved — EXIF stamped"
                    !mode && fileCount > 0 -> "⚠ ${fileCount}/3 Tele frames saved — EXIF stamped"
                    else -> "❌ No frames captured"
                }
            } catch (e: Exception) {
                // B-5: capture pipeline'da beklenmedik hata — AR asla paused kalmamalı (finally).
                Log.e(TAG, "Capture pipeline failed", e)
                _captureState.value = CaptureState.ERROR
                _lastCaptureLog.value = "❌ Çekim hatası: ${e.message ?: "Bilinmeyen hata"}"
            } finally {
                // B-5: exception olsa bile AR oturumu her zaman resume edilir.
                resumeArCallback()
            }

            delay(if (_captureState.value == CaptureState.DONE) 600 else 1500)
            _captureState.value = CaptureState.IDLE
        }
    }

    override fun onCleared() {
        super.onCleared()
        // H-3: AI engine'leri app-scope singleton'dır (AiServiceLocator) — ViewModel
        // kapanırken KAPATILMAZ, aksi halde diğer oturumların paylaştığı engine
        // native kaynakları serbest kalır. Yaşam süreleri app ile eşittir.
        // Gelecekte app sonlandırmasında kapatma gerekirse AiServiceLocator'a
        // ayrı bir release() eklenmelidir.
    }

    companion object {
        private const val TAG = "ScanViewModel"

        /** Depth/YOLO input rotate fallback açısı — portre arka kamera sensor 90° (Honor arka). */
        private const val ROTATE_DEGREES_PORTRAIT = 90f

        /** accumulatedPoints için bellek güvenli üst sınır (~300k nokta). */
        private const val MAX_ACCUMULATED_POINTS = 300_000

        // Termal eşikler — Honor / PowerManager fallback'te MODERATE (55°C) ve SEVERE (65°C)
        // kamera+NNAPI yükü altında sık görülür; bu yüzden yalnızca gerçek tehlikede çekim
        // engellenir (85°C+), ara sıcaklıklarda yalnızca AI frame-skip uygulanır.
        private const val THERMAL_WARNING_TEMP_C = 75f
        private const val THERMAL_ABORT_TEMP_C = 85f
        private const val THERMAL_WARNING_RECOVERY_TEMP_C = 70f
        private const val THERMAL_HARD_RECOVERY_TEMP_C = 80f
        private const val AI_FRAME_SKIP_DIVISOR = 3
    }
}
