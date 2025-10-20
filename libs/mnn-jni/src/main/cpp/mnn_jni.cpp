#include <jni.h>
#include <android/log.h>
#include <string>
#include <memory>
#include <sstream>
#include <fstream>
#include <vector>
#include <map>
#include <mutex>
#include <atomic>
#include <chrono>
#include <unistd.h>  // for chdir, getcwd
#include "llm/llm.hpp"
#include "llm/reranker.hpp"
#include "diffusion/diffusion.hpp"
#include "jsonhpp/json.hpp"
#include "MNN/MNNDefine.h"
#include "MNN/MNNForwardType.h"
#include "MNN/expr/Executor.hpp"
#include "MNN/expr/ExecutorScope.hpp"
#include "core/Backend.hpp"

#define LOG_TAG "MNN_JNI"

// ========== LogManager Integration (from llama_inference.cpp) ==========
// Global JNI references for LogManager
JavaVM* g_jvm = nullptr;
jclass g_logManagerClass = nullptr;
jmethodID g_logIMethod = nullptr;
jmethodID g_logEMethod = nullptr;

// Call LogManager to save logs to file
void call_log_manager(int level, const char* tag, const char* message) {
    if (!g_jvm || !g_logManagerClass) {
        return; // Not initialized yet
    }
    
    JNIEnv* env = nullptr;
    bool detach = false;
    
    int status = g_jvm->GetEnv((void**)&env, JNI_VERSION_1_6);
    if (status == JNI_EDETACHED) {
        if (g_jvm->AttachCurrentThread(&env, nullptr) == JNI_OK) {
            detach = true;
        } else {
            return;
        }
    }
    
    if (env) {
        jstring jTag = env->NewStringUTF(tag);
        jstring jMsg = env->NewStringUTF(message);
        
        if (jTag && jMsg) {
            if (level == ANDROID_LOG_ERROR && g_logEMethod) {
                env->CallStaticVoidMethod(g_logManagerClass, g_logEMethod, jTag, jMsg);
            } else if (g_logIMethod) {
                env->CallStaticVoidMethod(g_logManagerClass, g_logIMethod, jTag, jMsg);
            }
        }
        
        if (jTag) env->DeleteLocalRef(jTag);
        if (jMsg) env->DeleteLocalRef(jMsg);
        
        if (detach) {
            g_jvm->DetachCurrentThread();
        }
    }
}

// Enhanced log macros that write to BOTH logcat AND LogManager
#define LOGI(...) do { \
    __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__); \
    char buffer[2048]; \
    snprintf(buffer, sizeof(buffer), __VA_ARGS__); \
    call_log_manager(ANDROID_LOG_INFO, LOG_TAG, buffer); \
} while(0)

#define LOGD(...) do { \
    __android_log_print(ANDROID_LOG_DEBUG, LOG_TAG, __VA_ARGS__); \
    char buffer[2048]; \
    snprintf(buffer, sizeof(buffer), __VA_ARGS__); \
    call_log_manager(ANDROID_LOG_DEBUG, LOG_TAG, buffer); \
} while(0)

#define LOGW(...) do { \
    __android_log_print(ANDROID_LOG_WARN, LOG_TAG, __VA_ARGS__); \
    char buffer[2048]; \
    snprintf(buffer, sizeof(buffer), __VA_ARGS__); \
    call_log_manager(ANDROID_LOG_WARN, LOG_TAG, buffer); \
} while(0)

#define LOGE(...) do { \
    __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__); \
    char buffer[2048]; \
    snprintf(buffer, sizeof(buffer), __VA_ARGS__); \
    call_log_manager(ANDROID_LOG_ERROR, LOG_TAG, buffer); \
} while(0)

// UI progress log - sends to chat UI with <debug> tags
#define LOG_UI_PROGRESS(...) do { \
    char buffer[2048]; \
    snprintf(buffer, sizeof(buffer), "<debug>" __VA_ARGS__); \
    strcat(buffer, "</debug>"); \
    call_log_manager(ANDROID_LOG_INFO, "DIFFUSION_UI", buffer); \
} while(0)

// ========== MNN Log Redirection to LogManager ==========

// Custom MNN log function that redirects to LogManager
extern "C" void mnn_custom_log(int level, const char* tag, const char* format, ...) {
    char buffer[4096];
    va_list args;
    va_start(args, format);
    vsnprintf(buffer, sizeof(buffer), format, args);
    va_end(args);
    
    // Also print to logcat for immediate debugging
    __android_log_print(level, tag, "%s", buffer);
    
    // Redirect to LogManager if initialized
    if (g_jvm && g_logManagerClass) {
        JNIEnv* env = nullptr;
        bool detach = false;
        
        int status = g_jvm->GetEnv((void**)&env, JNI_VERSION_1_6);
        if (status == JNI_EDETACHED) {
            if (g_jvm->AttachCurrentThread(&env, nullptr) == JNI_OK) {
                detach = true;
            }
        }
        
        if (env) {
            jstring jTag = env->NewStringUTF(tag);
            jstring jMsg = env->NewStringUTF(buffer);
            
            if (level == ANDROID_LOG_ERROR && g_logEMethod) {
                env->CallStaticVoidMethod(g_logManagerClass, g_logEMethod, jTag, jMsg);
            } else if (g_logIMethod) {
                env->CallStaticVoidMethod(g_logManagerClass, g_logIMethod, jTag, jMsg);
            }
            
            env->DeleteLocalRef(jTag);
            env->DeleteLocalRef(jMsg);
            
            if (detach) {
                g_jvm->DetachCurrentThread();
            }
        }
    }
}

// Override MNN macros to use our custom log function
#undef MNN_PRINT
#undef MNN_ERROR
#define MNN_PRINT(format, ...) mnn_custom_log(ANDROID_LOG_INFO, "MNNJNI", format, ##__VA_ARGS__)
#define MNN_ERROR(format, ...) mnn_custom_log(ANDROID_LOG_ERROR, "MNNJNI", format, ##__VA_ARGS__)

using namespace MNN::Transformer;
using json = nlohmann::json;

// ========== Log Redirection Initialization ==========

// Initialize log redirection to LogManager
static void init_log_redirection(JNIEnv* env) {
    if (g_logManagerClass) {
        return; // Already initialized
    }
    
    // Get JavaVM
    env->GetJavaVM(&g_jvm);
    
    // Find LogManager class
    jclass localClass = env->FindClass("com/example/offlineai/LogManager");
    if (localClass) {
        g_logManagerClass = (jclass)env->NewGlobalRef(localClass);
        env->DeleteLocalRef(localClass);
        
        // Get log methods
        g_logIMethod = env->GetStaticMethodID(g_logManagerClass, "logI", "(Ljava/lang/String;Ljava/lang/String;)V");
        g_logEMethod = env->GetStaticMethodID(g_logManagerClass, "logE", "(Ljava/lang/String;Ljava/lang/String;)V");
        
        if (g_logIMethod && g_logEMethod) {
            LOGI("✅ MNN log redirection to LogManager initialized");
            // Test MNN log redirection
            MNN_PRINT("MNN_PRINT test: This should appear in log file\\n");
        } else {
            LOGW("Failed to find LogManager methods");
        }
    } else {
        LOGW("Failed to find LogManager class");
    }
}

// Cleanup log redirection
static void cleanup_log_redirection(JNIEnv* env) {
    if (g_logManagerClass) {
        env->DeleteGlobalRef(g_logManagerClass);
        g_logManagerClass = nullptr;
    }
    g_jvm = nullptr;
}

// ========== Debug Reranker Wrapper ==========

class DebugQwen3Reranker : public Qwen3Reranker {
public:
    DebugQwen3Reranker(const std::string& config_path) : Qwen3Reranker(config_path) {}
    
    std::vector<float> compute_scores(const std::string& query, const std::vector<std::string>& documents) override {
        LOGI("[RERANKER][DEBUG] ========== Starting compute_scores ==========");
        LOGI("[RERANKER][DEBUG] Query: %.100s", query.c_str());
        LOGI("[RERANKER][DEBUG] Document count: %d", (int)documents.size());
        
        // Call parent implementation
        auto scores = Qwen3Reranker::compute_scores(query, documents);
        
        // Log results
        for (size_t i = 0; i < scores.size(); i++) {
            LOGI("[RERANKER][DEBUG] Document[%d] score: %.6f", (int)i, scores[i]);
        }
        
        LOGI("[RERANKER][DEBUG] ========== Finished compute_scores ==========");
        return scores;
    }
};

// ========== Session Wrapper Class ==========

/**
 * MNN LLM Session Wrapper
 * Manages MNN Llm instance lifecycle and provides streaming inference
 */
class MnnLlmSession {
public:
    MnnLlmSession(const std::string& model_dir, const std::string& config_json)
        : model_dir_(model_dir), config_json_(config_json) {
        LOGI("Creating MNN LLM session: %s", model_dir.c_str());
    }
    
    ~MnnLlmSession() {
        LOGI("Destroying MNN LLM session");
        if (llm_) {
            delete llm_;
            llm_ = nullptr;
        }
    }
    
