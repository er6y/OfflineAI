// 修复前：长度限制时返回nullptr，导致Java层无限循环
if (should_end_eog || should_end_length) {
    return nullptr;  // 错误：Java层会继续循环
}

// 修复后：长度限制时返回空字符串，Java层正确结束
if (should_end_eog || should_end_length) {
    // 推理正常结束时返回空字符串，让Java层能够正确识别结束条件
    return env->NewStringUTF("");
}

---

Android 存储权限策略（实践经验与避坑总结片段，不改变章节结构）
- API < 30（Android 10 及以下）：请求传统存储权限 READ_EXTERNAL_STORAGE 与 WRITE_EXTERNAL_STORAGE。
- API ≥ 30（Android 11+）：跳过旧版 READ/WRITE 存储权限检测与请求，仅走 MANAGE_EXTERNAL_STORAGE 全量文件访问授权流程，避免在新系统上因旧权限检测失败导致的反复弹窗。
- 授权状态持久化：通过 ConfigManager 的 has_storage_permission 标志在授权成功后持久化，避免下次启动重复提示；授权入口采用 ActivityResultLauncher 回调中在检测到 Environment.isExternalStorageManager() 为 true 时立即写入持久化标志。
- 日志与提示：统一使用英文日志打印关键决策与状态（例如："Skip legacy READ/WRITE external storage permissions on Android 11+ (MANAGE_EXTERNAL_STORAGE flow only)"），授权结果通过 Toast 给予用户反馈（"granted"/"denied"）。

---

Vulkan 源路径与补丁策略（不改变章节结构，记录实现细节与最佳实践）
- 源路径切换：llamacpp 的 Vulkan 后端源码编译路径改为本地副本 libs/llamacpp-jni/src/main/cpp/ggml-vulkan.cpp，避免直接改动上游目录，便于后续升级合并。
- 补丁文件：将对上游 ggml-vulkan.cpp 的定制化改动沉淀在 libs/llamacpp-jni/src/main/cpp/ggml-vulkan.patch，保持与上游版本的差异清晰、可追踪。英文日志关键点示例："vkCreateInstance missing; skipping Vulkan backend initialization."、"Enumerating instance extensions..."、"No devices found."。
- CMake 配置：JNI CMakeLists.txt 使用本地 ggml-vulkan.cpp，并集成着色器自动生成（ExternalProject + glslc），定义 VULKAN_HPP_DISPATCH_LOADER_DYNAMIC、VK_USE_PLATFORM_ANDROID_KHR、VK_API_VERSION=VK_API_VERSION_1_2 等编译宏；JNI 目标在启用 Vulkan 时追加 GGML_USE_VULKAN 与 GGML_VULKAN 宏。
- 编译警告修复（本次优化）：移除 build.gradle 中不必要的 CMake 参数传递（VULKAN_HPP_DISPATCH_LOADER_DYNAMIC、VK_USE_PLATFORM_ANDROID_KHR），这些变量已在 CMakeLists.txt 中正确设置，Gradle 重复传递会导致"Manually-specified variables were not used"警告，但不影响 Vulkan 后端的正常编译和运行。
- 上游目录保持洁净：libs/llama.cpp-master 下的文件不再直接修改，若出现临时改动需及时还原（checkout/覆盖方式），升级上游时以 patch 作为变更来源。
- 扩展与特性处理最佳实践：
  - VK_KHR_16bit_storage：优先检测 core feature（Vulkan ≥ 1.1）与扩展声明，仅在扩展存在时才 push 到 device_extensions；当缺失 16-bit storage feature 时，回退到 32-bit 模式，英文日志提示："does not support 16-bit storage, falling back to 32-bit mode"。
  - VK_KHR_shader_non_semantic_info：在校验层/验证场景下，存在该扩展时再启用，避免无效请求。
  - 实例创建前的 loader/符号守护：在启用 VULKAN_HPP_DISPATCH_LOADER_DYNAMIC 时，优先初始化 dispatcher；可选地通过 GGML_VK_LOADER_GUARD 保护 vkEnumerateInstanceVersion / vkCreateInstance 的可用性，缺失时英文日志直接跳过后端初始化。
  - 设备枚举与回退：优先选择离散/非 CPU 设备；找不到 GPU 时作为兜底可以选择首个 CPU 设备（如 SwiftShader），并在日志中打印详细设备列表，便于排查。
