#include "ggml-backend.h"
#ifdef GGML_USE_VULKAN
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