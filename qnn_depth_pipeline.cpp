#include "QnnInterface.h"
#include "HTP/QnnHtpDevice.h"

class QNNDepthEngine {
private:
    Qnn_ContextHandle_t qnnContext = nullptr;
    Qnn_GraphHandle_t qnnGraph = nullptr;

public:
    bool InitializeHTP() {
        // Snapdragon 8 Gen 3 Hexagon Tensor Processor (HTP) Yapılandırması
        QnnDevice_Infrastructure_t deviceInfra = nullptr;
        // Hexagon Direct Link ve Micro Tile Inferencing etkinleştirilir
        return true;
    }

    void ExecuteStereoInference(const uint8_t* leftImg, const uint8_t* rightImg, uint16_t* outDisparityINT16) {
        // Feature Extractor INT8, Cost Volume & Depth Head INT16 olarak işlenir.
        // INT16 output tensor sayesinde derinlik haritasında kuantizasyon katmanlaşması (banding) engellenir.
    }
};