#ifndef ADAPTIVE_DEPTH_FUSION_H
#define ADAPTIVE_DEPTH_FUSION_H

#include <cstdint>

struct FusionParams {
    float varianceThreshold = 15.0f;
};

void FuseDepthMaps(
    const float* arcoreDepth, const float* arcoreConfidence,
    const float* stereoDepth, const uint8_t* rgbImage,
    float* fusedOutput, int width, int height);

#endif // ADAPTIVE_DEPTH_FUSION_H
