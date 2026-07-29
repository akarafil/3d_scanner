#include <jni.h>
#include <string>
#include <vector>
#include <android/log.h>
#include "hardware/zerocopy_buffer.h"
#include "npu/qnn_depth_pipeline.h"
#include "npu/npu_denoise_engine.h"
#include "fusion/adaptive_depth_fusion.h"
#include "thermal/thread_affinity.h"
#include <mutex>
#include "mesh/poisson_deferred.h"

#define LOG_TAG "NativeBridge"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)

static QNNDepthEngine g_qnnEngine;
static NpuDenoiseEngine g_denoiseEngine;
static PoissonDeferredReconstruction g_poissonRecon;

extern "C" JNIEXPORT jboolean JNICALL
Java_com_magicv3_scanner3d_MainActivity_initNativeEngine(JNIEnv* env, jobject /* this */) {
    LOGI("Initializing Magic3D Native Engine on Honor Magic V3...");
    bool npuOk = g_qnnEngine.InitializeHTP();
    g_denoiseEngine.Reset(); // NPU denoise pipeline başlat
    LOGI("NpuDenoiseEngine hazır: Temporal + Bilateral(9x9) + SOR pipeline aktif.");
    return static_cast<jboolean>(npuOk);
}

extern "C" JNIEXPORT void JNICALL
Java_com_magicv3_scanner3d_MainActivity_bindThreadAffinity(JNIEnv* env, jobject /* this */, jint roleIndex) {
    ThreadRole role = static_cast<ThreadRole>(roleIndex);
    BindThreadToCores(role);
}

static std::mutex g_cloudMutex;
static std::vector<Point3D> g_accumulatedPointCloud;

static bool g_useObjectROI = false;
static float g_targetDepth = 0.0f;
static float g_depthTolerance = 0.45f; // 45 cm etrafındaki objeyi izole et

extern "C" JNIEXPORT void JNICALL
Java_com_magicv3_scanner3d_MainActivity_setTargetObjectROINative(JNIEnv* env, jobject /* this */, jfloat normX, jfloat normY) {
    std::lock_guard<std::mutex> lock(g_cloudMutex);
    g_useObjectROI = true;
    LOGI("[ROI] Target object focus enabled at U=%.2f, V=%.2f", normX, normY);
}

extern "C" JNIEXPORT void JNICALL
Java_com_magicv3_scanner3d_MainActivity_clearTargetObjectROINative(JNIEnv* env, jobject /* this */) {
    std::lock_guard<std::mutex> lock(g_cloudMutex);
    g_useObjectROI = false;
    g_targetDepth = 0.0f;
    LOGI("[ROI] Target object focus cleared, scanning full scene.");
}

extern "C" JNIEXPORT void JNICALL
Java_com_magicv3_scanner3d_MainActivity_clearAccumulatedPoints(JNIEnv* env, jobject /* this */) {
    std::lock_guard<std::mutex> lock(g_cloudMutex);
    g_accumulatedPointCloud.clear();
    LOGI("Accumulated point cloud cleared.");
}

extern "C" JNIEXPORT jint JNICALL
Java_com_magicv3_scanner3d_MainActivity_getAccumulatedPointCount(JNIEnv* env, jobject /* this */) {
    std::lock_guard<std::mutex> lock(g_cloudMutex);
    return static_cast<jint>(g_accumulatedPointCloud.size());
}


