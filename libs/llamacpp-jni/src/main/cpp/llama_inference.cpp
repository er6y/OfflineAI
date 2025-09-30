#include <android/log.h>
#include <jni.h>
#include <iomanip>
#include <math.h>
#include <string>
#include <cstring>
#include <unistd.h>
#include <android/log.h>
#include <unordered_map>
#include <mutex>
#include <atomic>
#include <pthread.h>
#include <sys/types.h>
#include <fcntl.h>
#include <errno.h>
#include <stdarg.h>
#include <stdlib.h>
#include <sys/auxv.h>
#include <fstream>
#include <sstream>
#include <algorithm>
#include "llama.h"
#include "common.h"
#include "vulkan_version_patch.h"
#include "vulkan_runtime_detector.h"
#include "ggml-backend.h"
#include "ggml-backend-impl.h" // for ggml_backend_register()
#if defined(GGML_USE_VULKAN) || defined(GGML_VULKAN)
#include "ggml-vulkan.h" // for ggml_backend_vk_reg()
#endif
// Probes for CPU features and KleidiAI build
#include "ggml-cpu.h"
#if defined(GGML_CPU_KLEIDIAI) || defined(GGML_USE_CPU_KLEIDIAI)
#include "ggml-cpu/kleidiai/kleidiai.h"
#endif
// Multimodal support headers
#include "mtmd.h"
#include "mtmd-helper.h"
#include "clip.h"

// 在使用日志宏之前的前向声明，避免未声明标识符错误
void call_log_manager_print(const char* message);

// JNI全局引用，用于调用LogManager.print方法
static JavaVM* g_jvm = nullptr;
static jclass g_log_manager_class = nullptr;
static jmethodID g_log_manager_print_method = nullptr;

