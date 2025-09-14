cmake_minimum_required(VERSION 3.22.1)

project("llamacpp_jni")

# 设置C++标准
set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

# 设置编译选项 - 强制使用O3优化
set(CMAKE_CXX_FLAGS "${CMAKE_CXX_FLAGS} -O3 -DNDEBUG -Wno-deprecated-declarations")
set(CMAKE_C_FLAGS "${CMAKE_C_FLAGS} -O3 -DNDEBUG -Wno-deprecated-declarations")

# 确保所有构建类型都使用O3优化
set(CMAKE_CXX_FLAGS_DEBUG "${CMAKE_CXX_FLAGS_DEBUG} -O3")
set(CMAKE_C_FLAGS_DEBUG "${CMAKE_C_FLAGS_DEBUG} -O3")
set(CMAKE_CXX_FLAGS_RELEASE "${CMAKE_CXX_FLAGS_RELEASE} -O3")
set(CMAKE_C_FLAGS_RELEASE "${CMAKE_C_FLAGS_RELEASE} -O3")

# Android特定设置
if(ANDROID)
    set(CMAKE_CXX_FLAGS "${CMAKE_CXX_FLAGS} -fPIC")
    set(CMAKE_C_FLAGS "${CMAKE_C_FLAGS} -fPIC")
    
    # Android NDK 默认使用静态链接，无需显式指定 -static-libstdc++
    # 移除该参数以避免编译器警告
    
    # 根据ABI设置优化选项
    if(ANDROID_ABI STREQUAL "arm64-v8a")
        # ARM64架构优化 - ARM64本身支持NEON，启用fp16指令
        set(CMAKE_CXX_FLAGS "${CMAKE_CXX_FLAGS}  -march=armv8.2-a+fp16+dotprod -fno-limit-debug-info")
        set(CMAKE_C_FLAGS "${CMAKE_C_FLAGS} -march=armv8.2-a+fp16+dotprod")
        # 启用ARM的fp16支持
        set(CMAKE_CXX_FLAGS "${CMAKE_CXX_FLAGS} -DGGML_USE_FP16_VA=1 -D__ARM_FEATURE_FP16_VECTOR_ARITHMETIC=1")
        set(CMAKE_C_FLAGS "${CMAKE_C_FLAGS} -DGGML_USE_FP16_VA=1 -D__ARM_FEATURE_FP16_VECTOR_ARITHMETIC=1")
        # 启用dotprod（KleidiAI/ggml 将据此纳入 dotprod 内核与路径）
        set(CMAKE_CXX_FLAGS "${CMAKE_CXX_FLAGS} -DGGML_USE_DOTPROD=1")
        set(CMAKE_C_FLAGS "${CMAKE_C_FLAGS} -DGGML_USE_DOTPROD=1")
        message(STATUS "Android ${ANDROID_ABI} - ARM fp16 + dotprod instructions enabled")
    elseif(ANDROID_ABI STREQUAL "armeabi-v7a")
        # ARMv7架构优化 - 检查是否支持fp16
        set(CMAKE_CXX_FLAGS "${CMAKE_CXX_FLAGS}  -march=armv7-a -mfpu=neon-fp16")
        set(CMAKE_C_FLAGS "${CMAKE_C_FLAGS} -march=armv7-a -mfpu=neon-fp16")
        # 启用ARMv7的fp16支持（如果可用）
        set(CMAKE_CXX_FLAGS "${CMAKE_CXX_FLAGS} -DGGML_USE_FP16_VA=1 -D__ARM_FEATURE_FP16_VECTOR_ARITHMETIC=1")
        set(CMAKE_C_FLAGS "${CMAKE_C_FLAGS} -DGGML_USE_FP16_VA=1 -D__ARM_FEATURE_FP16_VECTOR_ARITHMETIC=1")
        message(STATUS "Android ${ANDROID_ABI} - ARM fp16 instructions enabled")
    elseif(ANDROID_ABI STREQUAL "x86_64" OR ANDROID_ABI STREQUAL "x86")
        # x86/x86_64架构 - 禁用高级指令集以避免模拟器兼容性问题
        set(CMAKE_CXX_FLAGS "${CMAKE_CXX_FLAGS} -mno-f16c -mno-fma -mno-avx2 -mno-avx -mno-sse4.2 -mno-sse4.1 -mno-ssse3 -mno-sse3")
        set(CMAKE_C_FLAGS "${CMAKE_C_FLAGS} -mno-f16c -mno-fma -mno-avx2 -mno-avx -mno-sse4.2 -mno-sse4.1 -mno-ssse3 -mno-sse3")
        # 取消宏定义以彻底禁用高级指令集
        set(CMAKE_CXX_FLAGS "${CMAKE_CXX_FLAGS} -U__F16C__ -U__FMA__ -U__AVX2__ -U__AVX__ -U__SSE4_2__ -U__SSE4_1__ -U__SSSE3__ -U__SSE3__")
        set(CMAKE_C_FLAGS "${CMAKE_C_FLAGS} -U__F16C__ -U__FMA__ -U__AVX2__ -U__AVX__ -U__SSE4_2__ -U__SSE4_1__ -U__SSSE3__ -U__SSE3__")
        # 明确禁用GGML高级指令集支持
        set(CMAKE_CXX_FLAGS "${CMAKE_CXX_FLAGS} -DGGML_F16C=0 -DGGML_USE_F16C=0 -DGGML_AVX=0 -DGGML_SSE=0")
        set(CMAKE_C_FLAGS "${CMAKE_C_FLAGS} -DGGML_F16C=0 -DGGML_USE_F16C=0 -DGGML_AVX=0 -DGGML_SSE=0")
        # 禁用NDK Translation的F16C检查
        set(CMAKE_CXX_FLAGS "${CMAKE_CXX_FLAGS} -DANDROID_DISABLE_F16C=1 -DANDROID_NDK_TRANSLATION_DISABLE_F16C=1")
        set(CMAKE_C_FLAGS "${CMAKE_C_FLAGS} -DANDROID_DISABLE_F16C=1 -DANDROID_NDK_TRANSLATION_DISABLE_F16C=1")
        message(STATUS "Android ${ANDROID_ABI} - Advanced instruction sets completely disabled for compatibility")
    endif()
