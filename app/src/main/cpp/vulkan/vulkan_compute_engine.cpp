#include "vulkan_compute_engine.h"
#include <android/log.h>
#include <cstring>
#include <cmath>

#define LOG_TAG "VulkanCompute"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

const uint32_t bilateral_spv[] = 
#include "bilateral_spv.h"
;

VulkanComputeEngine::VulkanComputeEngine() {}

VulkanComputeEngine::~VulkanComputeEngine() {
    Cleanup();
}

bool VulkanComputeEngine::Initialize() {
    if (m_initialized) return true;

    if (!CreateInstance()) return false;
    if (!PickPhysicalDevice()) return false;
    if (!CreateLogicalDevice()) return false;
    if (!CreateCommandPool()) return false;
    if (!CreateComputePipeline()) return false;

    m_initialized = true;
    LOGI("Vulkan Compute Engine successfully initialized.");
    return true;
}

bool VulkanComputeEngine::CreateInstance() {
    VkApplicationInfo appInfo{};
    appInfo.sType = VK_STRUCTURE_TYPE_APPLICATION_INFO;
    appInfo.pApplicationName = "3D Scanner";
    appInfo.applicationVersion = VK_MAKE_VERSION(1, 0, 0);
    appInfo.pEngineName = "VulkanComputeEngine";
    appInfo.engineVersion = VK_MAKE_VERSION(1, 0, 0);
    appInfo.apiVersion = VK_API_VERSION_1_0;

    VkInstanceCreateInfo createInfo{};
    createInfo.sType = VK_STRUCTURE_TYPE_INSTANCE_CREATE_INFO;
    createInfo.pApplicationInfo = &appInfo;

    if (vkCreateInstance(&createInfo, nullptr, &m_instance) != VK_SUCCESS) {
        LOGE("Failed to create Vulkan instance!");
        return false;
    }
    return true;
}

bool VulkanComputeEngine::PickPhysicalDevice() {
    uint32_t deviceCount = 0;
    vkEnumeratePhysicalDevices(m_instance, &deviceCount, nullptr);

    if (deviceCount == 0) {
        LOGE("Failed to find GPUs with Vulkan support!");
        return false;
    }

    std::vector<VkPhysicalDevice> devices(deviceCount);
    vkEnumeratePhysicalDevices(m_instance, &deviceCount, devices.data());

    for (const auto& device : devices) {
        uint32_t queueFamilyCount = 0;
        vkGetPhysicalDeviceQueueFamilyProperties(device, &queueFamilyCount, nullptr);
        std::vector<VkQueueFamilyProperties> queueFamilies(queueFamilyCount);
        vkGetPhysicalDeviceQueueFamilyProperties(device, &queueFamilyCount, queueFamilies.data());

        for (uint32_t i = 0; i < queueFamilyCount; ++i) {
            if (queueFamilies[i].queueFlags & VK_QUEUE_COMPUTE_BIT) {
                m_physicalDevice = device;
                m_queueFamilyIndex = i;
                return true;
            }
        }
    }

    LOGE("Failed to find a suitable physical device with compute capability!");
    return false;
}

bool VulkanComputeEngine::CreateLogicalDevice() {
    VkDeviceQueueCreateInfo queueCreateInfo{};
    queueCreateInfo.sType = VK_STRUCTURE_TYPE_DEVICE_QUEUE_CREATE_INFO;
    queueCreateInfo.queueFamilyIndex = m_queueFamilyIndex;
    queueCreateInfo.queueCount = 1;
    float queuePriority = 1.0f;
    queueCreateInfo.pQueuePriorities = &queuePriority;

    VkDeviceCreateInfo createInfo{};
    createInfo.sType = VK_STRUCTURE_TYPE_DEVICE_CREATE_INFO;
    createInfo.pQueueCreateInfos = &queueCreateInfo;
    createInfo.queueCreateInfoCount = 1;

    if (vkCreateDevice(m_physicalDevice, &createInfo, nullptr, &m_device) != VK_SUCCESS) {
        LOGE("Failed to create logical device!");
        return false;
    }

    vkGetDeviceQueue(m_device, m_queueFamilyIndex, 0, &m_computeQueue);
    return true;
}

