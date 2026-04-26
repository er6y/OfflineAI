package com.example.offlineai.agent

import android.content.Context
import com.example.offlineai.ConfigManager
import com.example.offlineai.LogManager
import java.io.File

// Literal placeholders for agent path variables (resolved at runtime by UnifiedActionExecutor.resolveVar).
// Using these constants avoids the awkward ${"$"}{SKILL_DIR} escape inside raw strings.
private const val SKILL_DIR = "\${SKILL_DIR}"
private const val WORKSPACE = "\${WORKSPACE}"

/**
 * Agent System Prompts - unified OpenAI function-call format.
 */
object AgentPrompts {
    
    /**
     * Get unified system prompt.
     */
    @Suppress("UNUSED_PARAMETER")
    fun getSystemPromptForApi(
        context: Context,
        apiUrl: String,
        modelName: String = "",
        availableApps: List<String> = emptyList()
    ): String {
        val format = ActionFormatRegistry.getFormatForApi(apiUrl, modelName)
        
        return """AI智能体：根据任务/截图/坐标系统/Action说明/规则/输出要求执行操作。

## 坐标系统
归一化坐标 [0-999]：左上角[0,0]，右下角[999,999]；估算：x=round(%x*999), y=round(%y*999)

${format.getFormatDescription().replace("__AGENT_PRESETS__", listAgentPresetsInline(context))}

## 规则

- **context action**（每步必须首先输出）：
  - **fact（历史事实记忆）**：
    - 仅记录长期稳定且关键的信息（最终路径、用户确认、坐标、硬约束、data_memory key）
    - 禁止写入临时状态、步骤号、失败过程、猜测、下一步计划
    - 禁止重复输出已存在的fact，如无新fact请输出空字符串""
    - 只新增，不删除，不修改
  - **text（当前轮次总结）**：
    - 仅写最近状态、当前错误、下一步
    - 所有临时信息和失败重试都写在 text，不写入 fact
    - 每轮覆盖，保持简短清晰

- **data_memory**（跨步骤持久化业务数据）：
  - 仅存后续步骤要复用的业务数据（搜索结果、提取内容等）
  - `set key value` / `get key` / `list`
  - value 用纯文本或简单 JSON，建议 ≤ 500 字符
  - `terminate` 可用 `{{key}}` 引用已存数据

- **防膨胀约束**：
  - 每个里程碑最多校验一次，通过后立即推进
  - SKILL.md / 同一文件读过一次后禁止重读；需要回顾请引用 `fact` 里已存的关键片段（路径/命令/参数），不要通过再次 `read_file` 重新确认
  - 禁止连续输出无执行动作的 `context/data_memory`
  - 可判定完成即 `terminate`，不要重复总结
  - 接近步数上限必须 fail-fast：说明卡点并 `terminate fail`

- **ask_user**（困惑或不确定时询问）：包含但不限于以下场景：
  - 需密码/验证码
  - 多个不同执行方向用户未明确
  - 同一操作重复3次失败
  - 需用户介入的必要场景
  - **带 url 参数**（弹出网页让用户操作）：当 web_get_content 发现页面需要登录、返回403/401、出现验证码、会话过期、或页面无法正常操作时，使用 `ask_user` 并附带 `url` 参数弹出可见网页，让用户手动登录/验证后点击"完成"继续。格式：`{"name":"ask_user","parameters":{"text":"提示信息","url":"https://..."}}`。用户完成操作后 WebView 保留登录态，后续 web_open/web_get_content/web_execute_js 可直接使用已登录身份。

- **python**（argv-only，语义 = `subprocess.run([...])`）
  - 返回 `{status, output, exit_code, duration_sec, log_file, truncated}`
    - 脚本：`{"argv":["$SKILL_DIR/xx/scripts/xx.py","subcmd","arg1"]}`
    - 内联：`{"argv":["-c","import os; print(os.getcwd())"]}`
    - ⚠ argv 每个参数必须独立成一个字符串；禁止把脚本+参数拼成一串：❌`["xx.py sub --flag v"]` → 会报 script not found
    - `timeout_sec`：不填=60s同步（99%用这个）；`>0` 同步N秒超时转后台；`=0` 立即返回（服务/长驻任务）
    - 单实例：上一个没跑完再调会报错
  - `python_status`：查当前/上次实例状态（output head 20000 字符，超限 `truncated:true`）
  - `python_kill`：终止当前实例
  - `truncated:true` 时用 `read_file(log_file)` 取完整日志

- **文件操作工具决策**：文件/目录任务必须优先使用文件工具（create_file/read_file/write_file/read_lines/edit_lines/grep/list_dir等），不要先走文件管理器UI
- **web 工具使用决策**：未指定搜索引擎时优先 bing.com
- **应用启动**：名称不明确时先调用 `get_app_list action`，严格匹配应用名，禁止幻觉
- **坐标点击失败（无效）处理**：等待 → 重试±20~40偏移 → 换策略
- **同一 action 反复失败的硬规则**（含 `type` / `click` / `long_press` / `swipe` / `read_file` 等所有 action）：
  - (1) 同一 action 类型连续失败 **2 次** → 必须**换策略**，例如：换 action（type↔click+ime）、换页面、`ask_user` 询问、或 `terminate fail`
  - (2) 同一 action 类型连续失败 **3 次** → 框架会自动 `[AUTO_TERMINATE]`，模型自身应**先于此自动终止**主动 `terminate fail`
  - (3) **禁止仅微调坐标 ±N 像素**重试同一 action：坐标错误几乎不是真正的失败原因（真正的原因往往是窗口处于过渡态、目标节点不可写、IME 未弹出等），微调坐标无效且会被框架的 same-failure 计数器抓住
  - (4) 失败时务必查看错误关键字（如 `no_active_window`、`no editable ancestor`、`Failed to type`），按错误类别选不同应对策略，而不是机械重试
- **防重复副作用操作**：涉及 `买入/卖出/下单/支付/转账/发送/删除/确认提交` 等有实际副作用的操作，**同一笔事务只准点一次最终确认按钮**。执行后必须：
  - (1) 在 `context.fact` 追加硬标记：`已执行: <动作描述>`（含关键字段如代码/数量/金额）
  - (2) 下一步切换到"委托/订单/记录/消息/历史"类验证页面+手动刷新，查到对应记录为准
  - (3) 原表单/按钮驻留在屏幕 ≠ 未提交成功；严禁因"按钮/表单还在"就重复点确认
  - (4) 若同一副作用 click 在近 5 步内已出现 ≥ 2 次，立即 `terminate fail`，禁止继续重试
- **批量调用**：会顺序执行每一个action，如先 context 后 click

- **定时任务管理**（schedule_get / schedule_set，仅在用户明确要求时使用）：
  - 4 个任务槽位（task_id 1..4）
  - `schedule_get` 返回当前配置的 Markdown 表 + 可用 `agent_preset` 列表，配置前务必先调一次
  - `schedule_set` patch 语义：只更新提供的字段，其余保持不变
  - 时间字段用 `"HH:MM"` 格式（如 `"09:30"`），weekdays 用逗号分隔 1-7（1=周一，7=周日）
  - **跨夜允许**：`end < start` 表示从 start 到次日 end；此时 `weekdays` 必须同时覆盖起始日和次日
  - `agent_preset` 必须从以下列表中选一个：${listAgentPresetsInline(context)}

- **show_output使用决策**（可选，主动向用户展示过程）：
  - 使用场景：任务中间产出结果、数据表格、分析汇总等需要用户阅读的内容
  - size: `small`（默认小窗）/ `medium`（2/3屏宽，半屏高）/ `large`（全屏宽，2/3屏高）
  - text 支持 Markdown，可含标题、短文本段、表格、列表等
  - 与其他 action 并列输出，执行顺序不影响

- **任务完成**：必须用 `terminate`（status=success/fail）
  - `text`：**必须是用户需要的必要信息输出（Markdown 格式）**，不是摘要、不是"我帮你做了X"，而是用户真正要看的结果本体（列表/表格/关键数值/链接/结论）。禁止只写一两句总结。
  - `size`：根据 `text` 信息块大小选择 `small`（默认，短文本/单值）/ `medium`（中等表格或多条目）/ `large`（长表格、大量文本）。
  - `files`：可选，生成文件的绝对全路径列表。
- **swipe up**：从下往上滑 → 查看更多
- **swipe down**：从上往下滑 → 返回顶部/刷新

## 用户要求
${loadAgentUserStepPrompt(context)}""".trimIndent()
    }
    
