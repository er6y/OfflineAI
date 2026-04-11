package com.example.offlineai.agent.service

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Path
import android.os.Build
import android.os.Bundle
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.core.app.NotificationCompat
import com.example.offlineai.AppConstants
import com.example.offlineai.ChatHistoryManager
import com.example.offlineai.ConfigManager
import com.example.offlineai.EmbeddingHandler
import com.example.offlineai.GraphStopwordsMatcher
import com.example.offlineai.HanLpNerHandler
import com.example.offlineai.KnowledgeGraphDatabase
import com.example.offlineai.LogManager
import com.example.offlineai.MainActivity
import com.example.offlineai.R
import com.example.offlineai.RagQaFragment
import com.example.offlineai.RagQueryManager
import com.example.offlineai.RuntimeConfigUtil
import com.example.offlineai.agent.ActionFormatRegistry
import com.example.offlineai.agent.AgentPrompts
import com.example.offlineai.agent.AgentTtsHelper
import com.example.offlineai.agent.AgentWebView
import com.example.offlineai.agent.core.AgentEngine
import com.example.offlineai.agent.model.AgentAction
import com.example.offlineai.agent.model.ExecutionResult
import com.example.offlineai.agent.model.TrajectoryStep
import com.example.offlineai.agent.ui.AgentFloatingWindow
import com.example.offlineai.agent.utils.AppNameMapper
import com.example.offlineai.ipc.InferenceClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Accessibility Service for Agent execution
 * Provides UI automation capabilities: click, type, swipe, etc.
 */
class AgentAccessibilityService : AccessibilityService() {
    
    companion object {
        private const val TAG = "AgentAccessibilityService"
        private const val NOTIFICATION_CHANNEL_ID = "agent_execution_channel"
        private const val NOTIFICATION_ID = 1001
        private const val AGENT_KB_NAME = "AgentKB"
        private const val AGENT_KB_TOP_K = 3
        
        @Volatile
        private var instance: AgentAccessibilityService? = null
        
        fun getInstance(): AgentAccessibilityService? = instance
        
        fun isServiceEnabled(): Boolean = instance != null
    }
    