    bool load() {
        try {
            LOGI("=== MNN Session Initialization START ===");
            LOGI("Model directory: %s", model_dir_.c_str());
            
            // CRITICAL: Parse backend from config BEFORE creating ExecutorScope
            // Backend MUST be set in ExecutorScope, not in JSON config!
            MNNForwardType forwardType = MNN_FORWARD_CPU; // Default
            int thread_num = 4; // Default
            
            if (!config_json_.empty()) {
                try {
                    json config = json::parse(config_json_);
                    std::string backend_type = config.value("backend_type", "cpu");
                    thread_num = config.value("thread_num", 4);
                    
                    // Map backend string to MNNForwardType enum
                    if (backend_type == "opencl") {
                        forwardType = MNN_FORWARD_OPENCL;
                    } else if (backend_type == "vulkan") {
                        forwardType = MNN_FORWARD_VULKAN;
                    } else if (backend_type == "npu") {
                        forwardType = MNN_FORWARD_NN; // NNAPI
                    } else {
                        forwardType = MNN_FORWARD_CPU;
                    }
                    
                    LOGI("=== Parsed Backend Config ===");
                    LOGI("Backend string: %s", backend_type.c_str());
                    LOGI("MNNForwardType: %d (CPU=0, OpenCL=3, Vulkan=7, NNAPI=6)", (int)forwardType);
                    LOGI("Thread num: %d", thread_num);
                    LOGI("==============================");
                } catch (const std::exception& e) {
                    LOGW("Failed to parse backend from config, using CPU: %s", e.what());
                }
            }
            
            // CRITICAL: Create MNN Executor with user-selected backend
            // Reference: libs/mnn/apps/Android/MnnLlmChat/app/src/main/cpp/llm_session.cpp
            MNN::BackendConfig backendConfig;
            auto executor = MNN::Express::Executor::newExecutor(forwardType, backendConfig, thread_num);
            MNN::Express::ExecutorScope scope(executor);
            LOGI("✅ MNN Executor and ExecutorScope created with forwardType=%d", (int)forwardType);
            
            // Create LLM instance from model directory
            std::string config_path = model_dir_ + "/config.json";
            LOGI("Creating LLM instance from config: %s", config_path.c_str());
            
            llm_ = Llm::createLLM(config_path);
            
            if (!llm_) {
                LOGE("Failed to create LLM instance");
                return false;
            }
            
            LOGI("LLM instance created successfully");
            
            // Set runtime config BEFORE load() for other parameters (temp, top_p, etc.)
            // Note: Backend is already set via ExecutorScope above
            if (!config_json_.empty()) {
                LOGI("Setting runtime config (temperature, top_p, etc.)...");
                llm_->set_config(config_json_);
                LOGI("Runtime config applied successfully");
            }
            
            // Load model (this will use the runtime config set above)
            LOGI("About to load MNN model from: %s", model_dir_.c_str());
            LOGI("Calling llm_->load()...");
            bool success = llm_->load();
            LOGI("llm_->load() returned: %s", success ? "SUCCESS" : "FAILED");
            
            if (success) {
                LOGI("=== MNN LLM SESSION LOADED SUCCESSFULLY ===");
                LOGI("Backend (from ExecutorScope): forwardType=%d", (int)forwardType);
                LOGI("===========================================");
                
                // Dump final config
                std::string final_config = llm_->dump_config();
                LOGD("Final MNN config: %s", final_config.c_str());
            } else {
                LOGE("Failed to load MNN LLM model");
            }
            
            return success;
            
        } catch (const std::exception& e) {
            LOGE("Exception during MNN LLM load: %s", e.what());
            return false;
        }
    }
    
    void reset() {
        if (llm_) {
            llm_->reset();
            LOGD("Session reset");
        }
    }
    
    // Inference with streaming callback
    bool inference(const std::string& prompt, 
                   std::function<bool(const std::string&)> token_callback,
                   std::function<void(const LlmContext*)> complete_callback) {
        if (!llm_) {
            LOGE("LLM not initialized");
            return false;
        }
        
        try {
            // Create ChatMessages format (same as official MNN app)
            ChatMessages history;
            history.emplace_back("user", prompt);
            
            // Create output stream with custom streambuf
            StreamBuffer stream_buffer(token_callback);
            std::ostream output_stream(&stream_buffer);
            
            // CRITICAL FIX: Follow official MNN implementation
            // Generate token-by-token with stop check between each token
            // Do NOT use max_new_tokens=-1 (unlimited), it makes stop impossible!
            
            // Parse max_new_tokens from config (官方实现)
            int max_new_tokens = 2048; // default
            try {
                json config = json::parse(config_json_);
                if (config.contains("max_new_tokens")) {
                    max_new_tokens = config["max_new_tokens"].get<int>();
                }
            } catch (...) {
                // Use default if parsing fails
            }
            
            bool stop_requested = false;
            bool generate_end = false;
            
            LOGI("[TEXT] About to call llm_->response() with max_new_tokens=%d (from config)", max_new_tokens);
            LOGI("[TEXT] Prompt: %s", prompt.c_str());
            
            // Initial response (generates first token)
            LOGI("[TEXT] Calling llm_->response(history, stream, \"<eop>\", 1)...");
            llm_->response(history, &output_stream, "<eop>", 1);
            int current_size = 1;
            LOGI("[TEXT] llm_->response() returned, current_size=%d", current_size);
            
            // Generate remaining tokens one by one
            // Note: MNN's generate() will return early if EOS token is detected
            // We check stop flags set by StreamBuffer callback
            LOGI("[TEXT] Starting generation loop, max_new_tokens=%d", max_new_tokens);
            while (!stop_requested && !generate_end && current_size < max_new_tokens) {
                LOGD("[TEXT] Calling llm_->generate(1), current_size=%d", current_size);
                llm_->generate(1);
                current_size++;
                LOGD("[TEXT] After generate(), current_size=%d", current_size);
                
                // Check flags set by StreamBuffer
                stop_requested = stream_buffer.isStopRequested();
                generate_end = stream_buffer.isGenerateEnd();
                
                if (stop_requested) {
                    LOGI("[TEXT] Stop requested at token %d", current_size);
                }
                if (generate_end) {
                    LOGI("[TEXT] Generation ended (EOS/<eop>) at token %d", current_size);
                }
            }
            LOGI("[TEXT] Generation loop finished: current_size=%d, stop=%d, end=%d", 
                 current_size, stop_requested, generate_end);
            
            if (stop_requested) {
                LOGI("[TEXT] Inference stopped by user after %d tokens", current_size);
            } else {
                LOGI("[TEXT] Inference completed, generated %d tokens", current_size);
            }
            
            // Get context with statistics
            const LlmContext* context = llm_->getContext();
            if (complete_callback) {
                complete_callback(context);
            }
            
            return true;
            
        } catch (const std::exception& e) {
            LOGE("Exception during inference: %s", e.what());
            return false;
        }
    }
    
    // Multi-modal inference with images
    bool inferenceWithImages(const std::string& prompt,
                            const std::vector<std::string>& image_paths,
                            std::function<bool(const std::string&)> token_callback,
                            std::function<void(const LlmContext*)> complete_callback) {
        if (!llm_) {
            LOGE("LLM not initialized");
            return false;
        }
        
        try {
            // Multimodal inference (with images)
            LOGI("[MULTIMODAL] Processing %zu images", image_paths.size());
            
            // Build multimodal prompt with <img> tags
            std::string multimodal_prompt = "";
            
            // Add all images at the beginning
            for (const auto& image_path : image_paths) {
                multimodal_prompt += "<img>" + image_path + "</img>";
                LOGI("[MULTIMODAL] Embedded image: %s", image_path.c_str());
            }
            
            // Append text prompt
            multimodal_prompt += prompt;
            
            LOGI("[MULTIMODAL] Final prompt length: %zu chars", multimodal_prompt.length());
            LOGD("[MULTIMODAL] Full prompt: %s", multimodal_prompt.c_str());
            
            // CRITICAL FIX: Use ChatMessages (history) instead of MultimodalPrompt
            // This matches the official MnnLlmChat implementation
            // The <img>path</img> tags will be parsed by Omni::tokenizer_encode
            ChatMessages history;
            history.emplace_back("user", multimodal_prompt);
            
            LOGI("[MULTIMODAL] Created ChatMessages with %zu entries", history.size());
            
            // Create output stream
            StreamBuffer stream_buffer(token_callback);
            std::ostream output_stream(&stream_buffer);
            
            LOGI("[MULTIMODAL] Starting MNN response() with ChatMessages...");
            
            // Parse max_new_tokens from config (官方实现)
            int max_new_tokens = 2048; // default
            try {
                json config = json::parse(config_json_);
                if (config.contains("max_new_tokens")) {
                    max_new_tokens = config["max_new_tokens"].get<int>();
                }
            } catch (...) {
                // Use default if parsing fails
            }
            
            bool stop_requested = false;
            bool generate_end = false;
            
            LOGI("[MULTIMODAL] About to call llm_->response() with max_new_tokens=%d (from config)", max_new_tokens);
            LOGI("[MULTIMODAL] User prompt: %s", multimodal_prompt.c_str());
            
            // Try to get token IDs to see vision embedding length
            try {
                MultimodalPrompt test_input;
                test_input.prompt_template = multimodal_prompt;
                auto token_ids = llm_->tokenizer_encode(test_input);
                LOGI("[MULTIMODAL] Tokenized prompt: %zu tokens", token_ids.size());
                // Log first few and last few tokens
                if (token_ids.size() > 0) {
                    std::string token_preview = "";
                    int preview_count = std::min(10, (int)token_ids.size());
                    for (int i = 0; i < preview_count; i++) {
                        token_preview += std::to_string(token_ids[i]) + " ";
                    }
                    LOGI("[MULTIMODAL] First %d tokens: %s", preview_count, token_preview.c_str());
                    if (token_ids.size() > 10) {
                        token_preview = "";
                        for (int i = token_ids.size() - 5; i < token_ids.size(); i++) {
                            token_preview += std::to_string(token_ids[i]) + " ";
                        }
                        LOGI("[MULTIMODAL] Last 5 tokens: %s", token_preview.c_str());
                    }
                }
            } catch (const std::exception& e) {
                LOGW("[MULTIMODAL] Failed to get token IDs: %s", e.what());
            }
            
            // Initial response (generates first token)
            // This properly handles:
            // 1. Prompt template application
            // 2. <img> tag parsing (in Omni::tokenizer_encode)
            // 3. Image loading and vision encoding
            LOGI("[MULTIMODAL] Calling llm_->response(history, stream, \"<eop>\", 1)...");
            llm_->response(history, &output_stream, "<eop>", 1);
            int current_size = 1;
            LOGI("[MULTIMODAL] llm_->response() returned, current_size=%d", current_size);
            
            // Generate remaining tokens one by one
            // Note: MNN's generate() will return early if EOS token is detected
            // We check stop flags set by StreamBuffer callback
            LOGI("[MULTIMODAL] Starting generation loop, max_new_tokens=%d", max_new_tokens);
            while (!stop_requested && !generate_end && current_size < max_new_tokens) {
                LOGD("[MULTIMODAL] Calling llm_->generate(1), current_size=%d", current_size);
                llm_->generate(1);
                current_size++;
                LOGD("[MULTIMODAL] After generate(), current_size=%d", current_size);
                
                // Check flags set by StreamBuffer
                stop_requested = stream_buffer.isStopRequested();
                generate_end = stream_buffer.isGenerateEnd();
                
                if (stop_requested) {
                    LOGI("[MULTIMODAL] Stop requested at token %d", current_size);
                }
                if (generate_end) {
                    LOGI("[MULTIMODAL] Generation ended (EOS/<eop>) at token %d", current_size);
                }
            }
            LOGI("[MULTIMODAL] Generation loop finished: current_size=%d, stop=%d, end=%d", 
                 current_size, stop_requested, generate_end);
            
            if (stop_requested) {
                LOGI("[MULTIMODAL] Inference stopped by user after %d tokens", current_size);
            } else {
                LOGI("[MULTIMODAL] Inference completed, generated %d tokens", current_size);
            }
            
            // Get context
            const LlmContext* context = llm_->getContext();
            if (complete_callback) {
                complete_callback(context);
            }
            
            return true;
            
        } catch (const std::exception& e) {
            LOGE("Exception during multimodal inference: %s", e.what());
            return false;
        }
    }
    
