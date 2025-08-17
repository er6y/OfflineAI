#include "vulkan_runtime_detector.h"
#include <android/log.h>
#include <dlfcn.h>
#include <string>
#include <vector>
#include <jni.h>

// 动态加载Vulkan，不直接链接
#define VK_NO_PROTOTYPES
#include <vulkan/vulkan.h>

// 外部声明JNI全局变量和函数（在llama_inference.cpp中定义）
extern JavaVM* g_jvm;
extern jclass g_log_manager_class;
extern jmethodID g_log_manager_print_method;
extern void call_log_manager_print(const char* message);

#define LOG_TAG "VulkanRuntimeDetector"

// 修改日志宏，同时输出到logcat和文件
#define LOGI(...) do { \
    __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__); \
    char buffer[1024]; \
    snprintf(buffer, sizeof(buffer), "[vulkan-info] " __VA_ARGS__); \
    std::string formatted_message = std::string(buffer) + "\n"; \
    call_log_manager_print(formatted_message.c_str()); \
} while(0)

#define LOGW(...) do { \
    __android_log_print(ANDROID_LOG_WARN, LOG_TAG, __VA_ARGS__); \
    char buffer[1024]; \
    snprintf(buffer, sizeof(buffer), "[vulkan-warn] " __VA_ARGS__); \
    std::string formatted_message = std::string(buffer) + "\n"; \
    call_log_manager_print(formatted_message.c_str()); \
} while(0)

#define LOGE(...) do { \
    __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__); \
    char buffer[1024]; \
    snprintf(buffer, sizeof(buffer), "[vulkan-error] " __VA_ARGS__); \
    std::string formatted_message = std::string(buffer) + "\n"; \
    call_log_manager_print(formatted_message.c_str()); \
} while(0)

