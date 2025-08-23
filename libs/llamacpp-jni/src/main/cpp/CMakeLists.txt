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
        set(CMAKE_CXX_FLAGS "${CMAKE_CXX_FLAGS}  -march=armv8-a+fp+simd+fp16 -fno-limit-debug-info")
        set(CMAKE_C_FLAGS "${CMAKE_C_FLAGS} -march=armv8-a+fp+simd+fp16")
        # 启用ARM的fp16支持
        set(CMAKE_CXX_FLAGS "${CMAKE_CXX_FLAGS} -DGGML_USE_FP16_VA=1 -D__ARM_FEATURE_FP16_VECTOR_ARITHMETIC=1")
        set(CMAKE_C_FLAGS "${CMAKE_C_FLAGS} -DGGML_USE_FP16_VA=1 -D__ARM_FEATURE_FP16_VECTOR_ARITHMETIC=1")
        message(STATUS "Android ${ANDROID_ABI} - ARM fp16 instructions enabled")
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
set(BUILD_INFO_CMAKE_PATH "${CMAKE_CURRENT_SOURCE_DIR}/../../../llama.cpp-master/cmake/build-info.cmake")
if(EXISTS ${BUILD_INFO_CMAKE_PATH})
    include(${BUILD_INFO_CMAKE_PATH})
endif()

# Create build-info.cpp file with build information
set(BUILD_INFO_FILE "${CMAKE_CURRENT_BINARY_DIR}/build-info.cpp")
file(WRITE ${BUILD_INFO_FILE}
    "#include <cstdint>\n"
    "int LLAMA_BUILD_NUMBER = ${BUILD_NUMBER};\n"
    "const char * LLAMA_COMMIT = \"${BUILD_COMMIT}\";\n"
    "const char * LLAMA_COMPILER = \"${BUILD_COMPILER}\";\n"
    "const char * LLAMA_BUILD_TARGET = \"${BUILD_TARGET}\";\n"
)

# Add the generated file to the sources
list(APPEND LLAMA_SOURCES ${BUILD_INFO_FILE})

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
if(NOT DEFINED GGML_VULKAN)
    set(GGML_VULKAN ON CACHE BOOL "Enable Vulkan runtime")
endif()
set(GGML_USE_CUDA OFF CACHE BOOL "Enable CUDA backend" FORCE)
set(GGML_NO_ACCELERATE ON CACHE BOOL "Disable other acceleration methods" FORCE)
message(STATUS "GGML_USE_VULKAN: ${GGML_USE_VULKAN}, GGML_VULKAN: ${GGML_VULKAN}")

# 处理Vulkan相关的CMake版本兼容性问题
# 如果启用Vulkan，需要确保CMake版本满足要求
if(GGML_USE_VULKAN)
    # Vulkan backend需要CMake 3.19+，当前版本已满足
    if(CMAKE_VERSION VERSION_LESS "3.19")
        message(FATAL_ERROR "Vulkan backend requires CMake 3.19 or higher, current version: ${CMAKE_VERSION}")
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
        
        # 添加Vulkan头文件路径
        include_directories("${Vulkan_INCLUDE_DIRS}")
    else()
        # 非Android平台使用标准查找
        find_package(Vulkan COMPONENTS glslc)
        if(NOT Vulkan_FOUND)
            message(WARNING "Vulkan not found, disabling Vulkan backend on non-Android platform")
            set(GGML_USE_VULKAN OFF CACHE BOOL "Enable Vulkan backend")
        endif()
    endif()
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

# 创建模块化的ggml库架构
# 1. 创建ggml-base库（核心功能）
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
    # 某些 x86 专用源码包含 AMX/IMMINTRIN 接口，原始仓库无条件加入并通过宏保护
    "${LLAMA_CPP_DIR}/ggml/src/ggml-cpu/amx/amx.cpp"
    "${LLAMA_CPP_DIR}/ggml/src/ggml-cpu/amx/mmq.cpp"
)

# 按架构补充 arch 特定实现
if(ANDROID_ABI STREQUAL "arm64-v8a" OR ANDROID_ABI STREQUAL "armeabi-v7a")
    list(APPEND GGML_CPU_SOURCES
        "${LLAMA_CPP_DIR}/ggml/src/ggml-cpu/arch/arm/quants.c"
        "${LLAMA_CPP_DIR}/ggml/src/ggml-cpu/arch/arm/repack.cpp"
    )
