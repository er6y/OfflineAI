#ifndef VULKAN_VERSION_PATCH_H
#define VULKAN_VERSION_PATCH_H

// 运行时检测也需要这些版本比较宏，即使未启用编译期的 GGML_USE_VULKAN
#include <vulkan/vulkan.h>

// 根据架构设置最小Vulkan版本要求
// ARM架构通常支持较低版本，x86架构需要更高版本
#define GGML_VULKAN_MIN_VERSION VK_API_VERSION_1_2
#define GGML_VULKAN_MIN_VERSION_STR "1.2"

// Vulkan版本检查宏
#define VULKAN_VERSION_MAJOR(version) VK_VERSION_MAJOR(version)
#define VULKAN_VERSION_MINOR(version) VK_VERSION_MINOR(version)
#define VULKAN_VERSION_PATCH(version) VK_VERSION_PATCH(version)

// 版本比较宏
#define VULKAN_VERSION_GE(version, major, minor, patch) \
    (VK_VERSION_MAJOR(version) > (major) || \
     (VK_VERSION_MAJOR(version) == (major) && VK_VERSION_MINOR(version) > (minor)) || \
     (VK_VERSION_MAJOR(version) == (major) && VK_VERSION_MINOR(version) == (minor) && VK_VERSION_PATCH(version) >= (patch)))

#endif // VULKAN_VERSION_PATCH_H