    private const val TAG = "AgentPrompts"
    private const val DEFAULT_PROMPT_FILE = "common_agent.txt"

    /**
     * List available agent_user preset names (without .txt), pipe-separated.
     * Mirrors the Settings page dropdown so the model picks from the exact same set.
     * Also used to replace the __AGENT_PRESETS__ placeholder in the action format example.
     */
    private fun listAgentPresetsInline(context: Context): String {
        val files = ConfigManager.listAgentUserFiles(context)
        if (files.isEmpty()) return "(none — agent_user/ 目录为空)"
        return files.joinToString("|") {
            if (it.endsWith(".txt")) it.dropLast(4) else it
        }
    }

    /**
     * Parsed result of an agent_user prompt file.
     * @param once  Content to inject only at Step 0
     * @param step  Content to inject at every step
     */
    data class ParsedUserPrompt(
        val once: String,
        val step: String
    )

    /**
     * Parse agent_user prompt file content into @once and @step sections.
     *
     * Format:
     *   # comment (ignored)
     *   @once single line
     *   @step single line
     *   @once { multi-line block }
     *   @step { multi-line block }
     *   bare line (no prefix) -> treated as @step
     */
    @JvmStatic
    fun parseAgentUserPrompt(content: String): ParsedUserPrompt {
        val onceLines = mutableListOf<String>()
        val stepLines = mutableListOf<String>()
        val lines = content.lines()
        var i = 0
        while (i < lines.size) {
            val line = lines[i]
            val trimmed = line.trim()

            // Skip empty lines and comments
            if (trimmed.isEmpty() || trimmed.startsWith("#")) {
                i++
                continue
            }

            // Multi-line block: @once { ... } or @step { ... }
            if (trimmed.startsWith("@once") && trimmed.contains("{")) {
                i++
                val block = StringBuilder()
                while (i < lines.size) {
                    val bLine = lines[i]
                    if (bLine.trim() == "}") { i++; break }
                    block.appendLine(bLine)
                    i++
                }
                val blockText = block.toString().trim()
                if (blockText.isNotEmpty()) onceLines.add(blockText)
                continue
            }
            if (trimmed.startsWith("@step") && trimmed.contains("{")) {
                i++
                val block = StringBuilder()
                while (i < lines.size) {
                    val bLine = lines[i]
                    if (bLine.trim() == "}") { i++; break }
                    block.appendLine(bLine)
                    i++
                }
                val blockText = block.toString().trim()
                if (blockText.isNotEmpty()) stepLines.add(blockText)
                continue
            }

            // Single-line: @once <text> or @step <text>
            if (trimmed.startsWith("@once ") || trimmed.startsWith("@once\t")) {
                val text = trimmed.substringAfter("@once").trim()
                if (text.isNotEmpty()) onceLines.add(text)
                i++
                continue
            }
            if (trimmed.startsWith("@step ") || trimmed.startsWith("@step\t")) {
                val text = trimmed.substringAfter("@step").trim()
                if (text.isNotEmpty()) stepLines.add(text)
                i++
                continue
            }

            // Bare line (no prefix) -> @step (backward compatible)
            stepLines.add(line)
            i++
        }

        return ParsedUserPrompt(
            once = onceLines.joinToString("\n"),
            step = stepLines.joinToString("\n")
        )
    }

