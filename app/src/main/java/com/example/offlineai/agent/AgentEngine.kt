package com.example.offlineai.agent.core

import android.content.Context
import android.graphics.Bitmap
import com.example.offlineai.LogManager
import com.example.offlineai.agent.executor.ActionExecutor
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
    
    private val executor = ActionExecutor(context)
    private val memory = TrajectoryMemory(maxHistorySteps = 3)
    private var screenshotCapture = ScreenshotCapture(context)
    private var floatingWindow: com.example.offlineai.agent.ui.AgentFloatingWindow? = null
    
    @Volatile
    private var isRunning = false
    
    @Volatile
    private var shouldStop = false
    
    private var currentJob: Job? = null
    
    /**
     * Callback interface for agent execution events
     */
    interface AgentCallback {
        fun onStepStarted(stepIndex: Int, thinking: String)
        fun onStepCompleted(stepIndex: Int, thinking: String, action: AgentAction, result: ExecutionResult)
        fun onTaskCompleted(success: Boolean, message: String)
        fun onError(error: String)
        fun onAnswer(text: String)
        fun onAskUser(question: String, callback: (String) -> Unit)
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
            is AgentAction.Answer -> {
                callback?.onAnswer(response.action.text)
                return@withContext ExecutionResult(
                    success = true,
                    message = "Answer: ${response.action.text}"
                )
            }
            is AgentAction.AskUser -> {
                // This will be handled by callback
                return@withContext ExecutionResult(
                    success = true,
                    message = "Ask user: ${response.action.text}"
                )
            }
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
                
                // Extract action text from model output for Previous Steps
                // Try <tool_call> first, fallback to "Action:" line (Doubao UI-TARS format)
                val toolCallMatch = Regex("<tool_call>\\s*(.+?)\\s*</tool_call>", RegexOption.DOT_MATCHES_ALL)
                    .find(modelOutput)
                val actionLineMatch = Regex("(?m)^\\s*Action:\\s*(.+)$").find(modelOutput)
                val rawOutput = when {
                    toolCallMatch != null -> toolCallMatch.groupValues[1].trim()
                    actionLineMatch != null -> actionLineMatch.groupValues[0].trim()
                    else -> ""
                }
                
                // Store in memory
                val step = TrajectoryStep(
                    stepIndex = memory.getStepCount(),
                    screenshot = screenshot,
                    thinking = response.thinking,
                    action = response.action,
                    executionResult = result,
                    coordinateError = coordError,
                    rawModelOutput = rawOutput
                )
                memory.addStep(step)
                
                callback?.onStepCompleted(step.stepIndex, response.thinking, response.action, result)
                
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
        
        LogManager.logI(TAG, "Starting agent task: $taskGoal")
        
        currentJob = launch {
            try {
                var stepIndex = 0
                var parseRetryCount = 0
                
                // Auto-press Home before first screenshot so model starts from Home screen
                val homeResult = AgentAccessibilityService.getInstance()?.pressHome() ?: false
                LogManager.logI(TAG, "[AGENT_INIT] Auto-press Home before first step: ${if (homeResult) "success" else "failed"}")
                if (homeResult) {
                    delay(1000) // Wait for Home screen to fully render
                }
                
                while (stepIndex < MAX_STEPS && !shouldStop) {
                    // Capture screenshot (pass floatingWindow to hide it during capture)
                    val screenshot = screenshotCapture.captureScreen(floatingWindow)
                    if (screenshot == null) {
                        callback?.onError("Failed to capture screenshot")
                        break
                    }
                    
                    val screenshotForInference = screenshot
                    val modelOutput = modelInferenceCallback(
                        taskGoal,
                        screenshotForInference,
                        memory.getRecentSteps()
                    )
                    
                    // Parse response with dynamic format selection based on API URL and user settings
                    val response = ActionParser.parse(modelOutput, apiUrl, modelName, context)
                    if (response == null) {
                        parseRetryCount++
                        LogManager.logE(TAG, "Parse failed at step $stepIndex (retry $parseRetryCount/$MAX_PARSE_RETRIES)")
                        
                        if (parseRetryCount >= MAX_PARSE_RETRIES) {
                            LogManager.logE(TAG, "Max parse retries reached, terminating agent")
                            callback?.onError("Failed to parse model output after $MAX_PARSE_RETRIES retries")
                            break
                        }
                        
                        // Get format-specific error hint
                        val format = com.example.offlineai.agent.ActionFormatRegistry.getFormatForApi(apiUrl, modelName)
                        val errorHint = format.getErrorHint()
                        
                        // Add error feedback to memory for next retry
                        val step = TrajectoryStep(
                            stepIndex = memory.getStepCount(),
                            screenshot = screenshot,
                            thinking = "",
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
                    callback?.onStepStarted(stepIndex, response.thinking)
                    
                    // Handle special actions
                    when (response.action) {
                        is AgentAction.Terminate -> {
                            val success = response.action.status == AgentAction.Terminate.Status.SUCCESS
                            
                            // Extract <tool_call> for this Terminate step
                            val toolCallMatch = Regex("<tool_call>\\s*(.+?)\\s*</tool_call>", RegexOption.DOT_MATCHES_ALL)
                                .find(modelOutput)
                            val toolCallOnly = if (toolCallMatch != null) {
                                "<tool_call>\n${toolCallMatch.groupValues[1].trim()}\n</tool_call>"
                            } else {
                                ""
                            }
                            
                            // Store Terminate step in memory
                            val terminateStep = TrajectoryStep(
                                stepIndex = memory.getStepCount(),
                                screenshot = screenshot,
                                thinking = response.thinking,
                                action = response.action,
                                executionResult = ExecutionResult(success = success, message = "Task terminated: ${response.action.status.value}"),
                                coordinateError = null,
                                rawModelOutput = toolCallOnly
                            )
                            memory.addStep(terminateStep)
                            
                            callback?.onStepCompleted(stepIndex, response.thinking, response.action, terminateStep.executionResult)
                            
                            // Check if experience summary is enabled
                            val experienceSummaryEnabled = com.example.offlineai.ConfigManager.getBoolean(
                                context,
                                com.example.offlineai.ConfigManager.KEY_AGENT_EXPERIENCE_SUMMARY,
                                false
                            )
                            
                            if (experienceSummaryEnabled && success) {
                                LogManager.logI(TAG, "[AGENT_EXP] Experience summary enabled, executing summary step")
                                
                                // Execute one more step for experience summary
                                // This step reuses the same flow: screenshot → model → save to history
                                // But skips ActionParser and shows save button instead
                                delay(STEP_DELAY_MS)
                                
                                // First, bring OfflineAI to foreground
                                try {
                                    val intent = android.content.Intent(context, com.example.offlineai.MainActivity::class.java)
                                    intent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK or android.content.Intent.FLAG_ACTIVITY_SINGLE_TOP)
                                    context.startActivity(intent)
                                    LogManager.logI(TAG, "[AGENT_EXP] Brought OfflineAI to foreground")
                                    delay(500) // Wait for activity to come to foreground
                                } catch (e: Exception) {
                                    LogManager.logE(TAG, "[AGENT_EXP] Failed to bring OfflineAI to foreground", e)
                                }
                                
                                val summaryScreenshot = screenshotCapture.captureScreen(floatingWindow)
                                if (summaryScreenshot != null) {
                                    // Build experience summary prompt from conversation.md (includes model replies)
                                    val taskHistory = AgentAccessibilityService.getInstance()?.readTaskHistoryFromConversationMd()
                                    if (!taskHistory.isNullOrEmpty()) {
                                        val summaryPrompt = AgentAccessibilityService.getInstance()?.buildExperienceSummaryPromptFromHistory(taskHistory)
                                        if (!summaryPrompt.isNullOrEmpty()) {
                                            // Set flag for empty system prompt during experience summary
                                            AgentAccessibilityService.getInstance()?.isExperienceSummaryStep = true
                                            
                                            // Call model with summary prompt (reuse modelInferenceCallback)
                                            // This will automatically save to conversation.md and refresh ChatUI
                                            val summaryResponse = try {
                                                modelInferenceCallback(
                                                    summaryPrompt,  // Use summary prompt instead of taskGoal
                                                    summaryScreenshot,
                                                    emptyList()  // No history for summary step
                                                )
                                            } finally {
                                                // Always reset the flag
                                                AgentAccessibilityService.getInstance()?.isExperienceSummaryStep = false
                                            }
                                            
                                            LogManager.logI(TAG, "[AGENT_EXP] Summary generated, length: ${summaryResponse.length}")
                                            
                                            // Notify service to show save button (don't parse action)
                                            // Floating window will remain visible with save/cancel buttons
                                            AgentAccessibilityService.getInstance()?.onExperienceSummaryGenerated(summaryResponse)
                                            
                                            // Don't call onTaskCompleted - wait for user to save or cancel
                                            LogManager.logI(TAG, "[AGENT_EXP] Waiting for user to save or cancel experience summary")
                                            // Break without calling onTaskCompleted, floating window stays visible
                                            break
                                        }
                                    }
                                }
                            }
                            
                            // Only call onTaskCompleted if experience summary was not generated
                            callback?.onTaskCompleted(success, "Task terminated: ${response.action.status.value}")
                            break
                        }
                        is AgentAction.Answer -> {
                            callback?.onAnswer(response.action.text)
                            break
                        }
                        else -> {
                            // Execute the action
                            val result = executor.execute(response.action)
                            
                            // Get coordinate error if any
                            val coordError = ActionParser.getLastCoordinateError()
                            ActionParser.clearCoordinateError()
                            
                            // Extract action text from model output for Previous Steps
                            // Try <tool_call> first, fallback to "Action:" line (Doubao UI-TARS format)
                            val toolCallMatch = Regex("<tool_call>\\s*(.+?)\\s*</tool_call>", RegexOption.DOT_MATCHES_ALL)
                                .find(modelOutput)
                            val actionLineMatch = Regex("(?m)^\\s*Action:\\s*(.+)$").find(modelOutput)
                            val rawOutput = when {
                                toolCallMatch != null -> toolCallMatch.groupValues[1].trim()
                                actionLineMatch != null -> actionLineMatch.groupValues[0].trim()
                                else -> ""
                            }
                            
                            val step = TrajectoryStep(
                                stepIndex = memory.getStepCount(),
                                screenshot = screenshot,
                                thinking = response.thinking,
                                action = response.action,
                                executionResult = result,
                                coordinateError = coordError,
                                rawModelOutput = rawOutput
                            )
                            memory.addStep(step)
                            
                            callback?.onStepCompleted(stepIndex, response.thinking, response.action, result)
                            
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
     * Release resources
     */
    fun release() {
        stop()
        screenshotCapture.release()
        LogManager.logI(TAG, "Agent engine released")
    }
}
