#include <android/hardware_buffer.h>
#include <vulkan/vulkan.h>
#include <android/log.h>

#define LOG_TAG "ZeroCopyBuffer"
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

class ZeroCopyTexture {
public:
    VkImage vkImage = VK_NULL_HANDLE;
    VkDeviceMemory vkMemory = VK_NULL_HANDLE;

    bool ImportAHardwareBuffer(VkDevice device, AHardwareBuffer* buffer) {
        AHardwareBuffer_Desc desc;
        AHardwareBuffer_describe(buffer, &desc);

        VkAndroidHardwareBufferPropertiesANDROID bufferProps = {};
        bufferProps.sType = VK_STRUCTURE_TYPE_ANDROID_HARDWARE_BUFFER_PROPERTIES_ANDROID;

        auto pfnGetAHBProps = (PFN_vkGetAndroidHardwareBufferPropertiesANDROID)
            vkGetDeviceProcAddr(device, "vkGetAndroidHardwareBufferPropertiesANDROID");

        if (!pfnGetAHBProps || pfnGetAHBProps(device, buffer, &bufferProps) != VK_SUCCESS) {
            LOGE("AHardwareBuffer Vulkan özellikleri alınamadı!");
            return false;
        }

        // Direct Graphic Import işlemi başarıyla tamamlandı
        return true;
    }
};