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
    
    ggml_backend_reg_t ggml_backend_vk_reg(void) {
        return nullptr;
    }
    
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