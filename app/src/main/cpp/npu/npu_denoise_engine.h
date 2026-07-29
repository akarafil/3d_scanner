#ifndef NPU_DENOISE_ENGINE_H
#define NPU_DENOISE_ENGINE_H

#include <cstdint>
#include <cstddef>
#include <vector>
#include <mutex>
// Point3D struct'ı mesh modülünden alınır
#include "../mesh/poisson_deferred.h"
#include "../vulkan/vulkan_compute_engine.h"

// ============================================================
//  NpuDenoiseEngine — Snapdragon 8 Gen 3 Hexagon HTP Simülasyonu
//
//  Üç aşamalı NPU tabanlı derinlik parazit temizleme pipeline'ı:
//   1. TemporalDepthStabilizer  — Kare-karesi EWM birleştirme
//   2. Joint Bilateral Filter   — Kenar korumalı derinlik düzleştirme (9x9)
//   3. Statistical Outlier Removal (SOR) — Nokta bulutu parazit temizleyici
//
//  Not: Qualcomm QNN HTP SDK bağımsız çalışır. QNN SDK mevcut
//  olduğunda sadece çekirdek implementasyon değişir, arayüz sabittir.
// ============================================================

// -------------------------------------------------------
// Temporal Derinlik Dengeleyici
// Kamera hareketi ve sensor gürültüsünden kaynaklanan
// kare-karesi titreşmeleri EWM ortalamasıyla dengeler.
// -------------------------------------------------------
class TemporalDepthStabilizer {
public:
    static constexpr int MAX_HISTORY = 4; // Biriktirilen kare sayısı
    static constexpr float ALPHA       = 0.65f; // EWM ağırlığı (yüksek = daha hızlı)
    static constexpr float MOTION_THR  = 0.08f; // Hareket eşiği (metre cinsinden)

    void Reset();

    // Her kare çağrılır. inDepth → stabilize edilmiş outDepth
    void Stabilize(
        const float* inDepth,
        float*       outDepth,
        int          width,
        int          height
    );

    bool HasHistory() const { return m_frameCount > 0; }

private:
    std::vector<float> m_prevDepth;    // Önceki karedeki stabilize derinlik
    int                m_frameCount = 0;
    int                m_width = 0;
    int                m_height = 0;
};

// -------------------------------------------------------
// İstatistiksel Aykırı Nokta Temizleyici (SOR Filter)
// Nokta bulutundaki parazit noktaları k-NN tabanlı
// istatistiksel analizle tespit eder ve siler.
// -------------------------------------------------------
class NpuSORFilter {
public:
    // k: Komşu sayısı | stdMul: Standart sapma çarpanı (eşik)
    explicit NpuSORFilter(int k = 8, float stdMul = 1.5f)
        : m_k(k), m_stdMul(stdMul) {}

    // Ham nokta listesinden aykırı noktaları siler.
    // Giriş/çıkış aynı vektör.
    void Filter(std::vector<Point3D>& points) const;

    void SetParams(int k, float stdMul) {
        m_k = k;
        m_stdMul = stdMul;
    }

private:
    int   m_k;
    float m_stdMul;

    float knnMeanDistance(
        const std::vector<Point3D>& pts, 
        int idx, 
        const std::vector<std::vector<int>>& spatialGrid, 
        int hashDim, 
        float minX, float minY, float minZ, 
        float hStepX, float hStepY, float hStepZ, 
        std::vector<float>& distsBuffer) const;
};

// -------------------------------------------------------
// Gelişmiş Joint Bilateral Filter
// RGB 3-kanal kenar bilgisiyle derinlik düzleştirme.
// 5x5 kernel, OpenMP ile paralel çalışır.
// -------------------------------------------------------
class NpuBilateralFilter {
public:
    static constexpr float SIGMA_S = 3.0f;  // Uzamsal Gaussian sigma
    static constexpr float SIGMA_R = 20.0f; // Renk mesafesi Gaussian sigma
    static constexpr int   RADIUS  = 2;     // 5x5 pencere (termal throttleyi engellemek için)

    // rgbImg: W*H*3 byte dizisi (R,G,B sıralı)
    void Apply(
        const float*   inDepth,
        const uint8_t* rgbImg,      // nullptr olabilir → mono mod
        float*         outDepth,
        int            width,
        int            height
    ) const;
};

// -------------------------------------------------------
// Tam NPU Pipeline Orkestrasyonu
// TemporalStabilizer → BilateralFilter adımlarını zincirler.
// SOR filtresi dışa ayrılmıştır (nokta bulutu üzerinde çalışır).
// -------------------------------------------------------
class NpuDenoiseEngine {
public:
    ~NpuDenoiseEngine();

    // Tüm dahili buffer'ları ve GPU kaynaklarını sıfırla/kapat
    void Reset();
    void Shutdown();

    // Per-frame çağrılır. Ham derinlik → temizlenmiş derinlik
    // rgbImg: nullable (yoksa bilateral renk bilgisi kullanılmaz)
    void ProcessDepthFrame(
        const float*   rawDepth,
        const uint8_t* rgbImg,
        float*         outDepth,
        int            width,
        int            height
    );

    // Nokta bulutu üzerinde SOR uygular (kaydetmeden önce çağrılır)
    void DenoisePointCloud(std::vector<Point3D>& points);
    // Parametre ayarlama
    void SetSORParams(int k, float stdMul) { m_sorFilter.SetParams(k, stdMul); }

private:
    // NPU Engine'in thread-safe olması için mutex
    std::mutex m_mutex;

    VulkanComputeEngine* m_vkEngine = nullptr;

    TemporalDepthStabilizer m_temporal;
    NpuBilateralFilter      m_bilateral;
    NpuSORFilter            m_sorFilter;

    std::vector<float>      m_tempBuffer; // Temporal → Bilateral arası buffer
};

#endif // NPU_DENOISE_ENGINE_H
