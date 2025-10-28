cmake_minimum_required(VERSION 3.22.1)
project(mnn_jni)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

# ========== MNN Source Paths ==========
set(MNN_ROOT ${CMAKE_CURRENT_SOURCE_DIR}/../../../../mnn)
set(MNN_LLM_ROOT ${MNN_ROOT}/transformers/llm)
set(SHERPA_MNN_ROOT ${MNN_ROOT}/apps/frameworks/sherpa-mnn)

message(STATUS "MNN Root: ${MNN_ROOT}")
message(STATUS "MNN LLM Root: ${MNN_LLM_ROOT}")
message(STATUS "Sherpa-MNN Root: ${SHERPA_MNN_ROOT}")

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

# ========== Find Prebuilt libMNN.so ==========
# CRITICAL: We now use prebuilt libMNN.so to avoid duplicating MNN code in libmnn_jni.so
# libMNN.so is built by a separate MNN module (libs/mnn) and contains:
# - MNN core engine
# - LLM/Diffusion/Vision/TTS engines (MNN_SEP_BUILD=OFF merges them)
# - All backends (OpenCL, Vulkan, etc.)
#
# This approach:
# 1. Eliminates ~60MB duplication (libmnn_jni.so: 71MB → ~10MB)
# 2. Shares libMNN.so with libsherpa-mnn-jni.so
# 3. Maintains flexibility (we build libMNN.so ourselves with desired backends)

message(STATUS "Looking for prebuilt libMNN.so...")

# Find libMNN.so from mnn-lib module's build output
get_filename_component(PROJECT_ROOT "${CMAKE_CURRENT_SOURCE_DIR}/../../../../.." ABSOLUTE)
set(MNN_MODULE_BASE_DIR "${PROJECT_ROOT}/libs/mnn-lib/build/intermediates/cxx/Release")

file(GLOB MNN_BUILD_DIRS "${MNN_MODULE_BASE_DIR}/*/obj/${ANDROID_ABI}")
set(MNN_LIB_FOUND FALSE)
foreach(BUILD_DIR ${MNN_BUILD_DIRS})
    if(EXISTS "${BUILD_DIR}/libMNN.so")
        message(STATUS "✅ Found libMNN.so: ${BUILD_DIR}/libMNN.so")
        set(MNN_LIB_DIR ${BUILD_DIR})
        set(MNN_LIB_FOUND TRUE)
        break()
    endif()
endforeach()

if(NOT MNN_LIB_FOUND)
    message(FATAL_ERROR "❌ libMNN.so not found. Please build libs:mnn-lib module first!")
endif()

# Link against prebuilt libMNN.so
link_directories(${MNN_LIB_DIR})
set(MNN_LIBS MNN)

# Also link backend libraries if they exist separately
if(EXISTS "${MNN_LIB_DIR}/libMNN_CL.so")
    list(APPEND MNN_LIBS MNN_CL)
    message(STATUS "✅ Found libMNN_CL.so")
endif()

if(EXISTS "${MNN_LIB_DIR}/libMNN_Vulkan.so")
    list(APPEND MNN_LIBS MNN_Vulkan)
    message(STATUS "✅ Found libMNN_Vulkan.so")
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

# Add sherpa-mnn includes ONLY to mnn_jni target (not globally)
# CRITICAL: Only add c-api directory, NOT csrc (csrc has features.h that conflicts with NDK)
if(MNN_BUILD_AUDIO)
    target_include_directories(mnn_jni PRIVATE
        ${SHERPA_MNN_ROOT}/sherpa-mnn/c-api
    )
endif()

# Link libraries
# CRITICAL: Only link libMNN.so (which contains all engines due to MNN_SEP_BUILD=OFF)
# Do NOT link llm/diffusion/etc. - they are already in libMNN.so
target_link_libraries(mnn_jni
    ${MNN_LIBS}  # libMNN.so (+ libMNN_CL.so, libMNN_Vulkan.so if MNN_SEP_BUILD=ON)
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
# NOTE: Custom operators are now built into libMNN.so by the libs/mnn module
# If you need custom operators:
# 1. Add them to libs/mnn/source/backend/cpu/ (or use MNN plugin system)
# 2. Rebuild libs/mnn module
# 3. libmnn_jni.so will automatically use them via libMNN.so
#
# Alternative: Use MNN's plugin system (MNN_WITH_PLUGIN=ON) for dynamic loading
message(STATUS "ℹ️  Custom operators should be built into libMNN.so")