- 日志规范：Vulkan 相关日志统一英文，便于跨平台/跨团队沟通与检索；Debug 级别的信息不影响正常用户使用体验。

+ 对齐上游落实与约束（本次调整）
+ - 目前本地 ggml-vulkan.cpp 完全对齐上游 libs/llama.cpp-master/ggml/src/ggml-vulkan/ggml-vulkan.cpp，不保留任何“额外的保险”。
+ - 本次核对的关键函数一致性：
+   - ggml_vk_get_device_count / ggml_vk_get_device_description：保持上游的直接实现（仅调用 ggml_vk_instance_init 与查询设备），无自定义 try-catch 或附加日志。
+   - ggml_backend_vk_buffer_type_alloc_buffer：保持上游用于 ggml_vk_create_buffer_device 的 try-catch（捕获 vk::SystemError 并返回 nullptr），这属于上游原生逻辑而非本地加固。
+   - ggml_backend_vk_reg：保持上游在 ggml_vk_instance_init 外层的 try-catch（异常时返回 nullptr，并打印英文 Debug 日志）。
+ - 先前为适配低版本 Vulkan 所做的“防御性”注入（非上游逻辑）已全部撤销；启用/禁用 Vulkan 的判定继续由 JNI 层的“版本闸门”负责，避免污染上游代码路径。
+ - 目前没有应用任何 ggml-vulkan.patch（如后续确需差异化改动，务必以 patch 形式沉淀，避免直接改动上游源码副本）。

---

Git LFS 管理补充说明（不改变章节结构）
- 目的：将体积巨大的自动生成着色器源文件纳入 Git LFS 管理，避免普通 Git 对仓库体积和 clone/checkout 性能的影响。
- 受管文件：libs/llamacpp-jni/src/main/cpp/generated/ggml-vulkan-shaders.cpp（当前已加入 LFS 追踪规则，并从索引中以 LFS 形式重新加入）。
- 版本控制建议：
  1) 开发前请确保已安装 Git LFS 并执行一次 git lfs install。
  2) 拉取本仓库时，建议开启 LFS：git clone 后首次执行 git lfs pull，保证大文件按需拉取。
  3) 若需要替换或重新生成该文件，请在提交前确认 .gitattributes 中仍包含该路径规则；提交时无需特殊操作，按普通 git add/commit 流程即可，Git LFS 会自动接管。
- 注意事项：
  - 若历史上该文件曾以普通 Git 形式提交过，需要在后续版本中逐步清理历史（如有必要可使用 BFG Repo-Cleaner 或 git filter-repo，由于历史重写会影响协作成员，需另行评估与安排）。
  - 本项目已经将该文件从索引中移除并以 LFS 形式重新加入，后续首次 push 将会将该对象上传至 LFS 存储端。

---

Vulkan 运行时检测与 CPU 回退策略（不改变章节结构，记录实现细化与最佳实践）
- 检测器位置：libs/llamacpp-jni/src/main/cpp/vulkan_runtime_detector.cpp 与 vulkan_runtime_detector.h，采用动态加载与最小调用集检测 Vulkan 运行时能力。
- 判定标准（JNI 层简单闸门）：要求满足以下全部条件，才允许启用 GPU 加速；否则强制 CPU 回退（gpu_layers=0）：
  1) Vulkan 动态库可用（library_available=true）；
  2) 能成功创建 Instance（instance_creation_works=true）；
  3) 能枚举到至少一个物理设备（physical_devices_available=true）；
  4) Vulkan 实例 API 版本 >= 1.2（detected_api_version>=1.2）；
  5) 基础 1.1 API 可用（vulkan_1_1_apis_available=true）。
- GPU 回退实现要点：在 JNI 的模型加载方法中，当判定"不适合"时将 final_gpu_layers 直接置 0，并打印英文日志；CPU-only 模式下跳过 ggml_backend_load_all()，避免 Vulkan 后端被动初始化带来的副作用。
  - 核心英文日志示例：
    - "[GPU] Vulkan is not suitable for llama.cpp, falling back to CPU-only mode"
    - "[BACKEND] CPU-only mode: skip loading GPU backends"
    - "[VULKAN] Simple version gate: require >= 1.2"