    // Configuration methods
    void updateConfig(const std::string& config_json) {
        if (llm_) {
            llm_->set_config(config_json);
            config_json_ = config_json;
            LOGD("Config updated: %s", config_json.c_str());
        }
    }
    
    std::string getConfig() const {
        if (llm_) {
            return llm_->dump_config();
        }
        return config_json_;
    }
    
    Llm* getLlm() const { return llm_; }
    
private:
    // Custom streambuf for streaming output
    class StreamBuffer : public std::streambuf {
    public:
        StreamBuffer(std::function<bool(const std::string&)> callback)
            : callback_(std::move(callback)), stop_requested_(false), generate_end_(false) {}

        ~StreamBuffer() override {
            flushRemaining();
        }
        
        bool isStopRequested() const {
            return stop_requested_;
        }
        
        bool isGenerateEnd() const {
            return generate_end_;
        }

    protected:
        // Handle bulk writes for better performance and correct UTF-8 processing
        std::streamsize xsputn(const char* s, std::streamsize n) override {
            if (!callback_ || n <= 0) {
                return n;
            }

            utf8Buffer_.append(s, static_cast<size_t>(n));

            // Extract complete UTF-8 characters
            size_t i = 0;
            std::string completeChars;
            completeChars.reserve(utf8Buffer_.size());

            while (i < utf8Buffer_.size()) {
                int length = utf8CharLength(static_cast<unsigned char>(utf8Buffer_[i]));
                if (length == 0 || i + static_cast<size_t>(length) > utf8Buffer_.size()) {
                    break; // incomplete sequence, keep in buffer
                }
                completeChars.append(utf8Buffer_, i, static_cast<size_t>(length));
                i += static_cast<size_t>(length);
            }

            // Keep remaining incomplete bytes
            if (i > 0) {
                utf8Buffer_.erase(0, i);
            }

            if (!completeChars.empty()) {
                // Check for EOS markers
                if (completeChars.find("<eop>") != std::string::npos) {
                    generate_end_ = true;
                    LOGD("[STREAM] Detected <eop> marker, generation ended");
                    // Don't send <eop> to callback
                    return n;
                }
                
                bool should_stop = callback_(completeChars);
                if (should_stop) {
                    stop_requested_ = true; // Set stop flag
                    LOGD("[STREAM] Callback requested stop");
                    return 0; // signal stop
                }
            }

            return n;
        }

        // Fallback for single character writes
        int overflow(int c) override {
            if (c == EOF) {
                return EOF;
            }
            char ch = static_cast<char>(c);
            return static_cast<int>(xsputn(&ch, 1));
        }

    private:
        static int utf8CharLength(unsigned char byte) {
            if ((byte & 0x80) == 0) return 1;
            if ((byte & 0xE0) == 0xC0) return 2;
            if ((byte & 0xF0) == 0xE0) return 3;
            if ((byte & 0xF8) == 0xF0) return 4;
            return 0;
        }

        void flushRemaining() {
            if (!utf8Buffer_.empty() && callback_) {
                callback_(utf8Buffer_);
                utf8Buffer_.clear();
            }
        }

        std::string utf8Buffer_;
        std::function<bool(const std::string&)> callback_;
        bool stop_requested_;
        bool generate_end_;
    };
    
    std::string model_dir_;
    std::string config_json_;
    Llm* llm_ = nullptr;
};

// ========== JNI Helper Functions ==========

// Convert Java string to C++ string
std::string jstring2string(JNIEnv* env, jstring jstr) {
    if (!jstr) return "";
    const char* chars = env->GetStringUTFChars(jstr, nullptr);
    std::string result(chars);
    env->ReleaseStringUTFChars(jstr, chars);
    return result;
}

// Convert C++ string to Java string
jstring string2jstring(JNIEnv* env, const std::string& str) {
    return env->NewStringUTF(str.c_str());
}

// Convert Java string array to C++ vector
std::vector<std::string> jstringArray2vector(JNIEnv* env, jobjectArray array) {
    std::vector<std::string> result;
    if (!array) return result;
    
    jsize length = env->GetArrayLength(array);
    for (jsize i = 0; i < length; i++) {
        jstring jstr = (jstring)env->GetObjectArrayElement(array, i);
        result.push_back(jstring2string(env, jstr));
        env->DeleteLocalRef(jstr);
    }
    return result;
}

// Create Java HashMap from LlmContext statistics
jobject createStatsMap(JNIEnv* env, const LlmContext* context) {
    jclass hashMapClass = env->FindClass("java/util/HashMap");
    jmethodID hashMapInit = env->GetMethodID(hashMapClass, "<init>", "()V");
    jmethodID putMethod = env->GetMethodID(hashMapClass, "put",
        "(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;");
    
    jobject hashMap = env->NewObject(hashMapClass, hashMapInit);
    
    jclass longClass = env->FindClass("java/lang/Long");
    jmethodID longInit = env->GetMethodID(longClass, "<init>", "(J)V");
    
    // Add statistics
    auto putLong = [&](const char* key, int64_t value) {
        jobject keyObj = env->NewStringUTF(key);
        jobject valueObj = env->NewObject(longClass, longInit, (jlong)value);
        env->CallObjectMethod(hashMap, putMethod, keyObj, valueObj);
        env->DeleteLocalRef(keyObj);
        env->DeleteLocalRef(valueObj);
    };
    
    if (context) {
        putLong("prompt_len", context->prompt_len);
        putLong("gen_seq_len", context->gen_seq_len);
        putLong("all_seq_len", context->all_seq_len);
        putLong("load_us", context->load_us);
        putLong("vision_us", context->vision_us);
        putLong("audio_us", context->audio_us);
        putLong("prefill_us", context->prefill_us);
        putLong("decode_us", context->decode_us);
        putLong("sample_us", context->sample_us);
    }
    
    return hashMap;
}

// ========== JNI Method Implementations ==========