endif()

# 查找必要的库
find_library(log-lib log)
find_library(android-lib android)

# 设置llama.cpp路径 - 指向统一的llama.cpp-master目录
set(LLAMA_CPP_DIR "${CMAKE_CURRENT_SOURCE_DIR}/../../../../llama.cpp-master")

# 检查llama.cpp目录是否存在
if(NOT EXISTS ${LLAMA_CPP_DIR})
    message(FATAL_ERROR "llama.cpp directory not found: ${LLAMA_CPP_DIR}")
endif()

# 跳过Git检查 - 设置默认的Git变量
set(GIT_SHA1 "unknown")
set(GIT_DATE "unknown")
set(GIT_COMMIT_SUBJECT "unknown")
set(LLAMA_STANDALONE OFF)

# 禁用Git相关的构建信息生成
set(LLAMA_BUILD_INFO OFF CACHE BOOL "Build info" FORCE)

# 禁用CURL依赖
set(LLAMA_CURL OFF CACHE BOOL "Enable CURL" FORCE)

# Set default build info values in case Git is not available
if(NOT DEFINED BUILD_NUMBER)
    set(BUILD_NUMBER 0)
endif()
if(NOT DEFINED BUILD_COMMIT)
    set(BUILD_COMMIT "unknown")
endif()
if(NOT DEFINED BUILD_COMPILER)
    set(BUILD_COMPILER "${CMAKE_CXX_COMPILER_ID} ${CMAKE_CXX_COMPILER_VERSION}")
endif()
if(NOT DEFINED BUILD_TARGET)
    set(BUILD_TARGET "${CMAKE_SYSTEM_NAME}-${CMAKE_SYSTEM_PROCESSOR}")
endif()

# Try to include build-info.cmake from llama.cpp if it exists
set(BUILD_INFO_CMAKE_PATH "${LLAMA_CPP_DIR}/cmake/build-info.cmake")
if(EXISTS ${BUILD_INFO_CMAKE_PATH})
    include(${BUILD_INFO_CMAKE_PATH})
endif()

# Map build info for upstream common/build-info.cpp (no local build-info.cpp here)
if(NOT DEFINED LLAMA_BUILD_NUMBER)
    set(LLAMA_BUILD_NUMBER ${BUILD_NUMBER})
endif()
if(NOT DEFINED LLAMA_BUILD_COMMIT)
    set(LLAMA_BUILD_COMMIT ${BUILD_COMMIT})
endif()
# Upstream common/build-info.cpp.in also uses BUILD_COMPILER and BUILD_TARGET which we already set via build-info.cmake

# 包含llama.cpp头文件
include_directories("${LLAMA_CPP_DIR}")
include_directories("${LLAMA_CPP_DIR}/common")
include_directories("${LLAMA_CPP_DIR}/include")
include_directories("${LLAMA_CPP_DIR}/ggml/include")
include_directories("${LLAMA_CPP_DIR}/ggml/src")
include_directories("${LLAMA_CPP_DIR}/ggml/src/ggml-cpu")
include_directories("${LLAMA_CPP_DIR}/src")
include_directories("${LLAMA_CPP_DIR}/vendor")

# 后端加速选项 - 启用后端注册系统，使用运行时Vulkan检测
# 避免静态链接Vulkan，通过JNI层动态加载实现GPU加速
set(GGML_USE_BACKEND_REGISTRY ON CACHE BOOL "Enable backend registry" FORCE)
set(GGML_USE_OPENCL OFF CACHE BOOL "Enable OpenCL backend" FORCE)
# 尊重外部 -D 传入的开关；如未指定则默认开启 Vulkan
if(NOT DEFINED GGML_USE_VULKAN)
    set(GGML_USE_VULKAN ON CACHE BOOL "Enable Vulkan backend")
endif()
# NOTE(maintainability): Removed duplicated default block for GGML_USE_VULKAN (runtime)
# if(NOT DEFINED GGML_USE_VULKAN)
#     set(GGML_USE_VULKAN ON CACHE BOOL "Enable Vulkan runtime")
# endif()
set(GGML_USE_CUDA OFF CACHE BOOL "Enable CUDA backend" FORCE)
set(GGML_NO_ACCELERATE ON CACHE BOOL "Disable other acceleration methods" FORCE)
message(STATUS "GGML_USE_VULKAN: ${GGML_USE_VULKAN}, GGML_VULKAN: ${GGML_VULKAN}")
# Alias: keep one switch name in logs/conditions (English log only)
set(ENABLE_VULKAN_BACKEND ${GGML_USE_VULKAN})
message(STATUS "ENABLE_VULKAN_BACKEND (alias of GGML_USE_VULKAN): ${ENABLE_VULKAN_BACKEND}")