elseif(ANDROID_ABI STREQUAL "x86_64" OR ANDROID_ABI STREQUAL "x86")
    list(APPEND GGML_CPU_SOURCES
        "${LLAMA_CPP_DIR}/ggml/src/ggml-cpu/arch/x86/quants.c"
        "${LLAMA_CPP_DIR}/ggml/src/ggml-cpu/arch/x86/repack.cpp"
    )
endif()

add_library(ggml-cpu STATIC ${GGML_CPU_SOURCES})
# 确保包含 ggml-cpu 目录头文件
target_include_directories(ggml-cpu PUBLIC ggml-base
    PRIVATE
        "${LLAMA_CPP_DIR}/ggml/src/ggml-cpu"
        "${LLAMA_CPP_DIR}/ggml/src"
)
# 维护依赖顺序：ggml-cpu 依赖 ggml-base
target_link_libraries(ggml-cpu PUBLIC ggml-base)

# 4. 如果启用Vulkan，创建ggml-vulkan库
if(GGML_USE_VULKAN AND (ANDROID OR Vulkan_FOUND))
    # 确保核心库在编译时包含Vulkan后端注册代码
    target_compile_definitions(ggml-base PUBLIC GGML_USE_VULKAN=1 GGML_VULKAN=1)

    # 禁用预生成着色器路径，强制使用宿主生成器 (ExternalProject)

    # ========== 生成 ggml-vulkan 着色器（使用上游生成器） ==========
    if(TRUE)
        # 引入 ExternalProject 模块（修复：之前未包含导致 ExternalProject_Add 未定义）
        include(ExternalProject)

        # 定义生成器源码/构建/安装目录变量（修复：之前未定义 _vkgen_* 导致变量未展开）
        set(_vkgen_source_dir  "${LLAMA_CPP_DIR}/ggml/src/ggml-vulkan/vulkan-shaders")
        if(CMAKE_HOST_WIN32)
            set(_vkgen_build_dir   "${CMAKE_CURRENT_BINARY_DIR}/vulkan-shaders-build-vs")
        else()
            set(_vkgen_build_dir   "${CMAKE_CURRENT_BINARY_DIR}/vulkan-shaders-build-ninja")
        endif()
        set(_vkgen_install_dir "${CMAKE_CURRENT_BINARY_DIR}/vulkan-shaders-install")
        set(_vkgen_toolchain_arg "")

        if(NOT EXISTS "${_vkgen_source_dir}")
            message(FATAL_ERROR "vulkan-shaders source directory not found: ${_vkgen_source_dir}")
        endif()

        # glslc 可执行文件（优先使用环境变量 VULKAN_SDK，其次使用常见安装路径提示；在 Android 构建下也尝试从 NDK 中寻找）
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
        find_program(Vulkan_GLSLC_EXECUTABLE NAMES glslc glslc.exe
            HINTS ${_glslc_hints}
            REQUIRED)
        message(STATUS "glslc found at: ${Vulkan_GLSLC_EXECUTABLE}")

        # 检测 glslc 对关键扩展的支持并将结果传递给生成器
        set(_vkgen_cmake_args)
        # GL_KHR_cooperative_matrix
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
        # GL_NV_cooperative_matrix2
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
        # GL_EXT_integer_dot_product
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
        # GL_EXT_bfloat16
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

        # 准备 host 工具链（交叉编译时用于生成器工程）
        set(_vkgen_toolchain_arg "")
        if(GGML_VULKAN_SHADERS_GEN_TOOLCHAIN)
            set(_vkgen_toolchain_arg "-DCMAKE_TOOLCHAIN_FILE=${GGML_VULKAN_SHADERS_GEN_TOOLCHAIN}")
        endif()

        if(CMAKE_HOST_WIN32)
            # 在 Windows 主机上直接使用 Visual Studio 生成器，避免 Ninja + vcvars 环境传递问题
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
                BUILD_ALWAYS TRUE
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
                BUILD_ALWAYS TRUE
                INSTALL_COMMAND   ${CMAKE_COMMAND} -E env --unset=DESTDIR "${CMAKE_COMMAND}" --install .
            )
        endif()
        # 生成的头/源路径
        set(_ggml_vk_header "${CMAKE_CURRENT_BINARY_DIR}/ggml-vulkan-shaders.hpp")
        set(_ggml_vk_source "${CMAKE_CURRENT_BINARY_DIR}/ggml-vulkan-shaders.cpp")
        set(_ggml_vk_input_dir  "${LLAMA_CPP_DIR}/ggml/src/ggml-vulkan/vulkan-shaders")
        set(_ggml_vk_output_dir "${CMAKE_CURRENT_BINARY_DIR}/vulkan-shaders.spv")

        # 主机生成器可执行文件路径
        set(_ggml_vk_host_suffix $<IF:$<STREQUAL:${CMAKE_HOST_SYSTEM_NAME},Windows>,.exe,>)
        set(_ggml_vk_genshaders_cmd "${_vkgen_install_dir}/vulkan-shaders-gen${_ggml_vk_host_suffix}")

        add_custom_command(
            OUTPUT ${_ggml_vk_header} ${_ggml_vk_source}
            COMMAND ${_ggml_vk_genshaders_cmd}
                    --glslc      ${Vulkan_GLSLC_EXECUTABLE}
                    --input-dir  ${_ggml_vk_input_dir}
                    --output-dir ${_ggml_vk_output_dir}
                    --target-hpp ${_ggml_vk_header}
                    --target-cpp ${_ggml_vk_source}
                    --no-clean
            DEPENDS vulkan-shaders-gen-ext
            COMMENT "Generate vulkan shaders (ggml-vulkan)"
        )
    endif()

    # ========== 创建 ggml-vulkan 静态库（切换为本地 ggml-vulkan.cpp + 生成的 shaders） ==========
    set(GGML_VULKAN_SOURCES
        "${CMAKE_CURRENT_SOURCE_DIR}/ggml-vulkan.cpp"
        ${_ggml_vk_source}
    )
    add_library(ggml-vulkan STATIC ${GGML_VULKAN_SOURCES})
    add_dependencies(ggml-vulkan vulkan-shaders-gen-ext)
    target_link_libraries(ggml-vulkan PUBLIC ggml-base)

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
    if(_use_pregenerated_shaders)
        target_include_directories(ggml-vulkan PRIVATE "${CMAKE_CURRENT_SOURCE_DIR}/generated")
    endif()

    # 链接Vulkan库到ggml-vulkan（Android 使用 NDK Vulkan）
    if(ANDROID)
        target_link_libraries(ggml-vulkan PRIVATE vulkan android log)
    else()
        target_link_libraries(ggml-vulkan PRIVATE Vulkan::Vulkan)
    endif()

    message(STATUS "ggml-vulkan backend included (with shader auto-generation)")
