#ifndef VULKAN_RUNTIME_DETECTOR_H
#define VULKAN_RUNTIME_DETECTOR_H

// 动态加载Vulkan，不直接链接
#define VK_NO_PROTOTYPES
#include <vulkan/vulkan.h>
#include <stdbool.h>

namespace vulkan_runtime {

/**
 * Vulkan运行时信息结构
 */
struct VulkanRuntimeInfo {
    bool library_available = false;           // Vulkan库是否可加载
    bool instance_creation_works = false;     // Vulkan实例是否可创建
    bool physical_devices_available = false;  // 是否有可用的物理设备
    bool vulkan_1_1_apis_available = false;   // Vulkan 1.1 API是否可用
    bool suitable_for_llamacpp = false;       // 是否适合llama.cpp使用
    uint32_t device_count = 0;                // 物理设备数量
    uint32_t detected_api_version = 0;        // 检测到的实际API版本
    uint32_t instance_version = 0;            // 实例支持的版本
    uint32_t device_api_version = 0;          // 首个物理设备的API版本（device properties apiVersion）
    bool meets_min_version_requirement = false; // 是否满足最小版本要求
};

/**
 * 加载Vulkan库
 * @return 成功返回true，失败返回false
 */
bool load_vulkan_library();

/**
 * 卸载Vulkan库
 */
void unload_vulkan_library();

/**
 * 测试Vulkan实例创建
 * @return 成功返回true，失败返回false
 */
bool test_vulkan_instance_creation();

/**
 * 检测实际的Vulkan API版本
 * @return 检测到的API版本
 */
uint32_t detect_vulkan_api_version();

/**
 * 检查Vulkan 1.1 API可用性
 * @param instance Vulkan实例
 * @return API可用返回true，不可用返回false
 */
bool check_vulkan_1_1_apis(VkInstance instance);

/**
 * 检测Vulkan运行时环境
 * @return VulkanRuntimeInfo结构，包含详细的检测结果
 */
VulkanRuntimeInfo detect_vulkan_runtime();

/**
 * 清理Vulkan运行时资源
 */
void cleanup_vulkan_runtime();

} // namespace vulkan_runtime

#endif // VULKAN_RUNTIME_DETECTOR_H