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

# CRITICAL: Force MNN to use logcat for Android logging (must be before add_subdirectory)
add_definitions(-DMNN_USE_LOGCAT)

# ========== KleidiAI path workaround ==========
# Upstream KleidiAI.cmake uses ${CMAKE_SOURCE_DIR}/source/backend/cpu/kleidiai,
# but in add_subdirectory mode CMAKE_SOURCE_DIR points to this dir (mnn-lib),
# not the real MNN root. Create a directory junction so the path resolves.
# This avoids modifying upstream code.
set(_kleidiai_link_dir "${CMAKE_CURRENT_SOURCE_DIR}/source/backend/cpu")
set(_kleidiai_link "${_kleidiai_link_dir}/kleidiai")
set(_kleidiai_real "${MNN_ROOT}/source/backend/cpu/kleidiai")
if(EXISTS "${_kleidiai_real}" AND NOT EXISTS "${_kleidiai_link}")
    file(MAKE_DIRECTORY "${_kleidiai_link_dir}")
    if(CMAKE_HOST_WIN32)
        # Windows: use junction (no admin needed); convert path to backslashes
        string(REPLACE "/" "\\" _link_native "${_kleidiai_link}")
        string(REPLACE "/" "\\" _real_native "${_kleidiai_real}")
        execute_process(
            COMMAND cmd /c mklink /J "${_link_native}" "${_real_native}"
            RESULT_VARIABLE _mklink_rc OUTPUT_QUIET ERROR_QUIET)
        if(NOT _mklink_rc EQUAL 0)
            message(WARNING "Failed to create kleidiai junction (rc=${_mklink_rc})")
        endif()
    else()
        execute_process(
            COMMAND ${CMAKE_COMMAND} -E create_symlink
                "${_kleidiai_real}" "${_kleidiai_link}")
    endif()
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
