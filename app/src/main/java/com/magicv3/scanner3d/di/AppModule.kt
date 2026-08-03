package com.magicv3.scanner3d.di

import android.content.Context
import com.magicv3.scanner3d.infra.storage.SessionFrameStore
import com.magicv3.scanner3d.infra.storage.ZipExporter
import com.magicv3.scanner3d.infra.storage.PlyExporter
import com.magicv3.scanner3d.infra.storage.MeshRepository
import com.magicv3.scanner3d.infra.storage.CacheCleaner
import com.magicv3.scanner3d.infra.ingestion.IngestionQueue
import com.magicv3.scanner3d.infra.system.SystemMonitor
import com.magicv3.scanner3d.infra.ai.DepthInferenceEngine
import com.magicv3.scanner3d.infra.ai.YoloInferenceEngine
import com.magicv3.scanner3d.domain.depth.DepthScaleEstimator
import com.magicv3.scanner3d.infra.depth.DefaultDepthScaleEstimator
import com.magicv3.scanner3d.domain.usecase.DepthToPointsUseCase
import com.magicv3.scanner3d.infra.depth.CameraCharacteristicsIntrinsicsProvider
import com.magicv3.scanner3d.infra.depth.TfliteDepthSource
import com.magicv3.scanner3d.infra.depth.ArCoreDepthSource
import com.magicv3.scanner3d.domain.depth.PointCloudStore
import com.magicv3.scanner3d.ui.scan.manager.AIInferenceManager
import com.magicv3.scanner3d.ui.scan.manager.CameraCaptureManager
import com.magicv3.scanner3d.ui.scan.manager.SessionExportManager
import com.magicv3.scanner3d.ui.scan.manager.ThermalSafetyManager
import com.magicv3.scanner3d.infra.camera.MultiLensCaptureOrchestrator
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton


@Module
@InstallIn(SingletonComponent::class)
object AppModule {

    @Provides
    @Singleton
    fun provideSessionFrameStore(@ApplicationContext context: Context): SessionFrameStore {
        return SessionFrameStore(context)
    }

    @Provides
    @Singleton
    fun provideIngestionQueue(
        @ApplicationContext context: Context,
        sessionFrameStore: SessionFrameStore
    ): IngestionQueue {
        return IngestionQueue.getInstance(context, sessionFrameStore)
    }


    @Provides
    @Singleton
    fun provideZipExporter(@ApplicationContext context: Context): ZipExporter {
        return ZipExporter(context)
    }

    @Provides
    @Singleton
    fun providePlyExporter(@ApplicationContext context: Context): PlyExporter {
        return PlyExporter(context)
    }

    @Provides
    @Singleton
    fun provideSystemMonitor(@ApplicationContext context: Context): SystemMonitor {
        return SystemMonitor(context)
    }

    @Provides
    @Singleton
    fun provideMeshRepository(@ApplicationContext context: Context): MeshRepository {
        return MeshRepository(context)
    }

    @Provides
    @Singleton
    fun provideCacheCleaner(@ApplicationContext context: Context): CacheCleaner {
        return CacheCleaner(context)
    }

    @Provides
    @Singleton
    fun provideDepthInferenceEngine(@ApplicationContext context: Context): DepthInferenceEngine {
        return DepthInferenceEngine(context)
    }

    @Provides
    @Singleton
    fun provideYoloInferenceEngine(@ApplicationContext context: Context): YoloInferenceEngine {
        return YoloInferenceEngine(context)
    }

    @Provides
    @Singleton
    fun provideDepthScaleEstimator(): DepthScaleEstimator {
        return DefaultDepthScaleEstimator()
    }

    @Provides
    @Singleton
    fun provideCameraCharacteristicsIntrinsicsProvider(@ApplicationContext context: Context): CameraCharacteristicsIntrinsicsProvider {
        return CameraCharacteristicsIntrinsicsProvider(context)
    }

    @Provides
    @Singleton
    fun provideDepthToPointsUseCase(
        depthScaleEstimator: DepthScaleEstimator,
        intrinsicsProvider: CameraCharacteristicsIntrinsicsProvider
    ): DepthToPointsUseCase {
        return DepthToPointsUseCase(depthScaleEstimator, intrinsicsProvider)
    }

    @Provides
    @Singleton
    fun provideArCoreDepthSource(): ArCoreDepthSource {
        return ArCoreDepthSource()
    }

    @Provides
    @Singleton
    fun provideTfliteDepthSource(
        depthEngine: DepthInferenceEngine,
        yoloEngine: YoloInferenceEngine,
        depthScaleEstimator: DepthScaleEstimator
    ): TfliteDepthSource {
        return TfliteDepthSource(depthEngine, yoloEngine, depthScaleEstimator)
    }

    @Provides
    @Singleton
    fun providePointCloudStore(): PointCloudStore {
        return PointCloudStore()
    }

    @Provides
    fun provideThermalSafetyManager(systemMonitor: SystemMonitor): ThermalSafetyManager {
        return ThermalSafetyManager(systemMonitor)
    }

    @Provides
    fun provideSessionExportManager(
        zipExporter: ZipExporter,
        plyExporter: PlyExporter,
        sessionFrameStore: SessionFrameStore,
        ingestionQueue: IngestionQueue,
        pointCloudStore: PointCloudStore
    ): SessionExportManager {
        return SessionExportManager(zipExporter, plyExporter, sessionFrameStore, ingestionQueue, pointCloudStore)
    }

    @Provides
    fun provideCameraCaptureManager(
        @ApplicationContext context: Context,
        orchestrator: MultiLensCaptureOrchestrator
    ): CameraCaptureManager {
        return CameraCaptureManager(context, orchestrator)
    }

    @Provides
    fun provideAIInferenceManager(
        yoloEngine: YoloInferenceEngine,
        tfliteDepthSource: TfliteDepthSource,
        arCoreDepthSource: ArCoreDepthSource,
        depthToPointsUseCase: DepthToPointsUseCase,
        pointCloudStore: PointCloudStore
    ): AIInferenceManager {
        return AIInferenceManager(yoloEngine, tfliteDepthSource, arCoreDepthSource, depthToPointsUseCase, pointCloudStore)
    }
}

