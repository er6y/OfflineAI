package com.example.offlineai.agent

/**
 * Agent System Prompts - Dynamic assembly based on format
 * All prompts are assembled here, formats only provide action syntax
 */
object AgentPrompts {
    
    /**
     * Context action requirements (shared by all formats)
     */
    const val CONTEXT_ACTION_REQUIREMENTS = """**每步必须先输出 context action**，包含：
① 任务进展：当前页面/状态；**已处理项目清单（含名称+状态，防止重复操作）**
② 关键信息：错误原因、重要坐标、已尝试次数及策略
③ 下一步计划：做什么、为什么、失败备选
⚠️ context只记录状态和策略；业务数据（邮件/内容/结果）必须用data_memory存储，严禁写入context。"""
    
    /**
     * Get system prompt based on API URL and model name
     * @param apiUrl API URL to determine format
     * @param modelName Model name (used for local models)
     * @param availableApps List of available app names on this device
     * @param useThinking Whether to include thinking tags
     * @param userSelectedFormat User selected format name (e.g., "OpenAI FuncCall"), null for auto selection
     */
    @Suppress("UNUSED_PARAMETER")
    fun getSystemPromptForApi(
        apiUrl: String,
        modelName: String = "",
        availableApps: List<String> = emptyList(),
        useThinking: Boolean = true,
        userSelectedFormat: String? = null
    ): String {
        // Use user selected format if provided, otherwise auto-detect from API URL
        val format = if (userSelectedFormat != null && userSelectedFormat != "Auto") {
            // Map UI format names to Registry names
            val registryName = when (userSelectedFormat) {
                "AutoGLM-Phone" -> "AutoGLM"
                else -> userSelectedFormat
            }
            ActionFormatRegistry.getFormatByName(registryName) 
                ?: ActionFormatRegistry.getFormatForApi(apiUrl, modelName)
        } else {
            ActionFormatRegistry.getFormatForApi(apiUrl, modelName)
        }
        
        // 动态组装：开场白 + thinking说明 + 格式描述 + 坐标系统 + 规则
        val thinkingSection = if (useThinking) {
            """## 输出要求
先在<${format.getThinkingTag()}>[思考]</${format.getThinkingTag()}>中思考，再输出动作。思考内容：当前屏幕关键元素、任务进展、下一步计划。"""
        } else {
            """## 输出要求
直接输出动作，无需思考过程。"""
        }   
        
        return """GUI智能体：根据任务和截图执行操作。
$thinkingSection

## 坐标系统
归一化坐标 [0-999]：左上角[0,0]，右下角[999,999]；估算：x=round(%x*999), y=round(%y*999)

${format.getFormatDescription()}

## 规则
- **ask_user**：①需密码/验证码；②多选项用户未明确；③同一操作重复3次失败；④需用户介入
- **网页检索**：优先 web_open/web_get_content/web_execute_js，默认 bing.com；非用户明确要求不打开浏览器应用
- **应用**：名称不明确时先调用 get_app_list；严格匹配应用名，禁止幻觉
- 可用back键返回；点击失败 → 等待 → 重试±20~40偏移 → 换策略
- **任务完成**：必须用 terminate/finish（status=success，text=结果摘要）
- **data_memory**：业务数据（邮件/内容/结果等）必须用此存储，严禁写入context
  - `set key value` 存入；`get key` 读取；`list` 查看；收到系统确认才算成功
  - 提示词开头的 **Data Memory:[key列表]** 是唯一可信已存状态；见到所有目标key后立即terminate
  - terminate/finish 的 text 用 `{{key}}` 引用，系统自动展开
- **swipe up**：从下往上滑 → 查看更多；**swipe down**：从上往下滑 → 返回顶部/刷新"""
    }
    
    /**
     * Get Agent system prompt based on thinking mode
     * @param noThinking Whether to use the no-thinking version
     * @param availableApps List of available app names on this device
     */
    @JvmStatic
    fun getAgentSystemPrompt(noThinking: Boolean, availableApps: List<String> = emptyList()): String {
        return getSystemPromptForApi("local", "", availableApps, !noThinking)
    }
}