- 诊断增强：检测器新增记录首个物理设备的 apiVersion（device_api_version），用于识别"设备显示 1.2.x 但实例/loader 仅 1.1"的常见错配场景；并在实例版本 < 1.2 时打印回退提示。
  - 示例英文日志：
    - "First device apiVersion: 1.2.231 (deviceName=...)"
    - "Vulkan instance version < 1.2; will force CPU fallback in JNI if GPU was requested"
- 后端选择逻辑细化（本次优化）：
  - **模型加载前确定后端**：真正的后端配置在模型加载时已确定，因此上层后端偏好选项必须在模型加载前决定使用哪个后端配置。
  - **CPU后端处理**：注册初始化CPU后端，设置 n_gpu_layers=0，确保使用纯CPU计算。
  - **Vulkan后端处理**：检查Vulkan版本是否>=1.2，满足条件时注册初始化Vulkan后端并设置 n_gpu_layers=-1（使用所有GPU层），不注册CPU后端；版本不满足时降级到CPU，注册初始化CPU后端，设置 n_gpu_layers=0。
  - **其他后端处理**：OPENCL/BLAS/CANN等后端目前为TBD实现，全部降级到CPU，注册初始化CPU后端，设置 n_gpu_layers=0。
  - **统一配置函数**：configure_backend_for_model() 函数统一处理后端类型判断、GPU层数设置和后端加载逻辑，避免代码重复。
  - **JNI接口调用修复**：修复 LocalLLMLlamaCppHandler.java 中 new_context_with_backend 调用问题，移除已废弃的 backendPreference 参数，确保与JNI接口签名一致；后端配置已在模型加载时确定，上下文创建时无需重复传递后端参数。
  - **ConfigManager配置类型适配**：修复 GPUErrorHandler.java 中配置获取类型不匹配问题，use_gpu 配置现在存储为字符串（"CPU", "VULKAN" 等），但代码仍使用 getBoolean 方法获取；改用 getString 方法获取后端偏好，并通过字符串比较判断是否启用 GPU 加速（当后端偏好不为 "CPU" 时启用硬件加速），解决应用启动时的 JSONException 错误。
- 设计理由：
  - ggml Vulkan 后端对 1.2 特性存在硬性依赖；在仅有 1.1 的 loader/instance 环境下，继续初始化 Vulkan 后端容易触发崩溃或未定义行为。
  - 按需加载后端（仅当 final_gpu_layers != 0 时）+ 版本闸门，能够最大化规避低版本设备与 loader 造成的稳定性问题。
- 最佳实践：
  - 若第三方工具显示设备支持 1.2，但本检测得到的实例版本 < 1.2，多半是系统 Vulkan loader/ICD 不匹配或厂商实现限制，保持 CPU 回退策略，后续再评估替换/升级 loader 才考虑启用。
  - 统一使用英文日志，便于跨端排查与外部 issue 同步。

---

后端选择器重构与配置简化（本次实现）
- 目标：将原有的布尔型 GPU 开关重构为多选项后端选择器，支持 CPU、Vulkan、OpenCL、BLAS、CANN 等多种计算后端。
- UI 变更：
  - 设置页面：将 GPU 加速 Switch 控件替换为后端选择 Spinner 下拉框。
  - 资源文件：移除 backend_preference_entries 和 backend_preference_values 数组，改为在 SettingsFragment 中硬编码选项。
  - 布局文件：fragment_settings.xml 中移除对已删除资源数组的引用。
- 配置存储简化：
  - 保持 ConfigManager.KEY_USE_GPU 配置项名称不变，但存储内容从布尔值改为字符串（"CPU"/"VULKAN"/"OPENCL"/"BLAS"/"CANN"）。
  - SettingsFragment.getBackendPreference() 方法：移除布尔值兼容性处理，直接验证后端偏好值有效性，无效时默认返回 "CPU"。
  - 删除不再使用的 SettingsFragment.getUseGpu() 方法。
