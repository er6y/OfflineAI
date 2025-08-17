#ifndef VULKAN_VERSION_PATCH_H
#define VULKAN_VERSION_PATCH_H

#ifdef GGML_USE_VULKAN

#include <vulkan/vulkan.h>

// 根据架构设置最小Vulkan版本要求
// ARM架构通常支持较低版本，x86架构需要更高版本
#if defined(__aarch64__) || defined(__arm__) || defined(_M_ARM64) || defined(_M_ARM)
    // ARM架构：使用Vulkan 1.1作为最小版本
    #define GGML_VULKAN_MIN_VERSION VK_API_VERSION_1_1
    #define GGML_VULKAN_MIN_VERSION_STR "1.1"
#else
    // x86/x86_64架构：使用Vulkan 1.2作为最小版本
    #define GGML_VULKAN_MIN_VERSION VK_API_VERSION_1_2
    #define GGML_VULKAN_MIN_VERSION_STR "1.2"
#endif

// Vulkan版本检查宏
#define VULKAN_VERSION_MAJOR(version) VK_VERSION_MAJOR(version)
#define VULKAN_VERSION_MINOR(version) VK_VERSION_MINOR(version)
#define VULKAN_VERSION_PATCH(version) VK_VERSION_PATCH(version)

// 版本比较宏
#define VULKAN_VERSION_GE(version, major, minor, patch) \
    (VK_VERSION_MAJOR(version) > (major) || \
     (VK_VERSION_MAJOR(version) == (major) && VK_VERSION_MINOR(version) > (minor)) || \
     (VK_VERSION_MAJOR(version) == (major) && VK_VERSION_MINOR(version) == (minor) && VK_VERSION_PATCH(version) >= (patch)))

#endif // GGML_USE_VULKAN

#endif // VULKAN_VERSION_PATCH_H