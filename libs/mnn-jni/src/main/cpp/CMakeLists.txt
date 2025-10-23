cmake_minimum_required(VERSION 3.22.1)
project(mnn_jni)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

# ========== MNN Source Paths ==========
set(MNN_ROOT ${CMAKE_CURRENT_SOURCE_DIR}/../../../../mnn)
set(MNN_LLM_ROOT ${MNN_ROOT}/transformers/llm)

message(STATUS "MNN Root: ${MNN_ROOT}")
message(STATUS "MNN LLM Root: ${MNN_LLM_ROOT}")

# ========== Build Options ==========
option(MNN_BUILD_LLM "Build MNN LLM" ON)
option(MNN_BUILD_DIFFUSION "Build MNN Diffusion" ON)
option(MNN_BUILD_AUDIO "Build MNN Audio (Sherpa-MNN ASR + MNN-TTS)" ON)
option(MNN_BUILD_OPENCV "Build OpenCV (required for diffusion)" ON)
option(MNN_IMGCODECS "Enable image codecs (required for diffusion)" ON)
option(MNN_OPENCL "Enable OpenCL backend" ON)
option(MNN_VULKAN "Enable Vulkan backend" ON)
option(MNN_ARM82 "Enable ARM82 (fp16/dot) support" ON)
option(MNN_SUPPORT_TRANSFORMER_FUSE "Enable transformer fusion" ON)
option(MNN_LOW_MEMORY "Enable low memory mode" ON)  # Required for weight quant (int4/int8)
option(MNN_CPU_WEIGHT_DEQUANT_GEMM "Enable CPU weight dequant GEMM" ON)
option(MNN_USE_LOGCAT "Use Android logcat for MNN_PRINT/MNN_ERROR" ON)  # ON: Use logcat, logs will be captured by LogManager via logcat filtering
option(MNN_OPEN_TIME_TRACE "Enable AUTOTIME performance logging (CRITICAL for debugging)" ON)
option(LLMNN_SUPPORT_VISION "Enable vision support" ON)
option(MNN_KLEIDIAI "Enable KleidiAI" ON)

# CRITICAL: Always use Release-like settings for MNN to avoid Backend registration issues
# MNN's DEBUG mode enables assertions that can cause crashes in Debug builds
# We keep logging enabled but disable debug assertions and optimizations
set(MNN_DEBUG OFF CACHE BOOL "Disable MNN debug mode (use Release settings)" FORCE)

# ========== Compiler Flags ==========
set(CMAKE_CXX_FLAGS "${CMAKE_CXX_FLAGS} -fexceptions -frtti")
set(CMAKE_CXX_FLAGS "${CMAKE_CXX_FLAGS} -Wall -Wno-unused-function")

# CRITICAL: Use Release-like optimization for both Debug and Release builds
# This prevents MNN Backend registration issues caused by debug assertions
if(CMAKE_BUILD_TYPE STREQUAL "Debug")
    # For Debug builds: keep debug symbols but use Release optimization
    set(CMAKE_CXX_FLAGS_DEBUG "-g -O2" CACHE STRING "Debug flags with Release optimization" FORCE)
    message(STATUS "Debug build: Using -O2 optimization to match Release behavior")
else()
    # For Release builds: standard optimization
    set(CMAKE_CXX_FLAGS_RELEASE "-O3 -DNDEBUG" CACHE STRING "Release flags" FORCE)
endif()

if(ANDROID)
    set(CMAKE_CXX_FLAGS "${CMAKE_CXX_FLAGS} -fPIC")
    
    # Enable NEON for ARM
    if(ANDROID_ABI STREQUAL "armeabi-v7a")
        set(CMAKE_CXX_FLAGS "${CMAKE_CXX_FLAGS} -mfpu=neon")
    endif()
    
    # Enable ARM82 features for arm64-v8a
    if(ANDROID_ABI STREQUAL "arm64-v8a" AND MNN_ARM82)
        set(CMAKE_CXX_FLAGS "${CMAKE_CXX_FLAGS} -march=armv8.2-a+fp16+dotprod")
    endif()
endif()

# ========== Find or Build MNN ==========
# Option 1: Use prebuilt MNN libraries (recommended for faster builds)
# Option 2: Build MNN from source (for customization)

# Force pthread detection success for Android cross-compile
set(CMAKE_HAVE_LIBC_PTHREAD 1 CACHE INTERNAL "" FORCE)
set(CMAKE_USE_PTHREADS_INIT 1 CACHE INTERNAL "" FORCE)
set(THREADS_PREFER_PTHREAD_FLAG ON CACHE INTERNAL "" FORCE)
set(CMAKE_THREAD_LIBS_INIT "-pthread" CACHE INTERNAL "" FORCE)

set(MNN_BUILD_FROM_SOURCE ON)

