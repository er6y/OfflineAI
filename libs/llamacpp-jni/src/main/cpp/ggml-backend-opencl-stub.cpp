// 后端stub实现
// 用于解决链接时找不到后端注册函数符号的问题

#include "ggml-backend.h"

extern "C" {
    // 提供空的后端注册函数
    ggml_backend_reg_t ggml_backend_opencl_reg(void) {
        return nullptr;
    }
    
    ggml_backend_reg_t ggml_backend_metal_reg(void) {
        return nullptr;
    }
    
    // 完全移除 Vulkan 的 stub 实现，避免与真实实现冲突
    // 注意：不再提供 ggml_backend_vk_reg 的 stub，让链接器使用真实的 Vulkan 后端实现
    
    ggml_backend_reg_t ggml_backend_cuda_reg(void) {
        return nullptr;
    }
    
    ggml_backend_reg_t ggml_backend_sycl_reg(void) {
        return nullptr;
    }
    
    ggml_backend_reg_t ggml_backend_cann_reg(void) {
        return nullptr;
    }
    
    ggml_backend_reg_t ggml_backend_blas_reg(void) {
        return nullptr;
    }
    
    ggml_backend_reg_t ggml_backend_rpc_reg(void) {
        return nullptr;
    }
}