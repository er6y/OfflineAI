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
- 源路径策略（最新）：直接编译上游源码 `libs/llama.cpp-master/ggml/src/ggml-vulkan/ggml-vulkan.cpp`，保持与上游完全一致，减少分叉维护成本。
- 补丁策略：仅当编译或运行在目标平台出现明确问题时，才以最小化补丁的方式修复，并且将补丁应用在上游文件路径（同目录）上。请将差异以 patch 形式保存，避免长期维护本地副本。
- CMake 配置：`libs/llamacpp-jni/src/main/cpp/CMakeLists.txt` 的 `GGML_VULKAN_SOURCES` 已切换为上游路径，并集成着色器自动生成（ExternalProject + glslc），定义 `VULKAN_HPP_DISPATCH_LOADER_DYNAMIC`、`VK_USE_PLATFORM_ANDROID_KHR`、`VK_API_VERSION=VK_API_VERSION_1_2` 等编译宏；JNI 目标在启用 Vulkan 时追加 `GGML_USE_VULKAN` 与 `GGML_VULKAN` 宏。
- Gradle 参数精简：移除 `build.gradle` 中不必要的 CMake 宏转发（如 `VULKAN_HPP_DISPATCH_LOADER_DYNAMIC`、`VK_USE_PLATFORM_ANDROID_KHR`），以避免 "Manually-specified variables were not used" 警告；这些宏均由 CMake 正确管理。
- 头文件发现与传参与回退策略（新增）：
  - 优先通过环境变量传递 Vulkan-Hpp 头文件路径：在 Gradle 中读取 `VULKAN_SDK` 并将 `${VULKAN_SDK}/Include` 作为 `-DVULKAN_HPP_DIR=...` 传递给 CMake，确保编译期可找到 `vulkan/vulkan.hpp`。
  - 在 <mcfile name="CMakeLists.txt" path="libs/llamacpp-jni/src/main/cpp/CMakeLists.txt"></mcfile> 中，针对 `ggml-vulkan` 与 `llamacpp_jni` 两个目标：若定义了 `VULKAN_HPP_DIR`，则直接通过 `target_include_directories` 注入该路径；否则回退到 `find_package(VulkanHeaders)` 查找系统或第三方提供的 Vulkan-Headers 包；两者均不可用时，CMake 将以清晰错误消息 fail-fast（提示未找到 `vulkan/vulkan.hpp`）。
  - Android NDK 自带的是 C API 头（`vulkan_core.h` 等），不包含 C++ 头 `vulkan.hpp`；因此需要额外安装 Vulkan-Headers（或 Vulkan SDK），并通过上面的传参策略提供路径。
  - 典型环境（Windows）配置示例：先设置环境变量 `set VULKAN_SDK=C:\VulkanSDK\1.3.xxx.x`，再执行构建命令（例如 `./gradlew :libs:llamacpp-jni:externalNativeBuildDebug -PKEYPSWD=abc-1234`），Gradle 会自动把 `${VULKAN_SDK}/Include` 传入 CMake。
  - 诊断方法：若编译报错 `fatal error: 'vulkan/vulkan.hpp' file not found`，请检查是否正确设置 `VULKAN_SDK` 与传参；也可在 `.cxx/Debug/<hash>/<abi>/compile_commands.json` 或 `ninja -v` 输出中确认是否包含 `-I<VULKAN_HPP_DIR>`。
  - 去除全局 include_directories：不再在 ANDROID 分支中使用 `include_directories("${Vulkan_INCLUDE_DIRS}")`，改为仅对 `ggml-vulkan` 与 `llamacpp_jni` 目标各自注入 `target_include_directories`，避免泄露头路径并提升可观测性（英文日志）。
  - Fail-fast 规则：当 `ENABLE_VULKAN_BACKEND=ON` 但既未提供 `VULKAN_HPP_DIR` 亦未解析到 `Vulkan_INCLUDE_DIRS` 时，CMake 在配置期直接 `message(FATAL_ERROR ...)` 终止，并给出清晰英文提示；避免把缺失 `vulkan.hpp` 的错误延迟到编译阶段才暴露。
  - 回退建议：若当下不需要 Vulkan 后端，可在 CMake 关闭 Vulkan（例如设置 `-DGGML_VULKAN=OFF` 或在工程开关处禁用相关目标），避免对 Vulkan-Headers 的构建期依赖；需要启用 Vulkan 时再恢复上述传参。