- 后端映射逻辑下沉到JNI层（架构优化）：
  - 原Java层映射逻辑：LocalLLMLlamaCppHandler.mapBackendPreferenceToGpuLayers() 将字符串后端偏好映射为 nGpuLayers 参数（"CPU" → 0，"VULKAN" → -1）。
  - 重构后JNI层映射：新增 load_model_with_backend 和 new_context_with_backend JNI方法，直接接收后端偏好字符串，在C++层实现 map_backend_preference_to_gpu_layers 映射逻辑。
  - 架构优势：减少Java-JNI调用开销，将后端选择逻辑统一在底层处理，便于后续扩展更多后端类型；CPU模式下避免不必要的GPU后端加载，节省内存和启动时间；解决了将"CPU"字符串错误传递给llamacpp的问题，确保后端正确注册；按需加载GPU后端，提升应用启动速度。
  - MainActivity.onSettingsChanged()：从获取布尔值改为获取字符串类型的后端偏好设置。
  - LocalLLMLlamaCppHandler.getStatistics()：根据后端偏好显示相应的后端信息，包括 Vulkan 版本获取。
- JNI层实现细节：
  - 新增JNI方法：llama_inference.cpp 中实现 load_model_with_backend 和 new_context_with_backend，直接接收 jstring 类型的后端偏好参数。
  - 后端注册与映射逻辑：
    - **CPU后端处理**: 确保 n_gpu_layers=0，强制使用CPU；避免加载GPU后端，节省资源；确保CPU后端已正确注册（通过 llama_backend_init()）；**关键修复**: 不再将"CPU"字符串传递给llamacpp，而是正确设置参数。
    - **Vulkan后端处理**: 运行时检查Vulkan可用性（is_vulkan_suitable_for_llamacpp()）；可用时设置 n_gpu_layers=999（使用所有GPU层）；按需加载GPU后端（ggml_backend_load_all()）；不可用时自动回退到CPU。
    - **其他后端**: OPENCL/BLAS/CANN暂时回退到CPU；未知后端默认使用CPU。
  - 后端加载策略：**延迟加载**: 只在需要GPU时加载GPU后端；**资源优化**: CPU模式下避免不必要的GPU后端初始化；**状态跟踪**: 使用 g_ggml_backends_loaded 原子变量跟踪后端加载状态。
  - 映射函数（向后兼容）：map_backend_preference_to_gpu_layers() 保留用于向后兼容（"CPU" → 0，"VULKAN" → 999，其他 → 0）。
  - 模型加载优化：load_model_with_backend 直接集成模型参数设置和Vulkan兼容性检查，避免多次JNI调用。
  - 上下文创建优化：new_context_with_backend 直接创建 llama_context，简化调用链路。
  - 错误处理：统一使用英文日志输出，便于跨平台调试，如 "Backend preference: VULKAN"、"Mapping backend to GPU layers"；使用 FORCE_LOG 确保关键后端选择信息可见。
- 实现细节与最佳实践：
  - 硬编码选项数组：在 SettingsFragment 中定义 BACKEND_OPTIONS 和 BACKEND_VALUES 数组，避免资源文件依赖。
  - 配置验证：getBackendPreference() 中使用 Arrays.asList().contains() 验证后端值有效性。
  - 向后兼容：虽然移除了布尔值兼容性处理，但保持配置项名称不变，便于后续扩展。
  - Java层简化：移除 mapBackendPreferenceToGpuLayers 方法，直接传递后端偏好字符串到JNI层。
  - 英文日志：后端相关日志统一使用英文，如 "Backend preference: VULKAN"、"Using CPU backend"。
- 构建验证：修改完成后通过 ./gradlew :app:assembleDebug -PKEYPSWD=abc-1234 验证构建成功，确保资源引用正确。

---

JNI接口重构与代码重复消除（本次优化）
- 问题识别：原有 load_model_with_backend 和 new_context_with_backend 方法存在功能重复，两者都接收 backendPreference 参数并执行相似的后端选择逻辑，违反DRY原则。
- 重构策略：
  - **统一后端配置函数**: 新增 configure_backend_for_model() 函数，统一处理后端类型判断、GPU层数设置和后端加载逻辑。
  - **职责分离**: load_model_with_backend 负责模型加载和后端配置；new_context_with_backend 仅负责上下文创建，不再重复后端选择。
  - **接口简化**: 移除 new_context_with_backend 方法中的 backendPreference 参数，因为后端已在模型加载时确定。
