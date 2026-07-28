#include "zerocopy_buffer.h"
#include <android/log.h>

#define LOG_TAG "ZeroCopyBuffer"
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

bool ZeroCopyTexture::ImportAHardwareBuffer(VkDevice device, AHardwareBuffer* buffer) {
    if (!buffer) {
        LOGE("AHardwareBuffer pointer is null!");
        return false;
    }

    AHardwareBuffer_Desc desc;
    AHardwareBuffer_describe(buffer, &desc);

    VkAndroidHardwareBufferPropertiesANDROID bufferProps = {};
    bufferProps.sType = VK_STRUCTURE_TYPE_ANDROID_HARDWARE_BUFFER_PROPERTIES_ANDROID;

    auto pfnGetAHBProps = (PFN_vkGetAndroidHardwareBufferPropertiesANDROID)
        vkGetDeviceProcAddr(device, "vkGetAndroidHardwareBufferPropertiesANDROID");

    if (!pfnGetAHBProps || pfnGetAHBProps(device, buffer, &bufferProps) != VK_SUCCESS) {
        LOGE("AHardwareBuffer Vulkan properties could not be retrieved!");
        return false;
    }

    return true;
}
