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
#include "llama.h"
#include "common.h"
#include "vulkan_version_patch.h"
#include "vulkan_runtime_detector.h"
#include "ggml-backend.h"
 
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
    
    // Simple gate: require Vulkan >= 1.2 and basic runtime availability
    bool version_ok = VULKAN_VERSION_GE(info.detected_api_version, 1, 2, 0);
    bool basic_ok = info.library_available && info.instance_creation_works && info.physical_devices_available;
    bool ok = basic_ok && version_ok;

    FORCE_LOG(TAG, "[VULKAN] Simple version gate: require >= 1.2");
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

// 新增：带GPU层数参数的模型加载方法
extern "C"
JNIEXPORT jlong JNICALL
Java_com_starlocalrag_llamacpp_LlamaCppInference_load_1model_1with_1gpu(JNIEnv *env, jobject, jstring filename, jint gpu_layers) {
    llama_model_params model_params = llama_model_default_params();
    
    // 初始设置GPU层数
    int requested_gpu_layers = std::max(-1, gpu_layers); // -1表示全部使用GPU，0表示仅CPU
    int final_gpu_layers = requested_gpu_layers;
    
    // 如果请求使用GPU（gpu_layers > 0 或 gpu_layers == -1），则检测Vulkan可用性
    if (requested_gpu_layers != 0) {
        FORCE_LOG(TAG, "[GPU] GPU acceleration requested with %d layers", requested_gpu_layers);
        
        // 使用新的Vulkan运行时检测器
        if (is_vulkan_suitable_for_llamacpp()) {
            FORCE_LOG(TAG, "[GPU] Vulkan is suitable for llama.cpp, using GPU acceleration with %d layers", requested_gpu_layers);
            final_gpu_layers = requested_gpu_layers;
        } else {
            FORCE_LOG(TAG, "[GPU] Vulkan is not suitable for llama.cpp, falling back to CPU-only mode");
            final_gpu_layers = 0; // 回退到CPU模式
        }
    } else {
        FORCE_LOG(TAG, "[GPU] CPU-only mode requested");
        final_gpu_layers = 0;
    }
    
    // 仅在需要 GPU 时按需加载 GGML 后端：注意 -1 也需要加载
    if (final_gpu_layers != 0) {
        bool already_loaded = g_ggml_backends_loaded.load();
        if (!already_loaded) {
            FORCE_LOG(TAG, "[BACKEND] Loading GGML backends on-demand for GPU use...");
            ggml_backend_load_all();
            g_ggml_backends_loaded.store(true);
            FORCE_LOG(TAG, "[BACKEND] GGML backends loaded");

            // Enumerate GGML backend devices to confirm whether Vulkan is visible
            size_t dev_count = ggml_backend_dev_count();
            FORCE_LOG(TAG, "[BACKEND] Enumerating ggml backend devices: count=%zu", dev_count);
            bool found_vulkan = false;
            for (size_t i = 0; i < dev_count; ++i) {
                auto dev = ggml_backend_dev_get(i);
                const char * dev_name = ggml_backend_dev_name(dev);
                int dev_type_val = (int) ggml_backend_dev_type(dev);
                FORCE_LOG(TAG, "[BACKEND] Device #%zu: name=%s, type=%d", i, dev_name ? dev_name : "(null)", dev_type_val);
                if (dev_name && (strstr(dev_name, "Vulkan") || strstr(dev_name, "vulkan"))) {
                    found_vulkan = true;
                }
            }
            FORCE_LOG(TAG, "[BACKEND] Vulkan device present: %s", found_vulkan ? "yes" : "no");
        }
    } else {
        FORCE_LOG(TAG, "[BACKEND] CPU-only mode: skip loading GPU backends");
    }

    // Map -1 to a large sentinel (e.g., 999) which llama.cpp commonly treats as "all layers"
    if (final_gpu_layers < 0) {
        model_params.n_gpu_layers = 999;
        FORCE_LOG(TAG, "[GPU] n_gpu_layers requested=-1 -> set to 999 for llama.cpp (all layers)");
    } else {
        model_params.n_gpu_layers = final_gpu_layers;
    }

    auto path_to_model = env->GetStringUTFChars(filename, 0);
    FORCE_LOG(TAG, "Loading model from %s with %d GPU layers (requested: %d, final: %d)", 
             path_to_model, model_params.n_gpu_layers, requested_gpu_layers, final_gpu_layers);

    auto model = llama_model_load_from_file(path_to_model, model_params);
    env->ReleaseStringUTFChars(filename, path_to_model);

    if (!model) {
        LOGe("load_model_with_gpu() failed");
        env->ThrowNew(env->FindClass("java/lang/IllegalStateException"), "load_model_with_gpu() failed");
        return 0;
    }

    // 获取并打印模型层分配信息
    int32_t total_layers = llama_model_n_layer(model);
    FORCE_LOG(TAG, "[MODEL_INFO] Model loaded successfully!");
    FORCE_LOG(TAG, "[MODEL_INFO] Total model layers: %d", total_layers);
    FORCE_LOG(TAG, "[MODEL_INFO] Requested GPU layers: %d", requested_gpu_layers);
    FORCE_LOG(TAG, "[MODEL_INFO] Final GPU layers: %d", final_gpu_layers);
    
    if (final_gpu_layers != 0) {
        int actual_gpu_layers = (final_gpu_layers < 0) ? total_layers : std::min(final_gpu_layers, total_layers);
        int cpu_layers = total_layers - actual_gpu_layers;
        FORCE_LOG(TAG, "[MODEL_INFO] ✓ GPU acceleration enabled");
        FORCE_LOG(TAG, "[MODEL_INFO] ✓ Layers on GPU: %d/%d", actual_gpu_layers, total_layers);
        FORCE_LOG(TAG, "[MODEL_INFO] ✓ Layers on CPU: %d/%d", cpu_layers, total_layers);
        if (final_gpu_layers < 0) {
            FORCE_LOG(TAG, "[MODEL_INFO] ✓ All layers offloaded to GPU (n_gpu_layers = -1)");
        }
    } else {
        FORCE_LOG(TAG, "[MODEL_INFO] ✓ CPU-only mode");
        FORCE_LOG(TAG, "[MODEL_INFO] ✓ All %d layers running on CPU", total_layers);
    }
    
    FORCE_LOG(TAG, "[MODEL_INFO] Model handle: %p", model);

    return reinterpret_cast<jlong>(model);
}

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

    bool parse_special = (format_chat == JNI_TRUE);
    printf("[PRINTF_DEBUG] About to call common_tokenize with text length: %zu\n", strlen(text));
    fflush(stdout);
    
    const auto tokens_list = common_tokenize(context, text, true, parse_special);
    
    printf("[PRINTF_DEBUG] common_tokenize completed, got %zu tokens\n", tokens_list.size());
    fflush(stdout);

    auto n_ctx = llama_n_ctx(context);
    auto n_batch = llama_n_batch(context);
    
    // 计算输入token允许的最大数量：总上下文长度 - 输出token预留
    int max_input_tokens = n_ctx - n_len;
    if (max_input_tokens <= 0) {
        LOGe("error: n_len(%d) >= n_ctx(%d), no space for input tokens", n_len, n_ctx);
        env->ReleaseStringUTFChars(jtext, text);
        return -1;
    }
    
    // 如果输入token数量超过允许的最大值，进行截断
    std::vector<llama_token> final_tokens = tokens_list;
    if (tokens_list.size() > (size_t)max_input_tokens) {
        LOGi("Input tokens(%zu) exceed max_input_tokens(%d), truncating to fit", 
             tokens_list.size(), max_input_tokens);
        final_tokens.resize(max_input_tokens);
    }
    
    auto n_kv_req = final_tokens.size() + n_len;

    LOGi("n_len = %d, n_ctx = %d, n_batch = %d, n_kv_req = %zu, input_tokens = %zu, max_input_tokens = %d", 
         n_len, n_ctx, n_batch, n_kv_req, final_tokens.size(), max_input_tokens);

    // KV cache大小检查 - 现在应该总是满足条件
    if (n_kv_req > n_ctx) {
        LOGe("error: n_kv_req(%zu) > n_ctx(%d), the required KV cache size is not big enough", n_kv_req, n_ctx);
        env->ReleaseStringUTFChars(jtext, text);
        return -1;
    }
    
    // 检查输入token数量是否超过batch大小
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