- 上游洁净性：除非应用最小补丁，否则不直接修改 `libs/llama.cpp-master` 目录中的其他文件；升级上游版本时优先对比并再应用本地补丁文件。
- 扩展与特性最佳实践：
  - VK_KHR_16bit_storage：优先检测 core feature（Vulkan ≥ 1.1）与扩展声明；缺失时回退到 32-bit，并打印英文日志："does not support 16-bit storage, falling back to 32-bit mode"。
  - VK_KHR_shader_non_semantic_info：仅在验证/调试场景且存在该扩展时启用（设备扩展列表中确实可用时才附加请求）。
  - VK_KHR_shader_float16_int8：仅当设备报告支持且启用 FP16 计算时才附加请求，否则不附加，避免无效扩展导致的创建失败。
  - 实例创建前的 loader/符号守护：在启用 `VULKAN_HPP_DISPATCH_LOADER_DYNAMIC` 时优先初始化 dispatcher；可选通过 `GGML_VK_LOADER_GUARD` 保护 `vkEnumerateInstanceVersion`/`vkCreateInstance` 可用性，不可用时直接跳过后端初始化（英文日志）。
  - 设备枚举与回退：优先离散 GPU；无 GPU 时可回退到 CPU 设备（如 SwiftShader），打印完整设备列表便于诊断；若最终仍无设备，优雅跳过 Vulkan 后端。
  - 日志规范：Vulkan 相关日志统一英文；Debug 级别信息不影响用户体验。

构建验证（本次）：
- Debug 版：在 `.cxx/Debug/<hash>/arm64-v8a` 目录内执行 `ninja -v -C <dir> llamacpp_jni`，成功产出 `libllamacpp_jni.so`。
- Release 版：`./gradlew :app:assembleRelease -PKEYPSWD=abc-1234 --info --stacktrace` 成功，APK 输出：`app/build/outputs/apk/release/app-release.apk`。
- JNI 修复：移除未暴露符号 `ggml_cpu_has_sve2()` 的调用，仅记录 SVE 运行时能力（SVE2 记为 0），修复 Release 构建失败。
- x86_64：在 `.cxx/Debug/<hash>/x86_64` 目录内执行 `ninja -v -C <dir> llamacpp_jni`，成功链接并输出 "LlamaCpp JNI library built for x86_64"。
- ARM64 K-quants 链接修复（本次）：在 <mcfile name="CMakeLists.txt" path="libs/llamacpp-jni/src/main/cpp/CMakeLists.txt"></mcfile> 的 ggml-cpu 目标创建后追加 `GGML_CPU_GENERIC=1` 编译定义，触发 `ggml-cpu/arch-fallback.h` 将 quants.c 中的 `*_generic` 实现重命名为无后缀符号，从而修复 `ggml_vec_dot_q5_K_q8_K`、`quantize_row_q8_K` 等未定义符号的链接错误；已通过 `ninja -v llamacpp_jni` 在 arm64-v8a 成功验证。注意：该设置仅作为通用回退，不影响其他架构专用内核，后续若按架构纳入专用 quants 源文件，可移除此定义。

对齐上游落实与约束（本次调整）
- 直接使用上游 `ggml-vulkan.cpp` 进行编译；不保留“额外的保险”。
- 关键函数遵循上游实现：
  - `ggml_vk_get_device_count` / `ggml_vk_get_device_description`：仅调用 `ggml_vk_instance_init` 与查询设备，无自定义 try-catch 或额外日志。
  - `ggml_backend_vk_buffer_type_alloc_buffer`：保留上游对 `vk::SystemError` 的捕获与返回 `nullptr` 的逻辑。
  - `ggml_backend_vk_reg`：保留上游在 `ggml_vk_instance_init` 外层的异常保护与英文 Debug 日志。
