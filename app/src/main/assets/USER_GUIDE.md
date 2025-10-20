# OfflineAI 用户指南 / User Guide

> When the net is gone, the mind stays on.

## 引言 / Introduction

OfflineAI 是一款离线优先的安卓 AI 应用，秉持“网络离线，思维常在”的产品理念，强调在设备本地完成模型推理与知识处理，确保隐私与可用性。

OfflineAI 的核心能力包括：本地对话、私有知识管理（含检索增强生成 RAG）、知识笔记与多模态推理。RAG 是本应用的重要功能之一，但并非唯一重点；本指南将以离线使用体验为中心阐述，RAG 原理与用法作为专题章节进行说明。

OfflineAI is an offline-first Android AI application. Guided by the motto "When the net is gone, the mind stays on.", it focuses on completing model inference and knowledge processing locally on-device to ensure privacy and availability.

Core capabilities include local conversation, private knowledge management (with RAG), knowledge notes, and multimodal inference. RAG is a major capability but not the only focus; this guide centers on offline experience and presents RAG as a dedicated topic.

## 1. RAG 基本原理介绍 / RAG Basic Principles

RAG（检索增强生成 / Retrieval-Augmented Generation）是一种结合了检索系统和生成式AI的技术框架，能够让大语言模型(LLM)基于特定知识回答问题，提高回答的准确性和可靠性。

RAG is a technical framework that combines retrieval systems with generative AI, enabling Large Language Models (LLM) to answer questions based on specific knowledge, improving accuracy and reliability.

### RAG 工作流程 / RAG Workflow

```text
Knowledge Vector Database Construction:
┌------------------┐     ┌-------------------┐     ┌-------------------┐
│                  │     │                   │     │  Text Vectorize   │
│  User Documents  │ --> │   Text Chunking   │ --> │  Tokenize &       │
│                  │     │ (Size/Overlap)    │     │  Embed & Normalize│
└------------------┘     └-------------------┘     └-------------------┘
                                                            │           
RAG Q&A Process:                                            v           
┌------------------┐     ┌-------------------┐     ┌-------------------┐
│                  │     │  Text Vectorize   │     │                   │
│  User Question   │ --> │  Tokenize &       │ --> │ Vector Database   │
│                  │     │  Embed & Normalize│     │    Retrieval      │
└------------------┘     └-------------------┘     └-------------------┘
                                                            │           
                                                            v           
┌------------------┐     ┌-------------------┐     ┌-------------------┐
│                  │     │                   │     │                   │
│ Generate Answer  │ <-- │ Large Language    │ <-- │ Build Context     │
│                  │     │ Model (LLM)       │     │                   │
└------------------┘     └-------------------┘     └-------------------┘                     
```

### RAG 核心步骤 / RAG Core Steps

1. **知识库构建 / Knowledge Base Construction**：
   - 导入文档（PDF、Word、文本等） / Import documents (PDF, Word, text, etc.)
   - 文本分块处理 / Text chunking processing
   - 使用嵌入模型将文本块转换为向量 / Convert text chunks to vectors using embedding models
   - 存储文本及其向量到向量数据库 / Store text and vectors in vector database

2. **问答交互 / Q&A Interaction**：
   - 用户提出问题 / User asks questions
   - 问题向量化 / Question vectorization
   - 在向量数据库中检索相似文本块 / Retrieve similar text chunks from vector database
   - 将检索结果与问题一起发送给大语言模型 / Send retrieval results with question to LLM
   - 大语言模型生成基于知识库的回答 / LLM generates knowledge-based answers

3. **优势 / Advantages**：
   - 回答基于特定知识，减少"幻觉" / Answers based on specific knowledge, reducing "hallucinations"
   - 无需重新训练模型即可更新知识 / Update knowledge without retraining models
   - 保持私密性，数据不离开本地设备 / Maintain privacy, data stays on local device

### 文本分块策略 / Text Chunking Strategy

#### 1. 暴力分块方法 / Brute Force Chunking
- **定义 / Definition**：将文档按固定长度(如500字符)简单切分 / Simply split documents by fixed length (e.g., 500 characters)
- **优势 / Advantages**：
  - 实现简单，计算成本低 / Simple implementation, low computational cost
  - 适合内容结构简单的文档 / Suitable for documents with simple content structure
- **缺陷 / Disadvantages**：
  - 容易切断语义连贯性 / Easy to break semantic coherence
  - 检索结果可能包含无关内容 / Retrieval results may contain irrelevant content
  - 对LLM归纳能力要求高 / High requirements for LLM summarization ability

#### 2. 检索结果分析 / Retrieval Result Analysis
- **有用信息 / Useful Information**：与问题直接相关的文本片段 / Text segments directly related to the question
- **噪声信息 / Noise Information**：
  - 同一文本块中不相关内容 / Irrelevant content in the same text chunk
  - 格式标记、页眉页脚等 / Format markers, headers, footers, etc.
- **对LLM的要求 / Requirements for LLM**：
  - 需要从混杂内容中提取关键信息 / Need to extract key information from mixed content
  - 必须识别并忽略无关内容 / Must identify and ignore irrelevant content
  - 需保持原始语义的准确性 / Need to maintain accuracy of original semantics

#### 3. 推荐分块策略 / Recommended Chunking Strategies
| Strategy / 策略            | Use Case / 使用场景             | Example Params / 示例参数    | Advantages / 优势        |
|----------------------------|--------------------------------|-----------------------------|--------------------------|
| Fixed Length / 固定长度     | Tech Docs/Code / 技术文档/代码  | 500 chars, 100              | Simple & Fast / 简单快速  |
| Semantic Chunk / 语义分块   | Narrative Content / 叙述内容    |   By Paragraph / 按段落      | Keep Semantic / 保持语义 |
| Hierarchical / 层次化       | Structured Docs / 结构化文档    | Title+Content / 标题+内容    | Keep Context / 保持上下文 |
| Dynamic Chunk / 动态分块    | Mixed Content / 混合内容        | Content Analysis / 内容分析  | Adaptive Best / 自适应最优|

**最佳实践建议 / Best Practice Recommendations**：
1. 技术文档 / Technical Documents：500-1000字符分块，20%重叠 / 500-1000 character chunks, 20% overlap
2. 叙述文本 / Narrative Text：按自然段落分块 / Chunk by natural paragraphs
3. 混合内容 / Mixed Content：先按结构分块，再对长块二次分块 / Structure-based chunking first, then secondary chunking for long blocks
4. 添加元数据标记块类型和关键字段 / Add metadata to mark chunk types and key fields

## 2. OfflineAI 软件介绍 / Software Introduction

OfflineAI 是一款离线优先的安卓 AI 应用，允许用户在设备本地构建私有知识、进行自然语言对话并管理知识笔记，无需依赖云服务。RAG 是本应用的重要能力之一，但不是唯一重点。应用支持多种文档格式，使用嵌入模型进行向量化，并通过本地数据库进行高效检索。

OfflineAI is an Android offline-first AI application that allows users to build private knowledge bases, conduct natural language conversations, and manage notes locally on their devices without relying on cloud services. RAG is one of the major capabilities, not the only focus. The app supports multiple document formats, uses embedding models for vectorization, and performs efficient retrieval through local databases.

### 主要特点 / Key Features

- **完全本地化 / Fully Local**：所有处理和存储都在设备本地进行，保护数据隐私 / All processing and storage are performed locally on the device, protecting data privacy
- **多格式支持 / Multi-format Support**：支持PDF、Word、Excel、PPT、文本等多种文档格式 / Supports PDF, Word, Excel, PPT, text and other document formats
- **多模态推理 / Multimodal Inference**：支持图像-文本多模态模型（如 LLaVA、Qwen-VL、InternVL 等），在离线环境进行多模态理解与回答 / Supports vision-language models (e.g., LLaVA, Qwen-VL, InternVL) for multimodal understanding and answering offline
- **AI绘图 / AI Image Generation**：支持Stable Diffusion文本生成图像（Text-to-Image），在本地设备进行AI绘图创作 / Supports Stable Diffusion text-to-image generation for AI art creation on local devices
- **聊天历史管理 / Chat History Management**：支持查看、保存、加载和切换多个聊天会话，方便管理对话历史 / Supports viewing, saving, loading and switching between multiple chat sessions for easy conversation history management
- **知识笔记 / Knowledge Notes**：支持创建和管理知识笔记，方便整理和扩充知识库 / Supports creating and managing knowledge notes for easy organization and expansion of knowledge base
- **灵活配置 / Flexible Configuration**：支持自定义嵌入模型、分块大小、API设置等 / Supports custom embedding models, chunk sizes, API settings, etc.
- **性能优化 / Performance Optimization**：优化内存使用和处理速度，支持大文件处理 / Optimized memory usage and processing speed, supports large file processing

## 3. 使用说明 / User Instructions

### 3.1 主界面导航 / Main Interface Navigation

OfflineAI 应用包含三个主要页面，通过底部导航栏进行切换：

The OfflineAI app contains three main pages, accessible through the bottom navigation bar:

- **RAG问答 / RAG Q&A**：进行基于知识库的问答交互 / Conduct knowledge base-based Q&A interactions
- **构建知识库 / Build Knowledge Base**：创建和更新知识库 / Create and update knowledge bases
- **知识笔记 / Knowledge Notes**：创建和管理知识笔记 / Create and manage knowledge notes

### 3.2 问答页面（支持RAG） / Q&A Page (with RAG)

#### 界面元素说明 / Interface Elements

- **API URL下拉框 / API URL Dropdown**：选择或输入LLM服务的API地址 / Select or input LLM service API address
- **API密钥输入框 / API Key Input**：输入API密钥 / Enter API key
- **模型选择下拉框 / Model Selection Dropdown**：选择使用的LLM模型 / Select LLM model to use
- **知识库下拉框 / Knowledge Base Dropdown**：选择要查询的知识库 / Select knowledge base to query
- **系统提示词输入框 / System Prompt Input**：设置发送给LLM的系统提示词 / Set system prompt to send to LLM
- **用户问题输入框 / User Question Input**：输入要询问的问题 / Enter question to ask
- **回答显示区域 / Answer Display Area**：显示AI回答，支持Markdown渲染 / Display AI answers with Markdown rendering support
- **发送/停止按钮 / Send/Stop Button**：发送问题或停止生成 / Send question or stop generation
- **近似深度设置 / Approximate Depth Setting**：控制向量数据库检索后提交给模型的文本块数量 / Controls the number of text chunks submitted to the model after vector database retrieval
  - 值越大，提交的上下文越多，回答可能更准确但速度更慢、消耗更多token / Larger values provide more context, potentially more accurate answers but slower speed and more token consumption
  - 值越小，响应更快但可能遗漏相关信息 / Smaller values provide faster response but may miss relevant information
  - 建议根据需求平衡：一般问答3-5，精确检索可设8-10 / Recommended balance based on needs: 3-5 for general Q&A, 8-10 for precise retrieval
