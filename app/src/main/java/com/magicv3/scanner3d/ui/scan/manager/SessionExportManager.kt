package com.magicv3.scanner3d.ui.scan.manager

import android.net.Uri
import android.util.Log
import com.magicv3.scanner3d.domain.depth.PointCloudStore
import com.magicv3.scanner3d.domain.model.ScanSession
import com.magicv3.scanner3d.domain.model.ScanStatus
import com.magicv3.scanner3d.infra.ingestion.IngestionQueue
import com.magicv3.scanner3d.infra.ingestion.IngestionState
import com.magicv3.scanner3d.infra.storage.PlyExporter
import com.magicv3.scanner3d.infra.storage.SessionFrameStore
import com.magicv3.scanner3d.infra.storage.ZipExporter
import com.magicv3.scanner3d.ui.scan.PlyExportState
import com.magicv3.scanner3d.ui.scan.ZipShareState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject


class SessionExportManager constructor(
    private val zipExporter: ZipExporter,
    private val plyExporter: PlyExporter,
    private val sessionFrameStore: SessionFrameStore,
    private val ingestionQueue: IngestionQueue,
    private val pointCloudStore: PointCloudStore
) {
    private val _zipShareState = MutableStateFlow<ZipShareState>(ZipShareState.Idle)
    val zipShareState: StateFlow<ZipShareState> = _zipShareState.asStateFlow()

    private val _plyExportState = MutableStateFlow<PlyExportState>(PlyExportState.Idle)
    val plyExportState: StateFlow<PlyExportState> = _plyExportState.asStateFlow()

    val queueState: StateFlow<IngestionState> = ingestionQueue.queueState

    fun resetIngestionToIdle() {
        ingestionQueue.resetToIdle()
    }


    fun resetZipShareState() {
        _zipShareState.value = ZipShareState.Idle
    }

    fun resetPlyExportState() {
        _plyExportState.value = PlyExportState.Idle
    }

    fun isRenderEngineInstalled(): Boolean = ingestionQueue.isRenderEngineInstalled()

    fun triggerZipShare(session: ScanSession, scope: CoroutineScope) {
        scope.launch {
            _zipShareState.value = ZipShareState.Zipping(session.frameCount)
            runCatching {
                zipExporter.export(session)
            }.onSuccess { result ->
                _zipShareState.value = ZipShareState.Done(result.uri, String.format(java.util.Locale.US, "%.1f MB", result.sizeBytes / 1_000_000.0))
                zipExporter.launchShareSheet(result, session.projectName)
                delay(2000)
                _zipShareState.value = ZipShareState.Idle
            }.onFailure { e ->
                Log.e(TAG, "ZIP export failed", e)
                _zipShareState.value = ZipShareState.Failed(e.message ?: "Bilinmeyen hata")
                delay(2500)
                _zipShareState.value = ZipShareState.Idle
            }
        }
    }

    fun triggerPlyExport(session: ScanSession, scope: CoroutineScope) {
        scope.launch {
            val pointsToExport = pointCloudStore.getPoints()
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
                Log.e(TAG, "PLY export failed", e)
                _plyExportState.value = PlyExportState.Failed(e.message ?: "PLY yazma hatası")
                delay(2000)
                _plyExportState.value = PlyExportState.Idle
            }
        }
    }

    fun enqueue3DRender(session: ScanSession, scope: CoroutineScope, onReport: (String) -> Unit) {
        if (!ingestionQueue.isRenderEngineInstalled()) {
            onReport("⚠ AlgorDroid Engine kurulu değil — M3SP paketi paylaşım yedeğiyle açılacak. Gerçek 3D render için motor kurulmalı.")
            Log.w(TAG, "3D render: AlgorDroid Engine not installed — Share Sheet fallback.")
        }
        scope.launch {
            sessionFrameStore.updateStatus(session.sessionId, ScanStatus.RENDERING)
            ingestionQueue.enqueue(session)
        }
    }

    companion object {
        private const val TAG = "SessionExportManager"
    }
}