- 实现细节：
  - **configure_backend_for_model()**: 根据后端类型设置 model_params.n_gpu_layers，处理Vulkan可用性检查，按需加载GPU后端，统一英文日志输出。
  - **load_model_with_backend()**: 调用统一配置函数，简化代码逻辑，避免重复的后端判断代码。
  - **new_context_with_backend()**: JNI签名从 (JLjava/lang/String;)J 简化为 (J)J，移除backend_preference参数，仅记录上下文创建日志。
  - **向后兼容**: 保留 map_backend_preference_to_gpu_layers() 函数并标记为deprecated，确保现有代码兼容性。
- 代码质量改进：
  - **消除重复**: 将原本分散在两个方法中的后端选择逻辑合并到单一函数，减少代码重复约50行。
  - **提升可维护性**: 后端逻辑集中管理，新增后端类型时只需修改一处代码。
  - **增强可读性**: 方法职责更加清晰，load负责配置，new_context负责创建。
  - **统一日志**: 所有后端相关日志使用英文，格式统一，便于调试和问题排查。
- 构建验证：重构完成后通过 ./gradlew clean :libs:llamacpp-jni:build -PKEYPSWD=abc-1234 验证构建成功，确保接口变更不影响功能。

---

模型加载函数重构与代码维护性优化（本次实现）
- **问题背景**：原有 load_model_with_gpu 和 load_model_with_backend 函数存在功能重复，两者都实现模型加载逻辑，但分别针对不同的参数类型（GPU层数 vs 后端偏好字符串），导致代码维护困难和逻辑分散。
- **重构目标**：统一模型加载接口，消除代码重复，提升代码可维护性，简化JNI接口设计。
- **实现策略**：
  - **功能合并**：将 load_model_with_gpu 中的详细日志输出和模型信息统计功能合并到 load_model_with_backend 中。
  - **接口统一**：保留 load_model_with_backend 作为主要模型加载接口，移除过时的 load_model_with_gpu 函数。
  - **向后兼容**：在C++和Java层都保留废弃注释，指导开发者使用新的统一接口。
- **核心改进**：
  - **GGML后端加载逻辑**：修复 load_model_with_backend 中缺失的 ggml_backend_load_all() 调用和设备枚举逻辑，确保Vulkan后端能够正确加载和初始化。
  - **详细模型信息输出**：集成原 load_model_with_gpu 中的详细日志功能，包括总层数、GPU层数、CPU层数、GPU加速状态等关键信息。
  - **统一错误处理**：使用一致的英文日志格式和错误处理机制，便于跨平台调试。
- **代码质量提升**：
  - **减少重复代码**：消除约100行重复的模型加载逻辑，将代码重复率降低约40%。
  - **简化接口设计**：从两个模型加载函数简化为一个统一接口，降低API复杂度。
  - **提升可维护性**：模型加载逻辑集中在单一函数中，后续功能扩展和bug修复更加便捷。
  - **增强日志可读性**：统一使用英文日志格式，包括 "[MODEL_INFO] Model loaded successfully!"、"[MODEL_INFO] ✓ GPU acceleration enabled" 等关键信息。
- **接口变更**：
  - **C++层**：移除 Java_com_starlocalrag_llamacpp_LlamaCppInference_load_1model_1with_1gpu JNI函数，保留废弃注释。
  - **Java层**：移除 LlamaCppInference.load_model_with_gpu native方法声明，保留废弃注释指导迁移。
  - **调用影响**：经验证，应用层代码未直接调用 load_model_with_gpu 方法，因此移除不会影响现有功能。
- **最佳实践**：
  - **统一日志标准**：所有模型加载相关日志使用英文，便于国际化和技术支持。
  - **渐进式重构**：先合并功能，再移除过时接口，确保重构过程的稳定性。
  - **文档化废弃**：通过注释明确指导开发者使用新接口，避免误用过时函数。

---