extern "C" JNIEXPORT void JNICALL
Java_com_magicv3_scanner3d_MainActivity_addPointsToAccumulator(
    JNIEnv* env, jobject /* this */,
    jfloatArray xArr, jfloatArray yArr, jfloatArray zArr,
    jfloatArray nxArr, jfloatArray nyArr, jfloatArray nzArr,
    jbyteArray rArr, jbyteArray gArr, jbyteArray bArr, jint size) {
    
    jfloat* x = env->GetFloatArrayElements(xArr, nullptr);
    jfloat* y = env->GetFloatArrayElements(yArr, nullptr);
    jfloat* z = env->GetFloatArrayElements(zArr, nullptr);
    jfloat* nx = env->GetFloatArrayElements(nxArr, nullptr);
    jfloat* ny = env->GetFloatArrayElements(nyArr, nullptr);
    jfloat* nz = env->GetFloatArrayElements(nzArr, nullptr);
    jbyte* r = env->GetByteArrayElements(rArr, nullptr);
    jbyte* g = env->GetByteArrayElements(gArr, nullptr);
    jbyte* b = env->GetByteArrayElements(bArr, nullptr);

    {
        std::lock_guard<std::mutex> lock(g_cloudMutex);
        g_accumulatedPointCloud.reserve(g_accumulatedPointCloud.size() + size);
        for (int i = 0; i < size; ++i) {
            g_accumulatedPointCloud.push_back({
                x[i], y[i], z[i], 
                nx[i], ny[i], nz[i],
                static_cast<uint8_t>(r[i]),
                static_cast<uint8_t>(g[i]),
                static_cast<uint8_t>(b[i])
            });
        }
    }

    env->ReleaseFloatArrayElements(xArr, x, JNI_ABORT);
    env->ReleaseFloatArrayElements(yArr, y, JNI_ABORT);
    env->ReleaseFloatArrayElements(zArr, z, JNI_ABORT);
    env->ReleaseFloatArrayElements(nxArr, nx, JNI_ABORT);
    env->ReleaseFloatArrayElements(nyArr, ny, JNI_ABORT);
    env->ReleaseFloatArrayElements(nzArr, nz, JNI_ABORT);
    env->ReleaseByteArrayElements(rArr, r, JNI_ABORT);
    env->ReleaseByteArrayElements(gArr, g, JNI_ABORT);
    env->ReleaseByteArrayElements(bArr, b, JNI_ABORT);
}

