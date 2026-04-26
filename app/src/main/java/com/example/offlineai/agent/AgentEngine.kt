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
        // Hard-stop when the model emits this many consecutive context/data_memory-only steps
        // (no real tool call). Empirical: 10/50 empty steps in one ark-code-latest task → cap at 5.
        private const val PURE_STEP_TERMINATE_THRESHOLD = 5
        // After this many cache hits on the same path, escalate from soft-OK hint to a hard FAIL
        // so the model can no longer interpret the response as a successful read. Empirical:
        // ark-code-latest re-issued the same ReadFile 19 consecutive times when given success=true.
        private const val CACHE_HIT_HARD_FAIL_THRESHOLD = 3
        // Hard-terminate when the same UI/IO action class fails with the same error category
        // this many consecutive times. Prevents the "micro-tweak coordinates" loop that escapes
        // the previous fine-grained signature (which compared full action JSON + raw error text).
        // Empirical: browser address bar type loop in offline-ai run, 10+ retries with shifting
        // (x,y) and identical "No active window" error.
        private const val SAME_FAILURE_TERMINATE_THRESHOLD = 3
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

    // Read cache (per-task): path -> step_index. Suppresses repeated ReadFile/ListDir on the same path
    // (root cause of "model loops re-reading SKILL.md 13 times" pattern observed in long ReAct).
    // Invalidated when CreateFile/WriteFile/EditLines/DeleteFile/RenameFile touches the same path.
    private val readFileHistory = mutableMapOf<String, Int>()
    private val listDirHistory = mutableMapOf<String, Int>()
    // Read cache hit counter: path -> hit_count. After CACHE_HIT_HARD_FAIL_THRESHOLD hits the cache
    // returns success=false (instead of OK with hint) so the model is forced to switch strategy.
    // Observed: model ignored success=true [ALREADY_READ_AT_STEP_N] hints and re-issued the same
    // ReadFile/ReadLines 19 consecutive times.
    private val readCacheHitCount = mutableMapOf<String, Int>()
    
    private var currentJob: Job? = null
    
    // Pending terminate output: recorded when Terminate action is received,
    // written to conversation.md in finally block after all steps (including experience summary) complete
    private var pendingTerminateText: String = ""
    private var pendingTerminateFiles: List<String> = emptyList()
    private var pendingTerminateSuccess: Boolean = false
    @Volatile
    private var hasPendingTerminate = false
    // User clicked stop button - signals finally block to close <agent> tag even without Terminate action
    @Volatile
    private var userStopped = false
    @Volatile
    private var reachedMaxSteps = false
    
    /**
     * Callback interface for agent execution events
     */
    interface AgentCallback {
        fun onStepStarted(stepIndex: Int)
        fun onStepCompleted(stepIndex: Int, action: AgentAction, result: ExecutionResult)
        fun onTaskCompleted(success: Boolean, message: String)
        fun onError(error: String)
        // Suspend execution and show AskUser UI; resume with user input text (may be empty)
        // url: optional URL to show WebView for user login/interaction
        suspend fun onAskUser(question: String, url: String? = null): String
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
        userStopped = false
        memory.clear()
        memory.setTaskGoal(taskGoal)
        dataMemory.clear()  // Clear task-scoped data memory for every new task
        readFileHistory.clear()  // Reset per-task read cache
        listDirHistory.clear()
        readCacheHitCount.clear()
        // [IMPROVEMENT 4+5+6] Clear executor-side per-task caches (web_open
        // dedup, web_get_content TTL cache). Without this the next task could
        // observe a [WEB_GET_REUSED_CACHE] hit from the previous task's URL.
        executor.resetTaskCaches()
        AgentAccessibilityService.getInstance()?.updateDataMemoryKeys(emptyList())
        AgentAccessibilityService.getInstance()?.updateTaskBrief("")

        // Reset floating window output size to small at task start
        floatingWindow?.resetOutputSize()

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
                            action = AgentAction.Wait(),
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

                    // If this step contains a Terminate action, record terminate details
                    // for deferred writing. The actual </agent> tag and terminate message
                    // will be written in the finally block after ALL steps complete
                    // (including experience summary if enabled).
                    val hasTerminate = dedupedActions.any { it is AgentAction.Terminate }
                    if (hasTerminate) {
                        val terminateAction = dedupedActions.filterIsInstance<AgentAction.Terminate>().firstOrNull()
                        pendingTerminateText = terminateAction?.let { expandPlaceholders(it.text).trim() } ?: ""
                        pendingTerminateFiles = terminateAction?.files ?: emptyList()
                        pendingTerminateSuccess = terminateAction?.status == AgentAction.Terminate.Status.SUCCESS
                        hasPendingTerminate = true
                        LogManager.logI(TAG, "[AGENT_BLOCK] Recorded pending terminate (text=${pendingTerminateText.length}, files=${pendingTerminateFiles.size})")
                    }

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

                                // NOTE: </agent> and terminate message are deferred to the finally block,
                                // written after all steps (including experience summary) complete.

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
                                // onTaskCompleted is deferred to finally block after </agent> is written
                                terminateTriggered = true
                                break
                            }
                            is AgentAction.AskUser -> {
                                LogManager.logI(TAG, "[AGENT][ASK_USER] Showing AskUser UI: ${action.text}, url=${action.url ?: "none"}")
                                val askPrefix = context.getString(com.example.offlineai.R.string.agent_tts_ask_user_prefix)
                                agentTts?.speak("$askPrefix ${action.text}")

                                lastAction = action

                                val userResponse = callback?.onAskUser(action.text, action.url) ?: ""
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

                                // Inject user reply into lastStepResult so next step knows what user answered
                                val askDesc = formatActionDesc(action)
                                AgentAccessibilityService.getInstance()?.lastStepResult =
                                    "Previous step executed:\n$askDesc -> [OK] $askResultMsg"
                                LogManager.logI(TAG, "[ASK_USER] Injected user reply into lastStepResult: $askResultMsg")

                                stepIndex++
                                askUserTriggered = true
                                break
                            }
                            is AgentAction.ShowOutput -> {
                                floatingWindow?.showOutput(action.text, action.size)
                                val desc = formatActionDesc(action)
                                stepResultLines.add("$desc -> [OK] Displayed to user")
                                LogManager.logI(TAG, "[SHOW_OUTPUT] size=${action.size}, text=${action.text.length} chars")
                            }
                            else -> {
                                lastAction = action

                                // [READ_CACHE] Intercept repeated ReadFile/ListDir on the same path.
                                // The model very often re-reads SKILL.md / re-lists scripts dir even though
                                // the content is unchanged in this task. Returning a synthetic OK result with
                                // an [ALREADY_DONE_AT_STEP_N] hint forces the model to reuse prior content.
                                val cachedResult = checkReadCacheHit(action)
                                val result = cachedResult ?: executor.execute(action)
                                if (cachedResult != null) {
                                    LogManager.logI(TAG, "[READ_CACHE] Suppressed duplicate ${formatActionDesc(action)}")
                                } else if (result.success) {
                                    recordReadCache(action, stepIndex)
                                }
                                // Any write/edit/delete/rename invalidates relevant cache entries.
                                invalidateReadCacheOnWrite(action)
                                // Note: python output is now returned directly via python_status returnData.
                                // No data_memory coupling (removed with python action refactor).
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
                                    // Hard-terminate after N consecutive same-category failures.
                                    // The signature is now error-category based (not coordinate-sensitive),
                                    // so micro-tweaking x,y will no longer reset the counter.
                                    if (sameFailureCount >= SAME_FAILURE_TERMINATE_THRESHOLD) {
                                        LogManager.logW(TAG, "[AGENT][SAME_FAILURE_LOOP] Auto-terminating after $sameFailureCount consecutive same-category failures (signature=$failureSignature)")
                                        pendingTerminateText = "[AUTO_TERMINATE] Task forcibly stopped after $sameFailureCount consecutive failures of the same action category. " +
                                            "Last action: ${formatActionDesc(action)}. Last error: ${result.error?.message ?: result.message}. " +
                                            "The model kept retrying the same action class with only superficial parameter changes (e.g. ±20 px coordinate shifts) instead of switching strategy. " +
                                            "Please review the failure cause and retry with a different approach (different action, ask_user, or refined task)."
                                        pendingTerminateSuccess = false
                                        hasPendingTerminate = true
                                        terminateTriggered = true
                                        break
                                    }
                                } else {
                                    lastFailureSignature = null
                                    sameFailureCount = 0

                                    // [IMPROVEMENT 6] Auto-stash web_get_content payload into
                                    // dataMemory so the LLM can reference it via
                                    // `data_memory get web_content_last` 3+ steps later, instead
                                    // of re-running web_get_content (observed: 6 redundant fetches
                                    // of the same 7089-char page in flash_dram log). The value is
                                    // truncated to 6000 chars for prompt budget; the full content
                                    // remains accessible via the immediately-next step's
                                    // returnData injection.
                                    if (action is AgentAction.WebGetContent &&
                                        !result.returnData.isNullOrEmpty()) {
                                        val capped = if (result.returnData.length > 6000)
                                            result.returnData.take(6000) +
                                                "\n[TRUNCATED: ${result.returnData.length - 6000} more chars in web_get_content returnData]"
                                        else result.returnData
                                        dataMemory["web_content_last"] = capped
                                        AgentAccessibilityService.getInstance()
                                            ?.updateDataMemoryKeys(dataMemory.keys.toList())
                                        LogManager.logI(TAG,
                                            "[AUTO_DATAMEMORY] web_content_last set " +
                                            "(${capped.length} chars from web_get_content)")
                                    }
                                }

                                if (actionIndex < dedupedActions.lastIndex) {
                                    delay(STEP_DELAY_MS)
                                }
                            }
                        }
                    }

                    // Update floating window output: show_output takes priority; fallback to context (fact+text)
                    if (!terminateTriggered && !askUserTriggered) {
                        val hasShowOutput = dedupedActions.any { it is AgentAction.ShowOutput }
                        if (!hasShowOutput) {
                            // Use currentContextText first; fall back to full currentContext (includes fact)
                            val displayText = currentContextText.ifEmpty { currentContext }
                            if (displayText.isNotEmpty()) {
                                floatingWindow?.updateContextText(displayText)
                                LogManager.logD(TAG, "[FLOATING_OUTPUT] Fallback context display (${displayText.length} chars)")
                            }
                        }
                    }

                    // [PURE_LOOP] Auto-terminate after too many consecutive context/data_memory-only steps.
                    // The previous soft-warning was being ignored by some models (observed: 10/50 empty steps
                    // in one task). Now we hard-stop at PURE_STEP_TERMINATE_THRESHOLD.
                    if (!terminateTriggered && !askUserTriggered && consecutivePureSteps >= PURE_STEP_TERMINATE_THRESHOLD) {
                        val realKeys = dataMemory.keys.joinToString(", ").ifEmpty { "(empty)" }
                        LogManager.logW(TAG, "[AGENT][PURE_LOOP] Auto-terminating after $consecutivePureSteps consecutive pure steps")
                        pendingTerminateText = "[AUTO_TERMINATE] Task forcibly stopped after $consecutivePureSteps consecutive steps " +
                            "with only context/data_memory and no real tool call. " +
                            "Stored data_memory keys: [$realKeys]. " +
                            "The model is stuck self-reflecting; please review the task and retry with a clearer goal or a different model."
                        pendingTerminateSuccess = false
                        hasPendingTerminate = true
                        terminateTriggered = true
                    }

                    // Assemble lastStepResult from all collected results
                    if (!terminateTriggered && !askUserTriggered && stepResultLines.isNotEmpty()) {
                        val header = "Previous step executed:"
                        val body = stepResultLines.joinToString("\n")
                        val warningMsg = if (consecutivePureSteps >= 2) {
                            val realKeys = dataMemory.keys.joinToString(", ").ifEmpty { "(empty)" }
                            val remaining = (PURE_STEP_TERMINATE_THRESHOLD - consecutivePureSteps).coerceAtLeast(1)
                            LogManager.logW(TAG, "[AGENT][PURE_LOOP] Consecutive pure steps=$consecutivePureSteps (terminate in $remaining)")
                            "\n[FATAL_NO_ACTION] You produced $consecutivePureSteps consecutive steps with ONLY context/data_memory " +
                            "and NO real tool call (python / read_file / create_file / web_get_content / etc.). " +
                            "Your fact and data_memory entries are already saved—stop accumulating notes. " +
                            "Stored data_memory keys: [$realKeys]. " +
                            "Emit a real tool call NOW. Task will auto-terminate in $remaining more empty step(s)."
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
                            action = AgentAction.Wait(),
                            executionResult = ExecutionResult(true, "context+data_memory step: $dmSummary"),
                            rawModelOutput = modelOutput.trim()
                        )
                        memory.addStep(pureStep)
                        callback?.onStepCompleted(stepIndex, AgentAction.Wait(), pureStep.executionResult)
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
                    reachedMaxSteps = true
                    // Note: onTaskCompleted is called unconditionally in finally block below
                }
                
            } catch (e: CancellationException) {
                // Coroutine cancelled (user stop) - let finally block handle </agent> writing
                LogManager.logI(TAG, "Agent execution cancelled (user stop)")
            } catch (e: Exception) {
                LogManager.logE(TAG, "Agent execution error", e)
                e.printStackTrace()
                callback?.onError("Execution error: ${e.message}")
            } finally {
                // Always close any unclosed <agent> tag, regardless of termination reason.
                // Reason classification: normal-terminate (Terminate action), user-stop, error-abort (parse fail / exception).
                val reason = when {
                    hasPendingTerminate -> "normal-terminate"
                    userStopped -> "user-stop"
                    reachedMaxSteps -> "max-steps"
                    else -> "error-abort"
                }
                try {
                    val chatFolderPath = com.example.offlineai.ConfigManager.getString(
                        context,
                        com.example.offlineai.ConfigManager.KEY_CURRENT_CHAT_FOLDER,
                        ""
                    )
                    if (chatFolderPath.isNotEmpty()) {
                        val conversationFile = java.io.File(chatFolderPath, "conversation.md")
                        if (conversationFile.exists()) {
                            val content = conversationFile.readText()
                            val lastOpen = content.lastIndexOf("<agent>")
                            val lastClose = content.lastIndexOf("</agent>")
                            val hasUnclosedAgent = lastOpen >= 0 && lastOpen > lastClose
                            if (hasUnclosedAgent) {
                                java.io.FileWriter(conversationFile, true).use { writer ->
                                    writer.write("\n</agent>\n")
                                    // Write terminate text/files only for normal termination
                                    val hasContent = pendingTerminateText.isNotEmpty() || pendingTerminateFiles.isNotEmpty()
                                    if (hasContent) {
                                        val sb = StringBuilder()
                                        sb.append("\n")
                                        if (pendingTerminateText.isNotEmpty()) {
                                            sb.append(pendingTerminateText).append("\n")
                                        }
                                        for (filePath in pendingTerminateFiles) {
                                            val file = java.io.File(filePath)
                                            if (!file.exists()) {
                                                LogManager.logW(TAG, "[AGENT_BLOCK] Terminate file not found: $filePath")
                                                continue
                                            }
                                            val ext = file.extension
                                            val typeLabel = com.example.offlineai.agent.model.classifyFileType(ext)
                                            val fileName = file.name
                                            val md = when (typeLabel) {
                                                "image" -> "\n![]($filePath)\n"
                                                "audio" -> "\n\uD83C\uDFA4 [$fileName]($filePath)\n"
                                                else -> "\n\uD83D\uDCCE [$fileName]($filePath)\n"
                                            }
                                            sb.append(md)
                                            LogManager.logI(TAG, "[AGENT_BLOCK] Terminate file attachment: $typeLabel $filePath")
                                        }
                                        writer.write(sb.toString())
                                        LogManager.logI(TAG, "[AGENT_BLOCK] Written terminate message (text=${pendingTerminateText.length}, files=${pendingTerminateFiles.size})")
                                    }
                                }
                                LogManager.logI(TAG, "[AGENT_BLOCK] Written </agent> in finally block ($reason)")
                            } else {
                                LogManager.logI(TAG, "[AGENT_BLOCK] No unclosed <agent> tag found, skipping ($reason)")
                            }
                        }
                    }
                } catch (e: Exception) {
                    LogManager.logE(TAG, "[AGENT_BLOCK] Failed to write </agent> in finally block", e)
                }
                // Always notify callback so UI reloads history after agent stops
                val terminateSuccess = if (hasPendingTerminate) pendingTerminateSuccess else false
                val terminateMsg = when {
                    hasPendingTerminate -> "Task terminated: ${if (pendingTerminateSuccess) "success" else "failure"}"
                    userStopped -> "User stopped"
                    reachedMaxSteps -> "Reached maximum steps limit"
                    else -> "Agent stopped (error)"
                }
                callback?.onTaskCompleted(terminateSuccess, terminateMsg)
                // Clear pending terminate state
                hasPendingTerminate = false
                pendingTerminateText = ""
                pendingTerminateFiles = emptyList()
                pendingTerminateSuccess = false
                userStopped = false
                reachedMaxSteps = false
                
                isRunning = false
                cachedAgentKbContext = ""
                currentContext = ""
                contextFactHistory.clear()
                currentContextText = ""
                readFileHistory.clear()
                listDirHistory.clear()
                readCacheHitCount.clear()
                AgentAccessibilityService.getInstance()?.currentContext = ""
                // Deactivate accessibility service
                AgentAccessibilityService.getInstance()?.setAgentActive(false)
                LogManager.logI(TAG, "Agent task completed")
            }
        }
    }
    
    /**
     * Stop agent execution
     * NOTE: Do NOT clear hasPendingTerminate here. The finally block in executeTask
     * will handle writing </agent> tag and terminate message after LLM output completes.
     */
    fun stop() {
        LogManager.logI(TAG, "Stopping agent execution")
        shouldStop = true
        userStopped = true
        currentJob?.cancel()
        // Must set isRunning=false here - if old task is stuck in blocking call (latch.await)
        // or coroutine cancel races with new task start, finally block may not execute in time.
        // The finally block's </agent> writing uses hasUnclosedAgent check so no duplication.
        isRunning = false
        // Do NOT clear pending terminate state - let finally block write </agent>
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

    /**
     * Build a coarse failure signature so that "same action type + same error category" collapses
     * into one bucket regardless of cosmetic parameter changes (coordinates, timestamps, etc.).
     *
     * Earlier version used full action JSON + raw error text, which let the model escape the
     * loop counter by nudging x/y by a few pixels. We now key on:
     *   - action class name (e.g. Type, Click, ReadFile)
     *   - error category extracted from the error message (e.g. no_active_window,
     *     no_editable_node, file_not_found, type_failed, click_failed, generic)
     */
    private fun buildFailureSignature(action: AgentAction, result: ExecutionResult): String {
        val errorText = (result.error?.message ?: result.message).lowercase()
        val category = when {
            errorText.contains("no_active_window") || errorText.contains("no active window") -> "no_active_window"
            errorText.contains("action_set_text failed") -> "set_text_failed"
            errorText.contains("no editable") || errorText.contains("no node covers") -> "no_editable_node"
            errorText.contains("failed to type") -> "type_failed"
            errorText.contains("failed to click") -> "click_failed"
            errorText.contains("failed to long press") -> "long_press_failed"
            errorText.contains("failed to swipe") -> "swipe_failed"
            errorText.contains("file not found") || errorText.contains("not found") -> "file_not_found"
            errorText.contains("permission") -> "permission_denied"
            errorText.contains("timeout") -> "timeout"
            errorText.contains("parse") -> "parse_error"
            else -> "generic"
        }
        return "${action.javaClass.simpleName}|$category"
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
     * [READ_CACHE] If this action is a ReadFile / ReadLines / ListDir on a path that already
     * succeeded in this same task, return a synthetic result instead of re-reading. Otherwise null.
     *
     * Escalation: first 1-2 hits return success=true with [ALREADY_READ_AT_STEP_N] hint;
     * from the 3rd hit (CACHE_HIT_HARD_FAIL_THRESHOLD) onward we return success=false with
     * [STUCK_LOOP] so the model treats it as an error and is forced to switch strategy.
     */
    private fun checkReadCacheHit(action: AgentAction): ExecutionResult? {
        val (path, kind, prev) = when (action) {
            is AgentAction.ReadFile -> Triple(action.path, "read_file", readFileHistory[action.path])
            is AgentAction.ReadLines -> Triple(action.path, "read_lines", readFileHistory[action.path])
            is AgentAction.ListDir -> Triple(action.path, "list_dir", listDirHistory[action.path])
            else -> return null
        }
        if (prev == null) return null
        val hits = (readCacheHitCount[path] ?: 0) + 1
        readCacheHitCount[path] = hits
        val safePath = path.replace("\"", "\\\"")
        return if (hits >= CACHE_HIT_HARD_FAIL_THRESHOLD) {
            // Hard fail: model must change strategy (use grep / different path / proceed to next phase).
            ExecutionResult(
                success = false,
                message = "[STUCK_LOOP] You have re-issued $kind('$path') $hits times in this task with no change. " +
                    "The content was already returned at step $prev. " +
                    "STOP re-reading. Required next action: either (a) act on the prior content (e.g., python helper, create_file with new content), " +
                    "(b) use grep to extract specific lines, or (c) emit terminate if the task is done. " +
                    "Re-issuing this read will keep failing.",
                returnData = "{\"status\":\"FAIL\",\"reason\":\"stuck_loop\",\"path\":\"$safePath\",\"prev_step\":$prev,\"hits\":$hits}"
            )
        } else {
            ExecutionResult(
                success = true,
                message = "[ALREADY_READ_AT_STEP_${prev}] $kind('$path') was successfully done earlier in this task. " +
                    "Reuse the content from step $prev's RESULT instead of re-reading. " +
                    "If the file may have changed since then, perform a write/edit action to invalidate this cache. " +
                    "(cache_hits=$hits; ${CACHE_HIT_HARD_FAIL_THRESHOLD - hits} more hits will be reported as FAIL)",
                returnData = "{\"status\":\"OK\",\"cached\":true,\"prev_step\":$prev,\"hits\":$hits,\"path\":\"$safePath\"}"
            )
        }
    }

    /**
     * [READ_CACHE] Record a successful ReadFile / ReadLines / ListDir into the per-task cache.
     */
    private fun recordReadCache(action: AgentAction, stepIndex: Int) {
        when (action) {
            is AgentAction.ReadFile -> readFileHistory[action.path] = stepIndex
            is AgentAction.ReadLines -> readFileHistory[action.path] = stepIndex
            is AgentAction.ListDir -> listDirHistory[action.path] = stepIndex
            else -> {}
        }
    }

    /**
     * [READ_CACHE] Invalidate cached entries when a write / delete / rename touches the same path.
     * Also clear the parent directory's list cache (since contents changed).
     */
    private fun invalidateReadCacheOnWrite(action: AgentAction) {
        fun parentOf(p: String): String? = try { java.io.File(p).parent } catch (_: Throwable) { null }
        fun removeBoth(p: String) {
            readFileHistory.remove(p)
            readCacheHitCount.remove(p)
        }
        fun removeListBoth(p: String) {
            listDirHistory.remove(p)
            readCacheHitCount.remove(p)
        }
        when (action) {
            is AgentAction.CreateFile -> {
                removeBoth(action.path)
                parentOf(action.path)?.let { removeListBoth(it) }
            }
            is AgentAction.WriteFile -> {
                removeBoth(action.path)
                parentOf(action.path)?.let { removeListBoth(it) }
            }
            is AgentAction.EditLines -> removeBoth(action.path)
            is AgentAction.DeleteFile -> {
                removeBoth(action.path)
                parentOf(action.path)?.let { removeListBoth(it) }
            }
            is AgentAction.RenameFile -> {
                removeBoth(action.oldPath)
                removeBoth(action.newPath)
                parentOf(action.oldPath)?.let { removeListBoth(it) }
                parentOf(action.newPath)?.let { removeListBoth(it) }
            }
            is AgentAction.CopyFile -> {
                removeBoth(action.dst)
                parentOf(action.dst)?.let { removeListBoth(it) }
            }
            is AgentAction.Mkdir -> parentOf(action.path)?.let { removeListBoth(it) }
            else -> {}
        }
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
        is AgentAction.Wait -> "wait(${action.seconds}s)"
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
        is AgentAction.Python -> {
            val preview = action.argv.joinToString(" ")
            val tag = if (action.timeoutSec == 0) " [async]" else if (action.timeoutSec != 30) " [t=${action.timeoutSec}s]" else ""
            "python(\"${preview.ellipsis(50)}\"$tag)"
        }
        is AgentAction.PythonStatus -> "python_status"
        is AgentAction.PythonKill -> "python_kill"
        is AgentAction.ShowOutput -> "show_output(size=${action.size}, text=\"${action.text.ellipsis(40)}\")"
        is AgentAction.ScheduleGet -> "schedule_get"
        is AgentAction.ScheduleSet -> {
            val fields = mutableListOf<String>()
            action.enabled?.let { fields.add("enabled=$it") }
            action.oneShot?.let { fields.add("one_shot=$it") }
            action.weekdays?.let { fields.add("weekdays=$it") }
            action.start?.let { fields.add("start=$it") }
            action.end?.let { fields.add("end=$it") }
            action.intervalMin?.let { fields.add("interval_min=$it") }
            action.agentPreset?.let { fields.add("agent_preset=$it") }
            action.prompt?.let { fields.add("prompt=(${it.length} chars)") }
            "schedule_set(task_id=${action.taskId}${if (fields.isNotEmpty()) ", " + fields.joinToString(", ") else ""})"
        }
    }

    /**
     * Format ExecutionResult into a unified JSON string for injection.
     * If returnData exists (already JSON from executor), use it directly.
     * Otherwise construct a simple JSON: {"status":"OK/FAIL","message":"..."}.
     * This ensures the model always sees consistent JSON after "->".
     */
    private fun formatResultStr(result: ExecutionResult, action: AgentAction? = null): String {
        // Large-payload actions (file IO / web / python output) need a higher cap so that
        // critical trailing content (e.g. NEXT_STEP / TASK instructions from skills) is preserved.
        // NOTE: python_status/python_kill historically fell into the default 2000 bucket,
        // which silently truncated the tail of Python stdout (e.g. stockpicker's NEXT_STEP block)
        // and caused the model to hallucinate follow-up actions. Keep them aligned with read_file.
        val maxReturnData = when (action) {
            is AgentAction.ReadFile, is AgentAction.ReadLines, is AgentAction.WebGetContent,
            is AgentAction.Python, is AgentAction.PythonStatus, is AgentAction.PythonKill -> 20000
            else -> 2000
        }
        return if (!result.returnData.isNullOrEmpty()) {
            val total = result.returnData.length
            if (total > maxReturnData) {
                // Explicit truncation marker so the model can detect the cut and fetch the full
                // payload via read_file(log_file) (for python) or read_lines (for files).
                result.returnData.take(maxReturnData) +
                    "\n[TRUNCATED: shown $maxReturnData of $total chars; use read_file(log_file) for full output]"
            } else {
                result.returnData
            }
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