- 低版本 Vulkan 的“防御性注入”逻辑不进入上游文件；启停策略交由 JNI 层版本闸门与后端选择决定。
- 最小化上游修复（本次新增）：`ggml_vk_instance_init()` 增加两点健壮性处理，以避免在模拟器/x86_64 等缺失 loader 或 API 版本不足时崩溃：
  - 在任何 Vulkan-HPP 调用前初始化动态分发器：`VULKAN_HPP_DEFAULT_DISPATCHER.init(vkGetInstanceProcAddr)`；初始化失败则打印英文告警并“跳过 Vulkan 后端初始化”。
  - `vk::enumerateInstanceVersion()` 异常或 `api_version < 1.2` 时，不再 `GGML_ABORT`，改为英文日志并返回（标记 Vulkan 不可用），让上层安全回退到 CPU。
  - 适用场景：Android 模拟器 x86_64、设备 loader/ICD 不完整、仅支持 1.1 的运行环境。
 - 设备扩展选择最小化：仅在设备明确支持时附加 `VK_KHR_16bit_storage`、`VK_KHR_shader_float16_int8`、`VK_KHR_shader_non_semantic_info`，避免无效扩展导致的设备创建失败。
 - Host pinned 内存回退：当 `ggml_vk_host_malloc()` 返回 `nullptr` 或出现 `vk::SystemError` 时，回退到 CPU 缓冲分配，避免崩溃（英文日志告警）。

- Gradle/AGP 环境下的 CMake include 策略（新增）：
  - 绝对路径包含 ggml/cmake/common.cmake，避免依赖 CMAKE_MODULE_PATH 搜索在 AGP 配置期出现不稳定；
  - 暂不包含 llama/cmake/common.cmake，使用本地空实现提供 `llama_add_compile_flags` 兜底，避免配置阶段失败；
  - 英文日志示例："Defined local stub for llama_add_compile_flags (upstream not providing)"；后续在 CMAKE_MODULE_PATH 稳定后可恢复 include 并移除 stub。

- 链接结构（新增）：
  - 按上游将 ggml-vulkan 构建为静态库并链接进 JNI 目标，替代直接把源文件编进 JNI；
  - 优点：减少 ODR/宏泄漏、可重用性更好、诊断更清晰（目标级 include/defs 而不是全局）。

- 上游托管边界（新增）：
  - ggml-base/cpu 尽量由上游 CMake 管理，JNI 仅作为薄胶水层；
  - 仅在 ARM K-quants 需要通用回退时追加 `GGML_CPU_GENERIC=1` 定义，待按架构的专用内核完善后可移除此定义。

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
- 设备可用性判定修复与诊断日志（本次）：
  - 判定修复：由“设备名称包含子串 'Vulkan'”改为依据 ggml 后端注册器名判断（`ggml_backend_dev_backend_reg()` + `ggml_backend_reg_name()` 比较是否为 "Vulkan"），避免设备名为 "Adreno/GeForce/SwiftShader" 等被误判为非 Vulkan 的情况。
  - 日志增强：设备枚举时新增打印 backend 名称；结果汇总日志改为 "[BACKEND] Vulkan device available (by backend name): yes/no"，便于快速判定是否正确识别 Vulkan 后端。
  - 影响范围：仅影响可用性判定与诊断输出，不改变版本闸门与安全回退策略；若运行时闸门（instance<1.2 等）不满足，仍将 CPU 回退。
  - JNI 层静态注册（新增）：在 `llama_inference.cpp` 中，调用 `ggml_backend_register(ggml_backend_vk_reg())`，并且放在 `ggml_backend_load_all()` 之前执行；这样在禁用上游注册器（`GGML_BACKEND_VULKAN=OFF`）但仍静态链接本地 `ggml-vulkan` 库的场景下，Vulkan 后端依然可以被设备枚举识别。英文日志示例："[BACKEND] Register Vulkan (static) via ggml_backend_vk_reg() before ggml_backend_load_all()"。
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
  - 保持 ConfigManager.KEY_USE_GPU 配置项名称不变，但存储内容从布尔值改为字符串（"CPU"/"VULKAN"）。
  - SettingsFragment.getBackendPreference() 方法：移除布尔值兼容性处理，直接验证后端偏好值有效性，无效时默认返回 "CPU"。
  - 删除不再使用的 SettingsFragment.getUseGpu() 方法。
