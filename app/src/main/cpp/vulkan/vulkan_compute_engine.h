#ifndef VULKAN_COMPUTE_ENGINE_H
#define VULKAN_COMPUTE_ENGINE_H

#include <vulkan/vulkan.h>
#include <vector>
#include <string>

struct PushConstants {
    int width;
    int height;
    float invSS2;
    float invSR2;
    int radius;
    int useRgb;
};

class VulkanComputeEngine {
public:
    VulkanComputeEngine();
    ~VulkanComputeEngine();

    bool Initialize();
    bool IsInitialized() const { return m_initialized; }

    // Dispatch the bilateral filter
    bool DispatchBilateralFilter(
        const float* inDepth,
        const uint8_t* inRgb, // Can be nullptr
        float* outDepth,
        int width,
        int height,
        float invSS2,
        float invSR2,
        int radius
    );

private:
    bool m_initialized = false;

    VkInstance m_instance = VK_NULL_HANDLE;
    VkPhysicalDevice m_physicalDevice = VK_NULL_HANDLE;
    VkDevice m_device = VK_NULL_HANDLE;
    VkQueue m_computeQueue = VK_NULL_HANDLE;
    uint32_t m_queueFamilyIndex = 0;

    VkCommandPool m_commandPool = VK_NULL_HANDLE;
    VkCommandBuffer m_commandBuffer = VK_NULL_HANDLE;

    VkDescriptorSetLayout m_descriptorSetLayout = VK_NULL_HANDLE;
    VkDescriptorPool m_descriptorPool = VK_NULL_HANDLE;
    VkDescriptorSet m_descriptorSet = VK_NULL_HANDLE;

    VkPipelineLayout m_pipelineLayout = VK_NULL_HANDLE;
    VkPipeline m_pipeline = VK_NULL_HANDLE;

    // Buffers
    struct BufferObject {
        VkBuffer buffer = VK_NULL_HANDLE;
        VkDeviceMemory memory = VK_NULL_HANDLE;
        size_t size = 0;
    };

    BufferObject m_inDepthBuffer;
    BufferObject m_inRgbBuffer;
    BufferObject m_outDepthBuffer;

    bool CreateInstance();
    bool PickPhysicalDevice();
    bool CreateLogicalDevice();
    bool CreateBuffers(size_t depthSize, size_t rgbSize);
    bool CreateComputePipeline();
    bool CreateCommandPool();

    bool CreateBuffer(VkDeviceSize size, VkBufferUsageFlags usage, VkMemoryPropertyFlags properties, VkBuffer& buffer, VkDeviceMemory& bufferMemory);
    uint32_t FindMemoryType(uint32_t typeFilter, VkMemoryPropertyFlags properties);
    void CleanupBuffers();
    void Cleanup();
};

#endif // VULKAN_COMPUTE_ENGINE_H