else()
    message(STATUS "Vulkan backend disabled; skipping ggml-vulkan")
endif()

# 仅当启用了 Vulkan 时才定义相关宏
if(GGML_USE_VULKAN AND (ANDROID OR Vulkan_FOUND))
    set(ENABLE_VULKAN_BACKEND ON)
    message(STATUS "Vulkan backend enabled: ${Vulkan_LIBRARIES}")
else()
    set(ENABLE_VULKAN_BACKEND OFF)
    message(STATUS "Vulkan backend disabled")
endif()

# Android平台特定宏
if(ANDROID)
    add_definitions(-DANDROID)
    add_definitions(-D__ANDROID__)
    add_definitions(-DVK_USE_PLATFORM_ANDROID_KHR=1)
endif()

# 收集llama.cpp源文件（避免重复包含 ggml 源码）
file(GLOB LLAMA_SOURCES
    "${LLAMA_CPP_DIR}/src/*.cpp"
    "${LLAMA_CPP_DIR}/src/*.c"
    "${LLAMA_CPP_DIR}/common/*.cpp"
    "${LLAMA_CPP_DIR}/common/*.c"
)

# 排除不需要的文件
list(FILTER LLAMA_SOURCES EXCLUDE REGEX ".*main\\.cpp$")
list(FILTER LLAMA_SOURCES EXCLUDE REGEX ".*test.*")
list(FILTER LLAMA_SOURCES EXCLUDE REGEX ".*example.*")
# llama.cpp 的 common/build-info.cpp 由其自身 CMake 生成，这里不使用，避免找不到文件或重复符号
list(FILTER LLAMA_SOURCES EXCLUDE REGEX ".*/common/build-info\\.cpp$")
# 追加本项目生成的 build-info.cpp，提供 LLAMA_BUILD_NUMBER/LLAMA_COMMIT 等符号
list(APPEND LLAMA_SOURCES ${BUILD_INFO_FILE})