bool VulkanComputeEngine::CreateCommandPool() {
    VkCommandPoolCreateInfo poolInfo{};
    poolInfo.sType = VK_STRUCTURE_TYPE_COMMAND_POOL_CREATE_INFO;
    poolInfo.queueFamilyIndex = m_queueFamilyIndex;
    poolInfo.flags = VK_COMMAND_POOL_CREATE_RESET_COMMAND_BUFFER_BIT;

    if (vkCreateCommandPool(m_device, &poolInfo, nullptr, &m_commandPool) != VK_SUCCESS) {
        LOGE("Failed to create command pool!");
        return false;
    }

    VkCommandBufferAllocateInfo allocInfo{};
    allocInfo.sType = VK_STRUCTURE_TYPE_COMMAND_BUFFER_ALLOCATE_INFO;
    allocInfo.commandPool = m_commandPool;
    allocInfo.level = VK_COMMAND_BUFFER_LEVEL_PRIMARY;
    allocInfo.commandBufferCount = 1;

    if (vkAllocateCommandBuffers(m_device, &allocInfo, &m_commandBuffer) != VK_SUCCESS) {
        LOGE("Failed to allocate command buffer!");
        return false;
    }

    return true;
}

bool VulkanComputeEngine::CreateComputePipeline() {
    VkDescriptorSetLayoutBinding bindings[3] = {};
    for (int i = 0; i < 3; ++i) {
        bindings[i].binding = i;
        bindings[i].descriptorType = VK_DESCRIPTOR_TYPE_STORAGE_BUFFER;
        bindings[i].descriptorCount = 1;
        bindings[i].stageFlags = VK_SHADER_STAGE_COMPUTE_BIT;
    }

    VkDescriptorSetLayoutCreateInfo layoutInfo{};
    layoutInfo.sType = VK_STRUCTURE_TYPE_DESCRIPTOR_SET_LAYOUT_CREATE_INFO;
    layoutInfo.bindingCount = 3;
    layoutInfo.pBindings = bindings;

    if (vkCreateDescriptorSetLayout(m_device, &layoutInfo, nullptr, &m_descriptorSetLayout) != VK_SUCCESS) {
        LOGE("Failed to create descriptor set layout!");
        return false;
    }

    VkDescriptorPoolSize poolSize{};
    poolSize.type = VK_DESCRIPTOR_TYPE_STORAGE_BUFFER;
    poolSize.descriptorCount = 3;

    VkDescriptorPoolCreateInfo poolInfo{};
    poolInfo.sType = VK_STRUCTURE_TYPE_DESCRIPTOR_POOL_CREATE_INFO;
    poolInfo.poolSizeCount = 1;
    poolInfo.pPoolSizes = &poolSize;
    poolInfo.maxSets = 1;

    if (vkCreateDescriptorPool(m_device, &poolInfo, nullptr, &m_descriptorPool) != VK_SUCCESS) {
        LOGE("Failed to create descriptor pool!");
        return false;
    }

    VkDescriptorSetAllocateInfo allocInfo{};
    allocInfo.sType = VK_STRUCTURE_TYPE_DESCRIPTOR_SET_ALLOCATE_INFO;
    allocInfo.descriptorPool = m_descriptorPool;
    allocInfo.descriptorSetCount = 1;
    allocInfo.pSetLayouts = &m_descriptorSetLayout;

    if (vkAllocateDescriptorSets(m_device, &allocInfo, &m_descriptorSet) != VK_SUCCESS) {
        LOGE("Failed to allocate descriptor set!");
        return false;
    }

    VkPushConstantRange pushConstant{};
    pushConstant.offset = 0;
    pushConstant.size = sizeof(PushConstants);
    pushConstant.stageFlags = VK_SHADER_STAGE_COMPUTE_BIT;

    VkPipelineLayoutCreateInfo pipelineLayoutInfo{};
    pipelineLayoutInfo.sType = VK_STRUCTURE_TYPE_PIPELINE_LAYOUT_CREATE_INFO;
    pipelineLayoutInfo.setLayoutCount = 1;
    pipelineLayoutInfo.pSetLayouts = &m_descriptorSetLayout;
    pipelineLayoutInfo.pushConstantRangeCount = 1;
    pipelineLayoutInfo.pPushConstantRanges = &pushConstant;

    if (vkCreatePipelineLayout(m_device, &pipelineLayoutInfo, nullptr, &m_pipelineLayout) != VK_SUCCESS) {
        LOGE("Failed to create pipeline layout!");
        return false;
    }

    VkShaderModuleCreateInfo createInfo{};
    createInfo.sType = VK_STRUCTURE_TYPE_SHADER_MODULE_CREATE_INFO;
    createInfo.codeSize = sizeof(bilateral_spv);
    createInfo.pCode = bilateral_spv;

    VkShaderModule computeShaderModule;
    if (vkCreateShaderModule(m_device, &createInfo, nullptr, &computeShaderModule) != VK_SUCCESS) {
        LOGE("Failed to create shader module!");
        return false;
    }

    VkPipelineShaderStageCreateInfo shaderStageInfo{};
    shaderStageInfo.sType = VK_STRUCTURE_TYPE_PIPELINE_SHADER_STAGE_CREATE_INFO;
    shaderStageInfo.stage = VK_SHADER_STAGE_COMPUTE_BIT;
    shaderStageInfo.module = computeShaderModule;
    shaderStageInfo.pName = "main";

    VkComputePipelineCreateInfo pipelineInfo{};
    pipelineInfo.sType = VK_STRUCTURE_TYPE_COMPUTE_PIPELINE_CREATE_INFO;
    pipelineInfo.stage = shaderStageInfo;
    pipelineInfo.layout = m_pipelineLayout;

    if (vkCreateComputePipelines(m_device, VK_NULL_HANDLE, 1, &pipelineInfo, nullptr, &m_pipeline) != VK_SUCCESS) {
        LOGE("Failed to create compute pipeline!");
        vkDestroyShaderModule(m_device, computeShaderModule, nullptr);
        return false;
    }

    vkDestroyShaderModule(m_device, computeShaderModule, nullptr);
    return true;
}