# 处理Vulkan相关的CMake版本兼容性问题
# 如果启用Vulkan，需要确保CMake版本满足要求
if(GGML_USE_VULKAN)
    # Vulkan backend requires CMake 3.19+. If not satisfied, gracefully downgrade to CPU-only.
    if(CMAKE_VERSION VERSION_LESS "3.19")
        message(WARNING "Vulkan backend requires CMake 3.19 or higher (current: ${CMAKE_VERSION}). Disabling Vulkan backend and building CPU only.")
        set(GGML_USE_VULKAN OFF)
        set(ENABLE_VULKAN_BACKEND OFF)
    endif()
    
    # 设置Vulkan相关的CMake策略
    if(POLICY CMP0114)
        cmake_policy(SET CMP0114 NEW)
    endif()
    
    # Android NDK Vulkan库配置
    if(ANDROID)
        # 手动设置Vulkan为可用状态，使用NDK内置的vulkan库
        set(Vulkan_FOUND TRUE)
        set(Vulkan_LIBRARIES "vulkan")
        
        # 尝试使用系统VULKAN_SDK环境变量指向的Vulkan SDK头文件（如果存在）
        if(DEFINED ENV{VULKAN_SDK} AND EXISTS "$ENV{VULKAN_SDK}/Include")
            set(Vulkan_INCLUDE_DIRS "$ENV{VULKAN_SDK}/Include")
            message(STATUS "Using VULKAN_SDK headers: ${Vulkan_INCLUDE_DIRS}")
        else()
            # 回退到NDK Vulkan头文件 - 自动探测NDK根目录
            if(NOT DEFINED NDK_ROOT)
                if(DEFINED ENV{ANDROID_NDK_ROOT} AND EXISTS "$ENV{ANDROID_NDK_ROOT}")
                    set(NDK_ROOT "$ENV{ANDROID_NDK_ROOT}")
                elseif(DEFINED CMAKE_ANDROID_NDK AND EXISTS "${CMAKE_ANDROID_NDK}")
                    set(NDK_ROOT "${CMAKE_ANDROID_NDK}")
                elseif(DEFINED ANDROID_NDK AND EXISTS "${ANDROID_NDK}")
                    set(NDK_ROOT "${ANDROID_NDK}")
                elseif(DEFINED ENV{ANDROID_NDK_HOME} AND EXISTS "$ENV{ANDROID_NDK_HOME}")
                    set(NDK_ROOT "$ENV{ANDROID_NDK_HOME}")
                endif()
            endif()
            if(DEFINED NDK_ROOT AND EXISTS "${NDK_ROOT}/sources/third_party/vulkan/src/include")
                set(Vulkan_INCLUDE_DIRS "${NDK_ROOT}/sources/third_party/vulkan/src/include")
            else()
                message(WARNING "Unable to locate NDK Vulkan headers; please set VULKAN_SDK or ensure NDK has sources/third_party/vulkan/src/include")
            endif()
            message(STATUS "Using NDK Vulkan headers: ${Vulkan_INCLUDE_DIRS}")
        endif()
        
        # Avoid global include_directories for Vulkan headers; use per-target target_include_directories instead (English logs only)
    else()
        # 非Android平台使用标准查找
        find_package(Vulkan COMPONENTS glslc)
        if(NOT Vulkan_FOUND)
            message(WARNING "Vulkan not found, disabling Vulkan backend on non-Android platform")
            set(GGML_USE_VULKAN OFF CACHE BOOL "Enable Vulkan backend")
        endif()
    endif()
endif()

# Resolve ENABLE_VULKAN_BACKEND from GGML_USE_VULKAN (English logs only)
if(NOT DEFINED ENABLE_VULKAN_BACKEND)
    set(ENABLE_VULKAN_BACKEND ${GGML_USE_VULKAN})
    message(STATUS "Resolve ENABLE_VULKAN_BACKEND=${ENABLE_VULKAN_BACKEND}")
endif()

# 根据架构设置指令集支持
if(ANDROID_ABI STREQUAL "x86_64" OR ANDROID_ABI STREQUAL "x86")
    # 对于x86/x86_64架构，禁用高级指令集以避免模拟器兼容性问题
    set(GGML_F16C OFF CACHE BOOL "Disable F16C for x86/x86_64 compatibility" FORCE)
    set(GGML_FMA OFF CACHE BOOL "Disable FMA for x86/x86_64 compatibility" FORCE)
    set(GGML_AVX2 OFF CACHE BOOL "Disable AVX2 for x86/x86_64 compatibility" FORCE)
    set(GGML_AVX OFF CACHE BOOL "Disable AVX for x86/x86_64 compatibility" FORCE)
    set(GGML_SSE OFF CACHE BOOL "Disable SSE for x86/x86_64 compatibility" FORCE)
    message(STATUS "GGML advanced instruction sets disabled for ${ANDROID_ABI} architecture")
else()
    # 对于ARM架构，保持默认设置
    message(STATUS "GGML instruction sets using default settings for ${ANDROID_ABI} architecture")
endif()
set(GGML_USE_METAL OFF CACHE BOOL "Enable Metal backend" FORCE)
set(GGML_USE_SYCL OFF CACHE BOOL "Enable SYCL backend" FORCE)
set(GGML_USE_KOMPUTE OFF CACHE BOOL "Enable Kompute backend" FORCE)
set(GGML_USE_NNAPI OFF CACHE BOOL "Enable NNAPI backend" FORCE)

# Define upstream ggml integration switch early so it affects build graph
# English note: Default OFF to use locally split ggml (ggml-base/ggml-cpu)
option(USE_UPSTREAM_GGML "Use upstream ggml CMake (CPU/KleidiAI) and keep local Vulkan" OFF)
message(STATUS "USE_UPSTREAM_GGML: ${USE_UPSTREAM_GGML}")
# Force local ggml on Android path to avoid missing ggml::ggml-base target (English logs)
if(USE_UPSTREAM_GGML)
    message(WARNING "Forcing USE_UPSTREAM_GGML=OFF to use local ggml-base/ggml-cpu integration for JNI build")
    set(USE_UPSTREAM_GGML OFF CACHE BOOL "Use upstream ggml CMake (CPU/KleidiAI) and keep local Vulkan" FORCE)
endif()

# 创建模块化的ggml库架构
# 1. 创建ggml-base库（核心功能）
if(NOT USE_UPSTREAM_GGML)
set(GGML_BASE_SOURCES
    "${LLAMA_CPP_DIR}/ggml/src/ggml.c"
    "${LLAMA_CPP_DIR}/ggml/src/ggml.cpp"
    "${LLAMA_CPP_DIR}/ggml/src/ggml-alloc.c"
    "${LLAMA_CPP_DIR}/ggml/src/ggml-backend.cpp"
    "${LLAMA_CPP_DIR}/ggml/src/ggml-backend-reg.cpp"
    "${LLAMA_CPP_DIR}/ggml/src/ggml-quants.c"
    # 需要 GGUF 读写与元数据支持
    "${LLAMA_CPP_DIR}/ggml/src/gguf.cpp"
    # 线程临界区实现（ggml_critical_section_* 等）
    "${LLAMA_CPP_DIR}/ggml/src/ggml-threading.cpp"
    # 优化与数据集 API 实现（ggml_opt_*）
    "${LLAMA_CPP_DIR}/ggml/src/ggml-opt.cpp"
)

