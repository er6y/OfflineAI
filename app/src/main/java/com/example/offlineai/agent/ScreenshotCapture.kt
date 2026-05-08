package com.example.offlineai.agent.utils

import android.app.Activity
import android.content.Context
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.DisplayMetrics
import android.view.WindowManager
import com.example.offlineai.LogManager
import com.example.offlineai.agent.ui.AgentFloatingWindow
import java.nio.ByteBuffer
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Screenshot Capture - captures screen for agent vision
 * Reuses MediaProjection from existing screenshot functionality
 */
class ScreenshotCapture(private val context: Context) {
    
    companion object {
        private const val TAG = "ScreenshotCapture"
    }
    
    private var mediaProjection: MediaProjection? = null
    private var imageReader: ImageReader? = null
    private var virtualDisplay: VirtualDisplay? = null
    
    private val screenWidth: Int
    private val screenHeight: Int
    private val screenDensity: Int
    
    // Track VirtualDisplay initialization state
    private var isVirtualDisplayReady = false
    
    // Detect emulator for special handling
    private val isEmulator: Boolean = Build.FINGERPRINT.contains("generic") ||
        Build.FINGERPRINT.contains("unknown") ||
        Build.MODEL.contains("google_sdk") ||
        Build.MODEL.contains("Emulator") ||
        Build.MODEL.contains("Android SDK") ||
        Build.MANUFACTURER.contains("Genymotion") ||
        Build.HARDWARE.contains("goldfish") ||
        Build.HARDWARE.contains("ranchu") ||
        Build.PRODUCT.contains("sdk") ||
        Build.PRODUCT.contains("vbox86p")
    
    // Track consecutive black screenshots for VirtualDisplay recreation
    private var consecutiveBlackScreens = 0
    
    init {
        val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val displayMetrics = DisplayMetrics()
        @Suppress("DEPRECATION")
        windowManager.defaultDisplay.getRealMetrics(displayMetrics)
        
        screenWidth = displayMetrics.widthPixels
        screenHeight = displayMetrics.heightPixels
        screenDensity = displayMetrics.densityDpi
        
        LogManager.logI(TAG, "Screen: ${screenWidth}x${screenHeight}, density: $screenDensity")
        LogManager.logI(TAG, "Emulator detected: $isEmulator (${Build.FINGERPRINT})")
    }
    
    /**
     * Check if MediaProjection is initialized
     */
    fun isInitialized(): Boolean {
        return mediaProjection != null
    }
    
