# CRITICAL: Minimal wrapper to help sherpa-mnn find libMNN.so
# This wrapper is necessary because:
# 1. sherpa-mnn's CMakeLists.txt expects ${MNN_LIB_DIR}/lib/libMNN.so
# 2. Our mnn-lib build output is in build/intermediates/cxx/Release/*/obj/${ANDROID_ABI}/
# 3. We need to dynamically find libMNN.so across different build hash directories
# 4. We configure sherpa-mnn to use shared libMNN.so instead of static linking

cmake_minimum_required(VERSION 3.22.1)
project(sherpa-mnn-jni-wrapper)

message(STATUS "========== Sherpa-MNN JNI Wrapper ==========")

# Find libMNN.so from mnn-lib build output
get_filename_component(PROJECT_ROOT "${CMAKE_CURRENT_SOURCE_DIR}/../../../../.." ABSOLUTE)
set(MNN_LIB_BASE_DIR "${PROJECT_ROOT}/libs/mnn-lib/build/intermediates/cxx/Release")

file(GLOB MNN_BUILD_DIRS "${MNN_LIB_BASE_DIR}/*/obj/${ANDROID_ABI}")
set(MNN_LIB_FOUND FALSE)
foreach(BUILD_DIR ${MNN_BUILD_DIRS})
    if(EXISTS "${BUILD_DIR}/libMNN.so")
        message(STATUS "✅ Found libMNN.so: ${BUILD_DIR}/libMNN.so")
        set(MNN_SO_DIR ${BUILD_DIR})  # Save the actual libMNN.so location
        set(MNN_LIB_FOUND TRUE)
        break()
    endif()
endforeach()

if(NOT MNN_LIB_FOUND)
    message(FATAL_ERROR "❌ libMNN.so not found. Please build libs:mnn-lib module first!")
endif()

# Configure sherpa-mnn to link against our libMNN.so
# CRITICAL: Set MNN_LIB_DIR for sherpa-mnn's CMakeLists.txt
# Sherpa-MNN expects:
# - ${MNN_LIB_DIR}/include for headers
# - ${MNN_LIB_DIR}/lib for libMNN.so
# We point MNN_LIB_DIR to MNN source (has include/), and override link_directories

get_filename_component(MNN_ROOT "${PROJECT_ROOT}/libs/mnn" ABSOLUTE)

# Create a temporary structure that sherpa-mnn expects
# We'll override link_directories AFTER sherpa-mnn sets it
set(MNN_LIB_DIR ${MNN_ROOT} CACHE STRING "MNN source directory" FORCE)

message(STATUS "✅ MNN_LIB_DIR set to: ${MNN_LIB_DIR}")
message(STATUS "✅ libMNN.so location: ${MNN_SO_DIR}")

# CRITICAL: Add our libMNN.so location to link directories BEFORE sherpa-mnn
# This ensures sherpa-mnn finds our libMNN.so instead of looking for ${MNN_LIB_DIR}/lib/libMNN.so
link_directories(${MNN_SO_DIR})

# Include sherpa-mnn's official CMakeLists.txt
get_filename_component(SHERPA_MNN_ROOT "${PROJECT_ROOT}/libs/mnn/apps/frameworks/sherpa-mnn" ABSOLUTE)

# CRITICAL: sherpa-mnn source uses #include "sherpa-mnn/csrc/xxx.h"
# Must add sherpa-mnn root to include path
include_directories(${SHERPA_MNN_ROOT})

add_subdirectory(${SHERPA_MNN_ROOT} ${CMAKE_CURRENT_BINARY_DIR}/sherpa_mnn_build)

message(STATUS "✅ Sherpa-MNN JNI configured with libMNN.so from: ${MNN_SO_DIR}")
