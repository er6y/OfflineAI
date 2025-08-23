#include "ggml-backend.h"
#ifdef GGML_USE_VULKAN
// 定义 Vulkan-Hpp 动态分发的全局存储，解决 vk::detail::defaultDispatchLoaderDynamic 未定义链接错误
#define VULKAN_HPP_DISPATCH_LOADER_DYNAMIC 1
#define VK_USE_PLATFORM_ANDROID_KHR 1
#include <vulkan/vulkan.hpp>
VULKAN_HPP_DEFAULT_DISPATCH_LOADER_DYNAMIC_STORAGE

#include "ggml-vulkan.h"
extern "C" {
// 引用关键 Vulkan 后端注册函数防止链接器裁剪
void force_vulkan_symbols_linker_keep() {
    // 通过函数指针引用防止链接器优化掉 Vulkan 代码
    volatile void* ptr = (void*)&ggml_backend_vk_reg;
    (void)ptr; // 消除未使用变量警告
}
}
#endif