- **重排数量设置 / Rerank Count Setting**：控制使用重排模型对检索结果进行重新排序的文本块数量 / Controls the number of text chunks for reranking model to reorder retrieval results
  - 重排模型能够更准确地评估文本块与问题的相关性 / Rerank models can more accurately evaluate the relevance between text chunks and questions
  - 通常设置为近似深度的2-3倍，然后从中选择最相关的文本块 / Usually set to 2-3 times the approximate depth, then select the most relevant text chunks
  - 例如：近似深度5，重排数量可设置为10-15 / Example: approximate depth 5, rerank count can be set to 10-15
  - 启用重排会增加处理时间，但能显著提高检索质量 / Enabling reranking increases processing time but significantly improves retrieval quality

#### 操作说明 / Operation Instructions

1. **API设置 / API Setup**：
   - 在API URL下拉框中选择预设的API地址或输入自定义地址 / Select preset API address from dropdown or input custom address
   - 在API密钥输入框中输入对应的API密钥 / Enter corresponding API key in the input field
   - 支持OpenAI、Claude、本地LLM等多种API格式 / Supports multiple API formats including OpenAI, Claude, local LLM, etc.

2. **模型选择 / Model Selection**：
   - 从模型下拉框中选择要使用的LLM模型 / Select LLM model to use from the dropdown
   - 不同模型有不同的能力和成本特点 / Different models have different capabilities and cost characteristics

3. **知识库选择 / Knowledge Base Selection**：
   - 从知识库下拉框中选择要查询的知识库 / Select knowledge base to query from the dropdown
   - 确保知识库已经构建完成 / Ensure the knowledge base has been built

4. **设置参数 / Parameter Settings**：
   - 根据需要调整近似深度（推荐3-8） / Adjust approximate depth as needed (recommended 3-8)
   - 如果启用了重排模型，设置重排数量（通常为近似深度的2-3倍） / If rerank model is enabled, set rerank count (usually 2-3 times the approximate depth)
   - 可选择性修改系统提示词 / Optionally modify system prompt

5. **提问 / Ask Questions**：
   - 在问题输入框中输入问题 / Enter question in the input field
   - 点击发送按钮开始问答 / Click send button to start Q&A
   - 可随时点击停止按钮中断生成 / Can click stop button anytime to interrupt generation

6. **聊天历史管理 / Chat History Management**：
   - **查看历史 / View History**：点击界面右上角的"历史"按钮查看所有聊天会话 / Click "History" button in top-right corner to view all chat sessions
   - **切换会话 / Switch Session**：在历史列表中点击任意会话即可切换并加载该对话 / Click any session in history list to switch and load that conversation
   - **保存会话 / Save Session**：当前对话会自动保存，支持多个独立会话并存 / Current conversation is automatically saved, supports multiple independent sessions
   - **删除会话 / Delete Session**：在历史列表中长按会话可删除不需要的对话 / Long-press session in history list to delete unwanted conversations
   - **会话命名 / Session Naming**：会话自动以时间戳命名，方便识别和管理 / Sessions are automatically named with timestamps for easy identification and management

7. **其他功能 / Other Features**：
   - 可以通过长按回答文本进行复制或转为笔记 / Can long-press answer text to copy or convert to notes
   - 点击停止按钮可以中断生成过程 / Click stop button to interrupt generation process

#### 多模态推理 / Multimodal Inference

OfflineAI 支持多模态模型（Vision-Language Models, VLM），可以在本地进行图像理解和视觉问答。

OfflineAI supports multimodal models (Vision-Language Models, VLM) for local image understanding and visual Q&A.

**支持的模型类型 / Supported Model Types**：
- **LLaVA 系列**：开源视觉-语言模型，适合移动设备 / Open-source vision-language model, suitable for mobile devices
- **Qwen-VL 系列**：阿里通义千问多模态版本，中文能力强 / Alibaba Qwen multimodal version, strong Chinese capability
- **InternVL 系列**：商汤多模态模型，多场景理解能力优秀 / SenseTime multimodal model, excellent multi-scenario understanding

**使用步骤 / Usage Steps**：
1. **下载多模态模型** / Download multimodal model
   - 通过"默认模型下载"功能获取预设模型 / Get preset models through "Default Model Download"
   - 或手动下载MNN格式的多模态模型文件 / Or manually download MNN format multimodal model files
   - 确保模型包含 `config.json`、`llm.mnn`、`llm.mnn.weight` 和 `visual.mnn` 等文件 / Ensure model contains `config.json`, `llm.mnn`, `llm.mnn.weight`, and `visual.mnn` files

2. **选择多模态模型** / Select multimodal model
   - 在问答页面的模型选择下拉框中选择多模态模型 / Select multimodal model in model selection dropdown on Q&A page
   - 应用会自动检测模型是否支持视觉特性 / App automatically detects if model supports vision features
   - 支持多模态的模型会显示图像输入按钮 / Models supporting multimodal will show image input button

3. **输入图像和问题** / Input image and question
   - 点击图像输入按钮，选择要分析的图片（支持 `.png`/`.jpg`/`.jpeg`/`.webp`）/ Click image input button, select image to analyze (supports `.png`/`.jpg`/`.jpeg`/`.webp`)
   - 在文本框中输入关于图像的问题或指令 / Enter question or instruction about the image in text box
   - 例如："描述这张图片的内容" / "请识别图中的物体" / "这张图片中有什么？" / Example: "Describe the content of this image" / "Identify objects in the image" / "What's in this picture?"

4. **获取回答** / Get answer
   - 点击发送按钮，模型会处理图像和文本 / Click send button, model will process image and text
   - 首次加载可能需要较长时间（视觉模型初始化）/ First-time loading may take longer (vision model initialization)
   - 模型会基于图像内容生成回答 / Model generates answer based on image content

**使用建议 / Usage Recommendations**：
- **模型选择** / Model Selection
  - 移动设备建议选择小尺寸模型（1B-3B参数）/ Mobile devices recommend small models (1B-3B parameters)
  - 大模型（7B+）可能导致内存不足或运行缓慢 / Large models (7B+) may cause out-of-memory or slow performance
  - 优先选择量化版本（INT4/INT8）以节省内存 / Prioritize quantized versions (INT4/INT8) to save memory

- **图像质量** / Image Quality
  - 使用清晰、光线充足的图片效果更好 / Use clear, well-lit images for better results
  - 避免过大的图片（建议 < 5MB）/ Avoid oversized images (recommend < 5MB)
  - 图片会自动调整大小以适配模型输入要求 / Images are automatically resized to match model input requirements

- **提示词技巧** / Prompt Tips
  - 提示词越具体，回答质量越好 / More specific prompts lead to better answers
  - 可以要求模型关注特定细节 / Can ask model to focus on specific details
  - 支持多轮对话，可以追问图像相关问题 / Supports multi-turn dialogue, can ask follow-up questions about the image

- **性能优化** / Performance Optimization
  - 首次加载模型耗时较长，后续使用会更快 / First-time model loading takes longer, subsequent use is faster
  - 在设置中调整"图片预处理尺寸"可以平衡速度和质量 / Adjust "Image Preprocessing Size" in settings to balance speed and quality
  - 调整"图像编码线程数"可以加快图像处理速度 / Adjust "Image Encoding Threads" to speed up image processing

**注意事项 / Notes**：
- ⚠️ 多模态模型需要MNN框架支持视觉特性编译 / Multimodal models require MNN framework compiled with vision features
- ⚠️ 模型文件通常较大（1-5GB），下载前确保存储空间充足 / Model files are usually large (1-5GB), ensure sufficient storage before downloading
- ⚠️ 如果当前选择的模型不支持多模态，图像输入按钮不会显示 / If current model doesn't support multimodal, image input button won't appear
- ⚠️ 不同模型的图像输入尺寸和预处理方式可能不同 / Different models may have different image input sizes and preprocessing methods
- ⚠️ 多模态推理比纯文本推理消耗更多资源 / Multimodal inference consumes more resources than text-only inference

#### AI绘图（Diffusion）/ AI Image Generation (Diffusion)

OfflineAI 支持 Stable Diffusion 文本生成图像（Text-to-Image），可以在本地设备上进行AI绘图创作，无需云端服务。

OfflineAI supports Stable Diffusion text-to-image generation for AI art creation on local devices without cloud services.

**支持的模型 / Supported Models**：
- **Stable Diffusion 1.5**：经典版本，速度快，适合移动设备 / Classic version, fast speed, suitable for mobile devices
- **太乙（Taiyi）中文SD**：针对中文提示词优化的版本 / Version optimized for Chinese prompts

**使用步骤 / Usage Steps**：

1. **下载Diffusion模型 / Download Diffusion Model**
   - 通过"默认模型下载"功能获取预设的Diffusion模型 / Get preset Diffusion models through "Default Model Download"
   - 或手动下载MNN格式的Diffusion模型 / Or manually download MNN format Diffusion models
   - 模型目录需包含：`text_encoder.mnn`、`unet.mnn`、`vae_decoder.mnn`、`vocab.json`、`merges.txt` / Model directory must contain: `text_encoder.mnn`, `unet.mnn`, `vae_decoder.mnn`, `vocab.json`, `merges.txt`

2. **选择Diffusion模型 / Select Diffusion Model**
   - 在问答页面的模型选择下拉框中选择Diffusion模型 / Select Diffusion model in model selection dropdown on Q&A page
   - 应用会自动识别Diffusion模型（检测到text_encoder.mnn文件）/ App automatically recognizes Diffusion models (detects text_encoder.mnn file)
   - 界面会切换到图像生成模式 / Interface switches to image generation mode

