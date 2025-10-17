# 多媒体聊天UI集成指南

## 概述

已成功集成MNN的多媒体聊天UI组件，支持：
- ✅ 图片输入/输出
- ✅ 语音输入/输出（预留接口）
- ✅ 多种折叠区域：`<think>`, `<debug>`, 性能指标
- ✅ Markdown渲染
- ✅ 流式输出增量更新

## 文件结构

```
app/src/main/java/com/example/offlineai/chat/
├── model/
│   └── ChatDataItem.kt              # 聊天消息数据模型
├── chatlist/
│   ├── ChatViewHolders.kt           # RecyclerView ViewHolders
│   ├── ChatRecyclerViewAdapter.kt   # RecyclerView适配器
│   └── AudioPlayerComponent.kt      # 音频播放组件
└── utils/
    └── CollapsibleTextParser.kt     # 折叠文本解析器

app/src/main/java/com/example/offlineai/utils/
└── AudioPlayService.kt              # 音频播放服务

app/src/main/res/layout/
├── item_holder_chatheader.xml       # 时间戳头部布局
├── item_holder_user.xml             # 用户消息布局
└── item_holder_assistant.xml        # AI助手消息布局（支持多折叠区）

app/src/main/res/drawable/
├── bg_chat_user.xml                 # 用户消息气泡背景
├── bg_chat_assistant.xml            # AI消息气泡背景
├── ic_arrow_up.xml                  # 展开图标
├── ic_arrow_down.xml                # 折叠图标
├── ic_audio_play.xml                # 播放图标
└── ic_audio_pause.xml               # 暂停图标
```

## 在RagQaFragment中集成

### 1. 修改布局文件 `fragment_rag_qa.xml`

```xml
<!-- 删除原来的 textViewResponse -->
<!-- 替换为 RecyclerView -->
<androidx.recyclerview.widget.RecyclerView
    android:id="@+id/recyclerViewChat"
    android:layout_width="0dp"
    android:layout_height="0dp"
    app:layout_constraintTop_toBottomOf="@+id/某个上方控件"
    app:layout_constraintBottom_toBottomOf="parent"
    app:layout_constraintStart_toStartOf="parent"
    app:layout_constraintEnd_toEndOf="parent" />
```

### 2. 在RagQaFragment.java中初始化

```java
import com.example.offlineai.chat.model.ChatDataItem;
import com.example.offlineai.chat.chatlist.ChatRecyclerViewAdapter;
import com.example.offlineai.chat.chatlist.ChatViewHolders;
import com.example.offlineai.chat.utils.CollapsibleTextParser;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

public class RagQaFragment extends Fragment {
    
    // 替换原来的 TextView
    private RecyclerView recyclerViewChat;
    private ChatRecyclerViewAdapter chatAdapter;
    private List<ChatDataItem> chatMessages = new ArrayList<>();
    
    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
        View view = inflater.inflate(R.layout.fragment_rag_qa, container, false);
        
        // 初始化RecyclerView
        recyclerViewChat = view.findViewById(R.id.recyclerViewChat);
        recyclerViewChat.setLayoutManager(new LinearLayoutManager(getContext()));
        
        chatAdapter = new ChatRecyclerViewAdapter(getContext());
        chatAdapter.updateModelNameAndItems("current_model", chatMessages);
        recyclerViewChat.setAdapter(chatAdapter);
        
        return view;
    }
    
    // 获取当前时间字符串
    private String getCurrentTime() {
        SimpleDateFormat sdf = new SimpleDateFormat("HH:mm", Locale.getDefault());
        return sdf.format(new Date());
    }
}
```

### 3. 发送消息（带图片支持）

```java
private void sendMessage() {
    String userInput = editTextUserPrompt.getText().toString().trim();
    if (userInput.isEmpty()) return;
    
    // 创建用户消息
    ChatDataItem userMsg;
    
    // 检查是否有选中的图片
    if (selectedImageUris != null && !selectedImageUris.isEmpty()) {
        // 带图片的消息
        userMsg = ChatDataItem.Companion.createImageInputData(
            getCurrentTime(),
            userInput,
            selectedImageUris.get(0)  // 第一张图片
        );
    } else {
        // 纯文本消息
        userMsg = new ChatDataItem(getCurrentTime(), ChatViewHolders.USER, userInput);
    }
    
    chatMessages.add(userMsg);
    chatAdapter.notifyItemInserted(chatMessages.size() - 1);
    
    // 创建AI消息占位符
    ChatDataItem aiMsg = new ChatDataItem(ChatViewHolders.ASSISTANT);
    aiMsg.setLoading(true);
    chatMessages.add(aiMsg);
    chatAdapter.notifyItemInserted(chatMessages.size() - 1);
    
    // 滚动到底部
    recyclerViewChat.smoothScrollToPosition(chatMessages.size() - 1);
    
    // 清空输入框和图片选择
    editTextUserPrompt.setText("");
    selectedImageUris.clear();
    imageThumbnailAdapter.notifyDataSetChanged();
    
    // 调用API发送消息
    callLlmApi(userInput);
}
```

### 4. 接收流式响应（支持折叠标记）

