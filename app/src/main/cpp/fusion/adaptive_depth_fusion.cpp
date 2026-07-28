#include "adaptive_depth_fusion.h"
#include <cmath>
#include <algorithm>
#include <vector>

void FuseDepthMaps(
    const float* arcoreDepth, const float* arcoreConfidence,
    const float* stereoDepth, const uint8_t* rgbImage,
    float* fusedOutput, int width, int height) 
{
    #pragma omp parallel for collapse(2)
    for (int y = 1; y < height - 1; ++y) {
        for (int x = 1; x < width - 1; ++x) {
            int idx = y * width + x;

            // 1. Bölgesel Doku Varyansı Hesaplama (3x3 Kernel)
            float sum = 0.0f, sumSq = 0.0f;
            for (int dy = -1; dy <= 1; ++dy) {
                for (int dx = -1; dx <= 1; ++dx) {
                    // RGB formatında gönderildiği için 3 kanallı okuma yapıyoruz
                    float val = static_cast<float>(rgbImage[((y + dy) * width + (x + dx)) * 3]); // Grayscale luminance tahmini için R kanalı yeterli
                    sum += val;
                    sumSq += val * val;
                }
            }
            float mean = sum / 9.0f;
            float varTex = (sumSq / 9.0f) - (mean * mean);

            // 2. Stereo ağırlığı cihazda NPU modeli olmadığı için iptal edildi.
            // Sadece ARCore derinliğini kullanıyoruz. 
            // Gelecekte gerçek stereo depth engine eklendiğinde açılabilir.
            fusedOutput[idx] = arcoreDepth[idx];
        }
    }
}