3. **输入提示词 / Input Prompt**
   - 在文本框中输入描述想要生成的图像的提示词 / Enter prompt describing the desired image in text box
   - **英文提示词**：Stable Diffusion 1.5 使用英文提示词效果更好 / English prompts work better with Stable Diffusion 1.5
   - **中文提示词**：太乙中文SD支持中文提示词 / Taiyi Chinese SD supports Chinese prompts
   - 提示词示例 / Prompt examples:
     - "a beautiful landscape with mountains and lake, sunset, high quality"
     - "一只可爱的猫咪，坐在窗台上，阳光明媚"
     - "cyberpunk city, neon lights, futuristic, 8k, detailed"

4. **调整生成参数 / Adjust Generation Parameters**
   - 在设置页面可配置生成参数（详见"Diffusion设置"章节）/ Configure generation parameters in settings page (see "Diffusion Settings" section)
   - **步数（Steps）**：控制生成质量和时间，推荐15-20步 / Controls generation quality and time, recommend 15-20 steps
   - **随机种子（Seed）**：固定种子可复现相同结果，-1表示随机 / Fixed seed reproduces same result, -1 means random
   - **内存模式（Memory Mode）**：根据设备内存选择合适模式 / Choose appropriate mode based on device memory

5. **生成图像 / Generate Image**
   - 点击发送按钮开始生成 / Click send button to start generation
   - 生成过程会显示详细进度信息（UNet去噪、VAE解码等）/ Generation process shows detailed progress (UNet denoising, VAE decoding, etc.)
   - 生成完成后，图像会自动保存到当前聊天文件夹 / After generation, image is automatically saved to current chat folder
   - 图像会在聊天界面中显示，可以点击查看大图 / Image displays in chat interface, click to view full size

**提示词技巧 / Prompt Tips**：

- **质量关键词 / Quality Keywords**：添加 "high quality", "detailed", "8k", "masterpiece" 等提升质量 / Add "high quality", "detailed", "8k", "masterpiece" to improve quality
- **风格关键词 / Style Keywords**：指定艺术风格，如 "oil painting", "watercolor", "photorealistic", "anime style" / Specify art style like "oil painting", "watercolor", "photorealistic", "anime style"
- **负面提示词 / Negative Prompts**：描述不想要的内容（当前版本暂不支持，未来会添加）/ Describe unwanted content (not supported in current version, will be added in future)
- **详细描述 / Detailed Description**：越详细的描述，生成的图像越符合预期 / More detailed descriptions produce images that better match expectations
- **避免模糊词汇 / Avoid Vague Terms**：使用具体的形容词和名词 / Use specific adjectives and nouns

**性能优化建议 / Performance Optimization Recommendations**：

- **设备选择 / Device Selection**
  - 推荐6GB+内存的设备 / Recommend devices with 6GB+ RAM
  - 4GB设备建议使用"省内存"模式 / 4GB devices should use "Memory Saving" mode
  - 8GB+设备可以使用"充足内存"或"平衡"模式 / 8GB+ devices can use "Enough Memory" or "Balance" mode

- **后端选择 / Backend Selection**
  - **GPU（OpenCL/Vulkan）**：速度最快，推荐优先使用 / Fastest speed, recommended first choice
  - **CPU**：速度较慢但稳定，兼容性最好 / Slower but stable, best compatibility
  - **NNAPI**：部分设备支持，速度介于CPU和GPU之间 / Some devices support, speed between CPU and GPU

- **步数调整 / Steps Adjustment**
  - **快速预览（10-15步）**：生成速度快，质量略低 / Fast preview (10-15 steps): Quick generation, slightly lower quality
  - **标准质量（15-20步）**：平衡速度和质量，推荐 / Standard quality (15-20 steps): Balanced speed and quality, recommended
  - **高质量（20-30步）**：质量更好但耗时更长 / High quality (20-30 steps): Better quality but longer time

**生成时间参考 / Generation Time Reference**：
- 骁龙8系列（OpenCL）：15步约30-60秒 / Snapdragon 8 series (OpenCL): ~30-60s for 15 steps
- 骁龙7系列（OpenCL）：15步约60-120秒 / Snapdragon 7 series (OpenCL): ~60-120s for 15 steps
- CPU模式：15步约3-5分钟 / CPU mode: ~3-5 minutes for 15 steps

**注意事项 / Notes**：
- ⚠️ **首次使用耗时较长**：首次加载Diffusion模型需要初始化，可能需要1-2分钟 / First-time loading takes longer, may need 1-2 minutes for initialization
- ⚠️ **图像尺寸固定**：当前版本生成512x512像素图像 / Current version generates 512x512 pixel images
- ⚠️ **显存/内存占用**：图像生成占用较多资源，建议关闭其他应用 / Image generation consumes significant resources, recommend closing other apps
- ⚠️ **生成质量**：受模型版本、提示词质量和生成参数影响 / Generation quality affected by model version, prompt quality and generation parameters
- ⚠️ **版权与伦理**：请遵守当地法律法规，不要生成违法或不当内容 / Please comply with local laws, do not generate illegal or inappropriate content

### 3.3 构建知识库页面 / Build Knowledge Base Page

#### 界面元素说明 / Interface Elements

- **知识库名称下拉框 / Knowledge Base Name Dropdown**：选择或创建知识库 / Select or create knowledge base
- **嵌入模型下拉框 / Embedding Model Dropdown**：选择用于生成文本向量的模型 / Select model for generating text vectors
- **重排模型下拉框 / Rerank Model Dropdown**：选择用于重新排序检索结果的模型（可选） / Select model for reordering retrieval results (optional)
  - 重排模型能够提高检索结果的相关性 / Rerank models can improve relevance of retrieval results
  - 如果不需要重排功能，可以选择"无" / If reranking is not needed, select "None"
  - 建议选择与嵌入模型匹配的重排模型 / Recommend choosing rerank model that matches embedding model
- **文件列表显示区域 / File List Display Area**：显示已选择的文件 / Display selected files
- **进度显示区域 / Progress Display Area**：显示处理进度 / Display processing progress
- **浏览文件按钮 / Browse Files Button**：选择要添加到知识库的文件 / Select files to add to knowledge base
- **创建知识库按钮 / Create Knowledge Base Button**：开始处理文件并构建知识库 / Start processing files and building knowledge base

#### JSON文件特殊处理
1. **自动识别格式**：
   - Alpaca格式（instruction/input/output）
   - DPO格式（prompt/chosen）
   - 对话格式（conversations/messages）

2. **处理规则**：
   - 最小文本块大小：使用设置中配置的值（默认50字符）
   - 大文件(>1MB)自动分片
   - 保留语义完整性

3. **示例**：
   ```json
   // Alpaca格式
   {"instruction":"解释AI","output":"人工智能是..."}
   
   // DPO格式
   {"prompt":"机器学习定义","chosen":"机器学习是..."}
   ```

#### 操作说明 / Operation Instructions

1. **选择知识库 / Select Knowledge Base**：
   - 在知识库下拉框中选择现有知识库或输入新名称创建 / Select existing knowledge base from dropdown or input new name to create
   - 新知识库会自动创建对应的存储目录 / New knowledge base will automatically create corresponding storage directory

2. **选择模型 / Select Models**：
   - 选择嵌入模型（必选） / Select embedding model (required)
   - 选择重排模型（可选，建议启用以提高检索质量） / Select rerank model (optional, recommended to enable for better retrieval quality)

3. **添加文档 / Add Documents**：
   - 点击"浏览文件"按钮选择文档 / Click "Browse Files" button to select documents
   - 支持多种格式：PDF、TXT、DOCX、MD等 / Supports multiple formats: PDF, TXT, DOCX, MD, etc.
   - 可以一次选择多个文件 / Can select multiple files at once

4. **开始构建 / Start Building**：
   - 点击"创建知识库"按钮开始处理 / Click "Create Knowledge Base" button to start processing
   - 系统会显示处理进度 / System will display processing progress
   - 处理完成后知识库即可用于问答 / After processing is complete, knowledge base is ready for Q&A
   - 建议把程序留在前台不要手动熄屏。程序在构建的时候会防止自动锁屏。 / Recommend keeping the app in foreground and not manually turning off screen. The app prevents auto-lock during building.

### 3.4 知识笔记页面 / Knowledge Notes Page

#### 界面元素说明 / Interface Elements

- **知识库下拉框 / Knowledge Base Dropdown**：选择要添加笔记的知识库 / Select knowledge base to add notes to
- **标题输入框 / Title Input**：输入笔记标题 / Enter note title
- **内容输入框 / Content Input**：输入笔记内容 / Enter note content
- **添加到知识库按钮 / Add to Knowledge Base Button**：将笔记添加到知识库 / Add note to knowledge base
- **进度显示区域 / Progress Display Area**：显示处理进度 / Display processing progress
  - 处理中：显示当前处理进度百分比 / Processing: Shows current progress percentage
  - 成功后会显示向量数据库项目数量变化（如"项目数：100 → 101"表示新增1条） / After success, shows vector database item count change (e.g., "Items: 100 → 101" indicates 1 new item added)
  - 失败时会显示错误信息 / Shows error message on failure

#### 操作说明 / Operation Instructions

1. 选择知识库 / Select knowledge base
2. 输入笔记标题和内容 / Enter note title and content
3. 点击"添加到知识库"按钮 / Click "Add to Knowledge Base" button
4. 处理完成后会显示成功信息 / Success message will be displayed after processing
5. 也可以从RAG问答页面通过长按回答文本，选择"转笔记"功能快速创建笔记 / Can also quickly create notes from RAG Q&A page by long-pressing answer text and selecting "Convert to Note" function

### 3.5 设置页面 / Settings Page

#### 界面元素说明 / Interface Elements

1. **目录设置 / Directory Settings**
   - 模型目录：本地LLM模型的存储目录路径 / Model Directory: Storage directory path for local LLM models
   - 嵌入模型目录：设置存储嵌入模型的目录路径 / Embedding Model Directory: Set storage directory path for embedding models
   - 重排模型目录：设置存储重排模型的目录路径 / Rerank Model Directory: Set storage directory path for rerank models
   - 知识库目录：设置存储知识库的目录路径 / Knowledge Base Directory: Set storage directory path for knowledge bases