```java
private void updateAiResponse(String chunk) {
    if (chatMessages.isEmpty()) return;
    
    ChatDataItem lastMsg = chatMessages.get(chatMessages.size() - 1);
    if (lastMsg.getType() != ChatViewHolders.ASSISTANT) return;
    
    // 累积文本
    String currentText = lastMsg.getText();
    if (currentText == null) currentText = "";
    String newText = currentText + chunk;
    lastMsg.setText(newText);
    
    // 解析折叠标记
    CollapsibleTextParser.INSTANCE.parseAndPopulate(newText, lastMsg);
    
    // 提取thinking时间
    long thinkingTime = CollapsibleTextParser.INSTANCE.extractThinkingTime(newText);
    if (thinkingTime > 0) {
        lastMsg.setThinkingFinishedTime(thinkingTime);
    }
    
    lastMsg.setLoading(false);
    
    // 增量更新（使用payload机制，性能更好）
    chatAdapter.updateRecentItem(lastMsg);
    
    // 滚动到底部
    recyclerViewChat.smoothScrollToPosition(chatMessages.size() - 1);
}
```

### 5. 响应完成后处理

```java
private void onResponseComplete() {
    if (chatMessages.isEmpty()) return;
    
    ChatDataItem lastMsg = chatMessages.get(chatMessages.size() - 1);
    if (lastMsg.getType() != ChatViewHolders.ASSISTANT) return;
    
    lastMsg.setLoading(false);
    chatAdapter.notifyItemChanged(chatMessages.size() - 1);
}
```

### 6. 添加Debug信息

```java
private void addDebugInfo(String debugInfo) {
    if (chatMessages.isEmpty()) return;
    
    ChatDataItem lastMsg = chatMessages.get(chatMessages.size() - 1);
    if (lastMsg.getType() != ChatViewHolders.ASSISTANT) return;
    
    // 设置debug文本
    lastMsg.setDebugText(debugInfo);
    chatAdapter.notifyItemChanged(chatMessages.size() - 1);
}
```

### 7. 添加性能指标

```java
private void addPerformanceMetrics(double prefillSpeed, double decodeSpeed) {
    if (chatMessages.isEmpty()) return;
    
    ChatDataItem lastMsg = chatMessages.get(chatMessages.size() - 1);
    if (lastMsg.getType() != ChatViewHolders.ASSISTANT) return;
    
    String perfText = String.format(Locale.US, 
        "Prefill: %.1f tokens/s\nDecode: %.1f tokens/s", 
        prefillSpeed, decodeSpeed);
    lastMsg.setPerformanceText(perfText);
    chatAdapter.notifyItemChanged(chatMessages.size() - 1);
}
```

### 8. 清空聊天记录

```java
private void clearChat() {
    chatMessages.clear();
    chatAdapter.notifyDataSetChanged();
}
```

## 折叠标记格式

### Thinking标记
```
<think>
这是推理过程...
可以是多行文本
</think>

或者：
<thinking>
推理内容
</thinking>
```

### Debug标记
```
<debug>
Debug信息：
- 检索到3个文档
- 使用了reranker
- 总耗时: 2.5s
</debug>
```

### 性能指标（自动识别）
```
prefill: 149 tokens/s decode: 89 tokens/s

或者：
<performance>
Prefill: 149 tokens/s
Decode: 89 tokens/s
</performance>
```

## 示例：完整的响应文本

```
<think>
用户询问关于AI的问题，我需要：
1. 检索相关知识库
2. 分析上下文
3. 生成准确回答
</think>

<debug>
检索结果：
- 找到5个相关文档
- Rerank后保留3个
- 相似度分数: 0.85, 0.78, 0.72
</debug>

人工智能（AI）是计算机科学的一个分支，致力于创建能够执行通常需要人类智能的任务的系统...

<performance>
Prefill: 149.2 tokens/s
Decode: 89.5 tokens/s
Total time: 3.2s
</performance>
```

## 注意事项

1. **图片显示**：用户消息中的图片会显示在文本上方
2. **流式更新**：使用`updateRecentItem()`进行增量更新，性能更好
3. **折叠状态**：默认展开所有折叠区域，用户可以点击切换
4. **Markdown渲染**：所有文本内容都支持Markdown格式
5. **音频功能**：已预留接口，需要实现录音和播放功能

## 后续扩展

### 添加图片生成支持
```java
ChatDataItem aiMsg = new ChatDataItem(ChatViewHolders.ASSISTANT);
aiMsg.setDisplayText("我为你生成了这张图片：");
aiMsg.setImageUri(Uri.fromFile(new File(generatedImagePath)));
chatMessages.add(aiMsg);
chatAdapter.notifyItemInserted(chatMessages.size() - 1);
```

### 添加语音输入支持
```java
ChatDataItem userMsg = ChatDataItem.Companion.createAudioInputData(
    getCurrentTime(),
    "语音转文本内容",
    audioFilePath,
    audioDuration
);
```

## 性能优化建议

1. 使用`notifyItemChanged(position, payload)`进行增量更新
2. 避免频繁调用`notifyDataSetChanged()`
3. 长文本使用`RecyclerView`的回收机制自动优化
4. 图片使用缩略图，点击后显示大图
