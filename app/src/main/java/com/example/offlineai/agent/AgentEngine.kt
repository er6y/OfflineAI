package com.example.offlineai.agent.core

import android.content.Context
import android.graphics.Bitmap
import com.example.offlineai.LogManager
import com.example.offlineai.ConfigManager
import com.example.offlineai.agent.AgentTtsHelper
import com.example.offlineai.agent.UnifiedActionExecutor
import com.example.offlineai.agent.model.AgentAction
import com.example.offlineai.agent.model.AgentResponse
import com.example.offlineai.agent.model.ExecutionResult
import com.example.offlineai.agent.model.TrajectoryMemory
import com.example.offlineai.agent.model.TrajectoryStep
import com.example.offlineai.agent.parser.ActionParser
import com.example.offlineai.agent.service.AgentAccessibilityService
import com.example.offlineai.agent.utils.ScreenshotCapture
import kotlinx.coroutines.*

/**
 * Agent Engine - core orchestrator for MAI-UI agent execution
 * Manages the agent execution loop: screenshot → model inference → action execution
 */
class AgentEngine(private val context: Context) {
    
    companion object {
        private const val TAG = "AgentEngine"
        private const val MAX_STEPS = 50 // Maximum steps to prevent infinite loops
        private const val STEP_DELAY_MS = 1500L // Delay between steps for UI stabilization
        private const val MAX_PARSE_RETRIES = 3 // Maximum retries for parse failures
    }
    
    private val executor = UnifiedActionExecutor(context)
    private val memory = TrajectoryMemory(maxHistorySteps = 10)  // Increased from 3->8->10 to help model detect repetitive failures and strategy shifts
    private var screenshotCapture = ScreenshotCapture(context)
    private var floatingWindow: com.example.offlineai.agent.ui.AgentFloatingWindow? = null
    private var agentTts: AgentTtsHelper? = null
    
    @Volatile
    private var isRunning = false
    
    @Volatile
    private var shouldStop = false
    
    // Cached AgentKB context: queried once at task start, reused by all steps and experience summary
    private var cachedAgentKbContext: String = ""
    
    // Current context: Agent's memory for next step (initialized with RAG recall, updated by context action)
    private var currentContext: String = ""
    
    // Data Memory: task-scoped KV store for accumulated business data (e.g., extracted email content)
    // Cleared at task start; only keys injected into prompt each step; values retrieved on demand
    private val dataMemory = mutableMapOf<String, String>()
    
    private var currentJob: Job? = null
    
    /**
     * Callback interface for agent execution events
     */
    interface AgentCallback {
        fun onStepStarted(stepIndex: Int)
        fun onStepCompleted(stepIndex: Int, action: AgentAction, result: ExecutionResult)
        fun onTaskCompleted(success: Boolean, message: String)
        fun onError(error: String)
        // Suspend execution and show AskUser UI; resume with user input text (may be empty)
        suspend fun onAskUser(question: String): String
    }
    
    private var callback: AgentCallback? = null
    
    fun setCallback(callback: AgentCallback?) {
        this.callback = callback
    }
    
    /**
     * Set ScreenshotCapture instance (for sharing initialized MediaProjection)
     */
    fun setScreenshotCapture(capture: ScreenshotCapture) {
        this.screenshotCapture = capture
    }
    
    /**
     * Set FloatingWindow reference (for hiding during screenshot)
     */
    fun setFloatingWindow(window: com.example.offlineai.agent.ui.AgentFloatingWindow?) {
        this.floatingWindow = window
    }

    /**
     * Set AgentTtsHelper for Terminate/AskUser voice announcements.
     * Pass null to disable TTS (when setting is off).
     */
    fun setAgentTts(tts: AgentTtsHelper?) {
        this.agentTts = tts
    }
    
    /**
     * Initialize MediaProjection for screenshot capture
     */
    fun initScreenCapture(resultCode: Int, data: android.content.Intent) {
        screenshotCapture.initMediaProjection(resultCode, data)
    }
    
    /**
     * Check if accessibility service is available
     */
    fun isAccessibilityServiceEnabled(): Boolean {
        return AgentAccessibilityService.isServiceEnabled()
    }
    
