#include <jni.h>
#include <android/log.h>
#include <dlfcn.h>
#include <string>

#define LOG_TAG "OpenCLDetector"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

// OpenCL basic types
typedef int32_t cl_int;
typedef uint32_t cl_uint;
typedef uint64_t cl_ulong;
typedef struct _cl_platform_id* cl_platform_id;
typedef struct _cl_device_id* cl_device_id;

#define CL_SUCCESS 0
#define CL_PLATFORM_NAME 0x0902
#define CL_PLATFORM_VERSION 0x0901
#define CL_DEVICE_NAME 0x102B
#define CL_DEVICE_TYPE_GPU 0x0004

// OpenCL function pointers
typedef cl_int (*clGetPlatformIDs_t)(cl_uint, cl_platform_id*, cl_uint*);
typedef cl_int (*clGetPlatformInfo_t)(cl_platform_id, cl_uint, size_t, void*, size_t*);
typedef cl_int (*clGetDeviceIDs_t)(cl_platform_id, cl_ulong, cl_uint, cl_device_id*, cl_uint*);
typedef cl_int (*clGetDeviceInfo_t)(cl_device_id, cl_uint, size_t, void*, size_t*);

extern "C" {

/**
 * Detect OpenCL availability on Android device
 * Returns: JSON-like string with detection result
 */
JNIEXPORT jstring JNICALL
Java_com_starlocalrag_llamacpp_OpenCLDetector_detectOpenCL(JNIEnv* env, jclass clazz) {
    LOGI("=== OpenCL Detection Start ===");
    
    std::string result = "";
    
    // Try to load OpenCL library
    const char* opencl_libs[] = {
        "libOpenCL.so",                          // Standard ICD loader (most likely)
        "/system/lib64/libOpenCL.so",            // System ICD loader
        "/vendor/lib64/libOpenCL.so",            // Vendor ICD loader
        "/system/vendor/lib64/libOpenCL.so",     // System vendor ICD
        "/vendor/lib64/egl/libmali.so",          // Mali driver (direct)
        "/system/vendor/lib64/egl/libmali.so",   // System Mali driver
        "libGLES_mali.so",                       // Mali GPU library
        "libmali.so",                            // Mali direct
        "/vendor/lib64/libmali.so",              // Vendor Mali
        "libPVROCL.so",                          // PowerVR
        "libllvm-glnext.so"                      // Some Adreno devices
    };
    
    void* opencl_handle = nullptr;
    const char* loaded_lib = nullptr;
    
    for (const char* lib_name : opencl_libs) {
        opencl_handle = dlopen(lib_name, RTLD_NOW | RTLD_LOCAL);
        if (opencl_handle) {
            loaded_lib = lib_name;
            LOGI("✓ Found OpenCL library: %s", lib_name);
            break;
        }
        LOGI("✗ Library not found: %s (%s)", lib_name, dlerror());
    }
    
    if (!opencl_handle) {
        result = "OpenCL: Not Available\nReason: No OpenCL library found on system\n";
        result += "Tested libraries:\n";
        for (const char* lib_name : opencl_libs) {
            result += "  - " + std::string(lib_name) + "\n";
        }
        LOGE("OpenCL not available on this device");
        return env->NewStringUTF(result.c_str());
    }
    
    result += "OpenCL Library: " + std::string(loaded_lib) + "\n";
    
    // Try to load OpenCL functions
    clGetPlatformIDs_t clGetPlatformIDs_func = 
        (clGetPlatformIDs_t)dlsym(opencl_handle, "clGetPlatformIDs");
    clGetPlatformInfo_t clGetPlatformInfo_func = 
        (clGetPlatformInfo_t)dlsym(opencl_handle, "clGetPlatformInfo");
    clGetDeviceIDs_t clGetDeviceIDs_func = 
        (clGetDeviceIDs_t)dlsym(opencl_handle, "clGetDeviceIDs");
    clGetDeviceInfo_t clGetDeviceInfo_func = 
        (clGetDeviceInfo_t)dlsym(opencl_handle, "clGetDeviceInfo");
    
    if (!clGetPlatformIDs_func) {
        result += "OpenCL API: Not Accessible\n";
        result += "Reason: Functions not exported (vendor restriction)\n";
        result += "Conclusion: OpenCL library exists but blocked for third-party apps\n";
        LOGW("OpenCL functions not exported - vendor has restricted access");
        dlclose(opencl_handle);
        return env->NewStringUTF(result.c_str());
    }
    
    result += "OpenCL API: Accessible\n";
    
    // Get platform count
    cl_uint platform_count = 0;
    cl_int ret = clGetPlatformIDs_func(0, nullptr, &platform_count);
    
    if (ret != CL_SUCCESS || platform_count == 0) {
        result += "Platforms: 0 found\n";
        result += "Reason: No OpenCL platforms available\n";
        LOGW("No OpenCL platforms found (ret=%d, count=%u)", ret, platform_count);
        dlclose(opencl_handle);
        return env->NewStringUTF(result.c_str());
    }
    
    result += "Platforms: " + std::to_string(platform_count) + "\n\n";
    LOGI("Found %u OpenCL platform(s)", platform_count);
    
    // Get platform details
    cl_platform_id* platforms = new cl_platform_id[platform_count];
    clGetPlatformIDs_func(platform_count, platforms, nullptr);
    
    for (cl_uint i = 0; i < platform_count; i++) {
        result += "Platform " + std::to_string(i) + ":\n";
        
        // Platform name
        char platform_name[256] = {0};
        clGetPlatformInfo_func(platforms[i], CL_PLATFORM_NAME, sizeof(platform_name), platform_name, nullptr);
        result += "  Name: " + std::string(platform_name) + "\n";
        
        // Platform version
        char platform_version[256] = {0};
        clGetPlatformInfo_func(platforms[i], CL_PLATFORM_VERSION, sizeof(platform_version), platform_version, nullptr);
        result += "  Version: " + std::string(platform_version) + "\n";
        
        // Get GPU devices
        cl_uint device_count = 0;
        clGetDeviceIDs_func(platforms[i], CL_DEVICE_TYPE_GPU, 0, nullptr, &device_count);
        result += "  GPU Devices: " + std::to_string(device_count) + "\n";
        
        if (device_count > 0) {
            cl_device_id* devices = new cl_device_id[device_count];
            clGetDeviceIDs_func(platforms[i], CL_DEVICE_TYPE_GPU, device_count, devices, nullptr);
            
            for (cl_uint j = 0; j < device_count; j++) {
                char device_name[256] = {0};
                clGetDeviceInfo_func(devices[j], CL_DEVICE_NAME, sizeof(device_name), device_name, nullptr);
                result += "    Device " + std::to_string(j) + ": " + std::string(device_name) + "\n";
            }
            
            delete[] devices;
        }
        result += "\n";
    }
    
    delete[] platforms;
    
    result += "Conclusion: OpenCL is AVAILABLE and USABLE on this device!\n";
    result += "Note: llama.cpp needs to be compiled with -DGGML_OPENCL=ON\n";
    
    LOGI("=== OpenCL Detection Complete: AVAILABLE ===");
    
    dlclose(opencl_handle);
    return env->NewStringUTF(result.c_str());
}

} // extern "C"