extern "C" {

JNIEXPORT jlong JNICALL
Java_com_offlineai_mnn_MnnInference_createSession(
    JNIEnv* env, jclass clazz,
    jstring modelDir, jstring configJson) {
    
    std::string model_dir = jstring2string(env, modelDir);
    std::string config_json = jstring2string(env, configJson);
    
    LOGI("createSession: modelDir=%s", model_dir.c_str());
    
    try {
        auto* session = new MnnLlmSession(model_dir, config_json);
        if (!session->load()) {
            delete session;
            LOGE("Failed to load MNN session");
            return 0;
        }
        
        LOGI("Session created successfully: %p", session);
        return reinterpret_cast<jlong>(session);
        
    } catch (const std::exception& e) {
        LOGE("Exception in createSession: %s", e.what());
        return 0;
    }
}

JNIEXPORT void JNICALL
Java_com_offlineai_mnn_MnnInference_destroySession(
    JNIEnv* env, jclass clazz, jlong sessionHandle) {
    
    auto* session = reinterpret_cast<MnnLlmSession*>(sessionHandle);
    if (session) {
        LOGI("Destroying session: %p", session);
        delete session;
    }
}

JNIEXPORT void JNICALL
Java_com_offlineai_mnn_MnnInference_resetSession(
    JNIEnv* env, jclass clazz, jlong sessionHandle) {
    
    auto* session = reinterpret_cast<MnnLlmSession*>(sessionHandle);
    if (session) {
        session->reset();
    }
}

JNIEXPORT jobject JNICALL
Java_com_offlineai_mnn_MnnInference_inference(
    JNIEnv* env, jclass clazz,
    jlong sessionHandle, jstring prompt, jobject callback) {
    
    auto* session = reinterpret_cast<MnnLlmSession*>(sessionHandle);
    if (!session) {
        LOGE("Invalid session handle");
        return nullptr;
    }
    
    std::string prompt_str = jstring2string(env, prompt);
    
    // Get callback methods
    jclass callbackClass = env->GetObjectClass(callback);
    jmethodID onTokenMethod = env->GetMethodID(callbackClass, "onToken", "(Ljava/lang/String;)Z");
    jmethodID onCompleteMethod = env->GetMethodID(callbackClass, "onComplete", "(Ljava/util/Map;)V");
    jmethodID onErrorMethod = env->GetMethodID(callbackClass, "onError", "(Ljava/lang/String;)V");
    
    jobject stats = nullptr;
    
    try {
        // Token callback
        auto token_callback = [&](const std::string& token) -> bool {
            jstring jtoken = string2jstring(env, token);
            jboolean should_stop = env->CallBooleanMethod(callback, onTokenMethod, jtoken);
            env->DeleteLocalRef(jtoken);
            return should_stop;
        };
        
        // Complete callback
        auto complete_callback = [&](const LlmContext* context) {
            stats = createStatsMap(env, context);
            env->CallVoidMethod(callback, onCompleteMethod, stats);
        };
        
        // Perform inference
        bool success = session->inference(prompt_str, token_callback, complete_callback);
        
        if (!success) {
            jstring error = string2jstring(env, "Inference failed");
            env->CallVoidMethod(callback, onErrorMethod, error);
            env->DeleteLocalRef(error);
        }
        
        return stats;
        
    } catch (const std::exception& e) {
        LOGE("Exception in inference: %s", e.what());
        jstring error = string2jstring(env, e.what());
        env->CallVoidMethod(callback, onErrorMethod, error);
        env->DeleteLocalRef(error);
        return nullptr;
    }
}

JNIEXPORT jobject JNICALL
Java_com_offlineai_mnn_MnnInference_inferenceWithImages(
    JNIEnv* env, jclass clazz,
    jlong sessionHandle, jstring prompt, jobjectArray imagePaths, jobject callback) {
    
    auto* session = reinterpret_cast<MnnLlmSession*>(sessionHandle);
    if (!session) {
        LOGE("Invalid session handle");
        return nullptr;
    }
    
    std::string prompt_str = jstring2string(env, prompt);
    std::vector<std::string> image_paths = jstringArray2vector(env, imagePaths);
    
    // Get callback methods
    jclass callbackClass = env->GetObjectClass(callback);
    jmethodID onTokenMethod = env->GetMethodID(callbackClass, "onToken", "(Ljava/lang/String;)Z");
    jmethodID onCompleteMethod = env->GetMethodID(callbackClass, "onComplete", "(Ljava/util/Map;)V");
    jmethodID onErrorMethod = env->GetMethodID(callbackClass, "onError", "(Ljava/lang/String;)V");
    
    jobject stats = nullptr;
    
    try {
        auto token_callback = [&](const std::string& token) -> bool {
            jstring jtoken = string2jstring(env, token);
            jboolean should_stop = env->CallBooleanMethod(callback, onTokenMethod, jtoken);
            env->DeleteLocalRef(jtoken);
            return should_stop;
        };
        
        auto complete_callback = [&](const LlmContext* context) {
            stats = createStatsMap(env, context);
            env->CallVoidMethod(callback, onCompleteMethod, stats);
        };
        
        bool success = session->inferenceWithImages(prompt_str, image_paths, 
                                                   token_callback, complete_callback);
        
        if (!success) {
            jstring error = string2jstring(env, "Multimodal inference failed");
            env->CallVoidMethod(callback, onErrorMethod, error);
            env->DeleteLocalRef(error);
        }
        
        return stats;
        
    } catch (const std::exception& e) {
        LOGE("Exception in inferenceWithImages: %s", e.what());
        jstring error = string2jstring(env, e.what());
        env->CallVoidMethod(callback, onErrorMethod, error);
        env->DeleteLocalRef(error);
        return nullptr;
    }
}

JNIEXPORT void JNICALL
Java_com_offlineai_mnn_MnnInference_updateConfig(
    JNIEnv* env, jclass clazz,
    jlong sessionHandle, jstring configJson) {
    
    auto* session = reinterpret_cast<MnnLlmSession*>(sessionHandle);
    if (session) {
        std::string config = jstring2string(env, configJson);
        session->updateConfig(config);
    }
}

JNIEXPORT jstring JNICALL
Java_com_offlineai_mnn_MnnInference_getConfig(
    JNIEnv* env, jclass clazz, jlong sessionHandle) {
    
    auto* session = reinterpret_cast<MnnLlmSession*>(sessionHandle);
    if (session) {
        return string2jstring(env, session->getConfig());
    }
    return nullptr;
}

JNIEXPORT jstring JNICALL
Java_com_offlineai_mnn_MnnInference_getMnnVersion(JNIEnv* env, jclass clazz) {
    // MNN version - would need to include MNN version header
    return string2jstring(env, "MNN-LLM-1.0");
}

JNIEXPORT jboolean JNICALL
Java_com_offlineai_mnn_MnnInference_isBackendAvailable(
    JNIEnv* env, jclass clazz, jstring backendName) {
    
    std::string backend = jstring2string(env, backendName);
    
    // Check backend availability
    // This would need MNN backend query API
    if (backend == "cpu") return JNI_TRUE;
    
#ifdef MNN_OPENCL
    if (backend == "opencl") return JNI_TRUE;
#endif
    
#ifdef MNN_VULKAN
    if (backend == "vulkan") return JNI_TRUE;
#endif
    
#ifdef MNN_NNAPI
    if (backend == "nnapi") return JNI_TRUE;
#endif
    
    return JNI_FALSE;
}

// ========== Embedding JNI Implementation ==========

// ========== Embedding Session Wrapper (统一模式) ==========
class MnnEmbeddingSession {
public:
    MnnEmbeddingSession(const std::string& model_dir, const std::string& config_json)
        : model_dir_(model_dir), config_json_(config_json) {
        LOGI("[EMBEDDING] Creating session: %s", model_dir.c_str());
    }
    
    ~MnnEmbeddingSession() {
        LOGI("[EMBEDDING] Destroying session");
        if (embedding_) {
            delete embedding_;
            embedding_ = nullptr;
        }
    }
    
    bool load() {
        try {
            // Create Embedding instance from model directory
            std::string config_path = model_dir_ + "/config.json";
            LOGI("[EMBEDDING] Creating from config: %s", config_path.c_str());
            
            embedding_ = Embedding::createEmbedding(config_path, true);
            
            if (!embedding_) {
                LOGE("[EMBEDDING] Failed to create instance");
                return false;
            }
            
            LOGI("[EMBEDDING] Instance created successfully");
            
            // Merge runtime parameters (memory, power, precision)
            if (!config_json_.empty()) {
                LOGI("[EMBEDDING] Merging runtime config: %s", config_json_.c_str());
                embedding_->set_config(config_json_);
                LOGI("[EMBEDDING] Final config: %s", embedding_->dump_config().c_str());
            }
            
            return true;
            
        } catch (const std::exception& e) {
            LOGE("[EMBEDDING] Exception during load: %s", e.what());
            return false;
        }
    }
    
    Embedding* get() { return embedding_; }
    
private:
    std::string model_dir_;
    std::string config_json_;
    Embedding* embedding_ = nullptr;
};

// Global embedding sessions map
static std::map<jlong, std::unique_ptr<MnnEmbeddingSession>> g_embedding_sessions;
static std::mutex g_embedding_sessions_mutex;
static jlong g_next_embedding_session_handle = 1;

/**
 * Create embedding session (统一接口)
 * Java signature: public static native long createEmbedding(String modelDir, String configJson);
 */
JNIEXPORT jlong JNICALL
Java_com_offlineai_mnn_MnnInference_createEmbeddingWithConfig(
    JNIEnv* env, jclass, jstring modelDir, jstring configJson) {
    
    if (!modelDir) {
        LOGE("[EMBEDDING] Model directory is null");
        return 0;
    }
    
    const char* dir_chars = env->GetStringUTFChars(modelDir, nullptr);
    std::string model_dir(dir_chars);
    env->ReleaseStringUTFChars(modelDir, dir_chars);
    
    std::string config_json;
    if (configJson) {
        const char* json_chars = env->GetStringUTFChars(configJson, nullptr);
        config_json = json_chars;
        env->ReleaseStringUTFChars(configJson, json_chars);
    }
    
    LOGI("[EMBEDDING] Creating session - dir: %s, config: %s", model_dir.c_str(), config_json.c_str());
    
    try {
        auto start_time = std::chrono::high_resolution_clock::now();
        
        auto session = std::make_unique<MnnEmbeddingSession>(model_dir, config_json);
        if (!session->load()) {
            LOGE("[EMBEDDING] Failed to load session");
            return 0;
        }
        
        auto end_time = std::chrono::high_resolution_clock::now();
        auto duration_ms = std::chrono::duration_cast<std::chrono::milliseconds>(end_time - start_time).count();
        
        int dim = session->get()->dim();
        LOGI("[EMBEDDING] ✓ Session created - dimension: %d, load time: %lld ms", dim, (long long)duration_ms);
        
        // Store session
        std::lock_guard<std::mutex> lock(g_embedding_sessions_mutex);
        jlong handle = g_next_embedding_session_handle++;
        g_embedding_sessions[handle] = std::move(session);
        
        return handle;
        
    } catch (const std::exception& e) {
        LOGE("[EMBEDDING] Exception: %s", e.what());
        return 0;
    }
}

/**
 * Compute embedding vector for text
 * Java signature: public static native float[] computeEmbedding(long embeddingHandle, String text);
 */
JNIEXPORT jfloatArray JNICALL
Java_com_offlineai_mnn_MnnInference_computeEmbedding(
    JNIEnv* env, jclass, jlong handle, jstring text) {
    
    if (!text) {
        LOGE("[EMBEDDING] Input text is null");
        return nullptr;
    }
    
    const char* txt = env->GetStringUTFChars(text, nullptr);
    std::string text_str(txt);
    env->ReleaseStringUTFChars(text, txt);
    
    try {
        auto start_time = std::chrono::high_resolution_clock::now();
        
        LOGI("[EMBEDDING] Computing for text length: %zu chars", text_str.length());
        
        // Get embedding from session
        Embedding* embedding = nullptr;
        {
            std::lock_guard<std::mutex> lock(g_embedding_sessions_mutex);
            auto session_it = g_embedding_sessions.find(handle);
            if (session_it == g_embedding_sessions.end()) {
                LOGE("[EMBEDDING] Invalid handle: %lld", (long long)handle);
                return nullptr;
            }
            embedding = session_it->second->get();
        }
        
        // Call MNN embedding API
        auto embed_start = std::chrono::high_resolution_clock::now();
        auto result = embedding->txt_embedding(text_str);
        auto embed_end = std::chrono::high_resolution_clock::now();
        auto embed_ms = std::chrono::duration_cast<std::chrono::milliseconds>(embed_end - embed_start).count();
        
        LOGI("[EMBEDDING] MNN txt_embedding() took %lld ms", (long long)embed_ms);
        
        // Extract float array from VARP
        auto extract_start = std::chrono::high_resolution_clock::now();
        auto info = result->getInfo();
        int size = info->size;
        const float* data = result->readMap<float>();
        
        if (!data) {
            LOGE("[EMBEDDING] Failed to read embedding data");
            return nullptr;
        }
        
        auto extract_end = std::chrono::high_resolution_clock::now();
        auto extract_ms = std::chrono::duration_cast<std::chrono::milliseconds>(extract_end - extract_start).count();
        
        LOGD("[EMBEDDING] Data extraction took %lld ms, vector size: %d", (long long)extract_ms, size);
        
        // Create Java float array
        auto jni_start = std::chrono::high_resolution_clock::now();
        jfloatArray array = env->NewFloatArray(size);
        if (!array) {
            LOGE("[EMBEDDING] Failed to allocate Java float array of size %d", size);
            return nullptr;
        }
        
        env->SetFloatArrayRegion(array, 0, size, data);
        auto jni_end = std::chrono::high_resolution_clock::now();
        auto jni_ms = std::chrono::duration_cast<std::chrono::milliseconds>(jni_end - jni_start).count();
        
        auto total_time = std::chrono::high_resolution_clock::now();
        auto total_ms = std::chrono::duration_cast<std::chrono::milliseconds>(total_time - start_time).count();
        
        LOGI("[EMBEDDING] ✓ Success - Total: %lld ms (MNN: %lld ms, Extract: %lld ms, JNI: %lld ms), Vector size: %d", 
             (long long)total_ms, (long long)embed_ms, (long long)extract_ms, (long long)jni_ms, size);
        
        return array;
        
    } catch (const std::exception& e) {
        LOGE("Exception computing embedding: %s", e.what());
        return nullptr;
    }
}

/**
 * Get embedding dimension
 * Java signature: public static native int getEmbeddingDimension(long embeddingHandle);
 */
JNIEXPORT jint JNICALL
Java_com_offlineai_mnn_MnnInference_getEmbeddingDimension(
    JNIEnv* env, jclass, jlong handle) {
    
    std::lock_guard<std::mutex> lock(g_embedding_sessions_mutex);
    auto session_it = g_embedding_sessions.find(handle);
    if (session_it == g_embedding_sessions.end()) {
        LOGE("[EMBEDDING] Invalid handle: %lld", (long long)handle);
        return 0;
    }
    return session_it->second->get()->dim();
}

/**
 * Release embedding model
 * Java signature: public static native void releaseEmbedding(long embeddingHandle);
 */
JNIEXPORT void JNICALL
Java_com_offlineai_mnn_MnnInference_releaseEmbedding(
    JNIEnv* env, jclass, jlong handle) {
    
    std::lock_guard<std::mutex> lock(g_embedding_sessions_mutex);
    auto session_it = g_embedding_sessions.find(handle);
    if (session_it != g_embedding_sessions.end()) {
        LOGI("[EMBEDDING] Releasing session handle: %lld", (long long)handle);
        g_embedding_sessions.erase(session_it);
    }
}

/**
 * Check if embedding handle is valid
 * Java signature: public static native boolean isEmbeddingValid(long embeddingHandle);
 */
JNIEXPORT jboolean JNICALL
Java_com_offlineai_mnn_MnnInference_isEmbeddingValid(
    JNIEnv* env, jclass, jlong handle) {
    
    std::lock_guard<std::mutex> lock(g_embedding_sessions_mutex);
    return g_embedding_sessions.find(handle) != g_embedding_sessions.end();
}

} // extern "C"