    /**
     * Load raw content of the selected agent_user prompt file.
     */
    private fun loadRawAgentUserFile(context: Context, fileName: String? = null): String {
        val name = fileName
            ?: ConfigManager.getString(context, ConfigManager.KEY_AGENT_USER_PROMPT_FILE, DEFAULT_PROMPT_FILE)
        return try {
            val agentUserDir = File(ConfigManager.getAgentUserPath(context))
            File(agentUserDir, name).readText(Charsets.UTF_8).trim()
        } catch (e: Exception) {
            LogManager.logW(TAG, "[LOAD] Failed to load $name: ${e.message}, using fallback")
            FALLBACK_STEP_PROMPT
        }
    }

    /**
     * Load @step content from the selected agent_user file.
     * Injected into system prompt (every step).
     */
    private fun loadAgentUserStepPrompt(context: Context): String {
        val raw = loadRawAgentUserFile(context)
        val parsed = parseAgentUserPrompt(raw)
        return parsed.step.ifEmpty { FALLBACK_STEP_PROMPT }
    }

    /**
     * Load @once content from the selected agent_user file.
     * Injected only at Step 0 in buildUserPromptWithHistory.
     */
    @JvmStatic
    fun loadAgentUserOncePrompt(context: Context): String {
        val raw = loadRawAgentUserFile(context)
        val parsed = parseAgentUserPrompt(raw)
        return parsed.once
    }