uint32_t VulkanComputeEngine::FindMemoryType(uint32_t typeFilter, VkMemoryPropertyFlags properties) {
    VkPhysicalDeviceMemoryProperties memProperties;
    vkGetPhysicalDeviceMemoryProperties(m_physicalDevice, &memProperties);

    for (uint32_t i = 0; i < memProperties.memoryTypeCount; i++) {
        if ((typeFilter & (1 << i)) && (memProperties.memoryTypes[i].propertyFlags & properties) == properties) {
            return i;
        }
    }
    LOGE("Failed to find suitable memory type!");
    return 0;
}

bool VulkanComputeEngine::CreateBuffer(VkDeviceSize size, VkBufferUsageFlags usage, VkMemoryPropertyFlags properties, VkBuffer& buffer, VkDeviceMemory& bufferMemory) {
    if (size == 0) return true;

    VkBufferCreateInfo bufferInfo{};
    bufferInfo.sType = VK_STRUCTURE_TYPE_BUFFER_CREATE_INFO;
    bufferInfo.size = size;
    bufferInfo.usage = usage;
    bufferInfo.sharingMode = VK_SHARING_MODE_EXCLUSIVE;

    if (vkCreateBuffer(m_device, &bufferInfo, nullptr, &buffer) != VK_SUCCESS) {
        LOGE("Failed to create buffer!");
        return false;
    }

    VkMemoryRequirements memRequirements;
    vkGetBufferMemoryRequirements(m_device, buffer, &memRequirements);

    VkMemoryAllocateInfo allocInfo{};
    allocInfo.sType = VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO;
    allocInfo.allocationSize = memRequirements.size;
    allocInfo.memoryTypeIndex = FindMemoryType(memRequirements.memoryTypeBits, properties);

    if (vkAllocateMemory(m_device, &allocInfo, nullptr, &bufferMemory) != VK_SUCCESS) {
        LOGE("Failed to allocate buffer memory!");
        return false;
    }

    vkBindBufferMemory(m_device, buffer, bufferMemory, 0);
    return true;
}