- Java 层包装方法（本次补充）：
  - 在 <mcfile name="LlamaCppInference.java" path="libs/llamacpp-jni/src/main/java/com/starlocalrag/llamacpp/LlamaCppInference.java"></mcfile> 的 setBackendPreference() 中，新增对 "KLEIDIAI" 与 "KLEIDIAI-SME" 的合法性校验；当接收到未知值时，打印英文警告并回退为 "CPU"，示例："Unknown backend preference: <value>, using CPU"。
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

 变更补充（UI 与兼容性处理）
- 设置页面的“后端偏好”下拉菜单现仅包含：CPU、Vulkan。已移除 KleidiAI/KleidiAI-SME；CPU 模式默认内含 KleidiAI 微内核（如已编译），无法在 UI 显式开/关。
- 兼容性策略：
  - 若已有配置保存为 "CANN"（历史值），在读取时将自动回退为 "CPU"，同时写回配置，避免不匹配导致的异常或错误显示。
  - 若已有配置保存为 "OPENCL" 或 "BLAS"，同样在读取时判定为无效并回退为 "CPU"。
  - 若已有配置保存为 "KLEIDIAI" 或 "KLEIDIAI-SME"（历史值），同样在读取时回退为 "CPU"，并写回配置，保持 UI 与底层一致。
- KleidiAI 行为（重要）：UI 不再提供 KleidiAI 选项；CPU 模式下默认携带 KleidiAI 微内核（若已编译进二进制），无法显式开/关。英文日志示例：
  - "[BACKEND] preference=CPU -> CPU path (KleidiAI microkernels if compiled)"
  - "[CPU] features -> dotprod=<0|1> sme=<0|1>"
  - "[KLEIDIAI] compiled-in: <yes|no>"
- 代码位置：<mcfile name="SettingsFragment.java" path="app/src/main/java/com/example/starlocalrag/SettingsFragment.java"></mcfile> 中的硬编码选项为来源；getBackendPreference(Context) 对读取值进行有效性校验与兼容映射；<mcfile name="llama_inference.cpp" path="libs/llamacpp-jni/src/main/cpp/llama_inference.cpp"></mcfile> 中依据后端字符串设置 GGML_KLEIDIAI_SME 环境变量。

---

后端选择器重构与配置简化（本次实现）
- 目标：将原有的布尔型 GPU 开关重构为多选项后端选择器，支持 CPU、Vulkan、OpenCL、BLAS、CANN 多种计算后端。
- UI 变更：
  - 设置页面：将 GPU 加速 Switch 控件替换为后端选择 Spinner 下拉框。
  - 资源文件：移除 backend_preference_entries 和 backend_preference_values 数组，改为在 SettingsFragment 中硬编码选项。
  - 布局文件：fragment_settings.xml 中移除对已删除资源数组的引用。
- 配置存储简化：
  - 保持 ConfigManager.KEY_USE_GPU 配置项名称不变，但存储内容从布尔值改为字符串（"CPU"/"VULKAN"/"KLEIDIAI-SME"）。
  - SettingsFragment.getBackendPreference() 方法：移除布尔值兼容性处理，直接验证后端偏好值有效性；对历史值进行兼容映射，无效时默认返回 "CPU"。
  - 删除不再使用的 SettingsFragment.getUseGpu() 方法。
- Java 层包装方法（本次补充）：
  - 在 <mcfile name="LlamaCppInference.java" path="libs/llamacpp-jni/src/main/java/com/starlocalrag/llamacpp/LlamaCppInference.java"></mcfile> 的 setBackendPreference() 中，包含对 "KLEIDIAI-SME" 的合法性校验；当接收到未知值时，打印英文警告并回退为 "CPU"，示例："Unknown backend preference: <value>, using CPU"。