namespace vulkan_runtime {

static void* vulkan_lib_handle = nullptr;
static bool vulkan_detection_attempted = false;
static VulkanRuntimeInfo cached_info = {};

// Vulkan函数指针
static PFN_vkGetInstanceProcAddr vkGetInstanceProcAddr_func = nullptr;
static PFN_vkCreateInstance vkCreateInstance_func = nullptr;
static PFN_vkDestroyInstance vkDestroyInstance_func = nullptr;
static PFN_vkEnumeratePhysicalDevices vkEnumeratePhysicalDevices_func = nullptr;
static PFN_vkGetPhysicalDeviceProperties vkGetPhysicalDeviceProperties_func = nullptr;
static PFN_vkGetPhysicalDeviceFeatures2 vkGetPhysicalDeviceFeatures2_func = nullptr;
static PFN_vkGetPhysicalDeviceFeatures2KHR vkGetPhysicalDeviceFeatures2KHR_func = nullptr;

bool load_vulkan_library() {
    if (vulkan_lib_handle != nullptr) {
        return true; // 已经加载
    }
    
    // 尝试加载Vulkan库
    const char* vulkan_lib_names[] = {
        "libvulkan.so.1",
        "libvulkan.so",
        "vulkan"
    };
    
    for (const char* lib_name : vulkan_lib_names) {
        vulkan_lib_handle = dlopen(lib_name, RTLD_NOW | RTLD_LOCAL);
        if (vulkan_lib_handle != nullptr) {
            LOGI("Successfully loaded Vulkan library: %s", lib_name);
            break;
        }
        LOGW("Failed to load %s: %s", lib_name, dlerror());
    }
    
    if (vulkan_lib_handle == nullptr) {
        LOGE("Failed to load any Vulkan library");
        return false;
    }
    
    // 加载基础函数
    vkGetInstanceProcAddr_func = (PFN_vkGetInstanceProcAddr)dlsym(vulkan_lib_handle, "vkGetInstanceProcAddr");
    if (vkGetInstanceProcAddr_func == nullptr) {
        LOGE("Failed to load vkGetInstanceProcAddr");
        unload_vulkan_library();
        return false;
    }
    
    // 某些全局函数需要直接从库中获取，而不是通过vkGetInstanceProcAddr
    vkCreateInstance_func = (PFN_vkCreateInstance)dlsym(vulkan_lib_handle, "vkCreateInstance");
    if (!vkCreateInstance_func) {
        // 如果直接获取失败，尝试通过vkGetInstanceProcAddr获取
        vkCreateInstance_func = (PFN_vkCreateInstance)vkGetInstanceProcAddr_func(VK_NULL_HANDLE, "vkCreateInstance");
    }
    
    // vkEnumerateInstanceExtensionProperties也是全局函数
    PFN_vkEnumerateInstanceExtensionProperties vkEnumerateInstanceExtensionProperties_func = 
        (PFN_vkEnumerateInstanceExtensionProperties)dlsym(vulkan_lib_handle, "vkEnumerateInstanceExtensionProperties");
    if (!vkEnumerateInstanceExtensionProperties_func) {
        vkEnumerateInstanceExtensionProperties_func = 
            (PFN_vkEnumerateInstanceExtensionProperties)vkGetInstanceProcAddr_func(VK_NULL_HANDLE, "vkEnumerateInstanceExtensionProperties");
    }
    
    if (!vkCreateInstance_func) {
        LOGE("Failed to load vkCreateInstance");
        unload_vulkan_library();
        return false;
    }
    
    LOGI("Vulkan library loaded successfully");
    return true;
}

void unload_vulkan_library() {
    if (vulkan_lib_handle != nullptr) {
        dlclose(vulkan_lib_handle);
        vulkan_lib_handle = nullptr;
        
        // 清空函数指针
        vkGetInstanceProcAddr_func = nullptr;
        vkCreateInstance_func = nullptr;
        vkDestroyInstance_func = nullptr;
        vkEnumeratePhysicalDevices_func = nullptr;
        vkGetPhysicalDeviceProperties_func = nullptr;
        vkGetPhysicalDeviceFeatures2_func = nullptr;
        vkGetPhysicalDeviceFeatures2KHR_func = nullptr;
        
        LOGI("Vulkan library unloaded");
    }
}

bool test_vulkan_instance_creation() {
    if (!vkCreateInstance_func) {
        LOGE("vkCreateInstance function not available");
        return false;
    }
    
    VkApplicationInfo app_info = {};
    app_info.sType = VK_STRUCTURE_TYPE_APPLICATION_INFO;
    app_info.pApplicationName = "VulkanTest";
    app_info.applicationVersion = VK_MAKE_VERSION(1, 0, 0);
    app_info.pEngineName = "TestEngine";
    app_info.engineVersion = VK_MAKE_VERSION(1, 0, 0);
    app_info.apiVersion = VK_API_VERSION_1_0;
    
    VkInstanceCreateInfo create_info = {};
    create_info.sType = VK_STRUCTURE_TYPE_INSTANCE_CREATE_INFO;
    create_info.pApplicationInfo = &app_info;
    
    VkInstance instance = VK_NULL_HANDLE;
    VkResult result = vkCreateInstance_func(&create_info, nullptr, &instance);
    
    if (result != VK_SUCCESS) {
        LOGE("Failed to create Vulkan instance: %d", result);
        return false;
    }
    
    // 现在获取实例相关的函数指针
    vkDestroyInstance_func = (PFN_vkDestroyInstance)vkGetInstanceProcAddr_func(instance, "vkDestroyInstance");
    vkEnumeratePhysicalDevices_func = (PFN_vkEnumeratePhysicalDevices)vkGetInstanceProcAddr_func(instance, "vkEnumeratePhysicalDevices");
    vkGetPhysicalDeviceProperties_func = (PFN_vkGetPhysicalDeviceProperties)vkGetInstanceProcAddr_func(instance, "vkGetPhysicalDeviceProperties");
    
    if (!vkDestroyInstance_func || !vkEnumeratePhysicalDevices_func || !vkGetPhysicalDeviceProperties_func) {
        LOGE("Failed to load instance functions");
        if (vkDestroyInstance_func) {
            vkDestroyInstance_func(instance, nullptr);
        }
        return false;
    }
    
    // 测试物理设备枚举
    uint32_t device_count = 0;
    result = vkEnumeratePhysicalDevices_func(instance, &device_count, nullptr);
    
    // 清理实例
    vkDestroyInstance_func(instance, nullptr);
    
    if (result != VK_SUCCESS) {
        LOGE("Failed to enumerate physical devices: %d", result);
        return false;
    }
    
    LOGI("Vulkan instance creation test successful, found %u physical devices", device_count);
    return true;
}

bool check_vulkan_1_1_apis(VkInstance instance) {
    if (!vkGetInstanceProcAddr_func || instance == VK_NULL_HANDLE) {
        return false;
    }
    
    // 尝试加载Vulkan 1.1 API
    vkGetPhysicalDeviceFeatures2_func = (PFN_vkGetPhysicalDeviceFeatures2)vkGetInstanceProcAddr_func(instance, "vkGetPhysicalDeviceFeatures2");
    vkGetPhysicalDeviceFeatures2KHR_func = (PFN_vkGetPhysicalDeviceFeatures2KHR)vkGetInstanceProcAddr_func(instance, "vkGetPhysicalDeviceFeatures2KHR");
    
    bool has_core_1_1 = (vkGetPhysicalDeviceFeatures2_func != nullptr);
    bool has_khr_extension = (vkGetPhysicalDeviceFeatures2KHR_func != nullptr);
    
    LOGI("Vulkan 1.1 API availability: core=%s, KHR=%s", 
         has_core_1_1 ? "yes" : "no", 
         has_khr_extension ? "yes" : "no");
    
    return has_core_1_1 || has_khr_extension;
}

VulkanRuntimeInfo detect_vulkan_runtime() {
    if (vulkan_detection_attempted) {
        return cached_info;
    }
    
    vulkan_detection_attempted = true;
    cached_info = {}; // 初始化为全false
    
    LOGI("Starting Vulkan runtime detection...");
    
    // 1. 尝试加载Vulkan库
    if (!load_vulkan_library()) {
        LOGE("Vulkan library not available");
        return cached_info;
    }
    
    cached_info.library_available = true;
    
    // 2. 测试Vulkan实例创建
    if (!test_vulkan_instance_creation()) {
        LOGE("Vulkan instance creation failed");
        return cached_info;
    }
    
    cached_info.instance_creation_works = true;
    
    // 3. 创建实例来检查API可用性
    VkApplicationInfo app_info = {};
    app_info.sType = VK_STRUCTURE_TYPE_APPLICATION_INFO;
    app_info.pApplicationName = "VulkanAPITest";
    app_info.applicationVersion = VK_MAKE_VERSION(1, 0, 0);
    app_info.pEngineName = "TestEngine";
    app_info.engineVersion = VK_MAKE_VERSION(1, 0, 0);
    app_info.apiVersion = VK_API_VERSION_1_0;
    
    VkInstanceCreateInfo create_info = {};
    create_info.sType = VK_STRUCTURE_TYPE_INSTANCE_CREATE_INFO;
    create_info.pApplicationInfo = &app_info;
    
    VkInstance instance = VK_NULL_HANDLE;
    VkResult result = vkCreateInstance_func(&create_info, nullptr, &instance);
    
    if (result == VK_SUCCESS && instance != VK_NULL_HANDLE) {
        // 4. 检查Vulkan 1.1 API可用性
        cached_info.vulkan_1_1_apis_available = check_vulkan_1_1_apis(instance);
        
        // 5. 枚举物理设备
        uint32_t device_count = 0;
        result = vkEnumeratePhysicalDevices_func(instance, &device_count, nullptr);
        if (result == VK_SUCCESS && device_count > 0) {
            cached_info.physical_devices_available = true;
            cached_info.device_count = device_count;
            LOGI("Found %u Vulkan physical device(s)", device_count);
        } else {
            LOGW("No Vulkan physical devices found or enumeration failed");
        }
        
        vkDestroyInstance_func(instance, nullptr);
    }
    
    // 6. 综合判断Vulkan是否可用于llama.cpp
    cached_info.suitable_for_llamacpp = 
        cached_info.library_available &&
        cached_info.instance_creation_works &&
        cached_info.physical_devices_available &&
        cached_info.vulkan_1_1_apis_available;
    
    LOGI("Vulkan runtime detection completed:");
    LOGI("  Library available: %s", cached_info.library_available ? "yes" : "no");
    LOGI("  Instance creation: %s", cached_info.instance_creation_works ? "yes" : "no");
    LOGI("  Physical devices: %s (%u found)", cached_info.physical_devices_available ? "yes" : "no", cached_info.device_count);
    LOGI("  Vulkan 1.1 APIs: %s", cached_info.vulkan_1_1_apis_available ? "yes" : "no");
    LOGI("  Suitable for llama.cpp: %s", cached_info.suitable_for_llamacpp ? "yes" : "no");
    
    return cached_info;
}

void cleanup_vulkan_runtime() {
    unload_vulkan_library();
    vulkan_detection_attempted = false;
    cached_info = {};
}

} // namespace vulkan_runtime