2. **文本分块设置 / Text Chunking Settings**
   - 分块大小：设置文本分块的大小（默认1000字符） / Chunk Size: Set text chunk size (default 1000 characters)
   - 重叠大小：设置文本分块的重叠大小（默认200字符） / Overlap Size: Set text chunk overlap size (default 200 characters)
   - 最小分块限制：设置文本分块的最小长度（默认50字符） / Minimum Chunk Limit: Set minimum length for text chunks (default 50 characters)
   - JSON训练集分块优化：特殊处理JSON格式的训练数据集 / JSON Training Set Chunk Optimization: Special processing for JSON format training datasets

3. **LLM推理设置 / LLM Inference Settings**
   - 最大序列长度：设置模型处理的最大序列长度 / Max Sequence Length: Set maximum sequence length for model processing
   - 推理线程数：设置推理时使用的CPU线程数 / Inference Threads: Set number of CPU threads used during inference
   - 最大输出Token数：限制模型单次输出的最大Token数量 / Max Output Tokens: Limit maximum number of tokens in single model output
   - 手动温度：控制输出的随机性（0.0-2.0，值越高越随机） / Manual Temperature: Control output randomness (0.0-2.0, higher values more random)
   - 手动Top-P：核采样参数（0.0-1.0，控制候选词范围） / Manual Top-P: Nucleus sampling parameter (0.0-1.0, controls candidate word range)
   - 手动Top-K：限制每步采样的候选词数量 / Manual Top-K: Limit number of candidate words per sampling step
   - 手动重复惩罚：减少重复内容的生成（1.0-2.0） / Manual Repetition Penalty: Reduce repetitive content generation (1.0-2.0)
   - 优先手动参数：启用时使用手动设置的参数而非模型默认参数 / Prioritize Manual Parameters: Use manually set parameters instead of model defaults when enabled

4. **Diffusion生成设置 / Diffusion Generation Settings**
   - **去噪步数 / Denoising Steps**：控制图像生成的质量和时间（10-50步）/ Controls image generation quality and time (10-50 steps)
     - 10-15步：快速预览，质量较低 / Quick preview, lower quality
     - 15-20步：标准质量，推荐 / Standard quality, recommended
     - 20-30步：高质量，耗时更长 / High quality, longer time
     - 30-50步：最高质量，显著增加生成时间 / Highest quality, significantly increases generation time
   - **随机种子 / Random Seed**：控制图像生成的随机性 / Controls image generation randomness
     - 使用随机种子：每次生成不同的图像（默认）/ Use random seed: generates different images each time (default)
     - 固定种子值：输入特定数值可复现相同的图像 / Fixed seed value: input specific number to reproduce same image
   - **内存模式 / Memory Mode**：根据设备内存选择合适的生成模式 / Choose appropriate generation mode based on device memory
     - 省内存模式：适合4GB内存设备，速度较慢 / Memory Saving: suitable for 4GB RAM devices, slower speed
     - 平衡模式：适合6GB内存设备，平衡速度和质量（推荐）/ Balance: suitable for 6GB RAM devices, balanced speed and quality (recommended)
     - 充足内存模式：适合8GB+设备，速度最快 / Enough Memory: suitable for 8GB+ devices, fastest speed

5. **GPU后端设置 / GPU Backend Settings**
   - **后端选择 / Backend Selection**：选择推理使用的计算后端 / Select computational backend for inference
     - **CPU**：纯CPU推理，兼容性最好但速度较慢 / Pure CPU inference, best compatibility but slower speed
     - **OpenCL**：GPU加速，适合大多数安卓设备，速度快 / GPU acceleration, suitable for most Android devices, fast speed
     - **Vulkan**：高性能GPU加速，部分设备支持，速度最快 / High-performance GPU acceleration, some devices support, fastest speed
     - **NNAPI**：Android神经网络API，部分设备支持 / Android Neural Networks API, some devices support
   - **后端兼容性说明 / Backend Compatibility Notes**：
     - ⚠️ 不是所有设备都支持所有后端 / Not all devices support all backends
     - ⚠️ 如果选择的后端不支持，可能导致应用崩溃 / If selected backend is not supported, app may crash
     - ⚠️ 建议先使用CPU后端测试，确认模型正常工作后再尝试GPU / Recommend testing with CPU backend first, then try GPU after confirming model works
     - ⚠️ GPU后端失败时会在日志中显示错误信息 / GPU backend failures will show error messages in logs
   - **后端推荐 / Backend Recommendations**：
     - 骁龙8系列：推荐OpenCL或Vulkan / Snapdragon 8 series: recommend OpenCL or Vulkan
     - 骁龙7系列：推荐OpenCL / Snapdragon 7 series: recommend OpenCL
     - 联发科天玑：推荐OpenCL / MediaTek Dimensity: recommend OpenCL
     - 老旧设备：建议使用CPU / Older devices: recommend CPU

6. **全局设置 / Global Settings**
   - 调试模式：启用详细日志输出 / Debug Mode: Enable detailed log output
   - 字体大小：调整应用中文本的显示大小 / Font Size: Adjust text display size in the application

#### 操作说明 / Operation Instructions

1. 从主菜单点击"设置"进入设置页面 / Click "Settings" from main menu to enter settings page
2. 调整各项设置 / Adjust various settings
3. 设置会自动保存 / Settings are automatically saved
4. 返回主界面时，设置会立即生效 / Settings take effect immediately when returning to main interface

### 3.6 菜单功能 / Menu Functions

OfflineAI 应用在右上角提供了菜单选项：

The OfflineAI app provides menu options in the top-right corner:

- **设置 / Settings**：打开设置页面，配置应用参数 / Open settings page to configure application parameters
### 默认模型下载

提供常用模型的快速下载功能，简化模型获取和配置过程：

#### 功能特点
- **模型选择**：提供预设的推荐模型列表，包括嵌入模型、重排模型和LLM模型
- **断点续传**：支持下载中断后继续下载，避免重复下载
- **自动配置**：下载完成后自动配置模型路径到相应设置中
- **进度显示**：实时显示下载进度、速度和剩余时间
- **存储管理**：自动检查存储空间，避免因空间不足导致下载失败

#### 推荐模型列表 / Recommended Model List
**嵌入模型 / Embedding Models**
- **bge-small-zh-v1.5**：中文优化的小型嵌入模型，适合移动设备 / Chinese-optimized small embedding model, suitable for mobile devices
- **bge-base-zh-v1.5**：中文优化的基础嵌入模型，平衡性能和效果 / Chinese-optimized basic embedding model, balanced performance and effectiveness
- **text2vec-base-chinese**：通用中文嵌入模型 / General Chinese embedding model

**重排模型 / Rerank Models**
- **bge-reranker-base**：基础重排模型，适合大多数场景 / Basic rerank model, suitable for most scenarios
- **bge-reranker-large**：大型重排模型，效果更好但速度较慢 / Large rerank model, better results but slower speed

**LLM模型 / LLM Models**
- **Qwen2-0.5B-Instruct**：0.5B参数的轻量级模型，适合学习研究 / 0.5B parameter lightweight model, suitable for learning and research
- **Qwen2-1.5B-Instruct**：1.5B参数的小型模型，性能和速度平衡 / 1.5B parameter small model, balanced performance and speed
- **TinyLlama-1.1B**：1.1B参数的英文模型，运行速度快 / 1.1B parameter English model, fast running speed

**多模态模型 / Multimodal Models**
- **LLaVA（GGUF 小尺寸变体）**：图像-文本理解，建议选择小模型以适配移动设备 / Vision-language understanding; choose small variants for mobile
- **Qwen-VL（GGUF）**：中文多模态能力较强，注意内存占用 / Strong CN multimodal ability; watch memory usage
- **InternVL（GGUF）**：多场景图像理解，需根据设备性能选择 / Broad image understanding; choose based on device capability

**注意事项 / Notes**：
- 多模态模型需要MNN框架支持视觉特性编译 / Multimodal models require MNN framework compiled with vision features
- 模型文件通常较大（1-5GB），下载前请确保存储空间充足 / Model files are usually large (1-5GB), ensure sufficient storage before downloading
- 首次加载多模态模型可能需要较长时间 / First-time loading of multimodal models may take longer

#### 使用建议
- **首次使用**：建议先下载一个嵌入模型和一个小型LLM模型
- **存储考虑**：根据设备存储空间选择合适大小的模型
- **网络环境**：在WiFi环境下载，避免消耗移动数据
- **模型组合**：可以同时下载多个不同类型的模型进行对比测试

#### 自定义模型下载列表 / Customize Model Download List

应用支持用户自定义编辑模型下载列表，添加自己需要的模型：

The app supports users to customize the model download list and add their own models:

**编辑方法 / Editing Method**：
1. 找到应用内部存储目录下的 `ModelDownloadList.txt` 文件 / Locate `ModelDownloadList.txt` in app's internal storage
2. 使用文本编辑器打开文件 / Open file with text editor
3. 按照格式添加新的模型条目 / Add new model entries following the format
4. 保存文件并重启应用 / Save file and restart app

**文件格式说明 / File Format**：
```
[模型类型]
模型名称|模型URL|文件名1:下载URL1|文件名2:下载URL2|...
```

**示例 / Example**：
```
[LLM]
Qwen2-0.5B-Instruct|https://modelscope.cn/models/qwen/Qwen2-0.5B-Instruct|config.json:https://...url1|llm.mnn:https://...url2

[Embedding]
bge-small-zh-v1.5|https://modelscope.cn/models/AI-ModelScope/bge-small-zh-v1.5|config.json:https://...url1|embedding.mnn:https://...url2

[Reranker]
bge-reranker-base|https://modelscope.cn/models/AI-ModelScope/bge-reranker-base|config.json:https://...url1|llm.mnn:https://...url2
```

**字段说明 / Field Description**：
- **模型类型 / Model Type**：`[LLM]`、`[Embedding]`、`[Reranker]` 或 `[Multimodal]`
- **模型名称 / Model Name**：显示在下载列表中的名称 / Name displayed in download list
- **模型URL / Model URL**：模型主页链接（可选，用于参考）/ Model homepage link (optional, for reference)
- **文件映射 / File Mapping**：`本地文件名:下载URL` 格式，用 `|` 分隔多个文件 / Format: `local_filename:download_url`, separate multiple files with `|`

**注意事项 / Notes**：
- 确保下载URL可访问且文件格式正确 / Ensure download URLs are accessible and file formats are correct
- 模型文件必须是MNN格式（.mnn文件）/ Model files must be in MNN format (.mnn files)
- 配置文件（config.json）必须包含模型所需的所有参数 / Config file (config.json) must contain all required parameters
- 编辑文件时注意保持格式一致，避免解析错误 / Maintain consistent format when editing to avoid parsing errors
- 建议先在小模型上测试，确认格式正确后再添加大模型 / Recommend testing with small models first before adding large models
### Language/语言切换 / Language Switching

