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
        # ARM64架构优化 - ARM64本身支持NEON，不需要-mfpu选项
        set(CMAKE_CXX_FLAGS "${CMAKE_CXX_FLAGS}  -march=armv8-a+fp+simd -fno-limit-debug-info")
        set(CMAKE_C_FLAGS "${CMAKE_C_FLAGS} -march=armv8-a+fp+simd")
    elseif(ANDROID_ABI STREQUAL "armeabi-v7a")
        # ARMv7架构优化
        set(CMAKE_CXX_FLAGS "${CMAKE_CXX_FLAGS}  -march=armv7-a -mfpu=neon")
        set(CMAKE_C_FLAGS "${CMAKE_C_FLAGS} -march=armv7-a -mfpu=neon")
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
set(GGML_USE_VULKAN OFF CACHE BOOL "Enable Vulkan backend" FORCE)
set(GGML_USE_CUDA OFF CACHE BOOL "Enable CUDA backend" FORCE)
set(GGML_VULKAN OFF CACHE BOOL "Enable Vulkan runtime" FORCE)
set(GGML_NO_ACCELERATE ON CACHE BOOL "Disable other acceleration methods" FORCE)

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
        
        # 尝试使用系统Vulkan SDK头文件（如果存在）
        set(HOST_VULKAN_INCLUDE "D:/tools/VulkanSDK/1.4.321.1/Include")
        if(EXISTS "${HOST_VULKAN_INCLUDE}")
            set(Vulkan_INCLUDE_DIRS "${HOST_VULKAN_INCLUDE}")
            message(STATUS "Using host Vulkan SDK headers: ${Vulkan_INCLUDE_DIRS}")
        else()
            # 回退到NDK Vulkan头文件
            set(Vulkan_INCLUDE_DIRS "${ANDROID_NDK}/sources/third_party/vulkan/src/include")
            message(STATUS "Using NDK Vulkan headers: ${Vulkan_INCLUDE_DIRS}")
        endif()
        
        # 添加Vulkan头文件路径
        include_directories("${Vulkan_INCLUDE_DIRS}")
    else()
        # 非Android平台使用标准查找
        find_package(Vulkan COMPONENTS glslc)
        if(NOT Vulkan_FOUND)
            message(WARNING "Vulkan not found, disabling Vulkan backend")
            set(GGML_USE_VULKAN OFF CACHE BOOL "Enable Vulkan backend" FORCE)
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
)

# 添加ggml-base库
add_library(ggml-base STATIC ${GGML_BASE_SOURCES})
target_include_directories(ggml-base PUBLIC 
    "${LLAMA_CPP_DIR}/ggml/include"
    "${LLAMA_CPP_DIR}/ggml/src"
)

# 2. 创建ggml-cpu库
set(GGML_CPU_SOURCES
    "${LLAMA_CPP_DIR}/ggml/src/ggml-cpu/ggml-cpu.cpp"
    "${LLAMA_CPP_DIR}/ggml/src/ggml-cpu/ggml-cpu.c"
)
add_library(ggml-cpu STATIC ${GGML_CPU_SOURCES})
target_link_libraries(ggml-cpu PUBLIC ggml-base)

# 4. 如果启用Vulkan，创建ggml-vulkan库
if(GGML_USE_VULKAN AND Vulkan_FOUND)
    # 创建ggml-vulkan静态库
    set(GGML_VULKAN_SOURCES
        "${LLAMA_CPP_DIR}/ggml/src/ggml-vulkan/ggml-vulkan.cpp"
        "${CMAKE_CURRENT_SOURCE_DIR}/generated/ggml-vulkan-shaders.cpp"
    )
    add_library(ggml-vulkan STATIC ${GGML_VULKAN_SOURCES})
    target_link_libraries(ggml-vulkan PUBLIC ggml-base)
    
    # 设置Vulkan编译定义
    target_compile_definitions(ggml-vulkan PRIVATE 
        VULKAN_HPP_DISPATCH_LOADER_DYNAMIC=1
        VK_USE_PLATFORM_ANDROID_KHR=1
        VK_API_VERSION=VK_API_VERSION_1_1
    )
    
    # 添加Vulkan包含路径
    target_include_directories(ggml-vulkan PRIVATE 
        "${LLAMA_CPP_DIR}/ggml/src/ggml-vulkan"
        "${Vulkan_INCLUDE_DIRS}"
        "${CMAKE_CURRENT_SOURCE_DIR}/generated"
    )
    
    # 链接Vulkan库到ggml-vulkan
    if(ANDROID)
        # Android上链接NDK的Vulkan库
        target_link_libraries(ggml-vulkan PRIVATE vulkan android log)
    else()
        target_link_libraries(ggml-vulkan PRIVATE Vulkan::Vulkan)
    endif()
    
    message(STATUS "ggml-vulkan backend included")
else()
    message(STATUS "Vulkan backend disabled; skipping ggml-vulkan")
endif()

# 5. 创建主ggml库，链接所有子库
add_library(ggml INTERFACE)
target_link_libraries(ggml INTERFACE ggml-base ggml-cpu)

# 如果启用Vulkan，将ggml-vulkan链接到主ggml库
if(GGML_USE_VULKAN AND Vulkan_FOUND)
    target_link_libraries(ggml INTERFACE ggml-vulkan)
    message(STATUS "ggml-vulkan linked to main ggml interface")
endif()

# 添加其他GGML源文件到相应的库
# 添加到ggml-base库
target_sources(ggml-base PRIVATE
    "${LLAMA_CPP_DIR}/ggml/src/ggml-opt.cpp"
    "${LLAMA_CPP_DIR}/ggml/src/ggml-threading.cpp"
    "${LLAMA_CPP_DIR}/ggml/src/gguf.cpp"
)

