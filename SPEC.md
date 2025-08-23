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
- 上游目录保持洁净：libs/llama.cpp-master 下的文件不再直接修改，若出现临时改动需及时还原（checkout/覆盖方式），升级上游时以 patch 作为变更来源。
- 扩展与特性处理最佳实践：
  - VK_KHR_16bit_storage：优先检测 core feature（Vulkan ≥ 1.1）与扩展声明，仅在扩展存在时才 push 到 device_extensions；当缺失 16-bit storage feature 时，回退到 32-bit 模式，英文日志提示："does not support 16-bit storage, falling back to 32-bit mode"。
  - VK_KHR_shader_non_semantic_info：在校验层/验证场景下，存在该扩展时再启用，避免无效请求。
  - 实例创建前的 loader/符号守护：在启用 VULKAN_HPP_DISPATCH_LOADER_DYNAMIC 时，优先初始化 dispatcher；可选地通过 GGML_VK_LOADER_GUARD 保护 vkEnumerateInstanceVersion / vkCreateInstance 的可用性，缺失时英文日志直接跳过后端初始化。
  - 设备枚举与回退：优先选择离散/非 CPU 设备；找不到 GPU 时作为兜底可以选择首个 CPU 设备（如 SwiftShader），并在日志中打印详细设备列表，便于排查。
- 日志规范：Vulkan 相关日志统一英文，便于跨平台/跨团队沟通与检索；Debug 级别的信息不影响正常用户使用体验。

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