提供中英文界面切换功能，满足不同用户的语言需求：
Provides Chinese-English interface switching functionality to meet different users' language needs:

#### 功能特点 / Features
- **双语支持 / Bilingual Support**：完整支持中文和英文界面 / Complete support for Chinese and English interfaces
- **即时生效 / Immediate Effect**：选择语言后立即切换界面 / Interface switches immediately after language selection
- **自动保存 / Auto Save**：语言偏好设置自动保存，重启应用后保持 / Language preference settings are automatically saved and maintained after app restart
- **全面覆盖 / Comprehensive Coverage**：包括所有界面元素、菜单、对话框和提示信息 / Includes all interface elements, menus, dialogs and prompt messages

#### 使用方法 / Usage Method
1. 点击菜单中的"Language/语言"选项 / Click "Language/语言" option in the menu
2. 在弹出的语言选择对话框中选择目标语言 / Select target language in the popup language selection dialog
3. 界面立即切换到所选语言 / Interface immediately switches to selected language
4. 设置自动保存，无需手动确认 / Settings are automatically saved, no manual confirmation needed

#### 支持范围 / Support Scope
- **界面文本 / Interface Text**：所有按钮、标签、标题等界面元素 / All buttons, labels, titles and other interface elements
- **菜单项 / Menu Items**：主菜单和上下文菜单 / Main menu and context menus
- **对话框 / Dialog Boxes**：确认对话框、错误提示、信息提示 / Confirmation dialogs, error prompts, information prompts
- **设置页面 / Settings Page**：所有设置项的标题和说明 / All setting item titles and descriptions
- **帮助文档 / Help Documentation**：用户指南和帮助信息 / User guides and help information
### 4.4 其他菜单功能 / Other Menu Functions

- **关于 / About**：显示应用版本信息 / Display application version information
- **查看日志 / View Logs**：打开日志查看页面，查看应用运行日志 / Open log viewing page to view application runtime logs
  - 支持点击选择单条或多条日志 / Support clicking to select single or multiple logs
  - 长按可复制选中日志内容 / Long press to copy selected log content
  - 支持通过分享功能转发选中日志 / Support sharing selected logs through share function
- **帮助 / Help**：打开本使用说明 / Open this user guide
- **退出 / Exit**：关闭应用 / Close application

### 3.7 日志查看页面 / Log Viewing Page

#### 界面元素说明 / Interface Elements

- **日志显示区域 / Log Display Area**：显示应用运行日志 / Display application runtime logs
- **清空日志按钮 / Clear Log Button**：清除当前日志 / Clear current logs
- **刷新按钮 / Refresh Button**：刷新日志内容 / Refresh log content
- **返回按钮 / Back Button**：返回主界面 / Return to main interface

#### 日志菜单选项 / Log Menu Options

- **清空日志 / Clear Logs**：清除所有日志 / Clear all logs
- **日志分享功能 / Log Sharing Function**：
  - 支持导出完整日志文件(.log格式) / Support exporting complete log files (.log format)
  - 可通过系统分享菜单发送到微信等社交平台 / Can send to WeChat and other social platforms through system share menu
  - 可选择分享全部日志或仅选中日志 / Can choose to share all logs or only selected logs
  - 分享时会自动附加设备型号和系统版本信息，便于故障定位 / Automatically attach device model and system version information when sharing for troubleshooting

## 4. 高级功能 / Advanced Features

### 4.1 重排模型功能 / Rerank Model Function

重排模型（Reranker）是一种专门用于重新排序检索结果的模型，能够更准确地评估文本块与查询问题的相关性。

Rerank models are specialized models for reordering retrieval results, capable of more accurately evaluating the relevance between text chunks and query questions.

#### 工作原理 / Working Principle
1. **初步检索 / Initial Retrieval**：嵌入模型先进行向量相似度检索 / Embedding model first performs vector similarity retrieval
2. **重新排序 / Reordering**：重排模型对检索结果进行精确的相关性评分 / Rerank model performs precise relevance scoring on retrieval results
3. **结果优化 / Result Optimization**：选择评分最高的文本块作为最终结果 / Select text chunks with highest scores as final results

#### 使用建议 / Usage Recommendations
- **适用场景 / Applicable Scenarios**：复杂查询、多义词查询、需要高精度检索的场景 / Complex queries, ambiguous word queries, scenarios requiring high-precision retrieval
- **性能影响 / Performance Impact**：会增加处理时间，但显著提高检索质量 / Increases processing time but significantly improves retrieval quality
- **参数设置 / Parameter Settings**：重排数量建议设置为近似深度的2-3倍 / Rerank count recommended to be set to 2-3 times the approximate depth

### 4.2 模型使用跟踪 / Model Usage Tracking

OfflineAI 实现了模型使用跟踪机制，防止模型过早卸载，确保模型在需要时保持加载状态。当应用检测到模型不再使用时，会自动释放资源，优化内存使用。

OfflineAI implements a model usage tracking mechanism to prevent premature model unloading and ensure models remain loaded when needed. When the application detects that a model is no longer in use, it automatically releases resources to optimize memory usage.

### 4.3 文本转笔记 / Text to Notes

在RAG问答页面，可以通过长按选择AI回答文本，然后悬浮菜单选择"转笔记"，将有用的回答内容快速保存到知识库中。

On the RAG Q&A page, you can long-press to select AI response text, then choose "Convert to Notes" from the floating menu to quickly save useful response content to the knowledge base.

### 4.4 模型选择记忆 / Model Selection Memory

应用支持"记住选择"功能，当选择嵌入模型时，可以选择将此选择保存到知识库元数据中，避免每次都需要重新选择模型。

The application supports a "Remember Selection" feature. When selecting an embedding model, you can choose to save this selection to the knowledge base metadata, avoiding the need to reselect the model each time.

### 4.5 分块大小选择 / Chunk Size Selection

在设置中可以配置文本分块和重叠大小，提供多种预设选项：
- 4000/800（大块/大重叠，适合复杂文档）
- 2000/400（中大块/中重叠）
- 1000/200（默认，平衡选项）
- 500/100（小块/小重叠，适合简单文档）
- 100/20（微块/微重叠，适合精确检索）

Text chunking and overlap sizes can be configured in settings, with multiple preset options available:
- 4000/800 (Large chunks/Large overlap, suitable for complex documents)
- 2000/400 (Medium-large chunks/Medium overlap)
- 1000/200 (Default, balanced option)
- 500/100 (Small chunks/Small overlap, suitable for simple documents)
- 100/20 (Micro chunks/Micro overlap, suitable for precise retrieval)

## 5. 故障排除 / Troubleshooting

### 5.1 常见问题 / Common Issues

**问题：无法连接到API服务 / Issue: Cannot connect to API service**
- 检查网络连接是否正常 / Check if network connection is normal
- 确认API URL和密钥是否正确 / Confirm if API URL and key are correct
- 检查API服务是否可用 / Check if API service is available

**问题：知识库构建失败 / Issue: Knowledge base construction failed**
- 检查文档格式是否支持 / Check if document format is supported
- 确认存储空间是否充足 / Confirm if storage space is sufficient
- 检查嵌入模型是否正确加载 / Check if embedding model is loaded correctly

**问题：回答质量不佳 / Issue: Poor answer quality**
- 尝试调整近似深度参数 / Try adjusting approximate depth parameters
- 考虑启用重排模型 / Consider enabling rerank model
- 检查知识库内容是否相关 / Check if knowledge base content is relevant
- 优化系统提示词 / Optimize system prompts

**问题：应用运行缓慢 / Issue: Application running slowly**
- 检查设备内存使用情况 / Check device memory usage
- 调整LLM推理参数 / Adjust LLM inference parameters：
  - 降低"最大序列长度"（如从4096降至2048）/ Reduce "Max Sequence Length" (e.g., from 4096 to 2048)
  - 减少"推理线程数"（如从8降至4）/ Reduce "Inference Threads" (e.g., from 8 to 4)
  - 降低"最大输出Token数"（如从2048降至512）/ Reduce "Max Output Tokens" (e.g., from 2048 to 512)
- 考虑使用更小的模型 / Consider using smaller models
- 清理不必要的知识库 / Clean up unnecessary knowledge bases

**问题：应用崩溃或内存不足（OOM）/ Issue: App crashes or Out Of Memory (OOM)**
- **立即操作 / Immediate Actions**：
  - 降低"最大序列长度"至1024或2048 / Reduce "Max Sequence Length" to 1024 or 2048
  - 关闭其他后台应用释放内存 / Close other background apps to free memory
  - 重启应用清理缓存 / Restart app to clear cache
- **长期优化 / Long-term Optimization**：
  - 使用更小的模型（如1B参数而非3B）/ Use smaller models (e.g., 1B parameters instead of 3B)
  - 避免同时加载多个大模型 / Avoid loading multiple large models simultaneously
  - 定期清理应用缓存和临时文件 / Regularly clear app cache and temporary files

**问题：多轮对话后AI"遗忘"前面的内容 / Issue: AI "forgets" previous content after multiple rounds**
- **原因 / Cause**：最大序列长度设置过小，超出限制后自动截断历史 / Max sequence length set too small, automatically truncates history when exceeded
- **解决方案 / Solution**：
  - 增加"最大序列长度"（如从1024增至2048或4096）/ Increase "Max Sequence Length" (e.g., from 1024 to 2048 or 4096)
  - 注意：需要确保设备有足够内存 / Note: Ensure device has sufficient memory
  - 参考上文"LLM推理设置详解"选择合适的值 / Refer to "LLM Inference Settings Explained" above to choose appropriate value

**问题：使用GPU后端时应用崩溃 / Issue: App crashes when using GPU backend**
- **原因 / Cause**：
  - 设备不支持所选的GPU后端（OpenCL/Vulkan/NNAPI）/ Device does not support selected GPU backend
  - 驱动程序过时或不兼容 / Outdated or incompatible drivers
  - GPU内存不足 / Insufficient GPU memory
  - 特定模型与GPU后端不兼容 / Specific model incompatible with GPU backend
