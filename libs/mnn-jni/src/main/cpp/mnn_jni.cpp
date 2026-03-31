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
#include <regex>
#include <unistd.h>  // for chdir, getcwd
#include <cerrno>    // for errno
#include <sys/stat.h> // for stat
#include <sys/types.h> // for gettid
#include <sys/syscall.h> // for syscall
#include <sys/resource.h> // for getrusage

// Get peak memory usage in KB from /proc/self/status
static long getPeakMemoryKB() {
    std::ifstream status("/proc/self/status");
    if (!status.is_open()) return -1;
    
    std::string line;
    while (std::getline(status, line)) {
        if (line.find("VmPeak:") == 0) {
            // Format: "VmPeak:    123456 kB"
            long value = 0;
            sscanf(line.c_str(), "VmPeak: %ld", &value);
            return value;
        }
    }
    return -1;
}

// Get current RSS memory in KB from /proc/self/status
static long getCurrentRssKB() {
    std::ifstream status("/proc/self/status");
    if (!status.is_open()) return -1;
    
    std::string line;
    while (std::getline(status, line)) {
        if (line.find("VmRSS:") == 0) {
            long value = 0;
            sscanf(line.c_str(), "VmRSS: %ld", &value);
            return value;
        }
    }
    return -1;
}

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

// ========== Multimedia Filter Utility ==========
/**
 * Filter multimedia tags from history content
 * Removes <img>...</img> and <audio>...</audio> tags
 * This is our advantage over ChatMNN - we filter multimedia for TTS
 * @param content Original content with potential multimedia tags
 * @return Filtered content with only text
 */
std::string filterMultimediaTags(const std::string& content) {
    std::string result = content;
    
    // Remove <img>...</img> tags (including <img src="..."/>)
    size_t img_start = 0;
    while ((img_start = result.find("<img", img_start)) != std::string::npos) {
        size_t img_end = result.find(">", img_start);
        if (img_end != std::string::npos) {
            // Check if it's self-closing tag
            if (result[img_end - 1] == '/') {
                result.erase(img_start, img_end - img_start + 1);
            } else {
                // Find closing </img>
                size_t close_tag = result.find("</img>", img_end);
                if (close_tag != std::string::npos) {
                    result.erase(img_start, close_tag - img_start + 6);
                } else {
                    result.erase(img_start, img_end - img_start + 1);
                }
            }
        } else {
            break;
        }
    }
    
    // Remove <audio>...</audio> tags
    size_t audio_start = 0;
    while ((audio_start = result.find("<audio", audio_start)) != std::string::npos) {
        size_t audio_end = result.find("</audio>", audio_start);
        if (audio_end != std::string::npos) {
            result.erase(audio_start, audio_end - audio_start + 8);
        } else {
            // Self-closing or malformed, remove opening tag only
            size_t tag_end = result.find(">", audio_start);
            if (tag_end != std::string::npos) {
                result.erase(audio_start, tag_end - audio_start + 1);
            }
            break;
        }
    }
    
    // Trim leading/trailing whitespace
    size_t first = result.find_first_not_of(" \t\n\r");
    if (first == std::string::npos) return "";
    size_t last = result.find_last_not_of(" \t\n\r");
    return result.substr(first, last - first + 1);
}

/**
 * Filter multimedia tags from entire history
 * @param history Original history with multimedia tags
 * @return Filtered history with only text content
 */
MNN::Transformer::ChatMessages filterMultimediaFromHistory(const MNN::Transformer::ChatMessages& history) {
    MNN::Transformer::ChatMessages filtered;
    filtered.reserve(history.size());
    
    for (const auto& item : history) {
        std::string role = item.first;
        std::string content = item.second;
        
        // Filter multimedia tags from content
        std::string filtered_content = filterMultimediaTags(content);
        
        // Only add if content is not empty after filtering
        if (!filtered_content.empty()) {
            filtered.emplace_back(role, filtered_content);
        }
    }
    
    return filtered;
}

// ========== WAV File Writer Utility ==========
/**
 * Write PCM audio data as WAV file
 * @param pcm_data Float32 audio samples in range [-1.0, 1.0]
 * @param sample_count Number of samples
 * @param output_path Output file path
 * @param sample_rate Sample rate (default: 24000 for Qwen2.5-Omni)
 * @return true if successful, false otherwise
 */
bool writeWavFile(const float* pcm_data, size_t sample_count, const char* output_path, int sample_rate = 24000) {
    std::ofstream file(output_path, std::ios::binary);
    if (!file.is_open()) {
        // Use direct log (this function runs before LogManager init)
        __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, "[TTS] Failed to open file: %s", output_path);
        return false;
    }
    
    // WAV file parameters (24kHz mono 16-bit)
    const int num_channels = 1;
    const int bits_per_sample = 16;
    const int byte_rate = sample_rate * num_channels * bits_per_sample / 8;
    const int block_align = num_channels * bits_per_sample / 8;
    const int data_size = sample_count * block_align;
    
    // Write WAV header (44 bytes)
    file.write("RIFF", 4);  // ChunkID
    int chunk_size = 36 + data_size;
    file.write(reinterpret_cast<char*>(&chunk_size), 4);  // ChunkSize
    file.write("WAVE", 4);  // Format
    file.write("fmt ", 4);  // Subchunk1ID
    int subchunk1_size = 16;
    file.write(reinterpret_cast<char*>(&subchunk1_size), 4);  // Subchunk1Size (PCM)
    short audio_format = 1;  // PCM
    file.write(reinterpret_cast<char*>(&audio_format), 2);  // AudioFormat
    short num_channels_short = num_channels;
    file.write(reinterpret_cast<char*>(&num_channels_short), 2);  // NumChannels
    file.write(reinterpret_cast<char*>(&sample_rate), 4);  // SampleRate
    file.write(reinterpret_cast<const char*>(&byte_rate), 4);  // ByteRate
    short block_align_short = block_align;
    file.write(reinterpret_cast<char*>(&block_align_short), 2);  // BlockAlign
    short bits_per_sample_short = bits_per_sample;
    file.write(reinterpret_cast<char*>(&bits_per_sample_short), 2);  // BitsPerSample
    file.write("data", 4);  // Subchunk2ID
    file.write(reinterpret_cast<const char*>(&data_size), 4);  // Subchunk2Size
    
    // Convert float32 to 16-bit PCM and write
    for (size_t i = 0; i < sample_count; i++) {
        // Clamp to [-1.0, 1.0] and convert to 16-bit
        float sample = std::max(-1.0f, std::min(1.0f, pcm_data[i]));
        short pcm = static_cast<short>(sample * 32767.0f);
        file.write(reinterpret_cast<char*>(&pcm), 2);
    }
    
    file.close();
    
    // Verify file was created
    struct stat st;
    if (stat(output_path, &st) == 0 && st.st_size > 0) {
        __android_log_print(ANDROID_LOG_INFO, LOG_TAG, "[TTS] ✅ WAV file written: %s (%ld bytes)", output_path, st.st_size);
        return true;
    } else {
        __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, "[TTS] ❌ File verification failed: %s", output_path);
        return false;
    }
}

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

// Log macros: use call_log_manager for unified logging (logcat + file)
// Note: call_log_manager calls Java LogManager which handles both logcat and file output
#define LOGI(...) do { \
    char buffer[2048]; \
    snprintf(buffer, sizeof(buffer), __VA_ARGS__); \
    call_log_manager(ANDROID_LOG_INFO, LOG_TAG, buffer); \
} while(0)

#define LOGD(...) do { \
    char buffer[2048]; \
    snprintf(buffer, sizeof(buffer), __VA_ARGS__); \
    call_log_manager(ANDROID_LOG_DEBUG, LOG_TAG, buffer); \
} while(0)

#define LOGW(...) do { \
    char buffer[2048]; \
    snprintf(buffer, sizeof(buffer), __VA_ARGS__); \
    call_log_manager(ANDROID_LOG_WARN, LOG_TAG, buffer); \
} while(0)