// 调试日志宏定义
#ifdef ENABLE_DEBUG_LOGS
    #define DEBUG_LOG(tag, fmt, ...) do { \
        __android_log_print(ANDROID_LOG_DEBUG, tag, "[DEBUG] " fmt, ##__VA_ARGS__); \
        char buffer[1024]; \
        snprintf(buffer, sizeof(buffer), "[llama-debug] " fmt, ##__VA_ARGS__); \
        std::string formatted_message = std::string(buffer) + "\n"; \
        call_log_manager_print(formatted_message.c_str()); \
    } while(0)
    #define ERROR_LOG(tag, fmt, ...) do { \
        __android_log_print(ANDROID_LOG_ERROR, tag, "[ERROR] " fmt, ##__VA_ARGS__); \
        char buffer[1024]; \
        snprintf(buffer, sizeof(buffer), "[llama-error] " fmt, ##__VA_ARGS__); \
        std::string formatted_message = std::string(buffer) + "\n"; \
        call_log_manager_print(formatted_message.c_str()); \
    } while(0)
    #define TRACE_LOG(tag, fmt, ...) do { \
        __android_log_print(ANDROID_LOG_VERBOSE, tag, "[TRACE] " fmt, ##__VA_ARGS__); \
        char buffer[1024]; \
        snprintf(buffer, sizeof(buffer), "[llama-trace] " fmt, ##__VA_ARGS__); \
        std::string formatted_message = std::string(buffer) + "\n"; \
        call_log_manager_print(formatted_message.c_str()); \
    } while(0)
#else
    #define DEBUG_LOG(tag, fmt, ...) // 发布版本禁用调试日志
    #define ERROR_LOG(tag, fmt, ...) do { \
        __android_log_print(ANDROID_LOG_ERROR, tag, fmt, ##__VA_ARGS__); \
        char buffer[1024]; \
        snprintf(buffer, sizeof(buffer), "[llama-error] " fmt, ##__VA_ARGS__); \
        std::string formatted_message = std::string(buffer) + "\n"; \
        call_log_manager_print(formatted_message.c_str()); \
    } while(0)
    #define TRACE_LOG(tag, fmt, ...) // 发布版本禁用跟踪日志
#endif

// 强制日志宏（始终启用）
#define FORCE_LOG(tag, fmt, ...) do { \
    __android_log_print(ANDROID_LOG_INFO, tag, "[FORCE] " fmt, ##__VA_ARGS__); \
    char buffer[1024]; \
    snprintf(buffer, sizeof(buffer), "[llama-force] " fmt, ##__VA_ARGS__); \
    std::string formatted_message = std::string(buffer) + "\n"; \
    call_log_manager_print(formatted_message.c_str()); \
} while(0)

// One-shot CPU/KleidiAI capability logging (prints build-time macros and runtime features)
static void log_cpu_kleidiai_capabilities_once() {
    static std::once_flag once_flag_caps;
    std::call_once(once_flag_caps, [](){
        // Print build-time architecture macro summary
        const char* arch =
        #if defined(__aarch64__)
            "aarch64";
        #elif defined(__arm__)
            "arm";
        #elif defined(__x86_64__)
            "x86_64";
        #elif defined(__i386__)
            "x86";
        #else
            "unknown";
        #endif

        // Print compiled-in KleidiAI status (based on compile-time macros)
        bool kleidiai_compiled = false;
        #if defined(GGML_CPU_KLEIDIAI) || defined(GGML_USE_CPU_KLEIDIAI)
            kleidiai_compiled = true;
        #endif

        // Collect build-time ARM feature macros (presence implies 1)
        int m_dotprod = 0;
        #if defined(__ARM_FEATURE_DOTPROD)
            m_dotprod = 1;
        #endif
        int m_fp16_vec = 0;
        #if defined(__ARM_FEATURE_FP16_VECTOR_ARITHMETIC)
            m_fp16_vec = 1;
        #endif
        int m_fp16_scalar = 0;
        #if defined(__ARM_FEATURE_FP16_SCALAR_ARITHMETIC)
            m_fp16_scalar = 1;
        #endif
        int m_neon = 0;
        #if defined(__ARM_NEON)
            m_neon = 1;
        #endif
        int m_aarch64 = 0;
        #if defined(__aarch64__)
            m_aarch64 = 1;
        #endif
        int m_sve = 0;
        #if defined(__ARM_FEATURE_SVE)
            m_sve = 1;
        #endif
        int m_sve2 = 0;
        #if defined(__ARM_FEATURE_SVE2)
            m_sve2 = 1;
        #endif

        // Runtime detection via ggml CPU feature probes
        const bool has_neon  = ggml_cpu_has_neon();
        const bool has_dp    = ggml_cpu_has_dotprod();
        const bool has_sve_  = ggml_cpu_has_sve();
        // NOTE: ggml does not expose ggml_cpu_has_sve2(); we only log SVE as a whole at runtime.
        const bool has_sve2_ = 0;

        // Print build-time macros
        FORCE_LOG("llama-android.cpp", "[CAPS] ---- Build-time (compiler macros) ----");
        FORCE_LOG("llama-android.cpp", "[CAPS] ARCH=%s", arch);
        FORCE_LOG("llama-android.cpp", "[CAPS] __ARM_FEATURE_DOTPROD=%d, __ARM_FEATURE_FP16_VECTOR_ARITHMETIC=%d, __ARM_FEATURE_FP16_SCALAR_ARITHMETIC=%d", m_dotprod, m_fp16_vec, m_fp16_scalar);
        FORCE_LOG("llama-android.cpp", "[CAPS] __ARM_NEON=%d, __aarch64__=%d, __ARM_FEATURE_SVE=%d, __ARM_FEATURE_SVE2=%d", m_neon, m_aarch64, m_sve, m_sve2);

        // Print compiled-in KleidiAI state
        FORCE_LOG("llama-android.cpp", "[KLEIDIAI] compiled-in: %s", kleidiai_compiled ? "true" : "false");

        // Print runtime CPU features
        FORCE_LOG("llama-android.cpp", "[CPU] runtime features -> neon=%d, dotprod=%d, sve=%d, sve2=%d", has_neon, has_dp, has_sve_, has_sve2_);
    });
}

// Global log tag for JNI logs
static const char* TAG = "LlamaCppJNI";

// ===== Context shift (KV-Cache sliding) config (JNI configurable) =====
static std::atomic<bool> g_ctx_shift_enabled{false};
static std::atomic<int>  g_ctx_shift_n_keep{1024};

// ===== JNI: context shift configuration setters/getters =====
extern "C"
JNIEXPORT void JNICALL
Java_com_starlocalrag_llamacpp_LlamaCppInference_set_1context_1shift(
        JNIEnv *, jclass, jboolean enable, jint n_keep) {
    g_ctx_shift_enabled.store(enable);
    g_ctx_shift_n_keep.store(std::max(0, (int)n_keep));
    FORCE_LOG(TAG, "[CTX_SHIFT] set_context_shift: enable=%s, n_keep=%d", enable ? "true" : "false", (int)n_keep);
}

extern "C"
JNIEXPORT jboolean JNICALL
Java_com_starlocalrag_llamacpp_LlamaCppInference_get_1context_1shift_1enabled(
        JNIEnv *, jclass) {
    return g_ctx_shift_enabled.load();
}

extern "C"
JNIEXPORT jint JNICALL
Java_com_starlocalrag_llamacpp_LlamaCppInference_get_1context_1shift_1n_1keep(
        JNIEnv *, jclass) {
    return g_ctx_shift_n_keep.load();
}

// 全局停止标志，用于中断推理
static std::atomic<bool> g_should_stop{false};
// 在首次请求 GPU 加速时按需加载 GGML 后端（仅加载一次）
static std::atomic<bool> g_ggml_backends_loaded{false};

// Write C++ code here.
//
// Do not forget to dynamically load the C++ library into your application.
//
// For instance,
//
// In MainActivity.java:
//    static {
//       System.loadLibrary("llama-android");
//    }
//
// Or, in MainActivity.kt:
//    companion object {
//      init {
//         System.loadLibrary("llama-android")
//      }
//    }

#define TAG "llama-android.cpp"
#define LOGi(...) do { \
    __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__); \
    char buffer[1024]; \
    snprintf(buffer, sizeof(buffer), __VA_ARGS__); \
    std::string formatted_message = "[llama-info] " + std::string(buffer) + "\n"; \
    call_log_manager_print(formatted_message.c_str()); \
} while(0)
#define LOGe(...) do { \
    __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__); \
    char buffer[1024]; \
    snprintf(buffer, sizeof(buffer), __VA_ARGS__); \
    std::string formatted_message = "[llama-error] " + std::string(buffer) + "\n"; \
    call_log_manager_print(formatted_message.c_str()); \
} while(0)

// stdout/stderr重定向相关变量
static int stdout_pipe[2] = {-1, -1};
static int stderr_pipe[2] = {-1, -1};
static pthread_t stdout_thread = 0;
static pthread_t stderr_thread = 0;
static bool redirect_initialized = false;
static volatile bool should_stop_threads = false;

// 读取管道并输出到logcat的线程函数
// 调用LogManager.print方法的辅助函数
void call_log_manager_print(const char* message) {
    if (!g_jvm || !g_log_manager_class || !g_log_manager_print_method) {
        return;
    }
    
    JNIEnv* env = nullptr;
    bool detach_needed = false;
    
    // 获取JNI环境
    int status = g_jvm->GetEnv((void**)&env, JNI_VERSION_1_6);
    if (status == JNI_EDETACHED) {
        status = g_jvm->AttachCurrentThread(&env, nullptr);
        detach_needed = true;
    }
    
    if (status == JNI_OK && env) {
        jstring jmessage = env->NewStringUTF(message);
        if (jmessage) {
            env->CallStaticVoidMethod(g_log_manager_class, g_log_manager_print_method, jmessage);
            env->DeleteLocalRef(jmessage);
        }
        
        if (detach_needed) {
            g_jvm->DetachCurrentThread();
        }
    }
}

void* stdout_reader_thread(void* arg) {
    char buffer[1024];
    ssize_t count;
    
    while (!should_stop_threads) {
        count = read(stdout_pipe[0], buffer, sizeof(buffer) - 1);
        if (count > 0) {
            buffer[count] = '\0';
            // 移除末尾的换行符
            if (count > 0 && buffer[count - 1] == '\n') {
                buffer[count - 1] = '\0';
            }
            
            // 输出到logcat
            __android_log_print(ANDROID_LOG_INFO, "llama-stdout", "%s", buffer);
        // 同时调用LogManager.print保存到文件
        std::string formatted_message = "[llama-stdout] " + std::string(buffer) + "\n";
        call_log_manager_print(formatted_message.c_str());
        } else if (count == 0) {
            // EOF reached
            break;
        } else if (errno == EAGAIN || errno == EWOULDBLOCK) {
            // No data available, sleep briefly
            usleep(10000); // 10ms
        } else {
            // Error occurred
            break;
        }
    }
    return nullptr;
}

void* stderr_reader_thread(void* arg) {
    char buffer[1024];
    ssize_t count;
    
    while (!should_stop_threads) {
        count = read(stderr_pipe[0], buffer, sizeof(buffer) - 1);
        if (count > 0) {
            buffer[count] = '\0';
            // 移除末尾的换行符
            if (count > 0 && buffer[count - 1] == '\n') {
                buffer[count - 1] = '\0';
            }
            
            // 输出到logcat
            __android_log_print(ANDROID_LOG_ERROR, "llama-stderr", "%s", buffer);
        // 同时调用LogManager.print保存到文件
        std::string formatted_message = "[llama-stderr] " + std::string(buffer) + "\n";
        call_log_manager_print(formatted_message.c_str());
        } else if (count == 0) {
            // EOF reached
            break;
        } else if (errno == EAGAIN || errno == EWOULDBLOCK) {
            // No data available, sleep briefly
            usleep(10000); // 10ms
        } else {
            // Error occurred
            break;
        }
    }
    return nullptr;
}

// 初始化stdout/stderr重定向
void setup_stdout_stderr_redirect() {
    if (redirect_initialized) {
        return;
    }
    
    FORCE_LOG(TAG, "[REDIRECT] Setting up stdout/stderr redirection to logcat...");
    
    // 创建stdout管道
    if (pipe(stdout_pipe) == -1) {
        ERROR_LOG(TAG, "[REDIRECT] Failed to create stdout pipe: %s", strerror(errno));
        return;
    }
    
    // 创建stderr管道
    if (pipe(stderr_pipe) == -1) {
        ERROR_LOG(TAG, "[REDIRECT] Failed to create stderr pipe: %s", strerror(errno));
        close(stdout_pipe[0]);
        close(stdout_pipe[1]);
        return;
    }
    
    // 备份原始的stdout和stderr
    int stdout_backup = dup(STDOUT_FILENO);
    int stderr_backup = dup(STDERR_FILENO);
    
    // 重定向stdout和stderr到管道
    if (dup2(stdout_pipe[1], STDOUT_FILENO) == -1) {
        ERROR_LOG(TAG, "[REDIRECT] Failed to redirect stdout: %s", strerror(errno));
        goto cleanup;
    }
    
    if (dup2(stderr_pipe[1], STDERR_FILENO) == -1) {
        ERROR_LOG(TAG, "[REDIRECT] Failed to redirect stderr: %s", strerror(errno));
        goto cleanup;
    }
    
    // 关闭管道的写端副本，保持重定向的文件描述符
    close(stdout_pipe[1]);
    close(stderr_pipe[1]);
    
    // 设置管道读端为非阻塞模式
    fcntl(stdout_pipe[0], F_SETFL, O_NONBLOCK);
    fcntl(stderr_pipe[0], F_SETFL, O_NONBLOCK);
    
    // 创建读取线程
    if (pthread_create(&stdout_thread, nullptr, stdout_reader_thread, nullptr) != 0) {
        ERROR_LOG(TAG, "[REDIRECT] Failed to create stdout reader thread");
        goto cleanup;
    }
    
    if (pthread_create(&stderr_thread, nullptr, stderr_reader_thread, nullptr) != 0) {
        ERROR_LOG(TAG, "[REDIRECT] Failed to create stderr reader thread");
        should_stop_threads = true;
        pthread_join(stdout_thread, nullptr);
        goto cleanup;
    }
    
    redirect_initialized = true;
    FORCE_LOG(TAG, "[REDIRECT] stdout/stderr redirection setup completed successfully");
    return;
    
cleanup:
    if (stdout_backup != -1) {
        dup2(stdout_backup, STDOUT_FILENO);
        close(stdout_backup);
    }
    if (stderr_backup != -1) {
        dup2(stderr_backup, STDERR_FILENO);
        close(stderr_backup);
    }
    if (stdout_pipe[0] != -1) close(stdout_pipe[0]);
    if (stdout_pipe[1] != -1) close(stdout_pipe[1]);
    if (stderr_pipe[0] != -1) close(stderr_pipe[0]);
    if (stderr_pipe[1] != -1) close(stderr_pipe[1]);
    ERROR_LOG(TAG, "[REDIRECT] Failed to setup stdout/stderr redirection");
}

jclass la_int_var;
jfieldID la_int_var_value;
jmethodID la_int_var_inc;

std::string cached_token_chars;

bool is_valid_utf8(const char * string) {
    //DEBUG_LOG("LlamaCppJNI", "is_valid_utf8 called with string=%p", string);
    
    if (!string) {
        //DEBUG_LOG("LlamaCppJNI", "is_valid_utf8: string is null, returning true");
        return true;
    }

    const unsigned char * bytes = (const unsigned char *)string;
    int num;
    int byte_count = 0;

    //DEBUG_LOG("LlamaCppJNI", "is_valid_utf8: starting validation loop");
    
    while (*bytes != 0x00) {
        byte_count++;
        if (byte_count > 1000) { // 防止无限循环
            //DEBUG_LOG("LlamaCppJNI", "is_valid_utf8: too many bytes, breaking loop");
            break;
        }
        
        if ((*bytes & 0x80) == 0x00) {
            // U+0000 to U+007F
            num = 1;
        } else if ((*bytes & 0xE0) == 0xC0) {
            // U+0080 to U+07FF
            num = 2;
        } else if ((*bytes & 0xF0) == 0xE0) {
            // U+0800 to U+FFFF
            num = 3;
        } else if ((*bytes & 0xF8) == 0xF0) {
            // U+10000 to U+10FFFF
            num = 4;
        } else {
            //DEBUG_LOG("LlamaCppJNI", "is_valid_utf8: invalid byte sequence at position %d", byte_count);
            return false;
        }

        bytes += 1;
        for (int i = 1; i < num; ++i) {
            if ((*bytes & 0xC0) != 0x80) {
                //DEBUG_LOG("LlamaCppJNI", "is_valid_utf8: invalid continuation byte at position %d", byte_count + i);
                return false;
            }
            bytes += 1;
        }
    }

    //DEBUG_LOG("LlamaCppJNI", "is_valid_utf8: validation completed successfully, total bytes=%d", byte_count);
    return true;
}

static void log_callback(ggml_log_level level, const char * fmt, void * data) {
    // 直接输出格式化字符串，不进行额外处理
    // 注意：这里假设fmt已经是完整的字符串，不需要额外的格式化参数
    if (level == GGML_LOG_LEVEL_ERROR) {
        __android_log_print(ANDROID_LOG_ERROR, TAG, "%s", fmt);
        // 同时调用LogManager.print保存到文件
        std::string formatted_message = "[llama-error] " + std::string(fmt) + "\n";
        call_log_manager_print(formatted_message.c_str());
    } else if (level == GGML_LOG_LEVEL_INFO) {
        __android_log_print(ANDROID_LOG_INFO, TAG, "%s", fmt);
        // 同时调用LogManager.print保存到文件
        std::string formatted_message = "[llama-info] " + std::string(fmt) + "\n";
        call_log_manager_print(formatted_message.c_str());
    } else if (level == GGML_LOG_LEVEL_WARN) {
        __android_log_print(ANDROID_LOG_WARN, TAG, "%s", fmt);
        // 同时调用LogManager.print保存到文件
        std::string formatted_message = "[llama-warn] " + std::string(fmt) + "\n";
        call_log_manager_print(formatted_message.c_str());
    } else {
        __android_log_print(ANDROID_LOG_DEFAULT, TAG, "%s", fmt);
        // 同时调用LogManager.print保存到文件
        std::string formatted_message = "[llama-default] " + std::string(fmt) + "\n";
        call_log_manager_print(formatted_message.c_str());
    }
}

// 使用新的Vulkan运行时检测器进行兼容性检查
static bool is_vulkan_suitable_for_llamacpp() {
    DEBUG_LOG(TAG, "[VULKAN] Checking Vulkan suitability for llama.cpp...");
    
    vulkan_runtime::VulkanRuntimeInfo info = vulkan_runtime::detect_vulkan_runtime();
    
    // PATCH: Lower requirement to Vulkan >= 1.1 for Mali G610 compatibility
    bool version_ok = VULKAN_VERSION_GE(info.detected_api_version, 1, 1, 0);
    bool basic_ok = info.library_available && info.instance_creation_works && info.physical_devices_available;
    bool ok = basic_ok && version_ok;

    FORCE_LOG(TAG, "[VULKAN] PATCHED version gate: require >= 1.1 (was 1.2)");
    FORCE_LOG(TAG, "[VULKAN] Detected Vulkan API %u.%u.%u; version_ok=%s; basic_ok=%s; suitable=%s",
              VK_VERSION_MAJOR(info.detected_api_version),
              VK_VERSION_MINOR(info.detected_api_version),
              VK_VERSION_PATCH(info.detected_api_version),
              version_ok ? "yes" : "no",
              basic_ok ? "yes" : "no",
              ok ? "yes" : "no");

    return ok;
}



extern "C"
JNIEXPORT jlong JNICALL
Java_com_starlocalrag_llamacpp_LlamaCppInference_load_1model(JNIEnv *env, jobject, jstring filename) {
    LOGi("[TRACE_POINT_1] ===== LOAD_MODEL FUNCTION ENTRY =====");
    
    llama_model_params model_params = llama_model_default_params();

    auto path_to_model = env->GetStringUTFChars(filename, 0);
    LOGi("[TRACE_POINT_1] Loading model from: %s", path_to_model);
    LOGi("[MEMORY_TRACE] Model loading started - checking memory state");

    auto model = llama_model_load_from_file(path_to_model, model_params);
    env->ReleaseStringUTFChars(filename, path_to_model);

    if (!model) {
        LOGe("load_model() failed");
        env->ThrowNew(env->FindClass("java/lang/IllegalStateException"), "load_model() failed");
        return 0;
    }

    return reinterpret_cast<jlong>(model);
}

// 后端偏好到GPU层数的映射函数
// 后端偏好到GPU层数的映射函数（保留用于向后兼容）
// Unified backend configuration function - eliminates code duplication
// Returns true if GPU backend should be loaded, false for CPU-only
static bool configure_backend_for_model(const std::string& backend, llama_model_params& model_params) {
    FORCE_LOG(TAG, "[BACKEND] Configuring backend: %s", backend.c_str());
    
    if (backend == "CPU") {
        // CPU backend: register and initialize CPU backend only, set layer=0
        model_params.n_gpu_layers = 0;
        FORCE_LOG(TAG, "[BACKEND] CPU backend selected: n_gpu_layers=0");
        return false; // Do not load GPU backends
        
    } else if (backend == "VULKAN") {
        // Vulkan backend: set layer=-1 (use all GPU layers)
        // Note: This will load Vulkan backend regardless of version compatibility
        model_params.n_gpu_layers = -1; // Use all GPU layers
        FORCE_LOG(TAG, "[BACKEND] Vulkan backend selected: n_gpu_layers=-1 (all layers)");
        return true; // Load GPU backends
        
    } else if (backend == "KLEIDIAI") {
        // KleidiAI preference means: prefer CPU backend with Arm-optimized microkernels
        // Equivalent to CLI "--device none" while keeping GPU backends compiled-in.
        model_params.n_gpu_layers = 0;
        FORCE_LOG(TAG, "[BACKEND] KleidiAI preference selected -> forcing CPU-only path (n_gpu_layers=0)");
        FORCE_LOG(TAG, "[BACKEND] Note: Vulkan backend remains compiled and loadable, but will not be used for this session");
        // Important: KleidiAI runtime performance depends on build flag GGML_CPU_KLEIDIAI=ON
        return false; // Do not load GPU backends for this model
        
    } else if (backend == "OPENCL" || backend == "BLAS" || backend == "CANN") {
        // Other backends: TBD implementation, fallback to CPU for now
        model_params.n_gpu_layers = 0;
        FORCE_LOG(TAG, "[BACKEND] %s backend TBD (not yet implemented), falling back to CPU", backend.c_str());
        return false; // Do not load GPU backends
        
    } else {
        // Unknown backend: default to CPU
        model_params.n_gpu_layers = 0;
        FORCE_LOG(TAG, "[BACKEND] Unknown backend '%s', defaulting to CPU", backend.c_str());
        return false; // Do not load GPU backends
    }
}

// Legacy function for backward compatibility - deprecated, use configure_backend_for_model instead
static int map_backend_preference_to_gpu_layers(const char* backend_preference) {
    if (!backend_preference) {
        FORCE_LOG(TAG, "[BACKEND] Backend preference is null, defaulting to CPU");
        return 0;
    }
    
    std::string backend(backend_preference);
    FORCE_LOG(TAG, "[BACKEND] [DEPRECATED] Using legacy mapping for backend: %s", backend.c_str());
    
    if (backend == "CPU") {
        return 0;
    } else if (backend == "VULKAN") {
        return -1; // Use all GPU layers
    } else if (backend == "OPENCL" || backend == "BLAS" || backend == "CANN") {
        return 0;
    } else {
        return 0;
    }
}



// === CPU info logging helper (English logs) ===
static void log_cpu_info_brief() {
    // Print compile-time architecture
#if defined(__aarch64__)
    const char* arch = "aarch64";
#elif defined(__arm__)
    const char* arch = "arm";
#elif defined(__x86_64__)
    const char* arch = "x86_64";
#elif defined(__i386__)
    const char* arch = "x86";
#else
    const char* arch = "unknown";
#endif
    FORCE_LOG(TAG, "[CPU] arch: %s", arch);

    // Read a brief summary from /proc/cpuinfo (model/hardware/features)
    std::ifstream fin("/proc/cpuinfo");
    if (fin.good()) {
        std::string line;
        std::string model, hardware, features;
        int lines_read = 0;
        while (std::getline(fin, line) && lines_read < 200) {
            ++lines_read;
            if (line.rfind("model name", 0) == 0 || line.rfind("Processor", 0) == 0) {
                model = line;
            } else if (line.rfind("Hardware", 0) == 0) {
                hardware = line;
            } else if (line.rfind("Features", 0) == 0 || line.rfind("flags", 0) == 0) {
                features = line;
            }
        }
        if (!model.empty())    FORCE_LOG(TAG, "[CPU] %s", model.c_str());
        if (!hardware.empty()) FORCE_LOG(TAG, "[CPU] %s", hardware.c_str());
        if (!features.empty()) FORCE_LOG(TAG, "[CPU] %s", features.c_str());
    } else {
        FORCE_LOG(TAG, "[CPU] /proc/cpuinfo not accessible");
    }

    // Read auxv hwcap values (numeric) and optionally decode well-known bits
#ifdef AT_HWCAP
    unsigned long hwcap = getauxval(AT_HWCAP);
#else
    unsigned long hwcap = 0;
#endif
#ifdef AT_HWCAP2
    unsigned long hwcap2 = getauxval(AT_HWCAP2);
#else
    unsigned long hwcap2 = 0;
#endif
    FORCE_LOG(TAG, "[CPU] HWCAP: 0x%lx HWCAP2: 0x%lx", hwcap, hwcap2);

#if defined(__aarch64__)
    // Decode aarch64-relevant bits if headers expose them
#ifdef HWCAP_ASIMDDP
    const char* has_asimddp = (hwcap & HWCAP_ASIMDDP) ? "yes" : "no";
#else
    const char* has_asimddp = "unknown";
#endif
#ifdef HWCAP2_SME
    const char* has_sme_aux = (hwcap2 & HWCAP2_SME) ? "yes" : "no";
#else
    const char* has_sme_aux = "unknown";
#endif
    FORCE_LOG(TAG, "[CPU] auxv -> asimddp(dotprod)=%s sme=%s", has_asimddp, has_sme_aux);
#endif
}

// 新增：带后端偏好参数的模型加载方法
extern "C"
JNIEXPORT jlong JNICALL
Java_com_starlocalrag_llamacpp_LlamaCppInference_load_1model_1with_1backend(JNIEnv *env, jobject, jstring filename, jstring backend_preference) {
    // Ensure llama/ggml logs are captured early (Android log + file sink)
    llama_log_set(log_callback, NULL);

#if defined(GGML_USE_VULKAN)
    FORCE_LOG(TAG, "[VULKAN] compiled-in: yes");
#else
    FORCE_LOG(TAG, "[VULKAN] compiled-in: no");
#endif
#ifdef GGML_VULKAN
    FORCE_LOG(TAG, "[VULKAN] GGML_VULKAN macro is defined (legacy)");
#endif
    // Proactively probe Vulkan runtime and print summary
    bool runtime_ok = is_vulkan_suitable_for_llamacpp();

    // 获取后端偏好字符串
    const char* backend_pref_cstr = env->GetStringUTFChars(backend_preference, nullptr);
    if (!backend_pref_cstr) {
        LOGe("load_model_with_backend(): Failed to get backend preference string");
        env->ThrowNew(env->FindClass("java/lang/IllegalArgumentException"), "Backend preference cannot be null");
        return 0;
    }
    
    std::string backend(backend_pref_cstr);
    FORCE_LOG(TAG, "[BACKEND] Loading model with backend preference: %s", backend.c_str());
    // Control SME microkernels via env before configuring backends
    if (backend == "KLEIDIAI-SME") {
        // Enable SME microkernels for KleidiAI-capable builds
        setenv("GGML_KLEIDIAI_SME", "1", 1); // Enable SME
        FORCE_LOG(TAG, "[BACKEND] SME microkernels enabled via GGML_KLEIDIAI_SME=1");
    } else if (backend == "CPU") {
        // CPU implies KleidiAI path with SME disabled
        setenv("GGML_KLEIDIAI_SME", "0", 1); // Disable SME
        FORCE_LOG(TAG, "[BACKEND] SME microkernels disabled via GGML_KLEIDIAI_SME=0 (CPU mode)");
    } else {
        // Do not touch env for other backends; default behavior applies
        FORCE_LOG(TAG, "[BACKEND] SME env untouched for backend: %s", backend.c_str());
    }

    // === KleidiAI/CPU feature probes (English logs) ===
#if defined(GGML_CPU_KLEIDIAI) || defined(GGML_USE_CPU_KLEIDIAI)
    FORCE_LOG(TAG, "[KLEIDIAI] compiled-in: yes");
#else
    FORCE_LOG(TAG, "[KLEIDIAI] compiled-in: no");
#endif
    // Print CPU info snapshot to help judge runtime feature support
    log_cpu_info_brief();
    {
        int has_dotprod = ggml_cpu_has_dotprod();
        int has_sme     = ggml_cpu_has_sme();
        FORCE_LOG(TAG, "[CPU] features -> dotprod=%d sme=%d", has_dotprod, has_sme);
    }
#if defined(GGML_CPU_KLEIDIAI) || defined(GGML_USE_CPU_KLEIDIAI)
    {
        ggml_backend_buffer_type_t bt = ggml_backend_cpu_kleidiai_buffer_type();
        FORCE_LOG(TAG, "[KLEIDIAI] buffer type available: %s", bt ? "yes" : "no");
    }
#endif
    
    // 释放字符串资源
    env->ReleaseStringUTFChars(backend_preference, backend_pref_cstr);
    
    // 获取文件路径
    const char *file_path = env->GetStringUTFChars(filename, nullptr);
    if (!file_path) {
        LOGe("load_model_with_backend(): Failed to get file path");
        env->ThrowNew(env->FindClass("java/lang/IllegalArgumentException"), "Failed to get file path");
        return 0;
    }
    
    llama_model_params model_params = llama_model_default_params();
    
    // Use unified backend configuration function
    bool should_load_gpu_backends = configure_backend_for_model(backend, model_params);
    FORCE_LOG(TAG, "[BACKEND] Final model params: n_gpu_layers=%d", model_params.n_gpu_layers);
    
    // Load all available backends using unified loading approach
    bool already_loaded = g_ggml_backends_loaded.load();
    if (!already_loaded) {
        FORCE_LOG(TAG, "[BACKEND] Loading all available backends...");
        
#if defined(GGML_USE_VULKAN) || defined(GGML_VULKAN)
    // Register statically-linked Vulkan backend before dynamic discovery
    // Skip Vulkan registration in CPU-only mode to avoid initialization errors on emulators
    if (backend == "VULKAN") {
        FORCE_LOG(TAG, "[BACKEND] Register Vulkan (static) via ggml_backend_vk_reg() before ggml_backend_load_all()");
        ggml_backend_register(ggml_backend_vk_reg());
    } else {
        FORCE_LOG(TAG, "[BACKEND] Skipping Vulkan registration (CPU-only mode)");
    }
#endif
        
        // Use unified backend loading - this loads all compiled backends
        ggml_backend_load_all();
        
        g_ggml_backends_loaded.store(true);
        
        // Enumerate all available backend devices
        size_t dev_count = ggml_backend_dev_count();
        FORCE_LOG(TAG, "[BACKEND] Available backend devices: count=%zu", dev_count);
        bool found_vulkan = false;
        for (size_t i = 0; i < dev_count; ++i) {
            auto dev = ggml_backend_dev_get(i);
            const char * dev_name = ggml_backend_dev_name(dev);
            int dev_type_val = (int) ggml_backend_dev_type(dev);
            // Fix: determine Vulkan by backend registry name, not device name
            ggml_backend_reg_t dev_reg = ggml_backend_dev_backend_reg(dev);
            const char * reg_name = ggml_backend_reg_name(dev_reg);
            FORCE_LOG(TAG, "[BACKEND] Device #%zu: name=%s, type=%d, backend=%s", i,
                      dev_name ? dev_name : "(null)",
                      dev_type_val,
                      reg_name ? reg_name : "(null)");
            if (reg_name && (strcmp(reg_name, "Vulkan") == 0 || strcmp(reg_name, "vulkan") == 0)) {
                found_vulkan = true;
            }
        }
        FORCE_LOG(TAG, "[BACKEND] Vulkan device available (by backend name): %s", found_vulkan ? "yes" : "no");
        
        // If Vulkan was explicitly requested but runtime unsuitable or no device, force CPU fallback
        if (backend == std::string("VULKAN") && (!found_vulkan || !runtime_ok)) {
            model_params.n_gpu_layers = 0; // force CPU-only
            should_load_gpu_backends = false;
            FORCE_LOG(TAG, "[BACKEND] Vulkan requested but unavailable/unsuitable -> fallback to CPU (n_gpu_layers=0)");
        }
        
        if (!should_load_gpu_backends) {
            FORCE_LOG(TAG, "[BACKEND] Note: GPU backends loaded but will be avoided due to safety settings");
        }
    }
    
    // Map -1 to a large sentinel (e.g., 999) which llama.cpp commonly treats as "all layers"
    if (model_params.n_gpu_layers < 0) {
        model_params.n_gpu_layers = 999;
        FORCE_LOG(TAG, "[GPU] n_gpu_layers requested=-1 -> set to 999 for llama.cpp (all layers)");
    } else {
        FORCE_LOG(TAG, "[GPU] n_gpu_layers set to %d", model_params.n_gpu_layers);
    }
    
    FORCE_LOG(TAG, "Loading model from %s with backend: %s (n_gpu_layers=%d)", 
             file_path, backend.c_str(), model_params.n_gpu_layers);
    
    llama_model *model = llama_load_model_from_file(file_path, model_params);
    env->ReleaseStringUTFChars(filename, file_path);
    
    if (!model) {
        LOGe("load_model_with_backend(): Failed to load model from %s", file_path);
        env->ThrowNew(env->FindClass("java/lang/RuntimeException"), "Failed to load model");
        return 0;
    }
    
    // Get and print detailed model layer allocation information
    int32_t total_layers = llama_model_n_layer(model);
    FORCE_LOG(TAG, "[MODEL_INFO] Model loaded successfully with backend: %s", backend.c_str());
    FORCE_LOG(TAG, "[MODEL_INFO] Total model layers: %d", total_layers);
    FORCE_LOG(TAG, "[MODEL_INFO] Configured GPU layers: %d", model_params.n_gpu_layers);
    
    if (model_params.n_gpu_layers != 0) {
        int actual_gpu_layers = (model_params.n_gpu_layers >= 999) ? total_layers : std::min(model_params.n_gpu_layers, total_layers);
        int cpu_layers = total_layers - actual_gpu_layers;
        FORCE_LOG(TAG, "[MODEL_INFO] ✓ GPU acceleration enabled");
        FORCE_LOG(TAG, "[MODEL_INFO] ✓ Layers on GPU: %d/%d", actual_gpu_layers, total_layers);
        FORCE_LOG(TAG, "[MODEL_INFO] ✓ Layers on CPU: %d/%d", cpu_layers, total_layers);
        if (model_params.n_gpu_layers >= 999) {
            FORCE_LOG(TAG, "[MODEL_INFO] ✓ All layers offloaded to GPU (n_gpu_layers = 999/-1)");
        }
    } else {
        FORCE_LOG(TAG, "[MODEL_INFO] ✓ CPU-only mode");
        FORCE_LOG(TAG, "[MODEL_INFO] ✓ All %d layers running on CPU", total_layers);
    }
    
    FORCE_LOG(TAG, "[MODEL_INFO] Model handle: %p", model);
    return reinterpret_cast<jlong>(model);
}

// 新增：带后端偏好参数的上下文创建方法
extern "C"
JNIEXPORT jlong JNICALL
Java_com_starlocalrag_llamacpp_LlamaCppInference_new_1context_1with_1backend(JNIEnv *env, jobject, jlong model_handle, jint n_ctx, jint n_batch, jint n_threads) {
    FORCE_LOG(TAG, "[BACKEND] Creating context (backend already configured during model loading)");
    
    // 验证模型句柄
    auto model = reinterpret_cast<llama_model *>(model_handle);
    if (!model) {
        LOGe("new_context_with_backend(): model cannot be null");
        env->ThrowNew(env->FindClass("java/lang/IllegalArgumentException"), "Model cannot be null");
        return 0;
    }
    
    // 使用传入的参数创建上下文
    llama_context_params ctx_params = llama_context_default_params();
    ctx_params.n_ctx = n_ctx > 0 ? n_ctx : 2048;
    ctx_params.n_batch = n_batch > 0 ? n_batch : 512;
    ctx_params.n_threads = n_threads > 0 ? n_threads : 1;
    
    FORCE_LOG(TAG, "[BACKEND] Context params: n_ctx=%d, n_batch=%d, n_threads=%d", 
              ctx_params.n_ctx, ctx_params.n_batch, ctx_params.n_threads);
    
    llama_context *ctx = llama_new_context_with_model(model, ctx_params);
    
    if (!ctx) {
        LOGe("new_context_with_backend(): Failed to create context");
        env->ThrowNew(env->FindClass("java/lang/RuntimeException"), "Failed to create context");
        return 0;
    }
    
    FORCE_LOG(TAG, "[BACKEND] Context created successfully (backend configured during model loading)");
    return reinterpret_cast<jlong>(ctx);
}

// 新增：带GPU层数参数的模型加载方法
// DEPRECATED: load_model_with_gpu function has been removed.
// Use load_model_with_backend with backend preference instead.
// This function was merged into load_model_with_backend for better code maintainability.

extern "C"
JNIEXPORT void JNICALL
Java_com_starlocalrag_llamacpp_LlamaCppInference_free_1model(JNIEnv *, jobject, jlong model) {
    //DEBUG_LOG("LlamaCppJNI", "free_model called with pointer=%p", (void*)model);
    
    if (model == 0) {
        //DEBUG_LOG("LlamaCppJNI", "free_model: model pointer is 0, skipping");
        return;
    }
    
    auto model_ptr = reinterpret_cast<llama_model *>(model);
    if (!model_ptr) {
        //DEBUG_LOG("LlamaCppJNI", "free_model: model is null after cast, skipping");
        return;
    }
    
    try {
        //DEBUG_LOG("LlamaCppJNI", "free_model: calling llama_model_free");
        llama_model_free(model_ptr);
        //DEBUG_LOG("LlamaCppJNI", "free_model: llama_model_free completed successfully");
    } catch (const std::exception& e) {
        //ERROR_LOG("LlamaCppJNI", "free_model: exception caught: %s", e.what());
    } catch (...) {
        //ERROR_LOG("LlamaCppJNI", "free_model: unknown exception caught");
    }
}

extern "C"
JNIEXPORT jlong JNICALL
Java_com_starlocalrag_llamacpp_LlamaCppInference_new_1context(JNIEnv *env, jobject, jlong jmodel) {
    auto model = reinterpret_cast<llama_model *>(jmodel);

    if (!model) {
        LOGe("new_context(): model cannot be null");
        env->ThrowNew(env->FindClass("java/lang/IllegalArgumentException"), "Model cannot be null");
        return 0;
    }

    int n_threads = std::max(1, std::min(8, (int) sysconf(_SC_NPROCESSORS_ONLN) - 2));
    LOGi("Using %d threads", n_threads);

    llama_context_params ctx_params = llama_context_default_params();

    ctx_params.n_ctx           = 2048;  // 默认上下文大小，应从Java层传递
    ctx_params.n_batch         = 2048;  // 设置batch大小与上下文大小一致，避免超出限制
    ctx_params.n_threads       = n_threads;
    ctx_params.n_threads_batch = n_threads;

    llama_context * context = llama_new_context_with_model(model, ctx_params);

    if (!context) {
        LOGe("llama_new_context_with_model() returned null)");
        env->ThrowNew(env->FindClass("java/lang/IllegalStateException"),
                      "llama_new_context_with_model() returned null)");
        return 0;
    }

    return reinterpret_cast<jlong>(context);
}

// 新增：带参数的上下文创建方法
extern "C"
JNIEXPORT jlong JNICALL
Java_com_starlocalrag_llamacpp_LlamaCppInference_new_1context_1with_1params(JNIEnv *env, jobject, jlong jmodel, jint context_size, jint threads, jint gpu_layers) {
    auto model = reinterpret_cast<llama_model *>(jmodel);

    if (!model) {
        LOGe("new_context_with_params(): model cannot be null");
        env->ThrowNew(env->FindClass("java/lang/IllegalArgumentException"), "Model cannot be null");
        return 0;
    }

    // 使用传入的参数，但仍然做合理性检查
    int n_threads = std::max(1, std::min(threads, (int) sysconf(_SC_NPROCESSORS_ONLN)));
    int n_ctx = std::max(512, std::min(context_size, 32768)); // 限制在合理范围内
    int n_gpu_layers = std::max(-1, gpu_layers); // -1表示全部使用GPU，0表示仅CPU
    
    LOGi("Creating context with params - ctx: %d, threads: %d, gpu_layers: %d", n_ctx, n_threads, n_gpu_layers);

    llama_context_params ctx_params = llama_context_default_params();

    ctx_params.n_ctx           = n_ctx;
    ctx_params.n_batch         = n_ctx;  // 设置batch大小等于上下文大小，统一使用maxSequenceLength
    ctx_params.n_threads       = n_threads;
    ctx_params.n_threads_batch = n_threads;
    
    LOGi("Context params set - n_ctx: %d, n_batch: %d, n_threads: %d", 
         ctx_params.n_ctx, ctx_params.n_batch, ctx_params.n_threads);
    // 注意：n_gpu_layers 属于 llama_model_params，不是 llama_context_params
    // GPU层数设置应该在模型加载时进行，这里不需要设置

    llama_context * context = llama_new_context_with_model(model, ctx_params);

    if (!context) {
        LOGe("llama_new_context_with_model() returned null)");
        env->ThrowNew(env->FindClass("java/lang/IllegalStateException"),
                      "llama_new_context_with_model() returned null)");
        return 0;
    }

    return reinterpret_cast<jlong>(context);
}

extern "C"
JNIEXPORT void JNICALL
Java_com_starlocalrag_llamacpp_LlamaCppInference_free_1context(JNIEnv *, jobject, jlong context) {
    //DEBUG_LOG("LlamaCppJNI", "free_context called with pointer=%p", (void*)context);
    
    if (context == 0) {
        //DEBUG_LOG("LlamaCppJNI", "free_context: context pointer is 0, skipping");
        return;
    }
    
    auto context_ptr = reinterpret_cast<llama_context *>(context);
    if (!context_ptr) {
        //DEBUG_LOG("LlamaCppJNI", "free_context: context is null after cast, skipping");
        return;
    }
    
    try {
        //DEBUG_LOG("LlamaCppJNI", "free_context: calling llama_free");
        llama_free(context_ptr);
        //DEBUG_LOG("LlamaCppJNI", "free_context: llama_free completed successfully");
    } catch (const std::exception& e) {
        //ERROR_LOG("LlamaCppJNI", "free_context: exception caught: %s", e.what());
    } catch (...) {
        //ERROR_LOG("LlamaCppJNI", "free_context: unknown exception caught");
    }
}

extern "C"
JNIEXPORT void JNICALL
Java_com_starlocalrag_llamacpp_LlamaCppInference_backend_1free(JNIEnv *env, jobject) {
    llama_backend_free();
    
    // 清理JNI全局引用
    if (g_log_manager_class) {
        env->DeleteGlobalRef(g_log_manager_class);
        g_log_manager_class = nullptr;
    }
    g_log_manager_print_method = nullptr;
    g_jvm = nullptr;
}

extern "C"
JNIEXPORT void JNICALL
Java_com_starlocalrag_llamacpp_LlamaCppInference_log_1to_1android(JNIEnv *, jobject) {
    llama_log_set(log_callback, NULL);
}

extern "C"
JNIEXPORT jstring JNICALL
Java_com_starlocalrag_llamacpp_LlamaCppInference_bench_1model(
        JNIEnv *env,
        jobject,
        jlong context_pointer,
        jlong model_pointer,
        jlong batch_pointer,
        jint pp,
        jint tg,
        jint pl,
        jint nr
        ) {
    auto pp_avg = 0.0;
    auto tg_avg = 0.0;
    auto pp_std = 0.0;
    auto tg_std = 0.0;

    const auto context = reinterpret_cast<llama_context *>(context_pointer);
    const auto model = reinterpret_cast<llama_model *>(model_pointer);
    const auto batch = reinterpret_cast<llama_batch *>(batch_pointer);

    const int n_ctx = llama_n_ctx(context);

    LOGi("n_ctx = %d", n_ctx);

    int i, j;
    int nri;
    for (nri = 0; nri < nr; nri++) {
        LOGi("Benchmark prompt processing (pp)");

        common_batch_clear(*batch);

        const int n_tokens = pp;
        for (i = 0; i < n_tokens; i++) {
            common_batch_add(*batch, 0, i, { 0 }, false);
        }

        batch->logits[batch->n_tokens - 1] = true;
        llama_memory_t mem = llama_get_memory(context);
        llama_memory_clear(mem, true);

        const auto t_pp_start = ggml_time_us();
        if (llama_decode(context, *batch) != 0) {
            LOGi("llama_decode() failed during prompt processing");
        }
        const auto t_pp_end = ggml_time_us();

        // bench text generation

        LOGi("Benchmark text generation (tg)");

        llama_memory_clear(mem, true);
        const auto t_tg_start = ggml_time_us();
        for (i = 0; i < tg; i++) {

            common_batch_clear(*batch);
            for (j = 0; j < pl; j++) {
                common_batch_add(*batch, 0, i, { j }, true);
            }

            LOGi("llama_decode() text generation: %d", i);
            if (llama_decode(context, *batch) != 0) {
                LOGi("llama_decode() failed during text generation");
            }
        }

        const auto t_tg_end = ggml_time_us();

        llama_memory_clear(mem, true);

        const auto t_pp = double(t_pp_end - t_pp_start) / 1000000.0;
        const auto t_tg = double(t_tg_end - t_tg_start) / 1000000.0;

        const auto speed_pp = double(pp) / t_pp;
        const auto speed_tg = double(pl * tg) / t_tg;

        pp_avg += speed_pp;
        tg_avg += speed_tg;

        pp_std += speed_pp * speed_pp;
        tg_std += speed_tg * speed_tg;

        LOGi("pp %f t/s, tg %f t/s", speed_pp, speed_tg);
    }

    pp_avg /= double(nr);
    tg_avg /= double(nr);

    if (nr > 1) {
        pp_std = sqrt(pp_std / double(nr - 1) - pp_avg * pp_avg * double(nr) / double(nr - 1));
        tg_std = sqrt(tg_std / double(nr - 1) - tg_avg * tg_avg * double(nr) / double(nr - 1));
    } else {
        pp_std = 0;
        tg_std = 0;
    }

    char model_desc[128];
    llama_model_desc(model, model_desc, sizeof(model_desc));

    const auto model_size     = double(llama_model_size(model)) / 1024.0 / 1024.0 / 1024.0;
    const auto model_n_params = double(llama_model_n_params(model)) / 1e9;

    const auto backend    = "(Android)"; // TODO: What should this be?

    std::stringstream result;
    result << std::setprecision(2);
    result << "| model | size | params | backend | test | t/s |\n";
    result << "| --- | --- | --- | --- | --- | --- |\n";
    result << "| " << model_desc << " | " << model_size << "GiB | " << model_n_params << "B | " << backend << " | pp " << pp << " | " << pp_avg << " ± " << pp_std << " |\n";
    result << "| " << model_desc << " | " << model_size << "GiB | " << model_n_params << "B | " << backend << " | tg " << tg << " | " << tg_avg << " ± " << tg_std << " |\n";

    return env->NewStringUTF(result.str().c_str());
}

// 全局变量来跟踪batch的token数量，用于正确释放内存
static std::unordered_map<llama_batch*, int> batch_token_counts;
static std::unordered_map<llama_batch*, int> batch_seq_max_counts;
static std::mutex batch_map_mutex;

extern "C"
JNIEXPORT jlong JNICALL
Java_com_starlocalrag_llamacpp_LlamaCppInference_new_1batch(JNIEnv *env, jobject, jint n_tokens, jint embd, jint n_seq_max) {
    LOGi("[TRACE_POINT_0] ===== NEW_BATCH FUNCTION ENTRY =====");
    LOGi("[TRACE_POINT_0] new_batch called with n_tokens=%d, embd=%d, n_seq_max=%d", n_tokens, embd, n_seq_max);
    // New batch start logging removed
    // Input params logging removed

    // 参数验证
    if (n_tokens <= 0 || n_seq_max <= 0) {
        LOGe("[ERROR] Invalid batch parameters: n_tokens=%d, n_seq_max=%d", n_tokens, n_seq_max);
        return 0;
    }

    // 限制参数范围以防止过度内存分配
    if (n_tokens > 8192) {
        LOGe("[ERROR] n_tokens too large: %d (max: 8192)", n_tokens);
        return 0;
    }
    
    if (n_seq_max > 64) {
        LOGe("[ERROR] n_seq_max too large: %d (max: 64)", n_seq_max);
        return 0;
    }
    
    // Parameter validation logging removed

    // 使用官方Android示例的手动内存分配方式
    // Source: Copy of llama.cpp:llama_batch_init but heap-allocated.
    llama_batch *batch = new llama_batch {
        0,
        nullptr,
        nullptr,
        nullptr,
        nullptr,
        nullptr,
        nullptr,
    };

    // 内存对齐验证函数
    auto check_memory_alignment = [](void* ptr, const char* name, size_t expected_align = 16) {
        if (!ptr) {
            LOGe("[MEMORY_CHECK] %s allocation failed!", name);
            return false;
        }
        uintptr_t addr = (uintptr_t)ptr;
        size_t alignment = addr % expected_align;
        LOGi("[MEMORY_CHECK] %s: addr=%p, alignment=%zu bytes (addr %% %zu = %zu)", 
             name, ptr, alignment, expected_align, alignment);
        if (alignment != 0) {
            LOGe("[MEMORY_CHECK] WARNING: %s not aligned to %zu bytes!", name, expected_align);
            return false;
        }
        LOGi("[MEMORY_CHECK] %s: properly aligned to %zu bytes", name, expected_align);
        return true;
    };
    
    // 内存连续性检查函数
    auto check_memory_contiguity = [](void* ptr, size_t element_size, size_t count, const char* name) {
        if (!ptr || count == 0) return true;
        
        // 检查内存是否连续分配
        char* base = (char*)ptr;
        bool is_contiguous = true;
        
        // 简单的连续性检查：验证内存块是否在合理范围内
        uintptr_t start_addr = (uintptr_t)base;
        uintptr_t end_addr = start_addr + (element_size * count);
        size_t total_size = element_size * count;
        
        // Contiguity check logging removed
        
        // 检查地址范围是否合理（不应该跨越太大的内存区域）
        if (total_size > 0 && total_size < SIZE_MAX) {
            // 尝试访问第一个和最后一个元素来验证内存可访问性
            volatile char first_byte = base[0];
            volatile char last_byte = base[total_size - 1];
            // Memory access test logging removed
        }
        
        // Android模拟器特殊检查：验证内存页对齐
        size_t page_size = 4096; // 典型页大小
        uintptr_t page_start = start_addr & ~(page_size - 1);
        uintptr_t page_end = (end_addr + page_size - 1) & ~(page_size - 1);
        size_t pages_used = (page_end - page_start) / page_size;
        
        // Page span logging removed
        
        return is_contiguous;
    };

    if (embd) {
        batch->embd = (float *) malloc(sizeof(float) * n_tokens * embd);
        // Embd array allocation logging removed
        check_memory_alignment(batch->embd, "embd array");
        check_memory_contiguity(batch->embd, sizeof(float), n_tokens * embd, "embd array");
    } else {
        batch->token = (llama_token *) malloc(sizeof(llama_token) * n_tokens);
        // Token array allocation logging removed
        check_memory_alignment(batch->token, "token array");
        check_memory_contiguity(batch->token, sizeof(llama_token), n_tokens, "token array");
    }

    batch->pos      = (llama_pos *)     malloc(sizeof(llama_pos)      * n_tokens);
    batch->n_seq_id = (int32_t *)       malloc(sizeof(int32_t)        * n_tokens);
    // 【关键修复】：分配 n_tokens+1 个seq_id指针，因为common_batch_add会检查seq_id[n_tokens]
    batch->seq_id   = (llama_seq_id **) malloc(sizeof(llama_seq_id *) * (n_tokens + 1));
    
    // Arrays allocation logging removed
    
    // 验证主要数组的内存对齐
    check_memory_alignment(batch->pos, "pos array");
    check_memory_alignment(batch->n_seq_id, "n_seq_id array");
    check_memory_alignment(batch->seq_id, "seq_id pointer array");
    
    // 验证主要数组的内存连续性
    check_memory_contiguity(batch->pos, sizeof(llama_pos), n_tokens, "pos array");
    check_memory_contiguity(batch->n_seq_id, sizeof(int32_t), n_tokens, "n_seq_id array");
    check_memory_contiguity(batch->seq_id, sizeof(llama_seq_id *), n_tokens + 1, "seq_id pointer array");
    
    // 为前n_tokens个位置分配seq_id数组
    for (int i = 0; i < n_tokens; ++i) {
        batch->seq_id[i] = (llama_seq_id *) malloc(sizeof(llama_seq_id) * n_seq_max);
        if (i < 5) {
            // Seq_id allocation logging removed
            check_memory_alignment(batch->seq_id[i], "seq_id sub-array");
            check_memory_contiguity(batch->seq_id[i], sizeof(llama_seq_id), n_seq_max, "seq_id sub-array");
        }
    }
    // 【关键修复】：将第n_tokens个位置设为nullptr，作为边界检查标记
    batch->seq_id[n_tokens] = nullptr;
    // Boundary marker logging removed
    
    batch->logits   = (int8_t *)        malloc(sizeof(int8_t)         * n_tokens);
    
    // Logits array allocation logging removed
    check_memory_alignment(batch->logits, "logits array");
    check_memory_contiguity(batch->logits, sizeof(int8_t), n_tokens, "logits array");
    
    // 验证batch结构体本身的对齐
    check_memory_alignment(batch, "batch structure");
    
    // Android模拟器特殊检查：验证内存是否在合理范围内
    uintptr_t batch_addr = (uintptr_t)batch;
    // Android check logging removed
    if (batch->embd) {
        uintptr_t embd_addr = (uintptr_t)batch->embd;
        // Embd array address logging removed
    }
    if (batch->token) {
        uintptr_t token_addr = (uintptr_t)batch->token;
        // Token array address logging removed
    }
    
    // 记录token数量和n_seq_max用于释放
    {
        std::lock_guard<std::mutex> lock(batch_map_mutex);
        batch_token_counts[batch] = n_tokens;
        batch_seq_max_counts[batch] = n_seq_max;
        // Batch recording logging removed
    }
    
    // New batch end logging removed

    return reinterpret_cast<jlong>(batch);
}

extern "C"
JNIEXPORT void JNICALL
Java_com_starlocalrag_llamacpp_LlamaCppInference_free_1batch(JNIEnv *, jobject, jlong batch_pointer) {
    const auto batch = reinterpret_cast<llama_batch *>(batch_pointer);
    
    // Batch freeing logging removed
    
    if (batch) {
        int n_tokens = 0;
        int n_seq_max = 0;
        
        // 获取记录的token数量和seq_max
        {
            std::lock_guard<std::mutex> lock(batch_map_mutex);
            auto it_tokens = batch_token_counts.find(batch);
            auto it_seq_max = batch_seq_max_counts.find(batch);
            
            if (it_tokens != batch_token_counts.end()) {
                n_tokens = it_tokens->second;
                batch_token_counts.erase(it_tokens);
                // Found n_tokens logging removed
            }
            
            if (it_seq_max != batch_seq_max_counts.end()) {
                n_seq_max = it_seq_max->second;
                batch_seq_max_counts.erase(it_seq_max);
                // Found n_seq_max logging removed
            }
        }
        
        // 手动释放内存，按照官方Android示例的方式
        if (batch->token) {
            free(batch->token);
            // Token array freed logging removed
        }
        if (batch->embd) {
            free(batch->embd);
            // Embd array freed logging removed
        }
        if (batch->pos) {
            free(batch->pos);
            // Pos array freed logging removed
        }
        if (batch->n_seq_id) {
            free(batch->n_seq_id);
            // N_seq_id array freed logging removed
        }
        if (batch->seq_id) {
            // 使用官方的释放方式：遍历到nullptr结束标记
            for (int i = 0; batch->seq_id[i] != nullptr; ++i) {
                free(batch->seq_id[i]);
            }
            free(batch->seq_id);
            // Seq_id arrays freed logging removed
        }
        if (batch->logits) {
            free(batch->logits);
            // Logits array freed logging removed
        }
        
        // 释放batch结构体
        delete batch;
        // Batch structure freed logging removed
    }
}

extern "C"
JNIEXPORT jlong JNICALL
Java_com_starlocalrag_llamacpp_LlamaCppInference_new_1sampler(JNIEnv *, jobject) {
    auto sparams = llama_sampler_chain_default_params();
    sparams.no_perf = true;
    llama_sampler * smpl = llama_sampler_chain_init(sparams);
    llama_sampler_chain_add(smpl, llama_sampler_init_greedy());

    return reinterpret_cast<jlong>(smpl);
}

extern "C"
JNIEXPORT void JNICALL
Java_com_starlocalrag_llamacpp_LlamaCppInference_free_1sampler(JNIEnv *, jobject, jlong sampler_pointer) {
    //DEBUG_LOG("LlamaCppJNI", "free_sampler called with pointer=%p", (void*)sampler_pointer);
    
    if (sampler_pointer == 0) {
        //DEBUG_LOG("LlamaCppJNI", "free_sampler: sampler_pointer is 0, skipping");
        return;
    }
    
    auto sampler = reinterpret_cast<llama_sampler *>(sampler_pointer);
    if (!sampler) {
        //DEBUG_LOG("LlamaCppJNI", "free_sampler: sampler is null after cast, skipping");
        return;
    }
    
    try {
        //DEBUG_LOG("LlamaCppJNI", "free_sampler: calling llama_sampler_free");
        llama_sampler_free(sampler);
        //DEBUG_LOG("LlamaCppJNI", "free_sampler: llama_sampler_free completed successfully");
    } catch (const std::exception& e) {
        //ERROR_LOG("LlamaCppJNI", "free_sampler: exception caught: %s", e.what());
    } catch (...) {
        //ERROR_LOG("LlamaCppJNI", "free_sampler: unknown exception caught");
    }
}

extern "C"
JNIEXPORT void JNICALL
Java_com_starlocalrag_llamacpp_LlamaCppInference_backend_1init(JNIEnv *env, jobject) {
    FORCE_LOG(TAG, "[BACKEND] Starting backend initialization...");
    
    // 初始化JNI全局引用用于LogManager调用
    if (!g_jvm) {
        env->GetJavaVM(&g_jvm);
        
        // 获取LogManager类
        jclass local_log_manager_class = env->FindClass("com/example/starlocalrag/LogManager");
        if (local_log_manager_class) {
            g_log_manager_class = (jclass)env->NewGlobalRef(local_log_manager_class);
            env->DeleteLocalRef(local_log_manager_class);
            
            // 获取print方法
            g_log_manager_print_method = env->GetStaticMethodID(g_log_manager_class, "print", "(Ljava/lang/String;)V");
            
            if (g_log_manager_print_method) {
                FORCE_LOG(TAG, "[BACKEND] LogManager.print method initialized successfully");
            } else {
                FORCE_LOG(TAG, "[BACKEND] Failed to find LogManager.print method");
            }
        } else {
            FORCE_LOG(TAG, "[BACKEND] Failed to find LogManager class");
        }
    }
    
    // 设置stdout/stderr重定向到logcat
    setup_stdout_stderr_redirect();
    
    // 设置日志回调
    llama_log_set(log_callback, NULL);
    
    // 初始化后端
    llama_backend_init();

    // Print CPU/KleidiAI capabilities once for diagnostics (build-time macros + runtime features)
    log_cpu_kleidiai_capabilities_once();
    
    // 跳过在此处加载所有后端，避免在 use_gpu=false 时触发 Vulkan 加载
    FORCE_LOG(TAG, "[BACKEND] Skipping ggml_backend_load_all(); will load backends on-demand if GPU is requested");
    
    FORCE_LOG(TAG, "[BACKEND] Backend initialization completed");
    
    // 测试重定向是否工作
    printf("[TEST] This printf should appear in logcat as llama-stdout\n");
    fprintf(stderr, "[TEST] This fprintf to stderr should appear in logcat as llama-stderr\n");
    fflush(stdout);
    fflush(stderr);
}

extern "C"
JNIEXPORT jstring JNICALL
Java_com_starlocalrag_llamacpp_LlamaCppInference_system_1info(JNIEnv *env, jobject) {
    return env->NewStringUTF(llama_print_system_info());
}

// Forward declaration for chat template helper
static std::string apply_chat_template(llama_context* context, const char* user_message);

extern "C"
JNIEXPORT jint JNICALL
Java_com_starlocalrag_llamacpp_LlamaCppInference_completion_1init(
        JNIEnv * env,
        jclass,
        jlong context_pointer,
        jlong batch_pointer,
        jstring jtext,
        jint n_len,
        jboolean format_chat
    ) {

    // 最早的跟踪点 - 确保函数被调用
    printf("[PRINTF_DEBUG] ===== COMPLETION_INIT FUNCTION ENTRY =====\n");
    fflush(stdout);
    LOGi("[TRACE_POINT_2] ===== COMPLETION_INIT FUNCTION ENTRY =====");
    printf("[PRINTF_DEBUG] Function parameters: context_ptr=%p, batch_ptr=%p, n_len=%d\n", 
           (void*)context_pointer, (void*)batch_pointer, n_len);
    fflush(stdout);
    LOGi("[TRACE_POINT_2] Function parameters: context_ptr=%p, batch_ptr=%p, n_len=%d", 
         (void*)context_pointer, (void*)batch_pointer, n_len);
    
    cached_token_chars.clear();

    LOGi("[MEMORY_TRACE] Inference started - checking batch and context state");
    // Completion init start logging removed
    // Input params logging removed

    const auto text = env->GetStringUTFChars(jtext, 0);
    const auto context = reinterpret_cast<llama_context *>(context_pointer);
    const auto batch = reinterpret_cast<llama_batch *>(batch_pointer);
    
    // Converted pointers logging removed

    if (!context) {
        LOGe("[ERROR] completion_init: context is null");
        env->ReleaseStringUTFChars(jtext, text);
        return -1;
    }

    if (!batch) {
        LOGe("[ERROR] completion_init: batch is null");
        env->ReleaseStringUTFChars(jtext, text);
        return -1;
    }
    
    // Basic pointer validation logging removed

    // Prompt content logging removed

    // Apply chat template if format_chat is true
    std::string processed_text;
    if (format_chat == JNI_TRUE) {
        processed_text = apply_chat_template(context, text);
        FORCE_LOG(TAG, "[COMPLETION_INIT] Chat template applied");
    } else {
        processed_text = text;
        FORCE_LOG(TAG, "[COMPLETION_INIT] Using original text without template");
    }

    bool parse_special = (format_chat == JNI_TRUE);
    printf("[PRINTF_DEBUG] About to call common_tokenize with text length: %zu\n", processed_text.length());
    fflush(stdout);
    
    const auto tokens_list = common_tokenize(context, processed_text, true, parse_special);
    
    printf("[PRINTF_DEBUG] common_tokenize completed, got %zu tokens\n", tokens_list.size());
    fflush(stdout);

    auto n_ctx = llama_n_ctx(context);
    auto n_batch = llama_n_batch(context);
    
    // English: Do NOT reserve n_len ahead of time. With context shift, output length is decoupled from n_ctx.
    // Allow the prompt to fit into the current context window directly.
    int max_input_tokens = std::max(1, (int)n_ctx - 1);
    std::vector<llama_token> final_tokens = tokens_list;
    if ((int)final_tokens.size() > max_input_tokens) {
        LOGi("Input tokens(%zu) exceed context window(%d), truncating to fit", 
             final_tokens.size(), max_input_tokens);
        final_tokens.resize(max_input_tokens);
    }
    
    LOGi("n_len = %d, n_ctx = %d, n_batch = %d, input_tokens = %zu, max_input_tokens = %d", 
         n_len, n_ctx, n_batch, final_tokens.size(), max_input_tokens);

    // Check batch capacity to avoid ggml transpose errors
    if (final_tokens.size() > (size_t)n_batch) {
        LOGe("input_tokens(%zu) > n_batch(%d), this may cause ggml_compute_forward_transpose error", 
             final_tokens.size(), n_batch);
        env->ReleaseStringUTFChars(jtext, text);
        return -1;
    }
    
    // 【关键修复】：检查batch的实际容量是否足够


    for (auto id : final_tokens) {
        printf("[PRINTF_DEBUG] Before common_token_to_piece for token %d\n", id);
        fflush(stdout);
        
        std::string token_str;
        try {
            token_str = common_token_to_piece(context, id);
            printf("[PRINTF_DEBUG] After common_token_to_piece, got string: %s\n", token_str.c_str());
            fflush(stdout);
        } catch (const std::exception& e) {
            printf("[PRINTF_DEBUG] Exception in common_token_to_piece: %s\n", e.what());
            fflush(stdout);
            continue;
        } catch (...) {
            printf("[PRINTF_DEBUG] Unknown exception in common_token_to_piece\n");
            fflush(stdout);
            continue;
        }
        
        LOGi("token: `%s`-> %d ", token_str.c_str(), id);
        printf("[PRINTF_DEBUG] Successfully logged token %d\n", id);
        fflush(stdout);
    }

    // 检查 batch 结构体的完整性
    if (!batch->token || !batch->pos || !batch->n_seq_id || !batch->seq_id || !batch->logits) {
        LOGe("[ERROR] batch structure is incomplete: token=%p, pos=%p, n_seq_id=%p, seq_id=%p, logits=%p",
             batch->token, batch->pos, batch->n_seq_id, batch->seq_id, batch->logits);
        env->ReleaseStringUTFChars(jtext, text);
        return -1;
    }

    // 检查 batch 的 seq_id 数组
    for (int i = 0; i < final_tokens.size(); i++) {
        if (!batch->seq_id[i]) {
            LOGe("[ERROR] batch->seq_id[%d] is null", i);
            env->ReleaseStringUTFChars(jtext, text);
            return -1;
        }
    }

    common_batch_clear(*batch);
    // Batch clear logging removed
    
    // 验证batch在clear后的状态
    if (final_tokens.size() > 0) {
        // 检查第一个位置的seq_id指针
        if (!batch->seq_id[0]) {
            LOGe("[ERROR] batch->seq_id[0] is null after clear!");
            env->ReleaseStringUTFChars(jtext, text);
            return -1;
        }
        // Seq_id verification logging removed
    }

    try {
        // evaluate the initial prompt
        for (auto i = 0; i < final_tokens.size(); i++) {
                // Token addition logging removed
            
            // 在调用common_batch_add前验证seq_id指针
            if (!batch->seq_id[batch->n_tokens]) {
                LOGe("[ERROR] batch->seq_id[%d] is null before common_batch_add!", batch->n_tokens);
                env->ReleaseStringUTFChars(jtext, text);
                return -1;
            }
            
            common_batch_add(*batch, final_tokens[i], i, { 0 }, false);
        }
    } catch (const std::exception& e) {
        LOGe("[ERROR] Exception during common_batch_add: %s", e.what());
        env->ReleaseStringUTFChars(jtext, text);
        return -1;
    } catch (...) {
        LOGe("[ERROR] Unknown exception during common_batch_add");
        env->ReleaseStringUTFChars(jtext, text);
        return -1;
    }
    
    // Batch validation logging removed

    // llama_decode will output logits only for the last token of the prompt
    batch->logits[batch->n_tokens - 1] = true;
    
    // 检查 seq_id 指针的有效性
    bool seq_id_valid = true;
    for (int i = 0; i < batch->n_tokens; ++i) {
        if (!batch->seq_id[i]) {
            LOGe("[ERROR] batch->seq_id[%d] is null!", i);
            seq_id_valid = false;
            break;
        }
    }
    
    if (!seq_id_valid) {
        LOGe("[ERROR] Invalid seq_id pointers detected, aborting decode");
        env->ReleaseStringUTFChars(jtext, text);
        return -1;
    }
    
    const auto model = llama_get_model(context);
    
    // 验证前几个token的数据完整性
    for (int i = 0; i < batch->n_tokens && i < 3; ++i) {
        // 验证seq_id指针
        if (!batch->seq_id[i]) {
            LOGe("[ERROR] CRITICAL: batch->seq_id[%d] is NULL before llama_decode!", i);
            env->ReleaseStringUTFChars(jtext, text);
            return -1;
        }
    }
    
    // Parameter validation logging removed
    int decode_result = llama_decode(context, *batch);
    LOGi("[TRACE_POINT_5] llama_decode returned: %d", decode_result);
    // Decode result logging removed
    
    if (decode_result != 0) {
        LOGe("llama_decode() failed with code: %d", decode_result);
        LOGi("[TRACE_POINT_6] llama_decode failed, cleaning up");
        env->ReleaseStringUTFChars(jtext, text);
        return -1;
    }
    
    LOGi("[TRACE_POINT_7] llama_decode completed successfully");
    // Decode success logging removed

    env->ReleaseStringUTFChars(jtext, text);
    
    // Completion init end logging removed

    return batch->n_tokens;
}

extern "C"
JNIEXPORT jstring JNICALL
Java_com_starlocalrag_llamacpp_LlamaCppInference_completion_1loop(
        JNIEnv * env,
        jobject,
        jlong context_pointer,
        jlong batch_pointer,
        jlong sampler_pointer,
        jint n_len,
        jobject intvar_ncur
) {
    // 在函数入口处检查停止标志，提供最快的停止响应
    if (g_should_stop.load()) {
        return nullptr;
    }
    
    // 参数有效性检查
    if (context_pointer == 0) {
        ERROR_LOG("LlamaCppJNI", "context_pointer is null");
        return nullptr;
    }
    if (batch_pointer == 0) {
        ERROR_LOG("LlamaCppJNI", "batch_pointer is null");
        return nullptr;
    }
    if (sampler_pointer == 0) {
        ERROR_LOG("LlamaCppJNI", "sampler_pointer is null");
        return nullptr;
    }
    if (intvar_ncur == nullptr) {
        ERROR_LOG("LlamaCppJNI", "intvar_ncur is null");
        return nullptr;
    }
    
    const auto context = reinterpret_cast<llama_context *>(context_pointer);
    const auto batch   = reinterpret_cast<llama_batch   *>(batch_pointer);
    const auto sampler = reinterpret_cast<llama_sampler *>(sampler_pointer);
    
    // 简化的参数验证日志
    //DEBUG_LOG("LlamaCppJNI", "Parameters validated");
    
    // 内存状态检查（简化）
    //DEBUG_LOG("LlamaCppJNI", "Inference started");
    
    const auto model = llama_get_model(context);
    if (model == nullptr) {
        __android_log_print(ANDROID_LOG_ERROR, "LlamaCppJNI", "[MODEL_ERROR] llama_get_model returned null");
        call_log_manager_print("[llama-error] [MODEL_ERROR] llama_get_model returned null\n");
        return nullptr;
    }
    
    const auto vocab = llama_model_get_vocab(model);
    if (vocab == nullptr) {
        __android_log_print(ANDROID_LOG_ERROR, "LlamaCppJNI", "[VOCAB_ERROR] llama_model_get_vocab returned null");
        call_log_manager_print("[llama-error] [VOCAB_ERROR] llama_model_get_vocab returned null\n");
        return nullptr;
    }

    // JNI 反射方法获取（简化日志）
    if (!la_int_var) {
        la_int_var = env->GetObjectClass(intvar_ncur);
        //DEBUG_LOG("LlamaCppJNI", "Got IntVar class");
    }
    if (!la_int_var_value) {
        la_int_var_value = env->GetFieldID(la_int_var, "value", "I");
        DEBUG_LOG("LlamaCppJNI", "Got value field ID");
    }
    if (!la_int_var_inc) {
        la_int_var_inc = env->GetMethodID(la_int_var, "inc", "()V");
        //DEBUG_LOG("LlamaCppJNI", "Got inc method ID");
    }
    
    // 获取当前位置
    const auto n_cur = env->GetIntField(intvar_ncur, la_int_var_value);
    DEBUG_LOG("LlamaCppJNI", "n_len = %d, n_ctx = %d, n_batch = %d, n_kv_req = %d, tokens_count = %d", 
                       n_len, llama_n_ctx(context), llama_n_batch(context), 
                       llama_kv_self_used_cells(context), batch->n_tokens);
    
    // 采样前的状态检查（仅在调试模式下）
    //DEBUG_LOG("LlamaCppJNI", "About to sample token - current_pos=%d, max_len=%d", n_cur, n_len);
    
    // 增强的 sampler 指针验证
    if (sampler == nullptr) {
        __android_log_print(ANDROID_LOG_ERROR, "LlamaCppJNI", "[SAMPLER_ERROR] sampler pointer is null before sampling");
        call_log_manager_print("[llama-error] [SAMPLER_ERROR] sampler pointer is null before sampling\n");
        return nullptr;
    }
    
    // 采样器调用（简化日志）
    DEBUG_LOG("LlamaCppJNI", "About to call llama_sampler_sample");
    
    // sample the most likely token
    const auto new_token_id = llama_sampler_sample(sampler, context, -1);
    
    // 采样完成（简化）
    //DEBUG_LOG("LlamaCppJNI", "Sampled token_id=%d", new_token_id);
    
    // 获取token信息（简化）
    DEBUG_LOG("LlamaCppJNI", "About to call common_token_to_piece for token_id=%d", new_token_id);
    
    auto new_token_chars = common_token_to_piece(context, new_token_id);
    
    //DEBUG_LOG("LlamaCppJNI", "common_token_to_piece completed, result length=%zu", new_token_chars.length());
    DEBUG_LOG("LlamaCppJNI", "token: `%s`-> %d", new_token_chars.c_str(), new_token_id);
    
    // DEBUG: 检查是否为特殊token - 添加 vocab 指针验证
    bool is_eog_token = false;
    bool is_eos_token = false;
    bool is_bos_token = false;
    
    if (vocab != nullptr) {
        is_eog_token = llama_vocab_is_eog(vocab, new_token_id);
        is_eos_token = (new_token_id == llama_vocab_eos(vocab));
        is_bos_token = (new_token_id == llama_vocab_bos(vocab));
    } else {
        __android_log_print(ANDROID_LOG_ERROR, "LlamaCppJNI", "[VOCAB_ERROR] vocab pointer is null when checking special tokens");
        call_log_manager_print("[llama-error] [VOCAB_ERROR] vocab pointer is null when checking special tokens\n");
    }
    
    DEBUG_LOG("LlamaCppJNI", "特殊token检查: is_eog=%s, is_eos=%s, is_bos=%s", 
                       is_eog_token ? "true" : "false",
                       is_eos_token ? "true" : "false", 
                       is_bos_token ? "true" : "false");
    
    // 打印模型的特殊token信息（简化，仅一次）
     static bool special_tokens_logged = false;
     if (!special_tokens_logged && vocab != nullptr) {
         DEBUG_LOG("LlamaCppJNI", "BOS token id: %d", llama_vocab_bos(vocab));
         DEBUG_LOG("LlamaCppJNI", "EOS token id: %d", llama_vocab_eos(vocab));
         DEBUG_LOG("LlamaCppJNI", "EOT token id: %d", llama_vocab_eot(vocab));
         DEBUG_LOG("LlamaCppJNI", "模型词汇表大小: %d", llama_vocab_n_tokens(vocab));
         special_tokens_logged = true;
     }

    // DEBUG: 检查推理结束条件 - 添加 vocab 指针验证
    bool should_end_eog = false;
    if (vocab != nullptr) {
        should_end_eog = llama_vocab_is_eog(vocab, new_token_id);
    }
    bool should_end_length = (n_cur == n_len);
    
    if (should_end_eog || should_end_length) {
        DEBUG_LOG("LlamaCppJNI", "推理结束 - EOG检测: %s, 长度限制: %s, 当前位置: %d, 最大长度: %d, 结束token_id: %d",
                           should_end_eog ? "true" : "false",
                           should_end_length ? "true" : "false",
                           n_cur, n_len, new_token_id);
        
        // 使用静态标志确保截断提示只打印一次
        static bool truncation_notice_sent = false;
        
        // 如果是因为长度限制结束，且还未发送过截断提示
        if (should_end_length && !should_end_eog && !truncation_notice_sent) {
            DEBUG_LOG("LlamaCppJNI", "达到输出上限，添加截断提示（仅一次）");
            truncation_notice_sent = true;
            return env->NewStringUTF("（已达输出上限，强行截断！）");
        }
        
        // EOG结束或其他情况，或已发送过截断提示，返回空字符串让Java层正确识别结束条件
        return env->NewStringUTF("");
    }

    DEBUG_LOG("LlamaCppJNI", "About to append to cached_token_chars, current length=%zu", cached_token_chars.length());
    
    cached_token_chars += new_token_chars;
    
    DEBUG_LOG("LlamaCppJNI", "String append completed, new length=%zu", cached_token_chars.length());

    jstring new_token = nullptr;
    if (is_valid_utf8(cached_token_chars.c_str())) {
        DEBUG_LOG("LlamaCppJNI", "Valid UTF-8 sequence formed, returning token: %s", cached_token_chars.c_str());
        new_token = env->NewStringUTF(cached_token_chars.c_str());
        DEBUG_LOG("LlamaCppJNI", "NewStringUTF completed successfully");
        // Cached token logging removed
        cached_token_chars.clear();
    } else {
        DEBUG_LOG("LlamaCppJNI", "Invalid UTF-8 sequence, continuing to accumulate characters. Current length: %zu", cached_token_chars.length());
        // 容错处理：当UTF-8验证失败时，不返回空字符串，而是返回nullptr
        // 这样Java层会继续等待下一个token，让字符继续累积直到形成有效的UTF-8序列
        new_token = nullptr;
    }

    // 批处理操作（仅在调试模式下记录）
    DEBUG_LOG("LlamaCppJNI", "Before batch operations - batch_ptr=0x%lx, n_tokens=%d", 
                       (unsigned long)batch, batch->n_tokens);
    
    DEBUG_LOG("LlamaCppJNI", "About to call common_batch_clear");
    common_batch_clear(*batch);
    DEBUG_LOG("LlamaCppJNI", "Batch cleared - n_tokens=%d", batch->n_tokens);
    
    DEBUG_LOG("LlamaCppJNI", "About to call common_batch_add with token_id=%d, pos=%d", new_token_id, n_cur);
    common_batch_add(*batch, new_token_id, n_cur, { 0 }, true);
    DEBUG_LOG("LlamaCppJNI", "Token added to batch - token_id=%d, pos=%d, n_tokens=%d", 
                       new_token_id, n_cur, batch->n_tokens);
    
    // JNI 调用（仅在调试模式下记录）
    DEBUG_LOG("LlamaCppJNI", "About to call inc method on intvar_ncur");
    env->CallVoidMethod(intvar_ncur, la_int_var_inc);
    DEBUG_LOG("LlamaCppJNI", "inc method called successfully");

    // 在llama_decode调用前检查停止标志，提高停止响应性
    if (g_should_stop.load()) {
        return nullptr;
    }
    
    // llama_decode调用（仅在调试模式下记录）
    DEBUG_LOG("LlamaCppJNI", "ABOUT TO CALL llama_decode - context=0x%lx, batch=0x%lx, n_tokens=%d", 
              (unsigned long)context, (unsigned long)batch, batch->n_tokens);
    
    // 内存状态检查
    DEBUG_LOG("LlamaCppJNI", "Pre-decode memory check - kv_used=%d, n_ctx=%d", 
              llama_kv_self_used_cells(context), llama_n_ctx(context));
    
    int decode_result = llama_decode(context, *batch);
    
    // llama_decode结果（仅在调试模式下记录）
    DEBUG_LOG("LlamaCppJNI", "llama_decode COMPLETED - result=%d", decode_result);
    
    if (decode_result != 0) {
        ERROR_LOG("LlamaCppJNI", "llama_decode FAILED with code: %d", decode_result);
        LOGe("llama_decode() failed in completion_loop");
        return nullptr;
    }
    
    DEBUG_LOG("LlamaCppJNI", "llama_decode SUCCEEDED - continuing inference");
    
    // 在llama_decode完成后检查停止标志，确保每生成一个token后都能及时响应停止请求
    if (g_should_stop.load()) {
        return nullptr;
    }

    // ===== Context Shift (KV sliding) =====
    // English: If enabled and current position reaches/exceeds n_ctx, shift KV to preserve n_keep tokens
    if (g_ctx_shift_enabled.load()) {
        const uint32_t n_ctx_now = llama_n_ctx(context);
        // fetch updated current position after inc()
        jint post_ncur = env->GetIntField(intvar_ncur, la_int_var_value);
        if ((uint32_t)post_ncur >= n_ctx_now) {
            llama_memory_t mem = llama_get_memory(context);
            if (llama_memory_can_shift(mem)) {
                const int n_keep = std::max(0, g_ctx_shift_n_keep.load());
                const int delta = std::max(0, post_ncur - n_keep);
                // Remove tail range to reduce pressure and then shift remaining positions
                llama_memory_seq_rm(mem, /*seq_id*/ 0, /*p0*/ n_keep, /*p1*/ -1);
                llama_memory_seq_add(mem, /*seq_id*/ 0, /*p0*/ n_keep, /*p1*/ -1, /*delta*/ -delta);
                // Reset Java-side current position so next token is appended at n_keep
                env->SetIntField(intvar_ncur, la_int_var_value, n_keep);
                TRACE_LOG(TAG, "[CTX_SHIFT] applied after decode: n_ctx=%u, n_keep=%d, pre_ncur=%d, delta=%d, new_ncur=%d",
                          n_ctx_now, n_keep, post_ncur, delta, n_keep);
            } else {
                TRACE_LOG(TAG, "[CTX_SHIFT] memory backend cannot shift; skipping");
            }
        }
    }

    // Completion loop decode success logging removed

    return new_token;
}

extern "C"
JNIEXPORT void JNICALL
Java_com_starlocalrag_llamacpp_LlamaCppInference_kv_1cache_1clear(JNIEnv *, jobject, jlong context) {
    if (context == 0) {
        LOGe("[KV_CACHE_CLEAR] ERROR: context pointer is null (0)");
        return;
    }
    
    llama_context *ctx = reinterpret_cast<llama_context *>(context);
    if (!ctx) {
        LOGe("[KV_CACHE_CLEAR] ERROR: context pointer is null after cast");
        return;
    }
    
    LOGi("[KV_CACHE_CLEAR] Clearing KV cache for context: %p", ctx);
    llama_memory_t mem = llama_get_memory(ctx);
    llama_memory_clear(mem, true);
    LOGi("[KV_CACHE_CLEAR] KV cache cleared successfully");
}

extern "C"
JNIEXPORT void JNICALL
Java_com_starlocalrag_llamacpp_LlamaCppInference_set_1should_1stop(
        JNIEnv *,
        jobject,
        jboolean should_stop
) {
    g_should_stop.store(should_stop);
    LOGi("[JNI] 设置停止标志: %s", should_stop ? "true" : "false");
    // 强制输出日志确保能看到
    __android_log_print(ANDROID_LOG_INFO, "LlamaCppJNI", "[强制日志] 设置停止标志: %s", should_stop ? "true" : "false");
    std::string formatted_message = "[llama-info] [强制日志] 设置停止标志: " + std::string(should_stop ? "true" : "false") + "\n";
    call_log_manager_print(formatted_message.c_str());
}

extern "C"
JNIEXPORT jboolean JNICALL
Java_com_starlocalrag_llamacpp_LlamaCppInference_get_1should_1stop(
        JNIEnv *,
        jobject
) {
    return g_should_stop.load();
}

extern "C"
JNIEXPORT jlong JNICALL
Java_com_starlocalrag_llamacpp_LlamaCppInference_new_1sampler_1with_1params(
        JNIEnv *env,
        jobject,
        jfloat temp,
        jfloat top_p,
        jint top_k
) {
    LOGi("[SAMPLER_CONFIG] ========== CREATING SAMPLER WITH PARAMS ==========");
    LOGi("[SAMPLER_CONFIG] Input parameters: temperature=%.6f, top_p=%.6f, top_k=%d", temp, top_p, top_k);
    LOGi("[SAMPLER_CONFIG] LLAMA_DEFAULT_SEED value: 0x%08X (%u)", LLAMA_DEFAULT_SEED, LLAMA_DEFAULT_SEED);
    
    // 创建采样器参数
    auto sparams = llama_sampler_chain_default_params();
    LOGi("[SAMPLER_CONFIG] Default sampler chain params created");
    
    // 创建采样器链
    llama_sampler * sampler = llama_sampler_chain_init(sparams);
    LOGi("[SAMPLER_CONFIG] Sampler chain initialized: %p", sampler);
    
    // 添加top-k采样器
    if (top_k > 0) {
        auto top_k_sampler = llama_sampler_init_top_k(top_k);
        llama_sampler_chain_add(sampler, top_k_sampler);
        LOGi("[SAMPLER_CONFIG] Added top-k sampler: k=%d, sampler=%p", top_k, top_k_sampler);
    } else {
        LOGi("[SAMPLER_CONFIG] Skipped top-k sampler (k=%d <= 0)", top_k);
    }
    
    // 添加top-p采样器
    if (top_p > 0.0f && top_p < 1.0f) {
        auto top_p_sampler = llama_sampler_init_top_p(top_p, 1);
        llama_sampler_chain_add(sampler, top_p_sampler);
        LOGi("[SAMPLER_CONFIG] Added top-p sampler: p=%.6f, sampler=%p", top_p, top_p_sampler);
    } else {
        LOGi("[SAMPLER_CONFIG] Skipped top-p sampler (p=%.6f not in (0,1))", top_p);
    }
    
    // 添加温度采样器
    if (temp > 0.0f) {
        auto temp_sampler = llama_sampler_init_temp(temp);
        llama_sampler_chain_add(sampler, temp_sampler);
        LOGi("[SAMPLER_CONFIG] Added temperature sampler: temp=%.6f, sampler=%p", temp, temp_sampler);
    } else {
        LOGi("[SAMPLER_CONFIG] Skipped temperature sampler (temp=%.6f <= 0)", temp);
    }
    
    // 添加分布采样器 - 使用LLAMA_DEFAULT_SEED获得随机种子
    auto dist_sampler = llama_sampler_init_dist(LLAMA_DEFAULT_SEED);
    llama_sampler_chain_add(sampler, dist_sampler);
    LOGi("[SAMPLER_CONFIG] Added distribution sampler: seed=0x%08X, sampler=%p", LLAMA_DEFAULT_SEED, dist_sampler);
    
    if (!sampler) {
        LOGe("[SAMPLER_CONFIG] ERROR: new_sampler_with_params() failed - sampler is null");
        return 0;
    }
    
    LOGi("[SAMPLER_CONFIG] Successfully created sampler chain: %p", sampler);
    LOGi("[SAMPLER_CONFIG] Final parameters: temp=%.6f, top_p=%.6f, top_k=%d", temp, top_p, top_k);
    LOGi("[SAMPLER_CONFIG] ======================================================");
    return reinterpret_cast<jlong>(sampler);
}

extern "C"
JNIEXPORT jlong JNICALL
Java_com_starlocalrag_llamacpp_LlamaCppInference_new_1sampler_1with_1full_1params(
        JNIEnv *env,
        jobject,
        jfloat temp,
        jfloat top_p,
        jint top_k,
        jfloat repeat_penalty
) {
    LOGi("[SAMPLER_CONFIG] ========== CREATING SAMPLER WITH FULL PARAMS ===========");
    LOGi("[SAMPLER_CONFIG] Input parameters: temperature=%.6f, top_p=%.6f, top_k=%d, repeat_penalty=%.6f", temp, top_p, top_k, repeat_penalty);
    LOGi("[SAMPLER_CONFIG] LLAMA_DEFAULT_SEED value: 0x%08X (%u)", LLAMA_DEFAULT_SEED, LLAMA_DEFAULT_SEED);
    
    // 创建采样器参数
    auto sparams = llama_sampler_chain_default_params();
    LOGi("[SAMPLER_CONFIG] Default sampler chain params created");
    
    // 创建采样器链
    llama_sampler * sampler = llama_sampler_chain_init(sparams);
    LOGi("[SAMPLER_CONFIG] Sampler chain initialized: %p", sampler);
    
    // 添加repeat penalty采样器
    if (repeat_penalty > 0.0f && repeat_penalty != 1.0f) {
        auto repeat_sampler = llama_sampler_init_penalties(64, repeat_penalty, 0.0f, 0.0f);
        llama_sampler_chain_add(sampler, repeat_sampler);
        LOGi("[SAMPLER_CONFIG] Added repeat penalty sampler: penalty=%.6f, sampler=%p", repeat_penalty, repeat_sampler);
    } else {
        LOGi("[SAMPLER_CONFIG] Skipped repeat penalty sampler (penalty=%.6f)", repeat_penalty);
    }
    
    // 添加top-k采样器
    if (top_k > 0) {
        auto top_k_sampler = llama_sampler_init_top_k(top_k);
        llama_sampler_chain_add(sampler, top_k_sampler);
        LOGi("[SAMPLER_CONFIG] Added top-k sampler: k=%d, sampler=%p", top_k, top_k_sampler);
    } else {
        LOGi("[SAMPLER_CONFIG] Skipped top-k sampler (k=%d <= 0)", top_k);
    }
    
    // 添加top-p采样器
    if (top_p > 0.0f && top_p < 1.0f) {
        auto top_p_sampler = llama_sampler_init_top_p(top_p, 1);
        llama_sampler_chain_add(sampler, top_p_sampler);
        LOGi("[SAMPLER_CONFIG] Added top-p sampler: p=%.6f, sampler=%p", top_p, top_p_sampler);
    } else {
        LOGi("[SAMPLER_CONFIG] Skipped top-p sampler (p=%.6f not in (0,1))", top_p);
    }
    
    // 添加温度采样器
    if (temp > 0.0f) {
        auto temp_sampler = llama_sampler_init_temp(temp);
        llama_sampler_chain_add(sampler, temp_sampler);
        LOGi("[SAMPLER_CONFIG] Added temperature sampler: temp=%.6f, sampler=%p", temp, temp_sampler);
    } else {
        LOGi("[SAMPLER_CONFIG] Skipped temperature sampler (temp=%.6f <= 0)", temp);
    }
    
    // 添加分布采样器 - 使用LLAMA_DEFAULT_SEED获得随机种子
    auto dist_sampler = llama_sampler_init_dist(LLAMA_DEFAULT_SEED);
    llama_sampler_chain_add(sampler, dist_sampler);
    LOGi("[SAMPLER_CONFIG] Added distribution sampler: seed=0x%08X, sampler=%p", LLAMA_DEFAULT_SEED, dist_sampler);
    
    if (!sampler) {
        LOGe("[SAMPLER_CONFIG] ERROR: new_sampler_with_full_params() failed - sampler is null");
        return 0;
    }
    
    LOGi("[SAMPLER_CONFIG] Successfully created sampler chain: %p", sampler);
    LOGi("[SAMPLER_CONFIG] Final parameters: temp=%.6f, top_p=%.6f, top_k=%d, repeat_penalty=%.6f", temp, top_p, top_k, repeat_penalty);
    LOGi("[SAMPLER_CONFIG] ======================================================================");
    return reinterpret_cast<jlong>(sampler);
}

// ========== 模型元数据获取 JNI 实现 ==========

extern "C"
JNIEXPORT jint JNICALL
Java_com_starlocalrag_llamacpp_LlamaCppInference_model_1meta_1count(JNIEnv *env, jobject, jlong model_handle) {
    auto model = reinterpret_cast<llama_model*>(model_handle);
    if (!model) {
        LOGe("[MODEL_META] model_meta_count: Invalid model handle");
        return -1;
    }
    
    int32_t count = llama_model_meta_count(model);
    LOGi("[MODEL_META] model_meta_count: %d", count);
    return count;
}

extern "C"
JNIEXPORT jstring JNICALL
Java_com_starlocalrag_llamacpp_LlamaCppInference_model_1meta_1key_1by_1index(JNIEnv *env, jobject, jlong model_handle, jint index) {
    auto model = reinterpret_cast<llama_model*>(model_handle);
    if (!model) {
        LOGe("[MODEL_META] model_meta_key_by_index: Invalid model handle");
        return nullptr;
    }
    
    char buf[256];
    int32_t result = llama_model_meta_key_by_index(model, index, buf, sizeof(buf));
    if (result < 0) {
        LOGe("[MODEL_META] model_meta_key_by_index: Failed to get key at index %d", index);
        return nullptr;
    }
    
    LOGi("[MODEL_META] model_meta_key_by_index[%d]: %s", index, buf);
    return env->NewStringUTF(buf);
}

extern "C"
JNIEXPORT jstring JNICALL
Java_com_starlocalrag_llamacpp_LlamaCppInference_model_1meta_1val_1str(JNIEnv *env, jobject, jlong model_handle, jstring key) {
    auto model = reinterpret_cast<llama_model*>(model_handle);
    if (!model) {
        LOGe("[MODEL_META] model_meta_val_str: Invalid model handle");
        return nullptr;
    }
    
    const char* key_str = env->GetStringUTFChars(key, nullptr);
    if (!key_str) {
        LOGe("[MODEL_META] model_meta_val_str: Invalid key string");
        return nullptr;
    }
    
    char buf[1024];
    int32_t result = llama_model_meta_val_str(model, key_str, buf, sizeof(buf));
    env->ReleaseStringUTFChars(key, key_str);
    
    if (result < 0) {
        LOGe("[MODEL_META] model_meta_val_str: Failed to get value for key '%s'", key_str);
        return nullptr;
    }
    
    LOGi("[MODEL_META] model_meta_val_str['%s']: %s", key_str, buf);
    return env->NewStringUTF(buf);
}

extern "C"
JNIEXPORT jstring JNICALL
Java_com_starlocalrag_llamacpp_LlamaCppInference_model_1meta_1val_1str_1by_1index(JNIEnv *env, jobject, jlong model_handle, jint index) {
    auto model = reinterpret_cast<llama_model*>(model_handle);
    if (!model) {
        LOGe("[MODEL_META] model_meta_val_str_by_index: Invalid model handle");
        return nullptr;
    }
    
    char buf[1024];
    int32_t result = llama_model_meta_val_str_by_index(model, index, buf, sizeof(buf));
    if (result < 0) {
        LOGe("[MODEL_META] model_meta_val_str_by_index: Failed to get value at index %d", index);
        return nullptr;
    }
    
    LOGi("[MODEL_META] model_meta_val_str_by_index[%d]: %s", index, buf);
    return env->NewStringUTF(buf);
}

extern "C"
JNIEXPORT jlong JNICALL
Java_com_starlocalrag_llamacpp_LlamaCppInference_model_1size(JNIEnv *env, jobject, jlong model_handle) {
    auto model = reinterpret_cast<llama_model*>(model_handle);
    if (!model) {
        LOGe("[MODEL_SIZE] model_size: Invalid model handle");
        return 0;
    }
    
    return static_cast<jlong>(llama_model_size(model));
}

extern "C"
JNIEXPORT jstring JNICALL
Java_com_starlocalrag_llamacpp_LlamaCppInference_get_1vulkan_1version(JNIEnv *env, jobject) {
    vulkan_runtime::VulkanRuntimeInfo info = vulkan_runtime::detect_vulkan_runtime();
    
    if (!info.library_available || !info.instance_creation_works || !info.physical_devices_available) {
        DEBUG_LOG(TAG, "[VULKAN_VERSION] Vulkan not available, returning null");
        return nullptr;
    }
    
    uint32_t major = VK_VERSION_MAJOR(info.detected_api_version);
    uint32_t minor = VK_VERSION_MINOR(info.detected_api_version);
    
    char version_str[16];
    snprintf(version_str, sizeof(version_str), "%u.%u", major, minor);
    
    DEBUG_LOG(TAG, "[VULKAN_VERSION] Returning Vulkan version: %s", version_str);
    return env->NewStringUTF(version_str);
}

// ============================================================================
// Multimodal Support (Image + Text)
// ============================================================================

/**
 * Check if the loaded model supports multimodal (vision) capabilities
 * Returns true if model has vision/image projection architecture
 */
extern "C"
JNIEXPORT jboolean JNICALL
Java_com_starlocalrag_llamacpp_LlamaCppInference_is_1model_1multimodal(JNIEnv *env, jobject, jlong model_handle) {
    auto model = reinterpret_cast<llama_model*>(model_handle);
    if (!model) {
        ERROR_LOG(TAG, "[MULTIMODAL] is_model_multimodal: Invalid model handle");
        return JNI_FALSE;
    }
    
    // Check for common multimodal metadata keys
    char buf[256];
    
    // Check for mmproj architecture (LLaVA, BakLLaVA, etc.)
    int32_t result = llama_model_meta_val_str(model, "mmproj.arch", buf, sizeof(buf));
    if (result >= 0) {
        FORCE_LOG(TAG, "[MULTIMODAL] Model has mmproj.arch: %s", buf);
        return JNI_TRUE;
    }
    
    // Check for CLIP vision model
    result = llama_model_meta_val_str(model, "clip.vision_model", buf, sizeof(buf));
    if (result >= 0) {
        FORCE_LOG(TAG, "[MULTIMODAL] Model has clip.vision_model: %s", buf);
        return JNI_TRUE;
    }
    
    // Check for vision architecture
    result = llama_model_meta_val_str(model, "vision.arch", buf, sizeof(buf));
    if (result >= 0) {
        FORCE_LOG(TAG, "[MULTIMODAL] Model has vision.arch: %s", buf);
        return JNI_TRUE;
    }
    
    // Check for Qwen2-VL and other vision-language models by architecture name
    result = llama_model_meta_val_str(model, "general.architecture", buf, sizeof(buf));
    if (result >= 0) {
        std::string arch(buf);
        // Convert to lowercase for case-insensitive comparison
        std::transform(arch.begin(), arch.end(), arch.begin(), ::tolower);
        
        // Check if architecture name contains vision/multimodal keywords
        if (arch.find("vl") != std::string::npos ||           // qwen2vl, qwenvl, etc.
            arch.find("vision") != std::string::npos ||       // vision models
            arch.find("llava") != std::string::npos ||        // llava variants
            arch.find("clip") != std::string::npos ||         // clip models
            arch.find("multimodal") != std::string::npos ||   // explicit multimodal
            arch.find("gemma3") != std::string::npos ||       // gemma3 (requires mtmd with mmproj)
            arch.find("paligemma") != std::string::npos ||    // paligemma variants
            arch.find("minicpm") != std::string::npos) {      // minicpm-v variants
            FORCE_LOG(TAG, "[MULTIMODAL] Model has vision-capable architecture: %s", buf);
            return JNI_TRUE;
        }
        
        // Note: Some models (e.g., MiniCPM-V-4.5) use generic architectures like "qwen3"
        // but are actually multimodal. GGUF conversion loses original model_type metadata.
        // For these cases, Java layer should check if mmproj file exists as fallback.
    }
    
    FORCE_LOG(TAG, "[MULTIMODAL] Model does not support multimodal capabilities");
    return JNI_FALSE;
}

/**
 * Get the target image size for the multimodal model from mtmd context
 * Returns the image size in pixels, or -1 if not available
 */
extern "C"
JNIEXPORT jint JNICALL
Java_com_starlocalrag_llamacpp_LlamaCppInference_get_1model_1image_1size(JNIEnv *env, jobject, jlong mtmd_handle) {
    auto mtmd_ctx = reinterpret_cast<mtmd_context*>(mtmd_handle);
    if (!mtmd_ctx) {
        ERROR_LOG(TAG, "[MULTIMODAL] get_model_image_size: Invalid mtmd context handle");
        return -1;
    }
    
    // Get image size from clip context (vision encoder)
    if (mtmd_support_vision(mtmd_ctx)) {
        // Access internal clip context to get image size
        // Note: This requires accessing mtmd_context internals
        // We use clip_get_image_size() from the clip API
        struct clip_ctx * ctx_v = nullptr;
        
        // Get clip context pointer from mtmd context
        // Since mtmd_context structure has ctx_v as public member, we can access it
        // by casting mtmd_context to a struct with the same layout
        struct mtmd_context_layout {
            struct clip_ctx * ctx_v;
            // ... other members we don't need
        };
        ctx_v = reinterpret_cast<mtmd_context_layout*>(mtmd_ctx)->ctx_v;
        
        if (ctx_v) {
            int32_t size = clip_get_image_size(ctx_v);
            if (size > 0) {
                FORCE_LOG(TAG, "[MULTIMODAL] Model image size from clip context: %d", size);
                return static_cast<jint>(size);
            }
        }
    }
    
    // Default fallback
    FORCE_LOG(TAG, "[MULTIMODAL] Model image size not found, using default: 336");
    return 336;
}

/**
 * Get the model architecture name
 * Returns architecture string or null if not available
 */
extern "C"
JNIEXPORT jstring JNICALL
Java_com_starlocalrag_llamacpp_LlamaCppInference_get_1model_1architecture(JNIEnv *env, jobject, jlong model_handle) {
    auto model = reinterpret_cast<llama_model*>(model_handle);
    if (!model) {
        ERROR_LOG(TAG, "[MULTIMODAL] get_model_architecture: Invalid model handle");
        return nullptr;
    }
    
    char buf[256];
    int32_t result = llama_model_meta_val_str(model, "general.architecture", buf, sizeof(buf));
    if (result < 0) {
        FORCE_LOG(TAG, "[MULTIMODAL] Model architecture not found in metadata");
        return nullptr;
    }
    
    FORCE_LOG(TAG, "[MULTIMODAL] Model architecture: %s", buf);
    return env->NewStringUTF(buf);
}

// ========== Multimodal (mtmd) Support ==========

/**
 * Initialize mtmd context for multimodal support
 * 
 * @param model_handle The llama model handle
 * @param mmproj_path Path to mmproj file (can be null if embedded in model)
 * @param use_gpu Whether to use GPU for image processing
 * @param n_threads Number of threads for image processing (0 = auto, use 2)
 * @return mtmd context handle, or 0 on failure
 */
extern "C"
JNIEXPORT jlong JNICALL
Java_com_starlocalrag_llamacpp_LlamaCppInference_init_1mtmd_1context(
    JNIEnv *env, jobject, jlong model_handle, jstring mmproj_path, jboolean use_gpu, jint n_threads) {
    
    auto model = reinterpret_cast<llama_model*>(model_handle);
    if (!model) {
        ERROR_LOG(TAG, "[MTMD] init_mtmd_context: Invalid model handle");
        return 0;
    }
    
    // Get mmproj path (can be null for embedded projectors)
    const char* mmproj_cstr = nullptr;
    if (mmproj_path != nullptr) {
        mmproj_cstr = env->GetStringUTFChars(mmproj_path, nullptr);
    }
    
    // Set up mtmd context parameters
    struct mtmd_context_params ctx_params = mtmd_context_params_default();
    ctx_params.use_gpu = use_gpu;
    // Use provided thread count, default to 2 if 0 or negative
    ctx_params.n_threads = (n_threads > 0) ? n_threads : 2;
    ctx_params.verbosity = GGML_LOG_LEVEL_INFO;
    ctx_params.print_timings = false;
    
    FORCE_LOG(TAG, "[MTMD] Image processing threads: %d (requested: %d)", 
              ctx_params.n_threads, n_threads);
    
    FORCE_LOG(TAG, "[MTMD] Initializing mtmd context - use_gpu=%d, mmproj=%s", 
              use_gpu, mmproj_cstr ? mmproj_cstr : "embedded");
    
    // Initialize mtmd context
    // For Qwen2-VL with embedded mmproj, pass nullptr as mmproj_fname
    mtmd_context* ctx = mtmd_init_from_file(mmproj_cstr, model, ctx_params);
    
    if (mmproj_cstr) {
        env->ReleaseStringUTFChars(mmproj_path, mmproj_cstr);
    }
    
    if (!ctx) {
        ERROR_LOG(TAG, "[MTMD] Failed to initialize mtmd context");
        return 0;
    }
    
    FORCE_LOG(TAG, "[MTMD] mtmd context initialized successfully: %p", ctx);
    return reinterpret_cast<jlong>(ctx);
}

/**
 * Free mtmd context
 */
extern "C"
JNIEXPORT void JNICALL
Java_com_starlocalrag_llamacpp_LlamaCppInference_free_1mtmd_1context(
    JNIEnv *env, jobject, jlong mtmd_handle) {
    
    auto ctx = reinterpret_cast<mtmd_context*>(mtmd_handle);
    if (!ctx) {
        ERROR_LOG(TAG, "[MTMD] free_mtmd_context: Invalid mtmd handle");
        return;
    }
    
    FORCE_LOG(TAG, "[MTMD] Freeing mtmd context: %p", ctx);
    mtmd_free(ctx);
}

/**
 * Load and preprocess an image file
 * 
 * @param mtmd_handle The mtmd context handle
 * @param image_path Path to the image file
 * @return bitmap handle, or 0 on failure
 */
extern "C"
JNIEXPORT jlong JNICALL
Java_com_starlocalrag_llamacpp_LlamaCppInference_load_1image_1bitmap(
    JNIEnv *env, jobject, jlong mtmd_handle, jstring image_path) {
    
    auto ctx = reinterpret_cast<mtmd_context*>(mtmd_handle);
    if (!ctx) {
        ERROR_LOG(TAG, "[MTMD] load_image_bitmap: Invalid mtmd handle");
        return 0;
    }
    
    const char* path_cstr = env->GetStringUTFChars(image_path, nullptr);
    if (!path_cstr) {
        ERROR_LOG(TAG, "[MTMD] load_image_bitmap: Failed to get image path");
        return 0;
    }
    
    FORCE_LOG(TAG, "[MTMD] Loading image bitmap from: %s", path_cstr);
    
    // Load bitmap from file using helper function
    mtmd_bitmap* bitmap = mtmd_helper_bitmap_init_from_file(ctx, path_cstr);
    
    env->ReleaseStringUTFChars(image_path, path_cstr);
    
    if (!bitmap) {
        ERROR_LOG(TAG, "[MTMD] Failed to load image bitmap");
        return 0;
    }
    
    FORCE_LOG(TAG, "[MTMD] Image bitmap loaded successfully: %p", bitmap);
    return reinterpret_cast<jlong>(bitmap);
}

/**
 * Free image bitmap
 */
extern "C"
JNIEXPORT void JNICALL
Java_com_starlocalrag_llamacpp_LlamaCppInference_free_1image_1bitmap(
    JNIEnv *env, jobject, jlong bitmap_handle) {
    
    auto bitmap = reinterpret_cast<mtmd_bitmap*>(bitmap_handle);
    if (!bitmap) {
        ERROR_LOG(TAG, "[MTMD] free_image_bitmap: Invalid bitmap handle");
        return;
    }
    
    FORCE_LOG(TAG, "[MTMD] Freeing image bitmap: %p", bitmap);
    mtmd_bitmap_free(bitmap);
}

/**
 * Get the default image marker for the model
 * For Qwen2-VL, this should return the appropriate vision tokens
 */
extern "C"
JNIEXPORT jstring JNICALL
Java_com_starlocalrag_llamacpp_LlamaCppInference_get_1image_1marker(
    JNIEnv *env, jobject, jlong mtmd_handle) {
    
    auto ctx = reinterpret_cast<mtmd_context*>(mtmd_handle);
    if (!ctx) {
        ERROR_LOG(TAG, "[MTMD] get_image_marker: Invalid mtmd handle");
        return env->NewStringUTF("<image>");
    }
    
    const char* marker = mtmd_default_marker();
    FORCE_LOG(TAG, "[MTMD] Image marker: %s", marker);
    
    return env->NewStringUTF(marker);
}

/**
 * Check if mtmd context requires non-causal attention mask
 * This is important for some vision models
 */
extern "C"
JNIEXPORT jboolean JNICALL
Java_com_starlocalrag_llamacpp_LlamaCppInference_mtmd_1use_1non_1causal(
    JNIEnv *env, jobject, jlong mtmd_handle) {
    
    auto ctx = reinterpret_cast<mtmd_context*>(mtmd_handle);
    if (!ctx) {
        ERROR_LOG(TAG, "[MTMD] mtmd_use_non_causal: Invalid mtmd handle");
        return JNI_FALSE;
    }
    
    bool use_non_causal = mtmd_decode_use_non_causal(ctx);
    FORCE_LOG(TAG, "[MTMD] Use non-causal mask: %d", use_non_causal);
    
    return use_non_causal ? JNI_TRUE : JNI_FALSE;
}

/**
 * Test multimodal inference with a simple image + text prompt
 * This is a simplified test function to verify mtmd functionality
 * 
 * @param mtmd_handle The mtmd context handle
 * @param llama_ctx_handle The llama context handle
 * @param image_path Path to the image file
 * @param prompt Text prompt (should contain image marker)
 * @return 0 on success, negative on error
 */
extern "C"
JNIEXPORT jint JNICALL
Java_com_starlocalrag_llamacpp_LlamaCppInference_test_1multimodal_1inference(
    JNIEnv *env, jobject, jlong mtmd_handle, jlong llama_ctx_handle, 
    jstring image_path, jstring prompt) {
    
    auto mtmd_ctx = reinterpret_cast<mtmd_context*>(mtmd_handle);
    auto llama_ctx = reinterpret_cast<llama_context*>(llama_ctx_handle);
    
    if (!mtmd_ctx || !llama_ctx) {
        ERROR_LOG(TAG, "[MTMD] test_multimodal_inference: Invalid handles");
        return -1;
    }
    
    const char* img_path = env->GetStringUTFChars(image_path, nullptr);
    const char* prompt_str = env->GetStringUTFChars(prompt, nullptr);
    
    if (!img_path || !prompt_str) {
        ERROR_LOG(TAG, "[MTMD] test_multimodal_inference: Failed to get strings");
        return -2;
    }
    
    FORCE_LOG(TAG, "[MTMD] Starting multimodal inference - image: %s, prompt length: %d", 
              img_path, (int)strlen(prompt_str));
    
    // Step 1: Load image bitmap
    FORCE_LOG(TAG, "[MTMD] Step 1: Loading image bitmap from file");
    mtmd_bitmap* bitmap = mtmd_helper_bitmap_init_from_file(mtmd_ctx, img_path);
    if (!bitmap) {
        ERROR_LOG(TAG, "[MTMD] Failed to load image from: %s", img_path);
        env->ReleaseStringUTFChars(image_path, img_path);
        env->ReleaseStringUTFChars(prompt, prompt_str);
        return -3;
    }
    FORCE_LOG(TAG, "[MTMD] Image loaded successfully: %dx%d", 
              mtmd_bitmap_get_nx(bitmap), mtmd_bitmap_get_ny(bitmap));
    
    // Step 2: Prepare input text
    FORCE_LOG(TAG, "[MTMD] Step 2: Preparing input text");
    mtmd_input_text text;
    text.text = prompt_str;
    text.add_special = true;
    text.parse_special = true;
    
    // Step 3: Create input chunks
    FORCE_LOG(TAG, "[MTMD] Step 3: Creating input chunks");
    mtmd_input_chunks* chunks = mtmd_input_chunks_init();
    if (!chunks) {
        ERROR_LOG(TAG, "[MTMD] Failed to initialize chunks");
        mtmd_bitmap_free(bitmap);
        env->ReleaseStringUTFChars(image_path, img_path);
        env->ReleaseStringUTFChars(prompt, prompt_str);
        return -4;
    }
    
    // Step 4: Tokenize (text + image)
    FORCE_LOG(TAG, "[MTMD] Step 4: Tokenizing text and image");
    const mtmd_bitmap* bitmaps_array[] = {bitmap};
    int32_t tokenize_result = mtmd_tokenize(mtmd_ctx, chunks, &text, bitmaps_array, 1);
    
    if (tokenize_result != 0) {
        ERROR_LOG(TAG, "[MTMD] Tokenize failed with code: %d", tokenize_result);
        mtmd_input_chunks_free(chunks);
        mtmd_bitmap_free(bitmap);
        env->ReleaseStringUTFChars(image_path, img_path);
        env->ReleaseStringUTFChars(prompt, prompt_str);
        return -5;
    }
    
    size_t n_chunks = mtmd_input_chunks_size(chunks);
    FORCE_LOG(TAG, "[MTMD] Tokenize success, created %zu chunks", n_chunks);
    
    // Step 5: Evaluate chunks (this processes the image + text)
    FORCE_LOG(TAG, "[MTMD] Step 5: Evaluating chunks (processing image and text)");
    llama_pos new_n_past = 0;
    int32_t eval_result = mtmd_helper_eval_chunks(
        mtmd_ctx,
        llama_ctx,
        chunks,
        0,          // n_past (starting position)
        0,          // seq_id
        512,        // n_batch
        true,       // logits_last (get logits for last token)
        &new_n_past
    );
    
    if (eval_result != 0) {
        ERROR_LOG(TAG, "[MTMD] Eval chunks failed with code: %d", eval_result);
        mtmd_input_chunks_free(chunks);
        mtmd_bitmap_free(bitmap);
        env->ReleaseStringUTFChars(image_path, img_path);
        env->ReleaseStringUTFChars(prompt, prompt_str);
        return -6;
    }
    
    FORCE_LOG(TAG, "[MTMD] Eval success! new_n_past: %d", new_n_past);
    FORCE_LOG(TAG, "[MTMD] Multimodal inference completed successfully");
    
    // Cleanup
    mtmd_input_chunks_free(chunks);
    mtmd_bitmap_free(bitmap);
    env->ReleaseStringUTFChars(image_path, img_path);
    env->ReleaseStringUTFChars(prompt, prompt_str);
    
    return 0; // Success
}

// ==================== Multimodal Token Generation JNI Methods ====================

extern "C"
JNIEXPORT jlong JNICALL
Java_com_starlocalrag_llamacpp_LlamaCppInference_mtmd_1create_1input_1chunks(
    JNIEnv* env, jclass clazz) {
    
    mtmd_input_chunks* chunks = mtmd_input_chunks_init();
    if (!chunks) {
        ERROR_LOG(TAG, "[MTMD] Failed to create input chunks");
        return 0;
    }
    
    FORCE_LOG(TAG, "[MTMD] Input chunks created: %p", chunks);
    return reinterpret_cast<jlong>(chunks);
}

extern "C"
JNIEXPORT void JNICALL
Java_com_starlocalrag_llamacpp_LlamaCppInference_mtmd_1free_1input_1chunks(
    JNIEnv* env, jclass clazz, jlong chunks_handle) {
    
    if (chunks_handle == 0) {
        return;
    }
    
    mtmd_input_chunks* chunks = reinterpret_cast<mtmd_input_chunks*>(chunks_handle);
    mtmd_input_chunks_free(chunks);
    FORCE_LOG(TAG, "[MTMD] Input chunks freed: %p", chunks);
}

extern "C"
JNIEXPORT jint JNICALL
Java_com_starlocalrag_llamacpp_LlamaCppInference_mtmd_1tokenize_1with_1images(
    JNIEnv* env, jclass clazz, jlong mtmd_handle, jlong chunks_handle, 
    jstring prompt, jlongArray image_handles) {
    
    if (mtmd_handle == 0 || chunks_handle == 0) {
        ERROR_LOG(TAG, "[MTMD] Invalid handles for tokenization");
        return -1;
    }
    
    mtmd_context* mtmd_ctx = reinterpret_cast<mtmd_context*>(mtmd_handle);
    mtmd_input_chunks* chunks = reinterpret_cast<mtmd_input_chunks*>(chunks_handle);
    
    // Get prompt string
    const char* prompt_str = env->GetStringUTFChars(prompt, nullptr);
    if (!prompt_str) {
        ERROR_LOG(TAG, "[MTMD] Failed to get prompt string");
        return -2;
    }
    
    // Prepare input text
    mtmd_input_text text;
    text.text = prompt_str;
    text.add_special = true;
    text.parse_special = true;
    
    // Get image handles array
    jsize num_images = env->GetArrayLength(image_handles);
    jlong* image_handles_arr = env->GetLongArrayElements(image_handles, nullptr);
    
    FORCE_LOG(TAG, "[MTMD] Tokenizing with %d images, prompt length: %d", 
              num_images, (int)strlen(prompt_str));
    
    // Convert image handles to bitmap pointers
    std::vector<const mtmd_bitmap*> bitmaps;
    for (int i = 0; i < num_images; i++) {
        mtmd_bitmap* bitmap = reinterpret_cast<mtmd_bitmap*>(image_handles_arr[i]);
        if (bitmap) {
            bitmaps.push_back(bitmap);
        }
    }
    
    // Tokenize
    int32_t result = mtmd_tokenize(mtmd_ctx, chunks, &text, bitmaps.data(), bitmaps.size());
    
    // Cleanup
    env->ReleaseLongArrayElements(image_handles, image_handles_arr, JNI_ABORT);
    env->ReleaseStringUTFChars(prompt, prompt_str);
    
    if (result != 0) {
        ERROR_LOG(TAG, "[MTMD] Tokenization failed with code: %d", result);
        return result;
    }
    
    size_t n_chunks = mtmd_input_chunks_size(chunks);
    FORCE_LOG(TAG, "[MTMD] Tokenization successful, created %zu chunks", n_chunks);
    
    return 0;
}

extern "C"
JNIEXPORT jint JNICALL
Java_com_starlocalrag_llamacpp_LlamaCppInference_mtmd_1eval_1chunks(
    JNIEnv* env, jclass clazz, jlong mtmd_handle, jlong llama_handle, 
    jlong chunks_handle, jint n_past, jint seq_id, jint n_batch, 
    jboolean logits_last, jintArray n_past_out) {
    
    if (mtmd_handle == 0 || llama_handle == 0 || chunks_handle == 0) {
        ERROR_LOG(TAG, "[MTMD] Invalid handles for eval chunks");
        return -1;
    }
    
    mtmd_context* mtmd_ctx = reinterpret_cast<mtmd_context*>(mtmd_handle);
    llama_context* llama_ctx = reinterpret_cast<llama_context*>(llama_handle);
    mtmd_input_chunks* chunks = reinterpret_cast<mtmd_input_chunks*>(chunks_handle);
    
    FORCE_LOG(TAG, "[MTMD] Evaluating chunks: n_past=%d, seq_id=%d, n_batch=%d", 
              n_past, seq_id, n_batch);
    
    llama_pos new_n_past = 0;
    int32_t result = mtmd_helper_eval_chunks(
        mtmd_ctx,
        llama_ctx,
        chunks,
        n_past,
        seq_id,
        n_batch,
        logits_last,
        &new_n_past
    );
    
    if (result != 0) {
        ERROR_LOG(TAG, "[MTMD] Eval chunks failed with code: %d", result);
        return result;
    }
    
    // Return new n_past value
    if (n_past_out != nullptr) {
        jint* out_arr = env->GetIntArrayElements(n_past_out, nullptr);
        if (out_arr) {
            out_arr[0] = (jint)new_n_past;
            env->ReleaseIntArrayElements(n_past_out, out_arr, 0);
        }
    }
    
    FORCE_LOG(TAG, "[MTMD] Eval chunks successful, new_n_past=%d", (int)new_n_past);
    
    return 0;
}

// Helper function: Apply chat template to text
static std::string apply_chat_template(llama_context* context, const char* user_message) {
    const llama_model* model = llama_get_model(context);
    if (!model) {
        ERROR_LOG(TAG, "[CHAT_TEMPLATE] Failed to get model");
        return user_message; // Fallback to original text
    }
    
    // Create chat message structure
    llama_chat_message messages[1];
    messages[0].role = "user";
    messages[0].content = user_message;
    
    // First call to get required buffer size
    int32_t required_size = llama_chat_apply_template(
        nullptr,  // Use model's default template
        messages,
        1,        // Number of messages
        true,     // add_ass (add assistant prompt)
        nullptr,
        0
    );
    
    if (required_size <= 0) {
        FORCE_LOG(TAG, "[CHAT_TEMPLATE] Failed to get template size, using original text");
        return user_message;
    }
    
    // Allocate buffer and apply template
    std::vector<char> buffer(required_size + 1);
    int32_t result = llama_chat_apply_template(
        nullptr,
        messages,
        1,
        true,
        buffer.data(),
        buffer.size()
    );
    
    if (result <= 0) {
        ERROR_LOG(TAG, "[CHAT_TEMPLATE] Failed to apply template");
        return user_message;
    }
    
    std::string formatted(buffer.data(), result);
    FORCE_LOG(TAG, "[CHAT_TEMPLATE] Applied template: original_len=%zu, formatted_len=%zu", 
              strlen(user_message), formatted.length());
    
    return formatted;
}

// Helper function: Process multimodal images and add to KV cache
static int process_multimodal_images(JNIEnv* env, jlong mtmd_handle, jlong context_pointer, 
                                   jlongArray image_handles, const char* text) {
    FORCE_LOG(TAG, "[MTMD_INIT] Processing multimodal images");
    
    mtmd_context* mtmd_ctx = reinterpret_cast<mtmd_context*>(mtmd_handle);
    llama_context* llama_ctx = reinterpret_cast<llama_context*>(context_pointer);
    
    if (!mtmd_ctx || !llama_ctx) {
        ERROR_LOG(TAG, "[MTMD_INIT] Invalid context handles");
        return -1;
    }
    
    jsize num_images = env->GetArrayLength(image_handles);
    FORCE_LOG(TAG, "[MTMD_INIT] Number of images: %d", num_images);
    
    // Step 1: Create input chunks
    mtmd_input_chunks* chunks = mtmd_input_chunks_init();
    if (!chunks) {
        ERROR_LOG(TAG, "[MTMD_INIT] Failed to create input chunks");
        return -1;
    }
    
    // Step 2: Build multimodal prompt with ONLY image markers (no text yet)
    // Text will be processed separately by completion_init
    std::string multimodal_prompt;
    const char* marker = mtmd_default_marker();
    
    for (int i = 0; i < num_images; i++) {
        multimodal_prompt += marker;
        // Don't add extra newline - let the model handle spacing
    }
    // DO NOT add text here - it will be processed by completion_init
    
    FORCE_LOG(TAG, "[MTMD_INIT] Using marker: %s, final prompt length: %d", 
              marker, (int)multimodal_prompt.length());
    
    // Step 3: Tokenize with images
    mtmd_input_text text_input;
    text_input.text = multimodal_prompt.c_str();
    text_input.add_special = true;
    text_input.parse_special = true;
    
    jlong* image_handles_arr = env->GetLongArrayElements(image_handles, nullptr);
    std::vector<const mtmd_bitmap*> bitmaps;
    for (int i = 0; i < num_images; i++) {
        mtmd_bitmap* bitmap = reinterpret_cast<mtmd_bitmap*>(image_handles_arr[i]);
        if (bitmap) {
            bitmaps.push_back(bitmap);
        }
    }
    
    int32_t tokenize_result = mtmd_tokenize(mtmd_ctx, chunks, &text_input, bitmaps.data(), bitmaps.size());
    env->ReleaseLongArrayElements(image_handles, image_handles_arr, JNI_ABORT);
    
    if (tokenize_result != 0) {
        ERROR_LOG(TAG, "[MTMD_INIT] Tokenization failed: %d", tokenize_result);
        mtmd_input_chunks_free(chunks);
        return -2;
    }
    
    size_t n_chunks = mtmd_input_chunks_size(chunks);
    FORCE_LOG(TAG, "[MTMD_INIT] Tokenization successful, created %zu chunks", n_chunks);
    
    // Step 4: Eval chunks (process image embeddings into KV cache)
    FORCE_LOG(TAG, "[MTMD_INIT] Step 4: Starting to eval %zu chunks (this may take time for image encoding)...", n_chunks);
    int64_t eval_start_time = ggml_time_ms();
    
    llama_pos new_n_past = 0;
    int32_t eval_result = mtmd_helper_eval_chunks(
        mtmd_ctx, llama_ctx, chunks,
        0,      // n_past (starting position)
        0,      // seq_id
        512,    // n_batch
        false,  // logits_last = false (we don't need logits yet)
        &new_n_past
    );
    
    int64_t eval_duration = ggml_time_ms() - eval_start_time;
    FORCE_LOG(TAG, "[MTMD_INIT] Eval chunks completed in %" PRId64 " ms", eval_duration);
    
    mtmd_input_chunks_free(chunks);
    
    if (eval_result != 0) {
        ERROR_LOG(TAG, "[MTMD_INIT] Eval chunks failed: %d", eval_result);
        return -3;
    }
    
    FORCE_LOG(TAG, "[MTMD_INIT] Images processed successfully, n_past=%d", (int)new_n_past);
    FORCE_LOG(TAG, "[MTMD_INIT] KV cache now contains image embeddings, ready for text tokenization");
    
    return (int)new_n_past;
}

// Main function: completion_init with optional image support
extern "C"
JNIEXPORT jint JNICALL
Java_com_starlocalrag_llamacpp_LlamaCppInference_completion_1init_1with_1images(
    JNIEnv* env, jclass clazz,
    jlong context_pointer,
    jlong batch_pointer,
    jstring jtext,
    jint n_len,
    jboolean format_chat,
    jlong mtmd_handle,
    jlongArray image_handles
) {
    FORCE_LOG(TAG, "[COMPLETION_INIT_WITH_IMAGES] Entry");
    
    const char* text = env->GetStringUTFChars(jtext, 0);
    
    // Step 1: If images provided, process them first
    int image_tokens = 0;
    if (mtmd_handle != 0 && image_handles != nullptr) {
        jsize num_images = env->GetArrayLength(image_handles);
        if (num_images > 0) {
            FORCE_LOG(TAG, "[COMPLETION_INIT_WITH_IMAGES] Processing %d images first", num_images);
            image_tokens = process_multimodal_images(env, mtmd_handle, context_pointer, image_handles, text);
            
            if (image_tokens < 0) {
                ERROR_LOG(TAG, "[COMPLETION_INIT_WITH_IMAGES] Image processing failed: %d", image_tokens);
                env->ReleaseStringUTFChars(jtext, text);
                return image_tokens;
            }
            
            FORCE_LOG(TAG, "[COMPLETION_INIT_WITH_IMAGES] Images processed, %d tokens in KV cache", image_tokens);
        }
    }
    
    // Step 2: Process text tokens manually (starting from image_tokens position)
    FORCE_LOG(TAG, "[COMPLETION_INIT_WITH_IMAGES] Now processing text tokens from position %d", image_tokens);
    
    llama_context* context = reinterpret_cast<llama_context*>(context_pointer);
    llama_batch* batch = reinterpret_cast<llama_batch*>(batch_pointer);
    
    if (!context || !batch) {
        ERROR_LOG(TAG, "[COMPLETION_INIT_WITH_IMAGES] Invalid context or batch pointer");
        env->ReleaseStringUTFChars(jtext, text);
        return -1;
    }
    
    // Apply chat template if format_chat is true
    std::string processed_text;
    if (format_chat == JNI_TRUE) {
        processed_text = apply_chat_template(context, text);
        FORCE_LOG(TAG, "[COMPLETION_INIT_WITH_IMAGES] Chat template applied");
    } else {
        processed_text = text;
        FORCE_LOG(TAG, "[COMPLETION_INIT_WITH_IMAGES] Using original text without template");
    }
    
    // Tokenize text
    bool parse_special = (format_chat == JNI_TRUE);
    FORCE_LOG(TAG, "[COMPLETION_INIT_WITH_IMAGES] Text to tokenize (length=%zu): '%s'", processed_text.length(), processed_text.c_str());
    FORCE_LOG(TAG, "[COMPLETION_INIT_WITH_IMAGES] parse_special=%s", parse_special ? "true" : "false");
    const auto tokens_list = common_tokenize(context, processed_text, true, parse_special);
    FORCE_LOG(TAG, "[COMPLETION_INIT_WITH_IMAGES] Tokenized text: %zu tokens", tokens_list.size());
    
    // Log first few tokens for debugging
    for (size_t i = 0; i < std::min((size_t)5, tokens_list.size()); i++) {
        std::string token_str = common_token_to_piece(context, tokens_list[i]);
        FORCE_LOG(TAG, "[COMPLETION_INIT_WITH_IMAGES] Token[%zu]: %d ('%s')", i, tokens_list[i], token_str.c_str());
    }
    
    env->ReleaseStringUTFChars(jtext, text);
    
    // Check if tokens fit in context
    auto n_ctx = llama_n_ctx(context);
    int max_input_tokens = std::max(1, (int)n_ctx - image_tokens - 1);
    std::vector<llama_token> final_tokens = tokens_list;
    if ((int)final_tokens.size() > max_input_tokens) {
        FORCE_LOG(TAG, "[COMPLETION_INIT_WITH_IMAGES] Input tokens(%zu) exceed available space(%d), truncating",
                  final_tokens.size(), max_input_tokens);
        final_tokens.resize(max_input_tokens);
    }
    
    // Clear batch and add tokens starting from image_tokens position
    common_batch_clear(*batch);
    for (size_t i = 0; i < final_tokens.size(); i++) {
        common_batch_add(*batch, final_tokens[i], 
                        image_tokens + i,  // Position starts after image tokens
                        {0}, false);
    }
    
    // Set logits for last token
    if (batch->n_tokens > 0) {
        batch->logits[batch->n_tokens - 1] = true;
    }
    
    FORCE_LOG(TAG, "[COMPLETION_INIT_WITH_IMAGES] Batch prepared: %d tokens, positions %d-%d",
              batch->n_tokens, image_tokens, image_tokens + batch->n_tokens - 1);
    
    // Decode text tokens
    int decode_result = llama_decode(context, *batch);
    if (decode_result != 0) {
        ERROR_LOG(TAG, "[COMPLETION_INIT_WITH_IMAGES] Text decode failed: %d", decode_result);
        return -1;
    }
    
    int total_tokens = image_tokens + batch->n_tokens;
    FORCE_LOG(TAG, "[COMPLETION_INIT_WITH_IMAGES] Complete: %d image tokens + %d text tokens = %d total",
              image_tokens, batch->n_tokens, total_tokens);
    
    return total_tokens;
}

