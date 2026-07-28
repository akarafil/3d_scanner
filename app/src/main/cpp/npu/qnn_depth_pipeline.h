#ifndef QNN_DEPTH_PIPELINE_H
#define QNN_DEPTH_PIPELINE_H

#include <cstdint>
#include <cstddef>

class QNNDepthEngine {
private:
    void* qnnContext = nullptr;
    void* qnnGraph = nullptr;

public:
    bool InitializeHTP();
    void ExecuteStereoInference(const uint8_t* leftImg, const uint8_t* rightImg, uint16_t* outDisparityINT16, int width, int height);
    void ExecuteDepthRefinement(const float* inDepth, const uint8_t* rgbImg, float* outDepth, int width, int height);
};

#endif // QNN_DEPTH_PIPELINE_H