    /**
     * Load a specific user prompt file from agent_user directory.
     * Used by scheduled tasks to load per-task prompt files.
     * Returns ONLY the @step portion (backward compatible).
     */
    @JvmStatic
    fun loadAgentUserPromptFile(context: Context, fileName: String): String {
        val raw = loadRawAgentUserFile(context, fileName)
        val parsed = parseAgentUserPrompt(raw)
        return parsed.step.ifEmpty { FALLBACK_STEP_PROMPT }
    }

    private const val FALLBACK_STEP_PROMPT = """- 输出动作前请思考：
  - 输出action是否正确恰当，能否符合任务要求
  - context需要包含哪些内容，才能确保后续步骤有足够信息，不迷惑或者造成步骤断片死循环。
  - 是否通过data_memory记录足够业务信息
  - 是否需要读取业务信息给下一步综合
  - 当前屏幕关键元素、任务进展和下一步计划"""

    /**
     * Build the first-round (Step 0) prompt block.
     * Includes: skill catalog, workspace path, @once user prompt, and planning requirements.
     * Called from AgentAccessibilityService.buildUserPromptWithHistory() only at stepIndex == 0.
     */
    @JvmStatic
    fun buildFirstRoundPrompt(
        context: Context,
        skillCatalog: String,
        agentWorkspacePath: String
    ): String {
        val sb = StringBuilder()

        // Path variables: tell model the absolute paths and that ${VAR} is auto-resolved
        val skillsDir = ConfigManager.getSkillsPath(context)
        sb.append("\n## 路径变量（action 中可直接使用，程序自动替换为绝对路径）\n")
        sb.append("- $SKILL_DIR = $skillsDir\n")
        if (agentWorkspacePath.isNotEmpty()) {
            sb.append("- $WORKSPACE = $agentWorkspacePath\n")
        }
        sb.append("\n")

        // Skill catalog
        if (skillCatalog.isNotEmpty()) {
            sb.append("## 可用技能\n以下是已安装的技能，如任务需要用到，必须先 read_file 对应 SKILL.md 学习用法后再编写脚本：\n")
            sb.append(skillCatalog)
            sb.append("\n⚠️ 如果 `## 用户要求` 中未有必选的技能，但是任务涉及以上技能，首轮 context.fact 必须记录：所需 SKILL.md 全路径 + 默认工作目录，供后续 step 参考。不涉及则忽略。\n\n")
        }

        // Default workspace path
        if (agentWorkspacePath.isNotEmpty()) {
            sb.append("默认工作目录: $agentWorkspacePath （脚本和生成文件默认保存到此目录，除非用户指定其他路径）\n\n")
        }

        // @once user prompt (from agent_user config file)
        val oncePrompt = loadAgentUserOncePrompt(context)
        if (oncePrompt.isNotEmpty()) {
            sb.append("\n## 首轮用户提示\n")
            sb.append(oncePrompt)
            sb.append("\n\n")
        }

        // First-round planning requirements
        sb.append("""
##首轮规划要求（只执行一次）
 -这是任务开始。先输出任务简报 JSON，并使用 data_memory 保存为 key=taskBrief；同一轮继续输出首个可执行 action。
 -任务简报 JSON 必须包含字段：任务、目标、里程碑(3-5个)、执行约束（强制）。
 -如果用户提供了附件（[Attached files]），必须在简报中原样记录所有附件路径，供后续步骤引用。
 -固定模板（原样复制）：{"name":"data_memory","parameters":{"operation":"set","key":"taskBrief","value":"任务:<任务>\n目标:<目标>\n用户附件:\n<原样列出所有附件路径，无则写'无'>\n里程碑:\nM1 <内容>\nM2 <内容>\nM3 <内容>\n...\n执行约束:\n<约束1>\n<约束2>\n...\n"}}""")

        return sb.toString()
    }

    /**
     * Get Agent system prompt.
     * @param context Application context
     * @param availableApps List of available app names on this device
     */
    @JvmStatic
    fun getAgentSystemPrompt(context: Context, availableApps: List<String> = emptyList()): String {
        return getSystemPromptForApi(context, "local", "", availableApps)
    }
}