# 添加到ggml-cpu库
target_sources(ggml-cpu PRIVATE
    "${LLAMA_CPP_DIR}/ggml/src/ggml-cpu/ops.cpp"
    "${LLAMA_CPP_DIR}/ggml/src/ggml-cpu/binary-ops.cpp"
    "${LLAMA_CPP_DIR}/ggml/src/ggml-cpu/unary-ops.cpp"
    "${LLAMA_CPP_DIR}/ggml/src/ggml-cpu/vec.cpp"
    "${LLAMA_CPP_DIR}/ggml/src/ggml-cpu/traits.cpp"
    "${LLAMA_CPP_DIR}/ggml/src/ggml-cpu/quants.c"
    "${LLAMA_CPP_DIR}/ggml/src/ggml-cpu/repack.cpp"
)

# 根据目标架构添加架构特定的源文件到ggml-cpu库
if(ANDROID_ABI STREQUAL "arm64-v8a" OR ANDROID_ABI STREQUAL "armeabi-v7a")
    target_sources(ggml-cpu PRIVATE
        "${LLAMA_CPP_DIR}/ggml/src/ggml-cpu/arch/arm/quants.c"
        "${LLAMA_CPP_DIR}/ggml/src/ggml-cpu/arch/arm/repack.cpp"
        "${LLAMA_CPP_DIR}/ggml/src/ggml-cpu/arch/arm/cpu-feats.cpp"
    )
elseif(ANDROID_ABI STREQUAL "x86_64" OR ANDROID_ABI STREQUAL "x86")
    target_sources(ggml-cpu PRIVATE
        "${LLAMA_CPP_DIR}/ggml/src/ggml-cpu/arch/x86/quants.c"
        "${LLAMA_CPP_DIR}/ggml/src/ggml-cpu/arch/x86/repack.cpp"
        "${LLAMA_CPP_DIR}/ggml/src/ggml-cpu/arch/x86/cpu-feats.cpp"
    )
endif()



# Build-info file already added above

# Debug: Print build info file path
message(STATUS "BUILD_INFO_FILE: ${BUILD_INFO_FILE}")
message(STATUS "BUILD_NUMBER: ${BUILD_NUMBER}")
message(STATUS "BUILD_COMMIT: ${BUILD_COMMIT}")
message(STATUS "BUILD_COMPILER: ${BUILD_COMPILER}")
message(STATUS "BUILD_TARGET: ${BUILD_TARGET}")

# 获取Git版本信息
find_program(GIT_EXE NAMES git git.exe)
if(GIT_EXE)
    execute_process(COMMAND ${GIT_EXE} rev-list --count HEAD
        WORKING_DIRECTORY ${LLAMA_CPP_DIR}
        OUTPUT_VARIABLE GGML_BUILD_NUMBER
        OUTPUT_STRIP_TRAILING_WHITESPACE
        ERROR_QUIET
    )
    execute_process(COMMAND ${GIT_EXE} rev-parse --short HEAD
        WORKING_DIRECTORY ${LLAMA_CPP_DIR}
        OUTPUT_VARIABLE GGML_BUILD_COMMIT
        OUTPUT_STRIP_TRAILING_WHITESPACE
        ERROR_QUIET
    )
else()
    set(GGML_BUILD_NUMBER "0")
    set(GGML_BUILD_COMMIT "unknown")
endif()

# 设置版本信息
set(GGML_INSTALL_VERSION "0.0.${GGML_BUILD_NUMBER}")

# 添加llama.cpp相关的编译定义
add_definitions(
    -DGGML_USE_LLAMAFILE=0
    -DGGML_USE_CUDA=0
    -DGGML_USE_METAL=0
    -DGGML_USE_OPENCL=0
    -DGGML_USE_CPU=1
    -DGGML_VERSION="${GGML_INSTALL_VERSION}"
    -DGGML_COMMIT="${GGML_BUILD_COMMIT}"
    -DVULKAN_HPP_DISPATCH_LOADER_DYNAMIC=1
)

# 仅当启用了 Vulkan 时才定义相关宏
if(GGML_USE_VULKAN AND Vulkan_FOUND)
    add_definitions(-DGGML_USE_VULKAN=1 -DGGML_VULKAN=1)
    message(STATUS "Vulkan backend enabled: ${Vulkan_LIBRARIES}")
else()
    add_definitions(-DGGML_USE_VULKAN=0 -DGGML_VULKAN=0)
    message(STATUS "Vulkan backend disabled")
endif()

# Android平台特定宏
if(ANDROID)
    add_definitions(-DANDROID)
    add_definitions(-D__ANDROID__)
endif()

# 收集llama.cpp源文件（避免重复包含 ggml 源码）
file(GLOB LLAMA_SOURCES
    "${LLAMA_CPP_DIR}/src/*.cpp"
    "${LLAMA_CPP_DIR}/src/*.c"
    "${LLAMA_CPP_DIR}/common/*.cpp"
    "${LLAMA_CPP_DIR}/common/*.c"
)

# 排除不需要的文件
list(FILTER LLAMA_SOURCES EXCLUDE REGEX ".*main\.cpp$")
list(FILTER LLAMA_SOURCES EXCLUDE REGEX ".*test.*")
list(FILTER LLAMA_SOURCES EXCLUDE REGEX ".*example.*")

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

# 链接库：ggml + Android NDK 库
target_link_libraries(llamacpp_jni
    ggml
    ${log-lib}
    android
    m
)

# 如果启用Vulkan且已找到Vulkan包，且为Android平台，显式链接NDK vulkan
if(GGML_USE_VULKAN AND Vulkan_FOUND AND ANDROID)
    target_link_libraries(llamacpp_jni vulkan)
    message(STATUS "Linking Android NDK Vulkan library: vulkan")
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