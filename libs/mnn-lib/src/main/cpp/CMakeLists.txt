cmake_minimum_required(VERSION 3.22.1)
project(mnn_lib)

# ========== MNN Source Path ==========
set(MNN_ROOT ${CMAKE_CURRENT_SOURCE_DIR}/../../../../mnn)

# ========== Critical Settings ==========
# CRITICAL: MNN_SEP_BUILD=OFF merges all engines into libMNN.so
set(MNN_SEP_BUILD OFF CACHE BOOL "Merge all engines into libMNN.so" FORCE)

# Disable NNAPI for x86_64 (FP16 symbol issues)
if(ANDROID_ABI STREQUAL "x86_64")
    set(MNN_NNAPI OFF CACHE BOOL "Disable NNAPI for x86_64" FORCE)
endif()

# ========== Build MNN ==========
# All build options are set via build.gradle arguments
add_subdirectory(${MNN_ROOT} ${CMAKE_CURRENT_BINARY_DIR}/mnn_build)

# ========== Custom CPU Operators (Optional) ==========
# Inject custom operators into MNNCPU if they exist
if(EXISTS ${CMAKE_CURRENT_SOURCE_DIR}/CPUGroupNorm.cpp)
    target_sources(MNNCPU PRIVATE ${CMAKE_CURRENT_SOURCE_DIR}/CPUGroupNorm.cpp)
    target_include_directories(MNNCPU PRIVATE ${CMAKE_CURRENT_SOURCE_DIR})
    message(STATUS "✅ Custom operator: CPUGroupNorm.cpp")
endif()