- **诊断步骤 / Diagnostic Steps**：
  1. 查看应用日志（菜单 → 查看日志）/ Check app logs (Menu → View Logs)
  2. 搜索关键词："Backend", "OpenCL", "Vulkan", "NNAPI", "crash" / Search keywords: "Backend", "OpenCL", "Vulkan", "NNAPI", "crash"
  3. 确认错误信息类型 / Identify error message type
- **解决方案 / Solution**：
  - **立即操作 / Immediate Action**：切换到CPU后端（设置 → GPU后端设置 → 选择CPU）/ Switch to CPU backend (Settings → GPU Backend Settings → Select CPU)
  - **测试其他后端 / Test Other Backends**：按顺序尝试：CPU → OpenCL → Vulkan → NNAPI / Try in order: CPU → OpenCL → Vulkan → NNAPI
  - **更新系统 / Update System**：确保Android系统和GPU驱动为最新版本 / Ensure Android system and GPU drivers are up-to-date
  - **降低负载 / Reduce Load**：
    - 使用更小的模型（如1B而非3B）/ Use smaller models (e.g., 1B instead of 3B)
    - 降低最大序列长度 / Reduce max sequence length
    - Diffusion使用"省内存"模式 / Use "Memory Saving" mode for Diffusion
- **后端兼容性测试 / Backend Compatibility Testing**：
  - 在设置中选择后端后，先用简单任务测试（如短文本生成）/ After selecting backend in settings, test with simple tasks first (e.g., short text generation)
  - 成功后再进行复杂任务（如长对话、图像生成）/ After success, proceed with complex tasks (e.g., long conversations, image generation)
  - 记录哪些后端可用，避免使用不兼容的后端 / Record which backends work, avoid using incompatible backends
- **已知设备问题 / Known Device Issues**：
  - 某些设备的Vulkan实现不完整，建议使用OpenCL / Some devices have incomplete Vulkan implementation, recommend OpenCL
  - 部分低端设备NNAPI支持有限，可能崩溃 / Some low-end devices have limited NNAPI support, may crash
  - x86_64模拟器可能不支持GPU后端 / x86_64 emulators may not support GPU backends

**问题：Diffusion图像生成失败或质量差 / Issue: Diffusion image generation fails or poor quality**
- **原因 / Cause**：
  - 提示词不够具体或包含错误 / Prompt not specific enough or contains errors
  - 去噪步数设置过低 / Denoising steps set too low
  - 内存模式选择不当 / Inappropriate memory mode selection
  - GPU后端不支持或不稳定 / GPU backend not supported or unstable
- **解决方案 / Solution**：
  - **提示词优化 / Prompt Optimization**：
    - 使用英文提示词（Stable Diffusion 1.5）/ Use English prompts (Stable Diffusion 1.5)
    - 添加质量关键词："high quality", "detailed", "8k" / Add quality keywords: "high quality", "detailed", "8k"
    - 使用具体描述词，避免抽象概念 / Use specific descriptions, avoid abstract concepts
  - **参数调整 / Parameter Adjustment**：
    - 增加去噪步数至20-30步 / Increase denoising steps to 20-30
    - 尝试不同的随机种子 / Try different random seeds
    - 根据设备内存调整内存模式 / Adjust memory mode based on device RAM
  - **后端切换 / Backend Switching**：
    - 如果GPU崩溃，切换到CPU后端测试 / If GPU crashes, switch to CPU backend for testing
    - CPU模式虽慢但更稳定 / CPU mode is slower but more stable
  - **模型检查 / Model Check**：
    - 确认模型文件完整（text_encoder.mnn、unet.mnn、vae_decoder.mnn）/ Confirm model files are complete
    - 重新下载损坏的模型文件 / Re-download corrupted model files

### 5.2 日志分析 / Log Analysis

当遇到问题时，可以通过查看日志来诊断：

When encountering problems, you can diagnose by viewing logs:

1. **访问日志 / Access Logs**：通过菜单 → 查看日志 / Through Menu → View Logs
2. **筛选日志 / Filter Logs**：按时间或级别筛选相关日志 / Filter relevant logs by time or level
3. **搜索关键词 / Search Keywords**：搜索错误信息或特定操作 / Search for error messages or specific operations
4. **导出日志 / Export Logs**：将日志导出用于进一步分析或技术支持 / Export logs for further analysis or technical support

应用提供详细的日志记录，可以通过"查看日志"菜单查看。日志包含四个级别：

The application provides detailed logging, which can be viewed through the "View Logs" menu. Logs contain four levels:

- **DEBUG**：详细调试信息 / Detailed debugging information
- **INFO**：一般信息 / General information
- **WARNING**：警告信息 / Warning information
- **ERROR**：错误信息 / Error information

查看日志可以帮助定位问题原因，特别是在处理大文件或复杂文档时。

Viewing logs can help locate the cause of problems, especially when processing large files or complex documents.

## 8. 最佳实践 / Best Practices

### 8.1 文档处理最佳实践 / Document Processing Best Practices

**文档准备 / Document Preparation**：
- 确保文档内容清晰、结构化 / Ensure document content is clear and structured
- 移除不必要的格式和图片 / Remove unnecessary formatting and images
- 对于PDF文档，确保文本可以正常提取 / For PDF documents, ensure text can be extracted normally

**分块策略 / Chunking Strategy**：
- 对于技术文档，建议使用较小的分块（500-1000字符） / For technical documents, recommend using smaller chunks (500-1000 characters)
- 对于叙述性文档，可以使用较大的分块（1000-2000字符） / For narrative documents, can use larger chunks (1000-2000 characters)
- 设置适当的重叠以保持上下文连续性 / Set appropriate overlap to maintain context continuity

**知识库组织 / Knowledge Base Organization**：
- 按主题或领域创建不同的知识库 / Create different knowledge bases by topic or domain
- 定期更新和维护知识库内容 / Regularly update and maintain knowledge base content
- 删除过时或不相关的文档 / Remove outdated or irrelevant documents

### 8.2 本地LLM使用建议 / Local LLM Usage Recommendations

#### 模型选择原则 / Model Selection Principles
- **手机设备限制 / Mobile Device Limitations**：由于手机内存和计算能力限制，建议使用2B参数以下的小模型 / Due to mobile memory and computational limitations, recommend using small models under 2B parameters
- **推荐模型规格 / Recommended Model Specifications**：
  - 1B-2B参数：适合日常问答和简单推理 / 1B-2B parameters: suitable for daily Q&A and simple reasoning
  - 500M-1B参数：适合基础文本理解 / 500M-1B parameters: suitable for basic text understanding
  - 小于500M参数：仅适合简单的文本分类任务 / Less than 500M parameters: only suitable for simple text classification tasks

#### LLM推理设置详解 / LLM Inference Settings Explained

**基础参数设置 / Basic Parameter Settings**

##### 1. 最大序列长度（KV Cache大小）/ Max Sequence Length (KV Cache Size)

**作用 / Purpose**：
- 控制模型的**上下文窗口大小**，即能记住多少历史对话 / Controls **context window size**, i.e., how much conversation history can be remembered
- 决定**KV Cache内存占用**，影响多轮对话和长文本理解能力 / Determines **KV Cache memory usage**, affects multi-turn dialogue and long text comprehension

**配置范围 / Configuration Range**：
- 当前范围：1024-6144 tokens / Current range: 1024-6144 tokens
- 默认值：2048 tokens / Default: 2048 tokens
- 步进：512 tokens / Step: 512 tokens

**内存消耗参考（以Qwen2.5-3B为例）/ Memory Consumption Reference (Qwen2.5-3B example)**：

| 序列长度 / Sequence Length | KV Cache内存 / KV Cache Memory | 适用场景 / Use Case |
|---------------------------|-------------------------------|-------------------|
| **1024** | ~294 MB | 短对话、内存受限设备 / Short conversations, memory-limited devices |
| **2048** | ~589 MB | 普通对话（推荐）/ Normal conversations (Recommended) ✅ |
| **4096** | ~1.15 GB | 长对话、RAG检索 / Long conversations, RAG retrieval |
| **6144** | ~1.73 GB | 超长文本、深度对话 / Very long texts, deep conversations |

**配置建议 / Configuration Recommendations**：

根据设备内存选择 / Choose based on device memory：
- **4GB RAM设备**：建议1024-2048，避免OOM / Recommend 1024-2048, avoid OOM
- **6GB RAM设备**：建议2048-4096，平衡性能 / Recommend 2048-4096, balanced performance
- **8GB+ RAM设备**：可选4096-6144，充分利用 / Can choose 4096-6144, full utilization

根据使用场景选择 / Choose based on use case：
- **普通聊天（2-3轮）**：1024-2048足够 / 1024-2048 sufficient
- **RAG问答（5个文档）**：建议2048-4096 / Recommend 2048-4096
- **长文本总结（2000字+）**：建议4096+ / Recommend 4096+
- **多轮深度对话（10轮+）**：建议4096-6144 / Recommend 4096-6144

**影响说明 / Impact Description**：
- ✅ **太小（1024）**：内存占用低，但容易"遗忘"前面的对话，RAG效果受限 / Low memory usage, but easily "forgets" previous conversations, limited RAG effectiveness
- ✅ **适中（2048-4096）**：平衡内存和性能，适合大多数场景 / Balanced memory and performance, suitable for most scenarios
- ⚠️ **太大（6144+）**：内存压力大，生成速度变慢，低端设备可能OOM / High memory pressure, slower generation, low-end devices may OOM

**技术细节 / Technical Details**：
- MNN使用固定chunk=256进行Prefill分块，控制峰值内存 / MNN uses fixed chunk=256 for Prefill chunking, controlling peak memory
- 序列长度通过`kvcache_limit`参数传递给MNN引擎 / Sequence length passed to MNN engine via `kvcache_limit` parameter
- 超过限制时，MNN会自动截断最早的token / When limit exceeded, MNN automatically truncates earliest tokens

##### 2. 推理线程数 / Inference Threads

**作用 / Purpose**：
- 控制CPU推理时使用的**并行线程数** / Controls **number of parallel threads** during CPU inference
- 影响推理速度和CPU占用率 / Affects inference speed and CPU utilization

**配置范围 / Configuration Range**：
- 范围：1-16线程 / Range: 1-16 threads
- 默认值：4线程 / Default: 4 threads

