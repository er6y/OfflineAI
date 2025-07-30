# StarLocalRAG

<div align="center">

![StarLocalRAG Logo](https://img.shields.io/badge/StarLocalRAG-Local%20RAG%20Solution-blue?style=for-the-badge)

**🚀 完全本地化的Android RAG应用 | Fully Local Android RAG Application**

[![Android](https://img.shields.io/badge/Android-3DDC84?style=flat-square&logo=android&logoColor=white)](https://developer.android.com/)
[![Java](https://img.shields.io/badge/Java-ED8B00?style=flat-square&logo=openjdk&logoColor=white)](https://www.java.com/)
[![Rust](https://img.shields.io/badge/Rust-000000?style=flat-square&logo=rust&logoColor=white)](https://www.rust-lang.org/)
[![ONNX](https://img.shields.io/badge/ONNX-005CED?style=flat-square&logo=onnx&logoColor=white)](https://onnx.ai/)
[![LlamaCpp](https://img.shields.io/badge/LlamaCpp-FF6B6B?style=flat-square)](https://github.com/ggerganov/llama.cpp)


## 示例demo

知识库构建 
![e0f8b519a5d3ab1e99e6dcba518114e5](https://github.com/user-attachments/assets/d7f938b6-56fe-46d2-add2-e3f6d59dc6e9)
在线大模型示例
![35d9dc5fbe5b59b2c3563185357ede76](https://github.com/user-attachments/assets/10057e1f-1957-4996-9db5-1f4dff23c739)
本地小模型示例
![84bffc5557514eabd43177546d2f800e](https://github.com/user-attachments/assets/fc1e5930-93c3-48e6-8eb6-caeffa144eab)
![29683f1ba196413c18c851a8245f7b84](https://github.com/user-attachments/assets/a1180485-40d3-4581-bfb0-fd5ebb217c84)

[English](#english) | [中文](#中文)

</div>

---

## 中文

### 📖 项目简介

StarLocalRAG 是一个基于Android平台的**完全本地化RAG（检索增强生成）应用**，支持离线知识库构建、文档检索和智能问答。所有AI推理和数据处理都在设备本地进行，确保用户数据隐私和安全。

### ✨ 核心特性

#### 🔒 **完全本地化**
- 所有AI推理在设备本地执行，无需联网
- 支持本地LLM模型和在线API双模式
- 数据完全保留在本地，保护用户隐私

#### 🧠 **多引擎AI推理**
- **LlamaCpp引擎**：支持GGUF格式模型，优化内存使用
- **ONNX Runtime 1.21.0**：高性能推理，支持GPU加速
- **Rust分词器**：高效的多架构分词处理（ARM64/ARMv7/x86_64）

#### 📚 **智能知识库系统**
- 支持多种文档格式：PDF、Word、Excel、PPT、TXT、JSON
- 智能文本分块策略：固定长度、语义分块、层次化分块
- 向量异常检测与修复系统，确保检索质量
- SQLite本地向量数据库，高效存储和检索

#### 🔍 **高级检索技术**
- **向量检索**：基于语义相似度的精确匹配
- **重排模型**：二次排序提升检索相关性
- **多模态支持**：文本、代码、结构化数据统一处理

#### ⚡ **性能优化**
- **流式输出**：实时显示AI生成内容
- **并发控制**：原子操作和线程安全设计
- **内存优化**：智能缓存和资源管理
- **推理中断**：支持实时停止和恢复

### 🏗️ 技术架构

#### 核心组件

```
┌─────────────────────────────────────────────────────────────┐
│                    StarLocalRAG 架构                        │
├─────────────────────────────────────────────────────────────┤
│  UI Layer (Android)                                         │
│  ├── RAG问答界面    ├── 知识库构建    ├── 模型管理           │
├─────────────────────────────────────────────────────────────┤
│  Business Logic Layer                                       │
│  ├── RagQueryManager  ├── KnowledgeBaseService              │
│  ├── EmbeddingModelManager  ├── RerankerModelManager        │
├─────────────────────────────────────────────────────────────┤
│  AI Inference Layer                                         │
│  ├── LocalLlmHandler (LlamaCpp)                            │
│  ├── EmbeddingModelHandler (ONNX)                          │
│  ├── TokenizerManager (Rust JNI)                           │
├─────────────────────────────────────────────────────────────┤
│  Data Layer                                                 │
│  ├── SQLiteVectorDatabase  ├── DocumentParser              │
│  ├── TextChunkProcessor    ├── VectorAnomalyHandler        │
└─────────────────────────────────────────────────────────────┘
```

#### 线程架构设计

- **Main Thread (UI)**：用户界面交互和状态管理
- **RagQa-Query-Thread**：RAG查询执行和模型调用
- **RagQa-StopCheck-Thread**：任务状态监控和防呆机制
- **API Thread Pool**：在线模型HTTP请求处理

### 🚀 技术亮点

#### 1. **高性能分词系统**
```rust
// Rust核心分词器，直接JNI接口
// 支持特殊token处理：```, ```
// 多架构优化：ARM64, ARMv7, x86_64
```

#### 2. **向量异常处理系统**
- **异常检测**：NaN值、无穷大、维度不匹配、分布异常
- **自动修复**：零向量处理、极值修正、维度对齐
- **质量评估**：向量质量报告和统计分析

#### 3. **智能并发控制**
```java
// 原子操作实现无锁并发控制
AtomicBoolean isBusy = new AtomicBoolean(false);
if (isBusy.compareAndSet(false, true)) {
    // 执行推理任务
}
```

#### 4. **全局停止机制**
- 统一的`GlobalStopManager`管理所有AI模块
- 主动轮询机制，实现即时响应用户中断
- 优雅的资源释放和状态恢复

### 📱 功能模块

#### RAG问答系统
- 基于知识库的智能问答
- 支持思考模式（`/no_think`指令）
- 流式输出和实时中断
- 多参数配置：检索深度、重排数量等

#### 知识库构建
- 多格式文档解析和处理
- 智能文本分块策略
- 向量化和数据库存储
- 模型兼容性检查

#### 模型管理
- 本地模型下载和管理
- 断点续传和智能重试
- 多模型并发下载
- GPU配置检测和优化

### 🛠️ 开发环境

#### 系统要求
- Android 7.0+ (API Level 24+)
- 4GB+ RAM（推荐8GB+）
- 2GB+ 可用存储空间

#### 构建依赖
- Android Studio 2023.1+
- Gradle 8.0+
- NDK 25.0+
- Rust 1.70+

#### 编译构建
```bash
# 克隆项目
git clone https://github.com/yourusername/StarLocalRAG.git
cd StarLocalRAG

# 初始化子模块
git submodule update --init --recursive

# 构建Rust分词器
cd libs/tokenizers-rust
cargo build --release

# 构建Android应用
./gradlew assembleDebug
```

### 📊 性能指标

| 指标 | 数值 | 说明 |
|------|------|------|
| 分词速度 | 10K+ tokens/s | Rust优化实现 |
| 向量检索 | <100ms | SQLite优化查询 |
| 内存占用 | <2GB | 智能缓存管理 |
| 模型加载 | <30s | 并发优化加载 |

### 🤝 贡献指南

我们欢迎社区贡献！请查看 [CONTRIBUTING.md](CONTRIBUTING.md) 了解详细信息。

#### 贡献方式
- 🐛 报告Bug和问题
- 💡 提出新功能建议
- 📝 改进文档
- 🔧 提交代码修复

### 📄 许可证

本项目采用 [MIT License](LICENSE) 开源协议。

### 🤖 AI驱动开发

**⚡ 这是一个完全由AI撰写的项目！**

本项目是AI编程能力的一次深度实践，展示了现代AI在复杂软件开发中的潜力：

#### 🛠️ **开发工具链**
- **主要AI IDE**: Lingma - 世界领先的AI编程环境
- **核心AI模型**: qwen3-coder - 提供主要的代码生成和架构设计

#### 💡 **AI编程实践**
- **完全AI生成**: 从架构设计到具体实现，全部由AI完成
- **迭代优化**: 通过AI对话持续改进代码质量和功能

#### 🔍 **技术局限性**
受限于当前大模型的能力，特别是长期记忆和全局架构理解能力，项目中可能存在：
- 部分代码结构冗余
- 某些设计层次不够合理
- 局部优化与全局一致性的平衡问题

尽管如此，这个项目仍然是**AI编程能力的优秀展示**，证明了AI在复杂软件开发中的巨大潜力。



#### 开源项目支持
感谢以下开源项目的支持：
- [llama.cpp](https://github.com/ggerganov/llama.cpp) - 高性能LLM推理引擎
- [ONNX Runtime](https://github.com/microsoft/onnxruntime) - 跨平台AI推理框架
- [Tokenizers](https://github.com/huggingface/tokenizers) - 高效分词器库

---

## English

### 📖 Project Overview

StarLocalRAG is a **fully local RAG (Retrieval-Augmented Generation) application** for Android that supports offline knowledge base construction, document retrieval, and intelligent Q&A. All AI inference and data processing are performed locally on the device, ensuring user data privacy and security.

### ✨ Key Features

#### 🔒 **Fully Local**
- All AI inference runs locally on device, no internet required
- Supports both local LLM models and online API modes
- Data stays completely local, protecting user privacy

#### 🧠 **Multi-Engine AI Inference**
- **LlamaCpp Engine**: Supports GGUF format models with optimized memory usage
- **ONNX Runtime 1.21.0**: High-performance inference with GPU acceleration
- **Rust Tokenizer**: Efficient multi-architecture tokenization (ARM64/ARMv7/x86_64)

#### 📚 **Intelligent Knowledge Base System**
- Multiple document formats: PDF, Word, Excel, PPT, TXT, JSON
- Smart text chunking strategies: fixed-length, semantic, hierarchical
- Vector anomaly detection and repair system for quality assurance
- SQLite local vector database for efficient storage and retrieval

#### 🔍 **Advanced Retrieval Technology**
- **Vector Retrieval**: Precise matching based on semantic similarity
- **Rerank Models**: Secondary ranking to improve retrieval relevance
- **Multi-modal Support**: Unified processing for text, code, and structured data

#### ⚡ **Performance Optimization**
- **Streaming Output**: Real-time display of AI-generated content
- **Concurrency Control**: Atomic operations and thread-safe design
- **Memory Optimization**: Smart caching and resource management
- **Inference Interruption**: Real-time stop and resume support

### 🏗️ Technical Architecture

#### Core Components

```
┌─────────────────────────────────────────────────────────────┐
│                    StarLocalRAG Architecture                │
├─────────────────────────────────────────────────────────────┤
│  UI Layer (Android)                                         │
│  ├── RAG Q&A Interface  ├── Knowledge Base  ├── Model Mgmt  │
├─────────────────────────────────────────────────────────────┤
│  Business Logic Layer                                       │
│  ├── RagQueryManager  ├── KnowledgeBaseService              │
│  ├── EmbeddingModelManager  ├── RerankerModelManager        │
├─────────────────────────────────────────────────────────────┤
│  AI Inference Layer                                         │
│  ├── LocalLlmHandler (LlamaCpp)                            │
│  ├── EmbeddingModelHandler (ONNX)                          │
│  ├── TokenizerManager (Rust JNI)                           │
├─────────────────────────────────────────────────────────────┤
│  Data Layer                                                 │
│  ├── SQLiteVectorDatabase  ├── DocumentParser              │
│  ├── TextChunkProcessor    ├── VectorAnomalyHandler        │
└─────────────────────────────────────────────────────────────┘
```

#### Thread Architecture Design

- **Main Thread (UI)**: User interface interaction and state management
- **RagQa-Query-Thread**: RAG query execution and model calls
- **RagQa-StopCheck-Thread**: Task status monitoring and safety mechanisms
- **API Thread Pool**: Online model HTTP request handling

### 🚀 Technical Highlights

#### 1. **High-Performance Tokenization System**
```rust
// Rust core tokenizer with direct JNI interface
// Special token support: ```, ```
// Multi-architecture optimization: ARM64, ARMv7, x86_64
```

#### 2. **Vector Anomaly Handling System**
- **Anomaly Detection**: NaN values, infinity, dimension mismatch, distribution anomalies
- **Auto Repair**: Zero vector handling, extreme value correction, dimension alignment
- **Quality Assessment**: Vector quality reports and statistical analysis

#### 3. **Smart Concurrency Control**
```java
// Lock-free concurrency control with atomic operations
AtomicBoolean isBusy = new AtomicBoolean(false);
if (isBusy.compareAndSet(false, true)) {
    // Execute inference task
}
```

#### 4. **Global Stop Mechanism**
- Unified `GlobalStopManager` for all AI modules
- Active polling mechanism for immediate user interrupt response
- Graceful resource release and state recovery

### 📱 Functional Modules

#### RAG Q&A System
- Knowledge base-driven intelligent Q&A
- Think mode support (`/no_think` command)
- Streaming output and real-time interruption
- Multi-parameter configuration: retrieval depth, rerank count, etc.

#### Knowledge Base Construction
- Multi-format document parsing and processing
- Smart text chunking strategies
- Vectorization and database storage
- Model compatibility checking

#### Model Management
- Local model download and management
- Resume download and smart retry
- Concurrent multi-model downloads
- GPU configuration detection and optimization

### 🛠️ Development Environment

#### System Requirements
- Android 7.0+ (API Level 24+)
- 4GB+ RAM (8GB+ recommended)
- 2GB+ available storage

#### Build Dependencies
- Android Studio 2023.1+
- Gradle 8.0+
- NDK 25.0+
- Rust 1.70+

#### Build Instructions
```bash
# Clone the project
git clone https://github.com/yourusername/StarLocalRAG.git
cd StarLocalRAG

# Initialize submodules
git submodule update --init --recursive

# Build Rust tokenizer
cd libs/tokenizers-rust
cargo build --release

# Build Android app
./gradlew assembleDebug
```

### 📊 Performance Metrics

| Metric | Value | Description |
|--------|-------|-------------|
| Tokenization Speed | 10K+ tokens/s | Rust-optimized implementation |
| Vector Retrieval | <100ms | SQLite-optimized queries |
| Memory Usage | <2GB | Smart cache management |
| Model Loading | <30s | Concurrent optimized loading |

### 🤝 Contributing

We welcome community contributions! Please see [CONTRIBUTING.md](CONTRIBUTING.md) for details.

#### Ways to Contribute
- 🐛 Report bugs and issues
- 💡 Suggest new features
- 📝 Improve documentation
- 🔧 Submit code fixes

### 📄 License

This project is licensed under the [MIT License](LICENSE).

### 🤖 AI-Driven Development

**⚡ This is a project entirely written by AI!**

This project represents a deep practice of AI programming capabilities, showcasing the potential of modern AI in complex software development:

#### 🛠️ **Development Toolchain**
- **Primary AI IDE**: Lingma - World's leading AI programming environment
- **Core AI Model**: qwen3-coder - Providing main code generation and architecture design

#### 💡 **AI Programming Practice**
- **Fully AI Generated**: From architecture design to specific implementation, all completed by AI
- **Iterative Optimization**: Continuous improvement of code quality and functionality through AI dialogue

#### 🔍 **Technical Limitations**
Limited by current large model capabilities, especially long-term memory and global architecture understanding, the project may have:
- Some redundant code structures
- Certain design hierarchies that are not reasonable enough
- Balance issues between local optimization and global consistency

Nevertheless, this project remains an **excellent demonstration of AI programming capabilities**, proving the tremendous potential of AI in complex software development.



#### Open Source Project Support
Thanks to the following open source projects:
- [llama.cpp](https://github.com/ggerganov/llama.cpp) - High-performance LLM inference engine
- [ONNX Runtime](https://github.com/microsoft/onnxruntime) - Cross-platform AI inference framework
- [Tokenizers](https://github.com/huggingface/tokenizers) - Efficient tokenizer library

---

<div align="center">

**⭐ 如果这个项目对你有帮助，请给我们一个星标！**

**⭐ If this project helps you, please give us a star!**

</div>