if(MNN_BUILD_FROM_SOURCE)
    message(STATUS "Building MNN from source")
    
    # Note: Most MNN options (MNN_BUILD_AUDIO, MNN_OPENCL, etc.) are set via build.gradle arguments
    # Exception: NNAPI must be conditionally disabled for x86_64 due to FP16 symbol issues
    if(ANDROID_ABI STREQUAL "x86_64")
        set(MNN_NNAPI OFF CACHE BOOL "Disable NNAPI for x86_64 (FP16 symbol missing)" FORCE)
        message(STATUS "⚠️ NNAPI disabled for ${ANDROID_ABI} (FP16 symbol issues)")
    endif()
    
    # Add MNN as subdirectory
    add_subdirectory(${MNN_ROOT} ${CMAKE_CURRENT_BINARY_DIR}/mnn_build)
    
    # MNN targets will be available: MNN, MNN_CL, MNN_Vulkan, etc.
    set(MNN_LIBS MNN)
    
    if(MNN_OPENCL)
        list(APPEND MNN_LIBS MNN_CL)
    endif()
    
    if(MNN_VULKAN)
        list(APPEND MNN_LIBS MNN_Vulkan)
    endif()
    
else()
    message(STATUS "Using prebuilt MNN libraries")
    
    # Find prebuilt MNN libraries
    find_library(MNN_LIB MNN PATHS ${MNN_ROOT}/build/${ANDROID_ABI} NO_DEFAULT_PATH)
    
    if(NOT MNN_LIB)
        message(FATAL_ERROR "Prebuilt MNN library not found. Please build MNN first or set MNN_BUILD_FROM_SOURCE=ON")
    endif()
    
    set(MNN_LIBS ${MNN_LIB})
    
    # Add include directories
    include_directories(
        ${MNN_ROOT}/include
        ${MNN_ROOT}/source
        ${MNN_ROOT}/express
    )
endif()

# ========== Include Directories ==========
include_directories(
    ${MNN_ROOT}/include
    ${MNN_ROOT}/source
    ${MNN_ROOT}/express
    ${MNN_ROOT}/3rd_party
    ${MNN_LLM_ROOT}/engine/include
    ${MNN_LLM_ROOT}/engine/app
    ${MNN_ROOT}/transformers/diffusion/engine/include
    ${CMAKE_CURRENT_SOURCE_DIR}
)

# Add nlohmann/json
include_directories(${MNN_ROOT}/3rd_party/nlohmann)

# ========== Source Files ==========
set(JNI_SOURCES
    mnn_jni.cpp
)

# ========== Build JNI Library ==========
add_library(mnn_jni SHARED ${JNI_SOURCES})

# Link libraries
target_link_libraries(mnn_jni
    ${MNN_LIBS}
    llm             # CRITICAL: Link MNN LLM library (includes audio/vision support)
    android
    log
)

# ========== Compile Definitions ==========
target_compile_definitions(mnn_jni PRIVATE
    MNN_BUILD_LLM=1
    MNN_BUILD_DIFFUSION=1
)

# Enable audio support if MNN_BUILD_AUDIO is ON
if(MNN_BUILD_AUDIO)
    target_compile_definitions(mnn_jni PRIVATE LLM_SUPPORT_AUDIO=1)
    message(STATUS "✅ Audio support enabled (LLM_SUPPORT_AUDIO)")
endif()

# Enable vision support if LLMNN_SUPPORT_VISION is ON
if(LLMNN_SUPPORT_VISION)
    target_compile_definitions(mnn_jni PRIVATE LLM_SUPPORT_VISION=1)
    message(STATUS "✅ Vision support enabled (LLM_SUPPORT_VISION)")
endif()

if(MNN_OPENCL)
    target_compile_definitions(mnn_jni PRIVATE MNN_OPENCL=1)
    message(STATUS "OpenCL backend enabled")
endif()

if(MNN_VULKAN)
    target_compile_definitions(mnn_jni PRIVATE MNN_VULKAN=1)
    message(STATUS "Vulkan backend enabled")
endif()

if(MNN_NNAPI)
    target_compile_definitions(mnn_jni PRIVATE MNN_NNAPI=1)
    message(STATUS "NNAPI backend enabled")
endif()

if(MNN_KLEIDIAI)
    target_compile_definitions(mnn_jni PRIVATE MNN_KLEIDIAI=1)
    message(STATUS "KleidiAI backend enabled")
endif()

if(MNN_USE_LOGCAT)
    target_compile_definitions(mnn_jni PRIVATE MNN_USE_LOGCAT=1)
endif()

if(MNN_OPEN_TIME_TRACE)
    target_compile_definitions(mnn_jni PRIVATE MNN_OPEN_TIME_TRACE=1)
    message(STATUS "MNN time trace logging enabled")
endif()

# MNN_DEBUG is now always OFF (set above) to use Release-like behavior
# This prevents Backend registration issues in Debug builds