# 创建JNI共享库，使用模块化的ggml库
add_library(llamacpp_jni SHARED
    llama_inference.cpp
    ggml-backend-opencl-stub.cpp
    vulkan_symbol_keeper.cpp
    vulkan_runtime_detector.cpp
    ${LLAMA_SOURCES}
)


# 设置目标属性
set_target_properties(llamacpp_jni PROPERTIES
    POSITION_INDEPENDENT_CODE ON
    CXX_STANDARD 17
    CXX_STANDARD_REQUIRED ON
)

# 链接库：使用模块化的 ggml 静态库 + Android NDK 库
# 统一改为 keyword 风格，避免与后续 PRIVATE 混用导致 CMake 报错
target_link_libraries(llamacpp_jni
    PRIVATE
        ggml-base
        ggml-cpu
        ${log-lib}
        android
        m
)

# 如果ggml-vulkan目标存在，为JNI目标补充必要的宏定义并链接Vulkan
if (TARGET ggml-vulkan)
    target_compile_definitions(llamacpp_jni PRIVATE
        GGML_USE_VULKAN=1
        GGML_VULKAN=1
        VULKAN_HPP_DISPATCH_LOADER_DYNAMIC=1
    )
    if (ANDROID)
        target_compile_definitions(llamacpp_jni PRIVATE VK_USE_PLATFORM_ANDROID_KHR=1)
    endif()

    target_link_libraries(llamacpp_jni PRIVATE ggml-vulkan)
    if (ANDROID)
        target_link_libraries(llamacpp_jni PRIVATE vulkan)
        message(STATUS "Linking Android NDK Vulkan library: vulkan")
    else()
        target_link_libraries(llamacpp_jni PRIVATE Vulkan::Vulkan)
    endif()
    message(STATUS "Linking ggml-vulkan static library into llamacpp_jni")
endif()

# 设置输出目录 - 直接输出到app的jniLibs目录
set(APP_JNILIBS_DIR "${CMAKE_CURRENT_SOURCE_DIR}/../../../../../app/src/main/jniLibs")
file(MAKE_DIRECTORY "${APP_JNILIBS_DIR}/${ANDROID_ABI}")

set_target_properties(llamacpp_jni PROPERTIES
    ARCHIVE_OUTPUT_DIRECTORY "${APP_JNILIBS_DIR}/${ANDROID_ABI}"
    LIBRARY_OUTPUT_DIRECTORY "${APP_JNILIBS_DIR}/${ANDROID_ABI}"
)

# 打印配置信息
message(STATUS "LlamaCpp JNI Configuration:")
message(STATUS "  CMAKE_BUILD_TYPE: ${CMAKE_BUILD_TYPE}")
message(STATUS "  ANDROID_ABI: ${ANDROID_ABI}")
message(STATUS "  CMAKE_CXX_FLAGS: ${CMAKE_CXX_FLAGS}")
message(STATUS "  CMAKE_C_FLAGS: ${CMAKE_C_FLAGS}")
message(STATUS "  LLAMA_CPP_DIR: ${LLAMA_CPP_DIR}")
message(STATUS "  LLAMA_SOURCES count: ${LLAMA_SOURCES}")
message(STATUS "  GGML backends: ggml-base, ggml-cpu, ggml-vulkan (if enabled)")

# 编译后处理
add_custom_command(TARGET llamacpp_jni POST_BUILD
    COMMAND ${CMAKE_COMMAND} -E echo "LlamaCpp JNI library built for ${ANDROID_ABI}"
    COMMAND ${CMAKE_COMMAND} -E echo "Output: ${APP_JNILIBS_DIR}/${ANDROID_ABI}/libllamacpp_jni.so"
)