- 后端映射逻辑下沉到JNI层（架构优化）：
  - 参见上文，不再赘述。

---

日志优化（补充：CPU能力可观测性）
+ 日志优化（补充：CPU能力可观测性）
+ - JNI 运行时能力打印新增：一次性 CPU/KleidiAI 能力快照函数，在 backend_init() 之后调用。
+   - 英文日志包含：编译期宏（ARCH/NEON/DOTPROD/SVE/SVE2）、KleidiAI 编译状态、运行时 `ggml_cpu_has_neon/dotprod/sve`。
+   - 说明：上游 ggml 当前仅提供 `ggml_cpu_has_sve()`，不包含 `ggml_cpu_has_sve2()`，因此 SVE2 在运行时日志中显示为 0；若后续上游加入 SVE2 探测，可平滑启用。
  - 新增：在 JNI `load_model_with_backend()` 的 `[KLEIDIAI] compiled-in: ...` 英文日志附近，追加 CPU 信息快照日志，便于判断设备是否具备相关能力：
    - `[CPU] arch: <aarch64|arm|x86_64|x86|unknown>`（编译期架构）
    - `/proc/cpuinfo` 摘要（model/Processor、Hardware、Features 或 flags）
    - `auxv` 硬件能力：`[CPU] HWCAP: 0x... HWCAP2: 0x...`，在 aarch64 上尝试解码 `asimddp(dotprod)` 与 `sme`
- 目的：
  - 与 `[CPU] features -> dotprod=... sme=...` 的运行时探测结果交叉验证，迅速定位“功能不可用”的根因（芯片不支持 / 系统未暴露 / 探测兼容性问题）。
  - 与 `[KLEIDIAI] buffer type available: ...` 联动，判断 KleidiAI 路径是否完整可用。
- 设计要点：
  - 仅使用英文日志，统一风格，利于跨平台排查。
  - 访问 `/proc/cpuinfo` 与 `getauxval(AT_HWCAP/AT_HWCAP2)`，失败时输出清晰的 fallback 日志。
  - aarch64 下若系统头未暴露 `HWCAP_ASIMDDP`/`HWCAP2_SME`，以 `unknown` 标示，避免构建耦合。
- 诊断建议：
  - `compiled-in: yes` 且 `HWCAP asimddp=yes`、`dotprod=1`：KleidiAI 可利用 dot product 微内核。
  - `compiled-in: yes` 但 `dotprod=0`：多为硬件不支持或系统未暴露；此时 KleidiAI 回退至 NEON 路径，功能正确但性能下降。
  - `sme=1` 需设备为 Armv9.2+ 且系统暴露能力，并配合 `KLEIDIAI-SME` 选项与 `GGML_KLEIDIAI_SME=1` 才可能命中。
- 构建与验证：
  - Debug 版：`./gradlew :app:assembleDebug -PKEYPSWD=abc-1234`
  - Release 版：`./gradlew assembleRelease -PKEYPSWD=abc-1234`
  - 本次已验证：assembleDebug/assembleRelease 均成功产出 APK（链接错误已消除）
  - 真机运行后观察 `[KLEIDIAI] compiled-in`、`[CPU] arch/.../HWCAP`、`[CPU] features`、`[KLEIDIAI] buffer type` 四组关键日志。