**配置建议 / Configuration Recommendations**：
- **4核CPU**：建议2-4线程 / Recommend 2-4 threads
- **6核CPU**：建议4-6线程 / Recommend 4-6 threads
- **8核+CPU**：建议6-8线程 / Recommend 6-8 threads
- ⚠️ 不建议超过物理核心数，会导致性能下降 / Not recommended to exceed physical core count, causes performance degradation

**注意事项 / Notes**：
- GPU后端时，此参数代表GPU MODE，不是线程数 / For GPU backend, this parameter represents GPU MODE, not thread count
- 线程数越多，CPU发热越明显 / More threads, more CPU heat
- 同时运行其他应用时，建议降低线程数 / When running other apps simultaneously, recommend reducing thread count

##### 3. 最大输出Token数 / Max Output Tokens

**作用 / Purpose**：
- 限制模型**单次生成的最大token数量** / Limits **maximum number of tokens** generated in single response
- 防止模型无限生成，避免资源浪费 / Prevents infinite generation, avoids resource waste

**配置范围 / Configuration Range**：
- 范围：512-4096 tokens / Range: 512-4096 tokens
- 默认值：512 tokens / Default: 512 tokens

**配置建议 / Configuration Recommendations**：
- **简短回答**：512-1024 tokens / 512-1024 tokens
- **详细解释**：1024-2048 tokens / 1024-2048 tokens
- **长文本生成**：2048-4096 tokens / 2048-4096 tokens

**影响说明 / Impact Description**：
- 此参数**不影响内存占用**，只控制生成长度 / Does **not affect memory usage**, only controls generation length
- 超过限制时，模型会自动停止生成 / When limit exceeded, model automatically stops generation

**高级参数调优 / Advanced Parameter Tuning**
- **温度（Temperature）/ Temperature**：控制输出的随机性 / Controls output randomness
  - 范围：0.1-1.0 / Range: 0.1-1.0
  - 较低值产生更确定的回答，较高值增加创造性 / Lower values produce more deterministic answers, higher values increase creativity
- **Top-P**：核采样参数，控制候选词汇范围 / Nucleus sampling parameter, controls candidate word range
  - 范围：0.1-1.0 / Range: 0.1-1.0
  - 通常设置为0.9-0.95 / Usually set to 0.9-0.95
- **Top-K**：限制每步选择的候选词数量 / Limits number of candidate words selected per step
  - 范围：1-100 / Range: 1-100
  - 通常设置为40-50 / Usually set to 40-50
- **重复惩罚 / Repetition Penalty**：避免模型产生重复内容 / Prevents model from generating repetitive content
  - 范围：1.0-1.2 / Range: 1.0-1.2
  - 1.0表示无惩罚，1.1-1.15为常用值 / 1.0 means no penalty, 1.1-1.15 are common values

**重要提醒 / Important Reminder**
⚠️ **本地小模型能力限制 / Local Small Model Limitations**：
- 推理能力相对较弱，可能出现逻辑错误 / Relatively weak reasoning ability, may have logical errors
- 知识更新不及时，可能包含过时信息 / Knowledge updates not timely, may contain outdated information
- 复杂问题的回答质量有限 / Limited answer quality for complex questions
- 主要用途是学习、研究和隐私保护场景 / Main uses are learning, research and privacy protection scenarios
- 对于重要决策或专业问题，建议使用在线大模型验证结果 / For important decisions or professional questions, recommend using online large models to verify results

#### 图像编码设置详解 / Image Encoding Settings Explained

**图片预处理尺寸（像素）/ Image Preprocessing Size (Pixels)**

**预设选项 / Preset Options**：
- `112, 280, 392, 504(默认), 672, 896, 1008, MAX(原图)` / `112, 280, 392, 504(default), 672, 896, 1008, MAX(original)`

**含义 / Meaning**：
- 按预设尺寸对输入图片进行等比例缩放后编码 / Scale input images proportionally to preset size before encoding
- `MAX`表示不缩放，按原始分辨率编码 / `MAX` means no scaling, encode at original resolution
- 这些预设均为28的倍数（适配常见VL模型）/ These presets are multiples of 28 (adapted for common VL models)

**配置建议 / Configuration Recommendations**：
- **中低端设备（4-6GB RAM）**：优先选择`392-504` / Prioritize `392-504`
- **高性能设备（8GB+ RAM）**：可选`672-1008`或`MAX` / Can choose `672-1008` or `MAX`
- ⚠️ 更大的尺寸会增加耗时与内存占用 / Larger sizes increase processing time and memory usage

**图像编码线程数说明 / Image Encoding Threads Note**：
- ⚠️ **已移除此配置项** / **This configuration has been removed**
- MNN引擎使用统一的"推理线程数"控制所有推理（包括文本LLM和视觉编码器）/ MNN engine uses unified "Inference Threads" to control all inference (including text LLM and vision encoder)
- 无法像llamacpp那样独立配置图像编码线程数 / Cannot independently configure image encoding threads like llamacpp
- 如需调整图像编码性能，请调整"推理线程数"参数 / To adjust image encoding performance, please adjust "Inference Threads" parameter

**使用提示 / Usage Tips**：
- 图片越清晰、提示词越具体，越能提升答案质量 / Clearer images and more specific prompts improve answer quality
- 首次加载多模态模型可能耗时较长，属于正常现象 / First-time loading of multimodal models may take longer, this is normal

### 8.3 聊天历史管理最佳实践 / Chat History Management Best Practices

OfflineAI 支持多会话管理，合理使用可以提高工作效率：

OfflineAI supports multi-session management, proper use can improve work efficiency:

**会话组织策略 / Session Organization Strategy**：
- **按主题分类 / Categorize by Topic**：为不同主题创建独立会话（如工作、学习、创作等）/ Create independent sessions for different topics (e.g., work, study, creation)
- **长期项目 / Long-term Projects**：重要项目保持独立会话，方便追溯和继续 / Keep independent sessions for important projects for easy tracking and continuation
- **临时咨询 / Temporary Consultation**：短期问题可以使用临时会话，完成后删除 / Use temporary sessions for short-term issues, delete after completion

**会话切换技巧 / Session Switching Tips**：
- **快速识别 / Quick Identification**：会话自动以时间戳命名，建议记住关键时间 / Sessions automatically named with timestamps, recommend remembering key times
- **定期清理 / Regular Cleanup**：删除不再需要的会话，保持列表整洁 / Delete unnecessary sessions to keep list tidy
- **重要会话 / Important Sessions**：建议截图或导出重要对话内容 / Recommend taking screenshots or exporting important conversation content

**使用建议 / Usage Recommendations**：
- 每个会话独立维护对话历史，不会相互影响 / Each session maintains independent conversation history without affecting others
- 切换会话时会自动保存当前对话 / Automatically saves current conversation when switching sessions
- 长时间对话建议适当分割为多个会话，避免上下文过长 / Recommend splitting long conversations into multiple sessions to avoid excessive context

### 8.4 Diffusion图像生成最佳实践 / Diffusion Image Generation Best Practices

AI绘图需要技巧和经验积累，以下是一些实用建议：

AI art requires skills and experience, here are some practical recommendations:

**提示词编写技巧 / Prompt Writing Tips**：

1. **结构化提示词 / Structured Prompts**：
   ```
   [主体] + [动作/姿态] + [环境] + [风格] + [质量词]
   [Subject] + [Action/Pose] + [Environment] + [Style] + [Quality]
   
   示例 / Example:
   "A cute cat, sitting on windowsill, sunny room with plants, watercolor style, high quality, detailed"
   ```

2. **权重使用（未来版本）/ Weight Usage (Future Version)**：
   - 某些提示词可以加权重，如 `(beautiful:1.5)` 表示强调"美丽" / Some prompts can be weighted, e.g., `(beautiful:1.5)` emphasizes "beautiful"
   - 当前版本暂不支持，建议多次重复重要词汇 / Not supported in current version, recommend repeating important words

3. **避免常见错误 / Avoid Common Mistakes**：
   - ❌ 提示词过于简单："a cat" / Too simple: "a cat"
   - ✅ 提示词详细："a fluffy orange cat sitting on a red cushion, natural lighting, photorealistic" / Detailed: "a fluffy orange cat sitting on a red cushion, natural lighting, photorealistic"
   - ❌ 中英混合（SD 1.5）："beautiful 风景" / Mixed Chinese-English (SD 1.5): "beautiful 风景"
   - ✅ 统一语言："beautiful landscape" 或 "美丽的风景"（使用太乙中文SD）/ Unified language: "beautiful landscape" or "美丽的风景" (with Taiyi Chinese SD)

**参数调优策略 / Parameter Tuning Strategy**：

1. **快速迭代 / Quick Iteration**：
   - 第一次生成使用10-15步，快速查看效果 / First generation use 10-15 steps to quickly check result
   - 满意后增加到20-30步获得高质量版本 / If satisfied, increase to 20-30 steps for high-quality version

2. **种子复用 / Seed Reuse**：
   - 生成满意图像后，记录种子值 / After generating satisfactory image, record seed value
   - 使用相同种子和微调提示词可以生成相似但不同的图像 / Using same seed with adjusted prompts generates similar but different images

3. **内存模式选择 / Memory Mode Selection**：
   - 测试阶段使用"省内存"模式，确保稳定性 / Use "Memory Saving" mode during testing to ensure stability
   - 正式生成使用"平衡"或"充足内存"模式提升速度 / Use "Balance" or "Enough Memory" mode for formal generation to improve speed

**后端性能对比 / Backend Performance Comparison**：

| 后端 Backend | 速度 Speed | 兼容性 Compatibility | 推荐场景 Recommended Scenario |
|-------------|-----------|---------------------|----------------------------|
| CPU | ★☆☆☆☆ | ★★★★★ | 测试、老旧设备 / Testing, old devices |
| OpenCL | ★★★★☆ | ★★★★☆ | 大多数安卓设备（推荐）/ Most Android devices (Recommended) |
| Vulkan | ★★★★★ | ★★★☆☆ | 高端设备、追求极致速度 / High-end devices, maximum speed |
| NNAPI | ★★★☆☆ | ★★☆☆☆ | 特定设备、实验性 / Specific devices, experimental |

**创作流程建议 / Creative Workflow Recommendations**：

1. **概念阶段 / Concept Phase**：
   - 使用简单提示词和低步数（10步）快速探索风格 / Use simple prompts and low steps (10) to quickly explore styles
   - 尝试不同的风格关键词找到合适方向 / Try different style keywords to find suitable direction

