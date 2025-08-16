# Vulkan CMake Configuration Handling in JNI Layer

## 问题描述

`libs/llama.cpp-master/ggml/src/ggml-vulkan/CMakeLists.txt` 文件中包含了Vulkan后端的CMake配置，但为了避免修改submodule工程，我们在JNI层的CMakeLists.txt中处理了Vulkan相关的配置。

## 解决方案

### 1. CMake版本兼容性处理

在 `libs/llamacpp-jni/src/main/cpp/CMakeLists.txt` 中添加了以下配置：

```cmake
# 处理Vulkan相关的CMake版本兼容性问题
if(GGML_USE_VULKAN)
    # Vulkan backend需要CMake 3.19+，当前版本已满足
    if(CMAKE_VERSION VERSION_LESS "3.19")
        message(FATAL_ERROR "Vulkan backend requires CMake 3.19 or higher, current version: ${CMAKE_VERSION}")
    endif()
    
    # 设置Vulkan相关的CMake策略
    if(POLICY CMP0114)
        cmake_policy(SET CMP0114 NEW)
    endif()
    
    # 查找Vulkan包
    find_package(Vulkan COMPONENTS glslc)
    if(NOT Vulkan_FOUND)
        message(WARNING "Vulkan not found, disabling Vulkan backend")
        set(GGML_USE_VULKAN OFF CACHE BOOL "Enable Vulkan backend" FORCE)
    endif()
endif()
```

### 2. 条件编译源文件

```cmake
# 如果启用Vulkan，添加Vulkan相关源文件
if(GGML_USE_VULKAN AND Vulkan_FOUND)
    list(APPEND GGML_SOURCES
        "${LLAMA_CPP_DIR}/ggml/src/ggml-vulkan/ggml-vulkan.cpp"
    )
    
    # 添加Vulkan相关的编译定义
    add_definitions(-DGGML_USE_VULKAN=1)
    
    # 包含Vulkan头文件目录
    include_directories("${LLAMA_CPP_DIR}/ggml/src/ggml-vulkan")
    
    message(STATUS "Vulkan backend enabled for GGML")
else()
    add_definitions(-DGGML_USE_VULKAN=0)
endif()
```

### 3. 条件链接Vulkan库

```cmake
# 如果启用Vulkan，链接Vulkan库
if(GGML_USE_VULKAN AND Vulkan_FOUND)
    target_link_libraries(llamacpp_jni Vulkan::Vulkan)
    
    # 如果存在Vulkan::Headers目标，也链接它
    if(TARGET Vulkan::Headers)
        target_link_libraries(llamacpp_jni Vulkan::Headers)
        message(STATUS "Linked Vulkan::Headers target")
    elseif(DEFINED Vulkan_INCLUDE_DIRS)
        target_include_directories(llamacpp_jni PRIVATE ${Vulkan_INCLUDE_DIRS})
        message(STATUS "Added Vulkan_INCLUDE_DIRS: ${Vulkan_INCLUDE_DIRS}")
    endif()
    
    message(STATUS "Vulkan libraries linked to llamacpp_jni")
endif()
```

## 优势

1. **避免修改submodule**: 不需要修改 `libs/llama.cpp-master` 中的任何文件
2. **向后兼容**: 当前默认禁用Vulkan，不影响现有构建
3. **灵活配置**: 可以通过设置 `GGML_USE_VULKAN=ON` 来启用Vulkan支持
4. **自动降级**: 如果系统没有Vulkan SDK，会自动禁用Vulkan后端

## 启用Vulkan支持

如果需要启用Vulkan支持，可以在CMakeLists.txt中修改：

```cmake
set(GGML_USE_VULKAN ON CACHE BOOL "Enable Vulkan backend" FORCE)
```

或者通过CMake命令行参数：

```bash
-DGGML_USE_VULKAN=ON
```

## 注意事项

1. 启用Vulkan需要系统安装Vulkan SDK
2. Android平台的Vulkan支持需要API级别24+
3. 当前配置中暂时禁用了Vulkan，确保基本编译通过
4. 如果需要完整的Vulkan shader编译支持，可能还需要额外的配置