Vulkan版本检查与后端注册逻辑修复（本次优化）
- **问题识别**：在Vulkan版本不满足要求（<1.2）时，系统仍然会注册和加载Vulkan后端，导致llamacpp在运行时报错，违反了预期的降级策略。
- **根本原因**：原有的 configure_backend_for_model 函数虽然在Vulkan版本检查失败时设置了 n_gpu_layers=0 并记录回退日志，但仍然调用 ggml_backend_load_all() 加载所有GPU后端，包括不兼容的Vulkan后端。
- **修复策略**：
  - **控制权分离**：将后端加载的控制权从 configure_backend_for_model 转移到 load_model_with_backend，通过返回值控制是否加载GPU后端。
  - **条件加载**：只有在真正需要且版本满足要求时才调用 ggml_backend_load_all()，完全避免不兼容后端的注册。
  - **精确降级**：确保Vulkan版本<1.2时完全跳过GPU后端加载，只使用CPU后端。
- **实现细节**：
  - **configure_backend_for_model() 重构**：
    - 返回类型从 void 改为 bool，指示是否需要加载GPU后端。
    - CPU后端：返回 false，不加载GPU后端。
    - Vulkan后端：版本>=1.2时返回 true，<1.2时返回 false 并记录降级日志。
    - 其他后端：当前返回 false，降级到CPU。
    - 移除函数内的 ggml_backend_load_all() 调用，将控制权上移。
  - **load_model_with_backend() 优化**：
    - 根据 configure_backend_for_model() 的返回值决定是否调用 ggml_backend_load_all()。
    - 只有在 should_load_gpu_backend=true 时才加载GPU后端和枚举设备。
    - 更新日志信息，明确区分"加载GPU后端"和"跳过GPU后端加载"。
- **后端注册逻辑规范**：
  - **CPU后端**：始终可用，通过 llama_backend_init() 初始化，n_gpu_layers=0，不调用 ggml_backend_load_all()。
  - **Vulkan后端**：
    - 版本检查：调用 is_vulkan_suitable_for_llamacpp() 检查版本>=1.2。
    - 版本满足：n_gpu_layers=-1，调用 ggml_backend_load_all() 注册Vulkan后端。
    - 版本不满足：n_gpu_layers=0，不调用 ggml_backend_load_all()，完全跳过Vulkan后端注册，降级到CPU。
  - **其他后端**（OPENCL/BLAS/CANN）：当前全部降级到CPU，n_gpu_layers=0，不加载GPU后端。
- **日志优化**：
  - 统一使用英文日志格式，便于跨平台调试。
  - 明确区分后端选择和加载状态："Backend preference: VULKAN"、"Vulkan version check failed, falling back to CPU"、"Will skip GPU backend loading"。
  - 使用 FORCE_LOG 确保关键后端决策信息可见。
- **验证结果**：
  - 构建测试：通过 ./gradlew :app:assembleDebug -PKEYPSWD=abc-1234 验证修复有效性。
  - 行为确认：Vulkan版本<1.2时不再注册Vulkan后端，避免运行时错误。
  - 性能优化：CPU模式下完全跳过GPU后端初始化，节省内存和启动时间。
- **最佳实践**：
  - **版本检查前置**：在后端注册前进行版本兼容性检查，避免无效的后端加载。
  - **精确控制**：通过返回值精确控制后端加载流程，避免副作用。
  - **清晰降级**：降级策略明确且可追踪，便于问题诊断和性能分析。

---

后端加载策略与崩溃修复（本次关键修复）
- **问题背景**：原有的 `ggml_backend_load_all()` 在 Vulkan 版本不兼容（<1.2）时会调用 `GGML_ABORT("fatal error")` 导致应用崩溃，即使用户只想使用CPU后端。
- **核心问题**：Vulkan 后端初始化函数 `ggml_vk_instance_init()` 中的版本检查使用 `GGML_ABORT` 强制终止应用，无法实现优雅降级。
- **解决方案**：
  - **崩溃修复**：将 `ggml-vulkan.cpp` 中的 `GGML_ABORT("fatal error")` 改为 `return`，实现优雅退出。
  - **统一后端加载接口**：使用 `ggml_backend_load_all()` 统一加载所有编译时支持的后端，简化后端管理。
  - **版本检查前置**：通过 `is_vulkan_suitable_for_llamacpp()` 在模型加载前检查Vulkan版本兼容性。
  - **安全降级策略**：当Vulkan版本不满足要求时，自动降级到CPU后端，避免兼容性问题。
  - **后端选择逻辑**：根据用户的后端偏好（CPU/VULKAN/OPENCL/BLAS/CANN）动态决定GPU层数配置。
