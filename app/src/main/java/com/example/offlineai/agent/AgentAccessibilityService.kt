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
import com.example.offlineai.agent.AgentPrompts
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
        
        @Volatile
        private var instance: AgentAccessibilityService? = null
        
        fun getInstance(): AgentAccessibilityService? = instance
        
        fun isServiceEnabled(): Boolean = instance != null
    }
    
    private var ragQueryManager: RagQueryManager? = null
    private var ragQaFragment: RagQaFragment? = null  // Direct Fragment reference for UI updates
    private var agentEngine: AgentEngine? = null
    private val serviceScope = CoroutineScope(Dispatchers.Main + Job())
    private var agentLoopJob: Job? = null
    internal var floatingWindow: AgentFloatingWindow? = null
    private var currentStep = 0
    private val maxSteps = 20
    private var cachedAppList: List<String>? = null  // Cache app list to avoid repeated queries
    private var savedTemperature: Float? = null  // Save original temperature before Agent starts
    
    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        initializeFloatingWindow()
        createNotificationChannel()
        LogManager.logI(TAG, "Accessibility service connected")
    }
    
    private var isAgentActive = false
    private var isWaitingForExperienceSave = false  // Flag to prevent auto-hide when waiting for user to save experience
    
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
                override fun onStepStarted(stepIndex: Int, thinking: String) {
                    // Silent
                }
                
                override fun onStepCompleted(stepIndex: Int, thinking: String, action: AgentAction, result: ExecutionResult) {
                    LogManager.logI(TAG, "[AGENT] Step $stepIndex: ${action.javaClass.simpleName} - ${if (result.success) "OK" else "FAIL"}")
                    saveStepToConversationMd(stepIndex, thinking, action, result)
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
                
                override fun onAnswer(text: String) {
                    LogManager.logI(TAG, "[AGENT] Answer received")
                }
                
                override fun onAskUser(question: String, callback: (String) -> Unit) {
                    // Not implemented
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
                val apiUrl = ConfigManager.getString(appContext, ConfigManager.KEY_API_URL, AppConstants.ApiUrl.LOCAL)
                val model = ConfigManager.getString(appContext, ConfigManager.KEY_MODEL_NAME, "")
                
                // Call AgentEngine.executeTask with model inference callback
                // RAG retrieval is handled by RagQueryManager's full pipeline via QueryRequest
                agentEngine?.executeTask(taskGoal, apiUrl, model) { instruction, screenshot, history ->
                    floatingWindow?.updateStatus("Waiting for model...")
                    val result = callRagQueryManagerSync(instruction, screenshot, history)
                    
                    // Extract pure model output (remove <debug> tags for Previous Steps)
                    // <debug> is for UI display only, should NOT be sent to model as history
                    val pureModelOutput = result.replace(Regex("<debug>.*?</debug>", RegexOption.DOT_MATCHES_ALL), "").trim()
                    
                    // Update floating window with model output
                    try {
                        floatingWindow?.updateOutput(pureModelOutput)
                    } catch (e: Exception) {
                        LogManager.logW(TAG, "Failed to update floating window output: ${e.message}")
                    }
                    
                    pureModelOutput
                }
                
                LogManager.logI(TAG, "Agent loop completed")
                showNotification("Agent Completed", "Task finished")
                
                // Check if waiting for experience save
                if (isWaitingForExperienceSave) {
                    LogManager.logI(TAG, "[AGENT_EXP] Waiting for user to save experience, keeping floating window visible")
                    // Don't hide floating window, user needs to interact with save/cancel buttons
                } else {
                    // Show countdown before hiding (5 seconds)
                    for (i in 5 downTo 1) {
                        try {
                            floatingWindow?.updateStatus("Task completed! Closing in ${i}s...")
                        } catch (e: Exception) {
                            LogManager.logW(TAG, "Failed to update floating window countdown")
                        }
                        delay(1000)
                    }
                    
                    try {
                        floatingWindow?.hide()  // Final hide uses full hide
                    } catch (e: Exception) {
                        LogManager.logW(TAG, "Failed to hide floating window")
                    }
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
                if (isWaitingForExperienceSave) {
                    // Keep floating window visible for save/cancel interaction
                    LogManager.logI(TAG, "[AGENT_EXP] Keeping floating window for experience save")
                    floatingWindow?.show()
                } else {
                    floatingWindow?.show()
                    stopAgentLoop()
                }
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
        screenshot: Bitmap?,
        history: List<TrajectoryStep>
    ): String {
        val startTime = System.currentTimeMillis()
        LogManager.logI(TAG, "[AGENT] Step $currentStep: instruction.len=${instruction.length}, screenshot=${screenshot != null}, history=${history.size}")
        
        currentStep++
        try {
            floatingWindow?.updateStep(currentStep)
            floatingWindow?.updateStatus("Step $currentStep: Thinking...")
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
                val apiUrl = ConfigManager.getString(context, ConfigManager.KEY_API_URL, AppConstants.ApiUrl.LOCAL)
                val apiKey = ConfigManager.getApiKey(context, apiUrl)  // Get API Key for specific URL
                val model = ConfigManager.getString(context, ConfigManager.KEY_MODEL_NAME, "")
                
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
                val noThinking = ConfigManager.getBoolean(context, ConfigManager.KEY_NO_THINKING, false)
                // Use empty system prompt for experience summary step
                val systemPrompt = if (isExperienceSummaryStep) {
                    LogManager.logI(TAG, "[AGENT_EXP] Using empty system prompt for experience summary")
                    ""
                } else {
                    AgentPrompts.getSystemPromptForApi(apiUrl, model, appNames, !noThinking)
                }
                
                // Log system prompt on first step (full content for debugging)
                if (currentStep == 1) {
                    LogManager.logI(TAG, "[AGENT][INIT] System Prompt length: ${systemPrompt.length}")
                    LogManager.logI(TAG, "[AGENT][INIT] System Prompt:\n$systemPrompt")
                }
                
                // Build user prompt with history (like MAI-UI/Open-AutoGLM)
                val userPromptWithHistory = buildUserPromptWithHistory(instruction, history)
                
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
                
                // RAG control: experience summary skips RAG, all other steps use normal RAG pipeline
                val configKnowledgeBase = ConfigManager.getString(context, ConfigManager.KEY_KNOWLEDGE_BASE, "")
                val searchDepth = ConfigManager.getInt(context, ConfigManager.KEY_SEARCH_DEPTH, 5)
                val graphRagEnabled = ConfigManager.getBoolean(context, ConfigManager.KEY_GRAPH_RAG_ENABLED, false)
                
                // Experience summary: pass empty knowledgeBase to bypass RAG entirely
                // Normal steps (1~N): pass configured knowledgeBase, let RagQueryManager handle full RAG pipeline
                val effectiveKnowledgeBase = if (isExperienceSummaryStep) {
                    LogManager.logI(TAG, "[AGENT_RAG] Experience summary step: skipping RAG")
                    ""
                } else {
                    LogManager.logI(TAG, "[AGENT_RAG] Step $currentStep: using RAG pipeline, kb=$configKnowledgeBase")
                    configKnowledgeBase
                }
                
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
                try {
                    floatingWindow?.updateOutput(pureModelOutput)
                } catch (e: Exception) {
                    LogManager.logW(TAG, "Failed to update floating window output: ${e.message}")
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
     * Click at screen position
     */
    fun clickAtPosition(x: Int, y: Int): Boolean {
        // Hide floating window before click (wait for UI rendered)
        val hideLatch = java.util.concurrent.CountDownLatch(1)
        floatingWindow?.temporaryHide {
            hideLatch.countDown()
        } ?: hideLatch.countDown()
        
        if (!hideLatch.await(500, java.util.concurrent.TimeUnit.MILLISECONDS)) {
            LogManager.logW(TAG, "Timeout waiting for floating window to hide before click")
        }
        
        val path = Path().apply {
            moveTo(x.toFloat(), y.toFloat())
        }
        
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, 100))
            .build()
        
        val result = dispatchGesture(gesture, null, null)
        LogManager.logD(TAG, "Click at ($x, $y): ${if (result) "success" else "failed"}")
        
        // Show floating window after click (wait for UI rendered)
        val showLatch = java.util.concurrent.CountDownLatch(1)
        floatingWindow?.temporaryShow {
            showLatch.countDown()
        } ?: showLatch.countDown()
        
        if (!showLatch.await(500, java.util.concurrent.TimeUnit.MILLISECONDS)) {
            LogManager.logW(TAG, "Timeout waiting for floating window to show after click")
        }
        
        return result
    }
    
    /**
     * Long press at screen position
     */
    fun longPressAtPosition(x: Int, y: Int, durationMs: Long = 1000): Boolean {
        // Hide floating window before long press (wait for UI rendered)
        val hideLatch = java.util.concurrent.CountDownLatch(1)
        floatingWindow?.temporaryHide {
            hideLatch.countDown()
        } ?: hideLatch.countDown()
        
        if (!hideLatch.await(500, java.util.concurrent.TimeUnit.MILLISECONDS)) {
            LogManager.logW(TAG, "Timeout waiting for floating window to hide before long press")
        }
        
        val path = Path().apply {
            moveTo(x.toFloat(), y.toFloat())
        }
        
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, durationMs))
            .build()
        
        val result = dispatchGesture(gesture, null, null)
        LogManager.logD(TAG, "Long press at ($x, $y): ${if (result) "success" else "failed"}")
        
        // Show floating window after long press (wait for UI rendered)
        val showLatch = java.util.concurrent.CountDownLatch(1)
        floatingWindow?.temporaryShow {
            showLatch.countDown()
        } ?: showLatch.countDown()
        
        if (!showLatch.await(500, java.util.concurrent.TimeUnit.MILLISECONDS)) {
            LogManager.logW(TAG, "Timeout waiting for floating window to show after long press")
        }
        
        return result
    }
    
    /**
     * Double click at screen position
     */
    fun doubleClickAtPosition(x: Int, y: Int): Boolean {
        // Hide floating window before double click (wait for UI rendered)
        val hideLatch = java.util.concurrent.CountDownLatch(1)
        floatingWindow?.temporaryHide {
            hideLatch.countDown()
        } ?: hideLatch.countDown()
        
        if (!hideLatch.await(500, java.util.concurrent.TimeUnit.MILLISECONDS)) {
            LogManager.logW(TAG, "Timeout waiting for floating window to hide before double click")
        }
        
        val path1 = Path().apply { moveTo(x.toFloat(), y.toFloat()) }
        val path2 = Path().apply { moveTo(x.toFloat(), y.toFloat()) }
        
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path1, 0, 100))
            .addStroke(GestureDescription.StrokeDescription(path2, 150, 100))
            .build()
        
        val result = dispatchGesture(gesture, null, null)
        LogManager.logD(TAG, "Double click at ($x, $y): ${if (result) "success" else "failed"}")
        
        // Show floating window after double click (wait for UI rendered)
        val showLatch = java.util.concurrent.CountDownLatch(1)
        floatingWindow?.temporaryShow {
            showLatch.countDown()
        } ?: showLatch.countDown()
        
        if (!showLatch.await(500, java.util.concurrent.TimeUnit.MILLISECONDS)) {
            LogManager.logW(TAG, "Timeout waiting for floating window to show after double click")
        }
        
        return result
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
        
        if (focusedNode == null) {
            LogManager.logW(TAG, "No focused input field found after auto-focus attempt")
            return "No focused input field. Click the input box first, then type"
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
     */
    private fun findEditableNode(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        if (node.isEditable) {
            @Suppress("DEPRECATION")
            return AccessibilityNodeInfo.obtain(node)
        }
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
    fun swipe(startX: Int, startY: Int, endX: Int, endY: Int, durationMs: Long = 300): Boolean {
        // Hide floating window before swipe (wait for UI rendered)
        val hideLatch = java.util.concurrent.CountDownLatch(1)
        floatingWindow?.temporaryHide {
            hideLatch.countDown()
        } ?: hideLatch.countDown()
        
        if (!hideLatch.await(500, java.util.concurrent.TimeUnit.MILLISECONDS)) {
            LogManager.logW(TAG, "Timeout waiting for floating window to hide before swipe")
        }
        
        val path = Path().apply {
            moveTo(startX.toFloat(), startY.toFloat())
            lineTo(endX.toFloat(), endY.toFloat())
        }
        
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, durationMs))
            .build()
        
        val result = dispatchGesture(gesture, null, null)
        LogManager.logD(TAG, "Swipe from ($startX, $startY) to ($endX, $endY): ${if (result) "success" else "failed"}")
        
        // Show floating window after swipe (wait for UI rendered)
        val showLatch = java.util.concurrent.CountDownLatch(1)
        floatingWindow?.temporaryShow {
            showLatch.countDown()
        } ?: showLatch.countDown()
        
        if (!showLatch.await(500, java.util.concurrent.TimeUnit.MILLISECONDS)) {
            LogManager.logW(TAG, "Timeout waiting for floating window to show after swipe")
        }
        
        return result
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
     * Format: Task Goal + Previous Steps (OBSERVATION + ACTION) + Current Instruction
     * 
     * @param instruction Current task goal/instruction
     * @param history List of previous trajectory steps
     * @return Formatted user prompt with history
     */
    private fun buildUserPromptWithHistory(
        instruction: String, 
        history: List<TrajectoryStep>
    ): String {
        // Experience summary step: use instruction as-is (already a complete prompt)
        if (isExperienceSummaryStep) {
            return instruction
        }
        
        val prompt = StringBuilder()
        
        // Task goal (Chinese for better token efficiency)
        prompt.append("任务: $instruction\n\n")
        
        // Add previous steps if any (include thinking summary + action for better context)
        if (history.isNotEmpty()) {
            prompt.append("历史步骤:\n")
            history.forEach { step ->
                LogManager.logD(TAG, "[HISTORY_DEBUG] S${step.stepIndex}: thinking.len=${step.thinking.length}, rawModel.len=${step.rawModelOutput.length}, thinking.preview=${step.thinking.take(50)}")
                prompt.append("S${step.stepIndex}: ")
                
                // Include full thinking content for better context
                if (step.thinking.isNotEmpty()) {
                    prompt.append("[思考:${step.thinking}] ")
                }
                
                // Action: use rawModelOutput if available, fallback to action description
                val actionText = step.rawModelOutput.trim().ifEmpty {
                    // Fallback: generate readable action description from parsed action
                    when (val action = step.action) {
                        is AgentAction.Click -> "click(${action.x},${action.y})"
                        is AgentAction.Type -> "type(${action.text})"
                        is AgentAction.Swipe -> "swipe(${action.direction})"
                        is AgentAction.Drag -> "drag(${action.startX},${action.startY}->${action.endX},${action.endY})"
                        is AgentAction.Open -> "open(${action.appName})"
                        is AgentAction.SystemButton -> "press(${action.button})"
                        is AgentAction.Wait -> "wait"
                        is AgentAction.Terminate -> "terminate(${action.status.value})"
                        is AgentAction.Answer -> "answer(${action.text})"
                        else -> action.javaClass.simpleName
                    }
                }
                prompt.append(actionText)
                
                // Result: -> OK or -> Failed: reason
                if (step.executionResult.success) {
                    prompt.append(" -> OK\n")
                } else {
                    prompt.append(" -> Failed: ${step.executionResult.message}\n")
                }
            }
            
            // Append repetition warning after history (closer to model's attention)
            prompt.append("\n⚠注意：仔细检查以上历史步骤，如果多次执行相同/相似动作但未取得进展，必须换一种方法！\n")
            prompt.append("\n")
        }
        
        // Current instruction (Chinese)
        prompt.append("现在，根据当前截图，决定下一步操作以继续任务。\n")
        
        return prompt.toString()
    }
    
    /**
     * Save step history to conversation.md
     * Called by AgentEngine callback after each step completes
     */
    private fun saveStepToConversationMd(
        stepIndex: Int,
        thinking: String,
        action: AgentAction,
        result: ExecutionResult
    ) {
        LogManager.logI(TAG, "[AGENT_STEP_HISTORY_DEBUG] === saveStepToConversationMd CALLED ===")
        LogManager.logI(TAG, "[AGENT_STEP_HISTORY_DEBUG] stepIndex=$stepIndex, thinking.length=${thinking.length}, action=${action.javaClass.simpleName}, result.success=${result.success}")
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
            // OBSERVATION: thinking
            // ACTION: action description
            // RESULT: result message
            markdown.append("\nStep $stepIndex:\n")
            
            // OBSERVATION (thinking)
            if (thinking.isNotEmpty()) {
                markdown.append("OBSERVATION: $thinking\n")
                LogManager.logI(TAG, "[AGENT_STEP_HISTORY_DEBUG] Added OBSERVATION, length=${thinking.length}")
            }
            
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
                is AgentAction.Answer ->
                    "Answer: ${action.text}"
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
            // Step records are only kept in memory history and included in next user message's "Previous Steps"
            // Appending here would pollute the AI message block in conversation.md
            LogManager.logI(TAG, "[AGENT_STEP_HISTORY] ✅ Step $stepIndex recorded in memory (not appended to conversation.md to avoid polluting AI message)")
            
        } catch (e: Exception) {
            LogManager.logE(TAG, "[AGENT_STEP_HISTORY] ❌ Failed to save step history", e)
            e.printStackTrace()
        }
    }
    
    /**
     * Get RagQueryManager's Fragment reference for UI updates
     * RagQueryManager uses RagQueryCallback interface to communicate with Fragment
     * The callback IS the Fragment itself (RagQaFragment implements RagQueryCallback)
     */
    private fun RagQueryManager.getFragment(): RagQaFragment? {
        return try {
            // RagQueryManager has a 'callback' field which is the Fragment
            val field = this.javaClass.getDeclaredField("callback")
            field.isAccessible = true
            val callback = field.get(this)
            
            if (callback == null) {
                LogManager.logW(TAG, "[AGENT_UI] Callback is null")
                return null
            }
            
            // Check if callback is directly RagQaFragment
            if (callback is RagQaFragment) {
                LogManager.logI(TAG, "[AGENT_UI] ✅ Callback is RagQaFragment directly")
                return callback
            }
            
            // If callback is anonymous inner class (e.g., RagQaFragment$10),
            // get the outer class reference (this$0)
            try {
                val outerField = callback.javaClass.getDeclaredField("this\$0")
                outerField.isAccessible = true
                val outerInstance = outerField.get(callback)
                
                if (outerInstance is RagQaFragment) {
                    LogManager.logI(TAG, "[AGENT_UI] ✅ Got RagQaFragment from anonymous inner class (${callback.javaClass.simpleName})")
                    return outerInstance
                } else {
                    LogManager.logW(TAG, "[AGENT_UI] Outer instance is not RagQaFragment: ${outerInstance?.javaClass?.name}")
                }
            } catch (e: NoSuchFieldException) {
                LogManager.logW(TAG, "[AGENT_UI] Callback is not inner class (no this\$0 field): ${callback.javaClass.name}")
            }
            
            LogManager.logW(TAG, "[AGENT_UI] ❌ Cannot get RagQaFragment from callback: ${callback.javaClass.name}")
            null
        } catch (e: Exception) {
            LogManager.logE(TAG, "[AGENT_UI] Failed to get Fragment reference", e)
            null
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
        
        steps.forEach { step ->
            history.append("Step ${step.stepIndex}:\n")
            
            // Add <tool_call> (rawModelOutput already contains only <tool_call>)
            if (step.rawModelOutput.isNotEmpty()) {
                history.append("${step.rawModelOutput}\n")
            }
            
            // ACTION
            val actionDesc = when (val action = step.action) {
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
                is AgentAction.Answer ->
                    "Answer: ${action.text}"
                else -> action.javaClass.simpleName
            }
            history.append("ACTION: $actionDesc\n")
            
            // RESULT
            history.append("RESULT: ${if (step.executionResult.success) "Success" else "Failed"} - ${step.executionResult.message}\n")
            
            // Coordinate error if any
            if (step.coordinateError != null) {
                history.append("NOTE: ${step.coordinateError}\n")
            }
            
            history.append("\n")
        }
        
        return history.toString()
    }
    
    /**
     * Build experience summary prompt from task history
     * This is called by AgentEngine when experience summary is enabled
     */
    fun buildExperienceSummaryPromptFromHistory(taskHistory: String): String {
        return """
你是一个Agent经验总结专家。请根据以下Agent任务执行历史，总结出可复用的经验。

任务执行历史：
$taskHistory

请按以下格式总结：
1. **任务概述**：本次是什么类型的任务？任务目标和要求是什么？（放在最前面）
2. **关键操作步骤**：按顺序列出完成任务的关键步骤
3. **目标应用识别**：如何识别和打开目标应用
4. **UI元素定位规律**：如何定位关键UI元素（搜索框、按钮等的位置规律）
5. **需要避免的错误**：根据执行结果中的失败记录，总结需要避免的操作
6. **最短成功路径**（3-5步）：精简后的最优执行路径

请用简洁的语言总结，便于未来类似任务参考。不要使用<thinking>或Action:格式，直接输出总结内容。
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
     * Read task history from conversation.md
     * Extract only the essential info: task goal + each step's thinking and action
     * Skip debug tags, image references, separators, and other noise
     */
    fun readTaskHistoryFromConversationMd(): String {
        try {
            val context = applicationContext
            val chatFolderPath = ConfigManager.getString(
                context,
                ConfigManager.KEY_CURRENT_CHAT_FOLDER,
                ""
            )
            
            if (chatFolderPath.isEmpty()) {
                LogManager.logE(TAG, "[AGENT_EXP] Chat folder path is empty")
                return ""
            }
            
            val conversationFile = java.io.File(chatFolderPath, "conversation.md")
            if (!conversationFile.exists()) {
                LogManager.logE(TAG, "[AGENT_EXP] conversation.md does not exist")
                return ""
            }
            
            val allLines = conversationFile.readLines()
            LogManager.logI(TAG, "[AGENT_EXP] Total lines in conversation.md: ${allLines.size}")
            
            // Find the last Agent task start: "## 用户" followed by "![](agent_step_1_*.jpg)" or "![](agent_step_1_*.png)"
            var taskStartIndex = -1
            for (i in allLines.size - 1 downTo 0) {
                val line = allLines[i].trim()
                if (line.startsWith("## 用户") && i + 2 < allLines.size) {
                    val nextLine = allLines[i + 2].trim()
                    if (nextLine.startsWith("![](agent_step_1_") && (nextLine.endsWith(".jpg)") || nextLine.endsWith(".png)"))) {
                        taskStartIndex = i
                        LogManager.logI(TAG, "[AGENT_EXP] Found Agent task start at line $i")
                        break
                    }
                }
            }
            
            if (taskStartIndex == -1) {
                LogManager.logE(TAG, "[AGENT_EXP] Agent task start not found in conversation.md")
                return ""
            }
            
            // Extract only essential content: task goal + each step's thinking and action
            val result = StringBuilder()
            var taskGoal = ""
            var stepCount = 0
            var inDebug = false
            
            for (i in taskStartIndex until allLines.size) {
                val line = allLines[i]
                val trimmed = line.trim()
                
                // Skip debug sections
                if (trimmed.startsWith("<debug>")) { inDebug = true; continue }
                if (trimmed.startsWith("</debug>")) { inDebug = false; continue }
                if (inDebug) continue
                
                // Skip noise lines
                if (trimmed.startsWith("<!-- MESSAGE_SEPARATOR -->")) continue
                if (trimmed.startsWith("## 用户")) continue
                if (trimmed.startsWith("## AI助手")) continue
                if (trimmed.startsWith("![](agent_step_")) continue
                if (trimmed.startsWith("Previous Steps:")) continue
                if (trimmed.startsWith("Step ") && trimmed.contains("ACTION:")) continue
                if (trimmed.startsWith("ACTION:")) continue
                // Keep RESULT lines - they contain execution success/failure info
                if (trimmed.startsWith("NOTE:")) continue
                if (trimmed.isEmpty()) continue
                
                // Capture task goal (first non-noise text line after agent_step_1 image)
                if (taskGoal.isEmpty() && !trimmed.startsWith("<thinking>") && !trimmed.startsWith("<think>") && !trimmed.startsWith("Action:")) {
                    taskGoal = trimmed
                    continue
                }
                
                // Capture thinking lines
                if (trimmed.startsWith("<thinking>") || trimmed.startsWith("<think>")) {
                    stepCount++
                    // Extract thinking content
                    val thinkContent = trimmed
                        .replace(Regex("^<thinking>"), "").replace(Regex("</thinking>$"), "")
                        .replace(Regex("^<think>"), "").replace(Regex("</think>$"), "")
                        .trim()
                    result.append("Step $stepCount thinking: $thinkContent\n")
                    continue
                }
                
                // Capture Action lines
                if (trimmed.startsWith("Action:")) {
                    result.append("Step $stepCount action: $trimmed\n")
                    continue
                }
                
                // Capture RESULT lines (execution success/failure)
                if (trimmed.startsWith("RESULT:")) {
                    result.append("Step $stepCount result: $trimmed\n\n")
                    continue
                }
            }
            
            // Build final history
            val history = StringBuilder()
            if (taskGoal.isNotEmpty()) {
                history.append("Task: $taskGoal\n\n")
            }
            history.append(result)
            
            val taskHistory = history.toString().trim()
            LogManager.logI(TAG, "[AGENT_EXP] Extracted $stepCount steps, length: ${taskHistory.length}")
            LogManager.logI(TAG, "[AGENT_EXP] Task history:\n$taskHistory")
            
            return taskHistory
            
        } catch (e: Exception) {
            LogManager.logE(TAG, "[AGENT_EXP] Failed to read task history", e)
            return ""
        }
    }
    
    
    /**
     * Save experience summary to AgentKB knowledge base.
     * Reuses the same note saving flow as KnowledgeNoteFragment:
     * KnowledgeGraphDatabase + InferenceClient.computeEmbedding + NER + knowledge graph.
     * No chunking — each experience is stored as a single complete entry (same as notes).
     */
    private fun saveExperienceToAgentKB() {
        LogManager.logI(TAG, "[AGENT_EXP] User clicked save button")
        
        if (experienceSummaryContent.isEmpty()) {
            LogManager.logE(TAG, "[AGENT_EXP] Experience summary content is empty, cannot save")
            floatingWindow?.updateStatus("No content to save")
            return
        }
        
        floatingWindow?.updateStatus("Saving to AgentKB...")
        floatingWindow?.hideSaveButton()
        
        serviceScope.launch {
            try {
                val context = applicationContext
                val kbName = "AgentKB"
                
                // Use ConfigManager.getKnowledgeBasePath() — same path as all other knowledge bases
                val kbBasePath = ConfigManager.getKnowledgeBasePath(context)
                val kbDir = java.io.File(kbBasePath, kbName)
                val isNewKb = !kbDir.exists()
                if (isNewKb) {
                    LogManager.logI(TAG, "[AGENT_EXP] AgentKB does not exist, creating at: ${kbDir.absolutePath}")
                    kbDir.mkdirs()
                }
                
                // Build note title + content (same format as KnowledgeNoteFragment)
                val timestamp = java.text.SimpleDateFormat("yyyyMMdd_HHmmss", java.util.Locale.US).format(java.util.Date())
                val noteTitle = "Agent Experience $timestamp"
                val fullText = "Title: $noteTitle\n\nContent: $experienceSummaryContent"
                LogManager.logI(TAG, "[AGENT_EXP] Note fullText length: ${fullText.length}")
                
                // Get embedding model from settings
                // NOTE: getLastSelectedEmbeddingModel() returns model NAME only (e.g. "Qwen3-Embedding-0.6B-MNN-int4"),
                // must join with getEmbeddingModelPath() base dir to get absolute path
                // (same as BuildKnowledgeBaseFragment line 961)
                val embeddingModelName = ConfigManager.getLastSelectedEmbeddingModel(context)
                if (embeddingModelName.isNullOrEmpty()) {
                    LogManager.logE(TAG, "[AGENT_EXP] No embedding model configured in settings")
                    floatingWindow?.updateStatus("Save failed: no embedding model")
                    isWaitingForExperienceSave = false
                    experienceSummaryContent = ""
                    return@launch
                }
                val embeddingModelPath = ConfigManager.getEmbeddingModelPath(context) +
                    java.io.File.separator + embeddingModelName
                LogManager.logI(TAG, "[AGENT_EXP] Using embedding model: $embeddingModelPath")
                
                withContext(Dispatchers.IO) {
                    // Generate embedding vector via InferenceClient (same as KnowledgeNoteFragment)
                    RuntimeConfigUtil.pushToInference(context)
                    val client = InferenceClient.getInstance(context)
                    val contentEmbedding = client.computeEmbedding(
                        embeddingModelPath,
                        EmbeddingHandler.MemoryMode.LOW.value,
                        fullText
                    )
                    if (contentEmbedding == null || contentEmbedding.isEmpty()) {
                        throw Exception("Embedding vector is null or empty")
                    }
                    LogManager.logI(TAG, "[AGENT_EXP] Embedding generated, dimension: ${contentEmbedding.size}")
                    
                    // Open KnowledgeGraphDatabase (auto-creates knowledge_graph.db if new)
                    val dbPath = java.io.File(kbDir, "knowledge_graph.db").absolutePath
                    val vectorDb = KnowledgeGraphDatabase(context, dbPath, kbName)
                    
                    try {
                        // Always update metadata (use model NAME, not absolute path)
                        // This matches TextChunkProcessor.writeKnowledgeBaseMetadata() convention:
                        // modeldir stores the model folder name (e.g. "Qwen3-Embedding-0.6B-MNN-int4"),
                        // and RagQueryManager.resolveEmbeddingModelPath() joins it with embeddingModelRoot.
                        val rerankerModel = ConfigManager.getLastSelectedRerankerModel(context) ?: ""
                        val metadata = KnowledgeGraphDatabase.DatabaseMetadata(embeddingModelName).apply {
                            embeddingDimension = contentEmbedding.size
                            modeldir = embeddingModelName
                            if (rerankerModel.isNotEmpty()) {
                                rerankerdir = rerankerModel
                            }
                        }
                        vectorDb.updateMetadata(metadata)
                        LogManager.logI(TAG, "[AGENT_EXP] Metadata updated: embedding=$embeddingModelName, dim=${contentEmbedding.size}")
                        
                        // Add complete note to database — no chunking (same as KnowledgeNoteFragment)
                        val docId = vectorDb.addChunk(fullText, noteTitle, contentEmbedding, "")
                        val success = docId >= 0
                        LogManager.logI(TAG, "[AGENT_EXP] addChunk result: docId=$docId, success=$success")
                        
                        // NER + knowledge graph via unified API (same as KnowledgeNoteFragment)
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
                                            // Apply stopwords filter
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
                                            
                                            // Single transactional call: entities + chunk-entity links + co-occurrence edges
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
                    
                    // Write metadata.json for compatibility (same as TextChunkProcessor)
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
     * Start countdown to close floating window after save
     */
    private fun startCountdownToClose() {
        serviceScope.launch {
            for (i in 5 downTo 1) {
                floatingWindow?.updateStatus("Closing in $i...")
                kotlinx.coroutines.delay(1000)
            }
            
            // Close floating window and stop service
            stopAgentLoop()
            LogManager.logI(TAG, "[AGENT_EXP] Countdown complete, Agent service stopped")
        }
    }
    
}