- dotprod 启用策略与回退（arm64-v8a）
  - 编译期：在 <mcfile name="CMakeLists.txt" path="libs/llamacpp-jni/src/main/cpp/CMakeLists.txt"></mcfile> 的 arm64-v8a 分支启用 `-march=armv8.2-a+fp16+dotprod` 并追加 `GGML_USE_DOTPROD`，确保 KleidiAI dotprod 微内核源文件被纳入构建。
  - 兼容性：设备不支持 FEAT_DotProd 时，运行时 `features -> dotprod=0`，自动回退 NEON 路径，功能正确但性能下降；因此全局开启 dotprod 是安全的。
  - 根因与修复：之前 Release 链接错误由“已注册 dotprod 变体但未编译对应实现”导致；现通过启用 dotprod 解决（匹配上游 ggml-cpu CMake 对 `+dotprod` 的条件汇编逻辑）。
  - 验证步骤：
    1) 构建 Debug/Release；
    2) 设备日志中 `[CPU] features -> dotprod=1` 且 `[KLEIDIAI] compiled-in: dotprod=yes`；
    3) 观察 matmul/反量化路径命中 dotprod 变体（性能压测可见）。
  - 回退策略：如遇个别 toolchain 不识别指令集，可临时回退到 `-march=armv8-a+fp+simd+fp16` 并移除 `GGML_USE_DOTPROD`；但推荐优先 `armv8.2-a + dotprod`。

---

Vulkan undefined symbol root cause：避免对上游 `ggml` 目标强行注入 `GGML_USE_VULKAN=0`/`GGML_VULKAN=0` 的 `target_compile_definitions`；否则会使 `#ifdef GGML_USE_VULKAN` 在 `ggml-backend-reg.cpp` 中被错误触发，但链接阶段未引入 `ggml-vulkan`，导致 `undefined symbol: ggml_backend_vk_reg`。

运行时验证（补充：日志重定向与初始化顺序）
- 日志重定向：JNI 在早期通过 dup/pipe 将 stdout/stderr 重定向至 Android logcat（英文注释），确保 native 日志可见；错误发生在初始化之前时，LOGE 仍能捕获。
- 初始化顺序：先调用 `llama_backend_init()` 完成后端注册基础设施，再执行一次性 CPU/KleidiAI 能力快照打印（避免未初始化情况下调用 ggml 检测 API）；最后根据后端偏好与版本闸门决定是否加载 GPU 后端。
- 关键英文日志示例：
  - "[BACKEND] Starting backend initialization..."
  - "[BACKEND] Backend initialization completed"
  - "[CAPS] ---- Build-time (compiler macros) ----"
  - "[CPU] runtime features -> neon=<0|1>, dotprod=<0|1>, sve=<0|1>, sve2=<0>"

---

KleidiAI 头文件路径与 CMake 集成（本次修复）
- 症状：使用 ninja -v 编译 <mcfile name="kernels.cpp" path="libs/llama.cpp-master/ggml/src/ggml-cpu/kleidiai/kernels.cpp"></mcfile> 报错找不到专用内核头（例如 "kai_matmul_clamp_f32_bf16p2vlx2_bf16p2vlx2_2vlx2vl_sme2_mopa.h"），英文错误示例："fatal error: '...mopa.h' file not found"。
- 根因：CMake 未将 KleidiAI ukernels/matmul 子目录加入 ggml-cpu 目标的 include 搜索路径，导致 kernels.cpp 顶部 include 的专用内核头无法解析。
- 解决策略：
  1) 仅对 ggml-cpu 目标追加 target_include_directories，避免全局污染；保持第三方源码不改动。
  2) 覆盖两个稳定的包含根：kai/ukernels/matmul/pack 以及具体的 matmul_clamp_* 子目录；针对 bf16 内核，额外加入 kai/ukernels/matmul/matmul_clamp_fp32_bf16p_bf16 目录，确保能解析 bf16 头。
  3) 建议用条件包裹（例如启用 KleidiAI 时才生效），避免未启用 KleidiAI 的冗余 include。
- 实施位置：
  - 在 <mcfile name="CMakeLists.txt" path="libs/llamacpp-jni/src/main/cpp/CMakeLists.txt"></mcfile> 中，ggml-cpu 目标创建后通过 target_include_directories 注入下列目录（示例）：
    - D:/yilei.wang/StarLocalRAG/libs/kleidiai/kai/ukernels/matmul/pack
    - D:/yilei.wang/StarLocalRAG/libs/kleidiai/kai/ukernels/matmul/matmul_clamp_f32_qsi8d32p_qsi4c32p
    - D:/yilei.wang/StarLocalRAG/libs/kleidiai/kai/ukernels/matmul/matmul_clamp_f32_qai8dxp_qsi4c32p
    - D:/yilei.wang/StarLocalRAG/libs/kleidiai/kai/ukernels/matmul/matmul_clamp_fp32_bf16p_bf16
  - 依据 <mcfile name="kernels.cpp" path="libs/llama.cpp-master/ggml/src/ggml-cpu/kleidiai/kernels.cpp"></mcfile> 顶部 include 的内核头做最小集合覆盖，避免过度添加目录。