# ========== Installation ==========
install(TARGETS mnn_jni
    LIBRARY DESTINATION ${CMAKE_INSTALL_LIBDIR}
)

# ========== Build Info ==========
message(STATUS "========== MNN JNI Build Configuration ==========")
message(STATUS "Android ABI: ${ANDROID_ABI}")
message(STATUS "MNN_BUILD_LLM: ${MNN_BUILD_LLM}")
message(STATUS "MNN_BUILD_DIFFUSION: ${MNN_BUILD_DIFFUSION}")
message(STATUS "MNN_BUILD_AUDIO: ${MNN_BUILD_AUDIO}")
if(MNN_BUILD_AUDIO)
    message(STATUS "  ⚠️ Audio support requires additional integration work")
    message(STATUS "  ⚠️ Need to link Sherpa-MNN and MNN-TTS libraries")
endif()
message(STATUS "MNN_BUILD_OPENCV: ${MNN_BUILD_OPENCV}")
message(STATUS "MNN_IMGCODECS: ${MNN_IMGCODECS}")
message(STATUS "MNN_OPENCL: ${MNN_OPENCL}")
message(STATUS "MNN_VULKAN: ${MNN_VULKAN}")
message(STATUS "MNN_NNAPI: ${MNN_NNAPI}")
message(STATUS "MNN_ARM82: ${MNN_ARM82}")
message(STATUS "MNN_KLEIDIAI: ${MNN_KLEIDIAI}")
message(STATUS "MNN_SUPPORT_TRANSFORMER_FUSE: ${MNN_SUPPORT_TRANSFORMER_FUSE}")
message(STATUS "MNN_LOW_MEMORY: ${MNN_LOW_MEMORY}")
message(STATUS "MNN Libraries: ${MNN_LIBS}")
message(STATUS "================================================")

# ========== Custom CPU Operators ==========
# 方案：将自定义算子注入到上游 MNN 的 MNNCPU 目标
# 
# 优势：
# - 源文件由 JNI 项目维护（libs/mnn-jni/src/main/cpp/）
# - 编译由上游 MNN 管理（注入到 MNNCPU 目标）
# - 不污染上游 MNN 目录，git pull 不受影响
# - 只需修改本文件，无需改动上游 CMakeLists.txt
#
# 工作原理：
# 1. target_sources(MNNCPU) 将我们的 .cpp 添加到 MNN 的 CPU backend
# 2. target_include_directories(MNNCPU) 添加我们的目录到 include path
# 3. MNN 的构建系统负责编译和链接
#
# 未来添加新算子：
# 1. 在 libs/mnn-jni/src/main/cpp/ 创建 CPUXxxOp.hpp/cpp
# 2. 使用 #include "CPUXxxOp.hpp" (相对路径)
# 3. 在下方 CUSTOM_CPU_OPS_SRC 列表中添加
# 4. 使用 REGISTER_CPU_OP_CREATOR 宏注册

if(MNN_BUILD_FROM_SOURCE)
    # 自定义算子文件列表（与 mnn_jni.cpp 同目录）
    set(CUSTOM_CPU_OPS_SRC
        ${CMAKE_CURRENT_SOURCE_DIR}/CPUGroupNorm.cpp
        # 未来添加新算子：
        # ${CMAKE_CURRENT_SOURCE_DIR}/CPUYourOp.cpp
    )
    
    # 检查是否有自定义算子
    set(CUSTOM_OPS_FOUND FALSE)
    set(CUSTOM_OPS_LIST "")
    foreach(src ${CUSTOM_CPU_OPS_SRC})
        if(EXISTS ${src})
            set(CUSTOM_OPS_FOUND TRUE)
            get_filename_component(src_name ${src} NAME)
            list(APPEND CUSTOM_OPS_LIST ${src_name})
        endif()
    endforeach()
    
    if(CUSTOM_OPS_FOUND)
        # 将自定义算子源文件注入到 MNNCPU 目标
        # MNNCPU 是 MNN 在 source/backend/cpu/CMakeLists.txt 中定义的 OBJECT library
        target_sources(MNNCPU PRIVATE ${CUSTOM_CPU_OPS_SRC})
        
        # 添加当前目录到 MNNCPU 的 include path
        # 这样 #include "CPUGroupNorm.hpp" 就能找到头文件
        target_include_directories(MNNCPU PRIVATE ${CMAKE_CURRENT_SOURCE_DIR})
        
        message(STATUS "========== Custom CPU Operators ==========")
        message(STATUS "✅ Injected from: ${CMAKE_CURRENT_SOURCE_DIR}")
        foreach(op ${CUSTOM_OPS_LIST})
            message(STATUS "   - ${op}")
        endforeach()
        message(STATUS "==========================================")
    else()
        message(STATUS "ℹ️  No custom CPU operators found")
    endif()
endif()