# 添加ggml-base库
add_library(ggml-base STATIC ${GGML_BASE_SOURCES})
target_include_directories(ggml-base PUBLIC 
    "${LLAMA_CPP_DIR}/ggml/include"
    "${LLAMA_CPP_DIR}/ggml/src"
)
# ggml.c 需要 GGML_VERSION/GGML_COMMIT 宏；若上游未定义，则提供缺省值，避免编译失败
# 正确的 CMake 书写应为 GGML_VERSION="unknown"，让编译命令行成为 -DGGML_VERSION="unknown"
# 之前的转义过度导致命令行宏非法，已修正
# 同时明确开启 CPU 后端注册（关键修复：保证 CPU backend 注册到 registry）
target_compile_definitions(ggml-base PUBLIC
    GGML_VERSION="unknown"
    GGML_COMMIT="unknown"
    GGML_USE_CPU=1
    GGML_USE_K_QUANTS=1
)

# 2. 创建ggml-cpu库
set(GGML_CPU_SOURCES
    "${LLAMA_CPP_DIR}/ggml/src/ggml-cpu/ggml-cpu.cpp"
    "${LLAMA_CPP_DIR}/ggml/src/ggml-cpu/ggml-cpu.c"
    # 补全上游必需实现文件，解决链接缺符号
    "${LLAMA_CPP_DIR}/ggml/src/ggml-cpu/repack.cpp"
    "${LLAMA_CPP_DIR}/ggml/src/ggml-cpu/hbm.cpp"
    "${LLAMA_CPP_DIR}/ggml/src/ggml-cpu/quants.c"
    "${LLAMA_CPP_DIR}/ggml/src/ggml-cpu/traits.cpp"
    "${LLAMA_CPP_DIR}/ggml/src/ggml-cpu/binary-ops.cpp"
    "${LLAMA_CPP_DIR}/ggml/src/ggml-cpu/unary-ops.cpp"
    "${LLAMA_CPP_DIR}/ggml/src/ggml-cpu/vec.cpp"
    "${LLAMA_CPP_DIR}/ggml/src/ggml-cpu/ops.cpp"
    # x86 AMX 源文件仅在 x86/x86_64 下加入，避免在 ARM 工具链无谓编译
    # "${LLAMA_CPP_DIR}/ggml/src/ggml-cpu/amx/amx.cpp"
    # "${LLAMA_CPP_DIR}/ggml/src/ggml-cpu/amx/mmq.cpp"
)
# Guard x86 AMX sources by ABI (English note)
if(ANDROID_ABI STREQUAL "x86_64" OR ANDROID_ABI STREQUAL "x86")
    list(APPEND GGML_CPU_SOURCES
        "${LLAMA_CPP_DIR}/ggml/src/ggml-cpu/amx/amx.cpp"
        "${LLAMA_CPP_DIR}/ggml/src/ggml-cpu/amx/mmq.cpp"
    )
endif()

# === KleidiAI integration (arm64-v8a only) ===
if(ANDROID_ABI STREQUAL "arm64-v8a")
    set(ENABLE_KLEIDIAI ON)
    # 追加 ggml KleidiAI glue 源（适配 ggml 与 KleidiAI ukernels）
    list(APPEND GGML_CPU_SOURCES
        "${LLAMA_CPP_DIR}/ggml/src/ggml-cpu/kleidiai/kleidiai.cpp"
        "${LLAMA_CPP_DIR}/ggml/src/ggml-cpu/kleidiai/kernels.cpp"
    )
    # 使用本地 KleidiAI 子模块（libs/kleidiai）中的最小内核与打包实现（NEON + dotprod）
    set(KLEIDIAI_SRC "${CMAKE_CURRENT_SOURCE_DIR}/../../../../kleidiai")
    if(EXISTS "${KLEIDIAI_SRC}")
        message(STATUS "KleidiAI: using local submodule at ${KLEIDIAI_SRC}")
        list(APPEND GGML_CPU_SOURCES
            # packers
            "${KLEIDIAI_SRC}/kai/ukernels/matmul/pack/kai_lhs_quant_pack_qsi8d32p_f32.c"
            "${KLEIDIAI_SRC}/kai/ukernels/matmul/pack/kai_rhs_pack_nxk_qsi4c32pscalef16_qsu4c32s16s0.c"
            # optional NEON-specific packers to improve coverage
            "${KLEIDIAI_SRC}/kai/ukernels/matmul/pack/kai_lhs_quant_pack_qsi8d32p_f32_neon.c"
            # dotprod matmul variants (qsi8d32p x qsi4c32p)
            "${KLEIDIAI_SRC}/kai/ukernels/matmul/matmul_clamp_f32_qsi8d32p_qsi4c32p/kai_matmul_clamp_f32_qsi8d32p1x4_qsi4c32p4x4_1x4_neon_dotprod.c"
            "${KLEIDIAI_SRC}/kai/ukernels/matmul/matmul_clamp_f32_qsi8d32p_qsi4c32p/kai_matmul_clamp_f32_qsi8d32p1x8_qsi4c32p4x8_1x4x32_neon_dotprod.c"
            "${KLEIDIAI_SRC}/kai/ukernels/matmul/matmul_clamp_f32_qsi8d32p_qsi4c32p/kai_matmul_clamp_f32_qsi8d32p4x4_qsi4c32p4x4_16x4_neon_dotprod.c"
        )
        set(HAVE_LOCAL_KLEIDIAI TRUE)
    else()
        message(WARNING "KleidiAI submodule not found at ${KLEIDIAI_SRC}; continuing without KleidiAI ukernels (CPU fallback)")
        set(ENABLE_KLEIDIAI OFF)
    endif()
endif()

add_library(ggml-cpu STATIC ${GGML_CPU_SOURCES})

# English note: enable generic-to-symbol fallback to satisfy K-quants symbols on ARM/Android when arch-specific quants are not compiled
message(STATUS "ggml-cpu: Enabling GGML_CPU_GENERIC fallback (map *_generic -> expected symbols)")
# This makes arch-fallback.h remap *_generic implementations to non-suffixed names (e.g., ggml_vec_dot_q4_K_q8_K)
# avoiding undefined references when only ggml-cpu/quants.c is built
# KleidiAI: also propagate GGML_USE_CPU_KLEIDIAI when enabled
if(ENABLE_KLEIDIAI)
    target_compile_definitions(ggml-cpu PRIVATE GGML_CPU_KLEIDIAI=1 GGML_USE_CPU_KLEIDIAI=1 GGML_USE_DOTPROD=1)