    /**
     * Execute agent task based on model streaming output
     * This is called when <tool_call> is detected in model output
     */
    suspend fun executeFromModelOutput(
        modelOutput: String,
        screenshot: Bitmap?
    ): ExecutionResult = withContext(Dispatchers.IO) {
        
        if (!isAccessibilityServiceEnabled()) {
            return@withContext ExecutionResult(
                success = false,
                message = "Accessibility service not enabled"
            )
        }
        
        // Parse model output (with user format selection support)
        val response = ActionParser.parse(modelOutput, "", "", context)
        if (response == null) {
            LogManager.logE(TAG, "Failed to parse model output")
            return@withContext ExecutionResult(
                success = false,
                message = "Failed to parse agent action"
            )
        }
        
        // Handle special actions that don't require execution
        when (response.action) {
            is AgentAction.Terminate -> {
                val success = response.action.status == AgentAction.Terminate.Status.SUCCESS
                callback?.onTaskCompleted(success, "Task terminated: ${response.action.status.value}")
                return@withContext ExecutionResult(
                    success = success,
                    message = "Task terminated: ${response.action.status.value}"
                )
            }
            else -> {
                // Execute the action
                val result = executor.execute(response.action)

                // Get coordinate error if any
                val coordError = ActionParser.getLastCoordinateError()
                ActionParser.clearCoordinateError()
                
                // Save complete model output for history
                val rawOutput = modelOutput.trim()
                
                // Store in memory
                val step = TrajectoryStep(
                    stepIndex = memory.getStepCount(),
                    screenshot = screenshot,
                    action = response.action,
                    executionResult = result,
                    coordinateError = coordError,
                    rawModelOutput = rawOutput
                )
                memory.addStep(step)
                
                callback?.onStepCompleted(step.stepIndex, response.action, result)
                
                return@withContext result
            }
        }
    }
    
