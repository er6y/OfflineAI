cmake_minimum_required(VERSION 3.22.1)
project(mnn-tts-jni-wrapper)

message(STATUS "========== MNN TTS JNI Wrapper ==========")

# Find libMNN.so from mnn-lib build output (like sherpa-mnn-jni)
get_filename_component(PROJECT_ROOT "${CMAKE_CURRENT_SOURCE_DIR}/../../../../.." ABSOLUTE)
set(MNN_LIB_BASE_DIR "${PROJECT_ROOT}/libs/mnn-lib/build/intermediates/cxx/Release")

file(GLOB MNN_BUILD_DIRS "${MNN_LIB_BASE_DIR}/*/obj/${ANDROID_ABI}")
set(MNN_LIB_FOUND FALSE)
foreach(BUILD_DIR ${MNN_BUILD_DIRS})
    if(EXISTS "${BUILD_DIR}/libMNN.so")
        message(STATUS "✅ Found libMNN.so: ${BUILD_DIR}/libMNN.so")
        set(MNN_SO_DIR ${BUILD_DIR})
        set(MNN_LIB_FOUND TRUE)
        break()
    endif()
endforeach()

if(NOT MNN_LIB_FOUND)
    message(FATAL_ERROR "❌ libMNN.so not found. Please build libs:mnn-lib module first!")
endif()

# Setup paths
get_filename_component(MNN_ROOT "${PROJECT_ROOT}/libs/mnn" ABSOLUTE)
get_filename_component(MNN_TTS_ROOT "${PROJECT_ROOT}/libs/mnn/apps/frameworks/mnn_tts" ABSOLUTE)


# Import libMNN.so
add_library(MNN SHARED IMPORTED)
set_target_properties(MNN PROPERTIES
    IMPORTED_LOCATION "${MNN_SO_DIR}/libMNN.so"
    INTERFACE_INCLUDE_DIRECTORIES "${MNN_ROOT}/include;${MNN_ROOT}/tools/audio/include;${MNN_ROOT}/transformers/llm/engine/include"
)

message(STATUS "✅ MNN target: ${MNN_SO_DIR}/libMNN.so")

# Include directories from upstream mnn_tts
include_directories(
    ${CMAKE_CURRENT_SOURCE_DIR}  # For regex_compat.hpp
    ${MNN_TTS_ROOT}/include
    ${MNN_TTS_ROOT}/include/bertvits2
    ${MNN_TTS_ROOT}/../3rd_party/include
    ${MNN_ROOT}/include
    ${MNN_ROOT}/tools/audio/include
    ${MNN_ROOT}/transformers/llm/engine/include
)

# Add RE2 library for Android (replaces std::regex to avoid locale crash)
if(ANDROID)
    message(STATUS "")
    message(STATUS "========================================")
    message(STATUS "🔧 Android Build: Applying RE2 Patch")
    message(STATUS "========================================")
    
    # STEP 1: Auto-patch upstream source files FIRST (before adding them to build)
    message(STATUS "STEP 1: Patching upstream TTS source files...")
    execute_process(
        COMMAND ${CMAKE_COMMAND} 
            -DMNN_TTS_ROOT=${MNN_TTS_ROOT}
            -DPATCH_ACTION=APPLY
            -P ${CMAKE_CURRENT_SOURCE_DIR}/regex_patch.cmake
        RESULT_VARIABLE PATCH_RESULT
    )
    if(NOT PATCH_RESULT EQUAL 0)
        message(FATAL_ERROR "Failed to apply RE2 patches")
    endif()
    message(STATUS "")
    
    # STEP 2: Add RE2 library
    message(STATUS "STEP 2: Adding RE2 library...")
    include(FetchContent)
    FetchContent_Declare(
        re2
        GIT_REPOSITORY https://github.com/google/re2.git
        GIT_TAG        2022-04-01  # Last version without mandatory Abseil dependency
    )
    set(RE2_BUILD_TESTING OFF CACHE BOOL "" FORCE)
    FetchContent_MakeAvailable(re2)
    
    # CRITICAL: Disable RE2 logging to avoid std::ostream (std::locale dependency)
    target_compile_definitions(re2 PRIVATE NDEBUG)
    message(STATUS "✅ RE2 library ready (logging disabled)")
    message(STATUS "========================================")
    message(STATUS "")
endif()

# Source files from upstream (copied from upstream CMakeLists.txt Line 48-82)
set(SHARED_SOURCE_FILES
    ${MNN_TTS_ROOT}/src/mnn_tts_config.cpp
    ${MNN_TTS_ROOT}/src/mnn_tts_sdk.cpp
    ${MNN_TTS_ROOT}/src/mnn_tts_logger.cpp
)

set(BERTVITS2_SOURCE_FILES
    ${MNN_TTS_ROOT}/src/bertvits2/an_to_cn.cpp
    ${MNN_TTS_ROOT}/src/bertvits2/utils.cpp
    ${MNN_TTS_ROOT}/src/bertvits2/mnn_bertvits2_tts_impl.cpp
    ${MNN_TTS_ROOT}/src/bertvits2/text_preprocessor.cpp
    ${MNN_TTS_ROOT}/src/bertvits2/pinyin.cpp
    ${MNN_TTS_ROOT}/src/bertvits2/chinese_g2p.cpp
    ${MNN_TTS_ROOT}/src/bertvits2/chinese_bert.cpp
    ${MNN_TTS_ROOT}/src/bertvits2/english_bert.cpp
    ${MNN_TTS_ROOT}/src/bertvits2/english_g2p.cpp
    ${MNN_TTS_ROOT}/src/bertvits2/tone_adjuster.cpp
    ${MNN_TTS_ROOT}/src/bertvits2/tts_generator.cpp
    ${MNN_TTS_ROOT}/src/bertvits2/word_spliter.cpp
)

set(ANDROID_SOURCE_FILES
    ${MNN_TTS_ROOT}/src/android/tts_service.cpp
    ${MNN_TTS_ROOT}/src/android/tts_service_jni.cpp
)

# Build libmnn_tts.so
add_library(mnn_tts SHARED
    ${SHARED_SOURCE_FILES}
    ${BERTVITS2_SOURCE_FILES}
    ${ANDROID_SOURCE_FILES}
)

# Add 16KB page size support
target_link_options(mnn_tts PRIVATE "-Wl,-z,max-page-size=16384")

# Link to libMNN.so and RE2 (Android only)
if(ANDROID)
    target_link_libraries(mnn_tts MNN log c++_shared re2)
    message(STATUS "✅ Linked RE2 for Android regex support (no locale dependency)")
else()
    target_link_libraries(mnn_tts MNN log c++_shared)
    message(STATUS "✅ Using std::regex on non-Android platforms")
endif()

message(STATUS "✅ MNN TTS JNI wrapper configured successfully")