endif()

target_compile_definitions(ggml-cpu PRIVATE GGML_CPU_GENERIC=1)

# 正确设置 ggml-cpu 的包含目录（不要将目标名作为包含目录）
target_include_directories(ggml-cpu
    PRIVATE
        "${LLAMA_CPP_DIR}/ggml/src/ggml-cpu"
        "${LLAMA_CPP_DIR}/ggml/src"
)
if(ENABLE_KLEIDIAI AND HAVE_LOCAL_KLEIDIAI)
    target_include_directories(ggml-cpu PRIVATE "${KLEIDIAI_SRC}" "${KLEIDIAI_SRC}/kai")
    # Add ukernels subdirectories so headers like kai_matmul_* can be found
    target_include_directories(ggml-cpu PRIVATE "${KLEIDIAI_SRC}/kai/ukernels/matmul/pack")
    target_include_directories(ggml-cpu PRIVATE "${KLEIDIAI_SRC}/kai/ukernels/matmul/matmul_clamp_f32_qsi8d32p_qsi4c32p")
    # Add bf16 matmul headers directory to satisfy includes in kleidiai/kernels.cpp
    target_include_directories(ggml-cpu PRIVATE "${KLEIDIAI_SRC}/kai/ukernels/matmul/matmul_clamp_fp32_bf16p_bf16p")
endif()
# 维护依赖顺序：ggml-cpu 依赖 ggml-base
target_link_libraries(ggml-cpu PUBLIC ggml-base)

# Create ggml interface alias for upstream llama compatibility  
if(NOT TARGET ggml)
    add_library(ggml INTERFACE)
    # Propagate ggml include dirs to consumers (like upstream llama)
    target_include_directories(ggml INTERFACE
        "${LLAMA_CPP_DIR}/ggml/include"
        "${LLAMA_CPP_DIR}/ggml/src"
        "${LLAMA_CPP_DIR}/ggml/src/ggml-cpu"
    )
    # Link our local ggml components so transitive link works
    target_link_libraries(ggml INTERFACE ggml-base ggml-cpu)
    message(STATUS "Created ggml interface alias linking to ggml-base and ggml-cpu with include dirs")
endif()

# Provide imported namespace aliases for robustness when external code expects ggml::ggml-base or ggml::ggml
if(NOT TARGET ggml::ggml-base)
    add_library(ggml::ggml-base INTERFACE IMPORTED)
    set_target_properties(ggml::ggml-base PROPERTIES
        INTERFACE_LINK_LIBRARIES ggml-base
        INTERFACE_INCLUDE_DIRECTORIES "$<BUILD_INTERFACE:${LLAMA_CPP_DIR}/ggml/include;${LLAMA_CPP_DIR}/ggml/src;${LLAMA_CPP_DIR}/ggml/src/ggml-cpu>"
    )
    message(STATUS "Created imported alias ggml::ggml-base -> ggml-base")
endif()
if(NOT TARGET ggml::ggml)
    add_library(ggml::ggml INTERFACE IMPORTED)
    set_target_properties(ggml::ggml PROPERTIES
        INTERFACE_LINK_LIBRARIES ggml
        INTERFACE_INCLUDE_DIRECTORIES "$<BUILD_INTERFACE:${LLAMA_CPP_DIR}/ggml/include;${LLAMA_CPP_DIR}/ggml/src;${LLAMA_CPP_DIR}/ggml/src/ggml-cpu>"
    )
    message(STATUS "Created imported alias ggml::ggml -> ggml")
endif()
endif()

# Make upstream ggml module includes resolvable via CMAKE_MODULE_PATH (English log)
list(APPEND CMAKE_MODULE_PATH "${LLAMA_CPP_DIR}")
message(STATUS "CMAKE_MODULE_PATH += ${LLAMA_CPP_DIR} for ggml/cmake/common.cmake and cmake/common.cmake")
# Load upstream cmake utilities required for compile flags/macros
# 1) Include ggml's common first for ggml-specific helpers
include("${LLAMA_CPP_DIR}/ggml/cmake/common.cmake")
# 2) Then include llama's top-level common for llama_add_compile_flags used by src/CMakeLists.txt
# Disabled to avoid include(ggml/cmake/common.cmake) via CMAKE_MODULE_PATH which may fail under AGP cmake
# if(EXISTS "${LLAMA_CPP_DIR}/cmake/common.cmake")
#     include("${LLAMA_CPP_DIR}/cmake/common.cmake")
#     message(STATUS "Included llama top-level cmake/common.cmake (defines llama_add_compile_flags)")
# endif()
# 2b) If still missing, provide a local stub to avoid configuration failure
if(NOT COMMAND llama_add_compile_flags)
    function(llama_add_compile_flags)
        # no-op: safe fallback for JNI build
    endfunction()
    message(STATUS "Defined local stub for llama_add_compile_flags (upstream not providing)")
endif()

# Add llama core library from upstream source (ensure this runs after common.cmake)
add_subdirectory("${LLAMA_CPP_DIR}/src" "${CMAKE_CURRENT_BINARY_DIR}/llama-src")
message(STATUS "Added upstream llama source from: ${LLAMA_CPP_DIR}/src")
# 新增：引入上游 common 以提供 common_tokenize/common_batch_* 实现（English log only）
add_subdirectory("${LLAMA_CPP_DIR}/common" "${CMAKE_CURRENT_BINARY_DIR}/llama-common")
message(STATUS "Added upstream llama common from: ${LLAMA_CPP_DIR}/common")