- 构建验证：
  - 执行 ninja -v -C <.cxx/Debug/.../arm64-v8a> llamacpp_jni 成功，产出 libllamacpp_jni.so；x86_64 同样产出。
  - 关键英文日志示例：
    - "[KLEIDIAI] Added ukernels include paths to target ggml-cpu"
    - "[BUILD] Missing KleidiAI header resolved by target-specific include directories"
- 链接风险排查：
  - 核对 ggml-cpu 与 KleidiAI 顶层 CMake，确认 bf16 内核源码（kai_matmul_clamp_f32_bf16p2vlx2_..._sme2_mopa.c）已纳入编译，避免仅头文件可见但缺少实现导致 undefined reference。
- 最佳实践与注意：
  - 使用 target_include_directories 而非全局 include_directories，提高可维护性与可观测性。
  - 避免在头文件中依赖仓库根相对路径；优先通过 include 路径解析。
  - 诊断优先用 "ninja -v" 观察实际编译命令，确认存在预期的 -I<kleidiai/...> 路径。

Windows 构建命令建议
- 快速验证 JNI：在 .cxx/Debug/<hash>/<abi> 目录执行：ninja -v -C <dir> llamacpp_jni
- Debug APK：./gradlew :app:assembleDebug -PKEYPSWD=abc-1234 --info --stacktrace
- Release APK：./gradlew :app:assembleRelease -PKEYPSWD=abc-1234 --info --stacktrace

---

CMake（JNI 构建脚本）优化补充说明（此次变更汇总，保持行为不变）
- 预检查增强：在 ENABLE_VULKAN_BACKEND=ON 时，新增对 vulkan.hpp（VULKAN_HPP_DIR / Vulkan_INCLUDE_DIRS / VULKAN_SDK/Include）与 glslc 的健壮性检测；缺失时仅禁用 Vulkan 后端，不中断整体构建（英文日志）。
- 生成器与特性探测：使用上游 vulkan-shaders 生成器（ExternalProject），通过 glslc 探测 cooperative_matrix / cooperative_matrix2 / integer_dot_product / bfloat16 支持并转递对应 GGML_VULKAN_*_GLSLC_SUPPORT 宏.
- 可执行后缀：改为 if(CMAKE_HOST_WIN32) 判定 .exe 后缀，替代生成器表达式，提升可读性与稳定性.
- 增量构建：注释 BUILD_ALWAYS TRUE，保持 Release 构建但避免强制每次重编生成器，改善构建效率.
- 目标注入：在发现 VULKAN_HPP_INCLUDE_DIR 时，分别对 ggml-vulkan 与 JNI 目标注入 include 路径；启用 Vulkan 时仅对 JNI 目标注入 GGML_USE_VULKAN/GGML_VULKAN 宏用于运行时日志标识.
- Debug 配置：保持 Debug 仍为 O3，不作修改.
- 构建校验：已在 Windows 上执行 .\\gradlew :app:assembleDebug -PKEYPSWD=abc-1234，arm64-v8a 与 x86_64 ABI 构建通过，产物生成成功.

---

本地 LLM 输出能力扩展与上下文滑动（Context Shift）实现（本次变更）
- 需求与设计要点：
  - 解耦“最大输出 token 数”与“最大序列长度（n_ctx）”。最大输出仅作为输出软上限，不再反向限制 n_ctx 或输入窗口.
  - 支持 KV-Cache 滑动（Context Shift）：当生成位置逼近 n_ctx 边界时滑动 KV，保留前缀 n_keep，继续生成，实现“滚动窗口”。
  - 目标：在移动端资源有限条件下，提升长/超长输出的可持续性与稳定性.

