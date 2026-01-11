package com.example.offlineai.agent.ui

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView
import com.example.offlineai.LogManager
import com.example.offlineai.R

/**
 * Floating window to display Agent execution status
 * Shows real-time Agent state without interrupting the target app
 */
class AgentFloatingWindow(private val context: Context) {
    
    companion object {
        private const val TAG = "AgentFloatingWindow"
    }
    
    private val windowManager: WindowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val mainHandler = Handler(Looper.getMainLooper())
    private var floatingView: View? = null
    private var isShowing = false
    private var isMinimized = false

    private fun runOnMainThread(block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            block()
        } else {
            mainHandler.post { block() }
        }
    }
    
    // Detect Huawei/HarmonyOS devices for special handling
    private val isHuaweiDevice: Boolean = Build.MANUFACTURER.equals("HUAWEI", ignoreCase = true) ||
        Build.MANUFACTURER.equals("HONOR", ignoreCase = true) ||
        Build.BRAND.equals("HUAWEI", ignoreCase = true) ||
        Build.BRAND.equals("HONOR", ignoreCase = true)
    
    // UI components
    private var textViewTask: TextView? = null
    private var textViewStep: TextView? = null
    private var textViewStatus: TextView? = null
    private var textViewOutput: TextView? = null
    private var buttonMinimize: ImageButton? = null
    private var buttonStop: ImageButton? = null
    private var buttonSaveExperience: android.widget.Button? = null
    private var layoutExpanded: LinearLayout? = null
    private var layoutMinimized: LinearLayout? = null
    
    // Callbacks
    private var onStopClickListener: (() -> Unit)? = null
    private var onSaveExperienceClickListener: (() -> Unit)? = null
    
    // Drag support
    private var initialX = 0
    private var initialY = 0
    private var initialTouchX = 0f
    private var initialTouchY = 0f
    
    @SuppressLint("ClickableViewAccessibility")
    fun show() {
        runOnMainThread {
            showInternal()
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun showInternal() {
        if (isShowing) {
            LogManager.logD(TAG, "Floating window already showing")
            return
        }

        try {
            // Inflate layout
            val inflater = LayoutInflater.from(context)
            floatingView = inflater.inflate(R.layout.agent_floating_window, null)

            // Initialize UI components
            textViewTask = floatingView?.findViewById(R.id.textViewTask)
            textViewStep = floatingView?.findViewById(R.id.textViewStep)
            textViewStatus = floatingView?.findViewById(R.id.textViewStatus)
            textViewOutput = floatingView?.findViewById(R.id.textViewOutput)
            buttonMinimize = floatingView?.findViewById(R.id.buttonMinimize)
            buttonStop = floatingView?.findViewById(R.id.buttonStop)
            buttonSaveExperience = floatingView?.findViewById(R.id.buttonSaveExperience)
            layoutExpanded = floatingView?.findViewById(R.id.layoutExpanded)
            layoutMinimized = floatingView?.findViewById(R.id.layoutMinimized)

            // Setup window parameters
            val windowType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            } else {
                @Suppress("DEPRECATION")
                WindowManager.LayoutParams.TYPE_PHONE
            }

            val params = WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                windowType,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                    WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT
            )

            // Position at top-right corner to avoid obscuring main interaction area
            params.gravity = Gravity.TOP or Gravity.END
            params.x = 10  // Small margin from right edge
            params.y = 200  // Below status bar

            // Huawei/HarmonyOS specific: Set higher window level
            if (isHuaweiDevice) {
                // Try to use highest possible level for Huawei devices
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    params.type = WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                }
                // Add flags to ensure visibility on Huawei/HarmonyOS
                params.flags = params.flags or WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED
                LogManager.logI(TAG, "Huawei device detected, applying special window flags")
            }

            LogManager.logD(TAG, "Window params: type=${params.type}, flags=${params.flags}, gravity=${params.gravity}")

            // Add view to window
            try {
                windowManager.addView(floatingView, params)
                isShowing = true
                LogManager.logI(TAG, "Floating window added to WindowManager successfully")
            } catch (e: Exception) {
                LogManager.logE(TAG, "Failed to add floating window to WindowManager", e)
                LogManager.logE(TAG, "Window type: ${params.type}, flags: ${params.flags}")
                LogManager.logE(TAG, "Huawei device: $isHuaweiDevice, Manufacturer: ${Build.MANUFACTURER}, Brand: ${Build.BRAND}")
                throw e
            }

            // Setup button listeners
            // Disable minimize button to prevent auto-collapse
            buttonMinimize?.visibility = View.GONE

            buttonStop?.setOnClickListener {
                onStopClickListener?.invoke()
            }
            
            buttonSaveExperience?.setOnClickListener {
                onSaveExperienceClickListener?.invoke()
            }

            // Setup drag listener
            floatingView?.setOnTouchListener { _, event ->
                when (event.action) {
                    MotionEvent.ACTION_DOWN -> {
                        initialX = params.x
                        initialY = params.y
                        initialTouchX = event.rawX
                        initialTouchY = event.rawY
                        true
                    }

                    MotionEvent.ACTION_MOVE -> {
                        params.x = initialX + (event.rawX - initialTouchX).toInt()
                        params.y = initialY + (event.rawY - initialTouchY).toInt()
                        windowManager.updateViewLayout(floatingView, params)
                        true
                    }

                    else -> false
                }
            }

            // Setup minimized view click listener
            layoutMinimized?.setOnClickListener {
                toggleMinimize()
            }

            LogManager.logI(TAG, "Floating window shown")
        } catch (e: Exception) {
            LogManager.logE(TAG, "Failed to show floating window", e)
        }
    }
    
    fun hide() {
        runOnMainThread {
            hideInternal()
        }
    }

    private fun hideInternal() {
        if (!isShowing) {
            return
        }

        try {
            floatingView?.let { windowManager.removeView(it) }
            floatingView = null
            isShowing = false
            LogManager.logI(TAG, "Floating window hidden")
        } catch (e: Exception) {
            LogManager.logE(TAG, "Failed to hide floating window", e)
        }
    }
    
    /**
     * Temporarily hide floating window (keeps view instance)
     * Used to avoid overlay intercepting dispatchGesture clicks
     * @param onHidden Callback invoked after UI is actually hidden (next frame rendered)
     */
    fun temporaryHide(onHidden: (() -> Unit)? = null) {
        runOnMainThread {
            if (!isShowing || floatingView == null) {
                onHidden?.invoke()
                return@runOnMainThread
            }

            try {
                floatingView?.visibility = View.GONE
                // Wait for next frame to ensure UI is actually hidden
                floatingView?.post {
                    LogManager.logD(TAG, "Floating window temporarily hidden (UI rendered)")
                    onHidden?.invoke()
                }
            } catch (e: Exception) {
                LogManager.logE(TAG, "Failed to temporarily hide floating window", e)
                onHidden?.invoke()
            }
        }
    }
    
    /**
     * Restore floating window after temporary hide
     * @param onShown Callback invoked after UI is actually shown (next frame rendered)
     */
    fun temporaryShow(onShown: (() -> Unit)? = null) {
        runOnMainThread {
            if (!isShowing || floatingView == null) {
                onShown?.invoke()
                return@runOnMainThread
            }

            try {
                floatingView?.visibility = View.VISIBLE
                // Wait for next frame to ensure UI is actually shown
                floatingView?.post {
                    LogManager.logD(TAG, "Floating window restored (UI rendered)")
                    onShown?.invoke()
                }
            } catch (e: Exception) {
                LogManager.logE(TAG, "Failed to restore floating window", e)
                onShown?.invoke()
            }
        }
    }
    
    fun updateTask(task: String) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            textViewTask?.text = task
        } else {
            mainHandler.post { textViewTask?.text = task }
        }
    }
    
    fun updateStep(currentStep: Int) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            textViewStep?.text = "Step: $currentStep"
            layoutMinimized?.findViewById<TextView>(R.id.textViewMinimizedStep)?.text = currentStep.toString()
        } else {
            mainHandler.post {
                textViewStep?.text = "Step: $currentStep"
                layoutMinimized?.findViewById<TextView>(R.id.textViewMinimizedStep)?.text = currentStep.toString()
            }
        }
    }
    
    fun updateStatus(status: String) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            textViewStatus?.text = status
        } else {
            mainHandler.post { textViewStatus?.text = status }
        }
    }

    fun updateOutput(output: String) {
        val compact = buildCompactOutput(output)
        if (Looper.myLooper() == Looper.getMainLooper()) {
            textViewOutput?.text = compact
        } else {
            mainHandler.post { textViewOutput?.text = compact }
        }
    }

    private fun buildCompactOutput(output: String): String {
        val maxLines = 6
        val maxChars = 800

        var text = output
        if (text.length > maxChars) {
            text = text.takeLast(maxChars)
        }

        val lines = text
            .replace("\r\n", "\n")
            .replace("\r", "\n")
            .split("\n")

        val tail = if (lines.size <= maxLines) lines else lines.takeLast(maxLines)
        return tail.joinToString("\n").trim()
    }
    
    fun setOnStopClickListener(listener: () -> Unit) {
        onStopClickListener = listener
    }
    
    fun setOnSaveExperienceClickListener(listener: () -> Unit) {
        onSaveExperienceClickListener = listener
    }
    
    /**
     * Show save experience button
     * Called when experience summary is ready
     * Stop button remains visible as "cancel" option
     */
    fun showSaveButton() {
        runOnMainThread {
            buttonSaveExperience?.visibility = View.VISIBLE
            buttonStop?.visibility = View.VISIBLE  // Keep stop button as "cancel" option
            LogManager.logI(TAG, "[AGENT_EXP] Save and cancel buttons shown")
        }
    }
    
    /**
     * Hide save experience button
     * Called after save is complete
     */
    fun hideSaveButton() {
        runOnMainThread {
            buttonSaveExperience?.visibility = View.GONE
            LogManager.logI(TAG, "[AGENT_EXP] Save button hidden")
        }
    }
    
    private fun toggleMinimize() {
        runOnMainThread {
            isMinimized = !isMinimized
            if (isMinimized) {
                layoutExpanded?.visibility = View.GONE
                layoutMinimized?.visibility = View.VISIBLE
            } else {
                layoutExpanded?.visibility = View.VISIBLE
                layoutMinimized?.visibility = View.GONE
            }
            LogManager.logD(TAG, "Floating window ${if (isMinimized) "minimized" else "expanded"}")
        }
    }
}