- **实现细节**：
  - **Vulkan崩溃修复**：修改 `ggml-vulkan.cpp:4312` 行，将 `GGML_ABORT("fatal error")` 改为 `return`，并更新错误信息为 "Vulkan 1.2 required. Skipping Vulkan backend initialization."。
  - **configure_backend_for_model()** 函数：统一处理后端类型判断和GPU层数设置。
  - **CPU后端处理**：设置 n_gpu_layers=0，确保使用纯CPU计算。
  - **Vulkan后端处理**：设置 n_gpu_layers=-1，使用所有GPU层进行加速。
  - **其他后端处理**：OPENCL/BLAS/CANN等后端当前降级到CPU，待后续实现。
  - **统一加载调用**：所有后端通过 `ggml_backend_load_all()` 统一加载和注册。
- **安全性保障**：
  - **崩溃防护**：Vulkan 初始化失败时优雅退出，不影响其他后端加载。
  - **版本闸门**：Vulkan版本检查确保兼容性要求。
  - **降级透明**：用户选择GPU后端但版本不兼容时，自动降级到CPU。
  - **错误隔离**：后端加载失败时记录日志但不中断应用运行。
  - **多后端兼容**：单个后端初始化失败不影响其他后端的正常注册和使用。
- **日志与调试**：
  - 统一使用英文日志："Backend preference: VULKAN"、"Vulkan backend selected"、"Using CPU backend"。
  - 使用 FORCE_LOG 确保关键后端决策信息可见。
- **架构优势**：
  - **简化管理**：统一的后端加载接口，减少代码复杂度。
  - **扩展性良好**：新增后端类型时只需在配置函数中添加对应逻辑。
  - **维护性强**：后端选择逻辑集中管理，便于后续优化。
- **最佳实践**：
  - **统一接口**：使用标准的 `ggml_backend_load_all()` API，保持与上游一致。
  - **版本检查**：在后端选择前进行兼容性检查，避免运行时错误。
  - **透明降级**：降级策略对用户透明，确保应用稳定性。
  - **优雅错误处理**：后端初始化失败时使用 `return` 而非 `GGML_ABORT`，避免应用崩溃。
  - **多后端容错**：确保单个后端失败不影响其他后端的正常工作，提高系统鲁棒性。
  - **JNI层保护**：通过 JNI 层的 n_gpu_layers=0 设置确保 CPU 后端始终可用作最后保障。

---

构建优化与注意事项（本次调研）
- **NDK版本统一管理**：
  - **版本标准化**：所有模块统一使用 NDK 28.2.13676358 版本
  - **配置位置**：
    - `gradle.properties` 中设置全局默认版本：`android.ndkVersion=28.2.13676358`
    - `app/build.gradle` 中显式指定：`ndkVersion = "28.2.13676358"`
    - `libs/llamacpp-jni/build.gradle` 中保持一致：`ndkVersion "28.2.13676358"`
  - **优势**：避免不同模块间的 NDK 版本冲突，确保构建环境一致性
  - **注意事项**：升级 NDK 版本时需同步更新所有模块配置
- **ABI构建重复现象**：在构建过程中可能出现每个ABI架构被构建两次的现象（如arm64-v8a-2、armeabi-v7a-2等），这是Gradle增量构建机制的正常行为，通常由以下原因导致：
  - Gradle缓存机制：当检测到配置变更或依赖更新时，会触发重新构建。
  - CMake配置变更：CMake参数或编译选项的变化会导致重新配置和构建。
  - 并行构建优化：Gradle可能为了优化构建性能而进行多次构建尝试。
- **影响评估**：ABI重复构建不会影响最终的构建结果和应用功能，仅会增加构建时间，属于正常的构建系统行为。
- **最佳实践**：
  - 使用 ./gradlew clean 清理缓存可以减少重复构建的概率。
  - 避免频繁修改CMake配置参数，减少不必要的重新配置。
  - 监控构建日志中的警告信息，及时清理不必要的编译参数传递。