bool VulkanComputeEngine::CreateBuffers(size_t depthSize, size_t rgbSize) {
    if (m_inDepthBuffer.size == depthSize && m_inRgbBuffer.size == rgbSize && m_outDepthBuffer.size == depthSize) {
        return true; // Already created
    }

    CleanupBuffers();

    VkBufferUsageFlags usage = VK_BUFFER_USAGE_STORAGE_BUFFER_BIT;
    VkMemoryPropertyFlags properties = VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT | VK_MEMORY_PROPERTY_HOST_COHERENT_BIT;

    if (!CreateBuffer(depthSize, usage, properties, m_inDepthBuffer.buffer, m_inDepthBuffer.memory)) return false;
    m_inDepthBuffer.size = depthSize;

    if (!CreateBuffer(rgbSize, usage, properties, m_inRgbBuffer.buffer, m_inRgbBuffer.memory)) return false;
    m_inRgbBuffer.size = rgbSize;

    if (!CreateBuffer(depthSize, usage, properties, m_outDepthBuffer.buffer, m_outDepthBuffer.memory)) return false;
    m_outDepthBuffer.size = depthSize;

    // Update Descriptor Sets
    VkDescriptorBufferInfo depthInfo{};
    depthInfo.buffer = m_inDepthBuffer.buffer;
    depthInfo.offset = 0;
    depthInfo.range = depthSize;

    VkDescriptorBufferInfo rgbInfo{};
    rgbInfo.buffer = m_inRgbBuffer.buffer;
    rgbInfo.offset = 0;
    rgbInfo.range = (rgbSize > 0) ? rgbSize : VK_WHOLE_SIZE;

    VkDescriptorBufferInfo outInfo{};
    outInfo.buffer = m_outDepthBuffer.buffer;
    outInfo.offset = 0;
    outInfo.range = depthSize;

    VkWriteDescriptorSet descriptorWrites[3] = {};

    descriptorWrites[0].sType = VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET;
    descriptorWrites[0].dstSet = m_descriptorSet;
    descriptorWrites[0].dstBinding = 0;
    descriptorWrites[0].dstArrayElement = 0;
    descriptorWrites[0].descriptorType = VK_DESCRIPTOR_TYPE_STORAGE_BUFFER;
    descriptorWrites[0].descriptorCount = 1;
    descriptorWrites[0].pBufferInfo = &depthInfo;

    descriptorWrites[1].sType = VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET;
    descriptorWrites[1].dstSet = m_descriptorSet;
    descriptorWrites[1].dstBinding = 1;
    descriptorWrites[1].dstArrayElement = 0;
    descriptorWrites[1].descriptorType = VK_DESCRIPTOR_TYPE_STORAGE_BUFFER;
    descriptorWrites[1].descriptorCount = 1;
    descriptorWrites[1].pBufferInfo = &rgbInfo;

    descriptorWrites[2].sType = VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET;
    descriptorWrites[2].dstSet = m_descriptorSet;
    descriptorWrites[2].dstBinding = 2;
    descriptorWrites[2].dstArrayElement = 0;
    descriptorWrites[2].descriptorType = VK_DESCRIPTOR_TYPE_STORAGE_BUFFER;
    descriptorWrites[2].descriptorCount = 1;
    descriptorWrites[2].pBufferInfo = &outInfo;

    vkUpdateDescriptorSets(m_device, 3, descriptorWrites, 0, nullptr);

    return true;
}

