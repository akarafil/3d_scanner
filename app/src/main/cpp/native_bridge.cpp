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
    jfloat nx, jfloat ny, jfloat nz, jint size) {
    
    jfloat* x = env->GetFloatArrayElements(xArr, nullptr);
    jfloat* y = env->GetFloatArrayElements(yArr, nullptr);
    jfloat* z = env->GetFloatArrayElements(zArr, nullptr);

    for (int i = 0; i < size; ++i) {
        std::lock_guard<std::mutex> lock(g_cloudMutex);
        g_accumulatedPointCloud.push_back({x[i], y[i], z[i], nx, ny, nz});
    }

    env->ReleaseFloatArrayElements(xArr, x, JNI_ABORT);
    env->ReleaseFloatArrayElements(yArr, y, JNI_ABORT);
    env->ReleaseFloatArrayElements(zArr, z, JNI_ABORT);
}

extern "C" JNIEXPORT jfloatArray JNICALL
Java_com_magicv3_scanner3d_MainActivity_getAccumulatedPoints(JNIEnv* env, jobject /* this */) {
    int size = g_accumulatedPointCloud.size();
    jfloatArray result = env->NewFloatArray(size * 3);
    jfloat* body = env->GetFloatArrayElements(result, nullptr);
    {
        std::lock_guard<std::mutex> lock(g_cloudMutex);
        for (int i = 0; i < size; ++i) {
            body[i * 3 + 0] = g_accumulatedPointCloud[i].x;
            body[i * 3 + 1] = g_accumulatedPointCloud[i].y;
            body[i * 3 + 2] = g_accumulatedPointCloud[i].z;
        }
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