#define LOGE(...) do { \
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
    
    // Redirect to LogManager if initialized; otherwise fall back to logcat directly
    if (g_jvm && g_logManagerClass) {
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
            jstring jMessage = env->NewStringUTF(buffer);
            jstring jTag = env->NewStringUTF(tag);

            if (jMessage && jTag) {
                if (level == ANDROID_LOG_ERROR && g_logEMethod) {
                    env->CallStaticVoidMethod(g_logManagerClass, g_logEMethod, jTag, jMessage);
                } else if (g_logIMethod) {
                    env->CallStaticVoidMethod(g_logManagerClass, g_logIMethod, jTag, jMessage);
                }
            }

            if (jMessage) env->DeleteLocalRef(jMessage);
            if (jTag) env->DeleteLocalRef(jTag);

            if (detach) {
                g_jvm->DetachCurrentThread();
            }
        }
    } else {
        __android_log_print(level, tag, "%s", buffer);
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
        : model_dir_(model_dir), config_json_(config_json), has_tts_(false), 
          enable_audio_output_(false), stop_requested_(false) {
        LOGI("Creating MNN LLM session: %s", model_dir.c_str());
        // Initialize history with system prompt (like ChatMNN Line 85)
        history_.emplace_back("system", "You are a helpful assistant.");
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
            
            // CRITICAL FIX: Merge runtime config into model config.json BEFORE createLLM()
            // Reason: Omni::Omni() reads image_size in constructor, before set_config() is called
            // Solution: Create a merged config and pass it to createLLM()
            std::string model_dir_with_slash = model_dir_;
            if (!model_dir_with_slash.empty() && model_dir_with_slash.back() != '/') {
                model_dir_with_slash += "/";
            }
            
            std::string config_path_for_llm = model_dir_with_slash;
            
            // If we have runtime config, merge it with model config.json
            if (!config_json_.empty()) {
                try {
                    // Read model's config.json
                    std::string model_config_path = model_dir_with_slash + "config.json";
                    std::ifstream model_config_file(model_config_path);
                    json merged_config;
                    
                    if (model_config_file.is_open()) {
                        model_config_file >> merged_config;
                        model_config_file.close();
                        LOGI("Loaded model config.json");
                    } else {
                        merged_config = json::object();
                        LOGW("Model config.json not found, using empty config");
                    }
                    
                    // Parse runtime config
                    json runtime_config = json::parse(config_json_);
                    
                    // Deep merge runtime config into model config (runtime takes precedence)
                    // CRITICAL: Use recursive merge to handle nested objects like jinja.context
                    std::function<void(json&, const json&)> deep_merge = [&](json& target, const json& source) {
                        for (auto it = source.begin(); it != source.end(); ++it) {
                            if (it.value().is_object() && target.contains(it.key()) && target[it.key()].is_object()) {
                                // Recursively merge nested objects
                                deep_merge(target[it.key()], it.value());
                            } else {
                                // Overwrite or add new key
                                target[it.key()] = it.value();
                            }
                        }
                    };
                    deep_merge(merged_config, runtime_config);
                    
                    // CRITICAL FIX: Auto-fix Qwen3.5 chat_template bug in llm_config.json
                    // MNN reads chat_template from llm_config.json, NOT config.json
                    // Official Qwen3.5 template has a bug: when enable_thinking=false, it outputs empty <think></think> tags
                    // This misleads the model to generate thinking content anyway, wasting compute.
                    // Fix: Remove the else branch so NO <think> tag is output when enable_thinking=false
                    std::string llm_config_path = model_dir_with_slash + "llm_config.json";
                    std::ifstream llm_config_file(llm_config_path);
                    if (llm_config_file.is_open()) {
                        json llm_config;
                        llm_config_file >> llm_config;
                        llm_config_file.close();
                        
                        if (llm_config.contains("jinja") && llm_config["jinja"].contains("chat_template")) {
                            std::string chat_template = llm_config["jinja"]["chat_template"].get<std::string>();
                            
                            // SAFETY CHECK: Only fix if template contains the EXACT buggy Qwen3.5 pattern
                            bool is_qwen35_buggy_template = (
                                chat_template.find("'<|im_start|>assistant\\n<think>\\n'") != std::string::npos
                            );
                            
                            if (is_qwen35_buggy_template) {
                                // Qwen3.5 template ends with:
                                // {%- if add_generation_prompt %}
                                //     {{- '<|im_start|>assistant\n<think>\n' }}
                                // {%- endif %}
                                //
                                // This ALWAYS outputs <think> tag, even when enable_thinking=false
                                // Fix: Add conditional check for enable_thinking
                                
                                std::regex buggy_pattern(
                                    R"(\{%- if add_generation_prompt %\}\s*\{\{- '<\|im_start\|>assistant\\n<think>\\n' \}\}\s*\{%- endif %\})"
                                );
                                
                                std::string fixed_template = std::regex_replace(chat_template, buggy_pattern,
                                    "{%- if add_generation_prompt %}\n    {{- '<|im_start|>assistant\\n' }}\n    {%- if enable_thinking is defined and enable_thinking is true %}\n        {{- '<think>\\n' }}\n    {%- endif %}\n{%- endif %}");
                                
                                if (fixed_template != chat_template) {
                                    llm_config["jinja"]["chat_template"] = fixed_template;
                                    
                                    // Write fixed llm_config.json back
                                    std::ofstream llm_config_out(llm_config_path);
                                    if (llm_config_out.is_open()) {
                                        llm_config_out << llm_config.dump(4);
                                        llm_config_out.close();
                                        LOGI("[TEMPLATE_FIX] Fixed Qwen3.5 llm_config.json - added enable_thinking check");
                                        LOGI("[TEMPLATE_FIX] When enable_thinking=false, NO <think> tag will be output");
                                    } else {
                                        LOGW("[TEMPLATE_FIX] Failed to write fixed llm_config.json");
                                    }
                                } else {
                                    LOGW("[TEMPLATE_FIX] Qwen3.5 pattern detected but regex replacement failed");
                                }
                            } else {
                                LOGI("[TEMPLATE_FIX] Not a Qwen3.5 buggy template, skipping fix");
                            }
                        }
                    } else {
                        LOGI("[TEMPLATE_FIX] llm_config.json not found, skipping template fix");
                    }
                    
                    // Debug: Log complete merged config to verify deep merge
                    LOGI("[CONFIG][MERGE] Complete merged config:");
                    LOGI("%s", merged_config.dump(2).c_str());
                    
                    // Debug: Log critical thinking-related config
                    if (merged_config.contains("jinja") && merged_config["jinja"].contains("context") && merged_config["jinja"]["context"].contains("enable_thinking")) {
                        LOGI("[CONFIG][MERGE] enable_thinking = %s", merged_config["jinja"]["context"]["enable_thinking"].get<bool>() ? "true" : "false");
                    } else {
                        LOGI("[CONFIG][MERGE] enable_thinking = NOT_FOUND");
                    }
                    if (merged_config.contains("assistant_prompt_template")) {
                        LOGI("[CONFIG][MERGE] assistant_prompt_template = %s", merged_config["assistant_prompt_template"].get<std::string>().c_str());
                    } else {
                        LOGI("[CONFIG][MERGE] assistant_prompt_template = NOT_FOUND");
                    }
                    
                    // Save merged config to temporary file
                    std::string temp_config_path = model_dir_with_slash + "config_runtime_merged.json";
                    std::ofstream temp_config_file(temp_config_path);
                    if (temp_config_file.is_open()) {
                        temp_config_file << merged_config.dump(2);
                        temp_config_file.close();
                        config_path_for_llm = temp_config_path;
                        LOGI("Saved merged config to: %s", temp_config_path.c_str());
                        LOGI("Merged config contains image_size: %d", merged_config.value("image_size", -1));
                    } else {
                        LOGW("Failed to save merged config, using model directory");
                    }
                } catch (const std::exception& e) {
                    LOGW("Failed to merge configs: %s, using model directory", e.what());
                }
            }
            
            LOGI("Creating LLM instance from: %s", config_path_for_llm.c_str());
            llm_ = Llm::createLLM(config_path_for_llm);
            
            if (!llm_) {
                LOGE("Failed to create LLM instance");
                return false;
            }
            
            LOGI("LLM instance created successfully");
            
            // Load model (this will use the runtime config set above)
            LOGI("About to load MNN model from: %s", model_dir_.c_str());
            LOGI("Calling llm_->load()...");
            bool success = llm_->load();
            LOGI("llm_->load() returned: %s", success ? "SUCCESS" : "FAILED");
            
            if (success) {
                LOGI("=== MNN LLM SESSION LOADED SUCCESSFULLY ===");
                LOGI("Backend (from ExecutorScope): forwardType=%d", (int)forwardType);
                
                // Check if model has TTS support (talker.mnn)
                std::string talker_path = model_dir_ + "/talker.mnn";
                FILE* talker_file = fopen(talker_path.c_str(), "r");
                if (talker_file) {
                    fclose(talker_file);
                    has_tts_ = true;
                    LOGI("✅ TTS (Talker) model detected");
                    
                    // CRITICAL: Set TTS callback AFTER load, like official MnnLlmChat
                    // Official flow: load() -> SetWavformCallback() -> enableAudioOutput(true) -> Response()
                    llm_->setWavformCallback([this](const float* data, size_t size, bool isEnd) -> bool {
                        LOGI("[TTS] Received audio chunk: size=%zu, isEnd=%d", size, isEnd);
                        // Check enable flag AND stop flag like ChatMNN (Line 205-207)
                        if (!enable_audio_output_ || stop_requested_) {
                            LOGW("[TTS] Callback rejected: enable=%d, stop=%d", enable_audio_output_, stop_requested_);
                            return false;
                        }
                        // DEBUG: Log first chunk header once
                        if (!tts_chunk_header_logged_) {
                            float s0 = size > 0 ? data[0] : 0.0f;
                            float s1 = size > 1 ? data[1] : 0.0f;
                            float s2 = size > 2 ? data[2] : 0.0f;
                            LOGI("[TTS][DEBUG] First audio chunk header: size=%zu, samples=[%.5f, %.5f, %.5f]...", size, s0, s1, s2);
                            tts_chunk_header_logged_ = true;
                        }
                        // Accumulate audio chunks
                        tts_audio_buffer_.insert(tts_audio_buffer_.end(), data, data + size);
                        LOGI("[TTS] Callback received: %zu samples, isEnd=%d, total=%zu", size, isEnd, tts_audio_buffer_.size());
                        return true; // Continue receiving (ChatMNN returns result of user callback)
                    });
                    LOGI("✅ TTS callback set after load (official pattern)");
                    LOGI("[TTS] Audio callback registered");
                } else {
                    has_tts_ = false;
                    LOGI("❌ TTS (Talker) model not found");
                }
                
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
            // Keep system prompt, clear conversation history (like ChatMNN Line 76)
            history_.resize(1);
            LOGD("Session reset, history cleared (system prompt kept)");
        }
    }
    
    // Enable/disable TTS audio output
    void enableAudioOutput(bool enable) {
        enable_audio_output_ = enable;
        LOGI("[TTS] Audio output %s", enable ? "enabled" : "disabled");
    }
    
    // Inference with streaming callback
    bool inference(const std::string& prompt, 
                   std::function<bool(const std::string&)> token_callback,
                   std::function<void(const LlmContext*)> complete_callback) {
        if (!llm_) {
            LOGE("LLM not initialized");
            return false;
        }
        
        // ✅ ALIGNED WITH ChatMNN Response() API:
        // 1. Java passes SINGLE user input with <img>/<audio> tags (NOT full prompt)
        // 2. C++ adds to internal history_ (KEEPS multimedia tags, NO filtering)
        // 3. C++ calls llm_->response(history_, ...) with FULL history
        // 4. MNN applies prompt template automatically
        bool has_audio = (prompt.find("<audio>") != std::string::npos);
        bool has_image = (prompt.find("<img>") != std::string::npos);
        const char* mode_tag = has_audio ? "[AUDIO]" : (has_image ? "[IMAGE]" : "[TEXT]");
        
        // DEBUG: Print current user input (first 200 chars)
        std::string prompt_preview = prompt.length() > 200 ? prompt.substr(0, 200) + "..." : prompt;
        LOGI("[JAVA->C++] Received SINGLE user input (len=%zu): %s", prompt.length(), prompt_preview.c_str());
        LOGI("[JAVA->C++] Has <img>: %d, Has <audio>: %d", has_image, has_audio);
        
        // DEBUG: Mark inference start for TTS diagnosis
        if (has_tts_) {
            LOGI("[TTS][DEBUG] ========== Inference START ==========");
            // Note: Cannot check TalkerEmbeds.size() without modifying MNN base class
            // Will check via audio output duplication instead
        }
        
        try {
            // ⚠️ NOTE: inference() API does NOT manage history_
            // History is managed by Java layer and passed via inferenceWithHistory() API
            // This API only processes single prompt (may contain <img>/<audio> tags)
            LOGI("[INFERENCE] Processing single prompt (length=%zu)", prompt.length());
            
            // DEBUG: Print prompt info
            LOGI("[INFERENCE] ========== PROMPT INFO ==========");
            std::string content_preview = prompt.length() > 100 ? 
                prompt.substr(0, 100) + "..." : prompt;
            bool has_img_tag = (prompt.find("<img>") != std::string::npos);
            bool has_aud_tag = (prompt.find("<audio>") != std::string::npos);
            LOGI("[INFERENCE] Prompt length: %zu, has_img=%d, has_audio=%d", 
                 prompt.length(), has_img_tag, has_aud_tag);
            LOGI("[INFERENCE] Preview: %s", content_preview.c_str());
            LOGI("[INFERENCE] ========== END PROMPT INFO ==========");
            
            // Count multimedia tags in prompt
            size_t img_count = 0, audio_count = 0;
            size_t pos = 0;
            while ((pos = prompt.find("<img>", pos)) != std::string::npos) {
                img_count++; pos++;
            }
            pos = 0;
            while ((pos = prompt.find("<audio>", pos)) != std::string::npos) {
                audio_count++; pos++;
            }
            LOGI("[INFERENCE] Multimedia tags: <img>=%zu, <audio>=%zu", img_count, audio_count);
            
            // Create output stream with custom streambuf
            StreamBuffer stream_buffer(token_callback);
            std::ostream output_stream(&stream_buffer);
            
            // Parse sampling and generation params from runtime config
            int max_new_tokens = 2048;
            float temperature = 1.0f;
            float top_p = 1.0f;
            int top_k = 40;
            float presence_penalty = 0.0f;
            float frequency_penalty = 0.0f;
            try {
                if (!config_json_.empty()) {
                    json config = json::parse(config_json_);
                    if (config.contains("max_new_tokens")) max_new_tokens = config["max_new_tokens"].get<int>();
                    if (config.contains("temperature")) temperature = config["temperature"].get<float>();
                    if (config.contains("top_p")) top_p = config["top_p"].get<float>();
                    if (config.contains("top_k")) top_k = config["top_k"].get<int>();
                    if (config.contains("presence_penalty")) presence_penalty = config["presence_penalty"].get<float>();
                    if (config.contains("frequency_penalty")) frequency_penalty = config["frequency_penalty"].get<float>();
                }
            } catch (...) {
                // Keep defaults if parsing fails
            }
            
            // Reset stop flags at start of inference
            stop_requested_ = false;
            bool generate_end = false;
            
            // CRITICAL: Enable audio output BEFORE text generation (ChatMNN Line 212)
            // MNN needs this flag during response() to accumulate TalkerEmbeds
            if (has_tts_ && !tts_output_path_.empty()) {
                tts_chunk_header_logged_ = false; // reset first-chunk log flag
                enable_audio_output_ = true;
                LOGI("[TTS] Audio output enabled BEFORE text generation");
            }
            
            LOGI("%s Sampling params -> max_new_tokens=%d, temperature=%.3f, top_p=%.3f, top_k=%d, presence_penalty=%.3f, frequency_penalty=%.3f",
                 mode_tag, max_new_tokens, temperature, top_p, top_k, presence_penalty, frequency_penalty);
            LOGI("%s Starting generation with max_new_tokens=%d", mode_tag, max_new_tokens);
            
            // Reset TTS first-chunk flag at generation start (defensive)
            tts_chunk_header_logged_ = false;
            
            // Check for multimodal content (like ChatMNN Line 194-206)
            // Note: For now, we don't have PromptProcessor, so just use history API
            // TODO: Implement processMultimodalPrompt() if needed for image/video support
            
            // ⚠️ CRITICAL: Call llm_->response() with prompt string (NOT history_)
            // History is managed by Java layer via inferenceWithHistory() API
            // This API processes single prompt only
            LOGI("[MNN_CALL] Calling llm_->response(prompt, ...) with single prompt");
            LOGI("[MNN_CALL] Prompt contains multimedia tags: <img>=%zu, <audio>=%zu", img_count, audio_count);
            llm_->response(prompt, &output_stream, "<eop>", 1);
            int current_size = 1;
            
            // Generate remaining tokens one by one
            // Note: MNN's generate() will return early if EOS token is detected
            // We check stop flags set by StreamBuffer callback
            while (!stop_requested_ && !generate_end && current_size < max_new_tokens) {
                // LOGD("[TEXT] Calling llm_->generate(1), current_size=%d", current_size);  // Too verbose
                llm_->generate(1);
                current_size++;
                // LOGD("[TEXT] After generate(), current_size=%d", current_size);  // Too verbose
                
                // Check flags set by StreamBuffer and update member variable
                bool stop_from_buffer = stream_buffer.isStopRequested();
                generate_end = stream_buffer.isGenerateEnd();
                
                if (stop_from_buffer) {
                    stop_requested_ = true;  // Update member variable for TTS callback
                    LOGI("%s Stop requested at token %d", mode_tag, current_size);
                }
                if (generate_end) {
                    LOGI("%s Generation ended (EOS/<eop>) at token %d", mode_tag, current_size);
                }
            }
            LOGI("%s Generation loop finished: current_size=%d, stop=%d, end=%d", 
                 mode_tag, current_size, stop_requested_, generate_end);
            
            if (stop_requested_) {
                LOGI("%s Inference stopped by user after %d tokens", mode_tag, current_size);
            } else {
                LOGI("%s Inference completed, generated %d tokens", mode_tag, current_size);
            }
            
            // DEBUG: Log text generation completion for TTS diagnosis
            if (has_tts_) {
                LOGI("[TTS][DEBUG] Text generation completed: %d tokens", current_size);
            }

            // Get generated response text (do NOT add to history_)
            std::string response_text = stream_buffer.getFullText();
            if (!response_text.empty()) {
                LOGI("[INFERENCE] Generated response: %zu chars", response_text.length());
                
                // Log preview for TTS diagnosis
                std::string preview = response_text;
                if (preview.length() > 160) {
                    preview = preview.substr(preview.length() - 160);
                }
                for (char& ch : preview) {
                    if (ch == '\n' || ch == '\r') ch = ' ';
                }
                LOGI("[TTS][DEBUG] Response preview (tail): %s", preview.c_str());
            } else {
                LOGI("[TTS][DEBUG] Response is empty (no tokens generated)");
            }
            
            // Generate TTS audio if supported and output path is set
            // Follow official MnnLlmChat pattern: callback already set in load()
            // Check member variable stop_requested_ (like ChatMNN Line 180)
            bool tts_path_ready = !tts_output_path_.empty();
            LOGI("[TTS][DEBUG] Audio pre-check: has_tts=%d, path_ready=%d, enable_flag=%d, stop_flag=%d", 
                 has_tts_, tts_path_ready ? 1 : 0, enable_audio_output_ ? 1 : 0, stop_requested_ ? 1 : 0);
            if (!stop_requested_ && has_tts_ && tts_path_ready) {
                LOGI("[TTS] ========== Starting TTS Generation ==========");
                LOGI("[TTS] Generated text tokens: %d", current_size);
                
                // DEBUG: Mark TTS generation start
                LOGI("[TTS][DEBUG] Calling generateWavform() for %d text tokens...", current_size);
                
                try {
                    // Clear buffer for new generation
                    tts_audio_buffer_.clear();
                    
                    // Audio output already enabled before text generation
                    LOGI("[TTS] Calling generateWavform()...");
                    
                    // Reset first-chunk log flag for audio generation stage
                    tts_chunk_header_logged_ = false;
                    
                    // Generate audio (synchronous, callback already set in load())
                    auto tts_start = std::chrono::steady_clock::now();
                    llm_->generateWavform();
                    auto tts_end = std::chrono::steady_clock::now();
                    auto tts_elapsed_ms = std::chrono::duration_cast<std::chrono::milliseconds>(tts_end - tts_start).count();
                    
                    // Disable audio output after generation
                    enable_audio_output_ = false;
                    LOGI("[TTS] generateWavform() completed in %lld ms, audio output disabled", (long long)tts_elapsed_ms);
                    
                    // DEBUG: Mark TTS generation end
                    LOGI("[TTS][DEBUG] generateWavform() completed, elapsed=%lld ms", (long long)tts_elapsed_ms);
                    
                    // Write WAV file synchronously (Diffusion-style)
                    if (!tts_audio_buffer_.empty()) {
                        float duration_sec = tts_audio_buffer_.size() / 24000.0f;
                        LOGI("[TTS] Writing WAV file: %zu samples (%.2fs) to %s", 
                             tts_audio_buffer_.size(), duration_sec, tts_output_path_.c_str());
                        bool success = writeWavFile(tts_audio_buffer_.data(), tts_audio_buffer_.size(), tts_output_path_.c_str());
                        if (success) {
                            LOGI("[TTS] ✅ Audio generation SUCCESS: %.2fs audio saved", duration_sec);
                        } else {
                            LOGE("[TTS] ❌ Failed to write WAV file");
                            tts_output_path_.clear(); // Clear path on error
                        }
                    } else {
                        LOGE("[TTS] ❌ No audio data generated (buffer empty!)");
                        tts_output_path_.clear();
                    }
                } catch (const std::exception& e) {
                    LOGE("[TTS] ❌ Audio generation exception: %s", e.what());
                    enable_audio_output_ = false;
                    tts_output_path_.clear();
                }
                LOGI("[TTS] =========================================");
            } else {
                // TTS not executed (stopped or no path), ensure flag is reset
                if (enable_audio_output_) {
                    enable_audio_output_ = false;
                    LOGI("[TTS] Audio output disabled (TTS skipped)");
                }
                if (!stop_requested_ && has_tts_ && !tts_path_ready) {
                    LOGW("[TTS][DEBUG] Skipping generateWavform: output path not set");
                }
            }
            
            // Get context with statistics
            const LlmContext* context = llm_->getContext();
            if (complete_callback) {
                complete_callback(context);
            }
            
            return true;
            
        } catch (const std::exception& e) {
            LOGE("Exception during inference: %s", e.what());
            enable_audio_output_ = false;  // Reset flag on error
            return false;
        }
    }
    
    // Inference with history (sliding window)
    bool inferenceWithHistory(const ChatMessages& history,
                             std::function<bool(const std::string&)> token_callback,
                             std::function<void(const LlmContext*)> complete_callback) {
        if (!llm_) {
            LOGE("LLM not initialized");
            return false;
        }
        
        // CRITICAL: DO NOT reset() when using history with images!
        // reset() clears visual embeddings -> SIGSEGV in Omni::embedding()
        // Only reset in normal inference (Line 454)
        LOGI("[HISTORY] Skipping reset() to preserve visual embeddings");
        
        try {
            const char* mode_tag = "[HISTORY]";
            
            // Create output stream with custom streambuf
            StreamBuffer stream_buffer(token_callback);
            std::ostream output_stream(&stream_buffer);
            
            // Parse max_new_tokens from config
            int max_new_tokens = 2048; // default
            try {
                json config = json::parse(config_json_);
                if (config.contains("max_new_tokens")) {
                    max_new_tokens = config["max_new_tokens"].get<int>();
                }
            } catch (...) {
                // Use default if parsing fails
            }
            
            // Reset stop flags at start of inference
            stop_requested_ = false;
            bool generate_end = false;
            
            // CRITICAL: Enable audio output BEFORE text generation (ChatMNN Line 212)
            // MNN needs this flag during response() to accumulate TalkerEmbeds
            if (has_tts_ && !tts_output_path_.empty()) {
                tts_chunk_header_logged_ = false; // reset first-chunk log flag
                enable_audio_output_ = true;
                LOGI("[TTS] Audio output enabled BEFORE text generation");
            }
            
            // ✅ Java layer already filtered history and kept tags in current input
            // C++ does NOT filter - directly pass to MNN
            // History structure: [system, user1, assistant1, ..., userN (with tags)]
            LOGI("%s Starting inference with history size=%zu, max_new_tokens=%d", 
                 mode_tag, history.size(), max_new_tokens);
            
            // Print FULL history for debugging
            LOGI("%s ========== HISTORY DUMP START ==========", mode_tag);
            for (size_t i = 0; i < history.size(); i++) {
                const auto& item = history[i];
                std::string preview = item.second.length() > 100 ? 
                    item.second.substr(0, 100) + "..." : item.second;
                bool has_img = (item.second.find("<img>") != std::string::npos);
                bool has_aud = (item.second.find("<audio>") != std::string::npos);
                LOGI("%s [%zu] role='%s', len=%zu, has_img=%d, has_audio=%d", 
                     mode_tag, i, item.first.c_str(), item.second.length(), has_img, has_aud);
                LOGI("%s [%zu] preview: %s", mode_tag, i, preview.c_str());
            }
            LOGI("%s ========== HISTORY DUMP END (total: %zu items) ==========", mode_tag, history.size());
            
            // Call MNN history API with complete history (including current input with tags)
            LOGI("%s Calling llm_->response(history, ...) - MNN history API", mode_tag);
            llm_->response(history, &output_stream, "<eop>", 1);
            int current_size = 1;
            
            // Generate remaining tokens one by one
            while (!stop_requested_ && !generate_end && current_size < max_new_tokens) {
                llm_->generate(1);
                current_size++;
                
                // Check flags set by StreamBuffer and update member variable
                bool stop_from_buffer = stream_buffer.isStopRequested();
                generate_end = stream_buffer.isGenerateEnd();
                
                if (stop_from_buffer) {
                    stop_requested_ = true;  // Update member variable for TTS callback
                    LOGI("%s Stop requested at token %d", mode_tag, current_size);
                }
                if (generate_end) {
                    LOGI("%s Generation ended (EOS/<eop>) at token %d", mode_tag, current_size);
                }
            }
            LOGI("%s Generation loop finished: current_size=%d, stop=%d, end=%d", 
                 mode_tag, current_size, stop_requested_, generate_end);
            
            if (stop_requested_) {
                LOGI("%s Inference stopped by user after %d tokens", mode_tag, current_size);
            } else {
                LOGI("%s Inference completed, generated %d tokens", mode_tag, current_size);
            }
            
            // Generate TTS audio if supported and output path is set
            // CRITICAL: Following ChatMNN pattern - directly generate audio on existing context
            // Reference: libs/mnn/apps/Android/MnnLlmChat/app/src/main/cpp/llm_session.cpp Line 325-327
            bool tts_path_ready = !tts_output_path_.empty();
            LOGI("[TTS][DEBUG][HISTORY] Audio pre-check: has_tts=%d, path_ready=%d, enable_flag=%d, stop_flag=%d",
                 has_tts_ ? 1 : 0,
                 tts_path_ready ? 1 : 0,
                 enable_audio_output_ ? 1 : 0,
                 stop_requested_ ? 1 : 0);

            if (!stop_requested_ && has_tts_ && tts_path_ready) {
                LOGI("[TTS] ========== Starting TTS Generation ==========");
                LOGI("[TTS] Generated text tokens: %d", current_size);
                
                // DEBUG: Mark TTS generation start
                LOGI("[TTS][DEBUG] Calling generateWavform() for %d text tokens...", current_size);
                
                try {
                    // Clear buffer for new generation
                    tts_audio_buffer_.clear();
                    
                    // Audio output already enabled before text generation (Line 738)
                    // Directly generate audio with accumulated TalkerEmbeds (like ChatMNN)
                    LOGI("[TTS] Calling generateWavform()...");
                    
                    // Reset first-chunk log flag for audio generation stage
                    tts_chunk_header_logged_ = false;
                    
                    // Generate audio (synchronous, callback already set in load())
                    auto tts_start = std::chrono::steady_clock::now();
                    llm_->generateWavform();
                    auto tts_end = std::chrono::steady_clock::now();
                    auto tts_elapsed_ms = std::chrono::duration_cast<std::chrono::milliseconds>(tts_end - tts_start).count();
                    
                    // Disable audio output after generation
                    enable_audio_output_ = false;
                    LOGI("[TTS] generateWavform() completed in %lld ms, audio output disabled", (long long)tts_elapsed_ms);
                    
                    // DEBUG: Mark TTS generation end
                    LOGI("[TTS][DEBUG] generateWavform() completed, elapsed=%lld ms", (long long)tts_elapsed_ms);
                    
                    // Write WAV file synchronously (Diffusion-style)
                    if (!tts_audio_buffer_.empty()) {
                        float duration_sec = tts_audio_buffer_.size() / 24000.0f;
                        LOGI("[TTS] Writing WAV file: %zu samples (%.2fs) to %s", 
                             tts_audio_buffer_.size(), duration_sec, tts_output_path_.c_str());
                        bool success = writeWavFile(tts_audio_buffer_.data(), tts_audio_buffer_.size(), tts_output_path_.c_str());
                        if (success) {
                            LOGI("[TTS] ✅ Audio generation SUCCESS: %.2fs audio saved", duration_sec);
                        } else {
                            LOGE("[TTS] ❌ Failed to write WAV file");
                            tts_output_path_.clear(); // Clear path on error
                        }
                    } else {
                        LOGE("[TTS] ❌ No audio data generated (buffer empty!)");
                        tts_output_path_.clear();
                    }
                } catch (const std::exception& e) {
                    LOGE("[TTS] ❌ Audio generation exception: %s", e.what());
                    enable_audio_output_ = false;
                    tts_output_path_.clear();
                }
                LOGI("[TTS] =========================================");
            } else {
                // TTS not executed (stopped or no path), log detailed reason and ensure flag is reset
                if (!has_tts_) {
                    LOGW("[TTS][DEBUG][HISTORY] Skipping generateWavform: has_tts_ is false");
                } else if (!tts_path_ready) {
                    LOGW("[TTS][DEBUG][HISTORY] Skipping generateWavform: output path not set");
                } else if (stop_requested_) {
                    LOGW("[TTS][DEBUG][HISTORY] Skipping generateWavform: stop_requested_ is true");
                }

                if (enable_audio_output_) {
                    enable_audio_output_ = false;
                    LOGI("[TTS] Audio output disabled (TTS skipped)");
                }
            }
            
            // Get context with statistics
            const LlmContext* context = llm_->getContext();
            if (complete_callback) {
                complete_callback(context);
            }
            
            return true;
            
        } catch (const std::exception& e) {
            LOGE("Exception during history inference: %s", e.what());
            enable_audio_output_ = false;  // Reset flag on error
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
            
            // Reset stop flags at start of inference (like ChatMNN Line 130-131)
            stop_requested_ = false;
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
            while (!stop_requested_ && !generate_end && current_size < max_new_tokens) {
                // LOGD("[MULTIMODAL] Calling llm_->generate(1), current_size=%d", current_size);  // Too verbose
                llm_->generate(1);
                current_size++;
                // LOGD("[MULTIMODAL] After generate(), current_size=%d", current_size);  // Too verbose
                
                // Check flags set by StreamBuffer and update member variable
                bool stop_from_buffer = stream_buffer.isStopRequested();
                generate_end = stream_buffer.isGenerateEnd();
                
                if (stop_from_buffer) {
                    stop_requested_ = true;  // Update member variable for TTS callback
                    LOGI("[MULTIMODAL] Stop requested at token %d", current_size);
                }
                if (generate_end) {
                    LOGI("[MULTIMODAL] Generation ended (EOS/<eop>) at token %d", current_size);
                }
            }
            LOGI("[MULTIMODAL] Generation loop finished: current_size=%d, stop=%d, end=%d", 
                 current_size, stop_requested_, generate_end);
            
            if (stop_requested_) {
                LOGI("[MULTIMODAL] Inference stopped by user after %d tokens", current_size);
            } else {
                LOGI("[MULTIMODAL] Inference completed, generated %d tokens", current_size);
                
                // Warn if suspiciously few tokens generated with multimodal input
                if (current_size < 10 && !image_paths.empty()) {
                    LOGW("[MULTIMODAL] Warning: Only %d tokens generated for image input!", current_size);
                    LOGW("[MULTIMODAL] This may indicate a vision encoder or multimodal fusion problem.");
                    LOGW("[MULTIMODAL] Consider trying a different multimodal model.");
                }
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
    
    // Multimodal inference with images and/or audio
    bool inferenceMultimodal(const std::string& prompt,
                            const std::vector<std::string>& image_paths,
                            const std::vector<std::string>& audio_paths,
                            std::function<bool(const std::string&)> token_callback,
                            std::function<void(const LlmContext*)> complete_callback) {
        if (!llm_) {
            LOGE("LLM not initialized");
            return false;
        }
        
        try {
            // Build multimodal prompt with tags
            std::string multimodal_prompt = "";
            
            // Add image tags
            if (!image_paths.empty()) {
                LOGI("[MULTIMODAL] Processing %zu images", image_paths.size());
                for (const auto& image_path : image_paths) {
                    multimodal_prompt += "<img>" + image_path + "</img>";
                    LOGI("[MULTIMODAL] Embedded image: %s", image_path.c_str());
                }
            }
            
            // Add audio tags
            if (!audio_paths.empty()) {
                LOGI("[MULTIMODAL] Processing %zu audio files", audio_paths.size());
                for (const auto& audio_path : audio_paths) {
                    multimodal_prompt += "<audio>" + audio_path + "</audio>";
                    LOGI("[MULTIMODAL] Embedded audio: %s", audio_path.c_str());
                }
            }
            
            // Append text prompt
            multimodal_prompt += prompt;
            
            LOGI("[MULTIMODAL] Final prompt length: %zu chars (images=%zu, audios=%zu)", 
                 multimodal_prompt.length(), image_paths.size(), audio_paths.size());
            
            // Use ChatMessages for history-based inference
            ChatMessages history;
            history.emplace_back("user", multimodal_prompt);
            
            // Create output stream
            StreamBuffer stream_buffer(token_callback);
            std::ostream output_stream(&stream_buffer);
            
            // Parse max_new_tokens from config
            int max_new_tokens = 2048;
            try {
                json config = json::parse(config_json_);
                if (config.contains("max_new_tokens")) {
                    max_new_tokens = config["max_new_tokens"].get<int>();
                }
            } catch (...) {
                // Use default if parsing fails
            }
            
            // Reset stop flags
            stop_requested_ = false;
            bool generate_end = false;
            
            LOGI("[MULTIMODAL] Starting inference with max_new_tokens=%d", max_new_tokens);
            
            // Initial response
            llm_->response(history, &output_stream, "<eop>", 1);
            int current_size = 1;
            
            // Generate remaining tokens
            while (!stop_requested_ && !generate_end && current_size < max_new_tokens) {
                llm_->generate(1);
                current_size++;
                
                // Check flags set by StreamBuffer
                bool stop_from_buffer = stream_buffer.isStopRequested();
                generate_end = stream_buffer.isGenerateEnd();
                
                if (stop_from_buffer) {
                    stop_requested_ = true;
                    LOGI("[MULTIMODAL] Stop requested at token %d", current_size);
                }
                if (generate_end) {
                    LOGI("[MULTIMODAL] Generation ended (EOS/<eop>) at token %d", current_size);
                }
            }
            
            LOGI("[MULTIMODAL] Generation completed: generated=%d tokens, stopped=%s", 
                 current_size, (stop_requested_ ? "by_user" : (generate_end ? "by_eos" : "by_limit")));
            
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
    
    // TTS support methods
    bool hasTTS() const {
        return has_tts_;
    }
    
    void setTtsOutputPath(const std::string& output_path) {
        tts_output_path_ = output_path;
        tts_audio_buffer_.clear();
        LOGI("[TTS] Output path set: %s", output_path.c_str());
    }
    
    std::string getTtsOutputPath() const {
        return tts_output_path_;
    }
    
    void setWavformCallback(std::function<bool(const float*, size_t, bool)> callback) {
        waveform_callback_ = std::move(callback);
        if (llm_ && has_tts_) {
            llm_->setWavformCallback(waveform_callback_);
            LOGI("[TTS] Waveform callback set (bridged to MNN)");
        }
    }
    
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

        const std::string& getDebugPreview() const {
            return debug_preview_;
        }
        
        // Get full generated text (like ChatMNN response_buffer)
        const std::string& getFullText() const {
            return full_text_;
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

                // Accumulate full text (like ChatMNN response_buffer)
                full_text_.append(completeChars);
                
                // Update debug preview with the most recent characters, keep last 256 chars
                debug_preview_.append(completeChars);
                if (debug_preview_.size() > 256) {
                    debug_preview_.erase(0, debug_preview_.size() - 256);
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

        std::function<bool(const std::string&)> callback_;
        std::string utf8Buffer_;
        bool stop_requested_;
        bool generate_end_;
        std::string debug_preview_;
        std::string full_text_;  // Accumulate full response text (like ChatMNN response_buffer)
    }; // End of StreamBuffer class

private:
    // Member variables
    std::string model_dir_;
    std::string config_json_;
    Llm* llm_ = nullptr;
    bool has_tts_;
    bool enable_audio_output_;  // CRITICAL: Like ChatMNN Line 150
    bool stop_requested_;  // CRITICAL: Like ChatMNN Line 141, global stop flag
    bool tts_chunk_header_logged_ = false; // DEBUG: first-chunk header log flag
    std::function<bool(const float*, size_t, bool)> waveform_callback_;
    std::string tts_output_path_;  // TTS output WAV file path
    std::vector<float> tts_audio_buffer_;  // TTS audio accumulator
    
    // CRITICAL: Maintain conversation history (like ChatMNN Line 77)
    // Format: vector<pair<role, content>>
    ChatMessages history_;
}; // End of MnnLlmSession class

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

JNIEXPORT jlong JNICALL
Java_com_offlineai_mnn_MnnInference_createSessionWithConfig(
    JNIEnv* env, jclass clazz,
    jstring modelDir, jstring configJsonPath, jstring runtimeConfigJson) {
    
    std::string model_dir = jstring2string(env, modelDir);
    std::string config_json_path = jstring2string(env, configJsonPath);
    std::string runtime_config_json = jstring2string(env, runtimeConfigJson);
    
    LOGI("createSessionWithConfig: modelDir=%s, configPath=%s", model_dir.c_str(), config_json_path.c_str());
    
    try {
        // Parse runtime config JSON
        json runtime_config = json::parse(runtime_config_json);
        
        // Read config.json file
        std::ifstream config_file(config_json_path);
        if (!config_file.is_open()) {
            LOGE("Failed to open config.json file: %s", config_json_path.c_str());
            return 0;
        }
        
        json config_json;
        config_file >> config_json;
        config_file.close();
        
        // Merge runtime config into config.json (runtime config takes precedence)
        for (auto& [key, value] : runtime_config.items()) {
            config_json[key] = value;
        }
        
        std::string merged_config_str = config_json.dump();
        LOGI("Merged config: %s", merged_config_str.c_str());
        
        auto* session = new MnnLlmSession(model_dir, merged_config_str);
        if (!session->load()) {
            delete session;
            LOGE("Failed to load MNN session with merged config");
            return 0;
        }
        
        LOGI("Session created successfully with merged config: %p", session);
        return reinterpret_cast<jlong>(session);
        
    } catch (const std::exception& e) {
        LOGE("Exception in createSessionWithConfig: %s", e.what());
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

JNIEXPORT jobject JNICALL
Java_com_offlineai_mnn_MnnInference_inferenceMultimodal(
    JNIEnv* env, jclass clazz,
    jlong sessionHandle, jstring prompt, jobjectArray imagePaths, jobjectArray audioPaths, jobject callback) {
    
    auto* session = reinterpret_cast<MnnLlmSession*>(sessionHandle);
    if (!session) {
        LOGE("Invalid session handle");
        return nullptr;
    }
    
    std::string prompt_str = jstring2string(env, prompt);
    std::vector<std::string> image_paths = imagePaths ? jstringArray2vector(env, imagePaths) : std::vector<std::string>();
    std::vector<std::string> audio_paths = audioPaths ? jstringArray2vector(env, audioPaths) : std::vector<std::string>();
    
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
        
        bool success = session->inferenceMultimodal(prompt_str, image_paths, audio_paths,
                                                   token_callback, complete_callback);
        
        if (!success) {
            jstring error = string2jstring(env, "Multimodal inference failed");
            env->CallVoidMethod(callback, onErrorMethod, error);
            env->DeleteLocalRef(error);
        }
        
        return stats;
        
    } catch (const std::exception& e) {
        LOGE("Exception in inferenceMultimodal: %s", e.what());
        jstring error = string2jstring(env, e.what());
        env->CallVoidMethod(callback, onErrorMethod, error);
        env->DeleteLocalRef(error);
        return nullptr;
    }
}

JNIEXPORT jobject JNICALL
Java_com_offlineai_mnn_MnnInference_inferenceWithHistory(
    JNIEnv* env, jclass clazz,
    jlong sessionHandle, jobject historyList, jobject callback) {
    
    auto* session = reinterpret_cast<MnnLlmSession*>(sessionHandle);
    if (!session) {
        LOGE("Invalid session handle");
        return nullptr;
    }
    
    // Convert Java List<Pair<String, String>> to C++ ChatMessages (ChatMNN approach)
    ChatMessages history;
    if (historyList) {
        // Get List class and methods
        jclass listClass = env->GetObjectClass(historyList);
        jmethodID sizeMethod = env->GetMethodID(listClass, "size", "()I");
        jmethodID getMethod = env->GetMethodID(listClass, "get", "(I)Ljava/lang/Object;");
        
        jint listSize = env->CallIntMethod(historyList, sizeMethod);
        LOGI("[HISTORY] Converting %d history items from Java List", listSize);
        
        // Get Pair class and field IDs (android.util.Pair)
        jclass pairClass = env->FindClass("android/util/Pair");
        if (!pairClass) {
            LOGE("[HISTORY] Failed to find android.util.Pair class");
            return nullptr;
        }
        
        jfieldID firstField = env->GetFieldID(pairClass, "first", "Ljava/lang/Object;");
        jfieldID secondField = env->GetFieldID(pairClass, "second", "Ljava/lang/Object;");
        
        if (!firstField || !secondField) {
            LOGE("[HISTORY] Failed to get Pair.first/second fields");
            return nullptr;
        }
        
        // Iterate through List and extract Pairs
        for (jint i = 0; i < listSize; i++) {
            jobject pairObj = env->CallObjectMethod(historyList, getMethod, i);
            if (!pairObj) continue;
            
            // Get Pair.first (role) and Pair.second (content)
            jobject roleObj = env->GetObjectField(pairObj, firstField);
            jobject contentObj = env->GetObjectField(pairObj, secondField);
            
            std::string role;
            std::string content;
            
            if (roleObj) {
                role = jstring2string(env, (jstring)roleObj);
                env->DeleteLocalRef(roleObj);
            }
            if (contentObj) {
                content = jstring2string(env, (jstring)contentObj);
                env->DeleteLocalRef(contentObj);
            }
            
            history.emplace_back(role, content);
            
            LOGD("[HISTORY] [%d] %s: %s...", i, role.c_str(), 
                 content.substr(0, std::min(50, (int)content.length())).c_str());
            
            env->DeleteLocalRef(pairObj);
        }
    }
    
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
        
        bool success = session->inferenceWithHistory(history, token_callback, complete_callback);
        
        if (!success) {
            jstring error = string2jstring(env, "History inference failed");
            env->CallVoidMethod(callback, onErrorMethod, error);
            env->DeleteLocalRef(error);
        }
        
        return stats;
        
    } catch (const std::exception& e) {
        LOGE("Exception in inferenceWithHistory: %s", e.what());
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

// ========== TTS (Text-to-Speech) JNI Implementation ==========

/**
 * Check if model supports TTS (has talker.mnn)
 * Java signature: public static native boolean hasTTS(long sessionHandle);
 */
JNIEXPORT jboolean JNICALL
Java_com_offlineai_mnn_MnnInference_hasTTS(
    JNIEnv* env, jclass clazz, jlong sessionHandle) {
    
    auto* session = reinterpret_cast<MnnLlmSession*>(sessionHandle);
    if (!session) {
        return JNI_FALSE;
    }
    return session->hasTTS() ? JNI_TRUE : JNI_FALSE;
}

/**
 * Set TTS waveform callback
 * Java signature: public static native boolean setWavformCallback(long sessionHandle, TtsCallback callback);
 */
JNIEXPORT jboolean JNICALL
Java_com_offlineai_mnn_MnnInference_setWavformCallback(
    JNIEnv* env, jclass clazz, jlong sessionHandle, jobject callback) {
    
    auto* session = reinterpret_cast<MnnLlmSession*>(sessionHandle);
    if (!session || !callback) {
        LOGE("[TTS] Invalid session or callback");
        return JNI_FALSE;
    }
    
    // Create global reference for callback
    jobject globalCallback = env->NewGlobalRef(callback);
    JavaVM* jvm;
    env->GetJavaVM(&jvm);
    
    // Get callback method ID
    jclass callbackClass = env->GetObjectClass(globalCallback);
    jmethodID onAudioDataMethod = env->GetMethodID(callbackClass, "onAudioData", "([FZ)Z");
    
    if (!onAudioDataMethod) {
        LOGE("[TTS] Failed to find onAudioData method");
        env->DeleteGlobalRef(globalCallback);
        return JNI_FALSE;
    }
    
    // Set C++ callback that bridges to Java
    session->setWavformCallback([jvm, globalCallback, onAudioDataMethod](
        const float* data, size_t size, bool isEnd) -> bool {
        
        JNIEnv* env = nullptr;
        bool needDetach = false;
        
        // Attach thread if needed
        if (jvm->GetEnv((void**)&env, JNI_VERSION_1_6) != JNI_OK) {
            if (jvm->AttachCurrentThread(&env, nullptr) == JNI_OK) {
                needDetach = true;
            } else {
                LOGE("[TTS] Failed to attach thread");
                return false;
            }
        }
        
        // Create float array
        jfloatArray audioDataArray = env->NewFloatArray(size);
        env->SetFloatArrayRegion(audioDataArray, 0, size, data);
        
        // Call Java callback
        jboolean result = env->CallBooleanMethod(globalCallback, onAudioDataMethod, 
                                                 audioDataArray, isEnd);
        
        // Cleanup
        env->DeleteLocalRef(audioDataArray);
        
        if (needDetach) {
            jvm->DetachCurrentThread();
        }
        
        return result == JNI_TRUE;
    });
    
    LOGI("[TTS] Waveform callback set successfully");
    return JNI_TRUE;
}

/**
 * Set TTS output file path (NEW METHOD - replaces callback for synchronous file writing)
 * Java signature: public static native void setTtsOutputPath(long sessionHandle, String outputPath);
 */
extern "C" JNIEXPORT void JNICALL
Java_com_offlineai_mnn_MnnInference_setTtsOutputPath(
    JNIEnv* env, jclass clazz, jlong sessionHandle, jstring jOutputPath) {
    
    auto* session = reinterpret_cast<MnnLlmSession*>(sessionHandle);
    if (!session) {
        LOGE("[TTS] Invalid session");
        return;
    }
    
    if (!jOutputPath) {
        session->setTtsOutputPath("");
        LOGI("[TTS] Output path cleared");
        return;
    }
    
    const char* output_path = env->GetStringUTFChars(jOutputPath, nullptr);
    session->setTtsOutputPath(std::string(output_path));
    env->ReleaseStringUTFChars(jOutputPath, output_path);
}

/**
 * Get TTS output file path
 * Java signature: public static native String getTtsOutputPath(long sessionHandle);
 */
extern "C" JNIEXPORT jstring JNICALL
Java_com_offlineai_mnn_MnnInference_getTtsOutputPath(
    JNIEnv* env, jclass clazz, jlong sessionHandle) {
    
    auto* session = reinterpret_cast<MnnLlmSession*>(sessionHandle);
    if (!session) {
        LOGE("[TTS] Invalid session");
        return nullptr;
    }
    
    std::string path = session->getTtsOutputPath();
    return path.empty() ? nullptr : env->NewStringUTF(path.c_str());
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
            // Parse memory mode and thread_num from config_json
            MNN::BackendConfig::MemoryMode memoryMode = MNN::BackendConfig::Memory_High;
            std::string memoryStr = "high";
            int threadNum = 4;  // Default: 4 threads (same as LLM default)
            
            if (!config_json_.empty()) {
                // Simple JSON parsing for "memory":"xxx"
                size_t memPos = config_json_.find("\"memory\"");
                if (memPos != std::string::npos) {
                    size_t valueStart = config_json_.find("\"", memPos + 9);
                    size_t valueEnd = config_json_.find("\"", valueStart + 1);
                    if (valueStart != std::string::npos && valueEnd != std::string::npos) {
                        memoryStr = config_json_.substr(valueStart + 1, valueEnd - valueStart - 1);
                        if (memoryStr == "low") {
                            memoryMode = MNN::BackendConfig::Memory_Low;
                        } else if (memoryStr == "normal") {
                            memoryMode = MNN::BackendConfig::Memory_Normal;
                        } else {
                            memoryMode = MNN::BackendConfig::Memory_High;
                        }
                    }
                }
                
                // Parse thread_num from config_json
                size_t threadPos = config_json_.find("\"thread_num\"");
                if (threadPos != std::string::npos) {
                    size_t colonPos = config_json_.find(":", threadPos);
                    if (colonPos != std::string::npos) {
                        size_t numStart = colonPos + 1;
                        // Skip whitespace
                        while (numStart < config_json_.length() && std::isspace(config_json_[numStart])) {
                            numStart++;
                        }
                        size_t numEnd = numStart;
                        while (numEnd < config_json_.length() && std::isdigit(config_json_[numEnd])) {
                            numEnd++;
                        }
                        if (numEnd > numStart) {
                            std::string numStr = config_json_.substr(numStart, numEnd - numStart);
                            threadNum = std::stoi(numStr);
                            LOGI("[EMBEDDING] Parsed thread_num from config: %d", threadNum);
                        }
                    }
                }
            }
            
            // Create independent Executor for Embedding
            MNN::BackendConfig bConfig;
            bConfig.precision = MNN::BackendConfig::Precision_Normal;
            bConfig.power = MNN::BackendConfig::Power_High;  // High power for big cores
            bConfig.memory = memoryMode;
            executor_ = MNN::Express::Executor::newExecutor(MNN_FORWARD_CPU, bConfig, threadNum);
            MNN::Express::ExecutorScope scope(executor_);
            LOGI("[EMBEDDING] ✅ Created independent Executor (CPU, %d threads, high power, memory=%s)", threadNum, memoryStr.c_str());
            
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
    std::shared_ptr<MNN::Express::Executor> executor_;  // Independent executor
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
        LOGI("[EMBEDDING] >>> Calling MNN txt_embedding() for %zu chars...", text_str.length());
        LOGI("[EMBEDDING] >>> Thread ID: %d, Session ptr: %p", gettid(), (void*)embedding);
        LOGI("[EMBEDDING] >>> About to enter MNN txt_embedding()...");
        
        auto embed_start = std::chrono::high_resolution_clock::now();
        auto result = embedding->txt_embedding(text_str);
        auto embed_end = std::chrono::high_resolution_clock::now();
        auto embed_ms = std::chrono::duration_cast<std::chrono::milliseconds>(embed_end - embed_start).count();
        
        LOGI("[EMBEDDING] <<< MNN txt_embedding() returned, took %lld ms", (long long)embed_ms);
        LOGI("[EMBEDDING] <<< Thread ID: %d completed", gettid());
        
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
            // Create independent Executor for Reranker (2 threads, high power)
            MNN::BackendConfig bConfig;
            bConfig.precision = MNN::BackendConfig::Precision_Normal;
            bConfig.power = MNN::BackendConfig::Power_High;  // High power for big cores
            bConfig.memory = MNN::BackendConfig::Memory_Low;
            executor_ = MNN::Express::Executor::newExecutor(MNN_FORWARD_CPU, bConfig, 2);  // 2 threads for small model
            MNN::Express::ExecutorScope scope(executor_);
            LOGI("[RERANKER] ✅ Created independent Executor (CPU, 2 threads, high power)");
            
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
    std::shared_ptr<MNN::Express::Executor> executor_;  // Independent executor
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
        
        // Debug tokenizer diagnostics for Qwen3 reranker are disabled in production to reduce overhead.
#if 0
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
#endif
        
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

// ========== NER (Named Entity Recognition) JNI Implementation ==========

/**
 * NER Session Wrapper - Uses LLM for entity extraction
 * Optimized for short, structured outputs (entity lists)
 */
class MnnNerSession {
public:
    MnnNerSession(const std::string& model_dir, const std::string& config_json)
        : model_dir_(model_dir), config_json_(config_json) {
        LOGI("[NER] Creating session: %s", model_dir.c_str());
    }
    
    ~MnnNerSession() {
        LOGI("[NER] Destroying session");
        llm_.reset();
        executor_.reset();
    }
    
    bool load() {
        try {
            // Create independent Executor for NER (2 threads, high power)
            MNN::BackendConfig bConfig;
            bConfig.precision = MNN::BackendConfig::Precision_Normal;
            bConfig.power = MNN::BackendConfig::Power_High;  // High power for big cores
            bConfig.memory = MNN::BackendConfig::Memory_Normal;
            executor_ = MNN::Express::Executor::newExecutor(MNN_FORWARD_CPU, bConfig, 2);  // 2 threads for small model
            MNN::Express::ExecutorScope scope(executor_);
            LOGI("[NER] ✅ Created independent Executor (CPU, 2 threads, high power)");
            
            // Build NER-optimized config and save to file
            std::string ner_config = buildNerConfig();
            LOGI("[NER] Config: %s", ner_config.c_str());
            
            // Save config to temporary file
            std::string config_path = model_dir_ + "/config.json";
            
            // Create LLM instance from config file
            llm_.reset(Llm::createLLM(config_path));
            if (!llm_) {
                LOGE("[NER] Failed to create LLM instance");
                return false;
            }
            
            // Apply runtime config after creation
            llm_->set_config(ner_config);
            
            // CRITICAL: Load model to initialize tokenizer and weights
            LOGI("[NER] Loading model...");
            llm_->load();
            
            LOGI("[NER] LLM instance created and loaded successfully");
            return true;
            
        } catch (const std::exception& e) {
            LOGE("[NER] Exception during load: %s", e.what());
            return false;
        }
    }
    
    /**
     * Extract entities from text using system prompt + user text
     * System prompt is cached after first use for efficiency
     */
    std::string extractEntities(const std::string& system_prompt, const std::string& text) {
        if (!llm_) {
            LOGE("[NER] LLM not initialized");
            return "";
        }
        
        try {
            MNN::Express::ExecutorScope scope(executor_);
            
            // CRITICAL: Reset history for each independent extraction
            // MNN LLM maintains internal history, clear it for independent tasks
            llm_->reset();
            LOGI("[NER] ✅ History reset for independent extraction");
            
            // Build full prompt: system_prompt + user_text
            std::string full_prompt = system_prompt + "\n\n" + text;
            LOGI("[NER] Processing text: %zu chars (system: %zu, user: %zu)", 
                 full_prompt.length(), system_prompt.length(), text.length());
            
            // Tokenize full prompt (system + user)
            auto full_tokens = llm_->tokenizer_encode(full_prompt);
            LOGI("[NER] Full prompt tokenized: %zu tokens", full_tokens.size());
            
            // Generate response
            std::string response;
            std::ostringstream oss;
            
            auto start_time = std::chrono::high_resolution_clock::now();
            LOGI("[NER] Starting response generation (max_new_tokens from config)...");
            
            // Generate with full prompt (system + user)
            llm_->response(full_tokens, &oss, nullptr, -1);  // max_new_tokens from config
            
            auto end_time = std::chrono::high_resolution_clock::now();
            auto duration_ms = std::chrono::duration_cast<std::chrono::milliseconds>(end_time - start_time).count();
            
            response = oss.str();
            
            LOGI("[NER] ✅ Extraction completed in %lld ms, response length: %zu chars", 
                 (long long)duration_ms, response.length());
            
            // Log response content for debugging
            if (response.empty()) {
                LOGW("[NER] WARNING: Empty response generated!");
            } else {
                LOGI("[NER] Response preview (first 300 chars): [%s]", 
                     response.substr(0, std::min(size_t(300), response.length())).c_str());
                if (response.length() > 300) {
                    LOGI("[NER] Response preview (last 100 chars): [%s]", 
                         response.substr(response.length() - 100).c_str());
                }
            }
            
            return response;
            
        } catch (const std::exception& e) {
            LOGE("[NER] Extraction failed: %s", e.what());
            return "";
        }
    }
    
    Llm* get() { return llm_.get(); }
    
private:
    /**
     * Build NER-optimized config (hardcoded parameters)
     */
    std::string buildNerConfig() {
        nlohmann::json config;
        
        // Parse user-provided runtime config (memory, power, precision, thread_num)
        if (!config_json_.empty()) {
            try {
                config = nlohmann::json::parse(config_json_);
            } catch (...) {
                LOGW("[NER] Failed to parse runtime config, using defaults");
            }
        }
        
        // NER-optimized parameters (hardcoded)
        config["max_new_tokens"] = 128;       // Short output for entity lists (64-128 tokens)
        config["temperature"] = 0.1;          // Low temperature for deterministic output
        config["top_p"] = 0.9;                // Nucleus sampling
        config["top_k"] = 40;                 // Top-k sampling
        
        // CRITICAL: Disable Qwen3 thinking mode (Jinja template context)
        // Reference: LocalLLMMNNHandler.java Line 881-894
        // Format: {"jinja":{"context":{"enable_thinking":false}}}
        config["jinja"]["context"]["enable_thinking"] = false;
        
        return config.dump();
    }
    
    std::string model_dir_;
    std::string config_json_;
    std::shared_ptr<MNN::Express::Executor> executor_;  // Independent executor
    std::unique_ptr<Llm> llm_;
};

// Global NER sessions map
static std::map<jlong, std::unique_ptr<MnnNerSession>> g_ner_sessions;
static std::mutex g_ner_sessions_mutex;
static jlong g_next_ner_session_handle = 1;

/**
 * Create NER session
 * Java signature: public static native long createNerWithConfig(String modelDir, String runtimeConfig);
 */
extern "C" JNIEXPORT jlong JNICALL
Java_com_offlineai_mnn_MnnInference_createNerWithConfig(
    JNIEnv* env, jclass clazz, jstring modelDir, jstring runtimeConfig) {
    
    const char* model_dir_cstr = env->GetStringUTFChars(modelDir, nullptr);
    std::string model_dir_str(model_dir_cstr);
    env->ReleaseStringUTFChars(modelDir, model_dir_cstr);
    
    std::string config_json;
    if (runtimeConfig) {
        const char* config_cstr = env->GetStringUTFChars(runtimeConfig, nullptr);
        config_json = std::string(config_cstr);
        env->ReleaseStringUTFChars(runtimeConfig, config_cstr);
    }
    
    try {
        LOGI("[NER] Creating session from: %s", model_dir_str.c_str());
        if (!config_json.empty()) {
            LOGI("[NER] Runtime config: %s", config_json.c_str());
        }
        
        auto start_time = std::chrono::high_resolution_clock::now();
        
        // Create session
        auto session = std::make_unique<MnnNerSession>(model_dir_str, config_json);
        
        // Load model
        if (!session->load()) {
            LOGE("[NER] Session load failed");
            return 0;
        }
        
        auto end_time = std::chrono::high_resolution_clock::now();
        auto duration_ms = std::chrono::duration_cast<std::chrono::milliseconds>(end_time - start_time).count();
        
        LOGI("[NER] ✓ Session created - load time: %lld ms", (long long)duration_ms);
        
        // Store session
        std::lock_guard<std::mutex> lock(g_ner_sessions_mutex);
        jlong handle = g_next_ner_session_handle++;
        g_ner_sessions[handle] = std::move(session);
        
        return handle;
        
    } catch (const std::exception& e) {
        LOGE("[NER] Exception: %s", e.what());
        return 0;
    }
}

/**
 * Extract entities from text
 * Java signature: public static native String extractEntities(long nerHandle, String systemPrompt, String text);
 */
extern "C" JNIEXPORT jstring JNICALL
Java_com_offlineai_mnn_MnnInference_extractEntities(
    JNIEnv* env, jclass clazz, jlong handle, jstring systemPrompt, jstring text) {
    
    // Get session from map
    std::lock_guard<std::mutex> lock(g_ner_sessions_mutex);
    auto it = g_ner_sessions.find(handle);
    if (it == g_ner_sessions.end()) {
        LOGE("[NER] Invalid handle: %lld", (long long)handle);
        return env->NewStringUTF("");
    }
    
    auto* session = it->second.get();
    if (!session) {
        LOGE("[NER] Session is null");
        return env->NewStringUTF("");
    }
    
    // Convert Java strings to C++
    const char* system_cstr = env->GetStringUTFChars(systemPrompt, nullptr);
    const char* text_cstr = env->GetStringUTFChars(text, nullptr);
    
    std::string system_str(system_cstr);
    std::string text_str(text_cstr);
    
    env->ReleaseStringUTFChars(systemPrompt, system_cstr);
    env->ReleaseStringUTFChars(text, text_cstr);
    
    try {
        // Extract entities
        std::string result = session->extractEntities(system_str, text_str);
        
        return env->NewStringUTF(result.c_str());
        
    } catch (const std::exception& e) {
        LOGE("[NER] Exception: %s", e.what());
        return env->NewStringUTF("");
    }
}

/**
 * Release NER session
 * Java signature: public static native void releaseNer(long nerHandle);
 */
extern "C" JNIEXPORT void JNICALL
Java_com_offlineai_mnn_MnnInference_releaseNer(
    JNIEnv* env, jclass clazz, jlong handle) {
    
    std::lock_guard<std::mutex> lock(g_ner_sessions_mutex);
    auto it = g_ner_sessions.find(handle);
    if (it != g_ner_sessions.end()) {
        g_ner_sessions.erase(it);
        LOGI("[NER] Session released: %lld", (long long)handle);
    } else {
        LOGW("[NER] Handle not found: %lld", (long long)handle);
    }
}

// ========== Diffusion (Text2Image) Support ==========

using namespace MNN::DIFFUSION;

// Struct to store diffusion creation parameters
struct DiffusionParams {
    std::string modelPath;
    DiffusionModelType modelType;
    MNNForwardType backendType;
    int memoryMode;
    int imageSize;       // Legacy: single size for square images
    int imageWidth;      // Output image width (for non-square)
    int imageHeight;     // Output image height (for non-square)
    bool textEncoderOnCPU;
    int gpuMemoryMode;   // 0=AUTO, 1=BUFFER, 2=IMAGE
    int precisionMode;   // 0=AUTO, 1=LOW(FP16), 2=NORMAL, 3=HIGH(FP32)
    int numThreads;      // CPU thread count
};

// Global map to store diffusion sessions, memory modes, and creation parameters
static std::map<jlong, std::unique_ptr<Diffusion>> g_diffusion_sessions;
static std::map<jlong, int> g_diffusion_memory_modes;
static std::map<jlong, DiffusionParams> g_diffusion_params;
static std::map<jlong, bool> g_diffusion_first_run;  // Track first run per session
static std::mutex g_diffusion_sessions_mutex;

extern "C" JNIEXPORT jlong JNICALL
Java_com_offlineai_mnn_MnnInference_createDiffusion(
    JNIEnv* env, jclass clazz, 
    jstring jModelDir, jint modelType, jint backendType, jint memoryMode, jint imageSize, jboolean textEncoderOnCPU, jstring jCachePath, jobject callback) {
    
    try {
        
        // Get model directory
        const char* modelDir = env->GetStringUTFChars(jModelDir, nullptr);
        const char* cachePath = env->GetStringUTFChars(jCachePath, nullptr);
        std::string modelDirStr(modelDir ? modelDir : "");
        std::string cachePathStr(cachePath ? cachePath : "");
        
        LOGI("[DIFFUSION] Creating diffusion session: modelDir=%s, modelType=%d, backend=%d, memory=%d, imageSize=%d, textEncoderOnCPU=%d",
             modelDirStr.c_str(), modelType, backendType, memoryMode, imageSize, (int)textEncoderOnCPU);
        LOGI("[DIFFUSION] Cache directory (backend-specific): %s", cachePathStr.c_str());
        
        // Get callback method
        jmethodID onTokenMethod = nullptr;
        if (callback != nullptr) {
            jclass callbackClass = env->GetObjectClass(callback);
            onTokenMethod = env->GetMethodID(callbackClass, "onToken", "(Ljava/lang/String;)Z");
        }
        
        // NOTE: Debug section (<debug> ... </debug>) is now managed on the Java side
        // (RagQueryManager / RagQaFragment). JNI only emits plain status lines for
        // Diffusion so that there is a single owner of debug tags.
        
        // ========== 切换工作目录到后端缓存目录 ==========
        // Java 已经创建好目录，格式: <model_path>/<backend>/.tempcache
        // chdir 后，MNN 的 fopen(".tempcache", "wb") 会直接写到这个目录
        int chdirResult = chdir(cachePathStr.c_str());
        if (chdirResult == 0) {
            char cwd[1024];
            getcwd(cwd, sizeof(cwd));
            LOGI("[DIFFUSION] ✅ Changed working directory to: %s", cwd);
            LOGI("[DIFFUSION] .tempcache will be saved here (backend-specific cache)");
        } else {
            LOGE("[DIFFUSION] ❌ Failed to chdir to: %s (errno=%d)", cachePathStr.c_str(), errno);
            LOGW("[DIFFUSION] .tempcache will use default location (may not be writable)");
        }
        
        // Validate backend type
        MNNForwardType actualBackend = static_cast<MNNForwardType>(backendType);
        LOGI("[DIFFUSION] Using user-selected backend: %d (CPU=0, OpenCL=3, Vulkan=7, NNAPI=6)", backendType);
        
        // Print "Loading models..." right before actual loading
        if (onTokenMethod) {
            jstring jmsg = env->NewStringUTF("\nLoading models...");
            env->CallBooleanMethod(callback, onTokenMethod, jmsg);
            env->DeleteLocalRef(jmsg);
        }
        
        // Note: Kernel compilation happens during first run(), not during load()
        // So we don't print "Compiling kernels" here
        
        // Create Diffusion instance via factory method (Diffusion is abstract base class)
        auto diffusion = std::unique_ptr<Diffusion>(Diffusion::createDiffusion(
            modelDirStr,
            static_cast<DiffusionModelType>(modelType),
            actualBackend,
            memoryMode,
            imageSize, imageSize,
            (bool)textEncoderOnCPU, false,
            GPU_MEMORY_AUTO, PRECISION_AUTO, CFG_MODE_AUTO, 4
        ));
        
        env->ReleaseStringUTFChars(jModelDir, modelDir);
        env->ReleaseStringUTFChars(jCachePath, cachePath);
        
        // Start loading models (this is where compilation happens if no cache)
        LOGI("[DIFFUSION] Starting diffusion->load() (backend=%d)...", actualBackend);
        
        auto start_time = std::chrono::high_resolution_clock::now();
        if (!diffusion->load()) {
            LOGE("[DIFFUSION] Failed to load diffusion model");
            LOGE("[DIFFUSION] If GPU backend is not available, Diffusion cannot run");
            LOGE("[DIFFUSION] Please ensure OpenCL or Vulkan is supported on your device");
            
            // Print error message (no closing debug tag, user will see error in Java layer)
            if (onTokenMethod) {
                jstring jmsg = env->NewStringUTF("\nFailed to load diffusion model");
                env->CallBooleanMethod(callback, onTokenMethod, jmsg);
                env->DeleteLocalRef(jmsg);
            }
            return 0;
        }
        auto end_time = std::chrono::high_resolution_clock::now();
        auto duration = std::chrono::duration_cast<std::chrono::milliseconds>(end_time - start_time).count();
        
        LOGI("[DIFFUSION] Model loaded in %.1f sec", duration / 1000.0);
        
        // Model loaded, generateImage will print success message
        
        // Store in global map with memory mode and creation parameters
        jlong handle = reinterpret_cast<jlong>(diffusion.get());
        {
            std::lock_guard<std::mutex> lock(g_diffusion_sessions_mutex);
            g_diffusion_sessions[handle] = std::move(diffusion);
            g_diffusion_memory_modes[handle] = memoryMode;
            
            // Save creation parameters for potential recreation
            DiffusionParams params;
            params.modelPath = modelDirStr;
            params.modelType = static_cast<DiffusionModelType>(modelType);
            params.backendType = actualBackend;
            params.memoryMode = memoryMode;
            params.imageSize = imageSize;
            params.textEncoderOnCPU = (bool)textEncoderOnCPU;
            params.gpuMemoryMode = 0;  // Default: AUTO (will use BUFFER)
            params.precisionMode = 0;  // Default: AUTO
            params.numThreads = 4;     // Default: 4 threads
            g_diffusion_params[handle] = params;
            g_diffusion_first_run[handle] = true;  // Mark as first run for this session
        }
        
        LOGI("[DIFFUSION] Diffusion session created: %lld", (long long)handle);
        return handle;
        
    } catch (const std::exception& e) {
        LOGE("[DIFFUSION] Failed to create diffusion session: %s", e.what());
        
        // Print exception message (no closing debug tag)
        if (callback != nullptr) {
            jclass callbackClass = env->GetObjectClass(callback);
            jmethodID onTokenMethod = env->GetMethodID(callbackClass, "onToken", "(Ljava/lang/String;)Z");
            if (onTokenMethod) {
                char buf[256];
                snprintf(buf, sizeof(buf), "\nException: %s", e.what());
                jstring jmsg = env->NewStringUTF(buf);
                env->CallBooleanMethod(callback, onTokenMethod, jmsg);
                env->DeleteLocalRef(jmsg);
            }
        }
        return 0;
    } catch (...) {
        LOGE("[DIFFUSION] Failed to create diffusion session: unknown error");
        
        // Print error message (no closing debug tag)
        if (callback != nullptr) {
            jclass callbackClass = env->GetObjectClass(callback);
            jmethodID onTokenMethod = env->GetMethodID(callbackClass, "onToken", "(Ljava/lang/String;)Z");
            if (onTokenMethod) {
                jstring jmsg = env->NewStringUTF("\nUnknown error");
                env->CallBooleanMethod(callback, onTokenMethod, jmsg);
                env->DeleteLocalRef(jmsg);
            }
        }
        return 0;
    }
}

extern "C" JNIEXPORT jlong JNICALL
Java_com_offlineai_mnn_MnnInference_createDiffusionAdvanced(
    JNIEnv* env, jclass clazz, 
    jstring jModelDir, jint modelType, jint backendType, jint memoryMode, jint imageSize, jboolean textEncoderOnCPU,
    jint gpuMemoryMode, jint precisionMode, jint numThreads, jstring jCachePath, jobject callback) {
    
    try {
        const char* modelDir = env->GetStringUTFChars(jModelDir, nullptr);
        const char* cachePath = env->GetStringUTFChars(jCachePath, nullptr);
        std::string modelDirStr(modelDir ? modelDir : "");
        std::string cachePathStr(cachePath ? cachePath : "");
        
        LOGI("[DIFFUSION] Creating diffusion session (advanced): modelDir=%s, modelType=%d, backend=%d, memory=%d, imageSize=%d, textEncoderOnCPU=%d, gpuMemMode=%d, precision=%d, numThreads=%d",
             modelDirStr.c_str(), modelType, backendType, memoryMode, imageSize, (int)textEncoderOnCPU, gpuMemoryMode, precisionMode, numThreads);
        
        jmethodID onTokenMethod = nullptr;
        if (callback != nullptr) {
            jclass callbackClass = env->GetObjectClass(callback);
            onTokenMethod = env->GetMethodID(callbackClass, "onToken", "(Ljava/lang/String;)Z");
        }
        
        int chdirResult = chdir(cachePathStr.c_str());
        if (chdirResult == 0) {
            LOGI("[DIFFUSION] Changed working directory to: %s", cachePathStr.c_str());
        } else {
            LOGE("[DIFFUSION] Failed to chdir to: %s (errno=%d)", cachePathStr.c_str(), errno);
        }
        
        MNNForwardType actualBackend = static_cast<MNNForwardType>(backendType);
        
        if (onTokenMethod) {
            jstring jmsg = env->NewStringUTF("\nLoading models...");
            env->CallBooleanMethod(callback, onTokenMethod, jmsg);
            env->DeleteLocalRef(jmsg);
        }
        
        // Create Diffusion instance with advanced GPU configuration via factory method
        auto diffusion = std::unique_ptr<Diffusion>(Diffusion::createDiffusion(
            modelDirStr,
            static_cast<DiffusionModelType>(modelType),
            actualBackend,
            memoryMode,
            imageSize, imageSize,
            (bool)textEncoderOnCPU, false,
            static_cast<DiffusionGpuMemoryMode>(gpuMemoryMode),
            static_cast<DiffusionPrecisionMode>(precisionMode),
            CFG_MODE_AUTO,
            numThreads
        ));
        
        env->ReleaseStringUTFChars(jModelDir, modelDir);
        env->ReleaseStringUTFChars(jCachePath, cachePath);
        
        LOGI("[DIFFUSION] Starting diffusion->load() (backend=%d, gpuMemMode=%d, precision=%d)...", actualBackend, gpuMemoryMode, precisionMode);
        
        auto start_time = std::chrono::high_resolution_clock::now();
        if (!diffusion->load()) {
            LOGE("[DIFFUSION] Failed to load diffusion model");
            if (onTokenMethod) {
                jstring jmsg = env->NewStringUTF("\nFailed to load model");
                env->CallBooleanMethod(callback, onTokenMethod, jmsg);
                env->DeleteLocalRef(jmsg);
            }
            return 0;
        }
        
        auto end_time = std::chrono::high_resolution_clock::now();
        auto load_duration = std::chrono::duration_cast<std::chrono::milliseconds>(end_time - start_time).count();
        LOGI("[DIFFUSION] Model loaded in %lld ms", (long long)load_duration);
        
        jlong handle = reinterpret_cast<jlong>(diffusion.get());
        {
            std::lock_guard<std::mutex> lock(g_diffusion_sessions_mutex);
            g_diffusion_sessions[handle] = std::move(diffusion);
            g_diffusion_memory_modes[handle] = memoryMode;
            
            DiffusionParams params;
            params.modelPath = modelDirStr;
            params.modelType = static_cast<DiffusionModelType>(modelType);
            params.backendType = actualBackend;
            params.memoryMode = memoryMode;
            params.imageSize = imageSize;
            params.textEncoderOnCPU = (bool)textEncoderOnCPU;
            params.gpuMemoryMode = gpuMemoryMode;
            params.precisionMode = precisionMode;
            params.numThreads = numThreads;
            g_diffusion_params[handle] = params;
            g_diffusion_first_run[handle] = true;  // Mark as first run for this session
        }
        
        LOGI("[DIFFUSION] Diffusion session created (advanced): %lld", (long long)handle);
        return handle;
        
    } catch (const std::exception& e) {
        LOGE("[DIFFUSION] Failed to create diffusion session: %s", e.what());
        return 0;
    } catch (...) {
        LOGE("[DIFFUSION] Failed to create diffusion session: unknown error");
        return 0;
    }
}

// New JNI method with separate width and height for non-square aspect ratios
extern "C" JNIEXPORT jlong JNICALL
Java_com_offlineai_mnn_MnnInference_createDiffusionWithSize(
    JNIEnv* env, jclass clazz, 
    jstring jModelDir, jint modelType, jint backendType, jint memoryMode, 
    jint imageWidth, jint imageHeight, jboolean textEncoderOnCPU,
    jint gpuMemoryMode, jint precisionMode, jint numThreads, jstring jCachePath, jobject callback) {
    
    try {
        const char* modelDir = env->GetStringUTFChars(jModelDir, nullptr);
        const char* cachePath = env->GetStringUTFChars(jCachePath, nullptr);
        std::string modelDirStr(modelDir ? modelDir : "");
        std::string cachePathStr(cachePath ? cachePath : "");
        
        LOGI("[DIFFUSION] Creating diffusion session (with size): modelDir=%s, modelType=%d, backend=%d, memory=%d, size=%dx%d, textEncoderOnCPU=%d, gpuMemMode=%d, precision=%d, numThreads=%d",
             modelDirStr.c_str(), modelType, backendType, memoryMode, imageWidth, imageHeight, (int)textEncoderOnCPU, gpuMemoryMode, precisionMode, numThreads);
        
        jmethodID onTokenMethod = nullptr;
        if (callback != nullptr) {
            jclass callbackClass = env->GetObjectClass(callback);
            onTokenMethod = env->GetMethodID(callbackClass, "onToken", "(Ljava/lang/String;)Z");
        }
        
        int chdirResult = chdir(cachePathStr.c_str());
        if (chdirResult == 0) {
            LOGI("[DIFFUSION] Changed working directory to: %s", cachePathStr.c_str());
        } else {
            LOGE("[DIFFUSION] Failed to chdir to: %s (errno=%d)", cachePathStr.c_str(), errno);
        }
        
        MNNForwardType actualBackend = static_cast<MNNForwardType>(backendType);
        
        if (onTokenMethod) {
            jstring jmsg = env->NewStringUTF("\nLoading models...");
            env->CallBooleanMethod(callback, onTokenMethod, jmsg);
            env->DeleteLocalRef(jmsg);
        }
        
        // Create Diffusion instance with separate width and height via factory method
        auto diffusion = std::unique_ptr<Diffusion>(Diffusion::createDiffusion(
            modelDirStr,
            static_cast<DiffusionModelType>(modelType),
            actualBackend,
            memoryMode,
            imageWidth, imageHeight,
            (bool)textEncoderOnCPU, false,
            static_cast<DiffusionGpuMemoryMode>(gpuMemoryMode),
            static_cast<DiffusionPrecisionMode>(precisionMode),
            CFG_MODE_AUTO,
            numThreads
        ));
        
        env->ReleaseStringUTFChars(jModelDir, modelDir);
        env->ReleaseStringUTFChars(jCachePath, cachePath);
        
        LOGI("[DIFFUSION] Starting diffusion->load() (backend=%d, size=%dx%d)...", actualBackend, imageWidth, imageHeight);
        
        auto start_time = std::chrono::high_resolution_clock::now();
        if (!diffusion->load()) {
            LOGE("[DIFFUSION] Failed to load diffusion model");
            if (onTokenMethod) {
                jstring jmsg = env->NewStringUTF("\nFailed to load model");
                env->CallBooleanMethod(callback, onTokenMethod, jmsg);
                env->DeleteLocalRef(jmsg);
            }
            return 0;
        }
        
        auto end_time = std::chrono::high_resolution_clock::now();
        auto load_duration = std::chrono::duration_cast<std::chrono::milliseconds>(end_time - start_time).count();
        LOGI("[DIFFUSION] Model loaded in %lld ms", (long long)load_duration);
        
        jlong handle = reinterpret_cast<jlong>(diffusion.get());
        {
            std::lock_guard<std::mutex> lock(g_diffusion_sessions_mutex);
            g_diffusion_sessions[handle] = std::move(diffusion);
            g_diffusion_memory_modes[handle] = memoryMode;
            
            DiffusionParams params;
            params.modelPath = modelDirStr;
            params.modelType = static_cast<DiffusionModelType>(modelType);
            params.backendType = actualBackend;
            params.memoryMode = memoryMode;
            params.imageSize = 0;  // Not used for non-square
            params.imageWidth = imageWidth;
            params.imageHeight = imageHeight;
            params.textEncoderOnCPU = (bool)textEncoderOnCPU;
            params.gpuMemoryMode = gpuMemoryMode;
            params.precisionMode = precisionMode;
            params.numThreads = numThreads;
            g_diffusion_params[handle] = params;
            g_diffusion_first_run[handle] = true;
        }
        
        LOGI("[DIFFUSION] Diffusion session created (with size %dx%d): %lld", imageWidth, imageHeight, (long long)handle);
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
    jint iterNum, jint randomSeed, jfloat cfgScale, jobject callback) {
    
    try {
        // Get diffusion instance and memory mode
        Diffusion* diffusion = nullptr;
        int memoryMode = 0;
        {
            std::lock_guard<std::mutex> lock(g_diffusion_sessions_mutex);
            auto it = g_diffusion_sessions.find(handle);
            if (it == g_diffusion_sessions.end()) {
                LOGE("[DIFFUSION] Invalid diffusion handle: %lld", (long long)handle);
                return JNI_FALSE;
            }
            diffusion = it->second.get();
            
            auto memIt = g_diffusion_memory_modes.find(handle);
            if (memIt != g_diffusion_memory_modes.end()) {
                memoryMode = memIt->second;
            }
        }
        
        // Get strings
        const char* prompt = env->GetStringUTFChars(jPrompt, nullptr);
        const char* outputPath = env->GetStringUTFChars(jOutputPath, nullptr);
        
        LOGI("[DIFFUSION] Generating image: prompt='%s', output='%s', iter=%d, seed=%d, cfg=%.2f, memoryMode=%d",
             prompt, outputPath, iterNum, randomSeed, cfgScale, memoryMode);
        
        LOGI("[DIFF_DEBUG] About to prepare callback");
        
        // Get callback class and methods
        jclass callbackClass = env->GetObjectClass(callback);
        jmethodID onProgressMethod = env->GetMethodID(callbackClass, "onProgress", "(I)Z");
        jmethodID onTokenMethod = env->GetMethodID(callbackClass, "onToken", "(Ljava/lang/String;)Z");
        
        // Track first run per session (not global static!)
        // This fixes the bug where switching models (SD1.5 -> ZImage) would incorrectly
        // treat the new session as "not first run" and try to reload with wrong cache
        bool is_first = false;
        {
            std::lock_guard<std::mutex> lock(g_diffusion_sessions_mutex);
            auto it = g_diffusion_first_run.find(handle);
            if (it != g_diffusion_first_run.end() && it->second) {
                is_first = true;
                g_diffusion_first_run[handle] = false;  // Mark as no longer first run
            }
        }
        
        // NOTE: Debug section (<debug> ... </debug>) is opened/closed on Java side.
        // JNI only emits plain status lines for Diffusion to avoid duplicate tags.
        
        // Step 2: Print model name and GPU settings (extract from current working directory)
        if (onTokenMethod) {
            char cwd[1024];
            getcwd(cwd, sizeof(cwd));
            std::string cwdStr(cwd);
            size_t lastSlash = cwdStr.find_last_of('/');
            if (lastSlash != std::string::npos) {
                cwdStr = cwdStr.substr(0, lastSlash); // Remove backend folder
                lastSlash = cwdStr.find_last_of('/');
                if (lastSlash != std::string::npos) {
                    std::string modelName = cwdStr.substr(lastSlash + 1);
                    char buf[512];
                    snprintf(buf, sizeof(buf), "\nModel: %s", modelName.c_str());
                    jstring jmsg = env->NewStringUTF(buf);
                    env->CallBooleanMethod(callback, onTokenMethod, jmsg);
                    env->DeleteLocalRef(jmsg);
                }
            }
            
            // Print GPU memory mode and precision from session params
            {
                std::lock_guard<std::mutex> lock(g_diffusion_sessions_mutex);
                auto it = g_diffusion_params.find(handle);
                if (it != g_diffusion_params.end()) {
                    const char* gpuMemModeStr = (it->second.gpuMemoryMode == 2) ? "IMAGE" : "BUFFER";
                    const char* precisionStr = "AUTO";
                    switch (it->second.precisionMode) {
                        case 1: precisionStr = "LOW(FP16)"; break;
                        case 2: precisionStr = "NORMAL"; break;
                        case 3: precisionStr = "HIGH(FP32)"; break;
                    }
                    char buf[256];
                    snprintf(buf, sizeof(buf), "\nGPU: MemMode=%s, Precision=%s", gpuMemModeStr, precisionStr);
                    jstring jmsg = env->NewStringUTF(buf);
                    env->CallBooleanMethod(callback, onTokenMethod, jmsg);
                    env->DeleteLocalRef(jmsg);
                }
            }
        }
        
        // Step 3: Check and print GPU Kernel Cache status
        if (onTokenMethod) {
            struct stat buffer;
            bool cacheExists = (stat(".tempcache", &buffer) == 0);
            
            char buf[512];
            if (cacheExists) {
                long cacheSize = buffer.st_size / 1024; // KB
                snprintf(buf, sizeof(buf), "\nGPU Kernel Cache: EXISTS (%ld KB)", cacheSize);
            } else {
                snprintf(buf, sizeof(buf), "\nGPU Kernel Cache: None");
            }
            jstring jmsg = env->NewStringUTF(buf);
            env->CallBooleanMethod(callback, onTokenMethod, jmsg);
            env->DeleteLocalRef(jmsg);
        }
        
        // Step 4: Print model status and reload if needed
        if (is_first && onTokenMethod) {
            // First generation: models just loaded successfully from createDiffusion
            jstring jmsg = env->NewStringUTF("\nModels loaded successfully!");
            env->CallBooleanMethod(callback, onTokenMethod, jmsg);
            env->DeleteLocalRef(jmsg);
        } else {
            // Subsequent runs: check if reload needed based on memory mode
            // MNN memory modes: 0=Low(省内存), 1=Enough(快速), 2=Balance(平衡)
            // In Low/Balance mode, models are freed after run(), must reload
            if (memoryMode != 1) {
                const char* modeStr = (memoryMode == 0) ? "Low" : "Balance";
                
                // Check if kernel cache exists
                struct stat buffer;
                bool cacheExists = (stat(".tempcache", &buffer) == 0);
                
                if (!cacheExists) {
                    // No cache yet: release old session to save cache, then recreate
                    if (onTokenMethod) {
                        char buf[256];
                        snprintf(buf, sizeof(buf), "\nNo kernel cache detected, saving for next run (Memory: %s)...", modeStr);
                        jstring jmsg = env->NewStringUTF(buf);
                        env->CallBooleanMethod(callback, onTokenMethod, jmsg);
                        env->DeleteLocalRef(jmsg);
                    }
                    
                    LOGI("[DIFFUSION] No cache, releasing to save");
                    
                    // Get creation parameters before erasing
                    DiffusionParams params;
                    {
                        std::lock_guard<std::mutex> lock(g_diffusion_sessions_mutex);
                        auto paramIt = g_diffusion_params.find(handle);
                        if (paramIt != g_diffusion_params.end()) {
                            params = paramIt->second;
                        }
                    }
                    
                    // Release old session (saves cache)
                    {
                        std::lock_guard<std::mutex> lock(g_diffusion_sessions_mutex);
                        g_diffusion_sessions.erase(handle);
                        g_diffusion_memory_modes.erase(handle);
                        g_diffusion_params.erase(handle);
                        g_diffusion_first_run.erase(handle);
                    }
                    LOGI("[DIFFUSION] Old session released, cache saved");
                    
                    // Wait for cache to be written
                    usleep(100000); // 100ms
                    
                    // Create new session
                    if (onTokenMethod) {
                        jstring jmsg = env->NewStringUTF("\nReloading models...");
                        env->CallBooleanMethod(callback, onTokenMethod, jmsg);
                        env->DeleteLocalRef(jmsg);
                    }
                    
                    // CRITICAL: Use factory method with all parameters to preserve GPU/precision settings
                    auto new_diffusion = std::unique_ptr<Diffusion>(Diffusion::createDiffusion(
                        params.modelPath,
                        params.modelType,
                        params.backendType,
                        params.memoryMode,
                        params.imageSize, params.imageSize,
                        params.textEncoderOnCPU, false,
                        static_cast<DiffusionGpuMemoryMode>(params.gpuMemoryMode),
                        static_cast<DiffusionPrecisionMode>(params.precisionMode),
                        CFG_MODE_AUTO,
                        params.numThreads
                    ));
                    new_diffusion->load();
                    
                    // Update global map
                    {
                        std::lock_guard<std::mutex> lock(g_diffusion_sessions_mutex);
                        g_diffusion_sessions[handle] = std::move(new_diffusion);
                        g_diffusion_memory_modes[handle] = memoryMode;
                        g_diffusion_params[handle] = params;
                        g_diffusion_first_run[handle] = false;  // Already loaded, not first run
                        diffusion = g_diffusion_sessions[handle].get();
                    }
                    
                    if (onTokenMethod) {
                        jstring jmsg = env->NewStringUTF("\nModels reloaded (cache will be faster next time)!");
                        env->CallBooleanMethod(callback, onTokenMethod, jmsg);
                        env->DeleteLocalRef(jmsg);
                    }
                } else {
                    // Cache exists: just reload (fast, 3-4s)
                    if (onTokenMethod) {
                        char buf[256];
                        long cacheSize = buffer.st_size / 1024; // KB
                        snprintf(buf, sizeof(buf), "\nReloading models (Cache: %ld KB, Memory: %s)...", cacheSize, modeStr);
                        jstring jmsg = env->NewStringUTF(buf);
                        env->CallBooleanMethod(callback, onTokenMethod, jmsg);
                        env->DeleteLocalRef(jmsg);
                    }
                    
                    LOGI("[DIFFUSION] Cache exists, fast reload");
                    diffusion->load();
                    
                    if (onTokenMethod) {
                        jstring jmsg = env->NewStringUTF("\nModels reloaded (fast with cache)!");
                        env->CallBooleanMethod(callback, onTokenMethod, jmsg);
                        env->DeleteLocalRef(jmsg);
                    }
                }
            } else {
                // Enough mode: models stay in memory
                if (onTokenMethod) {
                    char buf[128];
                    snprintf(buf, sizeof(buf), "\nStatus: Ready (Memory: Enough, models cached)");
                    jstring jmsg = env->NewStringUTF(buf);
                    env->CallBooleanMethod(callback, onTokenMethod, jmsg);
                    env->DeleteLocalRef(jmsg);
                }
            }
        }
        
        // Step 5: Print "Preparing..." with model-specific parameters
        if (onTokenMethod) {
            char buf[512];
            // Get model type from params
            DiffusionModelType modelType = STABLE_DIFFUSION_1_5;
            {
                std::lock_guard<std::mutex> lock(g_diffusion_sessions_mutex);
                auto it = g_diffusion_params.find(handle);
                if (it != g_diffusion_params.end()) {
                    modelType = it->second.modelType;
                }
            }
            
            // Use user-provided CFG scale for both model types
            const char* schedulerName = (modelType == STABLE_DIFFUSION_ZIMAGE) ? "FlowMatch-Euler" : "PLMS";
            snprintf(buf, sizeof(buf), "\nPreparing: Steps=%d, CFG=%.2f, Scheduler=%s, Seed=%s", 
                     iterNum, cfgScale, schedulerName, 
                     randomSeed < 0 ? "Random" : std::to_string(randomSeed).c_str());
            jstring jmsg = env->NewStringUTF(buf);
            env->CallBooleanMethod(callback, onTokenMethod, jmsg);
            env->DeleteLocalRef(jmsg);
        }
        
        LOGI("[DIFF_DEBUG] Callback prepared, creating lambda");
        
        // Progress tracking variables
        int total_steps = iterNum;
        int last_step = 0;
        bool unet_started = false;
        bool text_encoder_printed = false;
        
        // Track peak RSS memory during inference
        long peakRssKB = getCurrentRssKB();
        long initialRssKB = peakRssKB;
        
        std::function<void(int)> progressCallback = [env, callback, onProgressMethod, onTokenMethod, total_steps, &last_step, &unet_started, &text_encoder_printed, &peakRssKB](int progress) {
            // Sample current RSS and update peak
            long currentRss = getCurrentRssKB();
            if (currentRss > peakRssKB) {
                peakRssKB = currentRss;
            }
            LOGD("[DIFF_DEBUG] Progress callback invoked: %d%%", progress);
            
            // Send detailed progress to UI via onToken
            if (onTokenMethod) {
                char buf[256];
                
                // Text Encoder完成 + UNet开始 (progress >= 10% for first time)
                // NOTE: MNN may skip 0-10% callbacks, so check on first >= 10% callback
                if (progress >= 10 && !unet_started) {
                    snprintf(buf, sizeof(buf), "\nText Encoder: done\nUNet Steps(%d): ", total_steps);
                    last_step = 0;
                    unet_started = true;
                    text_encoder_printed = true;
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
                    snprintf(buf, sizeof(buf), "\nVAE Decoder: done");
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
            
            // Also call standard progress callback for UI progress bar
            if (onProgressMethod) {
                env->CallBooleanMethod(callback, onProgressMethod, progress);
            }
        };
        
        LOGI("[DIFF_DEBUG] ========================================");
        LOGI("[DIFF_DEBUG] ABOUT TO CALL diffusion->run()");
        LOGI("[DIFF_DEBUG] This will call: tokenizer->encode → text_encoder → unet → vae_decoder");
        LOGI("[DIFF_DEBUG] ========================================");
        
        // Run diffusion
        auto start = std::chrono::high_resolution_clock::now();
        bool success = diffusion->run(
            std::string(prompt),
            std::string(outputPath),
            iterNum,
            randomSeed,
            cfgScale,
            progressCallback
        );
        
        LOGI("[DIFF_DEBUG] diffusion->run() RETURNED");
        auto end = std::chrono::high_resolution_clock::now();
        auto duration = std::chrono::duration_cast<std::chrono::milliseconds>(end - start).count();
        
        LOGD("[DIFF_DEBUG] Releasing strings");
        env->ReleaseStringUTFChars(jPrompt, prompt);
        env->ReleaseStringUTFChars(jOutputPath, outputPath);
        
        if (success) {
            LOGI("[DIFFUSION] Image generated successfully in %lld ms", (long long)duration);
            LOGI("[DIFF_DEBUG] SUCCESS - Total time: %lld ms", (long long)duration);
            
            // Final RSS sample after inference completes
            long finalRssKB = getCurrentRssKB();
            if (finalRssKB > peakRssKB) {
                peakRssKB = finalRssKB;
            }
            
            // Calculate inference memory delta (peak - initial)
            long inferenceMemDeltaKB = peakRssKB - initialRssKB;
            
            LOGI("[DIFFUSION] Memory stats: initialRSS=%.1fMB, peakRSS=%.1fMB, delta=%.1fMB", 
                 initialRssKB / 1024.0, peakRssKB / 1024.0, inferenceMemDeltaKB / 1024.0);
            
            // Send completion info to UI
            if (onTokenMethod) {
                char buf[512];
                snprintf(buf, sizeof(buf), "\nCompleted (%.1fs)", duration / 1000.0);
                jstring jmsg = env->NewStringUTF(buf);
                env->CallBooleanMethod(callback, onTokenMethod, jmsg);
                env->DeleteLocalRef(jmsg);
                
                // Send memory stats: peak RSS during inference (actual physical memory used)
                if (peakRssKB > 0) {
                    snprintf(buf, sizeof(buf), "[MEMORY_STATS:peak=%.1f,rss=%.1f]", 
                             peakRssKB / 1024.0, finalRssKB / 1024.0);
                    jmsg = env->NewStringUTF(buf);
                    env->CallBooleanMethod(callback, onTokenMethod, jmsg);
                    env->DeleteLocalRef(jmsg);
                }
            }
        } else {
            LOGE("[DIFFUSION] Image generation failed");
            LOGE("[DIFF_DEBUG] FAILED");
            
            if (onTokenMethod) {
                jstring jmsg = env->NewStringUTF("\nGeneration failed");
                env->CallBooleanMethod(callback, onTokenMethod, jmsg);
                env->DeleteLocalRef(jmsg);
            }
        }
        
        return success ? JNI_TRUE : JNI_FALSE;
        
    } catch (const std::exception& e) {
        LOGE("[DIFFUSION] Failed to generate image: %s", e.what());
        
        // Send exception info to UI
        jclass callbackClass = env->GetObjectClass(callback);
        jmethodID onTokenMethod = env->GetMethodID(callbackClass, "onToken", "(Ljava/lang/String;)Z");
        if (onTokenMethod) {
            char buf[256];
            snprintf(buf, sizeof(buf), "\nException: %s", e.what());
            jstring jmsg = env->NewStringUTF(buf);
            env->CallBooleanMethod(callback, onTokenMethod, jmsg);
            env->DeleteLocalRef(jmsg);
        }
        return JNI_FALSE;
    } catch (...) {
        LOGE("[DIFFUSION] Failed to generate image: unknown error");
        
        // Send error info to UI
        jclass callbackClass = env->GetObjectClass(callback);
        jmethodID onTokenMethod = env->GetMethodID(callbackClass, "onToken", "(Ljava/lang/String;)Z");
        if (onTokenMethod) {
            jstring jmsg = env->NewStringUTF("\nUnknown error");
            env->CallBooleanMethod(callback, onTokenMethod, jmsg);
            env->DeleteLocalRef(jmsg);
        }
        return JNI_FALSE;
    }
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_offlineai_mnn_MnnInference_generateImageWithInput(
    JNIEnv* env, jclass clazz,
    jlong handle, jstring jPrompt, jstring jOutputPath, 
    jint iterNum, jint randomSeed, jfloat cfgScale, jstring jInputImagePath, jobject callback) {
    
    try {
        // Get diffusion instance and memory mode
        Diffusion* diffusion = nullptr;
        int memoryMode = 0;
        {
            std::lock_guard<std::mutex> lock(g_diffusion_sessions_mutex);
            auto it = g_diffusion_sessions.find(handle);
            if (it == g_diffusion_sessions.end()) {
                LOGE("[DIFFUSION] Invalid diffusion handle: %lld", (long long)handle);
                return JNI_FALSE;
            }
            diffusion = it->second.get();
            
            auto memIt = g_diffusion_memory_modes.find(handle);
            if (memIt != g_diffusion_memory_modes.end()) {
                memoryMode = memIt->second;
            }
        }
        
        // Get strings
        const char* prompt = env->GetStringUTFChars(jPrompt, nullptr);
        const char* outputPath = env->GetStringUTFChars(jOutputPath, nullptr);
        const char* inputImagePath = jInputImagePath ? env->GetStringUTFChars(jInputImagePath, nullptr) : "";
        
        // Determine mode based on input image
        std::string inputImageStr = inputImagePath ? std::string(inputImagePath) : "";
        bool isEditMode = !inputImageStr.empty();
        const char* modeStr = isEditMode ? "Edit" : "T2I";
        
        LOGI("[DIFFUSION] Generating image (%s mode): prompt='%s', output='%s', input='%s', iter=%d, seed=%d, cfg=%.2f, memoryMode=%d",
             modeStr, prompt, outputPath, inputImageStr.c_str(), iterNum, randomSeed, cfgScale, memoryMode);
        
        // Get callback class and methods
        jclass callbackClass = env->GetObjectClass(callback);
        jmethodID onProgressMethod = env->GetMethodID(callbackClass, "onProgress", "(I)Z");
        jmethodID onTokenMethod = env->GetMethodID(callbackClass, "onToken", "(Ljava/lang/String;)Z");
        
        // Track first run per session
        bool is_first = false;
        {
            std::lock_guard<std::mutex> lock(g_diffusion_sessions_mutex);
            auto it = g_diffusion_first_run.find(handle);
            if (it != g_diffusion_first_run.end() && it->second) {
                is_first = true;
                g_diffusion_first_run[handle] = false;
            }
        }
        
        // Print model info and mode
        if (onTokenMethod) {
            char cwd[1024];
            getcwd(cwd, sizeof(cwd));
            std::string cwdStr(cwd);
            size_t lastSlash = cwdStr.find_last_of('/');
            if (lastSlash != std::string::npos) {
                cwdStr = cwdStr.substr(0, lastSlash);
                lastSlash = cwdStr.find_last_of('/');
                if (lastSlash != std::string::npos) {
                    std::string modelName = cwdStr.substr(lastSlash + 1);
                    char buf[512];
                    snprintf(buf, sizeof(buf), "\nModel: %s (%s mode)", modelName.c_str(), modeStr);
                    jstring jmsg = env->NewStringUTF(buf);
                    env->CallBooleanMethod(callback, onTokenMethod, jmsg);
                    env->DeleteLocalRef(jmsg);
                }
            }
            
            // Print GPU settings
            {
                std::lock_guard<std::mutex> lock(g_diffusion_sessions_mutex);
                auto it = g_diffusion_params.find(handle);
                if (it != g_diffusion_params.end()) {
                    const char* gpuMemModeStr = (it->second.gpuMemoryMode == 2) ? "IMAGE" : "BUFFER";
                    const char* precisionStr = "AUTO";
                    switch (it->second.precisionMode) {
                        case 1: precisionStr = "LOW(FP16)"; break;
                        case 2: precisionStr = "NORMAL"; break;
                        case 3: precisionStr = "HIGH(FP32)"; break;
                    }
                    char buf[256];
                    snprintf(buf, sizeof(buf), "\nGPU: MemMode=%s, Precision=%s", gpuMemModeStr, precisionStr);
                    jstring jmsg = env->NewStringUTF(buf);
                    env->CallBooleanMethod(callback, onTokenMethod, jmsg);
                    env->DeleteLocalRef(jmsg);
                }
            }
        }
        
        // Check GPU cache status (reuse existing logic)
        if (onTokenMethod) {
            struct stat buffer;
            bool cacheExists = (stat(".tempcache", &buffer) == 0);
            
            char buf[512];
            if (cacheExists) {
                long cacheSize = buffer.st_size / 1024;
                snprintf(buf, sizeof(buf), "\nGPU Kernel Cache: EXISTS (%ld KB)", cacheSize);
            } else {
                snprintf(buf, sizeof(buf), "\nGPU Kernel Cache: None");
            }
            jstring jmsg = env->NewStringUTF(buf);
            env->CallBooleanMethod(callback, onTokenMethod, jmsg);
            env->DeleteLocalRef(jmsg);
        }
        
        // Model reload logic (same as original generateImage)
        if (is_first && onTokenMethod) {
            jstring jmsg = env->NewStringUTF("\nModels loaded successfully!");
            env->CallBooleanMethod(callback, onTokenMethod, jmsg);
            env->DeleteLocalRef(jmsg);
        } else {
            if (memoryMode != 1) {
                const char* modeStrMem = (memoryMode == 0) ? "Low" : "Balance";
                struct stat buffer;
                bool cacheExists = (stat(".tempcache", &buffer) == 0);
                
                if (!cacheExists) {
                    if (onTokenMethod) {
                        char buf[256];
                        snprintf(buf, sizeof(buf), "\nNo kernel cache detected, saving for next run (Memory: %s)...", modeStrMem);
                        jstring jmsg = env->NewStringUTF(buf);
                        env->CallBooleanMethod(callback, onTokenMethod, jmsg);
                        env->DeleteLocalRef(jmsg);
                    }
                    
                    DiffusionParams params;
                    {
                        std::lock_guard<std::mutex> lock(g_diffusion_sessions_mutex);
                        auto paramIt = g_diffusion_params.find(handle);
                        if (paramIt != g_diffusion_params.end()) {
                            params = paramIt->second;
                        }
                    }
                    
                    {
                        std::lock_guard<std::mutex> lock(g_diffusion_sessions_mutex);
                        g_diffusion_sessions.erase(handle);
                        g_diffusion_memory_modes.erase(handle);
                        g_diffusion_params.erase(handle);
                        g_diffusion_first_run.erase(handle);
                    }
                    
                    usleep(100000);
                    
                    if (onTokenMethod) {
                        jstring jmsg = env->NewStringUTF("\nReloading models...");
                        env->CallBooleanMethod(callback, onTokenMethod, jmsg);
                        env->DeleteLocalRef(jmsg);
                    }
                    
                    auto new_diffusion = std::unique_ptr<Diffusion>(Diffusion::createDiffusion(
                        params.modelPath,
                        params.modelType,
                        params.backendType,
                        params.memoryMode,
                        params.imageSize, params.imageSize,
                        params.textEncoderOnCPU, false,
                        static_cast<DiffusionGpuMemoryMode>(params.gpuMemoryMode),
                        static_cast<DiffusionPrecisionMode>(params.precisionMode),
                        CFG_MODE_AUTO,
                        params.numThreads
                    ));
                    new_diffusion->load();
                    
                    {
                        std::lock_guard<std::mutex> lock(g_diffusion_sessions_mutex);
                        g_diffusion_sessions[handle] = std::move(new_diffusion);
                        g_diffusion_memory_modes[handle] = memoryMode;
                        g_diffusion_params[handle] = params;
                        g_diffusion_first_run[handle] = false;
                        diffusion = g_diffusion_sessions[handle].get();
                    }
                    
                    if (onTokenMethod) {
                        jstring jmsg = env->NewStringUTF("\nModels reloaded (cache will be faster next time)!");
                        env->CallBooleanMethod(callback, onTokenMethod, jmsg);
                        env->DeleteLocalRef(jmsg);
                    }
                } else {
                    if (onTokenMethod) {
                        char buf[256];
                        long cacheSize = buffer.st_size / 1024;
                        snprintf(buf, sizeof(buf), "\nReloading models (Cache: %ld KB, Memory: %s)...", cacheSize, modeStrMem);
                        jstring jmsg = env->NewStringUTF(buf);
                        env->CallBooleanMethod(callback, onTokenMethod, jmsg);
                        env->DeleteLocalRef(jmsg);
                    }
                    
                    diffusion->load();
                    
                    if (onTokenMethod) {
                        jstring jmsg = env->NewStringUTF("\nModels reloaded (fast with cache)!");
                        env->CallBooleanMethod(callback, onTokenMethod, jmsg);
                        env->DeleteLocalRef(jmsg);
                    }
                }
            } else {
                if (onTokenMethod) {
                    jstring jmsg = env->NewStringUTF("\nStatus: Ready (Memory: Enough, models cached)");
                    env->CallBooleanMethod(callback, onTokenMethod, jmsg);
                    env->DeleteLocalRef(jmsg);
                }
            }
        }
        
        // Print preparation info
        if (onTokenMethod) {
            char buf[512];
            DiffusionModelType modelType = STABLE_DIFFUSION_1_5;
            {
                std::lock_guard<std::mutex> lock(g_diffusion_sessions_mutex);
                auto it = g_diffusion_params.find(handle);
                if (it != g_diffusion_params.end()) {
                    modelType = it->second.modelType;
                }
            }
            
            const char* schedulerName = (modelType == STABLE_DIFFUSION_ZIMAGE || modelType == LONGCAT_IMAGE_EDIT) ? "FlowMatch-Euler" : "PLMS";
            snprintf(buf, sizeof(buf), "\nPreparing: Steps=%d, CFG=%.2f, Scheduler=%s, Seed=%s", 
                     iterNum, cfgScale, schedulerName, 
                     randomSeed < 0 ? "Random" : std::to_string(randomSeed).c_str());
            jstring jmsg = env->NewStringUTF(buf);
            env->CallBooleanMethod(callback, onTokenMethod, jmsg);
            env->DeleteLocalRef(jmsg);
        }
        
        // Progress tracking
        int total_steps = iterNum;
        int last_step = 0;
        bool unet_started = false;
        bool text_encoder_printed = false;
        long peakRssKB = getCurrentRssKB();
        long initialRssKB = peakRssKB;
        
        std::function<void(int)> progressCallback = [env, callback, onProgressMethod, onTokenMethod, total_steps, &last_step, &unet_started, &text_encoder_printed, &peakRssKB](int progress) {
            long currentRss = getCurrentRssKB();
            if (currentRss > peakRssKB) {
                peakRssKB = currentRss;
            }
            
            if (onTokenMethod) {
                char buf[256];
                
                if (progress >= 10 && !unet_started) {
                    snprintf(buf, sizeof(buf), "\nText Encoder: done\nUNet Steps(%d): ", total_steps);
                    last_step = 0;
                    unet_started = true;
                    text_encoder_printed = true;
                }
                else if (progress >= 10 && progress < 95 && unet_started) {
                    int current_step = ((progress - 10) * total_steps) / 85 + 1;
                    if (current_step > last_step && current_step <= total_steps) {
                        snprintf(buf, sizeof(buf), "%d..", current_step);
                        last_step = current_step;
                        if (current_step == total_steps) {
                            snprintf(buf, sizeof(buf), "%d\nUNet: done\nVAE Decoder: generating...", total_steps);
                            unet_started = false;
                        }
                    } else {
                        buf[0] = '\0';
                    }
                }
                else if (progress >= 95 && progress < 100) {
                    if (unet_started) {
                        snprintf(buf, sizeof(buf), "%d\nUNet: done\nVAE Decoder: generating...", total_steps);
                        unet_started = false;
                    } else {
                        buf[0] = '\0';
                    }
                }
                else if (progress == 100) {
                    snprintf(buf, sizeof(buf), "\nVAE Decoder: done");
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
            
            if (onProgressMethod) {
                env->CallBooleanMethod(callback, onProgressMethod, progress);
            }
        };
        
        // Run diffusion with input image path
        auto start = std::chrono::high_resolution_clock::now();
        bool success = diffusion->run(
            std::string(prompt),
            std::string(outputPath),
            iterNum,
            randomSeed,
            cfgScale,
            progressCallback,
            inputImageStr  // Pass input image path (empty for T2I, path for Edit)
        );
        
        auto end = std::chrono::high_resolution_clock::now();
        auto duration = std::chrono::duration_cast<std::chrono::milliseconds>(end - start).count();
        
        // Release strings
        env->ReleaseStringUTFChars(jPrompt, prompt);
        env->ReleaseStringUTFChars(jOutputPath, outputPath);
        if (jInputImagePath) {
            env->ReleaseStringUTFChars(jInputImagePath, inputImagePath);
        }
        
        if (success) {
            LOGI("[DIFFUSION] Image generated successfully in %lld ms (%s mode)", (long long)duration, modeStr);
            
            long finalRssKB = getCurrentRssKB();
            if (finalRssKB > peakRssKB) {
                peakRssKB = finalRssKB;
            }
            
            long inferenceMemDeltaKB = peakRssKB - initialRssKB;
            LOGI("[DIFFUSION] Memory stats: initialRSS=%.1fMB, peakRSS=%.1fMB, delta=%.1fMB", 
                 initialRssKB / 1024.0, peakRssKB / 1024.0, inferenceMemDeltaKB / 1024.0);
            
            if (onTokenMethod) {
                char buf[512];
                snprintf(buf, sizeof(buf), "\nCompleted (%.1fs)", duration / 1000.0);
                jstring jmsg = env->NewStringUTF(buf);
                env->CallBooleanMethod(callback, onTokenMethod, jmsg);
                env->DeleteLocalRef(jmsg);
                
                if (peakRssKB > 0) {
                    snprintf(buf, sizeof(buf), "[MEMORY_STATS:peak=%.1f,rss=%.1f]", 
                             peakRssKB / 1024.0, finalRssKB / 1024.0);
                    jmsg = env->NewStringUTF(buf);
                    env->CallBooleanMethod(callback, onTokenMethod, jmsg);
                    env->DeleteLocalRef(jmsg);
                }
            }
        } else {
            LOGE("[DIFFUSION] Image generation failed (%s mode)", modeStr);
            
            if (onTokenMethod) {
                jstring jmsg = env->NewStringUTF("\nGeneration failed");
                env->CallBooleanMethod(callback, onTokenMethod, jmsg);
                env->DeleteLocalRef(jmsg);
            }
        }
        
        return success ? JNI_TRUE : JNI_FALSE;
        
    } catch (const std::exception& e) {
        LOGE("[DIFFUSION] Failed to generate image: %s", e.what());
        
        jclass callbackClass = env->GetObjectClass(callback);
        jmethodID onTokenMethod = env->GetMethodID(callbackClass, "onToken", "(Ljava/lang/String;)Z");
        if (onTokenMethod) {
            char buf[256];
            snprintf(buf, sizeof(buf), "\nException: %s", e.what());
            jstring jmsg = env->NewStringUTF(buf);
            env->CallBooleanMethod(callback, onTokenMethod, jmsg);
            env->DeleteLocalRef(jmsg);
        }
        return JNI_FALSE;
    } catch (...) {
        LOGE("[DIFFUSION] Failed to generate image: unknown error");
        
        jclass callbackClass = env->GetObjectClass(callback);
        jmethodID onTokenMethod = env->GetMethodID(callbackClass, "onToken", "(Ljava/lang/String;)Z");
        if (onTokenMethod) {
            jstring jmsg = env->NewStringUTF("\nUnknown error");
            env->CallBooleanMethod(callback, onTokenMethod, jmsg);
            env->DeleteLocalRef(jmsg);
        }
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
        g_diffusion_memory_modes.erase(handle);
        g_diffusion_params.erase(handle);
        g_diffusion_first_run.erase(handle);  // Clean up first_run tracking
        LOGI("[DIFFUSION] Diffusion session released: %lld", (long long)handle);
    } else {
        LOGW("[DIFFUSION] Diffusion handle not found: %lld", (long long)handle);
    }
}

// ========== ASR (Automatic Speech Recognition) ==========
// NOTE: ASR functionality has been moved to sherpa-mnn-jni module
// MnnInference.java still declares these methods for compatibility,
// but they will be implemented by sherpa-mnn-jni module

// ========== TTS (Text-to-Speech) - External Models ==========

// Global TTS session storage
static std::unordered_map<jlong, std::unique_ptr<MnnLlmSession>> g_tts_sessions;
static std::mutex g_tts_sessions_mutex;

/**
 * Create external TTS session
 * Java signature: public static native long createTtsSession(String modelDir, String configJson);
 * 
 * CRITICAL FIX: External TTS models should NOT use Llm::createLLM()
 * ================================================================
 * Problem: Llm::createLLM() expects LLM-specific files (embeddings_bf16.bin, llm.mnn)
 *          which don't exist in external TTS models (e.g., bert-vits2-MNN)
 * 
 * Root Cause: MnnLlmSession::load() calls Llm::createLLM() which has hardcoded file checks
 *             in llmconfig.hpp (Line 299: embedding_file(), Line 291: lm_model())
 * 
 * Solution: For now, we accept that external TTS loading will fail with these errors.
 *           The proper fix requires either:
 *           1. Creating a separate TTS-specific loading class (not reusing MnnLlmSession)
 *           2. Modifying MNN's llmconfig.hpp to make file checks optional
 *           3. Using a different MNN API for TTS models
 * 
 * Current Status: External TTS loading will show errors in logcat but won't crash.
 *                 Java layer will detect failure and mark externalTtsLoadFailed=true.
 * 
 * TODO: Implement proper TTS-specific loading logic that doesn't rely on LLM infrastructure
 */
extern "C" JNIEXPORT jlong JNICALL
Java_com_offlineai_mnn_MnnInference_createTtsSession(
    JNIEnv* env, jclass clazz, jstring modelDir, jstring configJson) {
    
    try {
        const char* model_dir_cstr = env->GetStringUTFChars(modelDir, nullptr);
        std::string model_dir(model_dir_cstr);
        env->ReleaseStringUTFChars(modelDir, model_dir_cstr);
        
        const char* config_json_cstr = env->GetStringUTFChars(configJson, nullptr);
        std::string config_json(config_json_cstr);
        env->ReleaseStringUTFChars(configJson, config_json_cstr);
        
        LOGI("[TTS] Creating external TTS session from: %s", model_dir.c_str());
        LOGW("[TTS] WARNING: External TTS uses LLM loading logic, may show file not found errors");
        LOGW("[TTS] This is a known limitation - errors are expected for non-LLM TTS models");
        
        // Create TTS session (reuse MnnLlmSession infrastructure)
        // NOTE: This will fail for external TTS models that don't have LLM structure
        auto session = std::make_unique<MnnLlmSession>(model_dir, config_json);
        if (!session->load()) {
            LOGE("[TTS] Failed to load TTS model");
            LOGE("[TTS] Check logcat for MNN errors about missing files");
            return 0;
        }
        
        jlong handle = reinterpret_cast<jlong>(session.get());
        
        std::lock_guard<std::mutex> lock(g_tts_sessions_mutex);
        g_tts_sessions[handle] = std::move(session);
        
        LOGI("[TTS] External TTS session created: %lld", (long long)handle);
        return handle;
        
    } catch (const std::exception& e) {
        LOGE("[TTS] Exception creating TTS session: %s", e.what());
        return 0;
    }
}

/**
 * Generate TTS audio from text
 * Java signature: public static native boolean generateTts(long ttsHandle, String text);
 */
extern "C" JNIEXPORT jboolean JNICALL
Java_com_offlineai_mnn_MnnInference_generateTts(
    JNIEnv* env, jclass clazz, jlong handle, jstring text) {
    
    std::lock_guard<std::mutex> lock(g_tts_sessions_mutex);
    auto it = g_tts_sessions.find(handle);
    if (it == g_tts_sessions.end()) {
        LOGE("[TTS] Invalid TTS handle: %lld", (long long)handle);
        return JNI_FALSE;
    }
    
    try {
        const char* text_cstr = env->GetStringUTFChars(text, nullptr);
        std::string text_str(text_cstr);
        env->ReleaseStringUTFChars(text, text_cstr);
        
        LOGI("[TTS] Generating audio for text: %s", text_str.c_str());
        
        auto* session = it->second.get();
        
        // Enable audio output flag (like native TTS)
        session->enableAudioOutput(true);
        
        // Perform text inference to generate TTS
        // The TTS model will process text and generate audio via generateWavform()
        bool success = session->inference(text_str, 
            [](const std::string& token) -> bool {
                // Token callback (not used for TTS)
                return true;
            },
            [](const LlmContext* ctx) {
                // Complete callback (not used for TTS)
            }
        );
        
        // Disable audio output after generation
        session->enableAudioOutput(false);
        
        if (success) {
            LOGI("[TTS] Audio generation completed successfully");
            return JNI_TRUE;
        } else {
            LOGE("[TTS] Audio generation failed");
            return JNI_FALSE;
        }
        
    } catch (const std::exception& e) {
        LOGE("[TTS] Exception generating TTS: %s", e.what());
        return JNI_FALSE;
    }
}

/**
 * Destroy TTS session
 * Java signature: public static native void destroyTtsSession(long ttsHandle);
 */
extern "C" JNIEXPORT void JNICALL
Java_com_offlineai_mnn_MnnInference_destroyTtsSession(
    JNIEnv* env, jclass clazz, jlong handle) {
    
    std::lock_guard<std::mutex> lock(g_tts_sessions_mutex);
    auto it = g_tts_sessions.find(handle);
    if (it != g_tts_sessions.end()) {
        g_tts_sessions.erase(it);
        LOGI("[TTS] TTS session destroyed: %lld", (long long)handle);
    } else {
        LOGW("[TTS] TTS handle not found: %lld", (long long)handle);
    }
}

// ========== External TTS (bert-vits2-MNN) - REMOVED ==========
// bert-vits2-MNN should be treated as a standard LLM text-to-audio model
// Use the existing LLM inference framework, not a separate TTS SDK
// Implementation will be in the LLM handler, similar to Omni TTS