- UI/配置变更：
  - `fragment_settings.xml`：将“最大输出 token 数”SeekBar 范围扩展为 512–16384（步进 512）。
  - `SettingsFragment.java`：
    - 校验放宽为 512–16384；移除“n_ctx > max_new_tokens + 256”的强耦合校验.
    - 英文提示日志保持：范围越界时只提示本项，不再耦合 n_ctx.

- 引擎与 JNI 实现：
  - Java 层（`LlamaCppInference.java`）：新增上下文滑动配置接口（static native）：
    - `set_context_shift(boolean enable, int nKeep)`
    - `get_context_shift_enabled()`、`get_context_shift_n_keep()`
  - C++ 层（`llama_inference.cpp`）：
    - 新增全局配置 `g_ctx_shift_enabled`、`g_ctx_shift_n_keep`；JNI 对应导出函数.
    - 在 `completion_loop(...)` 中，当当前位置到达或超过 `n_ctx` 时：
      - 使用 `llama_get_memory()` 获取 memory；若 `llama_memory_can_shift()` 为 true：
        - 通过 `llama_memory_seq_rm(mem, 0, n_keep, -1)` 移除可舍弃的尾段；
        - 通过 `llama_memory_seq_add(mem, 0, n_keep, -1, -delta)` 平移剩余位置；
        - 重置 `ncur` 使下一 token 追加到 `n_keep` 位置；
        - 以英文 TRACE 日志记录滑动详情（n_ctx/n_keep/delta/new_ncur 等）.
  - 引擎层（`LocalLLMLlamaCppHandler.java`）：
    - 在生成开始前启用滑动：`LlamaCppInference.set_context_shift(true, nKeep)`；
    - 默认 `n_keep = clamp(n_ctx/2, 256, 1024)` 以保留系统提示词与会话骨干；
    - 保留“最大输出 token 数”为生成循环软上限（UI 可设至 16384）。

- 最佳实践与注意事项：
  - n_keep 推荐范围：256–1024；对较小 n_ctx 使用相对更小的 n_keep，以平衡保留信息与可用窗口.
  - 上下文滑动不是长上下文扩展（不改变 n_ctx），而是“滚动窗口”；如需更长上下文，请结合 RoPE scaling（YaRN/Linear）.
  - 长时间生成对功耗与发热敏感，建议结合软停止条件（时长/字符数/停止词）与手动“停止”按钮；英文日志会标注滑动触发与停止原因，便于诊断.

- 兼容性与风险控制：
  - 若 `llama_memory_can_shift()` 返回 false，则降级为不滑动（英文 TRACE 日志），仍可按软上限生成.
  - 初期默认启用滑动；如需关闭，可在 Java 层 `set_context_shift(false, 0)` 关闭（留作内部开关）。

- 构建与验证：
  - Windows 调试构建：`./gradlew :app:assembleDebug -PKEYPSWD=abc-1234`；
  - Release 构建：`./gradlew assembleRelease -PKEYPSWD=abc-1234`；
  - 运行观察英文日志包含 `[CTX_SHIFT]`、`[STREAM]` 与 KV 操作调用；确认在 n_ctx 边界处能够继续输出且不中断.

- 回调语义与状态一致性（补充）
  - 停止行为：无论用户点击“停止”或触发全局停止标志，Java 引擎在 generateWithLlamaCpp 与 generateWithTraditionalStreaming 结束时都会回调 onComplete；停止时追加英文日志 "[STREAM] ... finalizing with onComplete" 以便诊断。
  - 目的：确保 LocalLlmHandler/LocalLlmAdapter 的上层状态机能稳定复位 READY/清理调用态，避免 UI 悬挂或下次调用被占用。
  - 异常路径：超时/错误仍走 onError，不改变既有语义。
  - 代码位置：<mcfile name="LocalLLMLlamaCppHandler.java" path="app/src/main/java/com/example/starlocalrag/api/LocalLLMLlamaCppHandler.java"></mcfile>