extern "C" JNIEXPORT jfloatArray JNICALL
Java_com_magicv3_scanner3d_MainActivity_getAccumulatedPoints(JNIEnv* env, jobject /* this */) {
    std::vector<Point3D> localCopy;
    {
        std::lock_guard<std::mutex> lock(g_cloudMutex);
        localCopy = g_accumulatedPointCloud;
    }

    int size = localCopy.size();
    jfloatArray result = env->NewFloatArray(size * 3);
    jfloat* body = env->GetFloatArrayElements(result, nullptr);
    for (int i = 0; i < size; ++i) {
        body[i * 3 + 0] = localCopy[i].x;
        body[i * 3 + 1] = localCopy[i].y;
        body[i * 3 + 2] = localCopy[i].z;
    }
    env->ReleaseFloatArrayElements(result, body, 0);
    return result;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_magicv3_scanner3d_MainActivity_exportPointCloudMesh(JNIEnv* env, jobject /* this */, jstring filePath) {
    const char* pathStr = env->GetStringUTFChars(filePath, nullptr);
    std::string outPath(pathStr);
    env->ReleaseStringUTFChars(filePath, pathStr);

    std::vector<Point3D> localCloudCopy;
    {
        std::lock_guard<std::mutex> lock(g_cloudMutex);
        localCloudCopy = g_accumulatedPointCloud;
    }

    bool success = g_poissonRecon.GenerateMeshFromPointCloud(localCloudCopy, outPath);
    return static_cast<jboolean>(success);
}

extern "C" JNIEXPORT void JNICALL
Java_com_magicv3_scanner3d_MainActivity_fuseDepthMapsNative(
    JNIEnv* env, jobject /* this */,
    jfloatArray arcoreDepthArr, jfloatArray arcoreConfArr,
    jfloatArray stereoDepthArr, jbyteArray rgbImgArr,
    jfloatArray outputArr, jint width, jint height) {

    jfloat* arcoreDepth = env->GetFloatArrayElements(arcoreDepthArr, nullptr);
    jfloat* arcoreConf  = env->GetFloatArrayElements(arcoreConfArr,  nullptr);
    jfloat* stereoDepth = env->GetFloatArrayElements(stereoDepthArr, nullptr);
    jbyte*  rgbImg      = env->GetByteArrayElements(rgbImgArr,       nullptr);
    jfloat* output      = env->GetFloatArrayElements(outputArr,       nullptr);

    const uint8_t* rgb = reinterpret_cast<const uint8_t*>(rgbImg);
    const int N = width * height;

    // -----------------------------------------------
    //  Aşama 1: Adaptif Derinlik Füzyonu (ARCore + Stereo)
    // -----------------------------------------------
    std::vector<float> fusedDepth(N);
    FuseDepthMaps(
        arcoreDepth, arcoreConf, stereoDepth,
        rgb, fusedDepth.data(), width, height
    );

    // -----------------------------------------------
    //  Aşama 2: NPU 3-Aşamalı Parazit Temizleme Pipeline'ı
    //    2a. Temporal EWM Stabilizer (kare titreşme bastırma)
    //    2b. Joint Bilateral Filter 9x9 (RGB-guided, kenar korumalı)
    // -----------------------------------------------
    std::vector<float> denoisedDepth(N);
    g_denoiseEngine.ProcessDepthFrame(
        fusedDepth.data(),
        rgb,
        denoisedDepth.data(),
        width, height
    );

    // -----------------------------------------------
    //  Aşama 3: QNN Bilateral Refinement (ek kenar keskinleştirme)
    // -----------------------------------------------
    g_qnnEngine.ExecuteDepthRefinement(
        denoisedDepth.data(), rgb, output, width, height
    );

    env->ReleaseFloatArrayElements(arcoreDepthArr, arcoreDepth, JNI_ABORT);
    env->ReleaseFloatArrayElements(arcoreConfArr,  arcoreConf,  JNI_ABORT);
    env->ReleaseFloatArrayElements(stereoDepthArr, stereoDepth, JNI_ABORT);
    env->ReleaseByteArrayElements(rgbImgArr,       rgbImg,      JNI_ABORT);
    env->ReleaseFloatArrayElements(outputArr,       output,      0);
}

// ============================================================
//  ZERO-COPY NATIVE FRAME PROCESSOR
//  Her karede Kotlin->Native kopyalama yapmadan doğrudan
//  DirectByteBuffer üzerinden NPU denoise + Backprojection yapar.
// ============================================================
extern "C" JNIEXPORT jfloat JNICALL
Java_com_magicv3_scanner3d_MainActivity_processFrameNative(
    JNIEnv* env, jobject /* this */,
    jobject depthDirectBuf, jint depthRowStride, jint depthPixelStride,
    jobject confDirectBuf, jint confRowStride, jint confPixelStride,
    jobject yDirectBuf, jobject uDirectBuf, jobject vDirectBuf,
    jint yRowStride, jint uvRowStride, jint uvPixelStride,
    jfloatArray cameraToWorldArr,
    jfloat fx, jfloat fy, jfloat cx, jfloat cy,
    jint width, jint height,
    jint imgW, jint imgH,
    jboolean isScanning,
    jintArray outDepthPixels,
    jintArray outSegmentedPixels) {

    if (!depthDirectBuf) return 0.0f;

    const uint8_t* depthRaw = static_cast<const uint8_t*>(env->GetDirectBufferAddress(depthDirectBuf));
    const uint8_t* confRaw = confDirectBuf ? static_cast<const uint8_t*>(env->GetDirectBufferAddress(confDirectBuf)) : nullptr;
    const uint8_t* yRaw = yDirectBuf ? static_cast<const uint8_t*>(env->GetDirectBufferAddress(yDirectBuf)) : nullptr;
    const uint8_t* uRaw = uDirectBuf ? static_cast<const uint8_t*>(env->GetDirectBufferAddress(uDirectBuf)) : nullptr;
    const uint8_t* vRaw = vDirectBuf ? static_cast<const uint8_t*>(env->GetDirectBufferAddress(vDirectBuf)) : nullptr;

    int N = width * height;
    std::vector<float> arcoreDepth(N);
    std::vector<float> arcoreConf(N, 1.0f);
    std::vector<uint8_t> rgbImg(N * 3, 128);

    // 1. Convert Depth & Confidence
    for (int y = 0; y < height; ++y) {
        for (int x = 0; x < width; ++x) {
            int idx = y * width + x;
            int depthByteIdx = y * depthRowStride + x * depthPixelStride;
            uint16_t mm = *reinterpret_cast<const uint16_t*>(depthRaw + depthByteIdx);
            arcoreDepth[idx] = static_cast<float>(mm) / 1000.0f;

            if (confRaw) {
                int confByteIdx = y * confRowStride + x * confPixelStride;
                arcoreConf[idx] = static_cast<float>(confRaw[confByteIdx]) / 255.0f;
            }
        }
    }

    // 2. Convert YUV to RGB for Depth Map Resolution
    if (yRaw && uRaw && vRaw && imgW > 0 && imgH > 0) {
        for (int y = 0; y < height; ++y) {
            for (int x = 0; x < width; ++x) {
                int camX = std::clamp(static_cast<int>(static_cast<float>(x) / width * imgW), 0, imgW - 1);
                int camY = std::clamp(static_cast<int>(static_cast<float>(y) / height * imgH), 0, imgH - 1);

                int yIdx = camY * yRowStride + camX;
                int uvIdx = (camY / 2) * uvRowStride + (camX / 2) * uvPixelStride;

                float yVal = static_cast<float>(yRaw[yIdx]);
                float uVal = static_cast<float>(uRaw[uvIdx]) - 128.0f;
                float vVal = static_cast<float>(vRaw[uvIdx]) - 128.0f;

                int r = std::clamp(static_cast<int>(yVal + 1.370705f * vVal), 0, 255);
                int g = std::clamp(static_cast<int>(yVal - 0.337633f * uVal - 0.698001f * vVal), 0, 255);
                int b = std::clamp(static_cast<int>(yVal + 1.732446f * uVal), 0, 255);

                int idx = (y * width + x) * 3;
                rgbImg[idx + 0] = static_cast<uint8_t>(r);
                rgbImg[idx + 1] = static_cast<uint8_t>(g);
                rgbImg[idx + 2] = static_cast<uint8_t>(b);
            }
        }
    }

    // 3. Process Depth Frame (NPU / Vulkan / OpenMP Denoise Pipeline)
    std::vector<float> fusedOutput(N);
    g_denoiseEngine.ProcessDepthFrame(
        arcoreDepth.data(),
        rgbImg.data(),
        fusedOutput.data(),
        width, height
    );

    // Calculate center distance
    int centerX = width / 2;
    int centerY = height / 2;
    float centerDist = fusedOutput[centerY * width + centerX];

    // 3a. Generate Depth Heatmap Pixels for Panel 2
    if (outDepthPixels) {
        jint* depthPix = env->GetIntArrayElements(outDepthPixels, nullptr);
        for (int i = 0; i < N; ++i) {
            float d = fusedOutput[i];
            if (d <= 0.1f) {
                depthPix[i] = 0xFF000000;
            } else {
                float norm = std::clamp((d - 0.1f) / 2.5f, 0.0f, 1.0f);
                uint8_t r = static_cast<uint8_t>(255.0f * norm);
                uint8_t g = static_cast<uint8_t>(255.0f * (1.0f - std::abs(norm - 0.5f) * 2.0f));
                uint8_t b = static_cast<uint8_t>(255.0f * (1.0f - norm));
                depthPix[i] = 0xFF000000 | (r << 16) | (g << 8) | b;
            }
        }
        env->ReleaseIntArrayElements(outDepthPixels, depthPix, 0);
    }

    // 3b. Generate Segmented/Isolated Object Pixels for Panel 3 (Background Removed)
    if (outSegmentedPixels) {
        jint* segPix = env->GetIntArrayElements(outSegmentedPixels, nullptr);
        for (int i = 0; i < N; ++i) {
            float d = fusedOutput[i];
            bool isTarget = true;
            if (g_useObjectROI && g_targetDepth > 0.1f) {
                if (std::abs(d - g_targetDepth) > g_depthTolerance) {
                    isTarget = false;
                }
            }
            if (d > 0.1f && isTarget) {
                uint8_t r = rgbImg[i * 3 + 0];
                uint8_t g = rgbImg[i * 3 + 1];
                uint8_t b = rgbImg[i * 3 + 2];
                segPix[i] = 0xFF000000 | (r << 16) | (g << 8) | b;
            } else {
                segPix[i] = 0xFF000000; // Arka plan silindi!
            }
        }
        env->ReleaseIntArrayElements(outSegmentedPixels, segPix, 0);
    }

    // If ROI is requested and targetDepth is not yet locked, lock it from touch point or center
    if (g_useObjectROI && g_targetDepth <= 0.0f) {
        g_targetDepth = centerDist > 0.1f ? centerDist : 1.0f;
        LOGI("[ROI] Auto-locked target depth to %.2f meters", g_targetDepth);
    }

    // 4. If Scanning: Backproject + Compute Normals + Accumulate in C++
    if (isScanning && cameraToWorldArr) {
        jfloat* c2w = env->GetFloatArrayElements(cameraToWorldArr, nullptr);

        int step = 8;
        std::vector<Point3D> newPoints;
        newPoints.reserve((height / step) * (width / step));

        for (int py = 0; py < height; py += step) {
            for (int px = 0; px < width; px += step) {
                int idx = py * width + px;
                float depth = fusedOutput[idx];

                if (depth > 0.1f && depth < 5.0f) {
                    // ARKA PLAN SİLME (Background Removal Verification)
                    if (g_useObjectROI && g_targetDepth > 0.1f) {
                        float distDiff = std::abs(depth - g_targetDepth);
                        if (distDiff > g_depthTolerance) {
                            continue; // Objenin arkasında veya çok önünde kalan nesneleri atla
                        }
                    }
                    float xCam = (px - cx) * depth / fx;
                    float yCam = (py - cy) * depth / fy;
                    float zCam = depth;

                    float xWorld = c2w[0] * xCam + c2w[4] * yCam + c2w[8] * zCam + c2w[12];
                    float yWorld = c2w[1] * xCam + c2w[5] * yCam + c2w[9] * zCam + c2w[13];
                    float zWorld = c2w[2] * xCam + c2w[6] * yCam + c2w[10] * zCam + c2w[14];

                    // Gradient-based Normal calculation
                    float rightDepth = (px + step < width) ? fusedOutput[py * width + px + step] : depth;
                    float downDepth = (py + step < height) ? fusedOutput[(py + step) * width + px] : depth;

                    float p1x = (px + step - cx) * rightDepth / fx;
                    float p1y = yCam;
                    float p1z = rightDepth;

                    float p2x = xCam;
                    float p2y = (py + step - cy) * downDepth / fy;
                    float p2z = downDepth;

                    float v1x = p1x - xCam, v1y = p1y - yCam, v1z = p1z - zCam;
                    float v2x = p2x - xCam, v2y = p2y - yCam, v2z = p2z - zCam;

                    float nxCam = v1y * v2z - v1z * v2y;
                    float nyCam = v1z * v2x - v1x * v2z;
                    float nzCam = v1x * v2y - v1y * v2x;

                    float len = std::sqrt(nxCam * nxCam + nyCam * nyCam + nzCam * nzCam);
                    if (len > 0.0001f) {
                        nxCam /= len; nyCam /= len; nzCam /= len;
                    } else {
                        nxCam = 0.0f; nyCam = 0.0f; nzCam = -1.0f;
                    }

                    float nxWorld = c2w[0] * nxCam + c2w[4] * nyCam + c2w[8] * nzCam;
                    float nyWorld = c2w[1] * nxCam + c2w[5] * nyCam + c2w[9] * nzCam;
                    float nzWorld = c2w[2] * nxCam + c2w[6] * nyCam + c2w[10] * nzCam;

                    int rgbIdx = (py * width + px) * 3;
                    uint8_t r = rgbImg[rgbIdx + 0];
                    uint8_t g = rgbImg[rgbIdx + 1];
                    uint8_t b = rgbImg[rgbIdx + 2];

                    newPoints.push_back({
                        xWorld, yWorld, zWorld,
                        nxWorld, nyWorld, nzWorld,
                        r, g, b
                    });
                }
            }
        }

        env->ReleaseFloatArrayElements(cameraToWorldArr, c2w, JNI_ABORT);

        if (!newPoints.empty()) {
            std::lock_guard<std::mutex> lock(g_cloudMutex);
            g_accumulatedPointCloud.insert(g_accumulatedPointCloud.end(), newPoints.begin(), newPoints.end());
        }
    }

    return centerDist;
}

// -----------------------------------------------
//  Temporal buffer sıfırlama (yeni tarama başladığında)
// -----------------------------------------------
extern "C" JNIEXPORT void JNICALL
Java_com_magicv3_scanner3d_MainActivity_clearTemporalBuffer(JNIEnv* env, jobject /* this */) {
    g_denoiseEngine.Reset();
    LOGI("[NPU] Temporal derinlik buffer'ı sıfırlandı.");
}

// -----------------------------------------------
//  Nokta bulutu üzerinde SOR parazit temizleyici
//  (mesh kaydetmeden önce çağrılır)
// -----------------------------------------------
extern "C" JNIEXPORT jint JNICALL
Java_com_magicv3_scanner3d_MainActivity_denoisePointCloudNative(JNIEnv* env, jobject /* this */) {
    std::lock_guard<std::mutex> lock(g_cloudMutex);
    int before = static_cast<int>(g_accumulatedPointCloud.size());
    g_denoiseEngine.DenoisePointCloud(g_accumulatedPointCloud);
    int after = static_cast<int>(g_accumulatedPointCloud.size());
    LOGI("[NPU-SOR] %d → %d nokta (%d parazit temizlendi).", before, after, before - after);
    return static_cast<jint>(before - after); // Temizlenen nokta sayısını döndür
}