// ========== JNI Lifecycle ==========

// Global flag to track initialization
static std::atomic<bool> g_mnn_initialized(false);

// Trigger MNN initialization by calling a public API
static void trigger_mnn_initialization() {
    // MNNGetExtraRuntimeCreator() will internally call registerBackend()
    // This is a public API that's safe to call
    try {
        // Just getting the creator will trigger backend registration
        MNN::MNNGetExtraRuntimeCreator(MNN_FORWARD_CPU);
        LOGI("MNN backend initialization triggered successfully");
    } catch (const std::exception& e) {
        LOGE("Failed to trigger MNN initialization: %s", e.what());
    } catch (...) {
        LOGE("Failed to trigger MNN initialization: unknown error");
    }
}

JNIEXPORT jint JNI_OnLoad(JavaVM* vm, void* reserved) {
    // CRITICAL: Use Android log directly (before LogManager init)
    __android_log_print(ANDROID_LOG_INFO, "MNN_JNI", "========================================");
    __android_log_print(ANDROID_LOG_INFO, "MNN_JNI", "JNI_OnLoad ENTRY - MNN JNI library loading...");
    __android_log_print(ANDROID_LOG_INFO, "MNN_JNI", "========================================");
    
    JNIEnv* env = nullptr;
    if (vm->GetEnv((void**)&env, JNI_VERSION_1_6) != JNI_OK) {
        __android_log_print(ANDROID_LOG_ERROR, "MNN_JNI", "Failed to get JNIEnv");
        return JNI_ERR;
    }
    
    // Save JavaVM for later use (DO NOT init LogManager here - it may not be ready yet!)
    env->GetJavaVM(&g_jvm);
    
    __android_log_print(ANDROID_LOG_INFO, "MNN_JNI", "JNIEnv obtained, JavaVM saved");
    __android_log_print(ANDROID_LOG_INFO, "MNN_JNI", "NOTE: LogManager init deferred to explicit initMnnLogger() call");
    
    LOGI("MNN JNI library loaded, version: JNI_VERSION_1_6");
    
    // Trigger MNN backend initialization (once per process)
    if (!g_mnn_initialized.exchange(true)) {
        LOGI("First time initialization, triggering MNN backend registration...");
        trigger_mnn_initialization();
    } else {
        LOGI("MNN already initialized in this process");
    }
    
    __android_log_print(ANDROID_LOG_INFO, "MNN_JNI", "========================================");
    __android_log_print(ANDROID_LOG_INFO, "MNN_JNI", "JNI_OnLoad EXIT - MNN JNI library loaded successfully");
    __android_log_print(ANDROID_LOG_INFO, "MNN_JNI", "Call MnnInference.initMnnLogger() from Java to enable file logging");
    __android_log_print(ANDROID_LOG_INFO, "MNN_JNI", "========================================");
    
    return JNI_VERSION_1_6;
}

// ========== Public API: Initialize MNN Logger ==========
// Call this from Application.onCreate() after LogManager is ready
extern "C" JNIEXPORT void JNICALL
Java_com_offlineai_mnn_MnnInference_initMnnLogger(JNIEnv* env, jclass) {
    __android_log_print(ANDROID_LOG_INFO, "MNN_JNI", "initMnnLogger() called from Java");
    
    // Now LogManager should be ready
    init_log_redirection(env);
    
    // Test MNN_PRINT macro
    MNN_PRINT("🔥 MNN_PRINT TEST - If you see this in log file, redirection works!\\n");
    
    __android_log_print(ANDROID_LOG_INFO, "MNN_JNI", "initMnnLogger() completed - MNN logs will now be saved to file");
}

JNIEXPORT void JNI_OnUnload(JavaVM* vm, void* reserved) {
    JNIEnv* env = nullptr;
    if (vm->GetEnv((void**)&env, JNI_VERSION_1_6) == JNI_OK) {
        cleanup_log_redirection(env);
    }
    
    LOGI("MNN JNI library unloaded");
}

// ========== Reranker JNI Implementation ==========

/**
 * Create reranker model - MNN one-stop solution
 * MNN will automatically detect reranker type from config.json
 * Java signature: public static native long createReranker(String configPath);
 */
