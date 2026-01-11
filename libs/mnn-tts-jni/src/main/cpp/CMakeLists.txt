# Aligned with upstream: libs/mnn/apps/frameworks/mnn_tts/CMakeLists.txt
# Only difference: libMNN.so path (use mnn-lib instead of project/android/build_64)

cmake_minimum_required(VERSION 3.13)

project(mnn_tts VERSION 1.0.0)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

option(BUILD_BERTVITS2 "Build BertVit2 TTS " ON)
option(BUILD_PIPER "Build PIPER TTS " OFF)
option(BUILD_SUPERTONIC "Build Supertonic TTS " ON)
option(BUILD_ANDROID "Build for Android" OFF)

if(ANDROID)
    set(BUILD_ANDROID ON CACHE BOOL "Build for Android" FORCE)
endif()

# ========== ONLY MODIFICATION: Find libMNN.so from mnn-lib ==========
add_library(MNN SHARED IMPORTED)
# Path: libs/mnn-tts-jni/src/main/cpp -> libs/mnn = ../../../../mnn
get_filename_component(MNN_SOURCE_ROOT "${CMAKE_CURRENT_LIST_DIR}/../../../../mnn" ABSOLUTE)

if(ANDROID)
    # Find libMNN.so from mnn-lib build output
    get_filename_component(PROJECT_ROOT "${CMAKE_CURRENT_SOURCE_DIR}/../../../../.." ABSOLUTE)
    set(MNN_LIB_BASE_DIR "${PROJECT_ROOT}/libs/mnn-lib/build/intermediates/cxx/Release")
    
    file(GLOB MNN_BUILD_DIRS "${MNN_LIB_BASE_DIR}/*/obj/${ANDROID_ABI}")
    set(MNN_LIB_FOUND FALSE)
    foreach(BUILD_DIR ${MNN_BUILD_DIRS})
        if(EXISTS "${BUILD_DIR}/libMNN.so")
            set(LIB_PATH ${BUILD_DIR})
            set(MNN_LIB_FOUND TRUE)
            message(STATUS "Found libMNN.so: ${LIB_PATH}/libMNN.so")
            break()
        endif()
    endforeach()
    
    if(NOT MNN_LIB_FOUND)
        message(FATAL_ERROR "libMNN.so not found. Please build libs:mnn-lib first!")
    endif()
else()
    # Non-Android: use upstream path
    set(MNN_INSTALL_ROOT "${MNN_SOURCE_ROOT}/project/android/build_64")
    set(LIB_PATH "${MNN_INSTALL_ROOT}/lib")
endif()

message(STATUS "MNN_SOURCE_ROOT: ${MNN_SOURCE_ROOT}")
message(STATUS "LIB_PATH: ${LIB_PATH}")

set_target_properties(MNN PROPERTIES INTERFACE_INCLUDE_DIRECTORIES
        "${MNN_SOURCE_ROOT}/include;${MNN_SOURCE_ROOT}/tools/audio/include/;${MNN_SOURCE_ROOT}/transformers/llm/engine/include"
)
set_target_properties(MNN PROPERTIES IMPORTED_LOCATION "${LIB_PATH}/libMNN.so")
# ========== END MODIFICATION ==========

# Use MNN_SOURCE_ROOT for all paths
set(MNN_TTS_ROOT "${MNN_SOURCE_ROOT}/apps/frameworks/mnn_tts")

include_directories(
	${MNN_TTS_ROOT}/include
	${MNN_SOURCE_ROOT}/apps/frameworks/3rd_party/include
)

if(BUILD_BERTVITS2)
  include_directories(
  	${MNN_TTS_ROOT}/include/bertvits2
  )
endif()

if(BUILD_PIPER)
  include_directories(
  	${MNN_TTS_ROOT}/include/piper
    ${MNN_TTS_ROOT}/third_party/piper/espeak-ng/src/include/espeak-ng/
  )
endif()

if(BUILD_SUPERTONIC)
  include_directories(
    ${MNN_TTS_ROOT}/include/supertonic
  )
endif()

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

if(BUILD_PIPER)
  set(PIPER_SOURCE_FILES
    ${MNN_TTS_ROOT}/src/piper/audio_generator.cpp
    ${MNN_TTS_ROOT}/src/piper/mnn_piper_tts_impl.cpp
    ${MNN_TTS_ROOT}/src/piper/phoneme_ids.cpp
  )
endif()

if(BUILD_SUPERTONIC)
  set(SUPERTONIC_SOURCE_FILES
    ${MNN_TTS_ROOT}/src/supertonic/mnn_supertonic_tts_impl.cpp
  )
endif()

if(BUILD_ANDROID)
  set(ANDROID_SOURCE_FILES
    ${MNN_TTS_ROOT}/src/android/tts_service.cpp
    ${MNN_TTS_ROOT}/src/android/tts_service_jni.cpp
  )
endif()

if(BUILD_PIPER)
  add_subdirectory(${MNN_TTS_ROOT}/third_party/piper/espeak-ng)
endif()

add_library(${PROJECT_NAME} SHARED ${PIPER_SOURCE_FILES} ${BERTVITS2_SOURCE_FILES} ${SUPERTONIC_SOURCE_FILES} ${SHARED_SOURCE_FILES} ${ANDROID_SOURCE_FILES})

# Add 16KB page size support for Android
if(BUILD_ANDROID)
    target_link_options(${PROJECT_NAME} PRIVATE "-Wl,-z,max-page-size=16384")
endif()

target_link_libraries(${PROJECT_NAME} log MNN)
