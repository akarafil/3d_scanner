#include <jni.h>
#include <string>
#include <vector>
#include <android/log.h>
#include <arm_neon.h>
#include "hardware/zerocopy_buffer.h"
#include "npu/npu_denoise_engine.h"
#include "thermal/thread_affinity.h"
#include <mutex>
#include "mesh/poisson_deferred.h"

#define LOG_TAG "NativeBridge"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)

static NpuDenoiseEngine g_denoiseEngine;
static PoissonDeferredReconstruction g_poissonRecon;

extern "C" JNIEXPORT jboolean JNICALL
Java_com_magicv3_scanner3d_MainActivity_initNativeEngine(JNIEnv* env, jobject /* this */) {
    LOGI("Initializing Magic3D Native Engine on Honor Magic V3...");
    g_denoiseEngine.Reset(); // NPU denoise pipeline başlat
    LOGI("NpuDenoiseEngine hazır: Temporal + Bilateral(5x5) + SOR pipeline aktif.");
    return static_cast<jboolean>(true);
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
static float g_roiTargetU = 0.5f;
static float g_roiTargetV = 0.5f;

extern "C" JNIEXPORT void JNICALL
Java_com_magicv3_scanner3d_MainActivity_setTargetObjectROINative(JNIEnv* env, jobject /* this */, jfloat normX, jfloat normY) {
    std::lock_guard<std::mutex> lock(g_cloudMutex);
    g_useObjectROI = true;
    g_targetDepth = 0.0f; // Force recalculation
    g_roiTargetU = normX;
    g_roiTargetV = normY;
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


extern "C" JNIEXPORT jfloatArray JNICALL
Java_com_magicv3_scanner3d_MainActivity_getAccumulatedPoints(JNIEnv* env, jobject /* this */) {
    std::vector<Point3D> localCloudCopy;
    {
        std::lock_guard<std::mutex> lock(g_cloudMutex);
        localCloudCopy = g_accumulatedPointCloud;
    } // lock released

    jfloatArray result = env->NewFloatArray(localCloudCopy.size() * 3);
    if (!localCloudCopy.empty()) {
        std::vector<float> flat(localCloudCopy.size() * 3);
        for (size_t i = 0; i < localCloudCopy.size(); ++i) {
            flat[i * 3 + 0] = localCloudCopy[i].x;
            flat[i * 3 + 1] = localCloudCopy[i].y;
            flat[i * 3 + 2] = localCloudCopy[i].z;
        }
        env->SetFloatArrayRegion(result, 0, flat.size(), flat.data());
    }
    return result;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_magicv3_scanner3d_MainActivity_exportPointCloudMesh(JNIEnv* env, jobject /* this */, jstring filePath) {
    const char* pathStr = env->GetStringUTFChars(filePath, nullptr);
    std::string outPath(pathStr);
    env->ReleaseStringUTFChars(filePath, pathStr);

    // B01: Ağır mesh oluşturma işlemini Cortex-X4 Prime çekirdeğine bağla
    BindThreadToCores(ThreadRole::POISSON_RECON);

    std::vector<Point3D> localCloudCopy;
    {
        std::lock_guard<std::mutex> lock(g_cloudMutex);
        localCloudCopy = g_accumulatedPointCloud;
    }

    bool success = g_poissonRecon.GenerateMeshFromPointCloud(localCloudCopy, outPath);

    // R04: Export bittikten sonra Prime Core (Cortex-X4) affinity kilidini serbest bırak
    BindThreadToCores(ThreadRole::ALL_CORES);

    return static_cast<jboolean>(success);
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
    
    // Per-frame bellek atıklarını (heap allocation churn) önlemek için static thread_local kullanım
    static thread_local std::vector<float> arcoreDepth;
    static thread_local std::vector<float> arcoreConf;
    static thread_local std::vector<uint8_t> rgbImg;
    static thread_local std::vector<float> fusedOutput;

    arcoreDepth.assign(N, 0.0f);
    arcoreConf.assign(N, 1.0f);
    rgbImg.assign(N * 3, 128);
    fusedOutput.assign(N, 0.0f);

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

    // 2. Convert YUV to RGB for Depth Map Resolution (SIMD NEON Optimized)
    if (yRaw && uRaw && vRaw && imgW > 0 && imgH > 0) {
        // Önceden hesaplanmış çarpanlar (B11: 1.370705f * 1024 = 1403.6 -> 1404)
        const int16x8_t c1370 = vdupq_n_s16(1404);
        const int16x8_t c337 = vdupq_n_s16(338);
        const int16x8_t c698 = vdupq_n_s16(698);
        const int16x8_t c1732 = vdupq_n_s16(1732);
        const int16x8_t const128 = vdupq_n_s16(128);

        for (int y = 0; y < height; ++y) {
            int camY = std::clamp(static_cast<int>(static_cast<float>(y) / height * imgH), 0, imgH - 1);
            int yRowOffset = camY * yRowStride;
            int uvRowOffset = (camY / 2) * uvRowStride;

            // 8 piksel bloklar halinde işle
            int x = 0;
            for (; x <= width - 8; x += 8) {
                int16_t yVals[8], uVals[8], vVals[8];
                for (int i = 0; i < 8; ++i) {
                    int camX = std::clamp(static_cast<int>(static_cast<float>(x + i) / width * imgW), 0, imgW - 1);
                    yVals[i] = yRaw[yRowOffset + camX];
                    uVals[i] = uRaw[uvRowOffset + (camX / 2) * uvPixelStride];
                    vVals[i] = vRaw[uvRowOffset + (camX / 2) * uvPixelStride];
                }

                int16x8_t yV = vld1q_s16(yVals);
                int16x8_t uV = vsubq_s16(vld1q_s16(uVals), const128);
                int16x8_t vV = vsubq_s16(vld1q_s16(vVals), const128);

                // R = Y + 1.370705 * V
                int16x8_t rV = vqaddq_s16(yV, vshrq_n_s16(vmulq_s16(vV, c1370), 10));
                // G = Y - 0.337633 * U - 0.698001 * V
                int16x8_t gV = vqsubq_s16(yV, vshrq_n_s16(vqaddq_s16(vmulq_s16(uV, c337), vmulq_s16(vV, c698)), 10));
                // B = Y + 1.732446 * U
                int16x8_t bV = vqaddq_s16(yV, vshrq_n_s16(vmulq_s16(uV, c1732), 10));

                uint8x8_t r8 = vqmovun_s16(rV);
                uint8x8_t g8 = vqmovun_s16(gV);
                uint8x8_t b8 = vqmovun_s16(bV);

                uint8x8x3_t rgb;
                rgb.val[0] = r8;
                rgb.val[1] = g8;
                rgb.val[2] = b8;

                vst3_u8(&rgbImg[(y * width + x) * 3], rgb);
            }

            // Kalan pikseller (genelde genişlik 8'in katı değilse)
            for (; x < width; ++x) {
                int camX = std::clamp(static_cast<int>(static_cast<float>(x) / width * imgW), 0, imgW - 1);
                int yIdx = yRowOffset + camX;
                int uvIdx = uvRowOffset + (camX / 2) * uvPixelStride;

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

    // 3. Process Depth Frame (GPU / CPU Denoise Pipeline)
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

    // 3a. Generate Depth Heatmap Pixels for Panel 2 (B03: Rotated 90 degrees clockwise in C++)
    if (outDepthPixels) {
        jint* depthPix = env->GetIntArrayElements(outDepthPixels, nullptr);
        for (int y = 0; y < height; ++y) {
            for (int x = 0; x < width; ++x) {
                float d = fusedOutput[y * width + x];
                int targetX = height - 1 - y;
                int targetY = x;
                int targetIdx = targetY * height + targetX;

                uint32_t color = 0xFF000000;
                if (d > 0.1f) {
                    float norm = std::clamp((d - 0.1f) / 2.5f, 0.0f, 1.0f);
                    uint8_t r = static_cast<uint8_t>(255.0f * norm);
                    uint8_t g = static_cast<uint8_t>(255.0f * (1.0f - std::abs(norm - 0.5f) * 2.0f));
                    uint8_t b = static_cast<uint8_t>(255.0f * (1.0f - norm));
                    color = 0xFF000000 | (r << 16) | (g << 8) | b;
                }
                depthPix[targetIdx] = color;
            }
        }
        env->ReleaseIntArrayElements(outDepthPixels, depthPix, 0);
    }

    // 3b. Generate Segmented/Isolated Object Pixels for Panel 3 (B03: Rotated 90 degrees clockwise in C++)
    if (outSegmentedPixels) {
        jint* segPix = env->GetIntArrayElements(outSegmentedPixels, nullptr);
        for (int y = 0; y < height; ++y) {
            for (int x = 0; x < width; ++x) {
                float d = fusedOutput[y * width + x];
                int targetX = height - 1 - y;
                int targetY = x;
                int targetIdx = targetY * height + targetX;

                bool isTarget = true;
                if (g_useObjectROI && g_targetDepth > 0.1f) {
                    if (std::abs(d - g_targetDepth) > g_depthTolerance) {
                        isTarget = false;
                    }
                }
                uint32_t color = 0xFF000000;
                if (d > 0.1f && isTarget) {
                    uint8_t r = rgbImg[(y * width + x) * 3 + 0];
                    uint8_t g = rgbImg[(y * width + x) * 3 + 1];
                    uint8_t b = rgbImg[(y * width + x) * 3 + 2];
                    color = 0xFF000000 | (r << 16) | (g << 8) | b;
                } else {
                    color = 0xFF000000; // Arka plan silindi!
                }
                segPix[targetIdx] = color;
            }
        }
        env->ReleaseIntArrayElements(outSegmentedPixels, segPix, 0);
    }

    // If ROI is requested and targetDepth is not yet locked, lock it from touch point
    if (g_useObjectROI && g_targetDepth <= 0.0f) {
        // UI is portrait, but depth buffer is landscape (rotated 90 clockwise in UI)
        // landscape_X = normV * width
        // landscape_Y = (1.0f - normU) * height
        int targetX = std::clamp(static_cast<int>(g_roiTargetV * width), 0, width - 1);
        int targetY = std::clamp(static_cast<int>((1.0f - g_roiTargetU) * height), 0, height - 1);
        
        float d = fusedOutput[targetY * width + targetX];
        g_targetDepth = d > 0.1f ? d : (centerDist > 0.1f ? centerDist : 1.0f);
        LOGI("[ROI] Locked target depth to %.2f meters from tap U=%.2f, V=%.2f (Mapped X=%d, Y=%d)", 
             g_targetDepth, g_roiTargetU, g_roiTargetV, targetX, targetY);
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
            // B10: O(N) kopyalama maliyetini azaltmak için önden kapasite genişlet
            if (g_accumulatedPointCloud.capacity() < g_accumulatedPointCloud.size() + newPoints.size() + 1024) {
                g_accumulatedPointCloud.reserve((g_accumulatedPointCloud.size() + newPoints.size()) * 2);
            }
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
    std::vector<Point3D> localCloud;
    {
        std::lock_guard<std::mutex> lock(g_cloudMutex);
        localCloud = g_accumulatedPointCloud;
    } // lock released - Denoise çalışırken frame processing kilitlenmez (B05)

    int before = static_cast<int>(localCloud.size());
    g_denoiseEngine.DenoisePointCloud(localCloud);
    int after = static_cast<int>(localCloud.size());

    {
        std::lock_guard<std::mutex> lock(g_cloudMutex);
        g_accumulatedPointCloud = std::move(localCloud);
    }

    LOGI("[NPU-SOR] %d → %d nokta (%d parazit temizlendi).", before, after, before - after);
    return static_cast<jint>(before - after);
}