// ========== Reranker Session Wrapper (统一模式) ==========
class MnnRerankerSession {
public:
    MnnRerankerSession(const std::string& model_dir, const std::string& config_json)
        : model_dir_(model_dir), config_json_(config_json) {
        LOGI("[RERANKER] Creating session: %s", model_dir.c_str());
    }
    
    ~MnnRerankerSession() {
        LOGI("[RERANKER] Destroying session");
        reranker_.reset();
    }
    
    bool load() {
        try {
            // Create config path
            std::string config_path = model_dir_ + "/config.json";
            LOGI("[RERANKER] Creating from config: %s", config_path.c_str());
            
            // Parse config to detect reranker type
            std::ifstream config_file(config_path);
            if (!config_file.is_open()) {
                LOGE("[RERANKER] Failed to open config file");
                return false;
            }
            
            json config;
            try {
                config_file >> config;
            } catch (const std::exception& e) {
                LOGE("[RERANKER] Failed to parse config JSON: %s", e.what());
                return false;
            }
            
            // Detect reranker type
            std::string reranker_type = "qwen3";  // default
            if (config.contains("reranker_type")) {
                reranker_type = config["reranker_type"];
            }
            
            LOGI("[RERANKER] Detected type: %s", reranker_type.c_str());
            
            // Create reranker based on type
            if (reranker_type == "qwen3") {
                auto qwen_reranker = std::make_unique<Qwen3Reranker>(config_path);
                reranker_ = std::move(qwen_reranker);
            } else if (reranker_type == "gte") {
                auto gte_reranker = std::make_unique<GteReranker>(config_path);
                reranker_ = std::move(gte_reranker);
            } else {
                LOGE("[RERANKER] Unknown reranker type: %s", reranker_type.c_str());
                return false;
            }
            
            // Merge runtime parameters via get_llm()
            if (!config_json_.empty() && reranker_->get_llm()) {
                LOGI("[RERANKER] Merging runtime config: %s", config_json_.c_str());
                reranker_->get_llm()->set_config(config_json_);
                LOGI("[RERANKER] Final config: %s", reranker_->get_llm()->dump_config().c_str());
            }
            
            LOGI("[RERANKER] Session created successfully");
            return true;
            
        } catch (const std::exception& e) {
            LOGE("[RERANKER] Exception during load: %s", e.what());
            return false;
        }
    }
    
    RerankerBase* get() { return reranker_.get(); }
    
private:
    std::string model_dir_;
    std::string config_json_;
    std::unique_ptr<RerankerBase> reranker_;
};

// Global reranker sessions map
static std::map<jlong, std::unique_ptr<MnnRerankerSession>> g_reranker_sessions;
static std::mutex g_reranker_sessions_mutex;
static jlong g_next_reranker_session_handle = 1;

/**
 * Create reranker session (统一接口)
 */
extern "C" JNIEXPORT jlong JNICALL
Java_com_offlineai_mnn_MnnInference_createRerankerWithConfig(
    JNIEnv* env, jclass clazz, jstring modelDir, jstring configJson) {
    
    if (!modelDir) {
        LOGE("[RERANKER] Model directory is null");
        return 0;
    }
    
    const char* dir_chars = env->GetStringUTFChars(modelDir, nullptr);
    std::string model_dir(dir_chars);
    env->ReleaseStringUTFChars(modelDir, dir_chars);
    
    std::string config_json;
    if (configJson) {
        const char* json_chars = env->GetStringUTFChars(configJson, nullptr);
        config_json = json_chars;
        env->ReleaseStringUTFChars(configJson, json_chars);
    }
    
    LOGI("[RERANKER] Creating session - dir: %s, config: %s", model_dir.c_str(), config_json.c_str());
    
    try {
        auto start_time = std::chrono::high_resolution_clock::now();
        
        auto session = std::make_unique<MnnRerankerSession>(model_dir, config_json);
        if (!session->load()) {
            LOGE("[RERANKER] Failed to load session");
            return 0;
        }
        
        auto end_time = std::chrono::high_resolution_clock::now();
        auto duration_ms = std::chrono::duration_cast<std::chrono::milliseconds>(end_time - start_time).count();
        
        LOGI("[RERANKER] ✓ Session created - load time: %lld ms", (long long)duration_ms);
        
        // Store session
        std::lock_guard<std::mutex> lock(g_reranker_sessions_mutex);
        jlong handle = g_next_reranker_session_handle++;
        g_reranker_sessions[handle] = std::move(session);
        
        return handle;
        
    } catch (const std::exception& e) {
        LOGE("[RERANKER] Exception: %s", e.what());
        return 0;
    }
}

/**
 * Set instruction for reranker (Qwen3 only)
 */
extern "C" JNIEXPORT void JNICALL
Java_com_offlineai_mnn_MnnInference_setRerankerInstruction(
    JNIEnv* env, jclass clazz, jlong handle, jstring instruction) {
    
    // Get session from map
    std::lock_guard<std::mutex> lock(g_reranker_sessions_mutex);
    auto it = g_reranker_sessions.find(handle);
    if (it == g_reranker_sessions.end()) {
        LOGE("Invalid reranker handle: %lld", (long long)handle);
        return;
    }
    
    auto* reranker = it->second->get();
    if (!reranker) {
        LOGE("Reranker session is null");
        return;
    }
    
    const char* instr_cstr = env->GetStringUTFChars(instruction, nullptr);
    std::string instr_str(instr_cstr);
    env->ReleaseStringUTFChars(instruction, instr_cstr);
    
    try {
        reranker->setInstruct(instr_str);
        LOGD("Reranker instruction set: %s", instr_str.c_str());
    } catch (const std::exception& e) {
        LOGE("Failed to set instruction: %s", e.what());
    }
}