    /**
     * Execute a complete agent task (autonomous multi-step execution)
     * This is for future use when we want fully autonomous agent execution
     */
    suspend fun executeTask(
        taskGoal: String,
        apiUrl: String,
        modelName: String,
        modelInferenceCallback: suspend (instruction: String, screenshot: Bitmap?, history: List<TrajectoryStep>) -> String
    ) = withContext(Dispatchers.IO) {
        
        if (isRunning) {
            LogManager.logW(TAG, "Agent is already running")
            return@withContext
        }
        
        if (!isAccessibilityServiceEnabled()) {
            callback?.onError("Accessibility service not enabled")
            return@withContext
        }
        
        // Set accessibility service to active state
        AgentAccessibilityService.getInstance()?.setAgentActive(true)
        
        isRunning = true
        shouldStop = false
        memory.clear()
        memory.setTaskGoal(taskGoal)
        dataMemory.clear()  // Clear task-scoped data memory for every new task
        AgentAccessibilityService.getInstance()?.updateDataMemoryKeys(emptyList())
        
        // Query AgentKB once at task start, cache for all steps and experience summary
        cachedAgentKbContext = try {
            com.example.offlineai.KnowledgeGraphDatabase.queryKnowledgeBase(
                context, "AgentKB", taskGoal, 3)
        } catch (e: Exception) {
            LogManager.logW(TAG, "[AGENT_RAG] AgentKB query failed at task start (non-fatal): ${e.message}")
            ""
        }
        if (cachedAgentKbContext.isNotEmpty()) {
            LogManager.logI(TAG, "[AGENT_RAG] AgentKB cached ${cachedAgentKbContext.length} chars of experience at task start")
        }
        
        // Initialize currentContext with RAG recall
        currentContext = if (cachedAgentKbContext.isNotEmpty()) {
            "RAG召回: $cachedAgentKbContext"
        } else {
            ""
        }
        
        // Pass current context to AgentAccessibilityService for step prompt assembly
        AgentAccessibilityService.getInstance()?.currentContext = currentContext
        
        LogManager.logI(TAG, "Starting agent task: $taskGoal")
        
        currentJob = launch {
            try {
                var stepIndex = 0
                var parseRetryCount = 0
                var lastAction: AgentAction? = null
                var consecutivePureSteps = 0  // Track stuck pure context/data_memory loops
                
                // Auto-press Home before first screenshot so model starts from Home screen
                val homeResult = AgentAccessibilityService.getInstance()?.pressHome() ?: false
                LogManager.logI(TAG, "[AGENT_INIT] Auto-press Home before first step: ${if (homeResult) "success" else "failed"}")
                if (homeResult) {
                    delay(1000) // Wait for Home screen to fully render
                }
                
                while (stepIndex < MAX_STEPS && !shouldStop) {
                    // Decide whether to capture screenshot based on last action
                    // For first step (lastAction == null), always capture screenshot
                    val needsScreenshot = lastAction?.needsScreenshot() ?: true
                    
                    // CRITICAL: Always create a new screenshot variable in each loop iteration
                    // to avoid accidentally reusing the previous screenshot
                    val screenshot: Bitmap? = if (needsScreenshot) {
                        // Capture new screenshot
                        val captured = screenshotCapture.captureScreen(floatingWindow)
                        if (captured == null) {
                            callback?.onError("Failed to capture screenshot")
                            break
                        }
                        LogManager.logI(TAG, "[AGENT][STEP_${stepIndex + 1}] Screenshot captured")
                        captured
                    } else {
                        // Skip screenshot for actions that return structured data (e.g., web_get_content)
                        LogManager.logI(TAG, "[AGENT][STEP_${stepIndex + 1}] Skipping screenshot (last action: ${lastAction?.javaClass?.simpleName})")
                        null
                    }
                    
                    // Pass screenshot directly to callback (null if not needed)
                    val modelOutput = modelInferenceCallback(
                        taskGoal,
                        screenshot,
                        memory.getRecentSteps()
                    )
                    
                    // Parse response with dynamic format selection based on API URL and user settings
                    val parseResult = ActionParser.parseActions(modelOutput, apiUrl, modelName, context)
                    if (parseResult == null) {
                        parseRetryCount++
                        LogManager.logE(TAG, "Parse failed at step $stepIndex (retry $parseRetryCount/$MAX_PARSE_RETRIES)")
                        
                        if (parseRetryCount >= MAX_PARSE_RETRIES) {
                            LogManager.logE(TAG, "Max parse retries reached, terminating agent")
                            callback?.onError("Failed to parse model output after $MAX_PARSE_RETRIES retries")
                            break
                        }
                        
                        val format = com.example.offlineai.agent.ActionFormatRegistry.getFormatForApi(apiUrl, modelName)
                        val errorHint = format.getErrorHint()
                        
                        // Add error feedback to memory for next retry
                        val step = TrajectoryStep(
                            stepIndex = memory.getStepCount(),
                            screenshot = screenshot,
                            action = AgentAction.Wait,
                            executionResult = ExecutionResult(
                                success = false,
                                message = errorHint,
                                error = null
                            ),
                            rawModelOutput = ""  // No valid tool_call for failed parse
                        )
                        memory.addStep(step)
                        continue  // Retry with error feedback
                    }

                    parseRetryCount = 0
                    val actions = parseResult
                    
                    // Extract context action and update currentContext
                    val contextAction = actions.filterIsInstance<AgentAction.Context>().firstOrNull()
                    if (contextAction != null) {
                        // Enforce 2500 char soft limit (increased from 1500 for info collection tasks)
                        // Prompt requires 1000 chars minimum, 2500 allows accumulating multiple email contents
                        currentContext = if (contextAction.text.length > 2500) {
                            LogManager.logW(TAG, "[CONTEXT] Context too long (${contextAction.text.length} chars), truncating to 2500")
                            contextAction.text.take(2500)
                        } else {
                            contextAction.text
                        }
                        LogManager.logI(TAG, "[CONTEXT] Updated: ${currentContext.take(100)}...")
                        // Update AgentAccessibilityService's context for next step
                        AgentAccessibilityService.getInstance()?.currentContext = currentContext
                    } else {
                        // Missing context action: reuse previous context + warning (soft mode, don't abort)
                        LogManager.logW(TAG, "[CONTEXT] Missing context action at step $stepIndex, reusing previous context: ${currentContext.take(50)}")
                    }
                    
                    // Handle data_memory actions before selecting actual action
                    // For "get" operations, inject returned value into lastStepResult so model sees it next step
                    val dataMemoryActions = actions.filterIsInstance<AgentAction.DataMemory>()
                    for (dmAction in dataMemoryActions) {
                        val dmResult = executeDataMemory(dmAction)
                        if (dmAction.operation == "get" || dmAction.operation == "list") {
                            val resultMsg = if (dmResult.success) dmResult.message else "data_memory ${dmAction.operation} failed: ${dmResult.message}"
                            AgentAccessibilityService.getInstance()?.lastStepResult = resultMsg
                        }
                    }
                    
                    // Get actual action (first non-context, non-data_memory action)
                    val actualAction = actions.firstOrNull { it !is AgentAction.Context && it !is AgentAction.DataMemory }
                    if (actualAction == null) {
                        // Pure context+data_memory step (no UI action) - record in memory and advance
                        // IMPORTANT: do NOT set lastAction=Context here - must take screenshot next step
                        // so model can see the screen and decide next action (avoid blind loop)
                        consecutivePureSteps++
                        LogManager.logI(TAG, "[AGENT][STEP_${stepIndex + 1}] Pure context/data_memory step, advancing (consecutive=$consecutivePureSteps)")
                        val dmSummary = dataMemoryActions.joinToString("; ") { "${it.operation}('${it.key}')" }
                        val pureStep = TrajectoryStep(
                            stepIndex = memory.getStepCount(),
                            screenshot = screenshot,
                            action = AgentAction.Wait,
                            executionResult = ExecutionResult(true, "context+data_memory step: $dmSummary"),
                            rawModelOutput = modelOutput.trim()
                        )
                        memory.addStep(pureStep)
                        callback?.onStepCompleted(stepIndex, AgentAction.Wait, pureStep.executionResult)
                        // Inject dm result into lastStepResult so model knows set succeeded
                        val dmKeys = dataMemoryActions.filter { it.operation == "set" }.mapNotNull { it.key }
                        val realKeys = dataMemory.keys.joinToString(", ").ifEmpty { "（空）" }
                        val setMsg = if (dmKeys.isNotEmpty()) {
                            "data_memory set success: ${dmKeys.joinToString(", ")}. Data Memory now: $realKeys"
                        } else {
                            "Data Memory now: $realKeys"
                        }
                        // When stuck in pure loop: inject strong system warning to break the deadlock
                        val warningMsg = if (consecutivePureSteps >= 2) {
                            LogManager.logW(TAG, "[AGENT][PURE_LOOP] Consecutive pure steps=$consecutivePureSteps, injecting loop-break warning")
                            "\n⚠️ 系统警告：已连续${consecutivePureSteps}步只输出data_memory/context，未执行任何UI操作。" +
                            "当前 Data Memory 实际存储的key为：[$realKeys]。" +
                            "你的context中声称已存入的key与实际不符时，必须重新执行 set 操作补存，或者立即输出terminate完成任务。" +
                            "禁止继续重复set同一个key。"
                        } else ""
                        AgentAccessibilityService.getInstance()?.lastStepResult = setMsg + warningMsg
                        // lastAction stays as-is: next step WILL take screenshot (model needs screen context)
                        stepIndex++
                        continue
                    }
                    // Reset pure step counter when a real UI action is executed
                    consecutivePureSteps = 0
                    
                    val response = AgentResponse(actualAction)
                    callback?.onStepStarted(stepIndex)
                    
                    // Handle special actions
                    when (response.action) {
                        is AgentAction.Terminate -> {
                            val success = response.action.status == AgentAction.Terminate.Status.SUCCESS
                            
                            // Expand {{key}} placeholders in terminate text using dataMemory values
                            val expandedText = expandPlaceholders(response.action.text)
                            val expandedAction = response.action.copy(text = expandedText)
                            
                            // Store Terminate step in memory (with expanded text)
                            val terminateStep = TrajectoryStep(
                                stepIndex = memory.getStepCount(),
                                screenshot = screenshot,
                                action = expandedAction,
                                executionResult = ExecutionResult(success = success, message = "Task terminated: ${expandedAction.status.value}"),  
                                coordinateError = null,
                                rawModelOutput = modelOutput.trim()  // Save complete output for history
                            )
                            memory.addStep(terminateStep)
                            
                            // Step 1: Show terminate text in floating window FIRST (before TTS)
                            callback?.onStepCompleted(stepIndex, expandedAction, terminateStep.executionResult)
                            
                            // Step 2: Launch TTS asynchronously so experience summary can run concurrently.
                            // Both TTS and experience summary run in parallel; we wait for TTS at the end.
                            val ttsPrefix = if (success)
                                context.getString(com.example.offlineai.R.string.agent_tts_task_done)
                            else
                                context.getString(com.example.offlineai.R.string.agent_tts_task_failed)
                            val ttsText = expandedText.trim()
                            val ttsFullText = if (ttsText.isNotEmpty()) "$ttsPrefix $ttsText" else ttsPrefix
                            val tts = agentTts
                            val ttsJob = if (tts != null) {
                                CoroutineScope(Dispatchers.Default).launch {
                                    tts.speak(ttsFullText)
                                    tts.awaitSpeechDone()
                                    LogManager.logD(TAG, "[AGENT_TTS] Terminate TTS finished")
                                }
                            } else null
                            
                            // Check if experience summary is enabled
                            val experienceSummaryEnabled = com.example.offlineai.ConfigManager.getBoolean(
                                context,
                                com.example.offlineai.ConfigManager.KEY_AGENT_EXPERIENCE_SUMMARY,
                                false
                            )
                            
                            if (experienceSummaryEnabled && success) {
                                LogManager.logI(TAG, "[AGENT_EXP] Experience summary enabled, executing summary step")
                                delay(STEP_DELAY_MS)
                                
                                // Bring OfflineAI to foreground before summary
                                try {
                                    val intent = android.content.Intent(context, com.example.offlineai.MainActivity::class.java)
                                    intent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK or android.content.Intent.FLAG_ACTIVITY_SINGLE_TOP)
                                    context.startActivity(intent)
                                    LogManager.logI(TAG, "[AGENT_EXP] Brought OfflineAI to foreground")
                                    delay(500)
                                } catch (e: Exception) {
                                    LogManager.logE(TAG, "[AGENT_EXP] Failed to bring OfflineAI to foreground", e)
                                }
                                
                                // Experience summary uses currentContext (model-maintained memory) + original RAG recall
                                val taskHistory = "任务目标: $taskGoal\n\n最新上下文记忆:\n$currentContext"
                                if (taskHistory.isNotEmpty()) {
                                    val agentKbRecalled = cachedAgentKbContext  // Full RAG recall for KB dedup
                                    val summaryPrompt = AgentAccessibilityService.getInstance()?.buildExperienceSummaryPromptFromHistory(
                                        taskHistory, agentKbRecalled)
                                    if (!summaryPrompt.isNullOrEmpty()) {
                                        AgentAccessibilityService.getInstance()?.isExperienceSummaryStep = true
                                        val summaryResponse = try {
                                            modelInferenceCallback(
                                                summaryPrompt,
                                                null,  // No screenshot needed for experience summary
                                                emptyList()
                                            )
                                        } finally {
                                            AgentAccessibilityService.getInstance()?.isExperienceSummaryStep = false
                                        }
                                        LogManager.logI(TAG, "[AGENT_EXP] Summary generated, length: ${summaryResponse.length}")
                                        AgentAccessibilityService.getInstance()?.onExperienceSummaryGenerated(summaryResponse)
                                        LogManager.logI(TAG, "[AGENT_EXP] Waiting for user to save or cancel experience summary")
                                        // Wait for TTS to finish (it was running concurrently with experience summary)
                                        ttsJob?.join()
                                        LogManager.logD(TAG, "[AGENT_TTS] TTS join completed (with experience summary)")
                                        break
                                    }
                                }
                            }
                            
                            // Only call onTaskCompleted if experience summary was not generated
                            // Wait for TTS to finish before declaring task complete
                            ttsJob?.join()
                            LogManager.logD(TAG, "[AGENT_TTS] TTS join completed (no experience summary)")
                            callback?.onTaskCompleted(success, "Task terminated: ${response.action.status.value}")
                            break
                        }
                        is AgentAction.AskUser -> {
                            LogManager.logI(TAG, "[AGENT][ASK_USER] Showing AskUser UI: ${response.action.text}")
                            
                            // TTS: announce the question/instruction (fire-and-forget, synced with UI display)
                            val askPrefix = context.getString(com.example.offlineai.R.string.agent_tts_ask_user_prefix)
                            agentTts?.speak("$askPrefix ${response.action.text}")
                            
                            // Update lastAction so next loop iteration also skips screenshot
                            lastAction = response.action
                            
                            // Suspend and wait for user input via floating window FIRST,
                            // then record result with actual user reply so lastStepResult is correct
                            val userResponse = callback?.onAskUser(response.action.text) ?: ""
                            LogManager.logI(TAG, "[AGENT][ASK_USER] User responded (${userResponse.length} chars)")
                            
                            // Build result message that includes the user's actual input
                            val askResultMsg = if (userResponse.isNotEmpty())
                                "AskUser completed. User replied: $userResponse"
                            else
                                "AskUser completed. User confirmed (no text input)"
                            
                            // Save AskUser step in memory with the actual user reply as result
                            val askUserResult = TrajectoryStep(
                                stepIndex = memory.getStepCount(),
                                screenshot = null,
                                action = response.action,
                                executionResult = ExecutionResult(true, askResultMsg),
                                rawModelOutput = ""
                            )
                            memory.addStep(askUserResult)
                            // onStepCompleted now fires AFTER user replied → lastStepResult contains the reply
                            callback?.onStepCompleted(stepIndex, response.action, askUserResult.executionResult)
                            
                            // No delay needed - user already spent time on input
                            stepIndex++
                            continue
                        }
                        else -> {
                            // Update lastAction BEFORE execution for next step's screenshot decision
                            lastAction = response.action
                            
                            // Execute the action
                            val result = executor.execute(response.action)
                            
                            // Get coordinate error if any
                            val coordError = ActionParser.getLastCoordinateError()
                            ActionParser.clearCoordinateError()
                            
                            // Save complete model output for history
                            val rawOutput = modelOutput.trim()
                            
                            val step = TrajectoryStep(
                                stepIndex = memory.getStepCount(),
                                screenshot = screenshot,
                                action = response.action,
                                executionResult = result,
                                coordinateError = coordError,
                                rawModelOutput = rawOutput
                            )
                            memory.addStep(step)
                            
                            callback?.onStepCompleted(stepIndex, response.action, result)
                            
                            if (!result.success) {
                                LogManager.logE(TAG, "Action failed: ${result.message}")
                                // Continue anyway, let model decide what to do
                            }
                        }
                    }
                    
                    // Wait for UI to stabilize
                    delay(STEP_DELAY_MS)
                    stepIndex++
                }
                
                if (stepIndex >= MAX_STEPS) {
                    LogManager.logW(TAG, "Reached maximum steps limit")
                    callback?.onTaskCompleted(false, "Reached maximum steps limit")
                }
                
            } catch (e: Exception) {
                LogManager.logE(TAG, "Agent execution error", e)
                e.printStackTrace()
                callback?.onError("Execution error: ${e.message}")
            } finally {
                isRunning = false
                cachedAgentKbContext = ""
                currentContext = ""
                AgentAccessibilityService.getInstance()?.currentContext = ""
                // Deactivate accessibility service
                AgentAccessibilityService.getInstance()?.setAgentActive(false)
                LogManager.logI(TAG, "Agent task completed")
            }
        }
    }
    
    /**
     * Stop agent execution
     */
    fun stop() {
        LogManager.logI(TAG, "Stopping agent execution")
        shouldStop = true
        currentJob?.cancel()
        isRunning = false
        cachedAgentKbContext = ""
        currentContext = ""
        AgentAccessibilityService.getInstance()?.currentContext = ""
        // Deactivate accessibility service
        AgentAccessibilityService.getInstance()?.setAgentActive(false)
    }
    
    /**
     * Check if agent is currently running (single source of truth for execution state)
     */
    fun isRunning(): Boolean = isRunning
    
    /**
     * Get current task status
     */
    fun getTaskStatus(): String {
        return if (isRunning) {
            "执行中 (${memory.getStepCount()} 步)"
        } else {
            memory.getTaskStatus()
        }
    }
    
    /**
     * Get execution history
     */
    fun getHistory(): List<TrajectoryStep> {
        return memory.getAllSteps()
    }
    
    /**
     * Clear execution history
     */
    fun clearHistory() {
        memory.clear()
    }
    
    /**
     * Execute a data_memory action: set/get/delete/list/clear on the task-scoped KV store.
     * Returns ExecutionResult so the result can be injected as lastStepResult for the next step.
     */
    private fun executeDataMemory(action: AgentAction.DataMemory): ExecutionResult {
        return when (action.operation) {
            "set" -> {
                val key = action.key ?: return ExecutionResult(false, "[DATA_MEMORY] set: missing key")
                val value = action.value ?: return ExecutionResult(false, "[DATA_MEMORY] set: missing value")
                dataMemory[key] = value
                LogManager.logI(TAG, "[DATA_MEMORY] set key='$key' (${value.length} chars)")
                AgentAccessibilityService.getInstance()?.updateDataMemoryKeys(dataMemory.keys.toList())
                ExecutionResult(true, "data_memory: stored '$key' (${value.length} chars)")
            }
            "get" -> {
                val key = action.key ?: return ExecutionResult(false, "[DATA_MEMORY] get: missing key")
                val value = dataMemory[key]
                if (value != null) {
                    LogManager.logI(TAG, "[DATA_MEMORY] get key='$key' -> ${value.length} chars")
                    ExecutionResult(true, value, returnData = value)
                } else {
                    LogManager.logW(TAG, "[DATA_MEMORY] get key='$key' not found")
                    ExecutionResult(false, "data_memory: key '$key' not found")
                }
            }
            "delete" -> {
                val key = action.key ?: return ExecutionResult(false, "[DATA_MEMORY] delete: missing key")
                dataMemory.remove(key)
                AgentAccessibilityService.getInstance()?.updateDataMemoryKeys(dataMemory.keys.toList())
                LogManager.logI(TAG, "[DATA_MEMORY] delete key='$key'")
                ExecutionResult(true, "data_memory: deleted '$key'")
            }
            "list" -> {
                val keys = dataMemory.keys.joinToString(", ").ifEmpty { "(empty)" }
                LogManager.logI(TAG, "[DATA_MEMORY] list -> $keys")
                ExecutionResult(true, "data_memory keys: $keys", returnData = keys)
            }
            "clear" -> {
                dataMemory.clear()
                AgentAccessibilityService.getInstance()?.updateDataMemoryKeys(emptyList())
                LogManager.logI(TAG, "[DATA_MEMORY] cleared all")
                ExecutionResult(true, "data_memory: cleared all entries")
            }
            else -> ExecutionResult(false, "[DATA_MEMORY] unknown operation: ${action.operation}")
        }
    }
    
    /**
     * Expand {{key}} placeholders in text using dataMemory values.
     * Used before outputting terminate text so the final report contains actual content.
     */
    private fun expandPlaceholders(text: String): String {
        if (text.isEmpty() || dataMemory.isEmpty()) return text
        val pattern = Regex("""\{\{(\w+)\}\}""")
        val expanded = pattern.replace(text) { matchResult ->
            val key = matchResult.groupValues[1]
            dataMemory[key] ?: "[Missing: $key]"
        }
        if (expanded != text) {
            LogManager.logI(TAG, "[DATA_MEMORY] Expanded placeholders in terminate text (${text.length} -> ${expanded.length} chars)")
        }
        return expanded
    }

    /**
     * Release resources
     */
    fun release() {
        stop()
        screenshotCapture.release()
        LogManager.logI(TAG, "Agent engine released")
    }
}