bool VulkanComputeEngine::DispatchBilateralFilter(
    const float* inDepth,
    const uint8_t* inRgb,
    float* outDepth,
    int width,
    int height,
    float invSS2,
    float invSR2,
    int radius
) {
    if (!m_initialized) return false;

    size_t depthSize = width * height * sizeof(float);
    size_t rgbSize = width * height * 3 * sizeof(uint8_t);
    // Align RGB size for GLSL std430 (uint arrays need 4 byte alignment)
    if (rgbSize % 4 != 0) {
        rgbSize += 4 - (rgbSize % 4); 
    }
    if (inRgb == nullptr) rgbSize = 4; // Allocate at least 4 bytes to avoid errors

    if (!CreateBuffers(depthSize, rgbSize)) {
        LOGE("Failed to create/resize buffers!");
        return false;
    }

    // Map and copy input data
    void* data;
    vkMapMemory(m_device, m_inDepthBuffer.memory, 0, depthSize, 0, &data);
    memcpy(data, inDepth, depthSize);
    vkUnmapMemory(m_device, m_inDepthBuffer.memory);

    if (inRgb) {
        vkMapMemory(m_device, m_inRgbBuffer.memory, 0, rgbSize, 0, &data);
        memcpy(data, inRgb, width * height * 3);
        vkUnmapMemory(m_device, m_inRgbBuffer.memory);
    }

    // Record command buffer
    VkCommandBufferBeginInfo beginInfo{};
    beginInfo.sType = VK_STRUCTURE_TYPE_COMMAND_BUFFER_BEGIN_INFO;
    beginInfo.flags = VK_COMMAND_BUFFER_USAGE_ONE_TIME_SUBMIT_BIT;

    vkBeginCommandBuffer(m_commandBuffer, &beginInfo);

    vkCmdBindPipeline(m_commandBuffer, VK_PIPELINE_BIND_POINT_COMPUTE, m_pipeline);
    vkCmdBindDescriptorSets(m_commandBuffer, VK_PIPELINE_BIND_POINT_COMPUTE, m_pipelineLayout, 0, 1, &m_descriptorSet, 0, nullptr);

    PushConstants pcs{};
    pcs.width = width;
    pcs.height = height;
    pcs.invSS2 = invSS2;
    pcs.invSR2 = invSR2;
    pcs.radius = radius;
    pcs.useRgb = (inRgb != nullptr) ? 1 : 0;

    vkCmdPushConstants(m_commandBuffer, m_pipelineLayout, VK_SHADER_STAGE_COMPUTE_BIT, 0, sizeof(PushConstants), &pcs);

    // Group size is 16x16
    uint32_t groupX = (width + 15) / 16;
    uint32_t groupY = (height + 15) / 16;
    vkCmdDispatch(m_commandBuffer, groupX, groupY, 1);

    vkEndCommandBuffer(m_commandBuffer);

    // Submit
    VkSubmitInfo submitInfo{};
    submitInfo.sType = VK_STRUCTURE_TYPE_SUBMIT_INFO;
    submitInfo.commandBufferCount = 1;
    submitInfo.pCommandBuffers = &m_commandBuffer;

    if (vkQueueSubmit(m_computeQueue, 1, &submitInfo, VK_NULL_HANDLE) != VK_SUCCESS) {
        LOGE("Failed to submit compute command buffer!");
        return false;
    }

    vkQueueWaitIdle(m_computeQueue);

    // Read back results
    vkMapMemory(m_device, m_outDepthBuffer.memory, 0, depthSize, 0, &data);
    memcpy(outDepth, data, depthSize);
    vkUnmapMemory(m_device, m_outDepthBuffer.memory);

    return true;
}

void VulkanComputeEngine::CleanupBuffers() {
    if (m_inDepthBuffer.buffer) {
        vkDestroyBuffer(m_device, m_inDepthBuffer.buffer, nullptr);
        vkFreeMemory(m_device, m_inDepthBuffer.memory, nullptr);
    }
    if (m_inRgbBuffer.buffer) {
        vkDestroyBuffer(m_device, m_inRgbBuffer.buffer, nullptr);
        vkFreeMemory(m_device, m_inRgbBuffer.memory, nullptr);
    }
    if (m_outDepthBuffer.buffer) {
        vkDestroyBuffer(m_device, m_outDepthBuffer.buffer, nullptr);
        vkFreeMemory(m_device, m_outDepthBuffer.memory, nullptr);
    }
    m_inDepthBuffer = {};
    m_inRgbBuffer = {};
    m_outDepthBuffer = {};
}

void VulkanComputeEngine::Cleanup() {
    if (!m_initialized) return;

    vkDeviceWaitIdle(m_device);

    CleanupBuffers();

    vkDestroyPipeline(m_device, m_pipeline, nullptr);
    vkDestroyPipelineLayout(m_device, m_pipelineLayout, nullptr);
    vkDestroyDescriptorPool(m_device, m_descriptorPool, nullptr);
    vkDestroyDescriptorSetLayout(m_device, m_descriptorSetLayout, nullptr);

    vkDestroyCommandPool(m_device, m_commandPool, nullptr);
    vkDestroyDevice(m_device, nullptr);
    vkDestroyInstance(m_instance, nullptr);

    m_initialized = false;
}
