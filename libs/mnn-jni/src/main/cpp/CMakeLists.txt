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
option(MNN_BUILD_OPENCV "Build OpenCV (required for diffusion)" ON)
option(MNN_IMGCODECS "Enable image codecs (required for diffusion)" ON)
option(MNN_OPENCL "Enable OpenCL backend" ON)
option(MNN_VULKAN "Enable Vulkan backend" ON)
option(MNN_ARM82 "Enable ARM82 (fp16/dot) support" ON)
option(MNN_SUPPORT_TRANSFORMER_FUSE "Enable transformer fusion" ON)
option(MNN_LOW_MEMORY "Enable low memory mode" ON)  # Required for weight quant (int4/int8)
option(MNN_CPU_WEIGHT_DEQUANT_GEMM "Enable CPU weight dequant GEMM" ON)
option(MNN_USE_LOGCAT "Use Android logcat" ON)
option(MNN_OPEN_TIME_TRACE "Enable time trace logging" ON)
option(LLMNN_SUPPORT_VISION "Enable vision support" ON)
option(MNN_KLEIDIAI "Enable KleidiAI" ON)

# CRITICAL: Always use Release-like settings for MNN to avoid Backend registration issues
# MNN's DEBUG mode enables assertions that can cause crashes in Debug builds
# We keep logging enabled but disable debug assertions and optimizations
set(MNN_DEBUG OFF CACHE BOOL "Disable MNN debug mode (use Release settings)" FORCE)

# Conditionally enable NNAPI only for ARM architectures (x86_64 has FP16 symbol issues)
if(ANDROID_ABI STREQUAL "arm64-v8a" OR ANDROID_ABI STREQUAL "armeabi-v7a")
    set(MNN_NNAPI ON CACHE BOOL "Enable NNAPI backend" FORCE)
    message(STATUS "NNAPI enabled for ${ANDROID_ABI}")
else()
    set(MNN_NNAPI OFF CACHE BOOL "Disable NNAPI backend" FORCE)
    message(STATUS "NNAPI disabled for ${ANDROID_ABI} (FP16 symbol issues)")
endif()

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
    opencl_diagnostic.cpp
)

# ========== Build JNI Library ==========
add_library(mnn_jni SHARED ${JNI_SOURCES})

# Link libraries
target_link_libraries(mnn_jni
    ${MNN_LIBS}
    android
    log
)

# ========== Compile Definitions ==========
target_compile_definitions(mnn_jni PRIVATE
    MNN_BUILD_LLM=1
    MNN_BUILD_DIFFUSION=1
)

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