    private var ragQueryManager: RagQueryManager? = null
    private var ragQaFragment: RagQaFragment? = null  // Direct Fragment reference for UI updates
    private var agentEngine: AgentEngine? = null
    internal var agentWebView: AgentWebView? = null
    private val serviceScope = CoroutineScope(Dispatchers.Main + Job())
    private var agentLoopJob: Job? = null
    internal var floatingWindow: AgentFloatingWindow? = null
    private var currentStep = 0
    private val maxSteps = 20
    private var cachedAppList: List<String>? = null  // Cache app list to avoid repeated queries
    private var savedTemperature: Float? = null  // Save original temperature before Agent starts
    private var agentTts: AgentTtsHelper? = null  // System TTS for Terminate/AskUser announcements
    
    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        initializeFloatingWindow()
        agentWebView = AgentWebView(applicationContext)
        createNotificationChannel()
        LogManager.logI(TAG, "Accessibility service connected")
    }
    
    private var isAgentActive = false
    private var isWaitingForExperienceSave = false  // Flag to prevent auto-hide when waiting for user to save experience
    
    // Current context: Agent's memory for next step (set by AgentEngine, updated after each step)
    @Volatile
    var currentContext: String = ""
    
    // Last step result: hardcoded fact injected into next prompt to prevent context amnesia
    // Format: "上一步: [action描述] → [结果]"
    @Volatile
    var lastStepResult: String = ""
    
    // Data Memory keys: lightweight index injected into prompt each step (values stay in AgentEngine)
    @Volatile
    private var dataMemoryKeys: List<String> = emptyList()

    // Task brief: structured plan generated once at task start and injected into follow-up prompts
    @Volatile
    private var taskBrief: String = ""
    
    fun updateDataMemoryKeys(keys: List<String>) {
        dataMemoryKeys = keys
    }

    fun updateTaskBrief(brief: String) {
        taskBrief = brief
    }
    
    fun setAgentActive(active: Boolean) {
        isAgentActive = active
        LogManager.logI(TAG, "Agent active state changed: $active")
    }
    
    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // Only process events when Agent is actively executing
        if (!isAgentActive) {
            return
        }
        
        // Monitor UI changes for action completion detection
        event?.let {
            LogManager.logD(TAG, "Accessibility event: ${it.eventType}, package: ${it.packageName}")
        }
    }
    
    override fun onInterrupt() {
        LogManager.logW(TAG, "Accessibility service interrupted")
    }
    
    override fun onDestroy() {
        super.onDestroy()
        isAgentActive = false
        stopAgentLoop()
        floatingWindow?.hide()
        floatingWindow = null
        agentWebView?.destroy()
        agentWebView = null
        instance = null
        LogManager.logI(TAG, "Accessibility service destroyed")
    }
    
    /**
     * Set RagQueryManager reference for Agent loop execution
     */
    fun setRagQueryManager(manager: RagQueryManager?) {
        this.ragQueryManager = manager
        LogManager.logI(TAG, "RagQueryManager reference set: ${manager != null}")
    }
    
    /**
     * Set RagQaFragment reference for UI updates
     * This is needed because querySync() temporarily replaces the callback in RagQueryManager
     */
    fun setRagQaFragment(fragment: RagQaFragment?) {
        this.ragQaFragment = fragment
        LogManager.logI(TAG, "[AGENT_UI] RagQaFragment reference set: ${fragment != null}, isAdded=${fragment?.isAdded}")
    }
    
    /**
     * Set AgentEngine reference for Agent loop execution
     */
    fun setAgentEngine(engine: AgentEngine?) {
        this.agentEngine = engine
        LogManager.logI(TAG, "AgentEngine reference set: ${engine != null}")
        
        // Set FloatingWindow reference for screenshot hiding
        if (engine != null) {
            engine.setFloatingWindow(floatingWindow)
            LogManager.logI(TAG, "FloatingWindow reference passed to AgentEngine")
        }
        
        // Set AgentEngine callback to save step history
        if (engine != null) {
            engine.setCallback(object : AgentEngine.AgentCallback {
                override fun onStepStarted(stepIndex: Int) {
                    // Silent
                }
                
                override fun onStepCompleted(stepIndex: Int, action: AgentAction, result: ExecutionResult) {
                    LogManager.logI(TAG, "[AGENT] Step $stepIndex: ${action.javaClass.simpleName} - ${if (result.success) "OK" else "FAIL"}")
                    saveStepToConversationMd(stepIndex, action, result)
                    // On terminate: show full expanded text in output area and lock it
                    if (action is AgentAction.Terminate) {
                        floatingWindow?.showTerminateResult(action.text)
                    }
                    // NOTE: lastStepResult is now assembled by AgentEngine after all actions are executed.
                    // This callback no longer writes lastStepResult to avoid partial overwrite issues.
                }
                
                override fun onTaskCompleted(success: Boolean, message: String) {
                    LogManager.logI(TAG, "[AGENT] Task completed: $success")
                    
                    // Experience summary is now handled inside AgentEngine.executeTask()
                    // When terminate is detected and summary is enabled, AgentEngine will:
                    // 1. Execute one more step with summary prompt
                    // 2. Call onExperienceSummaryGenerated() to show save button
                    // 3. Then call this callback
                    
                    // If no summary was generated (disabled or failed), just log
                    LogManager.logI(TAG, "[AGENT] Task completed, waiting for user action or timeout")
                }
                
                override fun onError(error: String) {
                    LogManager.logE(TAG, "[AGENT] Error: $error")
                }
                
                override suspend fun onAskUser(question: String): String {
                    LogManager.logI(TAG, "[AGENT] AskUser: showing input UI for question: $question")
                    return floatingWindow?.showAskUserInputAndWait(question) ?: ""
                }
            })
        }
    }
    
    /**
     * Start Agent autonomous loop execution
     */
    fun startAgentLoop(taskGoal: String) {
        if (ragQueryManager == null) {
            LogManager.logE(TAG, "Cannot start Agent loop: RagQueryManager is null")
            return
        }
        
        if (agentEngine == null) {
            LogManager.logE(TAG, "Cannot start Agent loop: AgentEngine is null")
            return
        }
        
        // Stop existing loop if any
        stopAgentLoop()
        
        LogManager.logI(TAG, "Starting Agent loop: $taskGoal")
        setAgentActive(true)
        currentStep = 0
        lastStepResult = ""  // Clear last step result for new task
        taskBrief = ""
        
        // Initialize Agent TTS if enabled
        val ttsEnabled = ConfigManager.isAgentTtsEnabled(applicationContext)
        agentTts = if (ttsEnabled) {
            LogManager.logI(TAG, "[AGENT_TTS] Initializing AgentTtsHelper")
            AgentTtsHelper(applicationContext)
        } else {
            null
        }
        agentEngine?.setAgentTts(agentTts)
        
        // Save original temperature and set lower temperature for Agent (reduce hallucination)
        val context = applicationContext
        savedTemperature = ConfigManager.getManualTemperature(context)
        ConfigManager.setManualTemperature(context, 0.2f)
        LogManager.logI(TAG, "Agent temperature: saved=$savedTemperature, set to 0.2 for accuracy")
        
        // Show floating window (non-blocking, failure won't stop Agent)
        try {
            floatingWindow?.show()
            floatingWindow?.updateTask(taskGoal)
            floatingWindow?.updateStep(0)
            floatingWindow?.updateStatus("Initializing...")
            
            // Set save experience button callback
            floatingWindow?.setOnSaveExperienceClickListener {
                saveExperienceToAgentKB()
            }
            
            LogManager.logI(TAG, "Floating window shown successfully")
        } catch (e: Exception) {
            LogManager.logE(TAG, "Failed to show floating window, but Agent will continue", e)
        }
        showNotification("Agent Executing", "Task: $taskGoal")
        
        agentLoopJob = serviceScope.launch {
            try {
                LogManager.logI(TAG, "Agent loop coroutine started, calling AgentEngine.executeTask()")
                
                // Get API URL and model name for dynamic format selection
                val appContext = applicationContext
                val apiUrl = ConfigManager.getAgentApiUrl(appContext)
                val model = ConfigManager.getAgentModelName(appContext)
                
                // Call AgentEngine.executeTask with model inference callback
                // RAG retrieval is handled by RagQueryManager's full pipeline via QueryRequest
                agentEngine?.executeTask(taskGoal, apiUrl, model) { instruction, screenshot, _ ->
                    floatingWindow?.updateStatus("Waiting for model...")
                    val result = callRagQueryManagerSync(instruction, screenshot)
                    
                    // Extract pure model output (remove <debug> tags for Previous Steps)
                    // <debug> is for UI display only, should NOT be sent to model as history
                    val pureModelOutput = result.replace(Regex("<debug>.*?</debug>", RegexOption.DOT_MATCHES_ALL), "").trim()
                    
                    // Update floating window with model output
                    // Skip during experience summary step to preserve the terminate result text
                    if (!isExperienceSummaryStep) {
                        try {
                            floatingWindow?.updateOutput(pureModelOutput)
                        } catch (e: Exception) {
                            LogManager.logW(TAG, "Failed to update floating window output: ${e.message}")
                        }
                    }
                    
                    pureModelOutput
                }
                
                LogManager.logI(TAG, "Agent loop completed")
                showNotification("Agent Completed", "Task finished")
                
                // Check if waiting for experience save
                // Keep floating window visible - user closes manually after reading the result
                if (isWaitingForExperienceSave) {
                    LogManager.logI(TAG, "[AGENT_EXP] Waiting for user to save experience, keeping floating window visible")
                } else {
                    LogManager.logI(TAG, "[AGENT] Task completed, floating window stays open for user to review")
                    floatingWindow?.updateStatus("Task completed")
                }
                
            } catch (e: Exception) {
                LogManager.logE(TAG, "Agent loop failed: ${e.message}", e)
                e.printStackTrace()
                try {
                    floatingWindow?.updateStatus("Error: ${e.message}")
                } catch (ex: Exception) {
                    LogManager.logW(TAG, "Failed to update floating window on error")
                }
                showNotification("Agent Error", "Error: ${e.message}")
                delay(3000)
                try {
                    floatingWindow?.hide()
                } catch (ex: Exception) {
                    LogManager.logW(TAG, "Failed to hide floating window on error")
                }
            } finally {
                // Window stays visible regardless - user closes it manually via Stop button.
                // Do NOT call stopAgentLoop() here: it hides the window, which races with
                // the show() call above and cuts off the terminate result display.
                // stopAgentLoop() is triggered by the user's Stop button click.
                if (isWaitingForExperienceSave) {
                    LogManager.logI(TAG, "[AGENT_EXP] Keeping floating window for experience save")
                } else {
                    LogManager.logI(TAG, "[AGENT] Task loop ended, floating window stays for user review")
                }
                // Restore temperature and deactivate agent state, but keep window open
                savedTemperature?.let { temp ->
                    ConfigManager.setManualTemperature(applicationContext, temp)
                    LogManager.logI(TAG, "Agent temperature restored to $temp in finally")
                    savedTemperature = null
                }
                isAgentActive = false
                agentTts?.shutdown()
                agentTts = null
                agentEngine?.setAgentTts(null)
            }
        }
    }
    
    /**
     * Stop Agent loop execution
     */
    fun stopAgentLoop() {
        LogManager.logI(TAG, "Agent loop stopped")
        agentLoopJob?.cancel()
        agentLoopJob = null
        isAgentActive = false
        cachedAppList = null  // Clear cache when agent stops
        agentTts?.shutdown()
        agentTts = null
        
        // If waiting for experience save, user clicked stop = cancel
        if (isWaitingForExperienceSave) {
            LogManager.logI(TAG, "[AGENT_EXP] User cancelled experience summary via stop button")
            isWaitingForExperienceSave = false
            experienceSummaryContent = ""
            floatingWindow?.hideSaveButton()
            // Fall through to normal cleanup below
        }
        
        // Normal stop: clear everything and hide window
        experienceSummaryContent = ""
        taskBrief = ""
        
        // Restore original temperature
        savedTemperature?.let { temp ->
            ConfigManager.setManualTemperature(applicationContext, temp)
            LogManager.logI(TAG, "Agent temperature restored to $temp")
            savedTemperature = null
        }
        
        floatingWindow?.hide()
        hideNotification()
    }
    
    /**
     * Call RagQueryManager synchronously for Agent inference
     * This is a blocking call that waits for model response
     */
    private suspend fun callRagQueryManagerSync(
        instruction: String,
        screenshot: Bitmap?
    ): String {
        val startTime = System.currentTimeMillis()
        LogManager.logI(TAG, "[AGENT] Step $currentStep: instruction.len=${instruction.length}, screenshot=${screenshot != null}")
        
        currentStep++
        try {
            floatingWindow?.updateStep(currentStep)
            floatingWindow?.updateStatus("Step $currentStep: Processing...")
        } catch (e: Exception) {
            LogManager.logW(TAG, "Failed to update floating window: ${e.message}")
        }
        
        return withContext(Dispatchers.IO) {
            try {
                val ragMgr = ragQueryManager ?: run {
                    LogManager.logE(TAG, "RagQueryManager reference is null")
                    return@withContext "<tool_call>{\"action\":\"terminate\",\"text\":\"Error: RagQueryManager not available\"}</tool_call>"
                }
                
                // Agent module builds its own QueryRequest with Agent-specific configuration
                val context = applicationContext
                val apiUrl = ConfigManager.getAgentApiUrl(context).ifEmpty { AppConstants.ApiUrl.LOCAL }
                val apiKey = ConfigManager.getApiKey(context, apiUrl)  // Get API Key for specific URL
                val model = ConfigManager.getAgentModelName(context)
                
                // Get installed launchable apps (use cache if available)
                val appNames = if (cachedAppList != null) {
                    cachedAppList!!
                } else {
                    val installedApps = AppNameMapper.getAllInstalledAppNames(context)
                    val names = installedApps.map { it.first }
                    LogManager.logI(TAG, "[AGENT] Found ${names.size} launchable apps")
                    cachedAppList = names
                    names
                }
                
                // Agent module manages its own prompt (dynamic format based on API URL)
                
                if (currentContext.isNotEmpty() && currentStep == 1) {
                    LogManager.logI(TAG, "[AGENT_CONTEXT] Initial context (RAG recall): ${currentContext.length} chars")
                }
                
                // Use unified system prompt
                val systemPrompt = if (isExperienceSummaryStep) {
                    LogManager.logI(TAG, "[AGENT_EXP] Using empty system prompt for experience summary")
                    ""
                } else {
                    AgentPrompts.getSystemPromptForApi(context, apiUrl, model, appNames)
                }
                
                // Log system prompt on first step (full content for debugging)
                if (currentStep == 1) {
                    LogManager.logI(TAG, "[AGENT][INIT] System Prompt length: ${systemPrompt.length}")
                    LogManager.logI(TAG, "[AGENT][INIT] System Prompt:\n$systemPrompt")
                }
                
                // Build user prompt with currentContext (currentStep already incremented, so use currentStep - 1 for 0-based index)
                val userPromptWithHistory = buildUserPromptWithHistory(instruction, currentStep - 1, screenshot != null)
                
                // Log user prompt for every step (replace image paths with placeholders)
                val userPromptForLog = userPromptWithHistory.replace(Regex("file://[^\\s)]+"), "[IMAGE:...]")
                LogManager.logI(TAG, "[AGENT][STEP_$currentStep] User Prompt length: ${userPromptWithHistory.length}")
                LogManager.logI(TAG, "[AGENT][STEP_$currentStep] User Prompt:\n$userPromptForLog")
                
                // Save screenshot to file and pass to model
                val screenshotPath = if (screenshot != null) {
                    try {
                        val chatFolder = ConfigManager.getString(
                            context,
                            ConfigManager.KEY_CURRENT_CHAT_FOLDER,
                            ""
                        )
                        if (chatFolder.isNotEmpty()) {
                            val timestamp = System.currentTimeMillis()
                            val fileName = "agent_step_${currentStep}_$timestamp.jpg"
                            val file = java.io.File(chatFolder, fileName)
                            
                            // Scale screenshot to fixed size for online API token savings
                            val targetW = ConfigManager.AGENT_SCREENSHOT_WIDTH
                            val targetH = ConfigManager.AGENT_SCREENSHOT_HEIGHT
                            val jpegQuality = ConfigManager.AGENT_SCREENSHOT_JPEG_QUALITY
                            val scaledBitmap = android.graphics.Bitmap.createScaledBitmap(screenshot, targetW, targetH, true)
                            
                            val outputStream = java.io.FileOutputStream(file)
                            // JPEG with quality=85 for balanced size/quality (saves ~80% vs full-res PNG)
                            scaledBitmap.compress(android.graphics.Bitmap.CompressFormat.JPEG, jpegQuality, outputStream)
                            outputStream.flush()
                            outputStream.close()
                            
                            // Recycle scaled bitmap if it's a new object (not the original)
                            if (scaledBitmap !== screenshot) {
                                scaledBitmap.recycle()
                            }
                            
                            LogManager.logI(TAG, "[AGENT_SCREENSHOT] Saved screenshot: ${file.absolutePath} (${targetW}x${targetH} JPEG q=$jpegQuality, size=${file.length()/1024}KB)")
                            file.absolutePath
                        } else {
                            LogManager.logW(TAG, "[AGENT_SCREENSHOT] Chat folder not set, screenshot not saved")
                            null
                        }
                    } catch (e: Exception) {
                        LogManager.logE(TAG, "[AGENT_SCREENSHOT] Failed to save screenshot", e)
                        null
                    }
                } else {
                    null
                }
                
                // Build QueryRequest for Agent with screenshot
                val imagePaths = if (screenshotPath != null) listOf(screenshotPath) else null
                
                // Save user message to conversation.md BEFORE calling model
                // Use the same userPromptWithHistory that is sent to model (single source of truth)
                try {
                    val chatFolderPath = ConfigManager.getString(
                        context,
                        ConfigManager.KEY_CURRENT_CHAT_FOLDER,
                        ""
                    )
                    
                    if (chatFolderPath.isNotEmpty()) {
                        val conversationFile = java.io.File(chatFolderPath, "conversation.md")
                        val markdown = StringBuilder()
                        
                        // Add separator if file already has content
                        if (conversationFile.exists() && conversationFile.length() > 0) {
                            markdown.append("\n\n<!-- MESSAGE_SEPARATOR -->\n\n")
                        }
                        
                        // Header: ## 用户 (timestamp)
                        val sdf = java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault())
                        val timeStr = sdf.format(java.util.Date())
                        markdown.append("## 用户 ($timeStr)\n\n")
                        
                        // Add screenshot if available
                        if (screenshotPath != null) {
                            val screenshotFile = java.io.File(screenshotPath)
                            markdown.append("![](${screenshotFile.name})\n\n")
                        }
                        
                        // Record exactly what is sent to model (single source of truth)
                        markdown.append(userPromptWithHistory)
                        markdown.append("\n\n")
                        
                        // Append to conversation.md
                        java.io.FileWriter(conversationFile, true).use { writer ->
                            writer.write(markdown.toString())
                        }
                        
                        // Trigger ChatUI to reload history from conversation.md
                        ragQueryManager?.requestReloadChatHistory()
                    }
                } catch (e: Exception) {
                    LogManager.logE(TAG, "[AGENT] Failed to save user message", e)
                }
                
                // Agent mode: ALWAYS skip RagQueryManager's RAG pipeline.
                // Agent owns its own RAG via queryKnowledgeBase() above (AgentKB).
                // User-configured knowledge base is ignored in Agent mode.
                val effectiveKnowledgeBase = ""
                LogManager.logI(TAG, "[AGENT_RAG] Agent mode: knowledgeBase=\"\" (Agent owns its own RAG via AgentKB)")
                val searchDepth = 0
                val graphRagEnabled = false
                
                val request = RagQueryManager.QueryRequest(
                    apiUrl,
                    apiKey,
                    model,
                    effectiveKnowledgeBase,
                    systemPrompt,
                    userPromptWithHistory,
                    imagePaths,
                    null, // audioPaths
                    0f, // audioDuration
                    searchDepth,
                    graphRagEnabled,
                    false, // needsAsr
                    null // asrModel
                )
                
                // Fully reuse RagQueryManager pipeline: RAG retrieval + prompt building + LLM call
                val result = ragMgr.querySync(request)
                
                if (result.isNullOrEmpty()) {
                    LogManager.logE(TAG, "RagQueryManager returned null or empty result")
                    floatingWindow?.updateStatus("Error: No response")
                    return@withContext "<tool_call>{\"action\":\"terminate\",\"text\":\"Error: Model returned no response\"}</tool_call>"
                }
                
                LogManager.logI(TAG, "[AGENT][STEP_$currentStep] Model response length: ${result.length}")
                
                // Extract pure model output (remove <debug> tags for Previous Steps)
                // <debug> is for UI display only, should NOT be sent to model as history
                val pureModelOutput = result.replace(Regex("<debug>.*?</debug>", RegexOption.DOT_MATCHES_ALL), "").trim()
                
                // Update floating window with model output
                // Skip during experience summary step to preserve the terminate result text
                if (!isExperienceSummaryStep) {
                    try {
                        floatingWindow?.updateOutput(pureModelOutput)
                    } catch (e: Exception) {
                        LogManager.logW(TAG, "Failed to update floating window output: ${e.message}")
                    }
                }
                
                // NOTE: UI update is now handled by polling mechanism (same as non-Agent mode)
                // No need to call requestAddAgentAIMessage - ChatUI will poll buffer and display streaming
                LogManager.logI(TAG, "[AGENT_UI] UI update handled by polling mechanism")
                
                val endTime = System.currentTimeMillis()
                LogManager.logI(TAG, "[AGENT_TIMING] callRagQueryManagerSync END - duration=${endTime - startTime}ms")
                
                // Return pure model output (without <debug>) to AgentEngine
                // This ensures TrajectoryStep.rawModelOutput doesn't contain UI-only <debug> tags
                pureModelOutput
            } catch (e: Exception) {
                LogManager.logE(TAG, "Error calling RagQueryManager", e)
                floatingWindow?.updateStatus("Error: ${e.message}")
                "<tool_call>{\"action\":\"terminate\",\"text\":\"Error: ${e.message}\"}</tool_call>"
            }
        }
    }
    

    /**
     * Create notification channel for Agent execution status
     */
    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                NOTIFICATION_CHANNEL_ID,
                "Agent Execution",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Shows Agent execution status"
                setShowBadge(false)
            }
            
            val notificationManager = getSystemService(NotificationManager::class.java)
            notificationManager?.createNotificationChannel(channel)
        }
    }
    
    /**
     * Show notification with Agent status
     */
    private fun showNotification(title: String, content: String) {
        val intent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        
        val notification = NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(content)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
        
        val notificationManager = getSystemService(NotificationManager::class.java)
        notificationManager?.notify(NOTIFICATION_ID, notification)
    }
    
    /**
     * Hide notification
     */
    private fun hideNotification() {
        val notificationManager = getSystemService(NotificationManager::class.java)
        notificationManager?.cancel(NOTIFICATION_ID)
    }
    
    /**
     * Initialize floating window
     */
    private fun initializeFloatingWindow() {
        floatingWindow = AgentFloatingWindow(this)
        floatingWindow?.setOnStopClickListener {
            LogManager.logI(TAG, "User clicked stop button in floating window")
            stopAgentLoop()
        }
        LogManager.logI(TAG, "Floating window initialized")
    }
    
    /**
     * Execute a block with floating window temporarily hidden.
     * Handles hide/show latch logic in one place.
     */
    private fun <T> withFloatingWindowHidden(block: () -> T): T {
        val hideLatch = java.util.concurrent.CountDownLatch(1)
        floatingWindow?.temporaryHide { hideLatch.countDown() } ?: hideLatch.countDown()
        if (!hideLatch.await(500, java.util.concurrent.TimeUnit.MILLISECONDS)) {
            LogManager.logW(TAG, "Timeout waiting for floating window to hide")
        }
        
        val result = block()
        
        val showLatch = java.util.concurrent.CountDownLatch(1)
        floatingWindow?.temporaryShow { showLatch.countDown() } ?: showLatch.countDown()
        if (!showLatch.await(500, java.util.concurrent.TimeUnit.MILLISECONDS)) {
            LogManager.logW(TAG, "Timeout waiting for floating window to show")
        }
        
        return result
    }
    
    /**
     * Click at screen position
     */
    fun clickAtPosition(x: Int, y: Int): Boolean = withFloatingWindowHidden {
        val path = Path().apply { moveTo(x.toFloat(), y.toFloat()) }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, 100))
            .build()
        val result = dispatchGesture(gesture, null, null)
        LogManager.logD(TAG, "Click at ($x, $y): ${if (result) "success" else "failed"}")
        result
    }
    
    /**
     * Long press at screen position
     */
    fun longPressAtPosition(x: Int, y: Int, durationMs: Long = 1000): Boolean = withFloatingWindowHidden {
        val path = Path().apply { moveTo(x.toFloat(), y.toFloat()) }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, durationMs))
            .build()
        val result = dispatchGesture(gesture, null, null)
        LogManager.logD(TAG, "Long press at ($x, $y): ${if (result) "success" else "failed"}")
        result
    }
    
    /**
     * Double click at screen position
     */
    fun doubleClickAtPosition(x: Int, y: Int): Boolean = withFloatingWindowHidden {
        val path1 = Path().apply { moveTo(x.toFloat(), y.toFloat()) }
        val path2 = Path().apply { moveTo(x.toFloat(), y.toFloat()) }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path1, 0, 100))
            .addStroke(GestureDescription.StrokeDescription(path2, 150, 100))
            .build()
        val result = dispatchGesture(gesture, null, null)
        LogManager.logD(TAG, "Double click at ($x, $y): ${if (result) "success" else "failed"}")
        result
    }
    
    /**
     * Input text to focused input field.
     * If no focused field, auto-search for an editable node and focus it first.
     * @return null on success, or a detailed error message on failure
     */
    fun inputTextWithReason(text: String): String? {
        var focusedNode = rootInActiveWindow?.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
        
        // Auto-focus: if no focused input field, try to find one and focus it
        if (focusedNode == null) {
            LogManager.logW(TAG, "No focused input field, attempting auto-focus...")
            val root = rootInActiveWindow
            if (root != null) {
                val editableNode = findEditableNode(root)
                if (editableNode != null) {
                    val focused = editableNode.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
                    val clicked = editableNode.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                    LogManager.logI(TAG, "Auto-focus editable node: focus=$focused, click=$clicked, class=${editableNode.className}")
                    if (focused || clicked) {
                        // Brief wait for focus to take effect
                        Thread.sleep(300)
                        focusedNode = rootInActiveWindow?.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
                    }
                    if (focusedNode == null) {
                        @Suppress("DEPRECATION")
                        editableNode.recycle()
                    }
                } else {
                    LogManager.logW(TAG, "No editable input field found on screen")
                }
            }
        }
        
        // Fallback: retry finding focus with longer wait (browser input fields need time)
        if (focusedNode == null) {
            LogManager.logW(TAG, "No focused input field found, retrying with longer wait...")
            for (retry in 1..3) {
                Thread.sleep(500) // Wait longer for browser input to gain focus
                focusedNode = rootInActiveWindow?.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
                if (focusedNode != null) {
                    LogManager.logI(TAG, "Found focused input field after retry $retry")
                    break
                }
            }
        }
        
        // Last resort: clipboard + inject Ctrl+A/Ctrl+V for WebView inputs that don't expose FOCUS_INPUT
        if (focusedNode == null) {
            LogManager.logW(TAG, "Still no focused input field, trying clipboard+paste for WebView...")
            return try {
                val clipboard = getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                val clip = android.content.ClipData.newPlainText("agent_input", text)
                clipboard.setPrimaryClip(clip)
                Thread.sleep(100)
                val selectOk = performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_ACCESSIBILITY_ALL_APPS).let {
                    // Use key injection instead - performGlobalAction for select-all isn't reliable
                    // Try ACTION_PASTE on any accessible node with paste support
                    val rootNode = rootInActiveWindow
                    var pasted = false
                    if (rootNode != null) {
                        // Try select-all + paste via accessibility actions on focused/editable nodes
                        val editNode = findEditableNode(rootNode)
                        if (editNode != null) {
                            editNode.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
                            editNode.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                            Thread.sleep(200)
                            // Clear existing text via SELECT_ALL then PASTE
                            editNode.performAction(0x00020000) // ACTION_SELECT_ALL (API 18+)
                            pasted = editNode.performAction(AccessibilityNodeInfo.ACTION_PASTE)
                            LogManager.logI(TAG, "[TYPE_WEBVIEW] editNode paste=$pasted, class=${editNode.className}")
                            @Suppress("DEPRECATION")
                            editNode.recycle()
                        }
                    }
                    pasted
                }
                
                if (selectOk) {
                    LogManager.logI(TAG, "[TYPE_WEBVIEW] Clipboard+paste succeeded for WebView input")
                    null // success
                } else {
                    LogManager.logW(TAG, "[TYPE_WEBVIEW] Paste action failed, text in clipboard for manual paste")
                    "No focused input field (text in clipboard, try manual paste)"
                }
            } catch (e: Exception) {
                LogManager.logE(TAG, "Failed clipboard workaround", e)
                "No focused input field. Click the input box first, then type"
            }
        }
        
        val args = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
        }
        
        val result = focusedNode.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
        @Suppress("DEPRECATION")
        focusedNode.recycle()
        
        LogManager.logD(TAG, "Input text '$text': ${if (result) "success" else "failed"}")
        return if (result) null else "ACTION_SET_TEXT failed on focused node"
    }
    
    /**
     * Legacy wrapper for backward compatibility
     */
    fun inputText(text: String): Boolean {
        return inputTextWithReason(text) == null
    }
    
    /**
     * Recursively find the first editable (text input) node in the accessibility tree
     * Enhanced to support WebView input fields
     */
    private fun findEditableNode(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        // Priority 1: isEditable=true (standard EditText)
        if (node.isEditable) {
            @Suppress("DEPRECATION")
            return AccessibilityNodeInfo.obtain(node)
        }
        
        // Priority 2: Check className for input-related views (WebView, EditText, etc.)
        val className = node.className?.toString() ?: ""
        val isFocusable = node.isFocusable
        val isClickable = node.isClickable
        
        // WebView or EditText that is focusable/clickable might be an input field
        if ((className.contains("EditText", ignoreCase = true) || 
             className.contains("WebView", ignoreCase = true)) && 
            (isFocusable || isClickable)) {
            @Suppress("DEPRECATION")
            return AccessibilityNodeInfo.obtain(node)
        }
        
        // Recursively search children
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            val found = findEditableNode(child)
            @Suppress("DEPRECATION")
            child.recycle()
            if (found != null) return found
        }
        return null
    }
    
    /**
     * Swipe gesture
     */
    fun swipe(startX: Int, startY: Int, endX: Int, endY: Int, durationMs: Long = 300): Boolean = withFloatingWindowHidden {
        val path = Path().apply {
            moveTo(startX.toFloat(), startY.toFloat())
            lineTo(endX.toFloat(), endY.toFloat())
        }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, durationMs))
            .build()
        val result = dispatchGesture(gesture, null, null)
        LogManager.logD(TAG, "Swipe from ($startX, $startY) to ($endX, $endY): ${if (result) "success" else "failed"}")
        result
    }
    
    /**
     * Drag gesture (longer duration than swipe)
     */
    fun drag(startX: Int, startY: Int, endX: Int, endY: Int, durationMs: Long = 600): Boolean {
        return swipe(startX, startY, endX, endY, durationMs)
    }
    
    /**
     * Press back button
     */
    fun pressBack(): Boolean {
        val result = performGlobalAction(GLOBAL_ACTION_BACK)
        LogManager.logD(TAG, "Press back: ${if (result) "success" else "failed"}")
        return result
    }
    
    /**
     * Press home button
     */
    fun pressHome(): Boolean {
        val result = performGlobalAction(GLOBAL_ACTION_HOME)
        LogManager.logD(TAG, "Press home: ${if (result) "success" else "failed"}")
        return result
    }
    
    /**
     * Press recent apps button
     */
    fun pressRecents(): Boolean {
        val result = performGlobalAction(GLOBAL_ACTION_RECENTS)
        LogManager.logD(TAG, "Press recents: ${if (result) "success" else "failed"}")
        return result
    }
    
    /**
     * Press Enter key - triggers IME action on focused input field
     * Strategy: Find and click the IME action button (search/send/done/go/next)
     */
    fun pressEnter(): Boolean {
        val focusedNode = rootInActiveWindow?.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
        
        if (focusedNode == null) {
            LogManager.logW(TAG, "No focused input field found for Enter action")
            return false
        }
        
        // Strategy 1: Try to find and click the IME action button
        // Look for buttons near the input field with text like "搜索", "发送", "完成", "Go", "Search", "Send"
        val root = rootInActiveWindow
        if (root != null) {
            val imeButton = findImeActionButton(root)
            if (imeButton != null) {
                val result = imeButton.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                @Suppress("DEPRECATION")
                imeButton.recycle()
                @Suppress("DEPRECATION")
                focusedNode.recycle()
                LogManager.logD(TAG, "Press Enter (clicked IME button): ${if (result) "success" else "failed"}")
                return result
            }
        }
        
        // Strategy 2: Fallback - insert newline character (may trigger submit in some apps)
        val args = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, 
                (focusedNode.text?.toString() ?: "") + "\n")
        }
        val result = focusedNode.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
        @Suppress("DEPRECATION")
        focusedNode.recycle()
        
        LogManager.logD(TAG, "Press Enter (inserted newline): ${if (result) "success" else "failed"}")
        return result
    }
    
    /**
     * Find IME action button (search/send/done/go/next button on keyboard)
     */
    private fun findImeActionButton(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        // Common IME action button texts
        val imeActionTexts = listOf("搜索", "发送", "完成", "前往", "下一步", 
                                     "Search", "Send", "Done", "Go", "Next")
        
        // Check current node
        val nodeText = node.text?.toString()?.trim() ?: ""
        if (node.isClickable && imeActionTexts.any { it.equals(nodeText, ignoreCase = true) }) {
            return node
        }
        
        // Recursively check children
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            val result = findImeActionButton(child)
            if (result != null) {
                @Suppress("DEPRECATION")
                if (child != result) child.recycle()
                return result
            }
            @Suppress("DEPRECATION")
            child.recycle()
        }
        
        return null
    }
    
    /**
     * Get current window root node (for debugging)
     */
    fun getCurrentWindowRoot(): AccessibilityNodeInfo? {
        return rootInActiveWindow
    }
    
    /**
     * Build user prompt with history context (like MAI-UI/Open-AutoGLM)
     * Format: Step N + Task Goal + Last Step Result + currentContext (Agent memory) + Current Instruction
     *
     * @param instruction Current task goal/instruction
     * @param stepIndex Current step number (0-based)
     * @return Formatted user prompt with context memory
     */
    private fun buildUserPromptWithHistory(
        instruction: String,
        stepIndex: Int,
        hasScreenshot: Boolean = true
    ): String {
        // Experience summary step: use instruction as-is (already a complete prompt)
        if (isExperienceSummaryStep) {
            return instruction
        }
        
        val prompt = StringBuilder()
        
        // Step number (1-based for user readability)
        prompt.append("Step ${stepIndex + 1}\n\n")
        
        // Task goal (Chinese for better token efficiency)
        prompt.append("任务: $instruction\n\n")

        if (stepIndex == 0) {
            prompt.append("""
##首轮规划要求（只执行一次）
 -这是任务开始。先输出任务简报 JSON，并使用 data_memory 保存为 key=taskBrief；同一轮继续输出首个可执行 action。
 -任务简报 JSON 必须包含字段：任务、目标、里程碑(3-5个)、执行约束（强制）。
 -固定模板（原样复制）：{"name":"data_memory","parameters":{"operation":"set","key":"taskBrief","value":"任务:<任务>\n目标:<目标>\n里程碑:\nM1 <内容>\nM2 <内容>\nM3 <内容>\n...\n执行约束:\n<约束1>\n<约束2>\n...\n"}}""");
        }
        
        // Inject last step hardcoded fact BEFORE context (highest priority, cannot be forgotten)
        // This is the "ground truth" the model must acknowledge regardless of its context memory
        if (lastStepResult.isNotEmpty()) {
            prompt.append("$lastStepResult\n\n")
        }
        
        // Inject data memory keys index (lightweight, no values)
        if (dataMemoryKeys.isNotEmpty()) {
            prompt.append("Data Memory: ${dataMemoryKeys.joinToString(", ")}\n\n")
        }

        if (taskBrief.isNotEmpty()) {
            prompt.append("任务简报（执行基线）:\n$taskBrief\n\n")
        }
        
        // Add current context (Agent's memory)
        if (currentContext.isNotEmpty()) {
            prompt.append("上下文记忆:\n$currentContext\n\n")
        }
        
        // Current instruction (Chinese) - adapt based on screenshot availability
        if (hasScreenshot) {
            prompt.append("根据当前截图或结果返回，决定下一步操作以继续任务。\n")
        } else {
            prompt.append("根据当前结果返回，决定下一步操作以继续任务。\n")
        }
        
        return prompt.toString()
    }
    
    /**
     * Save step history to conversation.md
     * Called by AgentEngine callback after each step completes
     */
    private fun saveStepToConversationMd(
        stepIndex: Int,
        action: AgentAction,
        result: ExecutionResult
    ) {
        LogManager.logI(TAG, "[AGENT_STEP_HISTORY_DEBUG] === saveStepToConversationMd CALLED ===")
        LogManager.logI(TAG, "[AGENT_STEP_HISTORY_DEBUG] stepIndex=$stepIndex, action=${action.javaClass.simpleName}, result.success=${result.success}")
        try {
            val context = applicationContext
            val chatFolderPath = ConfigManager.getString(
                context,
                ConfigManager.KEY_CURRENT_CHAT_FOLDER,
                ""
            )
            LogManager.logI(TAG, "[AGENT_STEP_HISTORY_DEBUG] chatFolderPath: $chatFolderPath")
            
            if (chatFolderPath.isEmpty()) {
                LogManager.logW(TAG, "[AGENT_STEP_HISTORY] ❌ Chat folder not set, cannot save step history")
                return
            }
            
            LogManager.logI(TAG, "[AGENT_STEP_HISTORY_DEBUG] chatFolderPath is not empty, proceeding...")
            
            val markdown = StringBuilder()
            
            // Format: Step N:
            // ACTION: action description
            // RESULT: result message
            markdown.append("\nStep $stepIndex:\n")
            
            // ACTION description
            markdown.append("ACTION: ")
            val actionDesc = when (action) {
                is AgentAction.Click -> 
                    "Clicked at [${action.x}, ${action.y}]"
                is AgentAction.Type -> 
                    "Typed: ${action.text}"
                is AgentAction.Swipe -> 
                    "Swiped ${action.direction}"
                is AgentAction.Open -> 
                    "Opened app: ${action.appName}"
                is AgentAction.SystemButton -> 
                    "Pressed ${action.button} button"
                is AgentAction.Wait -> 
                    "Waited"
                is AgentAction.Terminate ->
                    "Terminated (${action.status.value})"
                is AgentAction.AskUser ->
                    "AskUser: ${action.text}"
                is AgentAction.WebOpen ->
                    "WebOpen: ${action.url} (page loaded in background WebView)"
                is AgentAction.WebGetContent ->
                    "WebGetContent: extracted page content from background WebView"
                is AgentAction.WebExecuteJs ->
                    "WebExecuteJs: ${action.script.take(60)}"
                is AgentAction.CreateFile ->
                    "CreateFile: ${action.path} (${action.content.length} chars)"
                else -> action.javaClass.simpleName
            }
            markdown.append(actionDesc)
            markdown.append("\n")
            LogManager.logI(TAG, "[AGENT_STEP_HISTORY_DEBUG] actionDesc: $actionDesc")
            
            // RESULT
            markdown.append("RESULT: ")
            if (result.success) {
                markdown.append("Success - ${result.message}")
            } else {
                markdown.append("Failed - ${result.message}")
            }
            markdown.append("\n")
            LogManager.logI(TAG, "[AGENT_STEP_HISTORY_DEBUG] Markdown content: ${markdown.toString()}")
            
            // DO NOT append to conversation.md!
            // Appending here would pollute the AI message block in conversation.md
            LogManager.logI(TAG, "[AGENT_STEP_HISTORY] ✅ Step $stepIndex recorded in memory")
            
        } catch (e: Exception) {
            LogManager.logE(TAG, "[AGENT_STEP_HISTORY] ❌ Failed to save step history", e)
            e.printStackTrace()
        }
    }
    
    // Experience summary state
    private var experienceSummaryContent = ""
    // Flag to indicate current model call is for experience summary (system prompt should be empty)
    @Volatile
    var isExperienceSummaryStep = false
    
    /**
     * Build task history string from TrajectoryStep list (for AgentEngine to call)
     * This is called by AgentEngine when experience summary is enabled
     */
    fun buildTaskHistoryForSummary(steps: List<TrajectoryStep>): String {
        val history = StringBuilder()
        val maxResultLen = 160
        
        steps.forEach { step ->
            history.append("Step ${step.stepIndex}:\n")

            val actionDesc = when (val action = step.action) {
                is AgentAction.Click -> "click([${action.x},${action.y}])"
                is AgentAction.LongPress -> "long_press([${action.x},${action.y}])"
                is AgentAction.DoubleClick -> "double_click([${action.x},${action.y}])"
                is AgentAction.Type -> "type(len=${action.text.length})"
                is AgentAction.Swipe -> "swipe(${action.direction})"
                is AgentAction.Drag -> "drag([${action.startX},${action.startY}]→[${action.endX},${action.endY}])"
                is AgentAction.Open -> "open(${action.appName})"
                is AgentAction.SystemButton -> "system_button(${action.button})"
                is AgentAction.Wait -> "wait"
                is AgentAction.Terminate -> "terminate(${action.status.value})"
                is AgentAction.AskUser -> "ask_user"
                is AgentAction.GetAppList -> "get_app_list"
                is AgentAction.WebOpen -> "web_open"
                is AgentAction.WebGetContent -> "web_get_content"
                is AgentAction.WebExecuteJs -> "web_execute_js"
                is AgentAction.DataMemory -> "data_memory(${action.operation},${action.key})"
                is AgentAction.CreateFile -> "create_file(${action.path})"
                is AgentAction.ReadFile -> "read_file(${action.path})"
                is AgentAction.WriteFile -> "write_file(${action.path})"
                is AgentAction.ReadLines -> "read_lines(${action.path})"
                is AgentAction.EditLines -> "edit_lines(${action.path})"
                is AgentAction.Grep -> "grep(${action.path})"
                is AgentAction.RenameFile -> "rename_file(${action.oldPath}→${action.newPath})"
                is AgentAction.DeleteFile -> "delete_file(${action.path})"
                is AgentAction.CopyFile -> "copy_file(${action.src}→${action.dst})"
                is AgentAction.ListDir -> "list_dir(${action.path})"
                is AgentAction.Mkdir -> "mkdir(${action.path})"
                is AgentAction.SearchFiles -> "search_files(${action.path})"
                is AgentAction.PythonRun -> "python_run(${if (action.code != null) "code" else "file"})"
                is AgentAction.PythonStatus -> "python_status"
                is AgentAction.PythonKill -> "python_kill"
                else -> action.javaClass.simpleName
            }
            history.append("ACTION: $actionDesc\n")

            val resultMsg = step.executionResult.message.replace("\n", " ").trim().take(maxResultLen)
            history.append("RESULT: ${if (step.executionResult.success) "Success" else "Failed"} - $resultMsg\n")
            
            history.append("\n")
        }
        
        return history.toString()
    }
    
    /**
     * Build experience summary prompt from task history
     * This is called by AgentEngine when experience summary is enabled
     */
    fun buildExperienceSummaryPromptFromHistory(
        taskHistory: String,
        agentKbRecalled: String = ""
    ): String {
        val context = applicationContext
        val apiUrl = ConfigManager.getAgentApiUrl(context).ifEmpty { AppConstants.ApiUrl.LOCAL }
        val model = ConfigManager.getAgentModelName(context)
        val format = ActionFormatRegistry.getFormatForApi(apiUrl, model)
        
        val kbActionDesc = format.getKbActionDescription()
        
        // Build recalled experience section
        val recalledSection = if (agentKbRecalled.isNotEmpty()) {
            """
## 知识库中的已有经验（召回，仅供参考）
以下是从知识库中召回的历史经验，每个文档都有 [ID:xxx] 标签。
⚠️ 召回结果是相似度匹配，可能包含其他任务的经验，请先判断相关性再决定是否删除。
$agentKbRecalled"""
        } else {
            "\n## 知识库中未找到已有经验。\n"
        }
        
        return """
基于任务历史，输出经验总结的 KB Action。

## 任务执行历史
$taskHistory
$recalledSection

## 删除规则
- **严禁删除与本次任务无关的经验**
- 只有**同类型+过时+确定相关**才 delete，否则保留

## KB Action 格式
$kbActionDesc

## 新经验（kb_insert ≤500字，去修饰词）
- 使用正确之前context提到的Action名称（list_dir/create_file等）
- 关键步骤示例：
  - UI类：点击edge图标→搜索框→输入→搜索
  - 文件操作：list_dir(确认目录)→create_file(创建文件)→edit_lines(追加内容)→read_file(验证)
- 5要素：任务目标、步骤序列、应用名称、坐标/路径、避坑提示

输出 ≤800字，简要思考后直接输出 action。
        """.trimIndent()
    }
    
    
    /**
     * Called by AgentEngine when experience summary is generated
     * Shows save button and stores summary content
     */
    fun onExperienceSummaryGenerated(summaryContent: String) {
        LogManager.logI(TAG, "[AGENT_EXP] Summary generated, length: ${summaryContent.length}")
        
        // Store summary content for saving to AgentKB
        experienceSummaryContent = summaryContent
        isWaitingForExperienceSave = true  // Set flag to prevent auto-hide
        
        // Show save button
        floatingWindow?.updateStatus("Experience summary ready")
        floatingWindow?.showSaveButton()
        
        LogManager.logI(TAG, "[AGENT_EXP] Save button shown, waiting for user action")
    }
    
    /**
     * Read task history from in-memory trajectory (more reliable than conversation.md)
     * Extract the essential info: task goal + each step's model response and action
     */
    fun readTaskHistoryFromMemory(taskGoal: String, trajectory: List<TrajectoryStep>): String {
        try {
            val result = StringBuilder()
            result.append("Task: $taskGoal\n\n")
            
            trajectory.forEachIndexed { index, step ->
                val stepNum = index + 1
                
                // Extract first 100 chars from rawModelOutput as summary
                // Remove all tags to save tokens
                val cleanText = step.rawModelOutput
                    .replace(Regex("<thinking>|</thinking>|<tool_call>|</tool_call>"), "")
                    .replace(Regex("\\s+"), " ")
                    .trim()
                if (cleanText.isNotEmpty()) {
                    val summary = if (cleanText.length > 100) {
                        cleanText.take(100) + "..."
                    } else {
                        cleanText
                    }
                    result.append("Step $stepNum 模型输出: $summary\n")
                }
                
                // Extract action with detailed info
                result.append("Step $stepNum action: ${step.action}")
                
                // Add coordinate info for UI actions
                when (val action = step.action) {
                    is AgentAction.Click -> {
                        result.append(" (coordinates: x=${action.x}, y=${action.y})")
                    }
                    is AgentAction.LongPress -> {
                        result.append(" (coordinates: x=${action.x}, y=${action.y})")
                    }
                    is AgentAction.DoubleClick -> {
                        result.append(" (coordinates: x=${action.x}, y=${action.y})")
                    }
                    is AgentAction.Swipe -> {
                        result.append(" (direction: ${action.direction}")
                        if (action.x != null && action.y != null) {
                            result.append(", at: x=${action.x}, y=${action.y}")
                        }
                        result.append(")")
                    }
                    is AgentAction.Drag -> {
                        result.append(" (from: x=${action.startX}, y=${action.startY} to: x=${action.endX}, y=${action.endY})")
                    }
                    is AgentAction.Type -> {
                        result.append(" (text length: ${action.text.length} chars)")
                    }
                    is AgentAction.Open -> {
                        result.append(" (app: ${action.appName})")
                    }
                    is AgentAction.SystemButton -> {
                        result.append(" (button: ${action.button})")
                    }
                    is AgentAction.GetAppList -> {
                        // No extra info needed
                    }
                    is AgentAction.Terminate -> {
                        result.append(" (status: ${action.status})")
                    }
                    is AgentAction.Context -> {
                        result.append(" (context length: ${action.text.length} chars)")
                    }
                    is AgentAction.AskUser -> {
                        result.append(" (question/instruction: ${action.text})")
                    }
                    is AgentAction.KbInsert -> {
                        result.append(" (content length: ${action.text.length} chars)")
                    }
                    is AgentAction.KbDelete -> {
                        result.append(" (ids: ${action.ids})")
                    }
                    is AgentAction.WebOpen -> {
                        result.append(" (url: ${action.url})")
                    }
                    is AgentAction.WebGetContent -> {
                        // No extra info needed
                    }
                    is AgentAction.WebExecuteJs -> {
                        result.append(" (script length: ${action.script.length} chars)")
                    }
                    is AgentAction.Wait -> {
                        // No extra info needed
                    }
                    is AgentAction.DataMemory -> {
                        result.append(" (op=${action.operation}, key=${action.key})")
                    }
                    is AgentAction.CreateFile -> {
                        result.append(" (create ${action.path}, ${action.content.length} chars)")
                    }
                    is AgentAction.ReadFile -> {
                        result.append(" (read ${action.path})")
                    }
                    is AgentAction.WriteFile -> {
                        result.append(" (write ${action.path}, ${action.content.length} chars)")
                    }
                    is AgentAction.ReadLines -> {
                        result.append(" (read ${action.path} L${action.startLine}-${action.endLine})")
                    }
                    is AgentAction.EditLines -> {
                        result.append(" (edit ${action.path} L${action.startLine}-${action.endLine})")
                    }
                    is AgentAction.Grep -> {
                        result.append(" (grep ${action.path} for '${action.keyword}')")
                    }
                    is AgentAction.RenameFile -> {
                        result.append(" (rename ${action.oldPath} to ${action.newPath})")
                    }
                    is AgentAction.DeleteFile -> {
                        result.append(" (delete ${action.path})")
                    }
                    is AgentAction.CopyFile -> {
                        result.append(" (copy ${action.src} to ${action.dst})")
                    }
                    is AgentAction.ListDir -> {
                        result.append(" (list dir ${action.path})")
                    }
                    is AgentAction.Mkdir -> {
                        result.append(" (mkdir ${action.path})")
                    }
                    is AgentAction.SearchFiles -> {
                        result.append(" (search '${action.keyword}' in ${action.path})")
                    }
                    is AgentAction.PythonRun -> {
                        result.append(" (${if (action.code != null) "code" else "file"}: ${action.code?.take(30) ?: action.file})")
                    }
                    is AgentAction.PythonStatus -> {
                        result.append(" (list all sessions)")
                    }
                    is AgentAction.PythonKill -> {
                        result.append(" (kill active python instance)")
                    }
                }
                result.append("\n")
                
                // Add coordinate error if any
                if (step.coordinateError != null) {
                    result.append("Step $stepNum coordinate error: ${step.coordinateError}\n")
                }
                
                // Extract execution result with details
                if (step.executionResult.success) {
                    result.append("Step $stepNum result: Success")
                    // Add returnData if available (e.g., app list)
                    if (!step.executionResult.returnData.isNullOrEmpty()) {
                        result.append(" (returned ${step.executionResult.returnData.length} chars of data)")
                    }
                    result.append("\n")
                } else {
                    result.append("Step $stepNum result: Failed - ${step.executionResult.message}\n")
                }
                
                result.append("\n")
            }
            
            val taskHistory = result.toString().trim()
            LogManager.logI(TAG, "[AGENT_EXP] Extracted ${trajectory.size} steps from memory, length: ${taskHistory.length}")
            LogManager.logI(TAG, "[AGENT_EXP] Task history:\n$taskHistory")
            
            return taskHistory
            
        } catch (e: Exception) {
            LogManager.logE(TAG, "[AGENT_EXP] Failed to read task history from memory", e)
            return ""
        }
    }
    
    /**
     * Save experience summary to AgentKB knowledge base.
     * Parses model output for kb_delete and kb_insert actions, then executes them.
     * kb_delete: removes outdated/redundant chunks by ID
     * kb_insert: adds new experience summary with embedding + NER + knowledge graph
     */
    private fun saveExperienceToAgentKB() {
        LogManager.logI(TAG, "[AGENT_EXP] User clicked save button")
        
        if (experienceSummaryContent.isEmpty()) {
            LogManager.logE(TAG, "[AGENT_EXP] Experience summary content is empty, cannot save")
            floatingWindow?.updateStatus("No content to save")
            return
        }
        
        floatingWindow?.updateStatus("Executing KB actions...")
        floatingWindow?.hideSaveButton()
        
        serviceScope.launch {
            try {
                val context = applicationContext
                val kbName = AGENT_KB_NAME
                
                // Parse KB actions: returns at most [last_delete, last_insert] after CoT filtering
                val kbActions = parseKbActions(experienceSummaryContent)
                LogManager.logI(TAG, "[AGENT_EXP] Parsed ${kbActions.size} KB action(s)")
                
                if (kbActions.isEmpty()) {
                    LogManager.logW(TAG, "[AGENT_EXP] No KB action found, falling back to direct save")
                    executeKbInsert(context, kbName, experienceSummaryContent)
                } else {
                    // Execute in order: delete first (if any), then insert (if any)
                    for (action in kbActions) {
                        when (action) {
                            is KbActionItem.Delete -> {
                                executeKbDelete(context, kbName, action.ids)
                                LogManager.logI(TAG, "[AGENT_EXP] Executed kb_delete: ${action.ids.size} IDs")
                            }
                            is KbActionItem.Insert -> {
                                if (action.text.length < 50) {
                                    LogManager.logW(TAG, "[AGENT_EXP] Insert content too short (${action.text.length} chars)")
                                }
                                executeKbInsert(context, kbName, action.text)
                                LogManager.logI(TAG, "[AGENT_EXP] Executed kb_insert: ${action.text.length} chars")
                            }
                        }
                    }
                }
                
                // Clear flag after successful save
                isWaitingForExperienceSave = false
                experienceSummaryContent = ""
                
                floatingWindow?.updateStatus("Saved to AgentKB")
                startCountdownToClose()
                
            } catch (e: Exception) {
                LogManager.logE(TAG, "[AGENT_EXP] Failed to save experience to AgentKB", e)
                floatingWindow?.updateStatus("Save failed: ${e.message}")
                isWaitingForExperienceSave = false
                experienceSummaryContent = ""
            }
        }
    }
    
    /**
     * Sealed class for parsed KB action items
     */
    private sealed class KbActionItem {
        data class Delete(val ids: List<Long>) : KbActionItem()
        data class Insert(val text: String) : KbActionItem()
    }
    
    /**
     * Parse KB actions from model output using universal CoT strategy.
     * 
     * Universal CoT Strategy (completely generic):
     * 1. Call format.parseActionsWithCoT() to get all final actions (one per type)
     * 2. Filter out KB actions (KbDelete, KbInsert)
     * 3. Convert to KbActionItem and return
     * 
     * This is completely generic and works for any combination of action types.
     * No hardcoded logic - fully reuses ActionFormat's universal CoT implementation.
     */
    private fun parseKbActions(modelOutput: String): List<KbActionItem> {
        val actions = mutableListOf<KbActionItem>()
        
        try {
            val apiUrl = ConfigManager.getAgentApiUrl(applicationContext).ifEmpty { AppConstants.ApiUrl.LOCAL }
            val modelName = ConfigManager.getAgentModelName(applicationContext)
            val format = com.example.offlineai.agent.parser.ActionParser.resolveFormat(apiUrl, modelName)
            
            LogManager.logI(TAG, "[AGENT_EXP] Using ${format.getFormatName()} with universal CoT strategy")
            
            // Use universal CoT: group by type, take last of each type
            val finalActions = format.parseActions(modelOutput)
            
            LogManager.logI(TAG, "[AGENT_EXP] Universal CoT returned ${finalActions.size} final action(s)")
            
            // Filter and convert KB actions
            for (action in finalActions) {
                when (action) {
                    is AgentAction.KbDelete -> {
                        val ids = parseIdString(action.ids)
                        if (ids.isNotEmpty()) {
                            actions.add(KbActionItem.Delete(ids))
                            LogManager.logI(TAG, "[AGENT_EXP] Final kb_delete: ${ids.size} IDs")
                        }
                    }
                    is AgentAction.KbInsert -> {
                        if (action.text.isNotEmpty()) {
                            actions.add(KbActionItem.Insert(action.text))
                            LogManager.logI(TAG, "[AGENT_EXP] Final kb_insert: ${action.text.length} chars")
                        }
                    }
                    else -> {
                        // Ignore non-KB actions
                        LogManager.logD(TAG, "[AGENT_EXP] Ignoring non-KB action: ${action::class.simpleName}")
                    }
                }
            }
            
        } catch (e: Exception) {
            LogManager.logE(TAG, "[AGENT_EXP] Failed to parse KB actions", e)
        }
        
        return actions
    }
    
    /**
     * Parse comma-separated ID string into list of Long IDs
     */
    private fun parseIdString(idStr: String): List<Long> {
        return idStr.split(",")
            .mapNotNull { it.trim().toLongOrNull() }
    }
    
    /**
     * Execute kb_delete: remove chunks by IDs from AgentKB
     */
    private suspend fun executeKbDelete(context: android.content.Context, kbName: String, ids: List<Long>) {
        withContext(Dispatchers.IO) {
            try {
                val kbBasePath = ConfigManager.getKnowledgeBasePath(context)
                val kbDir = java.io.File(kbBasePath, kbName)
                val dbFile = java.io.File(kbDir, "knowledge_graph.db")
                if (!dbFile.exists()) {
                    LogManager.logW(TAG, "[AGENT_EXP] AgentKB DB not found, skipping delete")
                    return@withContext
                }
                val vectorDb = KnowledgeGraphDatabase(context, dbFile.absolutePath, kbName)
                try {
                    val deleted = vectorDb.deleteChunksByIds(ids)
                    LogManager.logI(TAG, "[AGENT_EXP] kb_delete executed: requested=${ids.size}, deleted=$deleted")
                } finally {
                    vectorDb.close()
                }
            } catch (e: Exception) {
                LogManager.logE(TAG, "[AGENT_EXP] kb_delete failed (non-fatal): ${e.message}")
            }
        }
    }
    
    /**
     * Execute kb_insert: add new experience text to AgentKB with embedding + NER + graph
     */
    private suspend fun executeKbInsert(context: android.content.Context, kbName: String, insertText: String) {
        withContext(Dispatchers.IO) {
            val kbBasePath = ConfigManager.getKnowledgeBasePath(context)
            val kbDir = java.io.File(kbBasePath, kbName)
            if (!kbDir.exists()) {
                LogManager.logI(TAG, "[AGENT_EXP] AgentKB does not exist, creating at: ${kbDir.absolutePath}")
                kbDir.mkdirs()
            }
            
            val timestamp = java.text.SimpleDateFormat("yyyyMMdd_HHmmss", java.util.Locale.US).format(java.util.Date())
            val noteTitle = "Agent Experience $timestamp"
            val fullText = "Title: $noteTitle\n\nContent: $insertText"
            LogManager.logI(TAG, "[AGENT_EXP] kb_insert: fullText length=${fullText.length}")
            
            val embeddingModelName = ConfigManager.getLastSelectedEmbeddingModel(context)
            if (embeddingModelName.isNullOrEmpty()) {
                LogManager.logE(TAG, "[AGENT_EXP] No embedding model configured, cannot insert")
                return@withContext
            }
            val embeddingModelPath = ConfigManager.getEmbeddingModelPath(context) +
                java.io.File.separator + embeddingModelName
            
            RuntimeConfigUtil.pushToInference(context)
            val client = InferenceClient.getInstance(context)
            val contentEmbedding = client.computeEmbedding(
                embeddingModelPath,
                EmbeddingHandler.MemoryMode.LOW.value,
                fullText
            )
            if (contentEmbedding == null || contentEmbedding.isEmpty()) {
                LogManager.logE(TAG, "[AGENT_EXP] Embedding vector is null or empty, cannot insert")
                return@withContext
            }
            LogManager.logI(TAG, "[AGENT_EXP] Embedding generated, dimension: ${contentEmbedding.size}")
            
            val dbPath = java.io.File(kbDir, "knowledge_graph.db").absolutePath
            val vectorDb = KnowledgeGraphDatabase(context, dbPath, kbName)
            
            try {
                // Update metadata
                val rerankerModel = ConfigManager.getLastSelectedRerankerModel(context) ?: ""
                val metadata = KnowledgeGraphDatabase.DatabaseMetadata(embeddingModelName).apply {
                    embeddingDimension = contentEmbedding.size
                    modeldir = embeddingModelName
                    if (rerankerModel.isNotEmpty()) {
                        rerankerdir = rerankerModel
                    }
                }
                vectorDb.updateMetadata(metadata)
                
                // Add chunk
                val docId = vectorDb.addChunk(fullText, noteTitle, contentEmbedding, "")
                val success = docId >= 0
                LogManager.logI(TAG, "[AGENT_EXP] addChunk result: docId=$docId, success=$success")
                
                // NER + knowledge graph
                if (success) {
                    try {
                        val dictPath = ConfigManager.getString(context, ConfigManager.KEY_GRAPH_CUSTOM_DICT_PATH, null)
                        val valueNone = context.getString(R.string.common_none)
                        val customDictPath = if (!dictPath.isNullOrEmpty() && dictPath != valueNone) dictPath else null
                        
                        val nerHandler = HanLpNerHandler(customDictPath)
                        try {
                            val nerResult = nerHandler.extractEntities(fullText)
                            if (nerResult != null && nerResult.isSuccess) {
                                var entities = nerResult.entities
                                if (entities != null && entities.isNotEmpty()) {
                                    val stopwordsPath = ConfigManager.getGraphStopwordsPath(context)
                                    if (!stopwordsPath.isNullOrEmpty()) {
                                        try {
                                            val matcher = GraphStopwordsMatcher.loadFromFile(stopwordsPath)
                                            if (matcher != null) {
                                                entities = entities.filter { !matcher.matches(it.text) }
                                            }
                                        } catch (e: Exception) {
                                            LogManager.logE(TAG, "[AGENT_EXP] Failed to load stopwords: ${e.message}")
                                        }
                                    }
                                    vectorDb.addEntitiesAndBuildGraph(docId, entities, nerHandler)
                                }
                            }
                        } finally {
                            nerHandler.release()
                        }
                    } catch (e: Exception) {
                        LogManager.logE(TAG, "[AGENT_EXP] NER/graph error (non-fatal): ${e.message}")
                    }
                }
                
                LogManager.logI(TAG, "[AGENT_EXP] Total chunks in AgentKB: ${vectorDb.chunkCount}")
            } finally {
                vectorDb.close()
            }
            
            // Write metadata.json for compatibility
            try {
                val jsonMetadataFile = java.io.File(kbDir, "metadata.json")
                val json = org.json.JSONObject()
                json.put("knowledgeBase", kbName)
                json.put("embeddingModel", embeddingModelName)
                json.put("modeldir", embeddingModelName)
                val rerankerMdl = ConfigManager.getLastSelectedRerankerModel(context) ?: ""
                if (rerankerMdl.isNotEmpty()) {
                    json.put("rerankerModel", rerankerMdl)
                }
                json.put("embeddingDimension", contentEmbedding.size)
                json.put("updated", System.currentTimeMillis())
                java.io.FileWriter(jsonMetadataFile, false).use { it.write(json.toString()) }
                LogManager.logD(TAG, "[AGENT_EXP] metadata.json written: ${jsonMetadataFile.absolutePath}")
            } catch (e: Exception) {
                LogManager.logW(TAG, "[AGENT_EXP] Failed to write metadata.json (non-fatal): ${e.message}")
            }
        }
    }
    
    /**
     * Close floating window and stop agent after experience is saved.
     * No countdown - user already clicked save so we close immediately.
     */
    private fun startCountdownToClose() {
        stopAgentLoop()
        LogManager.logI(TAG, "[AGENT_EXP] Experience saved, Agent service stopped")
    }
    
}
