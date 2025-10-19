#include <jni.h>
#include <android/log.h>
#include <dlfcn.h>
#include <string>
#include <vector>

#define LOG_TAG "OpenCL_Diagnostic"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

extern "C" JNIEXPORT jstring JNICALL
Java_com_offlineai_mnn_MnnInference_diagnoseOpenCL(JNIEnv* env, jclass clazz) {
    
    std::string result;
    result += "=== OpenCL Library Diagnostic ===\n\n";
    
    // List of possible OpenCL library paths (from MNN OpenCLWrapper.cpp)
    std::vector<std::string> opencl_paths = {
        "libOpenCL.so",
        "libGLES_mali.so",
        "libmali.so",
        "libOpenCL-pixel.so",
        "/system/vendor/lib64/libOpenCL.so",
        "/system/lib64/libOpenCL.so",
        "/system/vendor/lib64/egl/libGLES_mali.so",
        "/system/lib64/egl/libGLES_mali.so",
        "/vendor/lib64/libOpenCL.so",
        "/vendor/lib64/egl/libGLES_mali.so",
    };
    
    result += "Trying to load OpenCL libraries:\n";
    
    for (const auto& path : opencl_paths) {
        void* handle = dlopen(path.c_str(), RTLD_NOW | RTLD_LOCAL);
        
        if (handle != nullptr) {
            result += "✓ SUCCESS: " + path + "\n";
            
            // Try to get the actual loaded path
            Dl_info info;
            if (dladdr(dlsym(handle, "clGetPlatformIDs"), &info)) {
                result += "  Real path: " + std::string(info.dli_fname) + "\n";
            }
            
            LOGI("Successfully loaded: %s", path.c_str());
            dlclose(handle);
        } else {
            const char* error = dlerror();
            result += "✗ FAILED: " + path + "\n";
            if (error) {
                result += "  Error: " + std::string(error) + "\n";
                LOGE("Failed to load %s: %s", path.c_str(), error);
            }
        }
    }
    
    result += "\n=== End Diagnostic ===\n";
    
    return env->NewStringUTF(result.c_str());
}