# 4. 如果启用Vulkan，创建ggml-vulkan库
if(GGML_USE_VULKAN AND (ANDROID OR Vulkan_FOUND))
    # 如果使用外部工具链，确保参数被正确引用，避免Windows反斜杠产生转义问题
    if(GGML_USE_VULKAN AND (ANDROID OR Vulkan_FOUND))
        # 传递给vulkan-shaders-gen的toolchain参数需要加引号
        if(GGML_VULKAN_SHADERS_GEN_TOOLCHAIN)
            list(APPEND _vkgen_cmake_args "-DCMAKE_TOOLCHAIN_FILE=${GGML_VULKAN_SHADERS_GEN_TOOLCHAIN}")
        endif()
    
        # Ninja 检测和提示（仅提示，不中断构建）
        if(NOT CMAKE_MAKE_PROGRAM)
            find_program(NINJA_EXE NAMES ninja ninja.exe)
            if(NINJA_EXE)
                message(STATUS "Found Ninja: ${NINJA_EXE}")
            else()
                message(WARNING "Ninja not found. ExternalProject for Vulkan shader generation requires Ninja on host. Please install Ninja or set CMAKE_MAKE_PROGRAM.")
            endif()
        endif()
    endif()
endif()

# 禁用预生成着色器路径，强制使用宿主生成器 (ExternalProject)

# Pre-check Vulkan-Hpp availability (English logs only)
set(HAVE_VULKAN_HPP FALSE)
if(ENABLE_VULKAN_BACKEND)
    unset(VULKAN_HPP_INCLUDE_DIR)
    if(DEFINED VULKAN_HPP_DIR AND EXISTS "${VULKAN_HPP_DIR}/vulkan/vulkan.hpp")
        set(VULKAN_HPP_INCLUDE_DIR "${VULKAN_HPP_DIR}")
        set(HAVE_VULKAN_HPP TRUE)
        message(STATUS "ggml-vulkan: Using VULKAN_HPP_DIR=${VULKAN_HPP_DIR}")
    elseif(DEFINED Vulkan_INCLUDE_DIRS AND EXISTS "${Vulkan_INCLUDE_DIRS}/vulkan/vulkan.hpp")
        set(VULKAN_HPP_INCLUDE_DIR "${Vulkan_INCLUDE_DIRS}")
        set(HAVE_VULKAN_HPP TRUE)
        message(STATUS "ggml-vulkan: Using Vulkan_INCLUDE_DIRS=${Vulkan_INCLUDE_DIRS}")
    elseif(EXISTS "$ENV{VULKAN_SDK}/Include/vulkan/vulkan.hpp")
        set(VULKAN_HPP_INCLUDE_DIR "$ENV{VULKAN_SDK}/Include")
        set(HAVE_VULKAN_HPP TRUE)
        message(STATUS "ggml-vulkan: Using VULKAN_SDK include: $ENV{VULKAN_SDK}/Include")
    else()
        message(WARNING "Vulkan backend enabled but no valid vulkan.hpp found; disabling Vulkan backend for JNI build")
        set(ENABLE_VULKAN_BACKEND OFF CACHE BOOL "Enable Vulkan backend" FORCE)
    endif()
endif()

# ========== Generate ggml-vulkan shaders (use upstream generator) ==========
if(ENABLE_VULKAN_BACKEND)
    include(ExternalProject)

    set(_vkgen_source_dir  "${LLAMA_CPP_DIR}/ggml/src/ggml-vulkan/vulkan-shaders")
    if(CMAKE_HOST_WIN32)
        set(_vkgen_build_dir   "${CMAKE_CURRENT_BINARY_DIR}/vulkan-shaders-build-vs")
    else()
        set(_vkgen_build_dir   "${CMAKE_CURRENT_BINARY_DIR}/vulkan-shaders-build-ninja")
    endif()
    set(_vkgen_install_dir "${CMAKE_CURRENT_BINARY_DIR}/vulkan-shaders-install")

    if(NOT EXISTS ${_vkgen_source_dir})
        message(WARNING "vulkan-shaders: source directory not found: ${_vkgen_source_dir} - Vulkan backend disabled")
        set(ENABLE_VULKAN_BACKEND OFF)
    endif()

    # glslc discovery hints (English logs only)
    set(_glslc_hints
        $ENV{VULKAN_SDK}/Bin
        "D:/tools/VulkanSDK/1.4.321.1/Bin"
        $ENV{ANDROID_NDK}/shader-tools
        $ENV{ANDROID_NDK_HOME}/shader-tools
        $ENV{ANDROID_NDK_ROOT}/shader-tools
        $ENV{NDK_ROOT}/shader-tools
        $ENV{ANDROID_NDK}/toolchains/llvm/prebuilt/windows-x86_64/bin
        $ENV{ANDROID_NDK_HOME}/toolchains/llvm/prebuilt/windows-x86_64/bin
        $ENV{ANDROID_NDK_ROOT}/toolchains/llvm/prebuilt/windows-x86_64/bin
        $ENV{NDK_ROOT}/toolchains/llvm/prebuilt/windows-x86_64/bin
    )
    if(NOT Vulkan_GLSLC_EXECUTABLE)
        find_program(Vulkan_GLSLC_EXECUTABLE NAMES glslc glslc.exe HINTS ${_glslc_hints})
    endif()
    if(NOT Vulkan_GLSLC_EXECUTABLE)
        message(WARNING "Vulkan backend enabled but glslc not found; disabling Vulkan backend for JNI build")
        set(ENABLE_VULKAN_BACKEND OFF CACHE BOOL "Enable Vulkan backend" FORCE)
    else()
        message(STATUS "glslc found at: ${Vulkan_GLSLC_EXECUTABLE}")

        set(_vkgen_cmake_args)
        execute_process(
            COMMAND "${Vulkan_GLSLC_EXECUTABLE}" -o - -fshader-stage=compute --target-env=vulkan1.3 "${_vkgen_source_dir}/test_coopmat_support.comp"
            OUTPUT_VARIABLE _vkgen_out
            ERROR_VARIABLE _vkgen_err
        )
        if (NOT _vkgen_err MATCHES ".*extension not supported: GL_KHR_cooperative_matrix.*")
            list(APPEND _vkgen_cmake_args -DGGML_VULKAN_COOPMAT_GLSLC_SUPPORT=ON)
            message(STATUS "GL_KHR_cooperative_matrix supported by glslc (forwarding to generator)")
        else()
            message(STATUS "GL_KHR_cooperative_matrix not supported by glslc")
        endif()

        execute_process(
            COMMAND "${Vulkan_GLSLC_EXECUTABLE}" -o - -fshader-stage=compute --target-env=vulkan1.3 "${_vkgen_source_dir}/test_coopmat2_support.comp"
            OUTPUT_VARIABLE _vkgen_out
            ERROR_VARIABLE _vkgen_err
        )
        if (NOT _vkgen_err MATCHES ".*extension not supported: GL_NV_cooperative_matrix2.*")
            list(APPEND _vkgen_cmake_args -DGGML_VULKAN_COOPMAT2_GLSLC_SUPPORT=ON)
            message(STATUS "GL_NV_cooperative_matrix2 supported by glslc (forwarding to generator)")
        else()
            message(STATUS "GL_NV_cooperative_matrix2 not supported by glslc")
        endif()

        execute_process(
            COMMAND "${Vulkan_GLSLC_EXECUTABLE}" -o - -fshader-stage=compute --target-env=vulkan1.3 "${_vkgen_source_dir}/test_integer_dot_support.comp"
            OUTPUT_VARIABLE _vkgen_out
            ERROR_VARIABLE _vkgen_err
        )
        if (NOT _vkgen_err MATCHES ".*extension not supported: GL_EXT_integer_dot_product.*")
            list(APPEND _vkgen_cmake_args -DGGML_VULKAN_INTEGER_DOT_GLSLC_SUPPORT=ON)
            message(STATUS "GL_EXT_integer_dot_product supported by glslc (forwarding to generator)")
        else()
            message(STATUS "GL_EXT_integer_dot_product not supported by glslc")
        endif()

        execute_process(
            COMMAND "${Vulkan_GLSLC_EXECUTABLE}" -o - -fshader-stage=compute --target-env=vulkan1.3 "${_vkgen_source_dir}/test_bfloat16_support.comp"
            OUTPUT_VARIABLE _vkgen_out
            ERROR_VARIABLE _vkgen_err
        )
        if (NOT _vkgen_err MATCHES ".*extension not supported: GL_EXT_bfloat16.*")
            list(APPEND _vkgen_cmake_args -DGGML_VULKAN_BFLOAT16_GLSLC_SUPPORT=ON)
            message(STATUS "GL_EXT_bfloat16 supported by glslc (forwarding to generator)")
        else()
            message(STATUS "GL_EXT_bfloat16 not supported by glslc")
        endif()

        # Determine host suffix for generator (English note)
        if(CMAKE_HOST_WIN32)
            set(_ggml_vk_host_suffix ".exe")
        else()
            set(_ggml_vk_host_suffix "")
        endif()
    endif() # Vulkan_GLSLC_EXECUTABLE