extern "C" JNIEXPORT jfloatArray JNICALL
Java_com_offlineai_mnn_MnnInference_computeScores(
    JNIEnv* env, jclass clazz, jlong handle, jstring query, jobjectArray documents) {
    
    // Get session from map
    std::lock_guard<std::mutex> lock(g_reranker_sessions_mutex);
    auto it = g_reranker_sessions.find(handle);
    if (it == g_reranker_sessions.end()) {
        LOGE("Invalid reranker handle: %lld", (long long)handle);
        return nullptr;
    }
    
    auto* reranker = it->second->get();
    if (!reranker) {
        LOGE("Reranker session is null");
        return nullptr;
    }
    
    try {
        // Convert query
        const char* query_cstr = env->GetStringUTFChars(query, nullptr);
        std::string query_str(query_cstr);
        env->ReleaseStringUTFChars(query, query_cstr);
        
        // Convert documents array
        jsize doc_count = env->GetArrayLength(documents);
        std::vector<std::string> doc_vec;
        doc_vec.reserve(doc_count);
        
        for (jsize i = 0; i < doc_count; i++) {
            jstring doc = (jstring)env->GetObjectArrayElement(documents, i);
            if (doc != nullptr) {
                const char* doc_cstr = env->GetStringUTFChars(doc, nullptr);
                doc_vec.push_back(std::string(doc_cstr));
                env->ReleaseStringUTFChars(doc, doc_cstr);
                env->DeleteLocalRef(doc);
            }
        }
        
        LOGD("Computing scores for query with %d documents", (int)doc_vec.size());
        LOGI("[RERANKER] Query length: %d chars, first 50 chars: %.50s", 
             (int)query_str.length(), query_str.c_str());
        
        for (size_t i = 0; i < doc_vec.size(); i++) {
            LOGD("[RERANKER] Document %d length: %d chars", (int)i, (int)doc_vec[i].length());
        }
        
        LOGI("[RERANKER] Calling reranker->compute_scores()...");
        LOGI("[RERANKER] This may take a long time on emulator (several minutes)...");
        
        // Debug: Get token IDs for "yes" and "no" (Qwen3Reranker only)
        auto* qwen3_reranker = dynamic_cast<Qwen3Reranker*>(reranker);
        if (qwen3_reranker) {
            auto llm = qwen3_reranker->get_llm();
            if (llm) {
                auto yes_tokens = llm->tokenizer_encode("yes");
                auto no_tokens = llm->tokenizer_encode("no");
                LOGI("[RERANKER][DEBUG] ========== Token ID Diagnostics ==========");
                LOGI("[RERANKER][DEBUG] Architecture: %s", 
#ifdef __aarch64__
                     "ARM64"
#elif defined(__arm__)
                     "ARM32"
#elif defined(__x86_64__) || defined(_M_X64)
                     "x86_64"
#elif defined(__i386__) || defined(_M_IX86)
                     "x86"
#else
                     "Unknown"
#endif
                );
                LOGI("[RERANKER][DEBUG] Token 'yes': count=%d, first_id=%d", 
                     (int)yes_tokens.size(), yes_tokens.empty() ? -1 : yes_tokens[0]);
                LOGI("[RERANKER][DEBUG] Token 'no': count=%d, first_id=%d", 
                     (int)no_tokens.size(), no_tokens.empty() ? -1 : no_tokens[0]);
                
                // Also try Chinese tokens
                auto shi_tokens = llm->tokenizer_encode("是");
                auto fou_tokens = llm->tokenizer_encode("否");
                LOGI("[RERANKER][DEBUG] Token '是': count=%d, first_id=%d", 
                     (int)shi_tokens.size(), shi_tokens.empty() ? -1 : shi_tokens[0]);
                LOGI("[RERANKER][DEBUG] Token '否': count=%d, first_id=%d", 
                     (int)fou_tokens.size(), fou_tokens.empty() ? -1 : fou_tokens[0]);
                
                // Test tokenization of a sample prompt to verify tokenizer consistency
                std::string test_prompt = "<|im_start|>assistant\n<think>\n\n</think>\n\nyes";
                auto test_tokens = llm->tokenizer_encode(test_prompt);
                LOGI("[RERANKER][DEBUG] Test prompt token count: %d", (int)test_tokens.size());
                if (!test_tokens.empty()) {
                    LOGI("[RERANKER][DEBUG] Last token ID (should be 'yes'): %d", test_tokens.back());
                }
                LOGI("[RERANKER][DEBUG] ========================================");
            }
        }
        
        auto start_time = std::chrono::high_resolution_clock::now();
        
        // Compute scores (MNN reranker outputs softmax-normalized scores [0.0, 1.0])
        // Note: This is a blocking call that may take 3-5 minutes on emulator
        std::vector<float> scores = reranker->compute_scores(query_str, doc_vec);
        
        LOGI("[RERANKER] compute_scores() returned, processing results...");
        
        auto end_time = std::chrono::high_resolution_clock::now();
        auto duration_ms = std::chrono::duration_cast<std::chrono::milliseconds>(end_time - start_time).count();
        
        LOGI("[RERANKER] ✓ compute_scores() completed in %lld ms, got %d scores", 
             (long long)duration_ms, (int)scores.size());
        
        // Log score values and statistics
        float min_score = scores[0], max_score = scores[0], sum_score = 0.0f;
        for (size_t i = 0; i < scores.size(); i++) {
            LOGD("[RERANKER] Score[%d] = %.6f", (int)i, scores[i]);
            min_score = std::min(min_score, scores[i]);
            max_score = std::max(max_score, scores[i]);
            sum_score += scores[i];
        }
        float avg_score = sum_score / scores.size();
        LOGI("[RERANKER] Score statistics: min=%.6f, max=%.6f, avg=%.6f", 
             min_score, max_score, avg_score);
        
        // Critical analysis: If all scores are very low (< 0.01), something is wrong
        if (max_score < 0.01f) {
            LOGW("[RERANKER][CRITICAL] ========== LOW SCORE DIAGNOSTICS ==========");
            LOGW("[RERANKER][CRITICAL] All scores are extremely low (max=%.6f)!", max_score);
            LOGW("[RERANKER][CRITICAL] Possible causes:");
            LOGW("[RERANKER][CRITICAL]   1. Token IDs for 'yes'/'no' are incorrect on this architecture");
            LOGW("[RERANKER][CRITICAL]   2. Model output logits are wrong (numerical precision issue)");
            LOGW("[RERANKER][CRITICAL]   3. Prompt format doesn't match model training");
            LOGW("[RERANKER][CRITICAL]   4. Model file is corrupted or incompatible with this architecture");
            LOGW("[RERANKER][CRITICAL]   5. x86 emulator may have different floating point behavior");
            LOGW("[RERANKER][CRITICAL] ");
            LOGW("[RERANKER][CRITICAL] Recommendation:");
            LOGW("[RERANKER][CRITICAL]   - Compare token IDs between x86 and ARM logs");
            LOGW("[RERANKER][CRITICAL]   - Check if model files are architecture-specific");
            LOGW("[RERANKER][CRITICAL]   - Test on real ARM device to confirm");
            LOGW("[RERANKER][CRITICAL] ================================================");
        }
        
        // Convert to Java float array
        jfloatArray result = env->NewFloatArray(scores.size());
        if (result == nullptr) {
            LOGE("Failed to allocate float array");
            return nullptr;
        }
        
        env->SetFloatArrayRegion(result, 0, scores.size(), scores.data());
        
        return result;
        
    } catch (const std::exception& e) {
        LOGE("Failed to compute scores: %s", e.what());
        return nullptr;
    } catch (...) {
        LOGE("Failed to compute scores: unknown error");
        return nullptr;
    }
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_offlineai_mnn_MnnInference_isRerankerValid(
    JNIEnv* env, jclass clazz, jlong handle) {
    return handle != 0;
}

extern "C" JNIEXPORT void JNICALL
Java_com_offlineai_mnn_MnnInference_releaseReranker(
    JNIEnv* env, jclass clazz, jlong handle) {
    
    std::lock_guard<std::mutex> lock(g_reranker_sessions_mutex);
    auto it = g_reranker_sessions.find(handle);
    if (it != g_reranker_sessions.end()) {
        g_reranker_sessions.erase(it);
        LOGI("Reranker session released: %lld", (long long)handle);
    } else {
        LOGW("Reranker handle not found: %lld", (long long)handle);
    }
}

// ========== Diffusion (Text2Image) Support ==========

using namespace MNN::DIFFUSION;

// Global diffusion sessions map
static std::map<jlong, std::unique_ptr<Diffusion>> g_diffusion_sessions;
static std::mutex g_diffusion_sessions_mutex;

extern "C" JNIEXPORT jlong JNICALL
Java_com_offlineai_mnn_MnnInference_createDiffusion(
    JNIEnv* env, jclass clazz, 
    jstring jModelDir, jint modelType, jint backendType, jint memoryMode) {
    
    try {
        // Get model directory
        const char* modelDir = env->GetStringUTFChars(jModelDir, nullptr);
        
        LOGI("[DIFFUSION] Creating diffusion session: modelDir=%s, modelType=%d, backend=%d, memory=%d",
             modelDir, modelType, backendType, memoryMode);
        
        // ========== 设置OpenCL kernel缓存目录到模型目录 ==========
        // 这样.tempcache会保存在模型目录而不是app根目录，更稳定
        int chdirResult = chdir(modelDir);
        if (chdirResult == 0) {
            char cwd[1024];
            getcwd(cwd, sizeof(cwd));
            LOGI("[DIFFUSION] Changed working directory to: %s (for .tempcache)", cwd);
        } else {
            LOGW("[DIFFUSION] Failed to change working directory, .tempcache will use default location");
        }
        
        // Validate backend type
        MNNForwardType actualBackend = static_cast<MNNForwardType>(backendType);
        LOGI("[DIFFUSION] Using user-selected backend: %d (CPU=0, OpenCL=3, Vulkan=7, NNAPI=6)", backendType);
        
        // Create Diffusion instance
        auto diffusion = std::make_unique<Diffusion>(
            std::string(modelDir),
            static_cast<DiffusionModelType>(modelType),
            actualBackend,
            memoryMode
        );
        
        env->ReleaseStringUTFChars(jModelDir, modelDir);
        
        // Load model (this will compile OpenCL kernels on first run - may take 5-15 minutes!)
        LOGI("[DIFFUSION] ========================================");
        LOGI("[DIFFUSION] Starting model load (backend=%d)...", actualBackend);
        if (actualBackend == MNN_FORWARD_OPENCL || actualBackend == MNN_FORWARD_VULKAN) {
            LOGW("[DIFFUSION] ⚠️ GPU backend: First load will compile hundreds of kernels");
            LOGW("[DIFFUSION] ⚠️ This may take 5-15 minutes! Please be patient...");
            LOGW("[DIFFUSION] ⚠️ Subsequent loads will use cached kernels and be much faster");
        }
        LOGI("[DIFFUSION] ========================================");
        
        auto start_time = std::chrono::high_resolution_clock::now();
        if (!diffusion->load()) {
            LOGE("[DIFFUSION] Failed to load diffusion model");
            LOGE("[DIFFUSION] If GPU backend is not available, Diffusion cannot run");
            LOGE("[DIFFUSION] Please ensure OpenCL or Vulkan is supported on your device");
            return 0;
        }
        auto end_time = std::chrono::high_resolution_clock::now();
        auto duration = std::chrono::duration_cast<std::chrono::milliseconds>(end_time - start_time).count();
        
        LOGI("[DIFFUSION] ========================================");
        LOGI("[DIFFUSION] ✅ Diffusion model loaded successfully in %lld ms (%.1f sec)", 
             (long long)duration, duration / 1000.0);
        LOGI("[DIFFUSION] ========================================");
        
        // Store in global map
        jlong handle = reinterpret_cast<jlong>(diffusion.get());
        {
            std::lock_guard<std::mutex> lock(g_diffusion_sessions_mutex);
            g_diffusion_sessions[handle] = std::move(diffusion);
        }
        
        LOGI("[DIFFUSION] Diffusion session created: %lld", (long long)handle);
        return handle;
        
    } catch (const std::exception& e) {
        LOGE("[DIFFUSION] Failed to create diffusion session: %s", e.what());
        return 0;
    } catch (...) {
        LOGE("[DIFFUSION] Failed to create diffusion session: unknown error");
        return 0;
    }
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_offlineai_mnn_MnnInference_generateImage(
    JNIEnv* env, jclass clazz,
    jlong handle, jstring jPrompt, jstring jOutputPath, 
    jint iterNum, jint randomSeed, jobject callback) {
    
    try {
        // Get diffusion instance
        Diffusion* diffusion = nullptr;
        {
            std::lock_guard<std::mutex> lock(g_diffusion_sessions_mutex);
            auto it = g_diffusion_sessions.find(handle);
            if (it == g_diffusion_sessions.end()) {
                LOGE("[DIFFUSION] Invalid diffusion handle: %lld", (long long)handle);
                return JNI_FALSE;
            }
            diffusion = it->second.get();
        }
        
        // Get strings
        const char* prompt = env->GetStringUTFChars(jPrompt, nullptr);
        const char* outputPath = env->GetStringUTFChars(jOutputPath, nullptr);
        
        LOGI("[DIFFUSION] Generating image: prompt='%s', output='%s', iter=%d, seed=%d",
             prompt, outputPath, iterNum, randomSeed);
        
        __android_log_print(ANDROID_LOG_INFO, "MNN_JNI", "[DIFF_DEBUG] About to prepare callback");
        
        // Get callback class and methods
        jclass callbackClass = env->GetObjectClass(callback);
        jmethodID onProgressMethod = env->GetMethodID(callbackClass, "onProgress", "(I)Z");
        jmethodID onTokenMethod = env->GetMethodID(callbackClass, "onToken", "(Ljava/lang/String;)Z");
        
        // Send initial config info to UI
        if (onTokenMethod) {
            char buf[512];
            snprintf(buf, sizeof(buf), "\nPreparing: Steps=%d, CFG=7.5, Scheduler=PLMS, Seed=%s", 
                     iterNum, randomSeed < 0 ? "Random" : std::to_string(randomSeed).c_str());
            jstring jmsg = env->NewStringUTF(buf);
            env->CallBooleanMethod(callback, onTokenMethod, jmsg);
            env->DeleteLocalRef(jmsg);
        }
        
        __android_log_print(ANDROID_LOG_INFO, "MNN_JNI", "[DIFF_DEBUG] Callback prepared, creating lambda");
        
        // Track kernel compilation phase
        static std::atomic<bool> first_run_flag{true};
        bool is_first_run = first_run_flag.exchange(false);
        if (is_first_run && onTokenMethod) {
            jstring jmsg = env->NewStringUTF("\nFirst GPU run: compiling kernels (1-3 min)\nNext run will be faster");
            env->CallBooleanMethod(callback, onTokenMethod, jmsg);
            env->DeleteLocalRef(jmsg);
        }
        
        // Progress tracking variables
        int total_steps = iterNum;
        int last_step = 0;
        bool unet_started = false;
        
        // VAE decoding progress (heartbeat) 
        std::atomic<bool> vae_decoding{false};
        std::atomic<bool> should_stop{false};
        
        // Heartbeat thread for VAE decoding progress
        std::thread heartbeat_thread([&]() {
            while (!should_stop) {
                if (vae_decoding && onTokenMethod) {
                    jstring jmsg = env->NewStringUTF(".");
                    env->CallBooleanMethod(callback, onTokenMethod, jmsg);
                    env->DeleteLocalRef(jmsg);
                    std::this_thread::sleep_for(std::chrono::seconds(3));
                } else {
                    std::this_thread::sleep_for(std::chrono::milliseconds(200));
                }
            }
        });
        
        std::function<void(int)> progressCallback = [env, callback, onProgressMethod, onTokenMethod, total_steps, &vae_decoding, &last_step, &unet_started](int progress) {
            __android_log_print(ANDROID_LOG_INFO, "MNN_JNI", "[DIFF_DEBUG] Progress callback invoked: %d%%", progress);
            
            // Send detailed progress to UI via onToken
            if (onTokenMethod) {
                char buf[256];
                
                // Text Encoder完成 (progress < 10%)
                if (progress > 0 && progress < 10 && !unet_started) {
                    snprintf(buf, sizeof(buf), "\nText Encoder: done\nUNet Steps(%d): ", total_steps);
                    last_step = 0;
                    unet_started = true;
                }
                // UNet步骤 (10% ~ 95%)
                else if (progress >= 10 && progress < 95 && unet_started) {
                    // 计算当前步数：progress从10%开始，95%结束，对应step 1~20
                    int current_step = ((progress - 10) * total_steps) / 85 + 1;
                    if (current_step > last_step && current_step <= total_steps) {
                        snprintf(buf, sizeof(buf), "%d..", current_step);
                        last_step = current_step;
                        // UNet最后一步完成时，输出VAE Decoder信息
                        if (current_step == total_steps) {
                            snprintf(buf, sizeof(buf), "%d\nUNet: done\nVAE Decoder: generating...", total_steps);
                            unet_started = false;
                        }
                    } else {
                        buf[0] = '\0';
                    }
                }
                // UNet完成，VAE Decoder开始 (progress >= 95%) - 备用逻辑
                else if (progress >= 95 && progress < 100) {
                    if (unet_started) {
                        snprintf(buf, sizeof(buf), "%d\nUNet: done\nVAE Decoder: generating...", total_steps);
                        unet_started = false;
                    } else {
                        buf[0] = '\0';
                    }
                }
                // 全部完成 (progress == 100)
                else if (progress == 100) {
                    buf[0] = '\0';  // 在后面统一输出Completed
                }
                else {
                    buf[0] = '\0';
                }
                
                if (buf[0] != '\0') {
                    jstring jmsg = env->NewStringUTF(buf);
                    env->CallBooleanMethod(callback, onTokenMethod, jmsg);
                    env->DeleteLocalRef(jmsg);
                }
            }
            
            // Enable VAE decoding heartbeat when we reach final stage
            if (progress >= 95 && progress < 100) {
                vae_decoding = true;
            }
            
            // Also call standard progress callback for UI progress bar
            if (onProgressMethod) {
                env->CallBooleanMethod(callback, onProgressMethod, progress);
            }
        };
        
        __android_log_print(ANDROID_LOG_INFO, "MNN_JNI", "[DIFF_DEBUG] ========================================");
        __android_log_print(ANDROID_LOG_INFO, "MNN_JNI", "[DIFF_DEBUG] ABOUT TO CALL diffusion->run()");
        __android_log_print(ANDROID_LOG_INFO, "MNN_JNI", "[DIFF_DEBUG] This will call: tokenizer->encode → text_encoder → unet → vae_decoder");
        
        // CRITICAL: In non-enough memory mode, need to reload before each run
        // See MNN diffusion_demo.cpp line 67: if(memory_mode != 1) { diffusion->load(); }
        static std::atomic<bool> first_generation{true};
        if (!first_generation.load()) {
            __android_log_print(ANDROID_LOG_INFO, "MNN_JNI", "[DIFF_DEBUG] Not first generation, reloading diffusion models...");
            if (onTokenMethod) {
                jstring jmsg = env->NewStringUTF("\nReloading models...");
                env->CallBooleanMethod(callback, onTokenMethod, jmsg);
                env->DeleteLocalRef(jmsg);
            }
            diffusion->load();
            __android_log_print(ANDROID_LOG_INFO, "MNN_JNI", "[DIFF_DEBUG] Diffusion models reloaded");
        } else {
            first_generation.store(false);
        }
        
        __android_log_print(ANDROID_LOG_INFO, "MNN_JNI", "[DIFF_DEBUG] ========================================");
        
        // Run diffusion
        auto start = std::chrono::high_resolution_clock::now();
        bool success = diffusion->run(
            std::string(prompt),
            std::string(outputPath),
            iterNum,
            randomSeed,
            progressCallback
        );
        
        // Stop heartbeat thread
        vae_decoding = false;
        should_stop = true;
        if (heartbeat_thread.joinable()) {
            heartbeat_thread.join();
        }
        
        __android_log_print(ANDROID_LOG_INFO, "MNN_JNI", "[DIFF_DEBUG] diffusion->run() RETURNED");
        auto end = std::chrono::high_resolution_clock::now();
        auto duration = std::chrono::duration_cast<std::chrono::milliseconds>(end - start).count();
        
        __android_log_print(ANDROID_LOG_INFO, "MNN_JNI", "[DIFF_DEBUG] Releasing strings");
        env->ReleaseStringUTFChars(jPrompt, prompt);
        env->ReleaseStringUTFChars(jOutputPath, outputPath);
        
        if (success) {
            LOGI("[DIFFUSION] Image generated successfully in %lld ms", (long long)duration);
            __android_log_print(ANDROID_LOG_INFO, "MNN_JNI", "[DIFF_DEBUG] SUCCESS - Total time: %lld ms", (long long)duration);
            
            // Send completion info to UI (final summary)
            if (onTokenMethod) {
                char buf[256];
                snprintf(buf, sizeof(buf), "\nCompleted (%.1fs)", duration / 1000.0);
                jstring jmsg = env->NewStringUTF(buf);
                env->CallBooleanMethod(callback, onTokenMethod, jmsg);
                env->DeleteLocalRef(jmsg);
            }
        } else {
            LOGE("[DIFFUSION] Image generation failed");
            __android_log_print(ANDROID_LOG_ERROR, "MNN_JNI", "[DIFF_DEBUG] FAILED");
            
            if (onTokenMethod) {
                jstring jmsg = env->NewStringUTF("\nGeneration failed");
                env->CallBooleanMethod(callback, onTokenMethod, jmsg);
                env->DeleteLocalRef(jmsg);
            }
        }
        
        return success ? JNI_TRUE : JNI_FALSE;
        
    } catch (const std::exception& e) {
        LOGE("[DIFFUSION] Failed to generate image: %s", e.what());
        return JNI_FALSE;
    } catch (...) {
        LOGE("[DIFFUSION] Failed to generate image: unknown error");
        return JNI_FALSE;
    }
}

extern "C" JNIEXPORT void JNICALL
Java_com_offlineai_mnn_MnnInference_releaseDiffusion(
    JNIEnv* env, jclass clazz, jlong handle) {
    
    std::lock_guard<std::mutex> lock(g_diffusion_sessions_mutex);
    auto it = g_diffusion_sessions.find(handle);
    if (it != g_diffusion_sessions.end()) {
        g_diffusion_sessions.erase(it);
        LOGI("[DIFFUSION] Diffusion session released: %lld", (long long)handle);
    } else {
        LOGW("[DIFFUSION] Diffusion handle not found: %lld", (long long)handle);
    }
}
