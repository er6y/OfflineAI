package com.example.offlineai.agent

/**
 * Agent System Prompts - Dynamic assembly based on format
 * All prompts are assembled here, formats only provide action syntax
 */
object AgentPrompts {
    
    /**
     * Get system prompt based on API URL and model name
     * @param apiUrl API URL to determine format
     * @param modelName Model name (used for local models)
     * @param availableApps List of available app names on this device
     * @param useThinking Whether to include thinking tags
     */
    fun getSystemPromptForApi(
        apiUrl: String,
        modelName: String = "",
        availableApps: List<String> = emptyList(),
        useThinking: Boolean = true
    ): String {
        val format = ActionFormatRegistry.getFormatForApi(apiUrl, modelName)
        val appListJson = availableApps.joinToString("\",\"", prefix = "[\"", postfix = "\"]")
        
        // 动态组装：开场白 + thinking说明 + 正确示例 + 格式描述 + 坐标系统 + 规则
        val thinkingSection = if (useThinking) {
            """## 输出要求
你需要先在<${format.getThinkingTag()}>标签中思考，然后输出动作。

## 思考要求
<${format.getThinkingTag()}>中必须包含：
1. 当前屏幕观察：看到了什么和任务相关的关键元素
2. 任务进展：已完成什么，还需要做什么
3. 下一步计划：准备执行什么动作，为什么
4. 关键信息记录：记录有用的信息（如价格、名称、位置等）供后续步骤参考

**正确示例**：
${format.getCorrectExample()}"""
        } else {
            """## 输出要求
直接输出动作，无需思考过程。

**正确示例**：
${format.getCorrectExample().lines().filter { !it.contains(format.getThinkingTag()) }.joinToString("\n")}"""
        }   
        
        return """GUI智能体：根据任务和截图执行操作。
$thinkingSection

## 坐标系统
归一化坐标 [0-999]：左上角[0,0]，右下角[999,999]
运行时自动转换为设备实际像素坐标
估算位置% → x=round(%x*999), y=round(%y*999)

${format.getFormatDescription()}    

## 规则

- 可用应用列表（必须严格匹配，不能幻觉）: $appListJson
- 打开应用用对应格式的open/Launch动作
- **重要**：首先查看应用列表是否能直接打开应用；如果需要的应用不在列表中：先回到桌面，通过swipe滑动屏幕查找应用（注意图标文件夹的里面的应用图标），找到后计算坐标通过[动作]点击打开；多次滑动仍找不到，使用terminate/finish说明原因
- 只操作截图中可见元素
- 历史步骤判断，如果多次重复[同一][相同相似]动作，说明此方法行不通，不要继续尝试同一动作，需要尝试别的方法。
- 可以用back键返回上一页
- 点击失败? → 等待 → 重试±20~40偏移 → 换策略 → terminate/finish并说明
- 任务完成? → 使用terminate/finish动作"""
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
