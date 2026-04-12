package com.example.offlineai.agent.core

import android.content.Context
import android.graphics.Bitmap
import com.example.offlineai.LogManager
import com.example.offlineai.ConfigManager
import com.example.offlineai.agent.AgentTtsHelper
import com.example.offlineai.agent.SkillCatalog
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
        private const val DEFAULT_MAX_STEPS = 50 // Default maximum steps
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
    private val contextFactHistory = mutableListOf<String>()
    private var currentContextText: String = ""
    
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
        AgentAccessibilityService.getInstance()?.updateTaskBrief("")

        // Scan skills directory and cache catalog for Step 0 injection
        SkillCatalog.scan(context)
        AgentAccessibilityService.getInstance()?.updateSkillCatalog(SkillCatalog.getCatalogText())

        // Ensure agent_workspace directory exists and pass path to service
        val workspacePath = ConfigManager.ensureAgentWorkspace(context)
        AgentAccessibilityService.getInstance()?.updateAgentWorkspacePath(workspacePath)
        LogManager.logI(TAG, "[AGENT_INIT] Workspace: $workspacePath")
        
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
        contextFactHistory.clear()
        currentContextText = ""
        
        // Pass current context to AgentAccessibilityService for step prompt assembly
        AgentAccessibilityService.getInstance()?.currentContext = currentContext
        
        LogManager.logI(TAG, "Starting agent task: $taskGoal")
        
        currentJob = launch {
            try {
                var stepIndex = 0
                var parseRetryCount = 0
                var lastAction: AgentAction? = null
                var consecutivePureSteps = 0  // Track stuck pure context/data_memory loops
                var lastFailureSignature: String? = null
                var sameFailureCount = 0
                
                // Auto-press Home before first screenshot so model starts from Home screen
                val homeResult = AgentAccessibilityService.getInstance()?.pressHome() ?: false
                LogManager.logI(TAG, "[AGENT_INIT] Auto-press Home before first step: ${if (homeResult) "success" else "failed"}")
                if (homeResult) {
                    delay(1000) // Wait for Home screen to fully render
                }
                
                // Read configurable max steps (0 = unlimited)
                val configuredMaxSteps = ConfigManager.getInt(context, ConfigManager.KEY_AGENT_MAX_STEPS, DEFAULT_MAX_STEPS)
                val maxSteps = if (configuredMaxSteps <= 0) Int.MAX_VALUE else configuredMaxSteps
                LogManager.logI(TAG, "[AGENT] Max steps configured: $configuredMaxSteps (effective: ${if (maxSteps == Int.MAX_VALUE) "unlimited" else maxSteps.toString()})")
                
                while (stepIndex < maxSteps && !shouldStop) {
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
                        val parseDetail = com.example.offlineai.agent.parser.ActionParser.getLastParseError()
                        val parseFeedback = if (parseDetail.isNullOrBlank()) {
                            errorHint
                        } else {
                            "$errorHint Parse detail: $parseDetail"
                        }
                        
                        // Add error feedback to memory for next retry
                        val step = TrajectoryStep(
                            stepIndex = memory.getStepCount(),
                            screenshot = screenshot,
                            action = AgentAction.Wait,
                            executionResult = ExecutionResult(
                                success = false,
                                message = parseFeedback,
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
                        appendFact(contextAction.fact)
                        currentContextText = normalizeContextText(contextAction.text)
                        currentContext = composeContextPayload()
                        LogManager.logI(
                            TAG,
                            "[CONTEXT] Updated: facts=${contextFactHistory.size}, text=${currentContextText.length} chars"
                        )
                        // Update AgentAccessibilityService's context for next step
                        AgentAccessibilityService.getInstance()?.currentContext = currentContext
                    } else {
                        // Missing context action: reuse previous context + warning (soft mode, don't abort)
                        LogManager.logW(TAG, "[CONTEXT] Missing context action at step $stepIndex, reusing previous context: ${currentContext.take(50)}")
                    }
                    
                    // ========== UNIFIED ACTION PIPELINE ==========
                    // Dedup: remove context, keep last occurrence of each action type, sort by last position
                    val allNonContext = actions.filter { it !is AgentAction.Context }
                    val seen = mutableSetOf<String>()
                    val dedupedActions = allNonContext.reversed().filter { action ->
                        val typeKey = action::class.simpleName ?: action.javaClass.name
                        seen.add(typeKey)
                    }.reversed()
                    LogManager.logI(TAG, "[AGENT][STEP_${stepIndex + 1}] Deduped actions: ${dedupedActions.map { it::class.simpleName }}")

                    val hasUiAction = dedupedActions.any { it !is AgentAction.DataMemory }
                    if (!hasUiAction) {
                        consecutivePureSteps++
                        LogManager.logI(TAG, "[AGENT][STEP_${stepIndex + 1}] Pure context/data_memory step (consecutive=$consecutivePureSteps)")
                    } else {
                        consecutivePureSteps = 0
                    }

                    callback?.onStepStarted(stepIndex)
                    var terminateTriggered = false
                    var askUserTriggered = false
                    val stepResultLines = mutableListOf<String>()

                    for ((actionIndex, action) in dedupedActions.withIndex()) {
                        when (action) {
                            is AgentAction.DataMemory -> {
                                val dmResult = executeDataMemory(action)
                                val desc = formatActionDesc(action)
                                val resultStr = formatResultStr(dmResult, action)
                                stepResultLines.add("$desc -> $resultStr")
                                LogManager.logI(TAG, "[EXEC] $desc -> ${if (dmResult.success) "OK" else "FAIL"}")
                            }
                            is AgentAction.Terminate -> {
                                val success = action.status == AgentAction.Terminate.Status.SUCCESS
                                val expandedText = expandPlaceholders(action.text)
                                val expandedAction = action.copy(text = expandedText)

                                lastAction = expandedAction

                                val terminateStep = TrajectoryStep(
                                    stepIndex = memory.getStepCount(),
                                    screenshot = screenshot,
                                    action = expandedAction,
                                    executionResult = ExecutionResult(success = success, message = "Task terminated: ${expandedAction.status.value}"),
                                    coordinateError = null,
                                    rawModelOutput = modelOutput.trim()
                                )
                                memory.addStep(terminateStep)
                                callback?.onStepCompleted(stepIndex, expandedAction, terminateStep.executionResult)

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

                                val experienceSummaryEnabled = com.example.offlineai.ConfigManager.getBoolean(
                                    context,
                                    com.example.offlineai.ConfigManager.KEY_AGENT_EXPERIENCE_SUMMARY,
                                    false
                                )

                                if (experienceSummaryEnabled && success) {
                                    LogManager.logI(TAG, "[AGENT_EXP] Experience summary enabled, executing summary step")
                                    delay(STEP_DELAY_MS)

                                    try {
                                        val intent = android.content.Intent(context, com.example.offlineai.MainActivity::class.java)
                                        intent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK or android.content.Intent.FLAG_ACTIVITY_SINGLE_TOP)
                                        context.startActivity(intent)
                                        LogManager.logI(TAG, "[AGENT_EXP] Brought OfflineAI to foreground")
                                        delay(500)
                                    } catch (e: Exception) {
                                        LogManager.logE(TAG, "[AGENT_EXP] Failed to bring OfflineAI to foreground", e)
                                    }

                                    val stepHistory = AgentAccessibilityService.getInstance()?.buildTaskHistoryForSummary(memory.getAllSteps()) ?: ""
                                    val taskHistory = "任务目标: $taskGoal\n\n操作历史:\n$stepHistory\n\n最新上下文记忆:\n$currentContext"
                                    if (taskHistory.isNotEmpty()) {
                                        val agentKbRecalled = cachedAgentKbContext
                                        val summaryPrompt = AgentAccessibilityService.getInstance()?.buildExperienceSummaryPromptFromHistory(
                                            taskHistory, agentKbRecalled)
                                        if (!summaryPrompt.isNullOrEmpty()) {
                                            AgentAccessibilityService.getInstance()?.isExperienceSummaryStep = true
                                            val summaryResponse = try {
                                                modelInferenceCallback(
                                                    summaryPrompt,
                                                    null,
                                                    emptyList()
                                                )
                                            } finally {
                                                AgentAccessibilityService.getInstance()?.isExperienceSummaryStep = false
                                            }
                                            LogManager.logI(TAG, "[AGENT_EXP] Summary generated, length: ${summaryResponse.length}")
                                            AgentAccessibilityService.getInstance()?.onExperienceSummaryGenerated(summaryResponse)
                                            LogManager.logI(TAG, "[AGENT_EXP] Waiting for user to save or cancel experience summary")
                                            ttsJob?.join()
                                            LogManager.logD(TAG, "[AGENT_TTS] TTS join completed (with experience summary)")
                                            terminateTriggered = true
                                            break
                                        }
                                    }
                                }

                                ttsJob?.join()
                                LogManager.logD(TAG, "[AGENT_TTS] TTS join completed (no experience summary)")
                                callback?.onTaskCompleted(success, "Task terminated: ${action.status.value}")
                                terminateTriggered = true
                                break
                            }
                            is AgentAction.AskUser -> {
                                LogManager.logI(TAG, "[AGENT][ASK_USER] Showing AskUser UI: ${action.text}")
                                val askPrefix = context.getString(com.example.offlineai.R.string.agent_tts_ask_user_prefix)
                                agentTts?.speak("$askPrefix ${action.text}")

                                lastAction = action

                                val userResponse = callback?.onAskUser(action.text) ?: ""
                                LogManager.logI(TAG, "[AGENT][ASK_USER] User responded (${userResponse.length} chars)")

                                val askResultMsg = if (userResponse.isNotEmpty())
                                    "AskUser completed. User replied: $userResponse"
                                else
                                    "AskUser completed. User confirmed (no text input)"

                                val askUserResult = TrajectoryStep(
                                    stepIndex = memory.getStepCount(),
                                    screenshot = null,
                                    action = action,
                                    executionResult = ExecutionResult(true, askResultMsg),
                                    rawModelOutput = ""
                                )
                                memory.addStep(askUserResult)
                                callback?.onStepCompleted(stepIndex, action, askUserResult.executionResult)

                                stepIndex++
                                askUserTriggered = true
                                break
                            }
                            else -> {
                                lastAction = action

                                val result = executor.execute(action)
                                // After python_status succeeds, sync stdout to data_memory so subsequent dm get can find it
                                if (action is AgentAction.PythonStatus && result.success) {
                                    val pythonOutputs = executor.getPythonOutputSnapshot()
                                    if (pythonOutputs.isNotEmpty()) {
                                        for ((key, value) in pythonOutputs) {
                                            dataMemory[key] = value
                                        }
                                        AgentAccessibilityService.getInstance()?.updateDataMemoryKeys(dataMemory.keys.toList())
                                        LogManager.logI(TAG, "[PYTHON_STATUS] Synced ${pythonOutputs.size} stdout entries to data_memory")
                                    }
                                }
                                val coordError = ActionParser.getLastCoordinateError()
                                ActionParser.clearCoordinateError()

                                val step = TrajectoryStep(
                                    stepIndex = memory.getStepCount(),
                                    screenshot = screenshot,
                                    action = action,
                                    executionResult = result,
                                    coordinateError = coordError,
                                    rawModelOutput = modelOutput.trim()
                                )
                                memory.addStep(step)
                                callback?.onStepCompleted(stepIndex, action, result)

                                // Collect formatted result line
                                val desc = formatActionDesc(action)
                                val resultStr = formatResultStr(result, action)
                                stepResultLines.add("$desc -> $resultStr")
                                LogManager.logI(TAG, "[EXEC] $desc -> ${if (result.success) "OK" else "FAIL"}")

                                if (!result.success) {
                                    val failureSignature = buildFailureSignature(action, result)
                                    if (failureSignature == lastFailureSignature) {
                                        sameFailureCount++
                                    } else {
                                        lastFailureSignature = failureSignature
                                        sameFailureCount = 1
                                    }
                                    if (sameFailureCount >= 2) {
                                        val correctionHint = buildRealtimeCorrectionHint(action, result, sameFailureCount)
                                        stepResultLines.add("⚠️ $correctionHint")
                                        LogManager.logW(TAG, "[AGENT][REALTIME_CORRECTION] $correctionHint")
                                    }
                                } else {
                                    lastFailureSignature = null
                                    sameFailureCount = 0
                                }

                                if (actionIndex < dedupedActions.lastIndex) {
                                    delay(STEP_DELAY_MS)
                                }
                            }
                        }
                    }

                    // Assemble lastStepResult from all collected results
                    if (!terminateTriggered && !askUserTriggered && stepResultLines.isNotEmpty()) {
                        val header = "Previous step executed:"
                        val body = stepResultLines.joinToString("\n")
                        val warningMsg = if (consecutivePureSteps >= 2) {
                            val realKeys = dataMemory.keys.joinToString(", ").ifEmpty { "(empty)" }
                            LogManager.logW(TAG, "[AGENT][PURE_LOOP] Consecutive pure steps=$consecutivePureSteps")
                            "\n⚠️ WARNING: ${consecutivePureSteps} consecutive steps with only data_memory/context, no UI action. " +
                            "Data Memory keys: [$realKeys]. Execute a real action or terminate now."
                        } else ""
                        AgentAccessibilityService.getInstance()?.lastStepResult = "$header\n$body$warningMsg"
                        LogManager.logI(TAG, "[LAST_STEP] ${stepResultLines.size} action result(s) injected")
                    }

                    // Record pure step in trajectory if no UI action
                    if (!hasUiAction && !terminateTriggered && !askUserTriggered) {
                        val dmSummary = dedupedActions.joinToString("; ") { formatActionDesc(it) }
                        val pureStep = TrajectoryStep(
                            stepIndex = memory.getStepCount(),
                            screenshot = screenshot,
                            action = AgentAction.Wait,
                            executionResult = ExecutionResult(true, "context+data_memory step: $dmSummary"),
                            rawModelOutput = modelOutput.trim()
                        )
                        memory.addStep(pureStep)
                        callback?.onStepCompleted(stepIndex, AgentAction.Wait, pureStep.executionResult)
                    }

                    if (terminateTriggered) {
                        break
                    }
                    if (askUserTriggered) {
                        continue
                    }

                    // Wait for UI to stabilize
                    delay(STEP_DELAY_MS)
                    stepIndex++
                }
                
                if (stepIndex >= maxSteps) {
                    LogManager.logW(TAG, "Reached maximum steps limit ($maxSteps)")
                    callback?.onTaskCompleted(false, "Reached maximum steps limit ($maxSteps)")
                }
                
            } catch (e: Exception) {
                LogManager.logE(TAG, "Agent execution error", e)
                e.printStackTrace()
                callback?.onError("Execution error: ${e.message}")
            } finally {
                isRunning = false
                cachedAgentKbContext = ""
                currentContext = ""
                contextFactHistory.clear()
                currentContextText = ""
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
        contextFactHistory.clear()
        currentContextText = ""
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
                val key = action.key ?: return ExecutionResult(false, "[DATA_MEMORY] set: missing key",
                    returnData = "{\"status\":\"FAIL\",\"message\":\"missing key\"}")
                val value = action.value ?: return ExecutionResult(false, "[DATA_MEMORY] set: missing value",
                    returnData = "{\"status\":\"FAIL\",\"message\":\"missing value\"}")
                dataMemory[key] = value
                LogManager.logI(TAG, "[DATA_MEMORY] set key='$key' (${value.length} chars)")
                AgentAccessibilityService.getInstance()?.updateDataMemoryKeys(dataMemory.keys.toList())
                if (key == "taskBrief") {
                    val normalizedBrief = normalizeTaskBrief(value)
                    if (normalizedBrief != null) {
                        AgentAccessibilityService.getInstance()?.updateTaskBrief(normalizedBrief)
                        LogManager.logI(TAG, "[TASK_BRIEF] Stored and injected taskBrief text (${normalizedBrief.length} chars)")
                        return ExecutionResult(true, "data_memory set taskBrief",
                            returnData = "{\"status\":\"OK\",\"key\":\"taskBrief\",\"chars\":${normalizedBrief.length}}")
                    }
                    return ExecutionResult(false, "data_memory: taskBrief text is empty",
                        returnData = "{\"status\":\"FAIL\",\"key\":\"taskBrief\",\"message\":\"empty value\"}")
                }
                ExecutionResult(true, "data_memory set '$key'",
                    returnData = "{\"status\":\"OK\",\"key\":\"${key.replace("\"", "\\\"")}\",\"chars\":${value.length}}")
            }
            "get" -> {
                val key = action.key ?: return ExecutionResult(false, "[DATA_MEMORY] get: missing key",
                    returnData = "{\"status\":\"FAIL\",\"message\":\"missing key\"}")
                val value = dataMemory[key]
                if (value != null) {
                    LogManager.logI(TAG, "[DATA_MEMORY] get key='$key' -> ${value.length} chars")
                    val escapedValue = value.take(2000).replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "\\r").replace("\t", "\\t")
                    ExecutionResult(true, "data_memory get '$key'",
                        returnData = "{\"status\":\"OK\",\"key\":\"${key.replace("\"", "\\\"")}\",\"chars\":${value.length},\"value\":\"$escapedValue\"}")
                } else {
                    LogManager.logW(TAG, "[DATA_MEMORY] get key='$key' not found")
                    ExecutionResult(false, "data_memory: key '$key' not found",
                        returnData = "{\"status\":\"FAIL\",\"key\":\"${key.replace("\"", "\\\"")}\",\"message\":\"key not found\"}")
                }
            }
            "delete" -> {
                val key = action.key ?: return ExecutionResult(false, "[DATA_MEMORY] delete: missing key",
                    returnData = "{\"status\":\"FAIL\",\"message\":\"missing key\"}")
                dataMemory.remove(key)
                AgentAccessibilityService.getInstance()?.updateDataMemoryKeys(dataMemory.keys.toList())
                LogManager.logI(TAG, "[DATA_MEMORY] delete key='$key'")
                ExecutionResult(true, "data_memory delete '$key'",
                    returnData = "{\"status\":\"OK\",\"key\":\"${key.replace("\"", "\\\"")}\"}")
            }
            "list" -> {
                val keys = dataMemory.keys.joinToString(", ").ifEmpty { "(empty)" }
                LogManager.logI(TAG, "[DATA_MEMORY] list -> $keys")
                ExecutionResult(true, "data_memory list",
                    returnData = "{\"status\":\"OK\",\"keys\":\"${keys.replace("\"", "\\\"")}\"}")
            }
            "clear" -> {
                dataMemory.clear()
                AgentAccessibilityService.getInstance()?.updateDataMemoryKeys(emptyList())
                LogManager.logI(TAG, "[DATA_MEMORY] cleared all")
                ExecutionResult(true, "data_memory clear",
                    returnData = "{\"status\":\"OK\",\"message\":\"all entries cleared\"}")
            }
            else -> ExecutionResult(false, "[DATA_MEMORY] unknown operation: ${action.operation}",
                returnData = "{\"status\":\"FAIL\",\"message\":\"unknown operation: ${action.operation}\"}")
        }
    }

    private fun normalizeTaskBrief(value: String): String? {
        val trimmed = value.trim()
        if (trimmed.isEmpty()) return null
        return trimmed
    }

    private fun appendFact(fact: String) {
        val normalized = fact.trim()
        if (normalized.isEmpty()) return
        // Split by lines and deduplicate each line individually
        // This prevents duplicates when LLM returns overlapping multi-line facts across steps
        val lines = normalized.lines().map { it.trim() }.filter { it.isNotEmpty() }
        for (line in lines) {
            if (!contextFactHistory.contains(line)) {
                contextFactHistory.add(line)
            }
        }
    }

    private fun normalizeContextText(text: String): String {
        val normalized = text.trim()
        if (normalized.length > 1200) {
            LogManager.logW(TAG, "[CONTEXT] text too long (${normalized.length} chars), truncating to 1200")
            return normalized.take(1200)
        }
        return normalized
    }

    private fun composeContextPayload(): String {
        val sections = mutableListOf<String>()

        if (contextFactHistory.isNotEmpty()) {
            sections.add("fact:\n${contextFactHistory.joinToString("\n")}")
        }

        if (currentContextText.isNotEmpty()) {
            sections.add("text:\n$currentContextText")
        }

        if (sections.isEmpty()) {
            return ""
        }
        return sections.joinToString("\n\n")
    }

    private fun buildFailureSignature(action: AgentAction, result: ExecutionResult): String {
        val actionJson = try {
            action.toJson().toString()
        } catch (_: Exception) {
            action.javaClass.simpleName ?: "UnknownAction"
        }
        val errorText = result.error?.message ?: result.message
        return "${action.javaClass.simpleName}|$actionJson|$errorText"
    }

    private fun buildRealtimeCorrectionHint(action: AgentAction, result: ExecutionResult, repeatCount: Int): String {
        val errorText = (result.error?.message ?: result.message).lowercase()
        val genericHint = "Realtime correction: same action failed $repeatCount times. Do not repeat with identical parameters. Change strategy, ask_user, or terminate with reason."

        return when (action) {
            is AgentAction.Grep,
            is AgentAction.ReadFile,
            is AgentAction.ReadLines,
            is AgentAction.EditLines,
            is AgentAction.WriteFile,
            is AgentAction.CreateFile -> {
                if (errorText.contains("file not found") || errorText.contains("not found")) {
                    "Realtime correction: file operation failed $repeatCount times. create_file for new files. read_file/read_lines/edit_lines/grep require existing file. Use search_files or list_dir to find correct path first."
                } else {
                    genericHint
                }
            }
            else -> genericHint
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
     * Format action description for result injection (compact, with key params).
     * Long params (>50 chars) are truncated with "...".
     */
    private fun formatActionDesc(action: AgentAction): String = when (action) {
        is AgentAction.Click -> "click([${action.x},${action.y}])"
        is AgentAction.LongPress -> "long_press([${action.x},${action.y}])"
        is AgentAction.DoubleClick -> "double_click([${action.x},${action.y}])"
        is AgentAction.Type -> "type(\"${action.text.ellipsis(50)}\")"
        is AgentAction.Swipe -> "swipe(${action.direction}" +
            if (action.x != null) ",[${action.x},${action.y}])" else ")"
        is AgentAction.Drag -> "drag([${action.startX},${action.startY}]->[${action.endX},${action.endY}])"
        is AgentAction.Open -> "open(\"${action.appName}\")"
        is AgentAction.SystemButton -> "system_button(${action.button})"
        is AgentAction.Wait -> "wait"
        is AgentAction.GetAppList -> "get_app_list"
        is AgentAction.WebOpen -> "web_open(\"${action.url.ellipsis(80)}\")"
        is AgentAction.WebGetContent -> "web_get_content"
        is AgentAction.WebExecuteJs -> "web_execute_js(\"${action.script.ellipsis(60)}\")"
        is AgentAction.Terminate -> "terminate(${action.status.value})"
        is AgentAction.AskUser -> "ask_user(\"${action.text.ellipsis(40)}\")"
        is AgentAction.KbInsert -> "kb_insert"
        is AgentAction.KbDelete -> "kb_delete(${action.ids})"
        is AgentAction.Context -> "context"
        is AgentAction.DataMemory -> "data_memory(${action.operation}, key=${action.key})"
        is AgentAction.CreateFile -> "create_file(${action.path})"
        is AgentAction.ReadFile -> "read_file(${action.path})"
        is AgentAction.WriteFile -> "write_file(${action.path})"
        is AgentAction.ReadLines -> "read_lines(${action.path}, L${action.startLine}-${action.endLine})"
        is AgentAction.EditLines -> "edit_lines(${action.path}, L${action.startLine}-${action.endLine})"
        is AgentAction.Grep -> "grep(${action.path}, \"${action.keyword.ellipsis(30)}\")"
        is AgentAction.RenameFile -> "rename_file(${action.oldPath}, ${action.newPath})"
        is AgentAction.DeleteFile -> "delete_file(${action.path})"
        is AgentAction.CopyFile -> "copy_file(${action.src}, ${action.dst})"
        is AgentAction.ListDir -> "list_dir(${action.path})"
        is AgentAction.Mkdir -> "mkdir(${action.path})"
        is AgentAction.SearchFiles -> "search_files(${action.path}, \"${action.keyword.ellipsis(30)}\")"
        is AgentAction.PythonRun -> "python_run(\"${(action.code ?: action.file ?: "").ellipsis(50)}\")"
        is AgentAction.PythonStatus -> "python_status"
        is AgentAction.PythonKill -> "python_kill"
        is AgentAction.ShowMedia -> "show_media(${action.path})"
    }

    /**
     * Format ExecutionResult into a unified JSON string for injection.
     * If returnData exists (already JSON from executor), use it directly.
     * Otherwise construct a simple JSON: {"status":"OK/FAIL","message":"..."}.
     * This ensures the model always sees consistent JSON after "->".
     */
    private fun formatResultStr(result: ExecutionResult, action: AgentAction? = null): String {
        // read_file / read_lines / web_get_content carry large payloads; allow up to 20000 chars
        val maxReturnData = when (action) {
            is AgentAction.ReadFile, is AgentAction.ReadLines, is AgentAction.WebGetContent -> 20000
            else -> 2000
        }
        return if (!result.returnData.isNullOrEmpty()) {
            result.returnData.take(maxReturnData)
        } else {
            val status = if (result.success) "OK" else "FAIL"
            val msg = result.message.take(200).replace("\"", "\\\"").replace("\n", "\\n")
            "{\"status\":\"$status\",\"message\":\"$msg\"}"
        }
    }

    private fun String.ellipsis(maxLen: Int): String =
        if (length <= maxLen) this else take(maxLen) + "..."

    /**
     * Release resources
     */
    fun release() {
        stop()
        screenshotCapture.release()
        LogManager.logI(TAG, "Agent engine released")
    }
}
