#ifndef ZEROCOPY_BUFFER_H
#define ZEROCOPY_BUFFER_H

#define VK_USE_PLATFORM_ANDROID_KHR
#include <android/hardware_buffer.h>
#include <vulkan/vulkan.h>
#include <vulkan/vulkan_android.h>


class ZeroCopyTexture {
public:
    VkImage vkImage = VK_NULL_HANDLE;
    VkDeviceMemory vkMemory = VK_NULL_HANDLE;

    bool ImportAHardwareBuffer(VkDevice device, AHardwareBuffer* buffer);
};

#endif // ZEROCOPY_BUFFER_H