    /**
     * Initialize MediaProjection (requires user permission)
     */
    fun initMediaProjection(resultCode: Int, data: android.content.Intent) {
        val projectionManager = context.getSystemService(Context.MEDIA_PROJECTION_SERVICE) 
            as MediaProjectionManager
        
        mediaProjection = projectionManager.getMediaProjection(resultCode, data)
        
        // Register callback (required for Android 14+)
        // NOTE: Do NOT call release() in onStop() - it's triggered when Activity finishes
        // We need MediaProjection to stay alive for Agent loop
        mediaProjection?.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() {
                // Triggered when user taps "禁止" on the system screen-recording notification,
                // or when the system revokes the projection token (e.g. screen lock policy).
                // We must clear our cached references so isInitialized() returns false and the
                // next Agent run automatically re-requests permission instead of silently failing.
                LogManager.logW(TAG, "MediaProjection stopped (likely user revoked via notification). Clearing cached refs for auto re-request on next Agent run.")
                try { virtualDisplay?.release() } catch (_: Throwable) {}
                virtualDisplay = null
                isVirtualDisplayReady = false
                try { imageReader?.close() } catch (_: Throwable) {}
                imageReader = null
                mediaProjection = null
            }
        }, Handler(Looper.getMainLooper()))
        
        LogManager.logI(TAG, "MediaProjection initialized with callback")
    }
    
    /**
     * Capture screen and return bitmap
     * @param floatingWindow Optional floating window to hide during capture
     * @param retryOnFailure Whether to retry on MediaProjection failure (default: true)
     */
    fun captureScreen(floatingWindow: AgentFloatingWindow? = null, retryOnFailure: Boolean = true): Bitmap? {
        if (mediaProjection == null) {
            LogManager.logE(TAG, "MediaProjection not initialized, cannot capture screenshot")
            
            // If retry is enabled, request permission again
            if (retryOnFailure) {
                LogManager.logW(TAG, "Requesting MediaProjection permission for retry")
                // Note: This will trigger a new permission request, Agent loop should handle this
                // For now, just return null and let caller handle retry
            }
            return null
        }
        
        // Temporarily hide floating window to avoid it appearing in screenshot
        // Use callback to wait for UI actually hidden (next frame rendered)
        val hideLatch = CountDownLatch(1)
        floatingWindow?.temporaryHide {
            hideLatch.countDown()
        } ?: hideLatch.countDown()
        
        // Wait for hide operation to complete (timeout 500ms)
        if (!hideLatch.await(500, TimeUnit.MILLISECONDS)) {
            LogManager.logW(TAG, "Timeout waiting for floating window to hide")
        }
        
        try {
            // Create ImageReader with maxImages=2 (required for proper buffering)
            if (imageReader == null) {
                imageReader = ImageReader.newInstance(
                    screenWidth, 
                    screenHeight, 
                    PixelFormat.RGBA_8888, 
                    2  // Need 2 buffers: 1 for rendering, 1 for reading
                )
            }
            
            // Create VirtualDisplay
            val isFirstCapture = virtualDisplay == null
            if (virtualDisplay == null || !isVirtualDisplayReady) {
                LogManager.logI(TAG, "Creating VirtualDisplay (first=${isFirstCapture})")
                
                // Release old VirtualDisplay if exists
                virtualDisplay?.release()
                
                // Use different flags for emulator vs real device
                val displayFlags = if (isEmulator) {
                    // Emulator: Use OWN_CONTENT_ONLY for better compatibility
                    DisplayManager.VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY or
                    DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR
                } else {
                    // Real device: Use AUTO_MIRROR only
                    DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR
                }
                
                virtualDisplay = mediaProjection?.createVirtualDisplay(
                    "AgentScreenCapture",
                    screenWidth,
                    screenHeight,
                    screenDensity,
                    displayFlags,
                    imageReader?.surface,
                    null,
                    null
                )
                
                // First capture needs longer wait for VirtualDisplay to fully render
                // Emulators need more time than real devices
                val initWaitMs = if (isEmulator) 5000L else 3000L
                LogManager.logI(TAG, "Waiting for VirtualDisplay to initialize (${initWaitMs}ms, emulator=$isEmulator)...")
                Thread.sleep(initWaitMs)
                isVirtualDisplayReady = true
                
                // Additional wait for buffer to be ready
                val bufferWaitMs = if (isEmulator) 1000L else 500L
                LogManager.logI(TAG, "Waiting for ImageReader buffer (${bufferWaitMs}ms)...")
                Thread.sleep(bufferWaitMs)
            } else {
                // Subsequent captures need wait to ensure frame is ready
                val frameWaitMs = if (isEmulator) 500L else 200L
                Thread.sleep(frameWaitMs)
            }
            
            // Retry logic for acquiring image
            var image: Image? = null
            val maxRetries = if (isFirstCapture) 5 else 3
            var retryCount = 0
            
            while (image == null && retryCount < maxRetries) {
                image = imageReader?.acquireLatestImage()
                if (image == null) {
                    retryCount++
                    if (retryCount < maxRetries) {
                        LogManager.logD(TAG, "No image available, retry $retryCount/$maxRetries")
                        Thread.sleep(300)  // Increased from 200ms
                    }
                }
            }
            
            if (image == null) {
                LogManager.logW(TAG, "No image available after $maxRetries retries")
                // Reset VirtualDisplay state to force recreation next time
                isVirtualDisplayReady = false
                return null
            }
            
            val bitmap = imageToBitmap(image)
            image.close()
            
            if (bitmap == null) {
                LogManager.logE(TAG, "Failed to convert image to bitmap")
                isVirtualDisplayReady = false
                return null
            }
            
            // Check pixel distribution for diagnostics
            val pixelInfo = analyzePixelDistribution(bitmap)
            LogManager.logI(TAG, "Screenshot captured: ${bitmap.width}x${bitmap.height}, pixels: $pixelInfo")
            
            // Check for black screen and handle VirtualDisplay recreation
            if (isBlackScreen(bitmap)) {
                consecutiveBlackScreens++
                LogManager.logW(TAG, "Black screenshot detected! (consecutive=$consecutiveBlackScreens)")
                
                // On emulator, try recreating VirtualDisplay (up to 2 times)
                if (isEmulator && consecutiveBlackScreens <= 2) {
                    LogManager.logW(TAG, "Emulator black screen - recreating VirtualDisplay (attempt $consecutiveBlackScreens/2)")
                    isVirtualDisplayReady = false
                    virtualDisplay?.release()
                    virtualDisplay = null
                    // Recursive retry (floatingWindow will be passed through)
                    return captureScreen(floatingWindow)
                } else {
                    LogManager.logE(TAG, "Black screenshot persists after retries. MediaProjection may need to be reinitialized.")
                    isVirtualDisplayReady = false
                    virtualDisplay?.release()
                    virtualDisplay = null
                    // Restore floating window before returning (wait for UI rendered)
                    val showLatch = CountDownLatch(1)
                    floatingWindow?.temporaryShow {
                        showLatch.countDown()
                    } ?: showLatch.countDown()
                    
                    if (!showLatch.await(500, TimeUnit.MILLISECONDS)) {
                        LogManager.logW(TAG, "Timeout waiting for floating window to show")
                    }
                    return null
                }
            } else {
                // Reset counter on successful capture
                consecutiveBlackScreens = 0
            }
            
            // Restore floating window before returning (wait for UI rendered)
            val showLatch = CountDownLatch(1)
            floatingWindow?.temporaryShow {
                showLatch.countDown()
            } ?: showLatch.countDown()
            
            if (!showLatch.await(500, TimeUnit.MILLISECONDS)) {
                LogManager.logW(TAG, "Timeout waiting for floating window to show")
            }
            return bitmap
            
        } catch (e: Exception) {
            LogManager.logE(TAG, "Failed to capture screenshot: ${e.message}", e)
            e.printStackTrace()
            // Restore floating window before returning (wait for UI rendered)
            val showLatch = CountDownLatch(1)
            floatingWindow?.temporaryShow {
                showLatch.countDown()
            } ?: showLatch.countDown()
            
            if (!showLatch.await(500, TimeUnit.MILLISECONDS)) {
                LogManager.logW(TAG, "Timeout waiting for floating window to show")
            }
            return null
        }
    }
    
    /**
     * Convert Image to Bitmap
     */
    private fun imageToBitmap(image: Image): Bitmap? {
        val planes = image.planes
        val buffer: ByteBuffer = planes[0].buffer
        val pixelStride = planes[0].pixelStride
        val rowStride = planes[0].rowStride
        val rowPadding = rowStride - pixelStride * screenWidth
        
        val bitmap = Bitmap.createBitmap(
            screenWidth + rowPadding / pixelStride,
            screenHeight,
            Bitmap.Config.ARGB_8888
        )
        
        bitmap.copyPixelsFromBuffer(buffer)
        
        // Crop to actual screen size if there's padding
        return if (rowPadding > 0) {
            Bitmap.createBitmap(bitmap, 0, 0, screenWidth, screenHeight)
        } else {
            bitmap
        }
    }
    
    /**
     * Analyze pixel distribution for diagnostics
     */
    private fun analyzePixelDistribution(bitmap: Bitmap): String {
        val sampleSize = 100
        var blackPixels = 0
        var darkPixels = 0
        var brightPixels = 0
        
        val step = (bitmap.width * bitmap.height) / sampleSize
        
        for (i in 0 until sampleSize) {
            val x = (i * step) % bitmap.width
            val y = (i * step) / bitmap.width
            
            if (y >= bitmap.height) break
            
            val pixel = bitmap.getPixel(x, y)
            val r = (pixel shr 16) and 0xFF
            val g = (pixel shr 8) and 0xFF
            val b = pixel and 0xFF
            
            val brightness = (r + g + b) / 3
            
            when {
                brightness < 10 -> blackPixels++
                brightness < 50 -> darkPixels++
                else -> brightPixels++
            }
        }
        
        return "black=$blackPixels, dark=$darkPixels, bright=$brightPixels (sampled=$sampleSize)"
    }
    
    /**
     * Check if bitmap is mostly black
     */
    private fun isBlackScreen(bitmap: Bitmap, threshold: Float = 0.95f): Boolean {
        val sampleSize = 100  // Sample 100 pixels
        
        var blackPixels = 0
        val step = (bitmap.width * bitmap.height) / sampleSize
        
        for (i in 0 until sampleSize) {
            val x = (i * step) % bitmap.width
            val y = (i * step) / bitmap.width
            
            if (y >= bitmap.height) break
            
            val pixel = bitmap.getPixel(x, y)
            val r = (pixel shr 16) and 0xFF
            val g = (pixel shr 8) and 0xFF
            val b = pixel and 0xFF
            
            // Consider pixel black if RGB values are all < 10
            if (r < 10 && g < 10 && b < 10) {
                blackPixels++
            }
        }
        
        val blackRatio = blackPixels.toFloat() / sampleSize
        val isBlack = blackRatio >= threshold
        
        if (isBlack) {
            LogManager.logW(TAG, "Black screen detected: ${(blackRatio * 100).toInt()}% black pixels")
        }
        
        return isBlack
    }
    
    /**
     * Release resources
     */
    fun release() {
        virtualDisplay?.release()
        virtualDisplay = null
        isVirtualDisplayReady = false
        
        imageReader?.close()
        imageReader = null
        
        mediaProjection?.stop()
        mediaProjection = null
        
        LogManager.logI(TAG, "Screenshot capture released")
    }
}