2. **细化阶段 / Refinement Phase**：
   - 增加提示词细节，提升到15-20步 / Add prompt details, increase to 15-20 steps
   - 使用固定种子进行微调 / Use fixed seed for fine-tuning

3. **最终输出 / Final Output**：
   - 使用完整详细的提示词，25-30步生成高质量图像 / Use complete detailed prompts, 25-30 steps for high-quality image
   - 在"充足内存"模式下加速生成 / Accelerate generation in "Enough Memory" mode

**常见主题提示词参考 / Common Theme Prompt References**：

- **风景画 / Landscape**：
  "beautiful mountain landscape, sunset colors, lake reflection, dramatic clouds, high detail, 8k, masterpiece"

- **人物肖像 / Portrait**：
  "portrait of a young woman, natural lighting, soft focus, detailed face, photorealistic, professional photography"

- **科幻场景 / Sci-Fi Scene**：
  "futuristic cyberpunk city, neon lights, flying cars, rainy night, highly detailed, 8k, cinematic lighting"

- **动物 / Animals**：
  "cute fluffy kitten, big eyes, soft fur, sitting pose, natural lighting, high quality, detailed"

- **建筑 / Architecture**：
  "modern architecture, glass building, minimalist design, blue sky, geometric, professional photography, high detail"

### 8.5 GPU后端选择最佳实践 / GPU Backend Selection Best Practices

合理选择计算后端可以显著提升性能：

Proper backend selection can significantly improve performance:

**首次使用建议 / First-Time Use Recommendations**：

1. **从CPU开始 / Start with CPU**：
   - 先使用CPU后端确认模型可以正常加载和运行 / First use CPU backend to confirm model loads and runs normally
   - CPU模式虽慢但最稳定，适合验证 / CPU mode is slow but most stable, suitable for verification

2. **逐步尝试GPU / Gradually Try GPU**：
   - 按顺序测试：CPU → OpenCL → Vulkan → NNAPI / Test in order: CPU → OpenCL → Vulkan → NNAPI
   - 每次切换后用简单任务测试，避免直接进行复杂任务 / Test with simple tasks after each switch, avoid complex tasks immediately

3. **记录兼容性 / Record Compatibility**：
   - 记录哪些后端可用，哪些会崩溃 / Record which backends work and which crash
   - 不同模型可能对后端兼容性不同 / Different models may have different backend compatibility

**性能优化策略 / Performance Optimization Strategy**：

1. **LLM文本生成 / LLM Text Generation**：
   - OpenCL通常是最佳选择，兼容性和性能平衡 / OpenCL usually best choice, balanced compatibility and performance
   - Vulkan在支持的设备上可能更快 / Vulkan may be faster on supported devices
   - CPU适合短文本生成和测试 / CPU suitable for short text generation and testing

2. **Diffusion图像生成 / Diffusion Image Generation**：
   - **强烈推荐GPU加速**：CPU生成速度极慢（3-5分钟）/ **Strongly recommend GPU acceleration**: CPU generation very slow (3-5 minutes)
   - OpenCL或Vulkan可将生成时间降至30-120秒 / OpenCL or Vulkan can reduce generation time to 30-120 seconds
   - 如果GPU崩溃，检查内存模式是否设置正确 / If GPU crashes, check if memory mode is set correctly

3. **多模态推理 / Multimodal Inference**：
   - 视觉编码器通常对GPU要求较高 / Vision encoder usually has high GPU requirements
   - 建议先用CPU测试，确认稳定后再使用GPU / Recommend testing with CPU first, use GPU after confirming stability

**故障恢复流程 / Fault Recovery Procedure**：

如果GPU后端崩溃，按以下步骤恢复：
If GPU backend crashes, follow these steps to recover:

1. **立即切换 / Immediate Switch**：
   - 设置 → GPU后端设置 → 选择CPU / Settings → GPU Backend Settings → Select CPU
   - 重启应用确保设置生效 / Restart app to ensure settings take effect

2. **日志分析 / Log Analysis**：
   - 查看崩溃前的日志信息 / Check log information before crash
   - 搜索"Backend", "OpenCL", "Vulkan"等关键词 / Search keywords "Backend", "OpenCL", "Vulkan"

3. **逐步测试 / Gradual Testing**：
   - 在CPU模式下确认模型正常工作 / Confirm model works normally in CPU mode
   - 尝试其他GPU后端（如从Vulkan换到OpenCL）/ Try other GPU backends (e.g., switch from Vulkan to OpenCL)
   - 降低负载（使用更小模型、降低序列长度）/ Reduce load (use smaller model, reduce sequence length)

**设备特定建议 / Device-Specific Recommendations**：

- **旗舰设备（骁龙8系列、天玑9000+）/ Flagship Devices (Snapdragon 8, Dimensity 9000+)**：
  - 优先使用Vulkan或OpenCL / Prioritize Vulkan or OpenCL
  - 可以使用"充足内存"模式进行Diffusion生成 / Can use "Enough Memory" mode for Diffusion generation
  - 支持较大的最大序列长度（4096-6144）/ Support larger max sequence length (4096-6144)

- **中端设备（骁龙7系列、天玑8000系列）/ Mid-range Devices (Snapdragon 7, Dimensity 8000)**：
  - 推荐OpenCL / Recommend OpenCL
  - Diffusion使用"平衡"模式 / Use "Balance" mode for Diffusion
  - 最大序列长度建议2048-4096 / Max sequence length recommend 2048-4096

- **入门设备（骁龙6系列及以下）/ Entry Devices (Snapdragon 6 and below)**：
  - 可以尝试OpenCL，但CPU可能更稳定 / Can try OpenCL, but CPU may be more stable
  - 使用"省内存"模式 / Use "Memory Saving" mode
  - 最大序列长度建议1024-2048 / Max sequence length recommend 1024-2048
  - 避免同时运行多个应用 / Avoid running multiple apps simultaneously

### 8.6 重排模型使用建议 / Rerank Model Usage Recommendations

重排模型是提升检索质量的重要工具，但需要合理配置和使用：

Rerank models are important tools for improving retrieval quality, but require proper configuration and usage:

#### 适用场景 / Applicable Scenarios

**复杂查询处理 / Complex Query Processing**：
- 多概念查询：当用户问题包含多个相关概念时 / Multi-concept queries: when user questions contain multiple related concepts
- 长查询语句：超过10个词的复杂问题 / Long query statements: complex questions with more than 10 words
- 专业术语查询：包含领域特定术语的问题 / Professional terminology queries: questions containing domain-specific terms
- 上下文相关查询：需要理解上下文关系的问题 / Context-related queries: questions requiring understanding of contextual relationships

**多义词处理 / Polysemy Processing**：
- 歧义消解：当查询词有多种含义时 / Ambiguity resolution: when query words have multiple meanings
- 同义词匹配：识别不同表达方式的相同概念 / Synonym matching: identifying same concepts expressed differently
- 语义相似性：理解语义相近但表达不同的内容 / Semantic similarity: understanding semantically similar but differently expressed content

**高精度要求场景 / High Precision Requirement Scenarios**：
- 专业咨询：医疗、法律、技术等专业领域 / Professional consulting: medical, legal, technical and other professional fields
- 学术研究：需要精确引用和参考的场景 / Academic research: scenarios requiring precise citations and references
- 决策支持：基于准确信息进行决策的情况 / Decision support: situations requiring decisions based on accurate information

#### 模型选择建议 / Model Selection Recommendations

**bge-reranker-base**：
- 适用场景：一般用途，平衡性能和效果 / Applicable scenarios: general purpose, balanced performance and effectiveness
- 性能特点：处理速度较快，资源消耗适中 / Performance characteristics: faster processing speed, moderate resource consumption
- 推荐用户：大多数用户的首选 / Recommended users: first choice for most users
- 设备要求：中等配置设备即可运行 / Device requirements: can run on medium configuration devices

**bge-reranker-large**：
- 适用场景：高精度要求，复杂查询处理 / Applicable scenarios: high precision requirements, complex query processing
- 性能特点：效果更好但处理速度较慢 / Performance characteristics: better results but slower processing speed
- 推荐用户：对准确性要求极高的专业用户 / Recommended users: professional users with extremely high accuracy requirements
- 设备要求：需要较高配置设备 / Device requirements: requires high configuration devices

#### 参数配置策略 / Parameter Configuration Strategy

**重排数量设置 / Rerank Count Setting**：
- 基础配置：设置为近似深度的2倍 / Basic configuration: set to 2 times the approximate depth
- 平衡配置：设置为近似深度的2.5倍 / Balanced configuration: set to 2.5 times the approximate depth
- 高精度配置：设置为近似深度的3倍 / High precision configuration: set to 3 times the approximate depth
- 示例：近似深度5时，重排数量可设置为10-15 / Example: when approximate depth is 5, rerank count can be set to 10-15

**与嵌入模型配合 / Coordination with Embedding Models**：
- 模型兼容性：确保重排模型与嵌入模型语言一致 / Model compatibility: ensure rerank model and embedding model language consistency
- 参数协调：重排数量应大于近似深度 / Parameter coordination: rerank count should be greater than approximate depth
- 性能平衡：根据设备性能调整两者参数 / Performance balance: adjust both parameters based on device performance

#### 使用建议 / Usage Recommendations

**渐进式使用 / Progressive Usage**：
- 初期：先使用基础嵌入模型熟悉系统 / Initial stage: first use basic embedding model to familiarize with system
- 进阶：在需要时启用重排模型 / Advanced stage: enable rerank model when needed
- 优化：根据使用体验调整参数 / Optimization: adjust parameters based on usage experience

**场景测试 / Scenario Testing**：
- A/B测试：对比启用和关闭重排的效果 / A/B testing: compare effects of enabling and disabling reranking
- 场景分析：分析不同类型查询的重排效果 / Scenario analysis: analyze rerank effects for different types of queries
- 用户反馈：收集用户对检索质量的反馈 / User feedback: collect user feedback on retrieval quality

**性能监控 / Performance Monitoring**：
- 响应时间：监控重排对响应时间的影响 / Response time: monitor impact of reranking on response time
- 准确性评估：评估重排对结果准确性的提升 / Accuracy assessment: evaluate improvement in result accuracy from reranking
- 资源使用：监控CPU、内存等资源使用情况 / Resource usage: monitor CPU, memory and other resource usage