endif() # ENABLE_VULKAN_BACKEND

if(ENABLE_VULKAN_BACKEND)
    set(_vkgen_toolchain_arg "")
    if(GGML_VULKAN_SHADERS_GEN_TOOLCHAIN)
        set(_vkgen_toolchain_arg "-DCMAKE_TOOLCHAIN_FILE=${GGML_VULKAN_SHADERS_GEN_TOOLCHAIN}")
    endif()

    if(CMAKE_HOST_WIN32)
        ExternalProject_Add(vulkan-shaders-gen-ext
            SOURCE_DIR "${_vkgen_source_dir}"
            BINARY_DIR "${_vkgen_build_dir}"
            CMAKE_GENERATOR "Visual Studio 17 2022"
            CMAKE_GENERATOR_PLATFORM "x64"
            CMAKE_ARGS
                       -DCMAKE_INSTALL_PREFIX=${_vkgen_install_dir}
                       -DCMAKE_INSTALL_BINDIR=.
                       -DCMAKE_BUILD_TYPE=Release
                       ${_vkgen_toolchain_arg}
                       ${_vkgen_cmake_args}
            BUILD_COMMAND     ${CMAKE_COMMAND} --build . --config Release
            # BUILD_ALWAYS TRUE  # disabled to improve incremental builds
            INSTALL_COMMAND   ${CMAKE_COMMAND} -E env --unset=DESTDIR "${CMAKE_COMMAND}" --install . --config Release
        )
    else()
        ExternalProject_Add(vulkan-shaders-gen-ext
            SOURCE_DIR "${_vkgen_source_dir}"
            BINARY_DIR "${_vkgen_build_dir}"
            CMAKE_ARGS -G Ninja
                       -DCMAKE_MAKE_PROGRAM=${CMAKE_MAKE_PROGRAM}
                       -DCMAKE_INSTALL_PREFIX=${_vkgen_install_dir}
                       -DCMAKE_INSTALL_BINDIR=.
                       -DCMAKE_BUILD_TYPE=Release
                       ${_vkgen_toolchain_arg}
                       ${_vkgen_cmake_args}
            BUILD_COMMAND     ${CMAKE_COMMAND} --build .
            # BUILD_ALWAYS TRUE  # disabled to improve incremental builds
            INSTALL_COMMAND   ${CMAKE_COMMAND} -E env --unset=DESTDIR "${CMAKE_COMMAND}" --install .
        )
    endif()

    set(_ggml_vk_header "${CMAKE_CURRENT_BINARY_DIR}/ggml-vulkan-shaders.hpp")
    set(_ggml_vk_source "${CMAKE_CURRENT_BINARY_DIR}/ggml-vulkan-shaders.cpp")
    set(_ggml_vk_input_dir  "${LLAMA_CPP_DIR}/ggml/src/ggml-vulkan/vulkan-shaders")
    set(_ggml_vk_output_dir "${CMAKE_CURRENT_BINARY_DIR}/vulkan-shaders.spv")

    set(_ggml_vk_genshaders_cmd "${_vkgen_install_dir}/vulkan-shaders-gen${_ggml_vk_host_suffix}")

    add_custom_command(
        OUTPUT ${_ggml_vk_header} ${_ggml_vk_source}
        COMMAND ${_ggml_vk_genshaders_cmd}
                --glslc      "${Vulkan_GLSLC_EXECUTABLE}"
                --input-dir  "${_ggml_vk_input_dir}"
                --output-dir "${_ggml_vk_output_dir}"
                --target-hpp "${_ggml_vk_header}"
                --target-cpp "${_ggml_vk_source}"
                --no-clean
        DEPENDS vulkan-shaders-gen-ext
        COMMENT "Generate vulkan shaders (ggml-vulkan)"
    )
