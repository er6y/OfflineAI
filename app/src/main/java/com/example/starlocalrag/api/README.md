# 大模型API适配层设计文档

## 概述

本适配层设计旨在统一不同大模型API的调用方式，将DeepSeek、千问、Moonshot和豆包等不同供应商的API调用统一到OpenAI的接口模式上，实现更好的代码组织和可维护性。

## 架构设计

适配层采用了三层架构设计：

1. **LlmApiAdapter**：底层API适配器，负责实际的API调用和响应处理
2. **LlmModelFactory**：模型工厂，负责管理不同API提供商的模型和配置
3. **RagQueryManager**：RAG查询管理器，整合知识库查询和大模型调用

### 类图关系

```
RagQueryManager
    ↓ 依赖
LlmModelFactory → 单例模式
    ↓ 创建
LlmApiAdapter
```

## 主要组件

### 1. LlmApiAdapter

负责适配不同的大模型API，将它们统一到一个接口上。

主要功能：
- 自动检测API类型
- 统一的API调用入口
- 支持同步和异步调用方式
- 错误处理和响应解析

支持的API类型：
- OpenAI兼容API
- DeepSeek API
- Moonshot API
- 豆包 API
- 千问 API
- Ollama API

### 2. LlmModelFactory

负责创建和管理不同API提供商的模型实例。

主要功能：
- 管理不同模型提供商的配置
- 根据URL自动选择合适的提供商
- 提供模型列表和配置信息
- 封装API调用过程

### 3. RagQueryManager

负责处理知识库查询和大模型调用的整合。

主要功能：
- 执行RAG查询流程
- 知识库内容检索
- 提示词构建
- 进度回调和错误处理

## 使用方法

### 基本调用示例

```java
// 初始化RAG查询管理器
RagQueryManager ragManager = new RagQueryManager(context);

// 执行RAG查询
ragManager.executeRagQuery(
    apiUrl,          // API地址
    apiKey,          // API密钥
    model,           // 模型名称
    knowledgeBase,   // 知识库名称
    systemPrompt,    // 系统提示词
    userPrompt,      // 用户提问
    new RagQueryManager.RagQueryCallback() {
        @Override
        public void onProgressUpdate(String progress, String debugInfo) {
            // 处理进度更新
        }
        
        @Override
        public void onQueryCompleted(String result) {
            // 处理查询结果
        }
        
        @Override
        public void onQueryError(String errorMessage) {
            // 处理错误
        }
    }
);
```

### 直接调用大模型API

```java
// 获取模型工厂实例
LlmModelFactory modelFactory = LlmModelFactory.getInstance(context);

// 异步调用
modelFactory.callModel(
    apiUrl,
    apiKey,
    model,
    prompt,
    new LlmApiAdapter.ApiCallback() {
        @Override
        public void onSuccess(String response) {
            // 处理成功响应
        }
        
        @Override
        public void onError(String errorMessage) {
            // 处理错误
        }
    }
);

// 同步调用（在后台线程中使用）
String response = modelFactory.callModelSync(apiUrl, apiKey, model, prompt);
```

## 错误处理

适配层提供了统一的错误处理机制，包括：

1. 网络错误处理
2. API响应解析错误
3. 超时处理
4. 详细的错误信息和调试建议

## 扩展性

如需添加新的API提供商，只需：

1. 在`LlmApiAdapter.ApiType`枚举中添加新类型
2. 在`LlmApiAdapter`中实现对应的调用方法
3. 在`LlmModelFactory`的`initializeModelProviders`方法中添加新提供商配置
4. 在`getProviderByUrl`方法中添加新的URL检测逻辑

## 注意事项

1. 所有API调用都应在后台线程中进行，避免阻塞UI线程
2. API密钥应妥善保存，避免泄露
3. 不同API的响应格式可能略有不同，适配层已尽量统一处理
4. 知识库查询目前采用简单的文件读取方式，实际应用中应使用向量数据库进行相似度查询