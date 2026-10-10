# AGENTS.md — OfflineAI 开发规范

> 本文件是本仓库所有 AI 编码代理（ZCode / Devin / Trae / Claude 等）的统一工作规范。
> `.devin/rules/rules.md` 与 `.trae/rules/project_rules.md` 内容与此文件保持一致，以本文件为准。

## 1. 沟通语言

- 一律使用**中文**回答和解释问题。
- 代码中的**打印（Log）与注释必须使用英文**，与现有代码风格保持一致。

## 2. 构建与测试

- 存在代码修改需要构建验证时，Windows 下使用 **release 构建**（不要用 debug 构建）：
  ```bash
  ./gradlew assembleRelease -PKEYPSWD=abc-1234
  ```
  签名使用仓库根目录的 `key.jks`，keystore 密码 `abc-1234`。
- 构建产物位于 `app/build/outputs/apk/release/`。

## 3. 修改代码前的流程

改代码之前，先向用户说明以下几点，再动手：

1. **问题点**：当前实现有什么问题；
2. **考虑点**：涉及哪些模块 / 关联逻辑、有哪些风险；
3. **优化点**：可选的改进方向与取舍；
4. **怎么改**：本次的具体修改方案。

## 4. Git 纪律（重要）

- **不要自动提交 git**。用户明确让你提交时才提交。
- **不要随意 checkout / restore / reset 文件**，避免丢失用户之前的改动。
- 如果文件被改坏且你处理不了，**立即停止并通知用户**来处理，不要继续盲目操作。

## 5. 环境与故障处理

- 如果编辑文件总是出错（文件被 IDE / 其他进程锁定），可以停下来，请用户重启 IDE 后继续。
- 需要运行 Python 时，先切换 conda 环境：
  ```bash
  conda activate d:\yilei.wang\OfflineAI\.env
  ```

## 6. SPEC.md 文档维护

所有工作完成后，若本次工作涉及本项目**需求和代码的调整**（纯语法错误修复不算，其余酌情考虑），需要同步更新 `SPEC.md`：

- **不要更改 SPEC.md 的章节结构**；
- 若涉及需求变更、新需求、需求细化：
  - 将实现细化、代码关键点的增补/变更写入对应章节；
  - 功能设计、优化考虑与注意事项合并调整到相应章节；
  - 遵循"优化与注意事项 / 最佳实践"的组织方式；
- 整理时需**合并归纳、去重**，做全局性、有条理的补充，**不要堆屎山**。

## 7. 项目速览（新会话必读）

- **项目定位**：离线优先的 Android 端侧 AI 助手（LLM / VLM / ASR / TTS / Diffusion / 向量+图谱 RAG），推理统一走 MNN 栈。
- **技术规格书**：`SPEC.md`（约 1 万行）是理解需求与设计的唯一权威文档，动手前先查对应章节。
- **关键路径**：
  - 应用代码：`app/src/main/java/com/example/offlineai/`（Kotlin/Java 混合）
  - MNN 推理栈：`libs/mnn`（git 子模块，fork: `er6y/MNN`，上游: `alibaba/MNN`）
  - JNI 封装：`libs/mnn-jni/`（C++: `mnn_jni.cpp`，Java: `MnnInference.java`）
  - 配置：`config.json`（模型清单）、`gradle.properties`
- **子模块更新流程**（`libs/mnn`）：
  ```bash
  cd libs/mnn
  git fetch upstream --prune
  git rebase upstream/master   # 本地定制提交 rebase 到上游 master 之上
  cd ../.. && git add libs/mnn # 更新主仓库的子模块指针
  ```
  本地若有针对 MNN 的定制补丁，rebase 冲突需保留定制逻辑并适配上游变更。

## 8. GPU 训练机（远程 Linux，2×4090）

深度学习 / 模型转换 / 推理验证任务可使用内网 GPU 机（主机名 kemove-R5210-G11）：

- **连接**：`ssh yilei.wang@172.16.160.138`（用户名必须显式带，裸 IP 会拿本地用户名连导致 Permission denied；端口 22；本机 `~/.ssh/id_ed25519` 已在远端授权免密）
- **CUDA**：12.2，位于 `/usr/local/cuda-12.2`，`nvcc` 不在 PATH，调用必须写全路径 `/usr/local/cuda-12.2/bin/nvcc`
- **目录布局**（2026-10-10 确认）：
  - `/ux/work/yilei.wang/MNN` —— MNN 源码仓库，可 pull 同步到 `er6y/MNN` fork 最新 master
  - `/ux/work/yilei.wang/` —— 各模型工作目录，如 `Z-Image-Turbo/`、`Qwen-Image-2.1/`
- **禁区（重要）**：
  - ⛔ `/ux/work/yilei.wang/ldpc` —— 隔壁 **LDPC 项目**的目录，**绝对不要读写、写入任何东西**；
  - ⛔ 禁写 `/tmp`、`/home`；
  - 机上常有其他任务在跑（`n19_`、`results_floor2.csv` 等），连上去先只读观察，不要动正在运行的文件。
- **连通性验证**：`ssh yilei.wang@172.16.160.138 'nvidia-smi'`