endif()
# ========== 创建 ggml-vulkan 静态库（使用本地 ggml-vulkan.cpp + 生成的 shaders） ==========
if(ENABLE_VULKAN_BACKEND AND HAVE_VULKAN_HPP)
    set(GGML_VULKAN_SOURCES
        "${CMAKE_CURRENT_SOURCE_DIR}/ggml-vulkan.cpp"
        ${_ggml_vk_source}
    )
    add_library(ggml-vulkan STATIC ${GGML_VULKAN_SOURCES})
    add_dependencies(ggml-vulkan vulkan-shaders-gen-ext)

    # Ensure ABI alignment and PIC for static lib linkage into shared JNI
    set_target_properties(ggml-vulkan PROPERTIES
        POSITION_INDEPENDENT_CODE ON
        CXX_STANDARD 17
        CXX_STANDARD_REQUIRED ON)

    # Link ggml-vulkan against proper ggml base depending on backend choice
    if(USE_UPSTREAM_GGML)
        target_link_libraries(ggml-vulkan PUBLIC ggml::ggml-base)
    else()
        target_link_libraries(ggml-vulkan PUBLIC ggml-base)
    endif()

    # 设置Vulkan编译定义
    target_compile_definitions(ggml-vulkan PRIVATE 
        VULKAN_HPP_DISPATCH_LOADER_DYNAMIC=1
        VK_USE_PLATFORM_ANDROID_KHR=1
        VK_API_VERSION=VK_API_VERSION_1_2
    )

    # 添加Vulkan包含路径（VulkanSDK 头文件 + 本目录生成头）
    target_include_directories(ggml-vulkan PRIVATE 
        "${LLAMA_CPP_DIR}/ggml/src/ggml-vulkan"
        "${Vulkan_INCLUDE_DIRS}"
        "${CMAKE_CURRENT_SOURCE_DIR}"
        "${CMAKE_CURRENT_BINARY_DIR}"
    )
    if(DEFINED VULKAN_HPP_INCLUDE_DIR)
        target_include_directories(ggml-vulkan PRIVATE "${VULKAN_HPP_INCLUDE_DIR}")
    endif()
endif()

# Ensure ggml-vulkan links to Vulkan loader library on Android and non-Android
if(ENABLE_VULKAN_BACKEND AND HAVE_VULKAN_HPP)
    if(ANDROID)
        target_link_libraries(ggml-vulkan PUBLIC ${Vulkan_LIBRARIES})
    elseif(Vulkan_FOUND)
        target_link_libraries(ggml-vulkan PUBLIC ${Vulkan_LIBRARIES})
    endif()
endif()

# ========== JNI shared library ==========
add_library(llamacpp_jni SHARED
     "${CMAKE_CURRENT_SOURCE_DIR}/llama_inference.cpp"
     "${CMAKE_CURRENT_SOURCE_DIR}/vulkan_runtime_detector.cpp"
     "${CMAKE_CURRENT_SOURCE_DIR}/vulkan_symbol_keeper.cpp"
     "${CMAKE_CURRENT_SOURCE_DIR}/ggml-backend-opencl-stub.cpp"
)

target_include_directories(llamacpp_jni PRIVATE
    "${CMAKE_CURRENT_SOURCE_DIR}"
    "${CMAKE_CURRENT_BINARY_DIR}"
)

if(ENABLE_VULKAN_BACKEND AND VULKAN_HPP_INCLUDE_DIR)
    target_include_directories(llamacpp_jni PRIVATE "${VULKAN_HPP_INCLUDE_DIR}")
    message(STATUS "llamacpp_jni: Injected Vulkan headers include path -> ${VULKAN_HPP_INCLUDE_DIR}")
endif()

target_compile_definitions(llamacpp_jni PRIVATE
    VULKAN_HPP_DISPATCH_LOADER_DYNAMIC=1
    VK_USE_PLATFORM_ANDROID_KHR=1
)

if(USE_UPSTREAM_GGML)
    target_link_libraries(llamacpp_jni PRIVATE ggml-cpu ggml::ggml-base common)
else()
    target_link_libraries(llamacpp_jni PRIVATE ggml-cpu ggml-base llama common)
endif()

if(ENABLE_VULKAN_BACKEND AND HAVE_VULKAN_HPP)
    target_link_libraries(llamacpp_jni PRIVATE ggml-vulkan)
    # Ensure JNI sees Vulkan as compiled-in for logging (do not inject into ggml targets)
    target_compile_definitions(llamacpp_jni PRIVATE GGML_USE_VULKAN=1 GGML_VULKAN=1)
endif()

# === Bridge KleidiAI compile definitions to JNI target (for runtime logs) ===
if(ENABLE_KLEIDIAI)
    target_compile_definitions(llamacpp_jni PRIVATE GGML_CPU_KLEIDIAI=1 GGML_USE_CPU_KLEIDIAI=1)
    message(STATUS "llamacpp_jni: Propagated KleidiAI macros -> GGML_CPU_KLEIDIAI=1, GGML_USE_CPU_KLEIDIAI=1")
endif()
if(ANDROID)
    target_link_libraries(llamacpp_jni PRIVATE ${log-lib} ${android-lib} dl)
endif()

# Friendly summary (English logs only)
message(STATUS "llamacpp_jni: ENABLE_VULKAN_BACKEND=${ENABLE_VULKAN_BACKEND}")
if(ENABLE_VULKAN_BACKEND)
    if(DEFINED VULKAN_HPP_INCLUDE_DIR)
        message(STATUS "llamacpp_jni: Using Vulkan headers from ${VULKAN_HPP_INCLUDE_DIR}")
    else()
        message(STATUS "llamacpp_jni: No explicit Vulkan headers directory provided")
    endif()
else()
    message(STATUS "llamacpp_jni: Vulkan backend disabled at build-time (GGML_USE_VULKAN=0)")
endif()